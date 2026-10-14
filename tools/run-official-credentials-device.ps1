param(
    [Parameter(Mandatory = $true)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [string]$Adb = 'G:/Android SDK/platform-tools/adb.exe',
    [switch]$IncludeRealDsh,
    [switch]$DshOnly,
    [ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedDshManifestSha256
)
# Runs only the explicitly selected dedicated QA emulator; build/install is separate.
# Does not install packages, clear data, grant permissions or run model calls.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$evidenceRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android/qa/official-credentials-device'))
$runId = [Guid]::NewGuid().ToString('N')
$runRoot = Join-Path $evidenceRoot ('device-' + $runId)
New-Item -ItemType Directory -Path $runRoot | Out-Null
$env:TMP = $runRoot
$env:TEMP = $runRoot
$target = 'com.dafeiyu.mobile.dev'
$testComponent = 'com.dafeiyu.mobile.dev.test/androidx.test.runner.AndroidJUnitRunner'
$testClass = 'com.dafeiyu.mobile.data.OfficialCredentialDeviceSmoke'
$remoteReports = "/sdcard/Android/data/$target/files/qa/official-credential-device-$runId"
$stages = [Collections.Generic.List[object]]::new()
$record = [ordered]@{ schema = 1; runId = $runId; serial = $Serial; avd = 'Dafeiyu_QA_API_37'; target = $target;
    passed = $false; completed = $false; startedAt = [DateTimeOffset]::UtcNow.ToString('O'); stages = $stages;
    realDshRequested = [bool]$IncludeRealDsh; realDshExecuted = $false; expectedDshManifestSha256 = $ExpectedDshManifestSha256;
    nativeScopesRequested = !$DshOnly;
    modelRequests = 0; permissionGrants = 0; packageInstalls = 0; appDataClear = $false }
function Persist {
    $record | ConvertTo-Json -Depth 18 | Set-Content -LiteralPath (Join-Path $runRoot 'runner-report.json') -Encoding utf8NoBOM
}
function ReadAdb([string[]]$Arguments, [string]$LogName) {
    $log = Join-Path $runRoot $LogName
    & $Adb -s $Serial @Arguments *> $log
    if ($LASTEXITCODE -ne 0) { throw "ADB step failed: $LogName" }
    return [IO.File]::ReadAllText($log)
}
function FetchReport([string]$ReportName) {
    $local = Join-Path $runRoot ($ReportName + '.json')
    $null = ReadAdb @('pull', "$remoteReports/$ReportName.json", $local) ($ReportName + '-pull.log')
    $document = Get-Content -LiteralPath $local -Raw | ConvertFrom-Json
    if ($document.runId -ne $runId -or $document.package -ne $target -or !$document.completed -or !$document.passed) {
        throw "Android report failed or mismatched: $ReportName"
    }
    if (@($document.results).Count -eq 0 -or @($document.results | Where-Object { !$_.passed }).Count -ne 0) {
        throw "Android case evidence missing or failed: $ReportName"
    }
    if ($ReportName -eq 'restart') {
        if (!$document.retainedForRestartPhase -or $document.cleanupComplete) { throw 'Prepare must explicitly retain only its owned fixture' }
    } elseif ($ReportName -ne 'cleanup' -and !$document.cleanupComplete) {
        throw "Android UUID cleanup incomplete: $ReportName"
    }
    return $document
}
function Instrument([string]$Method, [string]$ReportName, [string[]]$Extra = @()) {
    $stage = [ordered]@{ method = $Method; report = $ReportName; passed = $false; startedAt = [DateTimeOffset]::UtcNow.ToString('O') }
    $stages.Add($stage); Persist
    $arguments = @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', "$testClass#$Method", '-e', 'official_qa_run', $runId,
        '-e', 'official_qa_avd', 'Dafeiyu_QA_API_37') + $Extra + @($testComponent)
    try {
        $output = ReadAdb $arguments ($ReportName + '-instrumentation.log')
        if ($output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED') {
            throw "Instrumentation did not report genuine OK(1): $ReportName"
        }
        $document = FetchReport $ReportName
        $stage.passed = $true
        $stage.cases = @($document.results).Count
        $stage.instrumentationPid = $document.pid
    } catch {
        $stage.errorClass = $_.Exception.GetType().FullName
        # Preserve a failed/incomplete report when one exists; never treat
        # a directory, screenshot or shell exit status as instrumentation PASS.
        try {
            $null = ReadAdb @('pull', "$remoteReports/$ReportName.json", (Join-Path $runRoot ($ReportName + '-failed.json'))) ($ReportName + '-failed-pull.log')
        } catch { $stage.failedReportRetrieval = 'unavailable' }
        throw
    } finally {
        $stage.finishedAt = [DateTimeOffset]::UtcNow.ToString('O'); Persist
    }
}
$preflightPassed = $false
$workPassed = $false
$mainFailure = $null
try {
    if (!(Test-Path -LiteralPath $Adb -PathType Leaf)) { throw 'ADB binary absent' }
    if ($DshOnly -and !$IncludeRealDsh) { throw 'DshOnly requires IncludeRealDsh and its reviewed installed manifest digest' }
    if ($IncludeRealDsh -and !$ExpectedDshManifestSha256) { throw 'Real DSH requires the exact already-installed reviewed manifest digest' }
    # Every ADB command names the selected device. Never pick a first device
    # or fallback to the user's AVD/PKB110 when this preflight fails.
    $devices = ReadAdb @('devices', '-l') 'devices.log'
    if ($devices -notmatch ('(?m)^' + [Regex]::Escape($Serial) + '\s+device\b')) { throw 'Requested emulator is not ready' }
    $avd = ReadAdb @('emu', 'avd', 'name') 'avd.log'
    if ($avd -notmatch '(?m)^Dafeiyu_QA_API_37\r?$') { throw 'Only the dedicated Dafeiyu_QA_API_37 may be used' }
    $abi = (ReadAdb @('shell', 'getprop', 'ro.product.cpu.abi') 'abi.log').Trim()
    $hardware = (ReadAdb @('shell', 'getprop', 'ro.hardware') 'hardware.log').Trim()
    if ($abi -ne 'x86_64' -or $hardware -notin @('ranchu', 'goldfish')) { throw 'Physical and other architecture devices are refused' }
    $installed = ReadAdb @('shell', 'pm', 'path', $target) 'target.log'
    if ($installed -notmatch 'package:.+base\.apk') { throw 'Root must install the reviewed debug target first' }
    $instrumentation = ReadAdb @('shell', 'pm', 'list', 'instrumentation') 'test-package.log'
    if ($instrumentation -notmatch ([Regex]::Escape("instrumentation:$testComponent (target=$target)"))) { throw 'Matching Android instrumentation package is not installed' }
    $record.preflight = [ordered]@{ passed = $true; abi = $abi; hardware = $hardware; adbSha256 = (Get-FileHash -LiteralPath $Adb).Hash.ToLowerInvariant() }
    $preflightPassed = $true; Persist
    if (!$DshOnly) {
        Instrument 'nativeVaultRoundtripAndActualAtomicFileRecovery' 'native'
        Instrument 'persistedRecoveryGatesAndExplicitLegacyResolution' 'recovery'
        Instrument 'prepareDurableFailureForActualProcessRestart' 'restart' @('-e', 'official_restart_phase', 'prepare')
        $null = ReadAdb @('shell', 'am', 'force-stop', $target) 'between-native-phases-force-stop.log'
        Instrument 'recoverDurableFailureAfterActualProcessRestart' 'restart-recovered' @('-e', 'official_restart_phase', 'recover')
        $prepare = Get-Content -LiteralPath (Join-Path $runRoot 'restart.json') -Raw | ConvertFrom-Json
        $recover = Get-Content -LiteralPath (Join-Path $runRoot 'restart-recovered.json') -Raw | ConvertFrom-Json
        if ($prepare.pid -eq $recover.pid) { throw 'Different Android process evidence is required' }
        $record.actualNativeProcessRestart = [ordered]@{ passed = $true; preparePid = $prepare.pid; recoveryPid = $recover.pid }
    }
    if ($IncludeRealDsh) {
        Instrument 'deferredPublicationWithActualPinnedDshAndRealCas' 'dsh' @('-e', 'official_real_dsh', 'true', '-e', 'official_dsh_manifest', $ExpectedDshManifestSha256)
        $record.realDshExecuted = $true
    }
    $workPassed = $true
} catch {
    $mainFailure = $_
    $record.errorClass = $_.Exception.GetType().FullName
} finally {
    if ($preflightPassed) {
        try {
            Instrument 'cleanupOwnedFixtures' 'cleanup' @('-e', 'official_cleanup', 'true')
            $record.cleanup = [ordered]@{ attempted = $true; passed = $true; ownership = 'UUID marker; no broad shell delete or Keystore alias removal' }
        } catch {
            $record.cleanup = [ordered]@{ attempted = $true; passed = $false; errorClass = $_.Exception.GetType().FullName;
                detail = 'Inspect retained QA fixture; possible runtime lease is never overridden' }
            if (!$mainFailure) { $mainFailure = $_ }
        }
    } else {
        $record.cleanup = [ordered]@{ attempted = $false; reason = 'Device preflight failed before instrumentation' }
    }
    $record.passed = $workPassed -and !$mainFailure
    $record.completed = $true
    $record.finishedAt = [DateTimeOffset]::UtcNow.ToString('O')
    Persist
}
Write-Output "Device candidate evidence: $runRoot"
if ($mainFailure) { throw 'Official credential device candidate did not pass; see runner-report.json and individual logs' }
Write-Output 'Real Android instrumentation passed for requested scopes only; visual/backend/user acceptance is separate.'
