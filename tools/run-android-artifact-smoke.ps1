param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$EvidenceName = 'android-artifact-smoke-0.1.25'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
if ($Serial -notmatch '^emulator-\d+$') { throw 'This runner operates only on the dedicated QA emulator, never a phone' }
if ($EvidenceName -notmatch '^[A-Za-z0-9._-]{1,80}$') { throw 'Invalid evidence name' }
$evidenceRoot = Join-Path $tempRoot ('qa\' + $EvidenceName)
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
$env:TMP = $evidenceRoot
$env:TEMP = $evidenceRoot
$runId = [guid]::NewGuid().ToString()
$package = 'com.dafeiyu.mobile.dev'
$adb = (Get-Command adb -ErrorAction Stop).Source
$fixedResource = [ordered]@{ schema = 1; runId = $runId; name = 'pnpm'; version = '10.34.5';
    url = 'https://registry.npmjs.org/pnpm/-/pnpm-10.34.5.tgz'; bytes = 4862438;
    sha256 = 'ccb5c479cab1b00621325bfe7d4c9a8a8031e7a525d7249e275ecbec81b08db2';
    integrity = 'sha512-pO4F8vc2WCVb1qiYWcBlpFwopX2u+uLIk6Fo7itzFow3uR6D5X6mdlStA/AwMXRkMOi84442LgQmBfuKvIAZLg==';
    installedOrExecuted = $false; fixtureUploaded = $false }
[IO.File]::WriteAllText((Join-Path $evidenceRoot 'fixed-resource.json'), ($fixedResource | ConvertTo-Json), [Text.UTF8Encoding]::new($false))

function Invoke-Adb([string[]]$Arguments, [string]$OutputName, [int]$TimeoutMs = 60000) {
    $process = [Diagnostics.Process]::new()
    $process.StartInfo.FileName = $adb
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardInput = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $process.StartInfo.ArgumentList.Add($argument) }
    if (!$process.Start()) { throw 'Cannot start ADB' }
    $process.StandardInput.Close()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $timedOut = !$process.WaitForExit($TimeoutMs)
    if ($timedOut) { $process.Kill($true); $process.WaitForExit() }
    $out = $stdout.GetAwaiter().GetResult()
    $err = $stderr.GetAwaiter().GetResult()
    [IO.File]::WriteAllText((Join-Path $evidenceRoot ($OutputName + '.log')), $out + $err, [Text.UTF8Encoding]::new($false))
    if ($timedOut) { throw "ADB timed out ($OutputName); see evidence log" }
    if ($process.ExitCode -ne 0) { throw "ADB failed ($OutputName); see evidence log" }
    return $out
}

$null = Invoke-Adb @('devices', '-l') 'devices'
$abi = Invoke-Adb @('-s', $Serial, 'shell', 'getprop', 'ro.product.cpu.abilist') 'abi'
if ($abi.Trim().Split(',')[0] -ne 'x86_64') { throw 'The selected QA device does not run native x86_64' }
$avd = Invoke-Adb @('-s', $Serial, 'emu', 'avd', 'name') 'avd'
if (($avd -split "`r?`n")[0].Trim() -ne 'Dafeiyu_QA_API_37') { throw 'Refusing to operate on another AVD' }
$null = Invoke-Adb @('-s', $Serial, 'shell', 'run-as', $package, 'rm', '-f', 'files/android-artifact-smoke-report.json') 'remove-own-stale-report'
try {
    $instrumentation = Invoke-Adb @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'com.dafeiyu.mobile.AndroidArtifactDownloadSmoke', '-e', 'artifact_run_id', $runId,
        ($package + '.test/androidx.test.runner.AndroidJUnitRunner')) 'instrumentation' 1000000
} finally {
    $report = Invoke-Adb @('-s', $Serial, 'exec-out', 'run-as', $package, 'cat', 'files/android-artifact-smoke-report.json') 'report'
    [IO.File]::WriteAllText((Join-Path $evidenceRoot 'report.json'), $report, [Text.UTF8Encoding]::new($false))
}
$parsed = $report | ConvertFrom-Json
if (!$parsed.passed -or $parsed.runId -ne $runId -or $parsed.hostPackage -ne $package -or
    $parsed.paidModelRequests -ne 0 -or $parsed.installationLaunches -ne 0 -or $parsed.permissionRequests -ne 0 -or
    $parsed.fixtureUploads -ne 0 -or $parsed.fixedResource.sha256 -ne $fixedResource.sha256 -or
    $parsed.checks.Count -ne 3 -or @($parsed.checks | Where-Object { !$_.passed }).Count -ne 0 -or
    !$parsed.testOwnedCleanup -or $instrumentation -notmatch 'OK \(1 test\)') {
    throw 'Actual Android public artifact smoke failed; read report.json and instrumentation.log. Missing Range or resume is not a skipped PASS'
}
Write-Output 'PASS 3 actual Android production public artifact checks; fixed four-part TLS download, real network cancellation and validator/offset-bound resume; no install or permission changes'
Write-Output (Join-Path $evidenceRoot 'report.json')
