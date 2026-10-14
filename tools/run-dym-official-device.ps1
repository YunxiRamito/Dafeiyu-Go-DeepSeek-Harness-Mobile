param(
    [Parameter(Mandatory = $true)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [string]$Adb = 'G:/Android SDK/platform-tools/adb.exe'
)
# Build/install is separate. Only the explicitly selected dedicated QA emulator is allowed.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$evidenceRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android/qa/dym-official-device'))
$runId = [Guid]::NewGuid().ToString('N')
$runRoot = Join-Path $evidenceRoot ('device-' + $runId)
New-Item -ItemType Directory -Path $runRoot | Out-Null
$env:TEMP = $runRoot
$env:TMP = $runRoot
$env:TMPDIR = $runRoot
$target = 'com.dafeiyu.mobile.dev'
$testComponent = 'com.dafeiyu.mobile.dev.test/androidx.test.runner.AndroidJUnitRunner'
$testMethod = 'com.dafeiyu.mobile.data.DymOfficialCredentialDeviceSmoke#realSevenZipCoordinatorAndNativeCredentials'
$remoteReport = "/sdcard/Android/data/$target/files/qa/dym-official-device-$runId/report.json"
$receipt = [ordered]@{
    schema = 1; runId = $runId; serial = $Serial; avd = 'Dafeiyu_QA_API_37'; target = $target
    startedAt = [DateTimeOffset]::UtcNow.ToString('O'); passed = $false; completed = $false
    appDataClear = $false; packageInstalls = 0; permissionGrants = 0; explicitModelCalls = 0
    actualProcessRestart = $false; yellowWarningVisualVerified = $false
}
function Persist {
    $receipt | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath (Join-Path $runRoot 'runner-report.json') -Encoding utf8NoBOM
}
function ReadAdb([string[]]$Arguments, [string]$LogName) {
    $log = Join-Path $runRoot $LogName
    & $Adb -s $Serial @Arguments *> $log
    if ($LASTEXITCODE -ne 0) { throw "ADB step failed: $LogName" }
    return [IO.File]::ReadAllText($log)
}
$failure = $null
$started = $false
try {
    Persist
    if (!(Test-Path -LiteralPath $Adb -PathType Leaf)) { throw 'ADB binary absent' }
    $devices = ReadAdb @('devices', '-l') 'devices.log'
    if ($devices -notmatch ('(?m)^' + [Regex]::Escape($Serial) + '\s+device\b')) { throw 'Requested QA emulator is not ready' }
    $avd = ReadAdb @('emu', 'avd', 'name') 'avd.log'
    if ($avd -notmatch '(?m)^Dafeiyu_QA_API_37\r?$') { throw 'Only the dedicated QA AVD may be used' }
    $abi = (ReadAdb @('shell', 'getprop', 'ro.product.cpu.abi') 'abi.log').Trim()
    $hardware = (ReadAdb @('shell', 'getprop', 'ro.hardware') 'hardware.log').Trim()
    if ($abi -ne 'x86_64' -or $hardware -notin @('ranchu', 'goldfish')) { throw 'Physical or other architecture devices are refused' }
    $installed = ReadAdb @('shell', 'pm', 'path', $target) 'target.log'
    if ($installed -notmatch 'package:.+base\.apk') { throw 'Reviewed debug target must already be installed' }
    $instrumentation = ReadAdb @('shell', 'pm', 'list', 'instrumentation') 'test-package.log'
    if ($instrumentation -notmatch ([Regex]::Escape("instrumentation:$testComponent (target=$target)"))) {
        throw 'Matching instrumentation package is not installed'
    }
    $receipt.preflight = [ordered]@{ passed = $true; abi = $abi; hardware = $hardware }
    Persist
    $started = $true
    $output = ReadAdb @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $testMethod,
        '-e', 'dym_qa_run', $runId, '-e', 'dym_qa_avd', 'Dafeiyu_QA_API_37',
        '-e', 'dym_qa_opt_in', 'true', $testComponent) 'instrumentation.log'
    if ($output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED') {
        throw 'Instrumentation did not report genuine OK(1)'
    }
    $local = Join-Path $runRoot 'report.json'
    $null = ReadAdb @('pull', $remoteReport, $local) 'report-pull.log'
    $document = Get-Content -LiteralPath $local -Raw | ConvertFrom-Json
    if ($document.runId -ne $runId -or $document.package -ne $target -or !$document.completed -or !$document.passed) {
        throw 'Actual Android report failed or mismatched'
    }
    if (!$document.cleanupComplete -or !$document.commonStatePreserved -or !$document.commonKeystoreAliasesPreserved) {
        throw 'Owned cleanup or common storage protection failed'
    }
    if (@($document.results).Count -ne 9 -or @($document.results | Where-Object { !$_.passed }).Count -ne 0) {
        throw 'Actual Android case evidence is missing or failed'
    }
    if ($document.explicitModelCalls -ne 0 -or $document.explicitRemoteServiceCalls -ne 0 -or
        $document.runtimeInstalls -ne 0 -or $document.permissionGrants -ne 0 -or $document.activityLaunches -ne 0) {
        throw 'Unexpected side effects were reported'
    }
    $receipt.passed = $true
    $receipt.cases = @($document.results).Count
    $receipt.instrumentationPid = $document.pid
    $receipt.cleanupComplete = $true
    $receipt.commonStatePreserved = $true
    $receipt.commonKeystoreAliasesPreserved = $true
} catch {
    $failure = $_
    $receipt.errorClass = $_.Exception.GetType().FullName
    if ($started) {
        try { $null = ReadAdb @('pull', $remoteReport, (Join-Path $runRoot 'failed-report.json')) 'failed-report-pull.log' }
        catch { $receipt.failedReportRetrieval = 'unavailable' }
    }
} finally {
    $receipt.completed = $true
    $receipt.finishedAt = [DateTimeOffset]::UtcNow.ToString('O')
    Persist
}
Write-Output "Real Android DYM evidence: $runRoot"
if ($failure) { throw 'Android DYM verification failed; inspect retained evidence without clearing application data' }
Write-Output 'Actual Android coordinator/vault/7z checks passed. Cross-process, visual and user acceptance remain separate.'
