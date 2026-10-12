#requires -Version 7.0
# Executed in the transaction owner's script scope; no independent device or build owner.
Set-StrictMode -Version Latest

function Get-QaQuotedText([string]$Text) {
    return (($Text -split '\r\n|\n|\r' | ForEach-Object { "> $_" }) -join "`n") + "`n`n"
}

function Assert-QaComposerText([xml]$Document, [string]$Expected) {
    $actual = (Get-QaComposer $Document).GetAttribute('text')
    Assert-Qa ($actual -ceq $Expected) 'Composer full text differs from exact synthetic copy/reference bytes'
    Assert-Qa (-not $actual.Contains('QA_EARLIER_ROUND')) 'Earlier process text leaked into copied/referenced final body'
}

function Get-QaDisclosure($Node) {
    $header = $Node.SelectSingleNode('ancestor-or-self::node[@clickable="true"][1]')
    Assert-Qa ($null -ne $header) 'Disclosure has no accessible clickable ancestor'
    Assert-QaTap $header
    return $header
}

function Set-QaDisclosure([string]$Label, [bool]$Expanded, [string]$Name) {
    $found = Find $Label $Name
    $header = Get-QaDisclosure $found.Node
    $state = $header.GetAttribute('state-desc')
    # UIAutomator omits Compose stateDescription on some Android versions. The fresh
    # frozen terminal fixture defaults to collapsed; only this script changes it.
    Assert-Qa ($script:disclosureStates.ContainsKey($Label)) 'Unknown disclosure; will not blindly toggle'
    if ($state) {
        Assert-Qa ($state -cin @('已展开', '已收起') -and
            ($state -ceq '已展开') -eq $script:disclosureStates[$Label]) 'Disclosure state drift'
    }
    if ($script:disclosureStates[$Label] -ne $Expanded) {
        Tap $header
        $script:disclosureStates[$Label] = $Expanded
    }
    $found = Find $Label "$Name-after"
    $state = (Get-QaDisclosure $found.Node).GetAttribute('state-desc')
    if ($state) { Assert-Qa ($state -ceq $(if ($Expanded) { '已展开' } else { '已收起' })) 'Disclosure did not reach requested state' }
    return $found
}

function Reset-QaComposer([string]$Name) {
    $doc = QaCaptureChat "$Name-before-clear"
    Tap (Get-QaComposer $doc)
    $null = Adb @('shell', 'input', 'keycombination', '113', '29')
    $null = Adb @('shell', 'input', 'keyevent', '67')
    $doc = QaCaptureChat "$Name-cleared"
    Assert-QaComposerText $doc ''
    # Back after composer focus dismisses the IME; verify that we remain in the chat.
    $null = Adb @('shell', 'input', 'keyevent', '4')
    $null = QaCaptureChat "$Name-ime-dismissed"
}

function Test-QaCopyIntoComposer($Node, [string]$Expected, [string]$Name) {
    $doc = QaCaptureChat "$Name-before-copy"
    Assert-QaComposerText $doc ''
    Tap $Node
    $doc = QaCaptureChat "$Name-copied"
    Tap (Get-QaComposer $doc)
    $null = Adb @('shell', 'input', 'keyevent', '279')
    $doc = QaCaptureChat "$Name-pasted"
    Assert-QaComposerText $doc $Expected
    $evidence = $script:lastCapture
    Reset-QaComposer $Name
    return $evidence
}

