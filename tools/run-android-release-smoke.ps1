param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][long]$VersionCode,
    [ValidateSet('stable', 'preview')][string]$Channel = 'stable',
    [string]$EvidenceName = 'android-release-smoke-0.1.25'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
if ($Serial -notmatch '^emulator-\d+$') { throw 'This runner operates only on the dedicated QA emulator, never a phone' }
if ($EvidenceName -notmatch '^[A-Za-z0-9._-]{1,80}$') { throw 'Invalid evidence name' }
if ($Version -notmatch '^[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}(?:-[A-Za-z0-9.-]+)?$' -or
    $VersionCode -lt 1 -or $VersionCode -gt [int]::MaxValue) { throw 'Invalid fixed candidate version/code' }
$candidate = [IO.Path]::GetFullPath($Apk)
if (!(Test-Path -LiteralPath $candidate -PathType Leaf)) { throw 'The final fixed formal release candidate does not exist; do not stage an old or synthetic APK' }
$candidateInfo = Get-Item -LiteralPath $candidate
if ($candidateInfo.Length -le 0 -or $candidateInfo.Length -gt 1GB -or
    ($candidateInfo.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Candidate APK size or path is invalid' }
$candidateHash = (Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash.ToLowerInvariant()
$evidenceRoot = Join-Path $tempRoot ('qa\' + $EvidenceName)
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
$env:TMP = $evidenceRoot
$env:TEMP = $evidenceRoot
$runId = [guid]::NewGuid().ToString()
$package = 'com.dafeiyu.mobile.dev'
$adb = (Get-Command adb -ErrorAction Stop).Source
$hostEvidence = [ordered]@{ schema = 1; runId = $runId; apk = $candidate; bytes = $candidateInfo.Length;
    sha256 = $candidateHash; package = 'com.dafeiyu.mobile'; version = $Version; versionCode = $VersionCode; channel = $Channel;
    installationLaunched = $false; permissionsGranted = $false }
[IO.File]::WriteAllText((Join-Path $evidenceRoot 'host-candidate.json'), ($hostEvidence | ConvertTo-Json), [Text.UTF8Encoding]::new($false))

function Invoke-Adb([string[]]$Arguments, [string]$OutputName, [string]$InputFile = '', [int]$TimeoutMs = 60000) {
    $process = [Diagnostics.Process]::new()
    $process.StartInfo.FileName = $adb
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardInput = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $process.StartInfo.ArgumentList.Add($argument) }
    if (!$process.Start()) { throw 'Cannot start ADB' }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $transferFailure = $null
    try {
        if ($InputFile) {
            $inputStream = [IO.File]::OpenRead([IO.Path]::GetFullPath($InputFile))
            $transferCancel = [Threading.CancellationTokenSource]::new(120000)
            try { $inputStream.CopyToAsync($process.StandardInput.BaseStream, $transferCancel.Token).GetAwaiter().GetResult() }
            finally { $inputStream.Dispose(); $transferCancel.Dispose() }
        }
    } catch { $transferFailure = $_ }
    finally { $process.StandardInput.Close() }
    $timedOut = !$process.WaitForExit($TimeoutMs)
    if ($timedOut) { $process.Kill($true); $process.WaitForExit() }
    $out = $stdout.GetAwaiter().GetResult()
    $err = $stderr.GetAwaiter().GetResult()
    [IO.File]::WriteAllText((Join-Path $evidenceRoot ($OutputName + '.log')), $out + $err, [Text.UTF8Encoding]::new($false))
    if ($timedOut) { throw "ADB timed out ($OutputName); see evidence log" }
    if ($process.ExitCode -ne 0) { throw "ADB failed ($OutputName); see evidence log" }
    if ($transferFailure) { throw "ADB input transfer failed ($OutputName); see evidence log" }
    return $out
}

$null = Invoke-Adb @('devices', '-l') 'devices'
$abi = Invoke-Adb @('-s', $Serial, 'shell', 'getprop', 'ro.product.cpu.abilist') 'abi'
if ($abi.Trim().Split(',')[0] -ne 'x86_64') { throw 'The selected QA device does not run native x86_64' }
$avd = Invoke-Adb @('-s', $Serial, 'emu', 'avd', 'name') 'avd'
if (($avd -split "`r?`n")[0].Trim() -ne 'Dafeiyu_QA_API_37') { throw 'Refusing to operate on another AVD' }
$remoteDirectory = "files/android-release-smoke-input/$runId"
$null = Invoke-Adb @('-s', $Serial, 'shell', 'run-as', $package, 'mkdir', '-p', $remoteDirectory) 'mkdir-test-input'
# exec-in streams APK binary bytes without shell text conversion. Only the
# freshly generated test UUID directory is written, never an update candidate
# owned by the production app and never installed by this runner.
$remote = "run-as $package sh -c 'cat > $remoteDirectory/candidate.apk'"
$null = Invoke-Adb @('-s', $Serial, 'exec-in', $remote) 'stage-final-candidate' $candidate 180000
$null = Invoke-Adb @('-s', $Serial, 'shell', 'run-as', $package, 'rm', '-f', 'files/android-release-smoke-report.json') 'remove-own-stale-report'
try {
    $instrumentation = Invoke-Adb @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'com.dafeiyu.mobile.AndroidReleaseSmoke', '-e', 'release_run_id', $runId, '-e', 'release_sha256', $candidateHash,
        '-e', 'release_size', $candidateInfo.Length.ToString([Globalization.CultureInfo]::InvariantCulture),
        '-e', 'release_version', $Version, '-e', 'release_code', $VersionCode.ToString([Globalization.CultureInfo]::InvariantCulture),
        '-e', 'release_channel', $Channel, ($package + '.test/androidx.test.runner.AndroidJUnitRunner')) 'instrumentation' '' 2100000
} finally {
    $report = Invoke-Adb @('-s', $Serial, 'exec-out', 'run-as', $package, 'cat', 'files/android-release-smoke-report.json') 'report'
    [IO.File]::WriteAllText((Join-Path $evidenceRoot 'report.json'), $report, [Text.UTF8Encoding]::new($false))
}
$parsed = $report | ConvertFrom-Json
if (!$parsed.passed -or $parsed.runId -ne $runId -or $parsed.hostPackage -ne $package -or
    $parsed.releasePackage -ne 'com.dafeiyu.mobile' -or $parsed.paidModelRequests -ne 0 -or
    $parsed.installationLaunches -ne 0 -or $parsed.permissionRequests -ne 0 -or
    $parsed.checks.Count -ne 5 -or @($parsed.checks | Where-Object { !$_.passed }).Count -ne 0 -or
    !$parsed.testOwnedCleanup.workRemoved -or !$parsed.testOwnedCleanup.stagedCandidateRemoved -or
    $instrumentation -notmatch 'OK \(1 test\)') {
    throw 'Actual Android formal release smoke failed; a missing candidate/feed is not a skipped PASS. Read report.json and instrumentation.log'
}
Write-Output 'PASS 5 actual Android formal release checks; real signed public feed, final APK identity/verifiers and production/observed HTTPS Range; no install or permission changes'
Write-Output (Join-Path $evidenceRoot 'report.json')
