param(
    [Parameter(Mandatory = $true)][ValidateSet('Prepare','Recover')][string]$Phase,
    [Parameter(Mandatory = $true)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-f0-9]{32}$')][string]$RunId,
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedDshManifestSha256,
    [string]$Adb = 'G:/Android SDK/platform-tools/adb.exe'
)
# Installs are separate: use reviewed production-integrated dev/test APKs,
# then run these two opt-in phases in the dedicated QA emulator.
# No install, package data clear, permission grant, downloads or paid models.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$taskRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android/qa/official-cold-0.1.31-device'))
if ([IO.Path]::GetPathRoot($taskRoot).TrimEnd('\','/') -ne 'G:') { throw 'All evidence must remain on G' }
$runRoot = Join-Path $taskRoot ("runs/device-$RunId-$($Phase.ToLowerInvariant())")
if (Test-Path -LiteralPath $runRoot) { throw 'Do not overwrite earlier phase evidence' }
New-Item -ItemType Directory -Path $runRoot | Out-Null
$env:TMP = $runRoot
$env:TEMP = $runRoot
$target = 'com.dafeiyu.mobile.dev'
$component = 'com.dafeiyu.mobile.dev.test/androidx.test.runner.AndroidJUnitRunner'
$testClass = 'com.dafeiyu.mobile.data.OfficialColdDshDeviceSmoke'
$remote = "/sdcard/Android/data/$target/files/qa/official-cold-device-$RunId"
$state = [ordered]@{schema=1; runId=$RunId; phase=$Phase; serial=$Serial; avd='Dafeiyu_QA_API_37'; target=$target;
    expectedDshManifest=$ExpectedDshManifestSha256; startedAt=[DateTimeOffset]::UtcNow.ToString('O');
    passed=$false; completed=$false; packageInstalls=0; appDataClear=$false; permissionGrants=0; paidModelCalls=0;
    mainEvidence=$null; cleanup=$null}
function Persist { $state | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $runRoot 'runner-report.json') -Encoding utf8NoBOM }
function AdbRead([string[]]$Arguments, [string]$LogName) {
    $log = Join-Path $runRoot $LogName
    & $Adb -s $Serial @Arguments *> $log
    if ($LASTEXITCODE -ne 0) { throw "ADB stage failed: $LogName" }
    return [IO.File]::ReadAllText($log)
}
function Instrument([string]$Method,[string]$Name,[string[]]$Extra) {
    $arguments = @('shell','am','instrument','-w','-r','-e','class',"$testClass#$Method",'-e','official_cold_run',$RunId,
        '-e','official_qa_avd','Dafeiyu_QA_API_37','-e','official_dsh_manifest',$ExpectedDshManifestSha256) + $Extra + @($component)
    $output = AdbRead $arguments "$Name-instrumentation.log"
    if ($output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED') {
        throw "No genuine OK(1) instrumentation result: $Name"
    }
    $local = Join-Path $runRoot "$Name.json"
    $null = AdbRead @('pull',"$remote/$Name.json",$local) "$Name-pull.log"
    $document = Get-Content -LiteralPath $local -Raw | ConvertFrom-Json
    if ($document.runId -ne $RunId -or $document.package -ne $target -or
        $document.expectedDshManifest -ne $ExpectedDshManifestSha256 -or !$document.completed -or !$document.passed -or
        @($document.results).Count -eq 0 -or @($document.results | Where-Object {!$_.passed}).Count -ne 0) {
        throw "Android report failed or mismatched: $Name"
    }
    return $document
}
$preflight = $false
$phaseAuthorized = $Phase -eq 'Prepare'
$workSucceeded = $false
$failure = $null
try {
    Persist
    if (!(Test-Path -LiteralPath $Adb -PathType Leaf)) { throw 'ADB missing' }
    $devices = AdbRead @('devices','-l') 'devices.log'
    if ($devices -notmatch ('(?m)^'+[Regex]::Escape($Serial)+'\s+device\b')) { throw 'Specified emulator not ready' }
    $avd = AdbRead @('emu','avd','name') 'avd.log'
    if ($avd -notmatch '(?m)^Dafeiyu_QA_API_37\r?$') { throw 'Dedicated QA AVD only' }
    $abi = (AdbRead @('shell','getprop','ro.product.cpu.abi') 'abi.log').Trim()
    $hardware = (AdbRead @('shell','getprop','ro.hardware') 'hardware.log').Trim()
    if ($abi -ne 'x86_64' -or $hardware -notin @('ranchu','goldfish')) { throw 'Physical and other devices refused' }
    $installed = AdbRead @('shell','pm','path',$target) 'target.log'
    if ($installed -notmatch 'package:.+base\.apk') { throw 'Reviewed dev target must already be installed' }
    $tests = AdbRead @('shell','pm','list','instrumentation') 'instrumentation-package.log'
    if ($tests -notmatch [Regex]::Escape("instrumentation:$component (target=$target)")) { throw 'Matching test package missing' }
    $preflight = $true
    $state.preflight=[ordered]@{passed=$true;abi=$abi;hardware=$hardware;adbSha256=(Get-FileHash -LiteralPath $Adb -Algorithm SHA256).Hash.ToLowerInvariant()}
    Persist
    if ($Phase -eq 'Prepare') {
        $report = Instrument 'prepareColdWritesBeforeActualApplicationRestart' 'prepare' @('-e','official_cold_phase','prepare')
        if (!$report.retainedForRestart -or $report.cleanupComplete -or @($report.results).Count -ne 3) {
            throw 'Prepare must leave only its three proven-owned durable fixtures'
        }
        $state.mainEvidence=[ordered]@{passed=$true;cases=@($report.results).Count;androidPid=$report.pid;retainedForRestart=$true}
    } else {
        # A comment that prepare succeeded is not evidence. Bind the exact
        # retained run and proven cleanup before crossing its process boundary.
        $prepareRoot = Join-Path $taskRoot "runs/device-$RunId-prepare"
        $prepareRunner = Get-Content -LiteralPath (Join-Path $prepareRoot 'runner-report.json') -Raw | ConvertFrom-Json
        $prepareReport = Get-Content -LiteralPath (Join-Path $prepareRoot 'prepare.json') -Raw | ConvertFrom-Json
        if (!$prepareRunner.completed -or !$prepareRunner.passed -or $prepareRunner.runId -ne $RunId -or
            $prepareRunner.phase -ne 'Prepare' -or $prepareRunner.serial -ne $Serial -or
            $prepareRunner.target -ne $target -or $prepareRunner.avd -ne 'Dafeiyu_QA_API_37' -or
            $prepareRunner.expectedDshManifest -ne $ExpectedDshManifestSha256 -or !$prepareRunner.mainEvidence.passed -or
            $prepareRunner.mainEvidence.cases -ne 3 -or !$prepareRunner.mainEvidence.retainedForRestart -or
            !$prepareReport.completed -or !$prepareReport.passed -or $prepareReport.runId -ne $RunId -or
            $prepareReport.package -ne $target -or $prepareReport.phase -ne 'prepare' -or
            $prepareReport.expectedDshManifest -ne $ExpectedDshManifestSha256 -or
            !$prepareReport.retainedForRestart -or $prepareReport.cleanupComplete -or $prepareReport.pid -le 0 -or
            $prepareReport.pid -ne $prepareRunner.mainEvidence.androidPid -or @($prepareReport.results).Count -ne 3 -or
            @($prepareReport.results | Where-Object {!$_.passed}).Count -ne 0) {
            throw 'Matching completed Prepare evidence and proven idle ownership required before force-stop'
        }
        $phaseAuthorized = $true
        $state.prepareEvidence=[ordered]@{runId=$RunId;androidPid=$prepareReport.pid;
            runnerSha256=(Get-FileHash -LiteralPath (Join-Path $prepareRoot 'runner-report.json') -Algorithm SHA256).Hash.ToLowerInvariant();
            reportSha256=(Get-FileHash -LiteralPath (Join-Path $prepareRoot 'prepare.json') -Algorithm SHA256).Hash.ToLowerInvariant()}
        Persist
        # Forces a real application PID boundary, never clears application
        # data and never stops a user service outside the chosen QA dev app.
        # Prepare already proved all its actual helper/native leases closed.
        $null = AdbRead @('shell','am','force-stop',$target) 'between-phases-force-stop.log'
        $report = Instrument 'recoverColdWritesAfterActualApplicationRestart' 'recover' @('-e','official_cold_phase','recover')
        if (!$report.cleanupComplete -or !$report.actualApplicationRestart -or $report.preparePid -eq $report.recoveryPid -or
            @($report.results).Count -ne 12) { throw 'Recovery needs actual distinct Android PIDs and 12 complete cases' }
        $state.mainEvidence=[ordered]@{passed=$true;cases=@($report.results).Count;preparePid=$report.preparePid;
            recoveryPid=$report.recoveryPid;actualApplicationRestart=$true;fixtureCleanupComplete=$true}
    }
    $workSucceeded = $true
} catch {
    $failure = $_
    $state.errorClass = $_.Exception.GetType().FullName
    try { $null = AdbRead @('pull',"$remote/$($Phase.ToLowerInvariant()).json",(Join-Path $runRoot 'failed-android-report.json')) 'failed-report-pull.log' }
    catch { $state.failedReport = 'unavailable' }
} finally {
    if ($preflight -and $phaseAuthorized -and ($Phase -eq 'Recover' -or !$workSucceeded)) {
        try {
            $cleanup = Instrument 'cleanupOwnedColdFixtures' 'cleanup' @('-e','official_cold_cleanup','true')
            $state.cleanup=[ordered]@{attempted=$true;passed=$cleanup.cleanupComplete;ownership='verified UUID only; real lease guard; no alias removal'}
            if (!$cleanup.cleanupComplete) { throw 'Cleanup pending' }
        } catch {
            $state.cleanup=[ordered]@{attempted=$true;passed=$false;errorClass=$_.Exception.GetType().FullName;
                detail='Retain UUID fixture if an actual managed lease is pending; root reviews before retry'}
            if (!$failure) { $failure=$_ }
        }
    } elseif ($workSucceeded) {
        $state.cleanup=[ordered]@{attempted=$false;reason='Successful prepare deliberately retained for exact matching recover phase'}
    } else {
        $state.cleanup=[ordered]@{attempted=$false;reason='No admitted device phase; preserve unmatched or preflight-failed fixture'}
    }
    $state.passed=$workSucceeded -and !$failure
    $state.completed=$true
    $state.finishedAt=[DateTimeOffset]::UtcNow.ToString('O')
    Persist
}
Write-Output "Android cold recovery evidence: $runRoot"
if ($failure) { throw 'Cold DSH phase failed or cleanup pending; inspect recorded fixed-class reports' }
