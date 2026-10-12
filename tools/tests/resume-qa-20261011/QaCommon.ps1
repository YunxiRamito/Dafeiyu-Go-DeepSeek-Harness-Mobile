#requires -Version 7.0
Set-StrictMode -Version Latest

function Assert-Qa([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Get-QaProject {
    return [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
}

function Assert-QaOutputPath([string]$Path) {
    $root = [IO.Path]::GetFullPath($PSScriptRoot)
    $resolved = [IO.Path]::GetFullPath($Path)
    Assert-Qa ($resolved.StartsWith($root + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) 'Output must remain inside exclusive QA directory'
    return $resolved
}

function Get-QaHashes([string]$Root) {
    return @(Get-ChildItem -LiteralPath $Root -File -Recurse | Sort-Object FullName | ForEach-Object {
        [ordered]@{
            path = [IO.Path]::GetRelativePath($Root, $_.FullName).Replace('\', '/')
            bytes = $_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    })
}

function Get-QaPreparationHashes {
    $files = @(Get-ChildItem -LiteralPath $PSScriptRoot -File | Where-Object {
        $_.Extension -in @('.ps1', '.kt', '.md', '.xml') -or $_.Name -eq 'fixture-manifest.json'
    }) + @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'fixtures') -File -Recurse)
    return @($files | Sort-Object FullName | ForEach-Object {
        [ordered]@{
            path = [IO.Path]::GetRelativePath($PSScriptRoot, $_.FullName).Replace('\', '/')
            bytes = $_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    })
}

function Test-QaFixture([string]$Root) {
    $files = @(Get-ChildItem -LiteralPath $Root -File -Recurse)
    Assert-Qa ($files.Count -eq 6) 'Fixture must contain index, empty tasks and exactly two text outputs'
    Assert-Qa (($files | Measure-Object Length -Sum).Sum -le 512KB) 'Fixture exceeds 512 KiB QA memory budget'
    $index = Get-Content -LiteralPath (Join-Path $Root 'workspace-index.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
    Assert-Qa ($index.version -eq 2) 'Expected workspace schema v2'
    Assert-Qa ($index.selectedConversationId -eq 'qa-activity-019') 'Wrong fixture selection'
    Assert-Qa ($index.workspaces.Count -eq 2 -and $index.conversations.Count -eq 3) 'Unexpected synthetic population'
    Assert-Qa (-not (@($index.workspaces | Where-Object { $_.ContainsKey('uri') }).Count)) 'External workspace URI forbidden'
    $snapshot = $index.messages['qa-activity-019'][1].streamSnapshot
    Assert-Qa ($snapshot.schema -eq 1 -and $snapshot.isFinal -and $snapshot.runStatus -ceq 'COMPLETED') 'Completed snapshot required'
    Assert-Qa ($snapshot.runRounds -eq 2 -and $snapshot.runSteps -eq 13 -and $snapshot.runElapsedMillis -eq 125000) 'Synthetic metrics changed'
    Assert-Qa ($snapshot.usage.totalTokens -eq 777777 -and $snapshot.toolCalls.Count -eq 13) 'Fixture usage/activity mismatch'
    Assert-Qa ($snapshot.messages[-1].blocks[0].text.StartsWith('QA_FINAL_REPLY_019')) 'Final sentinel missing'
    Assert-Qa (-not $snapshot.messages[-1].blocks[0].text.Contains('QA_EARLIER_ROUND')) 'Earlier text leaks into final round'
    $tasks = Get-Content -LiteralPath (Join-Path $Root 'tasks.json') -Raw | ConvertFrom-Json
    Assert-Qa ($tasks.version -eq 1 -and $tasks.tasks.Count -eq 0) 'Task queue must be empty'
    foreach ($call in @($snapshot.toolCalls | Where-Object name -eq 'write_text_file')) {
        $result = $call.result | ConvertFrom-Json
        $id = $result.data.output_id
        Assert-Qa ($id -match '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') 'Invalid output UUID'
        $output = Join-Path $Root "official-tool-outputs/$id"
        $metadata = Get-Content -LiteralPath (Join-Path $output 'metadata.json') -Raw | ConvertFrom-Json
        $bytes = [IO.File]::ReadAllBytes((Join-Path $output 'content.txt'))
        $text = [Text.UTF8Encoding]::new($false, $true).GetString($bytes)
        $arguments = $call.arguments | ConvertFrom-Json
        Assert-Qa ($bytes.Length -le 64KB -and $bytes.Length -eq $metadata.size_bytes) 'Output byte budget/metadata mismatch'
        Assert-Qa ($text -ceq $arguments.text -and $metadata.filename -ceq $arguments.filename) 'Output content mismatch'
        Assert-Qa ($metadata.version -eq 1 -and $metadata.id -eq $id -and $metadata.conversation_id -eq 'qa-activity-019') 'Output ownership mismatch'
    }
    $long = $index.messages['qa-long-019']
    Assert-Qa ($long.Count -eq 40 -and $long[0].text.StartsWith('QA_LONG_FIRST_019')) 'Long-chat first sentinel missing'
    Assert-Qa ($long[-1].text.EndsWith('QA_LONG_LAST_019')) 'Long-chat last sentinel missing'
    foreach ($conversation in $index.messages.Values) {
        foreach ($message in $conversation) {
            Assert-Qa ($message.text.Length -le 8192) 'Per-message budget exceeds 8192 characters'
            Assert-Qa (-not $message.ContainsKey('attachmentIds')) 'Fixture must not refer to user attachments'
        }
    }
    return $index
}

function Get-QaNode([xml]$Document, [string]$Label, [switch]$Contains) {
    $nodes = @($Document.SelectNodes('//node') | Where-Object {
        if ($Contains) { $_.GetAttribute('text').Contains($Label) -or $_.GetAttribute('content-desc').Contains($Label) }
        else { $_.GetAttribute('text') -ceq $Label -or $_.GetAttribute('content-desc') -ceq $Label }
    })
    if ($nodes.Count -gt 0) { return $nodes[0] }
    return $null
}

function Get-QaBounds($Node) {
    Assert-Qa ($null -ne $Node) 'Node missing'
    $match = [regex]::Match($Node.GetAttribute('bounds'), '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    Assert-Qa $match.Success 'Invalid UI bounds'
    $b = @(1..4 | ForEach-Object { [int]$match.Groups[$_].Value })
    Assert-Qa ($b[2] -gt $b[0] -and $b[3] -gt $b[1]) 'Empty UI bounds'
    return ,$b
}

function Get-QaComposer([xml]$Document) {
    $node = Get-QaNode $Document '消息输入框'
    Assert-Qa ($null -ne $node) 'Composer missing'
    $editable = $node.SelectSingleNode('ancestor-or-self::node[@class="android.widget.EditText"][1]')
    if (-not $editable) { $editable = $node.SelectSingleNode('.//node[@class="android.widget.EditText"]') }
    Assert-Qa ($null -ne $editable) 'No editable composer; coordinate fallback forbidden'
    return $editable
}

function Get-QaCompleted019Metrics {
    return @('模型轮数 2', '实际执行工具步骤 13', '累计用量 777777 token',
        '耗时 125.0 秒', '任务进度未知，未提供任务总量')
}

function Assert-QaRunInfo019([xml]$Document) {
    $nodes = @($Document.SelectNodes('//node') | Where-Object {
        $_.GetAttribute('content-desc').Contains('模型轮数 ')
    })
    Assert-Qa ($nodes.Count -eq 1) 'Expected one current 0.1.19 run-info accessibility node'
    $fields = $nodes[0].GetAttribute('content-desc') -split '，'
    foreach ($field in @('已完成', '模型轮数 2', '实际执行工具步骤 13',
        '累计用量 777777 token', '耗时 125.0 秒', '任务进度未知', '未提供任务总量')) {
        Assert-Qa ($fields -ccontains $field) "Current run-info field missing or drifted: $field"
    }
    Assert-Qa ($nodes[0].GetAttribute('scrollable') -ne 'true' -and
        $nodes[0].GetAttribute('class') -ne 'android.widget.HorizontalScrollView') 'Current FlowRow must not be a scroll strip'
    $bounds = Get-QaBounds $nodes[0]
    $composer = Get-QaBounds (Get-QaComposer $Document)
    Assert-Qa ($bounds[3] -le $composer[1]) 'Run-info overlaps editable composer'
}

function Get-QaAppCredentialPaths {
    return @('no_backup/.deepseek_api_key.v1.bin',
        'no_backup/.deepseek_api_key.v1.bin.bak', 'no_backup/.deepseek_api_key.v1.bin.new')
}

function Get-QaAppCredentialState([scriptblock]$Exists) {
    return @(Get-QaAppCredentialPaths | ForEach-Object {
        [pscustomobject]@{ path = $_; present = [bool](& $Exists $_) }
    })
}

function Assert-QaAppCredentialsAbsent($State) {
    $expected = @(Get-QaAppCredentialPaths)
    Assert-Qa (@($State).Count -eq $expected.Count) 'Incomplete App credential existence check'
    foreach ($path in $expected) {
        $entry = @($State | Where-Object { $_.path -ceq $path })
        Assert-Qa ($entry.Count -eq 1 -and $entry[0].present -is [bool]) 'Invalid App credential existence result'
        Assert-Qa (-not $entry[0].present) "App credential family present: $path; use a fresh emulator; do not read or remove credentials"
    }
}

function Assert-QaAppUnconfiguredUi([xml]$Document) {
    foreach ($label in @('DeepSeek', '模型目录', '模型提供商顺序', 'API Key', '安全保存')) {
        Assert-Qa ($null -ne (Get-QaNode $Document $label)) "App configuration UI unknown: missing $label; use a fresh emulator"
    }
    foreach ($label in @('已加密保存', '清除 Key', '获取模型', '读取中…')) {
        Assert-Qa ($null -eq (Get-QaNode $Document $label)) "App configured or busy: $label; use a fresh emulator"
    }
    $save = Get-QaNode $Document '安全保存'
    Assert-Qa ($null -ne $save.SelectSingleNode('ancestor-or-self::node[@enabled="false"][1]')) 'App key input is not empty/disabled; use a fresh emulator'
    $inputs = @($Document.SelectNodes('//node[@class="android.widget.EditText"]'))
    Assert-Qa ($inputs.Count -eq 1 -and $inputs[0].GetAttribute('text') -ceq '' -and
        $inputs[0].GetAttribute('password') -ceq 'true') 'App key input unknown/nonempty; do not read credentials; use a fresh emulator'
}

function Assert-QaChat([xml]$Document, [string]$Title, [string[]]$Metrics = @()) {
    Assert-Qa ($null -eq (Get-QaNode $Document '关闭侧栏')) 'Scene drift: sidebar is open'
    Assert-Qa ($null -ne (Get-QaNode $Document '打开菜单')) 'Scene drift: chat navigation missing'
    Assert-Qa ($null -ne (Get-QaNode $Document $Title)) 'Scene drift: wrong conversation'
    $null = Get-QaComposer $Document
    Assert-Qa ($null -eq (Get-QaNode $Document '停止生成')) 'Scene drift: unexpected generation'
    foreach ($sentinel in $Metrics) {
        Assert-Qa ($null -ne (Get-QaNode $Document $sentinel -Contains)) "Scene drift: metric missing: $sentinel"
    }
    if ($Metrics -ccontains '模型轮数 2') { Assert-QaRunInfo019 $Document }
}

function Assert-QaSidebar([xml]$Document) {
    Assert-Qa ($null -ne (Get-QaNode $Document '关闭侧栏')) 'Sidebar scene required'
    Assert-Qa ($null -ne (Get-QaNode $Document '工作目录')) 'Sidebar workspace list missing'
    Assert-Qa ($null -eq (Get-QaNode $Document '停止生成')) 'Unexpected generation in sidebar'
}

function Get-QaChatSurface([xml]$Document) {
    $composer = Get-QaBounds (Get-QaComposer $Document)
    $candidates = @($Document.SelectNodes('//node[@scrollable="true"]') | Where-Object {
        $_.GetAttribute('class') -ne 'android.widget.HorizontalScrollView' -and
        $_.GetAttribute('content-desc') -notlike '*Tokens*' -and
        $_.GetAttribute('content-desc') -notlike '*模型轮数 *' -and
        $null -eq $_.SelectSingleNode('.//node[contains(@content-desc,"Tokens") or contains(@content-desc,"模型轮数 ")]')
    } | Where-Object {
        # Only nested offscreen candidates may be empty; a broken list root still fails.
        $ancestor = $_.SelectSingleNode('ancestor::node[@scrollable="true"][1]')
        if ($_.GetAttribute('bounds') -ceq '[0,0][0,0]' -and $null -ne $ancestor) {
            $null = Get-QaBounds $ancestor
            return $false
        }
        $b = Get-QaBounds $_
        if ($null -ne $ancestor) {
            $outer = Get-QaBounds $ancestor
            if ($b[2] -le $outer[0] -or $b[0] -ge $outer[2] -or
                $b[3] -le $outer[1] -or $b[1] -ge $outer[3]) { return $false }
        }
        $b[3] -le $composer[1] -and ($b[3] - $b[1]) -ge 120
    } | Sort-Object { $b = Get-QaBounds $_; -($b[3] - $b[1]) })
    if ($candidates.Count -gt 0) { return $candidates[0] }
    return $null
}

function Test-QaNodeInSurface($Node, $Surface) {
    $visible = Get-QaBounds $Surface
    if ($Node.GetAttribute('bounds') -ceq '[0,0][0,0]') { return $false }
    $b = Get-QaBounds $Node
    $x = [int](($b[0] + $b[2]) / 2)
    $y = [int](($b[1] + $b[3]) / 2)
    $ancestor = $Node
    while ($null -ne $ancestor -and $ancestor -ne $Surface) {
        if ($ancestor.GetAttribute('bounds') -ceq '[0,0][0,0]') { return $false }
        $clip = Get-QaBounds $ancestor
        $visible = @([Math]::Max($visible[0], $clip[0]), [Math]::Max($visible[1], $clip[1]),
            [Math]::Min($visible[2], $clip[2]), [Math]::Min($visible[3], $clip[3]))
        $ancestor = $ancestor.ParentNode
    }
    return $ancestor -eq $Surface -and $x -ge $visible[0] -and $x -lt $visible[2] -and
        $y -ge $visible[1] -and $y -lt $visible[3]
}

function Get-QaVisibleChatNode([xml]$Document, [string]$Label, [switch]$Contains) {
    $surface = Get-QaChatSurface $Document
    if ($null -eq $surface) { return $null }
    foreach ($node in $surface.SelectNodes('.//node')) {
        $matches = if ($Contains) {
            $node.GetAttribute('text').Contains($Label) -or $node.GetAttribute('content-desc').Contains($Label)
        } else {
            $node.GetAttribute('text') -ceq $Label -or $node.GetAttribute('content-desc') -ceq $Label
        }
        if ($matches -and (Test-QaNodeInSurface $node $surface)) {
            Assert-QaTap $node
            return $node
        }
    }
    return $null
}

function Assert-QaTap($Node) {
    Assert-Qa ($null -ne $Node) 'Cannot tap missing node'
    Assert-Qa ($Node.GetAttribute('enabled') -ne 'false') 'QA action is disabled'
    Assert-Qa ($null -eq $Node.SelectSingleNode('ancestor-or-self::node[@content-desc="发送消息" or @content-desc="停止生成"]')) 'QA may not send or stop generation'
    $null = Get-QaBounds $Node
}

function Get-QaVersionBinding([string]$ExpectedVersion = '0.1.19', [int]$ExpectedVersionCode = 20) {
    Assert-Qa ($ExpectedVersion -cin @('0.1.19', '0.1.20')) 'Only reviewed versions 0.1.19 or 0.1.20 are supported'
    if ($ExpectedVersion -ceq '0.1.19') {
        Assert-Qa ($ExpectedVersionCode -eq 20) '0.1.19 versionCode must be 20'
    } else {
        Assert-Qa ($ExpectedVersionCode -gt 20) '0.1.20 requires an explicit newer versionCode; no inferred build binding'
    }
    return [pscustomobject]@{ version = $ExpectedVersion; version_code = $ExpectedVersionCode }
}

function Assert-QaApkBadging([string[]]$Badging, [string]$ExpectedVersion = '0.1.19', [int]$ExpectedVersionCode = 20) {
    $binding = Get-QaVersionBinding $ExpectedVersion $ExpectedVersionCode
    $lines = @($Badging | Where-Object { $_ -like 'package:*' })
    Assert-Qa ($lines.Count -eq 1) 'Final APK package/version mismatch'
    $match = [regex]::Match($lines[0], "^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'(?:\s|$)")
    Assert-Qa ($match.Success -and $match.Groups[1].Value -ceq 'com.dafeiyu.mobile.preview' -and
        $match.Groups[2].Value -ceq [string]$binding.version_code -and
        $match.Groups[3].Value -ceq $binding.version) 'Final APK package/version mismatch'
    return $lines[0]
}

function Assert-QaInstalledMetadata([string]$Dump, [string]$Hash, [string]$ExpectedHash,
    [string]$ExpectedVersion = '0.1.19', [int]$ExpectedVersionCode = 20) {
    $binding = Get-QaVersionBinding $ExpectedVersion $ExpectedVersionCode
    Assert-Qa ($ExpectedHash -match '^[a-fA-F0-9]{64}$') 'Final APK SHA256 required'
    Assert-Qa ($Dump -match ('(?m)^\s*versionName=' + [regex]::Escape($binding.version) + '\s*$')) `
        "Installed version must be $($binding.version)"
    Assert-Qa ($Dump -match ('(?m)^\s*versionCode=' + $binding.version_code + '(?:\s|$)')) `
        "Installed versionCode must be $($binding.version_code)"
    Assert-Qa ($Dump -match '(?m)^\s*flags=\[.*DEBUGGABLE') 'Debuggable APK required'
    Assert-Qa ($Hash -match '^[a-fA-F0-9]{64}$' -and $Hash -ieq $ExpectedHash) 'Installed APK differs from final artifact'
}

function Save-QaState($Items, [scriptblock]$Exists, [scriptblock]$Copy, [scriptblock]$Compare) {
    $records = @()
    foreach ($item in $Items) {
        $present = [bool](& $Exists $item.Path)
        if ($present) {
            & $Copy $item.Path $item.Backup
            & $Compare $item.Backup $item.Path
        }
        $records += [pscustomobject]@{ Path = $item.Path; Backup = $item.Backup; Present = $present }
    }
    return ,$records
}

function Restore-QaState($Records, [scriptblock]$Remove, [scriptblock]$Copy, [scriptblock]$Compare, [scriptblock]$Exists) {
    $errors = [Collections.Generic.List[string]]::new()
    foreach ($record in $Records) {
        try {
            & $Remove $record.Path
            if ($record.Present) {
                & $Copy $record.Backup $record.Path
                & $Compare $record.Backup $record.Path
            } else { Assert-Qa (-not (& $Exists $record.Path)) "Original absence not restored: $($record.Path)" }
        } catch { $errors.Add("$($record.Path): $($_.Exception.Message)") }
    }
    Assert-Qa ($errors.Count -eq 0) "RESTORE FAILED; KEEP BACKUP: $($errors -join '; ')"
}
