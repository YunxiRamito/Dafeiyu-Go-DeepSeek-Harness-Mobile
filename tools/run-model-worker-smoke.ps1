param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$CacheArchive = (Join-Path $PSScriptRoot '..\..\Temp_Android\qa\model-worker-android-fixtures\model-worker-cache.zip'),
    [string]$EvidenceName = 'model-worker-smoke-0.1.25'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
if ($Serial -notmatch '^emulator-\d+$') { throw 'This script operates only on the dedicated QA emulator, never a phone.' }
if ($EvidenceName -notmatch '^[A-Za-z0-9._-]{1,80}$') { throw 'Invalid evidence name' }
$evidenceRoot = Join-Path $tempRoot ('qa\' + $EvidenceName)
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
$env:TMP = $evidenceRoot
$env:TEMP = $evidenceRoot
$adb = (Get-Command adb -ErrorAction Stop).Source
$package = 'com.dafeiyu.mobile.dev'

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
            $transferCancel = [Threading.CancellationTokenSource]::new(60000)
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
if (!(Test-Path -LiteralPath $CacheArchive -PathType Leaf) -or
    (Get-Item -LiteralPath $CacheArchive).Length -ne 10360321 -or
    (Get-FileHash -LiteralPath $CacheArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne
    '994f101877e00c434659f564bc0cc1901f72b09b9137c62c384d86cb924d3797') {
    throw 'Model worker fixed cache fixture size/SHA-256 mismatch'
}
$null = Invoke-Adb @('-s', $Serial, 'shell', 'run-as', $package, 'mkdir', '-p', 'cache/model-worker-smoke-input') 'mkdir'
# exec-in avoids Windows shell text-mode CTRL-Z truncation of binary archives.
$remote = "run-as " + $package + " sh -c 'cat > cache/model-worker-smoke-input/model-worker-cache.zip'"
$null = Invoke-Adb @('-s', $Serial, 'exec-in', $remote) 'stage-model-worker-cache' $CacheArchive
$null = Invoke-Adb @('-s', $Serial, 'shell', 'run-as', $package, 'rm', '-f', 'files/model-worker-smoke-report.json') 'remove-own-stale-report'
$started = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
try {
    $instrumentation = Invoke-Adb @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'com.dafeiyu.mobile.PiAiWorkerSmokeTest', ($package + '.test/androidx.test.runner.AndroidJUnitRunner')) 'instrumentation' '' 600000
} finally {
    $report = Invoke-Adb @('-s', $Serial, 'exec-out', 'run-as', $package, 'cat', 'files/model-worker-smoke-report.json') 'report'
    [IO.File]::WriteAllText((Join-Path $evidenceRoot 'report.json'), $report, [Text.UTF8Encoding]::new($false))
}
$parsed = $report | ConvertFrom-Json
# Device and host clocks can differ. The stale report was deleted explicitly;
# the bounded timestamp still rejects a report older than the ten-minute run.
if (!$parsed.passed -or $parsed.package -ne $package -or $parsed.paidModelRequests -ne 0 -or
    $parsed.checks.Count -ne 6 -or @($parsed.checks | Where-Object { !$_.passed }).Count -ne 0 -or
    $parsed.started_at -lt $started - 600000 -or $instrumentation -notmatch 'OK \(1 test\)') {
    throw 'Actual Android model worker smoke failed; read report.json and instrumentation.log'
}
Write-Output 'PASS 6 actual Android model worker checks; verified install, public catalog, loopback stream/tool/cancel and real lease release; zero paid requests'
Write-Output (Join-Path $evidenceRoot 'report.json')
