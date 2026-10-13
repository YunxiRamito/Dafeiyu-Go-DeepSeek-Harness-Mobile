param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Fixture = (Join-Path $PSScriptRoot '..\..\Temp_Android\qa\dym-pc-parity\fixtures\pc-original.dym'),
    [string]$EvidenceName = 'dym-cross-platform-smoke-0.1.25'
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'This test only operates on the dedicated QA emulator.' }
if ($EvidenceName -notmatch '^[A-Za-z0-9._-]{1,80}$') { throw 'Invalid evidence name' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$evidenceRoot = Join-Path $tempRoot ('qa\' + $EvidenceName)
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
$env:TEMP = $evidenceRoot
$env:TMP = $evidenceRoot
$adb = (Get-Command adb -ErrorAction Stop).Source
$package = 'com.dafeiyu.mobile.dev'
function Invoke-Adb([string[]]$Arguments, [string]$Name, [int]$TimeoutMs = 60000) {
    $process = [Diagnostics.Process]::new()
    $process.StartInfo.FileName = $adb
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $process.StartInfo.ArgumentList.Add($argument) }
    if (!$process.Start()) { throw 'Cannot start ADB' }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $timedOut = !$process.WaitForExit($TimeoutMs)
    if ($timedOut) { $process.Kill($true); $process.WaitForExit() }
    $output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
    [IO.File]::WriteAllText((Join-Path $evidenceRoot ($Name + '.log')), $output, [Text.UTF8Encoding]::new($false))
    if ($timedOut -or $process.ExitCode -ne 0) { throw "ADB failed or timed out ($Name); read the evidence log" }
    return $output
}
$null = Invoke-Adb @('devices', '-l') 'devices'
$abi = Invoke-Adb @('-s', $Serial, 'shell', 'getprop', 'ro.product.cpu.abilist') 'abi'
if ($abi.Trim().Split(',')[0] -ne 'x86_64') { throw 'Expected native x86_64 QA ABI' }
$avd = Invoke-Adb @('-s', $Serial, 'emu', 'avd', 'name') 'avd'
if (($avd -split "`r?`n")[0].Trim() -ne 'Dafeiyu_QA_API_37') { throw 'Refusing another AVD' }
$Fixture = [IO.Path]::GetFullPath($Fixture)
if (!(Test-Path -LiteralPath $Fixture -PathType Leaf) -or
    (Get-Item -LiteralPath $Fixture).Length -ne 1395 -or
    (Get-FileHash -LiteralPath $Fixture -Algorithm SHA256).Hash.ToLowerInvariant() -ne
    '07ca0e16cca4b80b21cc604747be9496bf3f24a68effea019b33075b31f2a133') {
    throw 'Fixed PC 7zr input fixture size or SHA mismatch'
}
$remoteRoot = '/sdcard/Android/data/' + $package + '/files'
$remoteInput = $remoteRoot + '/dym-parity-input.dym'
$remoteOutput = $remoteRoot + '/dym-parity-device-output.dym'
$null = Invoke-Adb @('-s', $Serial, 'shell', 'mkdir', '-p', $remoteRoot) 'prepare-fixture-directory'
$null = Invoke-Adb @('-s', $Serial, 'push', $Fixture, $remoteInput) 'push-fixture'
$null = Invoke-Adb @('-s', $Serial, 'shell', 'run-as', $package, 'rm', '-f',
    'files/dym-cross-platform-smoke-report.json') 'remove-own-stale-report'
$null = Invoke-Adb @('-s', $Serial, 'shell', 'rm', '-f', $remoteOutput) 'remove-own-stale-export'
$started = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
try {
    $instrumentation = Invoke-Adb @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'com.dafeiyu.mobile.data.DymCrossPlatformSmoke#pcArchiveAndActualAndroidStores',
        '-e', 'pc_archive', $remoteInput,
        ($package + '.test/androidx.test.runner.AndroidJUnitRunner')) 'instrumentation' 900000
} finally {
    $reportText = Invoke-Adb @('-s', $Serial, 'exec-out', 'run-as', $package, 'cat',
        'files/dym-cross-platform-smoke-report.json') 'report'
    [IO.File]::WriteAllText((Join-Path $evidenceRoot 'report.json'), $reportText, [Text.UTF8Encoding]::new($false))
}
$report = $reportText | ConvertFrom-Json
if (!$report.passed -or $report.schema -ne 1 -or $report.paidModelRequests -ne 0 -or
    $report.checks.Count -ne 6 -or @($report.checks | Where-Object { !$_.passed }).Count -ne 0 -or
    !$report.testStoresRemoved -or !$report.testRuntimeLeasesReleased -or !$report.platformKeyAliasesPreserved -or
    $report.output -notin @($remoteOutput, ('/storage/emulated/0/Android/data/' + $package + '/files/dym-parity-device-output.dym')) -or
    $report.startedAt -lt $started - 600000 -or
    $report.finishedAt -lt $report.startedAt -or $instrumentation -notmatch 'OK \(1 test\)') {
    throw 'Real Android .dym check failed; read report.json and instrumentation.log'
}
$output = Join-Path $evidenceRoot 'dym-parity-device-output.dym'
$null = Invoke-Adb @('-s', $Serial, 'pull', $remoteOutput, $output) 'pull-device-export'
$bytes = [IO.File]::ReadAllBytes($output)
if ($bytes.Length -lt 6 -or [Convert]::ToHexString($bytes[0..5]) -ne '377ABCAF271C') {
    throw 'Device export is not genuine sevenzip'
}
$artifact = [ordered]@{
    source = 'actual Android production ContentResolver exporter with file URI'; bytes = $bytes.Length
    sha256 = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
    inputBytes = (Get-Item -LiteralPath $Fixture).Length
    inputSha256 = (Get-FileHash -LiteralPath $Fixture -Algorithm SHA256).Hash.ToLowerInvariant()
    paidModelRequests = 0; pcRestoreVerified = $false
}
[IO.File]::WriteAllText((Join-Path $evidenceRoot 'export-artifact.json'), ($artifact | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
$null = Invoke-Adb @('-s', $Serial, 'shell', 'rm', '-f', $remoteInput, $remoteOutput) 'clean-own-external-fixtures'
Write-Output 'PASS 6 real Android .dym checks; PC 7zr input, actual Keystore/preferences/history, idle credential lease and genuine ContentResolver sevenzip export. Zero paid requests; PC restore of this device export and SAF document-provider authorization still require separate verification.'
Write-Output $output
