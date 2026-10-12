#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$qa = [IO.Path]::GetFullPath((Join-Path $project '../Temp_Android/qa/emulator-0.1.18'))
$evidence = Join-Path $qa 'activity-018-11b0f8b60fc84e3384dd5b23e1b4e6af'
$runnerPath = Join-Path $qa 'verify-activity-receipts.ps1'
$commonPath = Join-Path $qa 'activity-qa-common.ps1'
. $commonPath
$passed = 0

function Check([string]$Name, [scriptblock]$Action) {
    & $Action
    $script:passed++
    Write-Output "PASS $Name"
}
function Reject([scriptblock]$Action, [string]$Message) {
    $rejected = $false
    try { & $Action } catch {
        Assert-Qa ($_.Exception.Message.Contains($Message)) "Unexpected failure: $($_.Exception.Message)"
        $rejected = $true
    }
    Assert-Qa $rejected "Expected rejection: $Message"
}
function Read-Dump([string]$Name) {
    return [xml](Get-Content -LiteralPath (Join-Path $evidence $Name) -Raw -Encoding utf8)
}
$asts = @{}
Check 'PowerShell parse; runner top-level never executed' {
    foreach ($path in @($runnerPath, $commonPath, (Join-Path $qa 'prepare-production-web-smoke.ps1'), $PSCommandPath)) {
        $tokens = $null; $errors = $null
        $asts[$path] = [Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
        Assert-Qa (@($errors).Count -eq 0) "Parse failed: $path"
    }
}
# Import only pure selection / mocked interaction functions, never the device entry point.
foreach ($name in @('FindVisible', 'SwipeList', 'Tap', 'Composer')) {
    $definition = $asts[$runnerPath].Find({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true)
    Assert-Qa ($null -ne $definition) "Missing runner function: $name"
    . ([scriptblock]::Create($definition.Extent.Text))
}
$expectedConversationTitle = 'QA 018 grouped activities'
$expectedMetricSentinels = @('轮数 2', '工具步骤 13', 'Tokens 777777', '耗时 125.0 s')
$clean = Read-Dump 'completed-collapsed-True-0.xml'
$driftSidebar = Read-Dump 'grouped-expanded-True-2.xml'
$driftRunning = Read-Dump 'grouped-expanded-True-3.xml'
$driftMetrics = Read-Dump 'grouped-expanded-True-7.xml'
$calls = [Collections.Generic.List[string]]::new()
function Adb([string[]]$Arguments) { $calls.Add($Arguments -join ' ') }
$frames = [Collections.Generic.Queue[xml]]::new()
function Capture([string]$Name) {
    Assert-Qa ($frames.Count -gt 0) 'Mock capture queue exhausted'
    return $frames.Dequeue()
}
Check 'recorded good chat selects vertical surface, excludes horizontal metrics' {
    Assert-QaActivityChat $clean $expectedConversationTitle $expectedMetricSentinels
    $node = Get-QaChatScrollSurface $clean
    Assert-Qa ($node.GetAttribute('bounds') -ceq '[0,231][1080,1848]') 'Wrong scroll surface'
    Assert-Qa ((Get-QaEditableNode $clean).GetAttribute('class') -ceq 'android.widget.EditText') 'Editable ancestor not resolved'
}
Check 'all recorded chat surfaces selected; sidebar rejected without coordinate fallback' {
    foreach ($file in Get-ChildItem -LiteralPath $evidence -Filter '*.xml' -File) {
        $doc = [xml](Get-Content -LiteralPath $file.FullName -Raw -Encoding utf8)
        if ($file.Name -eq 'grouped-expanded-True-2.xml') {
            Reject { Get-QaChatScrollSurface $doc } 'Composer missing'
        } else {
            Assert-Qa ($null -ne (Get-QaChatScrollSurface $doc)) "Missing surface: $($file.Name)"
        }
    }
}
Check 'recorded sidebar, unexpected generation and changed metrics fail closed' {
    Reject { Assert-QaActivityChat $driftSidebar $expectedConversationTitle $expectedMetricSentinels } 'sidebar is open'
    Reject { Assert-QaActivityChat $driftRunning $expectedConversationTitle $expectedMetricSentinels } 'unexpected generation'
    Reject { Assert-QaActivityChat $driftMetrics $expectedConversationTitle $expectedMetricSentinels } 'synthetic metric missing'
}
Check 'FindVisible returns present fixture label without interaction' {
    $calls.Clear()
    $frames.Enqueue($clean)
    $result = FindVisible 'QA_FINAL_REPLY_018' 'offline-found' $false -Contains
    Assert-Qa ($result.Node.GetAttribute('text').Contains('QA_FINAL_REPLY_018')) 'Wrong label'
    Assert-Qa ($calls.Count -eq 0) 'Present label caused input'
}
Check 'FindVisible stops at scene drift before any swipe' {
    $frames.Enqueue($driftSidebar)
    Reject { FindVisible 'missing-label' 'offline-sidebar' } 'sidebar is open'
    Assert-Qa ($calls.Count -eq 0) 'Scene drift caused input'
}
$noSurface = [xml]$clean.OuterXml
$vertical = Get-QaChatScrollSurface $noSurface
$vertical.SetAttribute('scrollable', 'false')
Check 'horizontal-only frame is not mistaken for chat scrolling' {
    Assert-Qa ($null -eq (Get-QaChatScrollSurface $noSurface)) 'Metrics selected as chat'
    # Missing labels exercise bounded no-gesture recaptures.
    1..3 | ForEach-Object { $frames.Enqueue($noSurface) }
    Reject { FindVisible 'missing-label' 'offline-no-surface' } 'after 3 captures'
    Assert-Qa ($calls.Count -eq 0) 'Absent vertical surface caused input'
    (Get-QaNode $noSurface 'QA_FINAL_REPLY_018' -Contains).SetAttribute('text', 'TRANSIENT_UNRELATED_CONTENT')
    $frames.Enqueue($noSurface); $frames.Enqueue($noSurface); $frames.Enqueue($clean)
    $null = FindVisible 'QA_FINAL_REPLY_018' 'offline-transient-found' $false -Contains
    Assert-Qa ($frames.Count -eq 0 -and $calls.Count -eq 0) 'Transient recapture did not recover without input'
}
Check 'mocked reverse-layout swipe stays within recorded chat bounds' {
    $calls.Clear()
    SwipeList $clean $true
    SwipeList $clean $false
    Assert-Qa ($calls.Count -eq 2) 'Expected two mocked swipes'
    Assert-Qa ($calls[0] -ceq 'shell input swipe 540 554 540 1525 400') 'Earlier swipe geometry differs'
    Assert-Qa ($calls[1] -ceq 'shell input swipe 540 1525 540 554 400') 'Later swipe geometry differs'
}
Check 'send and stop controls are rejected by Tap' {
    $calls.Clear()
    $disabledSend = [xml]'<hierarchy><node content-desc="发送消息" enabled="false" bounds="[0,0][100,100]"/></hierarchy>'
    Reject { Tap $disabledSend.hierarchy.node } 'QA action is disabled'
    $enabledSend = [xml]'<hierarchy><node content-desc="发送消息" enabled="true" bounds="[0,0][100,100]"/></hierarchy>'
    Reject { Tap $enabledSend.hierarchy.node } 'may not send'
    $sendDescendant = [xml]'<hierarchy><node content-desc="发送消息" enabled="true"><node enabled="true" bounds="[0,0][100,100]"/></node></hierarchy>'
    Reject { Tap $sendDescendant.hierarchy.node.node } 'may not send'
    Reject { Tap (Get-QaNode $driftRunning '停止生成') } 'may not send'
    Assert-Qa ($calls.Count -eq 0) 'Forbidden control caused input'
}
Check 'both installed APK regexes accept tilde and reject shell metacharacters' {
    foreach ($path in @($runnerPath, (Join-Path $qa 'prepare-production-web-smoke.ps1'))) {
        $patterns = @($asts[$path].FindAll({
            param($node)
            $node -is [Management.Automation.Language.StringConstantExpressionAst] -and
                ($node.Value.StartsWith('^/data/app/') -or $node.Value.StartsWith('^package:(/data/app/'))
        }, $true))
        Assert-Qa ($patterns.Count -eq 1) "Expected one installed-path regex: $path"
        $prefix = if ($patterns[0].Value.StartsWith('^package:')) { 'package:' } else { '' }
        $valid = $prefix + '/data/app/~~abc_1+==/com.dafeiyu.mobile.preview-abc==/base.apk'
        Assert-Qa ($valid -match $patterns[0].Value) 'Tilde path rejected'
        foreach ($bad in @(' ', ';', "'", '$', '`', '\')) {
            Assert-Qa (($valid.Replace('abc_1', "abc${bad}1")) -notmatch $patterns[0].Value) 'Unsafe path accepted'
        }
    }
}
Check 'existing local synthetic fixture schema remains valid' {
    $null = Test-ActivityFixture (Join-Path $qa 'fixtures')
}
Write-Output "RESULT $passed PASS / 0 FAIL; saved XML, pure helpers and mocked input only; no ADB/Gradle/network."
