param(
    [Parameter(Mandatory)][string]$Serial,
    [Parameter(Mandatory)][string]$ReleaseDirectory,
    [Parameter(Mandatory)][string]$EvidenceName
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
if ($Serial -notmatch '^emulator-\d+$') { throw 'Only the dedicated QA emulator is allowed.' }
if ($EvidenceName -notmatch '^[a-z0-9-]{8,80}$') { throw 'Expected a unique evidence name.' }
$source = [IO.Path]::GetFullPath($ReleaseDirectory)
if (!$source.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Use a release prepared under Temp_Android.'
}
$evidence = Join-Path $tempRoot ('qa\' + $EvidenceName)
if (Test-Path -LiteralPath $evidence) { throw 'Preserve prior evidence; select a new evidence name.' }
New-Item -ItemType Directory -Path $evidence | Out-Null
$env:TEMP = $evidence
$env:TMP = $evidence
$files = @('app.apk', 'manifest.json', 'manifest.sig')
foreach ($name in $files) {
    $item = Get-Item -LiteralPath (Join-Path $source $name)
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Release inputs must be regular files.'
    }
}
$manifest = Get-Content -LiteralPath (Join-Path $source 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$hash = (Get-FileHash -LiteralPath (Join-Path $source 'app.apk') -Algorithm SHA256).Hash.ToLowerInvariant()
if ($manifest.packageName -ne 'com.dafeiyu.mobile' -or $manifest.sha256 -cne $hash -or $manifest.channel -ne 'stable') {
    throw 'The host APK and signed metadata candidate differ.'
}
$runId = [guid]::NewGuid().ToString()
$package = 'com.dafeiyu.mobile.dev'
$adb = (Get-Command adb -ErrorAction Stop).Source
$utf8 = [Text.UTF8Encoding]::new($false)
function Invoke-Adb([string[]]$Arguments, [string]$Name, [string]$InputFile = '', [int]$TimeoutMs = 60000) {
    $process = [Diagnostics.Process]::new()
    $process.StartInfo.FileName = $adb
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardInput = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $process.StartInfo.ArgumentList.Add($argument) }
    if (!$process.Start()) { throw "Cannot start ADB ($Name)." }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $transferError = $null
    try {
        if ($InputFile) {
            $stream = [IO.File]::OpenRead($InputFile)
            $cancel = [Threading.CancellationTokenSource]::new(120000)
            try { $stream.CopyToAsync($process.StandardInput.BaseStream, $cancel.Token).GetAwaiter().GetResult() }
            finally { $stream.Dispose(); $cancel.Dispose() }
        }
    } catch { $transferError = $_ }
    finally { $process.StandardInput.Close() }
    $timedOut = !$process.WaitForExit($TimeoutMs)
    if ($timedOut) { $process.Kill($true); $process.WaitForExit() }
    $out = $stdout.GetAwaiter().GetResult()
    $err = $stderr.GetAwaiter().GetResult()
    [IO.File]::WriteAllText((Join-Path $evidence ($Name + '.log')), $out + $err, $utf8)
    if ($timedOut -or $process.ExitCode -ne 0 -or $transferError) { throw "ADB failed ($Name); read its G-drive log." }
    return $out
}
$null = Invoke-Adb @('devices', '-l') 'devices'
$avd = Invoke-Adb @('-s', $Serial, 'emu', 'avd', 'name') 'avd'
if (($avd -split "`r?`n")[0].Trim() -ne 'Dafeiyu_QA_API_37') { throw 'Refusing another AVD.' }
$hostReceipt = [ordered]@{
    schema = 1; runId = $runId; serial = $Serial; version = $manifest.version; code = $manifest.versionCode
    sha256 = $hash; bytes = $manifest.byteSize; formalInstallAttempted = $false
    installerOpened = $false; permissionsGranted = $false
}
[IO.File]::WriteAllText((Join-Path $evidence 'host-candidate.json'), ($hostReceipt | ConvertTo-Json), $utf8)
$remote = "files/android-cached-update-input/$runId"
$null = Invoke-Adb @('-s', $Serial, 'shell', 'run-as', $package, 'mkdir', '-p', $remote) 'mkdir-owned-input'
try {
    foreach ($name in $files) {
        $targetName = if ($name -eq 'app.apk') { 'candidate.apk' } else { $name }
        $command = "run-as $package sh -c 'cat > $remote/$targetName'"
        $null = Invoke-Adb @('-s', $Serial, 'exec-in', $command) ('stage-' + $name.Replace('.', '-')) `
            (Join-Path $source $name) 180000
    }
    $instrumentation = Invoke-Adb @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'com.dafeiyu.mobile.AndroidCachedUpdateDeviceTest', '-e', 'cached_update_run_id', $runId,
        ($package + '.test/androidx.test.runner.AndroidJUnitRunner')) 'instrumentation' '' 900000
    $report = Invoke-Adb @('-s', $Serial, 'exec-out', 'run-as', $package, 'cat',
        'files/android-cached-update-report.json') 'report'
    [IO.File]::WriteAllText((Join-Path $evidence 'report.json'), $report, $utf8)
    $parsed = $report | ConvertFrom-Json
    if (!$parsed.passed -or $parsed.runId -ne $runId -or $parsed.checks.Count -ne 13 -or
        @($parsed.checks | Where-Object { !$_.passed }).Count -ne 0 -or $parsed.networkRequests -ne 0 -or
        $parsed.installationLaunches -ne 0 -or $parsed.permissionRequests -ne 0 -or $parsed.paidModelRequests -ne 0 -or
        $instrumentation -notmatch 'OK \(1 test\)') {
        throw 'Actual Android cached update checks failed; read report.json and instrumentation.log.'
    }
    Write-Output 'PASS 13 real Android cache recovery checks, actual signed APK and formal PackageManager identity; no HTTP, installation or permission changes.'
} finally {
    # Exact UUID-owned path and only the three staged test inputs; never production update files.
    foreach ($name in @('candidate.apk', 'manifest.json', 'manifest.sig')) {
        $null = Invoke-Adb @('-s', $Serial, 'shell', 'run-as', $package, 'rm', '-f', "$remote/$name") `
            ('cleanup-' + $name.Replace('.', '-'))
    }
    $null = Invoke-Adb @('-s', $Serial, 'shell', 'run-as', $package, 'rmdir', $remote) 'cleanup-owned-input'
}
