param([string]$TempRoot, [string]$JavaRoot, [ValidateRange(128, 512)][int]$HeapMiB = 512)

$ErrorActionPreference = 'Stop'
$runnerToolsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runner = Get-Content -LiteralPath (Join-Path $runnerToolsRoot 'test-chat-stream-ui.ps1') -Raw
# Reuse only cached compiler/API setup; include real production sources and focused checks.
$runner = $runner.Replace('$PSScriptRoot', '$runnerToolsRoot')
$runner = $runner.Replace(
    '(Join-Path $sourceRoot ''ui\AssistantActivitySummary.kt''),',
    '(Join-Path $sourceRoot ''ui\AssistantActivitySummary.kt''),' + [Environment]::NewLine +
    '    (Join-Path $sourceRoot ''ui\ActivityReceiptDetails.kt''),'
)
$runner = $runner.Replace(
    '(Join-Path $fixtureRoot ''ChatStreamUiChecks.kt'')',
    '(Join-Path $fixtureRoot ''ChatStreamUiChecks.kt''),' + [Environment]::NewLine +
    '    (Join-Path $fixtureRoot ''AssistantActivitySummaryChecks.kt''),' + [Environment]::NewLine +
    '    (Join-Path $fixtureRoot ''AssistantWebReceiptChecks.kt''),' + [Environment]::NewLine +
    '    (Join-Path $fixtureRoot ''AssistantActivityTimelineChecks.kt''),' + [Environment]::NewLine +
    '    (Join-Path $fixtureRoot ''ThinkingReceiptChecks.kt''),' + [Environment]::NewLine +
    '    (Join-Path $runnerToolsRoot ''tests\activity-details\ActivityReceiptDetailsChecks.kt'')'
)
$runner += @'

foreach ($check in @('AssistantActivitySummaryChecks', 'AssistantWebReceiptChecks',
    'AssistantActivityTimelineChecks', 'ThinkingReceiptChecks', 'ActivityReceiptDetailsChecks')) {
    & $java -cp "$outputJar;$classpath" "com.dafeiyu.mobile.ui.${check}Kt" 2>&1 |
        Tee-Object -FilePath (Join-Path $testRoot "$check-results.log")
    if ($LASTEXITCODE -ne 0) { throw "Focused check failed: $check" }
}
'@
$runner = $runner.Replace('& $java -cp', '& $java "-Xmx${HeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp')
& ([scriptblock]::Create($runner)) -TempRoot $TempRoot -JavaRoot $JavaRoot