function Get-QaFileReferenceNode($CopyNode) {
    $surface = Get-QaChatSurface $CopyNode.OwnerDocument
    Assert-Qa ($null -ne $surface -and (Test-QaNodeInSurface $CopyNode $surface)) 'Copy action outside validated chat'
    $copyBounds = Get-QaBounds $CopyNode
    # Compose flattens Row semantics. Match the only adjacent reference with the same
    # vertical bounds in the validated surface, as demonstrated by saved device XML.
    $nodes = @($surface.SelectNodes('.//node[@content-desc="引用到消息输入框"]') | Where-Object {
        if (-not (Test-QaNodeInSurface $_ $surface)) { return $false }
        $b = Get-QaBounds $_
        [Math]::Abs($b[1] - $copyBounds[1]) -le 2 -and [Math]::Abs($b[3] - $copyBounds[3]) -le 2 -and $b[0] -ge $copyBounds[2]
    })
    Assert-Qa ($nodes.Count -eq 1) 'Missing or ambiguous copy/reference action row'
    Assert-QaTap $nodes[0]
    return $nodes[0]
}

function Set-QaFilePreview([string]$Filename, [bool]$Expanded) {
    $found = Find $Filename 'short-file-header' $false
    $header = Get-QaDisclosure $found.Node
    $labels = @($header.SelectNodes('descendant-or-self::node') | Where-Object {
        $_.GetAttribute('content-desc') -cin @("收起 $Filename", "预览 $Filename")
    })
    Assert-Qa ($labels.Count -eq 1) 'Unknown generated file preview state'
    $label = $labels[0].GetAttribute('content-desc')
    Assert-Qa ($label -cin @("收起 $Filename", "预览 $Filename")) 'Unknown generated file preview state'
    if (($label -ceq "收起 $Filename") -ne $Expanded) { Tap $header }
    $expected = if ($Expanded) { "收起 $Filename" } else { "预览 $Filename" }
    $null = Find $expected 'short-file-preview-state' $false
}

function Invoke-QaShortReceipts {
    $evidence = [Collections.Generic.List[object]]::new()
    $script:disclosureStates = @{ '已完成 · 用时 2 分 5 秒' = $false }
    $terminal = Set-QaDisclosure '已完成 · 用时 2 分 5 秒' $false 'short-terminal-collapsed'
    Assert-Qa ($null -eq (Get-QaNode $terminal.Document 'QA_EARLIER_ROUND' -Contains)) 'Collapsed earlier process leaked'
    $null = Set-QaDisclosure '已完成 · 用时 2 分 5 秒' $true 'short-terminal-open'
    $snapshot = $index.messages['qa-activity-019'][1].streamSnapshot
    $groups = @(
        @{ label = '已建立 3 个子智能体 · 已发送 3 条消息 · 收到 3 条通知'; children = @($snapshot.subagents | ForEach-Object { @{ label = "$($_.name) · $($_.agentId)"; id = $_.agentId } }) },
        @{ label = '编写了 2 个文件'; children = @($snapshot.toolCalls | Where-Object name -eq 'write_text_file' | ForEach-Object { @{ label = "write_text_file · $($_.callId)"; id = $_.callId } }) },
        @{ label = '运行了 3 个命令'; children = @($snapshot.toolCalls | Where-Object name -eq 'run_command' | ForEach-Object { @{ label = "run_command · $($_.callId)"; id = $_.callId } }) }
    )
    foreach ($group in $groups) {
        $script:disclosureStates[$group.label] = $false
        $null = Set-QaDisclosure $group.label $true 'short-batch-open'
        foreach ($child in $group.children) {
            $script:disclosureStates[$child.label] = $false
            $null = Set-QaDisclosure $child.label $true 'short-receipt-open'
            $found = Find $child.id 'short-receipt-id'
            # Exact identifier node must be expanded content, not merely the collapsed header.
            Assert-Qa ($found.Node.GetAttribute('text') -ceq $child.id) 'Receipt identifier content missing'
            $null = Find '复制回执' 'short-receipt-copy-control'
            $evidence.Add($script:lastCapture)
            $null = Set-QaDisclosure $child.label $false 'short-receipt-close'
        }
        $null = Set-QaDisclosure $group.label $false 'short-batch-close'
    }
    $null = Find '查看了 1 个图片' 'short-image-metadata'
    $evidence.Add($script:lastCapture)
    $null = Set-QaDisclosure '已完成 · 用时 2 分 5 秒' $false 'short-terminal-close'
    $final = Find 'QA_FINAL_REPLY_019' 'short-final' $false -Contains
    Assert-Qa ($null -eq (Get-QaNode $final.Document 'QA_EARLIER_ROUND' -Contains)) 'Process leaked after collapse'
    $evidence.Add($script:lastCapture)
    MemorySample 'short-receipts'
    $script:batchCases.Add([ordered]@{ name = 'Receipts'; status = 'PENDING_REVIEW'; semantic_checks = 'PASS'
        expanded_receipts = 8; evidence = $evidence.ToArray()
        review = 'PNG: adjacent batch ordering, spacing, transitions, hit boundaries; image metadata only' })
}

