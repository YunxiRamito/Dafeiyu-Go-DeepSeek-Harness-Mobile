param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$DshManifest,
    [string]$GuestArchive = (Join-Path $PSScriptRoot '..\..\Temp_Android\linux-x86_64-bundles\ubuntu24.04.3-node24.19.0-x86_64-20261012.tar.gz'),
    [string]$DshArchive = (Join-Path $PSScriptRoot '..\..\Temp_Android\dsh-bundles\dsh-0.2.1-alpha.1-linux-x64.tar.gz'),
    [string]$EvidenceName = 'runtime-smoke-0.1.25'
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

function Invoke-Adb([string[]]$Arguments, [string]$OutputName, [string]$InputFile = '') {
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
            try { $inputStream.CopyTo($process.StandardInput.BaseStream) }
            finally { $inputStream.Dispose() }
        }
    } catch { $transferFailure = $_ }
    finally { $process.StandardInput.Close() }
    $process.WaitForExit()
    $out = $stdout.GetAwaiter().GetResult()
    $err = $stderr.GetAwaiter().GetResult()
    [IO.File]::WriteAllText((Join-Path $evidenceRoot ($OutputName + '.log')), $out + $err, [Text.UTF8Encoding]::new($false))
    if ($process.ExitCode -ne 0) { throw "ADB failed ($OutputName); see evidence log" }
    if ($transferFailure) { throw "ADB input transfer failed ($OutputName); see evidence log. $($transferFailure.Exception.Message)" }
    return $out
}

$null = Invoke-Adb @('devices', '-l') 'devices'
$abi = Invoke-Adb @('-s', $Serial, 'shell', 'getprop', 'ro.product.cpu.abilist') 'abi'
if ($abi.Trim().Split(',')[0] -ne 'x86_64') { throw 'The selected QA device does not run native x86_64' }
$avd = Invoke-Adb @('-s', $Serial, 'emu', 'avd', 'name') 'avd'
if (($avd -split "`r?`n")[0].Trim() -ne 'Dafeiyu_QA_API_37') { throw 'Refusing to operate on an AVD other than Dafeiyu_QA_API_37' }

foreach ($path in @($GuestArchive, $DshArchive, $DshManifest)) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing staged fixture: $path" }
}
$manifest = Get-Content -LiteralPath $DshManifest -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.abi -ne 'x86_64' -or !($manifest.archiveUrl -like 'https://*')) { throw 'DSH fixture manifest must be the published x86_64 manifest' }
if ((Get-Item -LiteralPath $DshArchive).Length -ne $manifest.archiveSize -or
    (Get-FileHash -LiteralPath $DshArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.archiveSha256) {
    throw 'DSH archive fixture does not match its published manifest'
}
$null = Invoke-Adb @('-s', $Serial, 'shell', 'run-as', $package, 'mkdir', '-p', 'cache/runtime-smoke-input') 'mkdir'
foreach ($fixture in @(
    @{ Name = 'guest.tar.gz'; Path = $GuestArchive },
    @{ Name = 'dsh.tar.gz'; Path = $DshArchive },
    @{ Name = 'dsh-manifest.json'; Path = $DshManifest }
)) {
    # Windows adb shell consumes stdin in text mode (CTRL-Z becomes EOF).
    # exec-in uses the raw binary transport needed by these tar.gz archives.
    $remote = "run-as " + $package + " sh -c 'cat > cache/runtime-smoke-input/" + $fixture.Name + "'"
    $null = Invoke-Adb @('-s', $Serial, 'exec-in', $remote) ('stage-' + $fixture.Name) $fixture.Path
}
try {
    $null = Invoke-Adb @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'com.dafeiyu.mobile.RuntimeSmokeTest', ($package + '.test/androidx.test.runner.AndroidJUnitRunner')) 'instrumentation'
} finally {
    $report = Invoke-Adb @('-s', $Serial, 'exec-out', 'run-as', $package, 'cat', 'files/runtime-smoke-report.json') 'report'
    [IO.File]::WriteAllText((Join-Path $evidenceRoot 'report.json'), $report, [Text.UTF8Encoding]::new($false))
}
$parsed = $report | ConvertFrom-Json
if (!$parsed.passed) { throw 'Actual Android runtime smoke failed; read report.json and instrumentation.log' }
Write-Output ("PASS actual Android runtime smoke; sandboxVerified=" + $parsed.sandboxVerified + '; requiresVmDecision=' + $parsed.requiresVmDecision)
Write-Output (Join-Path $evidenceRoot 'report.json')
