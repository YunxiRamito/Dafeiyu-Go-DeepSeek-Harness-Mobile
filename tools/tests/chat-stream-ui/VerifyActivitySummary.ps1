param([string]$TempRoot, [string]$JavaRoot, [ValidateRange(128, 512)][int]$HeapMiB = 512)

$ErrorActionPreference = 'Stop'
$runnerToolsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runner = Get-Content -LiteralPath (Join-Path $runnerToolsRoot 'test-chat-stream-ui.ps1') -Raw
# The base runner owns production sources; this wrapper adds only aggregation checks.
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
    '    (Join-Path $fixtureRoot ''AssistantActivityTimelineChecks.kt'')'
)
$runner += @'

& $java -cp "$outputJar;$classpath" com.dafeiyu.mobile.ui.AssistantActivitySummaryChecksKt |
    Tee-Object -FilePath (Join-Path $testRoot 'activity-summary-results.log')
if ($LASTEXITCODE -ne 0) { throw 'Activity summary aggregation checks failed' }

& $java -cp "$outputJar;$classpath" com.dafeiyu.mobile.ui.AssistantWebReceiptChecksKt |
    Tee-Object -FilePath (Join-Path $testRoot 'web-receipt-results.log')
if ($LASTEXITCODE -ne 0) { throw 'Activity web receipt checks failed' }

& $java -cp "$outputJar;$classpath" com.dafeiyu.mobile.ui.AssistantActivityTimelineChecksKt |
    Tee-Object -FilePath (Join-Path $testRoot 'activity-timeline-results.log')
if ($LASTEXITCODE -ne 0) { throw 'Activity timeline checks failed' }
'@
$runner = $runner.Replace('& $java -cp', '& $java "-Xmx${HeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp')
& ([scriptblock]::Create($runner)) -TempRoot $TempRoot -JavaRoot $JavaRoot