function Invoke-QaShortCopyReference {
    Assert-Qa $OwnsDisposableClipboard 'Independent QA emulator disposable clipboard ownership required'
    $evidence = [Collections.Generic.List[object]]::new()
    $snapshot = $index.messages['qa-activity-019'][1].streamSnapshot
    $finalText = [string]$snapshot.messages[-1].blocks[0].text
    $files = @($snapshot.toolCalls | Where-Object name -eq 'write_text_file' | ForEach-Object { $_.arguments | ConvertFrom-Json })
    foreach ($file in $files) { Set-QaFilePreview $file.filename $false }
    $doc = QaCaptureChat 'short-copy-initial'
    Assert-QaComposerText $doc ''
    $copy = Find '复制回复' 'short-copy-response' $false
    $evidence.Add((Test-QaCopyIntoComposer $copy.Node $finalText 'short-response'))
    $copy = Find '复制回复' 'short-reference-response-row' $false
    Tap (Get-QaFileReferenceNode $copy.Node)
    $doc = QaCaptureChat 'short-response-reference'
    Assert-QaComposerText $doc (Get-QaQuotedText $finalText)
    $evidence.Add($script:lastCapture)
    Reset-QaComposer 'short-response-reference'
    foreach ($arguments in $files) {
        Set-QaFilePreview $arguments.filename $true
        $copy = Find '复制完整文本' 'short-file-copy' $false
        Assert-Qa ($null -ne (Get-QaVisibleChatNode $copy.Document "收起 $($arguments.filename)")) 'Copy target not associated with expected open file'
        $evidence.Add((Test-QaCopyIntoComposer $copy.Node $arguments.text 'short-file'))
        $copy = Find '复制完整文本' 'short-file-reference-row' $false
        Tap (Get-QaFileReferenceNode $copy.Node)
        $doc = QaCaptureChat 'short-file-reference'
        Assert-QaComposerText $doc (Get-QaQuotedText "$($arguments.filename)`n$($arguments.text)")
        $evidence.Add($script:lastCapture)
        Reset-QaComposer 'short-file-reference'
        Set-QaFilePreview $arguments.filename $false
    }
    $script:batchCases.Add([ordered]@{ name = 'CopyReference'; status = 'PASS'; exact_text_checks = 6
        evidence = $evidence.ToArray(); clipboard = 'Disposable emulator clipboard/history consumed; no restoration claim' })
}

function Invoke-QaShortRunInfo320 {
    $script:displayChanged = $true
    $width = [int][Math]::Round(320 * $density / 160)
    $height = [int][Math]::Round(640 * $density / 160)
    $null = Adb @('shell', 'wm', 'size', "${width}x${height}")
    Start-Sleep -Milliseconds 700
    $doc = QaCaptureChat 'short-run-info-320dp'
    Assert-QaRunInfo019 $doc
    Assert-Qa ($null -ne (Get-QaChatSurface $doc)) '320dp chat surface missing'
    $size = Adb @('shell', 'wm', 'size')
    Assert-Qa ($size -match "Override size:\s*${width}x${height}(?:\s|$)") '320dp override not applied'
    $script:batchCases.Add([ordered]@{ name = 'RunInfo320'; status = 'PENDING_REVIEW'; semantic_checks = 'PASS'
        width_dp = 320; density = $density; evidence = $script:lastCapture
        review = 'PNG: measured capsule radius, glyphs/text wrapping, no clipping or composer overlap' })
}

