#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'QaCommon.ps1')
$root = Assert-QaOutputPath (Join-Path $PSScriptRoot ('runs/offline-' + [Guid]::NewGuid().ToString('N')))
New-Item -ItemType Directory -Path $root -Force | Out-Null
$results = [Collections.Generic.List[object]]::new()
function Check([string]$Name, [scriptblock]$Action) {
    try {
        & $Action
        $results.Add(@{ name = $Name; status = 'PASS' })
        Write-Output "PASS $Name"
    } catch {
        $results.Add(@{ name = $Name; status = 'FAIL'; error = $_.Exception.Message })
        Write-Output "FAIL $Name`: $($_.Exception.Message)"
    }
}
function Reject([scriptblock]$Action, [string]$Expected) {
    $rejected = $false
    try { & $Action } catch {
        Assert-Qa ($_.Exception.Message.Contains($Expected)) "Unexpected rejection: $($_.Exception.Message)"
        $rejected = $true
    }
    Assert-Qa $rejected "Expected rejection: $Expected"
}
$legacy = Join-Path (Get-QaProject) '../Temp_Android/qa/emulator-0.1.18'
$frames = Join-Path $legacy 'activity-018-11b0f8b60fc84e3384dd5b23e1b4e6af'
$good = [xml](Get-Content -LiteralPath (Join-Path $frames 'completed-collapsed-True-0.xml') -Raw -Encoding utf8)
$sidebar = [xml](Get-Content -LiteralPath (Join-Path $frames 'grouped-expanded-True-2.xml') -Raw -Encoding utf8)
$running = [xml](Get-Content -LiteralPath (Join-Path $frames 'grouped-expanded-True-3.xml') -Raw -Encoding utf8)
$changed = [xml](Get-Content -LiteralPath (Join-Path $frames 'grouped-expanded-True-7.xml') -Raw -Encoding utf8)
$title = 'QA 018 grouped activities'
$metrics = @('轮数 2', '工具步骤 13', 'Tokens 777777', '耗时 125.0 s')
$current = [xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot 'offline-current-run-info.xml') -Raw -Encoding utf8)
$asts = @{}
Check 'all exclusive PowerShell entry points parse without execution' {
    foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File) {
        $tokens = $null; $errors = $null
        $asts[$file.Name] = [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors)
        Assert-Qa (@($errors).Count -eq 0) "$($file.Name): $($errors -join '; ')"
    }
}
Check 'bounded 0.1.19 synthetic fixture/schema/content references' {
    $null = Test-QaFixture (Join-Path $PSScriptRoot 'fixtures')
}
Check 'fixture SHA256 manifest binds all six files' {
    $manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'fixture-manifest.json') -Raw | ConvertFrom-Json
    $hashes = Get-QaHashes (Join-Path $PSScriptRoot 'fixtures')
    Assert-Qa (($hashes | ConvertTo-Json -Compress) -ceq ($manifest.fixture | ConvertTo-Json -Compress)) 'Fixture hash drift'
}
Check 'current 0.1.19 merged accessibility contract and actual chat selector' {
    Assert-QaChat $current 'QA 019 grouped activities' (Get-QaCompleted019Metrics)
    Assert-Qa ((Get-QaChatSurface $current).GetAttribute('bounds') -ceq '[0,60][320,430]') 'Wrong current chat surface'
    $runner = $asts['VerifyActivity019.ps1'].Extent.Text
    Assert-Qa ($runner.Contains('$metrics = Get-QaCompleted019Metrics') -and
        -not $runner.Contains('Tokens 777777') -and -not $runner.Contains('耗时 125.0 s')) '019 runner retains old metrics baseline'
    $source = Get-Content -LiteralPath (Join-Path (Get-QaProject) 'app/src/main/java/com/dafeiyu/mobile/ui/ChatRunInfoBar.kt') -Raw
    foreach ($text in @('模型轮数 ', '实际执行工具步骤 ', '累计用量 ', '耗时 ',
        '任务进度未知，未提供任务总量', 'FlowRow(', 'clearAndSetSemantics { contentDescription = description }')) {
        Assert-Qa ($source.Contains($text)) "Current source accessibility/layout contract missing: $text"
    }
    Assert-Qa (-not $source.Contains('horizontalScroll')) 'Old scroll strip contract returned'
}
Check '019 rejects old metrics and substring values 20 rounds/130 steps' {
    $old = [xml]$current.OuterXml
    $node = $old.SelectSingleNode('//node[contains(@content-desc,"模型轮数 ")]')
    $node.SetAttribute('content-desc', '已完成，轮数 2，工具步骤 13，Tokens 777777，耗时 125.0 s')
    Reject { Assert-QaRunInfo019 $old } 'Expected one current'
    foreach ($replacement in @(@('模型轮数 2', '模型轮数 20'), @('实际执行工具步骤 13', '实际执行工具步骤 130'))) {
        $drift = [xml]$current.OuterXml
        $node = $drift.SelectSingleNode('//node[contains(@content-desc,"模型轮数 ")]')
        $node.SetAttribute('content-desc', $node.GetAttribute('content-desc').Replace($replacement[0], $replacement[1]))
        Reject { Assert-QaRunInfo019 $drift } 'field missing or drifted'
    }
}
Check 'current run-info cannot be selected as chat or overlap composer' {
    $doc = [xml]$current.OuterXml
    (Get-QaChatSurface $doc).SetAttribute('scrollable', 'false')
    $info = $doc.SelectSingleNode('//node[contains(@content-desc,"模型轮数 ")]')
    $info.SetAttribute('scrollable', 'true')
    $info.SetAttribute('bounds', '[0,350][320,510]')
    Assert-Qa ($null -eq (Get-QaChatSurface $doc)) 'Metrics selected as chat surface'
    $info.SetAttribute('scrollable', 'false')
    $info.SetAttribute('bounds', '[0,440][320,550]')
    Reject { Assert-QaRunInfo019 $doc } 'overlaps'
}
Check 'App-only credential family absent/present/error checks fail closed without reading payload' {
    $absent = { param($path) return $false }
    $state = Get-QaAppCredentialState $absent
    Assert-QaAppCredentialsAbsent $state
    foreach ($presentPath in Get-QaAppCredentialPaths) {
        $probe = { param($path) return $path -ceq $presentPath }
        Reject { Assert-QaAppCredentialsAbsent (Get-QaAppCredentialState $probe) } 'App credential family present'
    }
    Reject { Get-QaAppCredentialState { param($path) throw 'existence unknown' } } 'existence unknown'
    Reject { Assert-QaAppCredentialsAbsent @() } 'Incomplete'
    $store = Get-Content -LiteralPath (Join-Path (Get-QaProject) 'app/src/main/java/com/dafeiyu/mobile/data/BalanceStore.kt') -Raw
    Assert-Qa ($store.Contains('CREDENTIAL_FILE_NAME = ".deepseek_api_key.v1.bin"') -and
        $store.Contains('appContext.noBackupFilesDir')) 'Credential path contract changed'
}
Check 'saved App Models UI gate accepts only empty unconfigured form and rejects saved/unknown UI' {
    $doc = [xml]('<hierarchy><node text="DeepSeek"/><node text="模型目录"/><node text="模型提供商顺序"/>' +
        '<node text="API Key"/><node class="android.widget.EditText" password="true" text=""/>' +
        '<node enabled="false"><node text="安全保存"/></node></hierarchy>')
    Assert-QaAppUnconfiguredUi $doc
    $saved = [xml]$doc.OuterXml
    $flag = $saved.CreateElement('node'); $flag.SetAttribute('text', '已加密保存')
    $null = $saved.DocumentElement.AppendChild($flag)
    Reject { Assert-QaAppUnconfiguredUi $saved } 'App configured or busy'
    Reject { Assert-QaAppUnconfiguredUi ([xml]'<hierarchy/>') } 'App configuration UI unknown'
    $doc.SelectSingleNode('//node[@class="android.widget.EditText"]').SetAttribute('text', 'synthetic')
    Reject { Assert-QaAppUnconfiguredUi $doc } 'input unknown/nonempty'
}
Check 'saved 0.1.18 good chat selects actual vertical surface' {
    Assert-QaChat $good $title $metrics
    Assert-Qa ((Get-QaChatSurface $good).GetAttribute('bounds') -ceq '[0,231][1080,1848]') 'Wrong chat bounds'
}
Check 'recorded failure frame is Sidebar and rejected by chat guard' {
    Assert-QaSidebar $sidebar
    Reject { Assert-QaChat $sidebar $title $metrics } 'sidebar is open'
    Reject { Get-QaChatSurface $sidebar } 'Composer missing'
    $result = Get-Content -LiteralPath (Join-Path $frames 'result.txt') -Raw
    Assert-Qa ($result.Contains('FAIL: No chat scroll surface') -and $result.Contains('RESTORED')) 'Historical fail/restore evidence missing'
}
Check 'saved later generation and metric drift fail closed' {
    Reject { Assert-QaChat $running $title $metrics } 'unexpected generation'
    Reject { Assert-QaChat $changed $title $metrics } 'metric missing'
}
Check 'chat selectors reject horizontal-only and missing composer' {
    $copy = [xml]$good.OuterXml
    (Get-QaChatSurface $copy).SetAttribute('scrollable', 'false')
    Assert-Qa ($null -eq (Get-QaChatSurface $copy)) 'Horizontal metrics selected as vertical chat'
    (Get-QaNode $copy '消息输入框').SetAttribute('content-desc', 'removed')
    Reject { Get-QaChatSurface $copy } 'Composer missing'
}
Check 'send, stop and descendants rejected without ADB input' {
    foreach ($label in @('发送消息', '停止生成')) {
        $doc = [xml]("<hierarchy><node content-desc='$label' enabled='true' bounds='[0,0][100,100]'><node enabled='true' bounds='[0,0][20,20]'/></node></hierarchy>")
        Reject { Assert-QaTap $doc.hierarchy.node } 'may not send'
        Reject { Assert-QaTap $doc.hierarchy.node.node } 'may not send'
        $doc.hierarchy.node.SetAttribute('enabled', 'false')
        Reject { Assert-QaTap $doc.hierarchy.node } 'disabled'
    }
}
# Import interaction helpers only; ADB is replaced by a counter and Capture by a saved-frame queue.
foreach ($name in @('Tap', 'Swipe', 'Find', 'QaCaptureChat', 'QaChatSurfaceFingerprint', 'QaDiagnosticRightPaddingX')) {
    $definition = $asts['VerifyActivity019.ps1'].Find({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true)
    Assert-Qa ($null -ne $definition) "Missing helper $name"
    . ([scriptblock]::Create($definition.Extent.Text))
}
$calls = [Collections.Generic.List[string]]::new()
$queue = [Collections.Generic.Queue[xml]]::new()
$captureNames = [Collections.Generic.List[string]]::new()
$report = [Collections.Generic.List[string]]::new()
$resultRoot = $root
function Adb([string[]]$Arguments) { $calls.Add($Arguments -join ' ') }
function Capture([string]$Name) {
    $captureNames.Add($Name)
    Assert-Qa ($queue.Count -gt 0) 'Mock capture queue exhausted'
    return $queue.Dequeue()
}
Check 'present label returns without input; sidebar drift causes no input' {
    $calls.Clear()
    $queue.Enqueue($good)
    $null = Find 'QA_FINAL_REPLY_018' 'offline-found' $false -Contains
    $queue.Enqueue($sidebar)
    Reject { Find 'missing' 'offline-sidebar' } 'sidebar is open'
    Assert-Qa ($calls.Count -eq 0) 'Unexpected input'
}
Check 'three missing surfaces stop with no gesture; transient recapture recovers' {
    $noSurface = [xml]$good.OuterXml
    (Get-QaChatSurface $noSurface).SetAttribute('scrollable', 'false')
    1..3 | ForEach-Object { $queue.Enqueue($noSurface) }
    Reject { Find 'missing' 'offline-absent' } 'after 3 captures'
    (Get-QaNode $noSurface 'QA_FINAL_REPLY_018' -Contains).SetAttribute('text', 'temporary')
    $queue.Enqueue($noSurface); $queue.Enqueue($noSurface); $queue.Enqueue($good)
    $null = Find 'QA_FINAL_REPLY_018' 'offline-transient' $false -Contains
    Assert-Qa ($calls.Count -eq 0 -and $queue.Count -eq 0) 'Recapture unexpectedly used input'
}
Check 'reverse layout earlier/later swipes remain inside actual chat bounds' {
    $calls.Clear()
    Swipe $good $true
    Swipe $good $false
    Assert-Qa ($calls.Count -eq 2 -and $calls[0] -ceq 'shell input swipe 540 554 540 1525 400' -and
        $calls[1] -ceq 'shell input swipe 540 1525 540 554 400') 'Unexpected gesture direction/geometry'
}
$failed019Root = Join-Path $PSScriptRoot 'runs/activity-019-b2e24879535048608476476508124a89'
$expanded019 = [xml](Get-Content -LiteralPath (Join-Path $failed019Root '002-expanded-receipts-True-0.xml') -Raw -Encoding utf8)
$later019 = [xml](Get-Content -LiteralPath (Join-Path $failed019Root '018-expanded-receipts-True-16.xml') -Raw -Encoding utf8)
$partial019 = [xml](Get-Content -LiteralPath (Join-Path $failed019Root '019-expanded-receipts-True-17.xml') -Raw -Encoding utf8)
$title = 'QA 019 grouped activities'
$metrics = Get-QaCompleted019Metrics
Check 'actual partial 019 dump requires a fresh complete scene; no gesture during recapture' {
    $calls.Clear(); $captureNames.Clear(); $queue.Clear(); $report.Clear()
    Reject { Assert-QaChat $partial019 $title $metrics } 'chat navigation missing'
    $queue.Enqueue($partial019); $queue.Enqueue($expanded019)
    $doc = QaCaptureChat 'offline-partial'
    Assert-QaChat $doc $title $metrics
    Assert-Qa ($doc.OuterXml -ceq $expanded019.OuterXml -and $queue.Count -eq 0 -and
        $captureNames.Count -eq 2 -and $captureNames[0] -ceq 'offline-partial-scene-0' -and
        $captureNames[1] -ceq 'offline-partial-scene-1' -and $calls.Count -eq 0 -and
        $report.Count -eq 1 -and $report[0].Contains('chat navigation missing')) 'Fresh complete scene was not required'
    $captureNames.Clear()
    $queue.Enqueue($expanded019)
    $doc = QaCaptureChat 'offline-initial' -FirstDocument $partial019
    Assert-Qa ($doc.OuterXml -ceq $expanded019.OuterXml -and $captureNames.Count -eq 1 -and
        $captureNames[0] -ceq 'offline-initial-scene-1' -and $calls.Count -eq 0) 'FirstDocument counted incorrectly'
}
Check 'three rejected scene frames stop without input; known sidebar or generation rejects immediately' {
    $calls.Clear(); $queue.Clear(); $captureNames.Clear()
    1..3 | ForEach-Object { $queue.Enqueue($partial019) }
    Reject { QaCaptureChat 'offline-exhausted' } 'chat navigation missing'
    Assert-Qa ($queue.Count -eq 0 -and $captureNames.Count -eq 3 -and $calls.Count -eq 0) 'Capture exceeded three attempts'
    foreach ($label in @('关闭侧栏', '停止生成')) {
        $drift = [xml]$partial019.OuterXml
        $flag = $drift.CreateElement('node'); $flag.SetAttribute('content-desc', $label)
        $null = $drift.DocumentElement.AppendChild($flag)
        $queue.Enqueue($drift); $captureNames.Clear()
        $reason = if ($label -ceq '关闭侧栏') { 'sidebar is open' } else { 'unexpected generation' }
        Reject { QaCaptureChat 'offline-positive-drift' } $reason
        Assert-Qa ($queue.Count -eq 0 -and $captureNames.Count -eq 1 -and $calls.Count -eq 0) 'Known drift was retried or input sent'
    }
}
Check 'recorded expanded 019 chat subtree remains identical despite missing outer navigation subtree' {
    $surface = Get-QaChatSurface $expanded019
    $partialSurface = $partial019.SelectSingleNode('//node[@scrollable="true" and @bounds="[0,231][1080,1854]"]')
    Assert-Qa ($null -ne $partialSurface -and $surface.OuterXml -ceq $partialSurface.OuterXml) 'Recorded partial list subtree changed'
    $before = QaChatSurfaceFingerprint $expanded019
    Assert-Qa ($before -ceq (QaChatSurfaceFingerprint $later019)) 'Recorded expanded list content moved'
    $drift = [xml]$expanded019.OuterXml
    (Get-QaNode $drift '打开菜单').SetAttribute('content-desc', 'outside-list-change')
    Assert-Qa ($before -ceq (QaChatSurfaceFingerprint $drift)) 'Fingerprint included sibling navigation'
    $moved = [xml]$expanded019.OuterXml
    (Get-QaNode $moved '收起 qa-output-a.txt').SetAttribute('bounds', '[63,555][1017,684]')
    Assert-Qa ($before -cne (QaChatSurfaceFingerprint $moved)) 'Fingerprint missed actual child movement'
}
Check 'actual 019 center swipe logs file-header hit; diagnostic padding uses distinct empty x and logs bounds' {
    $calls.Clear()
    $logPath = Join-Path $root 'swipe-log.jsonl'
    $priorCount = @(Get-Content -LiteralPath $logPath).Count
    Swipe $expanded019 $true
    $paddingX = QaDiagnosticRightPaddingX $expanded019
    Swipe $expanded019 $true -DiagnosticX $paddingX
    $records = @(Get-Content -LiteralPath $logPath | ForEach-Object { $_ | ConvertFrom-Json })
    $center = $records[$priorCount]; $padding = $records[$priorCount + 1]
    Assert-Qa ($paddingX -eq 1048 -and $calls.Count -eq 2 -and
        $calls[0] -ceq 'shell input swipe 540 556 540 1529 400' -and
        $calls[1] -ceq 'shell input swipe 1048 556 1048 1529 400') 'Actual recorded geometry drifted'
    Assert-Qa ($center.bounds -ceq '[0,231][1080,1854]' -and
        ($center.from -join ',') -ceq '540,556' -and ($center.to -join ',') -ceq '540,1529' -and
        $center.command_succeeded -and @($center.start_hits | Where-Object {
            $_.description -ceq '收起 qa-output-a.txt' -and
            $_.bounds -ceq '[63,556][1017,685]'
        }).Count -eq 1 -and @($center.start_hits | Where-Object {
            $_.clickable -ceq 'true' -and $_.bounds -ceq '[63,556][1017,685]'
        }).Count -eq 1) 'Header description child/clickable parent start-hit evidence missing'
    Assert-Qa ($padding.command_succeeded -and $padding.lane -ceq 'diagnostic-right-padding' -and
        @($padding.start_hits).Count -eq 0) 'Padding candidate hits a recorded child'
}
Check 'diagnostic rejects occupied right edge or out-of-surface x without a gesture' {
    $calls.Clear()
    $occupied = [xml]$expanded019.OuterXml
    (Get-QaNode $occupied '收起 qa-output-a.txt').SetAttribute('bounds', '[63,556][1080,685]')
    Reject { QaDiagnosticRightPaddingX $occupied } 'No recorded right padding corridor'
    Reject { Swipe $expanded019 $true -DiagnosticX 1080 } 'Swipe x outside'
    Assert-Qa ($calls.Count -eq 0) 'Rejected candidate sent input'
}
$collapse019 = [xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot `
    'runs/activity-019-ce85fece8aba414faf508540b4b2b60d/009-collapse-True-0-scene-0.xml') -Raw -Encoding utf8)
Check 'actual 009 failure is an empty non-scroll button in Swipe hit scan; center geometry preserved' {
    $calls.Clear()
    Assert-Qa ($null -eq (Get-QaNode $collapse019 '已完成 · 用时 2 分 5 秒')) 'Recorded completion label unexpectedly exists'
    $empty = @($collapse019.SelectNodes('//node[@bounds="[0,0][0,0]"]'))
    Assert-Qa ($empty.Count -eq 1 -and $empty[0].GetAttribute('class') -ceq 'android.widget.Button' -and
        $empty[0].GetAttribute('scrollable') -ceq 'false') 'Actual empty-node evidence changed'
    Reject { Get-QaBounds $empty[0] } 'Empty UI bounds'
    Assert-Qa ((Get-QaChatSurface $collapse019).GetAttribute('bounds') -ceq '[0,231][1080,1854]') 'Actual root changed'
    Swipe $collapse019 $true
    Assert-Qa ($calls.Count -eq 1 -and $calls[0] -ceq 'shell input swipe 540 556 540 1529 400') 'Actual center changed'
    $record = Get-Content -LiteralPath (Join-Path $root 'swipe-log.jsonl') -Tail 1 | ConvertFrom-Json
    Assert-Qa ($record.command_succeeded -and
        @($record.start_hits | Where-Object { $_.bounds -ceq '[0,0][0,0]' }).Count -eq 0) 'Empty node entered hit log'
    Assert-Qa ((QaDiagnosticRightPaddingX $collapse019) -eq 1048 -and $calls.Count -eq 1) 'Empty child broke padding scan'
}
Check 'actual 009 derived same-name empty candidate cannot hide a later visible match' {
    $calls.Clear(); $queue.Clear()
    $doc = [xml]$collapse019.OuterXml
    $visible = Get-QaNode $doc '收起 qa-output-a.txt'
    $empty = $visible.CloneNode($true)
    $empty.SetAttribute('bounds', '[0,0][0,0]')
    $null = $visible.ParentNode.InsertBefore($empty, $visible)
    Assert-Qa ((Get-QaNode $doc '收起 qa-output-a.txt').GetAttribute('bounds') -ceq '[0,0][0,0]') 'Regression ordering missing'
    $queue.Enqueue($doc)
    $found = Find '收起 qa-output-a.txt' 'offline-empty-before-visible'
    Assert-Qa ($found.Node.GetAttribute('bounds') -ceq '[63,1484][1017,1613]' -and
        $calls.Count -eq 0 -and $queue.Count -eq 0) 'Find returned an empty candidate or sent input'
    Reject { Tap $empty } 'Empty UI bounds'
    Assert-Qa ($calls.Count -eq 0) 'Empty node was tapped'
}
Check 'actual 009 derived empty or wholly offscreen match continues real bounded center scrolling' {
    foreach ($bounds in @('[0,0][0,0]', '[63,100][1017,180]')) {
        $calls.Clear(); $queue.Clear()
        $doc = [xml]$collapse019.OuterXml
        (Get-QaNode $doc '收起 qa-output-a.txt').SetAttribute('bounds', $bounds)
        $queue.Enqueue($doc); $queue.Enqueue($collapse019)
        $found = Find '收起 qa-output-a.txt' 'offline-invisible-then-visible'
        Assert-Qa ($found.Node.GetAttribute('bounds') -ceq '[63,1484][1017,1613]' -and
            $calls.Count -eq 1 -and $calls[0] -ceq 'shell input swipe 540 556 540 1529 400' -and
            $queue.Count -eq 0) 'Invisible match prevented real scrolling or became a tap'
    }
    $clipped = [xml]$collapse019.OuterXml
    $header = Get-QaNode $clipped '收起 qa-output-a.txt'
    $header.ParentNode.SetAttribute('bounds', '[63,100][1017,180]')
    Assert-Qa ($null -eq (Get-QaVisibleChatNode $clipped '收起 qa-output-a.txt')) 'Invisible ancestor was ignored'
}
Check 'only offscreen matches exhaust original 24 per direction without tapping' {
    $calls.Clear(); $queue.Clear()
    $doc = [xml]$collapse019.OuterXml
    (Get-QaNode $doc '收起 qa-output-a.txt').SetAttribute('bounds', '[0,0][0,0]')
    1..48 | ForEach-Object { $queue.Enqueue($doc) }
    Reject { Find '收起 qa-output-a.txt' 'offline-all-offscreen' } 'after bounded chat scrolling'
    Assert-Qa ($queue.Count -eq 0 -and $calls.Count -eq 48 -and
        @($calls | Where-Object { $_ -like '* tap *' }).Count -eq 0 -and
        @($calls | Where-Object { $_ -ceq 'shell input swipe 540 556 540 1529 400' }).Count -eq 24 -and
        @($calls | Where-Object { $_ -ceq 'shell input swipe 540 1529 540 556 400' }).Count -eq 24) 'Bound or center geometry changed'
}
Check 'empty or offscreen nested scroll candidates are excluded while actual root and malformed bounds fail closed' {
    $calls.Clear()
    foreach ($bounds in @('[0,0][0,0]', '[63,0][1017,200]')) {
        $doc = [xml]$collapse019.OuterXml
        $surface = Get-QaChatSurface $doc
        $nested = $doc.CreateElement('node')
        $nested.SetAttribute('scrollable', 'true'); $nested.SetAttribute('bounds', $bounds)
        $null = $surface.AppendChild($nested)
        Assert-Qa ((Get-QaChatSurface $doc).GetAttribute('bounds') -ceq '[0,231][1080,1854]') 'Nested candidate replaced root'
        Swipe $doc $true
    }
    $before = $calls.Count
    foreach ($bounds in @('[0,0][0,0]', 'malformed')) {
        $doc = [xml]$collapse019.OuterXml
        (Get-QaChatSurface $doc).SetAttribute('bounds', $bounds)
        $reason = if ($bounds -ceq 'malformed') { 'Invalid UI bounds' } else { 'Empty UI bounds' }
        Reject { Swipe $doc $true } $reason
    }
    $doc = [xml]$collapse019.OuterXml
    (Get-QaNode $doc '收起 qa-output-a.txt').SetAttribute('bounds', 'malformed')
    Reject { Get-QaVisibleChatNode $doc '收起 qa-output-a.txt' } 'Invalid UI bounds'
    Reject { Swipe $doc $true } 'Invalid UI bounds'
    Reject { QaDiagnosticRightPaddingX $doc } 'Invalid UI bounds'
    Assert-Qa ($calls.Count -eq $before) 'Malformed bounds sent input'
}
Check 'diagnostic branch uses exactly two swipes without Find and remains inside full restoration finally' {
    $ast = $asts['VerifyActivity019.ps1']
    $branch = $ast.Find({
        param($node)
        $node -is [Management.Automation.Language.IfStatementAst] -and
            $node.Clauses[0].Item1.Extent.Text -ceq '$DiagnoseScroll' -and
            $node.Extent.Text.Contains("QaCaptureChat 'diagnose-collapsed'")
    }, $true)
    Assert-Qa ($null -ne $branch) 'Diagnostic branch missing'
    $commands = @($branch.FindAll({
        param($node)
        $node -is [Management.Automation.Language.CommandAst]
    }, $true))
    Assert-Qa (@($commands | Where-Object { $_.GetCommandName() -ceq 'Swipe' }).Count -eq 2 -and
        @($commands | Where-Object { $_.GetCommandName() -ceq 'Find' }).Count -eq 0) 'Diagnostic runs a scroll search'
    Assert-Qa (@($branch.FindAll({
        param($node)
        $node -is [Management.Automation.Language.LoopStatementAst]
    }, $true)).Count -eq 0) 'Diagnostic branch contains a loop'
    $enclosingTry = $branch.Parent
    while ($null -ne $enclosingTry -and $enclosingTry -isnot [Management.Automation.Language.TryStatementAst]) {
        $enclosingTry = $enclosingTry.Parent
    }
    Assert-Qa ($null -ne $enclosingTry -and $null -ne $enclosingTry.Finally -and
        $enclosingTry.Finally.Extent.Text.Contains('Restore-QaState $records') -and
        $branch.Extent.Text.Contains('DIAGNOSTIC ONLY') -and
        @($branch.FindAll({
            param($node)
            $node -is [Management.Automation.Language.StringConstantExpressionAst] -and
                $node.Value -match '^PASS(?:\s|$)'
        }, $true)).Count -eq 0) 'Diagnostic bypasses restoration or claims full PASS'
    $find = $ast.Find({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Find'
    }, $true)
    Assert-Qa ($find.Extent.Text.Contains('$i -lt 24') -and
        $find.Extent.Text.Contains('@($Earlier, (-not $Earlier))')) 'Default search limits changed'
    $capture = $ast.Find({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Capture'
    }, $true)
    Assert-Qa (@($capture.FindAll({
        param($node)
        $node -is [Management.Automation.Language.LoopStatementAst]
    }, $true)).Count -eq 0) 'Nested raw Capture retries can exceed scene budget'
}
$title = 'QA 018 grouped activities'
$metrics = @('轮数 2', '工具步骤 13', 'Tokens 777777', '耗时 125.0 s')
Check 'version/code/debuggable/hash gates reject old and intermediate APKs' {
    $hash = 'a' * 64
    $dump = "versionName=0.1.19`nversionCode=20 minSdk=30`nflags=[ DEBUGGABLE HAS_CODE ]"
    Assert-QaInstalledMetadata $dump $hash $hash
    Reject { Assert-QaInstalledMetadata ($dump.Replace('0.1.19', '0.1.18')) $hash $hash } 'version must'
    Reject { Assert-QaInstalledMetadata ($dump.Replace('versionCode=20', 'versionCode=19')) $hash $hash } 'versionCode'
    Reject { Assert-QaInstalledMetadata $dump ('b' * 64) $hash } 'differs'
    Reject { Assert-QaInstalledMetadata ($dump.Replace('DEBUGGABLE', 'RELEASE')) $hash $hash } 'Debuggable'
}
Check 'runner requires explicit emulator Serial, final ownership/hash and App-only credential gate before mutation' {
    $ast = $asts['VerifyActivity019.ps1']
    $serial = $ast.ParamBlock.Parameters | Where-Object { $_.Name.VariablePath.UserPath -eq 'Serial' }
    Assert-Qa ($null -eq $serial.DefaultValue) 'Implicit emulator serial forbidden'
    $text = $ast.Extent.Text
    Assert-Qa ($text.Contains('$FinalReady -and $MainAgentOwnsEmulator') -and
        $text.Contains('Assert-QaAppCredentialsAbsent $credentialState') -and
        -not $text.Contains('IsolatedEmulatorNoCredentials')) 'Ownership/App-only gate missing'
    Assert-Qa ($text.Contains('^emulator-\d+$')) 'Phone guard missing'
    Assert-Qa ($text.IndexOf('Assert-QaInstalledMetadata $dump') -lt $text.IndexOf("$" + "null = Adb @('shell', 'am', 'force-stop'")) 'Hash guard must precede force-stop'
    Assert-Qa ($text.IndexOf('Assert-QaAppCredentialsAbsent $credentialState') -lt
        $text.IndexOf("$" + "null = Adb @('shell', 'am', 'force-stop'")) 'App existence gate must precede force-stop'
    Assert-Qa ($text.Contains('[ -e $Path ] || [ -L $Path ]') -and
        $text.Contains('Assert-Qa ($answer -cin')) 'Unknown and dangling symlink existence must fail closed'
}
Check 'allowlist covers complete workspace and task/output roots, including AtomicFile siblings' {
    $text = $asts['VerifyActivity019.ps1'].Extent.Text
    foreach ($path in @('files/Dafeiyu-Works', 'no_backup/background-tasks', 'no_backup/official-tool-outputs')) {
        Assert-Qa ($text.Contains("Path = '$path'")) "Missing backup root $path"
    }
    Assert-Qa ($text.Contains('$mutationStarted = $true')) 'Mutation must be marked before first removal'
    Assert-Qa (-not $text.Contains('input keyevent 279')) 'Clipboard may not be overwritten by automated matrix'
}
Check 'actual private transaction helpers resist default Copy alias and retain exact removal allowlist' {
    $ast = $asts['VerifyActivity019.ps1']
    $text = $ast.Extent.Text
    $operations = [Collections.Generic.List[string]]::new()
    $backup = 'no_backup/activity-019-offline-backup'
    $items = @([pscustomobject]@{ Path = 'files/Dafeiyu-Works'; Backup = "$backup/workspace" })
    function Private([string]$Command) { $operations.Add($Command) }
    Assert-Qa ((Get-Alias -Name Copy).Definition -ceq 'Copy-Item') 'Default Copy alias required to reproduce the recorded failure'
    foreach ($name in @('QaCopy', 'QaCompare', 'QaRemove')) {
        $definition = $ast.Find({
            param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
        }, $true)
        Assert-Qa ($null -ne $definition) "Missing transaction helper $name"
        . ([scriptblock]::Create($definition.Extent.Text))
        Assert-Qa ((Get-Command $name).CommandType -eq 'Function') "Alias overrides $name"
    }
    $remote = '/data/local/tmp/activity-019-offline'
    QaCopy "$remote/workspace-index.json" 'files/Dafeiyu-Works/workspace-index.json'
    & ${function:QaCompare} $items[0].Backup $items[0].Path
    & ${function:QaRemove} $items[0].Path
    Assert-Qa ($operations.Count -eq 3 -and
        $operations[0] -ceq "cp -R $remote/workspace-index.json files/Dafeiyu-Works/workspace-index.json" -and
        $operations[1].Contains("cmp $backup/manifest-") -and
        $operations[1].Contains("for tree in $($items[0].Backup) $($items[0].Path)") -and
        $operations[2] -ceq 'rm -rf files/Dafeiyu-Works') 'Private helper was not invoked through mocked private shell'
    Reject { QaRemove 'no_backup' } 'Removal outside exact transaction allowlist'
    Assert-Qa ($operations.Count -eq 3) 'Out-of-allowlist path reached private shell'
    $oldCommands = @($ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.CommandAst] -and
            $node.GetCommandName() -in @('Copy', 'Compare', 'Remove')
    }, $true))
    Assert-Qa ($oldCommands.Count -eq 0) 'Alias-prone transaction invocation remains'
    Assert-Qa ($text.Contains('Save-QaState $items ${function:Exists} ${function:QaCopy} ${function:QaCompare}') -and
        $text.Contains('Restore-QaState $records ${function:QaRemove} ${function:QaCopy} ${function:QaCompare} ${function:Exists}')) 'Transaction scriptblock references drifted'
    Assert-Qa (-not $text.Contains('$pid =') -and $text.Contains('--pid=$appProcessId')) 'Read-only PowerShell PID variable used as App process ID'
}
Check 'fresh missing or unsafe no_backup rejects before App stop and transaction without creating the root' {
    $ast = $asts['VerifyActivity019.ps1']
    $definition = $ast.Find({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'QaAssertBackupParent'
    }, $true)
    Assert-Qa ($null -ne $definition) 'Missing backup parent precondition'
    . ([scriptblock]::Create($definition.Extent.Text))
    $parentState = 'ready'
    $probes = [Collections.Generic.List[string]]::new()
    function Private([string]$Command) { $probes.Add($Command); return $parentState }
    QaAssertBackupParent
    foreach ($parentState in @('missing-or-unsafe', 'unknown')) {
        Reject { QaAssertBackupParent } 'Fresh install prerequisite'
    }
    Assert-Qa ($probes.Count -eq 3 -and @($probes | Where-Object {
        $_ -cne 'if [ -d no_backup ] && [ ! -L no_backup ]; then echo ready; else echo missing-or-unsafe; fi'
    }).Count -eq 0) 'Parent probe changed state or allowed symlink'
    $text = $ast.Extent.Text
    $gate = $text.IndexOf("`n    QaAssertBackupParent")
    $stop = $text.IndexOf("$" + "null = Adb @('shell', 'am', 'force-stop'")
    Assert-Qa ($gate -ge 0 -and $gate -lt $stop -and $gate -lt $text.IndexOf('Private "mkdir $backup"')) 'Fresh-install prerequisite must precede lifecycle/backup mutation'
    Assert-Qa (-not $text.Contains('mkdir -p no_backup') -and
        -not $text.Contains('rm -rf no_backup"')) 'Runner changed original root creation/deletion boundary'
}
Check 'backup verification failure aborts without destructive action' {
    $memory = @{ one = 'original' }
    $events = [Collections.Generic.List[string]]::new()
    $items = @([pscustomobject]@{ Path = 'one'; Backup = 'backup-one' })
    $exists = { param($path) $memory.ContainsKey($path) }
    $copy = { param($a, $b) $events.Add('copy'); $memory[$b] = $memory[$a] }
    $compare = { param($a, $b) throw 'backup mismatch' }
    Reject { $null = Save-QaState $items $exists $copy $compare } 'backup mismatch'
    Assert-Qa ($memory.one -ceq 'original' -and $events.Count -eq 1) 'Original changed before backup verified'
}
Check 'restore continues after failure, preserves backups and restores original absence' {
    $memory = @{ one = 'original'; three = 'original-three' }
    $items = @('one', 'two', 'three') | ForEach-Object { [pscustomobject]@{ Path = $_; Backup = "backup-$_" } }
    $exists = { param($path) $memory.ContainsKey($path) }
    $mockCopy = { param($a, $b) $memory[$b] = $memory[$a] }
    $compare = { param($a, $b) Assert-Qa ($memory[$a] -ceq $memory[$b]) 'Bytes differ' }
    $remove = { param($path) $memory.Remove($path) | Out-Null }
    $records = Save-QaState $items $exists $mockCopy $compare
    $memory.one = 'fixture'; $memory.two = 'new'; $memory.three = 'fixture-three'
    $badCopy = { param($a, $b) if ($b -eq 'one') { throw 'restore failure' }; & $mockCopy $a $b }
    Reject { Restore-QaState $records $remove $badCopy $compare $exists } 'KEEP BACKUP'
    Assert-Qa ($memory.three -ceq 'original-three' -and -not $memory.ContainsKey('two')) 'Later path/absence skipped'
    Assert-Qa ($memory['backup-one'] -ceq 'original' -and $memory['backup-three'] -ceq 'original-three') 'Backup deleted'
}
Check 'production one MiB cap unchanged; live target is small RFC768' {
    $source = Get-Content -LiteralPath (Join-Path (Get-QaProject) 'app/src/main/java/com/dafeiyu/mobile/data/OfficialWebClient.kt') -Raw
    Assert-Qa ($source.Contains('const val MAX_BYTES = 1024 * 1024')) 'Production cap changed'
    $runner = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'ProductionWebSmoke.kt') -Raw
    Assert-Qa ($runner.Contains('rfc768.html') -and -not $runner.Contains('rfc9110.html')) 'Oversized happy-path target'
}
Check 'production runner defaults to deferred without JVM process invocation' {
    $output = & (Join-Path $PSScriptRoot 'InvokeProductionWebSmoke.ps1')
    Assert-Qa ([string]$output -like 'DEFERRED:*') 'Default must defer compilation'
}
Check 'device runner preparation mode makes no ADB command' {
    $output = & (Join-Path $PSScriptRoot 'VerifyActivity019.ps1')
    Assert-Qa ([string]$output -like 'PREPARED*No ADB executed.*') 'Preparation failed'
}
. (Join-Path $PSScriptRoot 'QaLongChat.ps1')
. (Join-Path $PSScriptRoot 'QaBatch020.ps1')
Check '020 explicit version binding preserves 019 metadata rejection' {
    $hash = 'a' * 64
    $dump = "versionName=0.1.20`nversionCode=21 minSdk=30`nflags=[ DEBUGGABLE HAS_CODE ]"
    Assert-QaInstalledMetadata $dump $hash $hash '0.1.20' 21
    Reject { Assert-QaInstalledMetadata $dump $hash $hash } 'version must'
    Reject { Get-QaVersionBinding '0.1.20' 20 } 'explicit newer'
    Reject { Assert-QaInstalledMetadata ($dump.Replace('versionCode=21', 'versionCode=22')) $hash $hash '0.1.20' 21 } 'versionCode'
    Reject { Assert-QaInstalledMetadata $dump ('b' * 64) $hash '0.1.20' 21 } 'differs'
    $path = Join-Path $PSScriptRoot 'freeze-020-manifest.json'
    $output = & (Join-Path $PSScriptRoot 'Invoke-QaBatch020.ps1') -ExpectedVersionCode 21 -FreezeManifestPath $path
    Assert-Qa ([string]$output -like 'PREPARED 0.1.20/code21 Short*No ADB executed.*') '020 entry point cannot prepare'
    Reject { & (Join-Path $PSScriptRoot 'Invoke-QaBatch020.ps1') -ExpectedVersionCode 21 -FreezeManifestPath (Join-Path $PSScriptRoot 'freeze-manifest.json') } 'historical 019'
}
Check 'quoted text mirrors Kotlin lineSequence including trailing empty line and exact composer check' {
    foreach ($text in @("a`nb`n", "a`r`nb`r`n", "a`rb`r")) {
        Assert-Qa ((Get-QaQuotedText $text) -ceq "> a`n> b`n> `n`n") 'Newline or trailing empty line drift'
    }
    $doc = [xml]$current.OuterXml
    (Get-QaComposer $doc).SetAttribute('text', "> alpha`n`n")
    Assert-QaComposerText $doc "> alpha`n`n"
    Reject { Assert-QaComposerText $doc "> alpha`n" } 'exact synthetic'
    (Get-QaComposer $doc).SetAttribute('text', 'QA_EARLIER_ROUND')
    Reject { Assert-QaComposerText $doc 'QA_EARLIER_ROUND' } 'Earlier process'
}
Check 'real saved XML without state-desc resolves receipt ancestors and isolated response/file reference rows' {
    $saved = [xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runs/activity-019-30b3d08caa2b409c90848385ede716c9/001-completed-collapsed-True-0-scene-0.xml') -Raw)
    $header = Get-QaDisclosure (Get-QaNode $saved '已完成 · 用时 2 分 5 秒')
    Assert-Qa ($header.GetAttribute('state-desc') -ceq '' -and $header.GetAttribute('clickable') -ceq 'true') 'Saved schema expectation drift'
    $copies = @($saved.SelectNodes('//node[@content-desc="复制完整文本" or @content-desc="复制回复"]'))
    Assert-Qa ($copies.Count -eq 3) 'Expected response and two generated files'
    $references = @($copies | ForEach-Object { Get-QaFileReferenceNode $_ })
    Assert-Qa (@($references | ForEach-Object { $_.GetAttribute('bounds') } | Select-Object -Unique).Count -eq 3) 'Reference selected across unrelated action rows'
    foreach ($name in @('qa-output-a.txt', 'qa-output-b-with-a-long-synthetic-filename.txt')) {
        $header = Get-QaDisclosure (Get-QaNode $saved $name)
        Assert-Qa ($null -ne $header.SelectSingleNode('.//node[@content-desc="收起 ' + $name + '"]')) 'Saved default file preview is not expanded'
    }
}
Check 'disclosure toggles work with actual saved schema lacking state-desc and reject unknown controls' {
    $saved = [xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runs/activity-019-30b3d08caa2b409c90848385ede716c9/001-completed-collapsed-True-0-scene-0.xml') -Raw)
    $script:disclosureStates = @{ '已完成 · 用时 2 分 5 秒' = $false }
    $taps = [Collections.Generic.List[object]]::new()
    function Find($label, $name) { [pscustomobject]@{ Document = $saved; Node = Get-QaNode $saved $label } }
    function Tap($node) { $taps.Add($node) }
    $null = Set-QaDisclosure '已完成 · 用时 2 分 5 秒' $true 'mock'
    $null = Set-QaDisclosure '已完成 · 用时 2 分 5 秒' $true 'mock'
    $null = Set-QaDisclosure '已完成 · 用时 2 分 5 秒' $false 'mock'
    Assert-Qa ($taps.Count -eq 2) 'Disclosure blindly toggled already requested state'
    $script:disclosureStates.Clear()
    Reject { Set-QaDisclosure '已完成 · 用时 2 分 5 秒' $true 'mock' } 'Unknown disclosure'
}
Check 'file preview uses real header state and never closes an already expanded requested preview' {
    $saved = [xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runs/activity-019-30b3d08caa2b409c90848385ede716c9/001-completed-collapsed-True-0-scene-0.xml') -Raw)
    $taps = [Collections.Generic.List[object]]::new()
    function Find($label, $name, $earlier) { [pscustomobject]@{ Document = $saved; Node = Get-QaNode $saved $label } }
    function Tap($node) {
        $taps.Add($node)
        $description = $node.SelectSingleNode('.//node[starts-with(@content-desc,"收起 ") or starts-with(@content-desc,"预览 ")]')
        $label = $description.GetAttribute('content-desc')
        $description.SetAttribute('content-desc', $(if ($label.StartsWith('收起 ')) { $label.Replace('收起 ', '预览 ') } else { $label.Replace('预览 ', '收起 ') }))
    }
    Set-QaFilePreview 'qa-output-a.txt' $true
    Assert-Qa ($taps.Count -eq 0) 'Expanded preview was closed'
    Set-QaFilePreview 'qa-output-a.txt' $false
    Set-QaFilePreview 'qa-output-a.txt' $true
    Assert-Qa ($taps.Count -eq 2) 'Preview state transition drift'
}
Check 'long real saved XML full text and stable pixels never certify a visible sentinel' {
    Add-Type -AssemblyName System.Drawing
    $longIndex = Test-QaFixture (Join-Path $PSScriptRoot 'fixtures')
    $doc = [xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runs/activity-019-30b3d08caa2b409c90848385ede716c9/025-long-first-True-0-scene-0.xml') -Raw)
    $rows = [Collections.Generic.List[object]]::new()
    $moves = [Collections.Generic.List[bool]]::new()
    $capture = { param($name) [pscustomobject]@{ Document = $doc; Pixels = [int[]]::new(3072); Evidence = @{ png = "$name.png" } } }
    $scroll = { param($d, $earlier, $stable) $moves.Add($earlier) }
    $record = { param($row) $rows.Add($row) }
    $leg = Invoke-QaLongLeg first $longIndex.messages['qa-long-019'] 2 10 $capture $scroll $record
    Assert-Qa ($leg.status -ceq 'INCOMPLETE' -and $leg.swipes -eq 2 -and $rows.Count -eq 3 -and
        @($moves | Where-Object { -not $_ }).Count -eq 0 -and $leg.best_semantic_round -eq 19) 'Long budget/direction/round tracking drift'
    $moves.Clear(); $rows.Clear()
    $leg = Invoke-QaLongLeg last $longIndex.messages['qa-long-019'] 6 10 $capture $scroll $record
    Assert-Qa ($leg.status -ceq 'PENDING_REVIEW' -and $leg.swipes -eq 3 -and $null -ne $leg.candidate) 'Stable latest text must only nominate PNG review'
    Assert-Qa ((Get-QaLongCoverageStatus @($leg, $leg) $false) -ceq 'PENDING_REVIEW') 'Long falsely auto-passed'
}
Check 'long no progress and time budgets terminate independently of increased swipe caps' {
    $longIndex = Test-QaFixture (Join-Path $PSScriptRoot 'fixtures')
    $doc = [xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runs/activity-019-30b3d08caa2b409c90848385ede716c9/025-long-first-True-0-scene-0.xml') -Raw)
    $time = @{ seconds = 0 }
    $capture = { param($name) [pscustomobject]@{ Document = $doc; Pixels = [int[]]::new(3072); Evidence = @{ png = "$name.png" } } }
    $scroll = { param($d, $earlier, $stable) $time.seconds += 2 }
    $record = { param($row) }
    $elapsed = { $time.seconds }
    $leg = Invoke-QaLongLeg first $longIndex.messages['qa-long-019'] 120 100 $capture $scroll $record -NoProgressSeconds 3 -ElapsedSeconds $elapsed
    Assert-Qa ($leg.status -ceq 'INCOMPLETE' -and $leg.reason -ceq 'no_pixel_or_directional_round_progress' -and $leg.swipes -eq 2) 'No progress cap ineffective'
    $time.seconds = 0
    $leg = Invoke-QaLongLeg first $longIndex.messages['qa-long-019'] 120 3 $capture $scroll $record -ElapsedSeconds $elapsed
    Assert-Qa ($leg.status -ceq 'INCOMPLETE' -and $leg.reason -ceq 'time_budget_exhausted') 'Time budget ineffective'
}
$passed = @($results | Where-Object { $_.status -eq 'PASS' }).Count
[ordered]@{
    schema = 1; passed = $passed; total = $results.Count; results = $results.ToArray()
    environment = 'PowerShell, saved 0.1.18/failed 0.1.19 including actual 009 empty-button XML and derived offscreen candidates, synthetic current 019 accessibility XML, mock input and in-memory rollback'
    adb_executed = $false; jvm_executed = $false; model_called = $false
    device_019_qa = 'DEFERRED'; production_network_smoke = 'DEFERRED pending main-agent JVM scheduling'
    qa_inputs = Get-QaPreparationHashes
} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $root 'result.json') -Encoding utf8NoBOM
Write-Output "RESULT $passed/$($results.Count) offline PASS; $root"
Assert-Qa ($passed -eq $results.Count) 'Offline checks failed'