function Invoke-QaBatchLogSample {
    $appProcessId = (Adb @('shell', 'pidof', $package)).Trim()
    Assert-Qa ($appProcessId -match '^\d+$') 'Expected one live app PID'
    $log = Adb @('logcat', '-d', "--pid=$appProcessId", '-t', '400')
    $log | Set-Content -LiteralPath (Join-Path $resultRoot 'app-logcat.txt') -Encoding utf8NoBOM
    Assert-Qa ($log -notmatch 'OutOfMemoryError|FATAL EXCEPTION|ANR in') 'App crash/OOM marker found'
    MemorySample 'batch-final'
    $report.Add('PASS current live PID bounded log sample only; peak memory/leak/prior killed PID not certified')
}

function Invoke-QaBatchShort {
    foreach ($case in @('Receipts', 'CopyReference', 'RunInfo320')) {
        if ($case -notin $ShortCases) { continue }
        switch ($case) {
            Receipts { Invoke-QaShortReceipts }
            CopyReference { Invoke-QaShortCopyReference }
            RunInfo320 { Invoke-QaShortRunInfo320 }
        }
    }
    Invoke-QaBatchLogSample
    $script:batchStatus = if (@($script:batchCases | Where-Object status -ne 'PASS').Count) { 'PENDING_REVIEW' } else { 'PASS' }
}

function Invoke-QaBatchLong {
    SelectConversation 'QA 019 bounded long chat' @()
    $legs = [Collections.Generic.List[object]]::new()
    foreach ($endpoint in @('first', 'last')) {
        $script:adbDeadline = [DateTimeOffset]::UtcNow.AddSeconds($LongMaxSeconds)
        try {
            $leg = Invoke-QaLongLeg -Endpoint $endpoint -Messages $index.messages['qa-long-019'] `
                -MaxSwipes $LongMaxSwipes -MaxSeconds $LongMaxSeconds -NoProgressSeconds $LongNoProgressSeconds `
                -CaptureFrame {
                    param($name)
                    $doc = QaCaptureChat "long-$name"
                    $surface = Get-QaChatSurface $doc
                    Assert-Qa ($null -ne $surface) 'LongChat scroll surface missing'
                    [pscustomobject]@{ Document = $doc; Evidence = $script:lastCapture
                        Pixels = Get-QaChatPixels (Join-Path $resultRoot $script:lastCapture.png) (Get-QaBounds $surface) }
                } -ScrollFrame {
                    param($doc, $earlier, $stable)
                    # Same direction throughout the leg. Stable pixels do not justify reversing.
                    Swipe $doc $earlier
                    Start-Sleep -Milliseconds 350
                } -RecordObservation {
                    param($row)
                    $row | ConvertTo-Json -Depth 10 -Compress |
                        Add-Content -LiteralPath (Join-Path $resultRoot 'long-progress.jsonl') -Encoding utf8NoBOM
                }
            $legs.Add($leg)
            $script:batchCases.Add($leg)
            $script:batchStatus = Get-QaLongCoverageStatus $legs.ToArray() $false
        } catch {
            $failure = [ordered]@{ endpoint = $endpoint
                status = if ($_.Exception.Message.StartsWith('INCOMPLETE:')) { 'INCOMPLETE' } else { 'FAIL' }
                reason = $_.Exception.Message; candidate = $null }
            $script:batchCases.Add($failure)
            throw
        } finally { $script:adbDeadline = $null }
        MemorySample "long-$endpoint"
    }
    Invoke-QaBatchLogSample
    $script:batchStatus = Get-QaLongCoverageStatus $legs.ToArray() $false
    $report.Add('Long endpoints require manual PNG sentinel review; XML full message text is not proof of visible lines')
}
