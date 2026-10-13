param(
    [switch]$CompileOnly,
    [string]$Serial,
    [string]$EvidenceName = 'dsh-archive-smoke-0.1.25',
    [string]$ExpectedManifest
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
if ($EvidenceName -notmatch '^[A-Za-z0-9._-]{1,80}$') { throw 'Invalid evidence name' }
$evidenceRoot = Join-Path $tempRoot ('qa\' + $EvidenceName)
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
$env:TMP = $evidenceRoot
$env:TEMP = $evidenceRoot
if ($CompileOnly) {
    $cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
    function Cached([string]$relative, [string]$filter = '*.jar') {
        $file = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $relative) -Recurse -File -Filter $filter |
            Select-Object -First 1 -ExpandProperty FullName
        if (!$file) { throw "Missing cached dependency $relative" }
        return $file
    }
    function Aar([string]$relative, [string]$name) {
        $archive = [IO.Compression.ZipFile]::OpenRead((Cached $relative '*.aar'))
        try {
            $entry = $archive.GetEntry('classes.jar')
            if (!$entry) { throw 'AndroidX AAR has no classes.jar' }
            $file = Join-Path $evidenceRoot ($name + '.jar')
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $file, $true)
            return $file
        } finally { $archive.Dispose() }
    }
    $stdlib = Cached 'org.jetbrains.kotlin\kotlin-stdlib\2.1.20'
    $coroutines = Cached 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0'
    $annotations = Cached 'org.jetbrains\annotations'
    $compiler = @((Cached 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20'), $stdlib, $coroutines,
        $annotations, (Cached 'org.jetbrains.kotlin\kotlin-reflect'), (Cached 'org.jetbrains.intellij.deps\trove4j')) -join ';'
    $classpath = @($stdlib, $coroutines, $annotations, (Cached 'org.apache.commons\commons-compress\1.27.1'),
        (Cached 'com.squareup.okhttp3\okhttp\4.12.0'), (Cached 'com.squareup.okio\okio-jvm\3.6.0'),
        (Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'),
        (Aar 'androidx.test\runner\1.6.2' 'runner'), (Aar 'androidx.test\monitor\1.7.2' 'monitor'),
        (Aar 'androidx.test.ext\junit\1.2.1' 'androidx-junit'), (Cached 'junit\junit\4.13.2')) -join ';'
    $dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
    $sources = @('LinuxRuntimeContract.kt', 'LinuxSandboxContract.kt', 'LinuxSandboxVerifier.kt', 'LinuxArchiveExtractor.kt',
        'LinuxRuntimeManager.kt', 'LinuxManagedProcess.kt', 'ComponentDownloadClient.kt', 'ArtifactDownloader.kt',
        'DshRuntimeManifest.kt', 'DshRuntimeInstaller.kt', 'DshRuntimeManager.kt', 'DshSandboxProbe.kt', 'DshExtensionTransaction.kt',
        'DshProviderOrderProjection.kt', 'ModelProviderConfig.kt', 'ModelProviderStore.kt', 'ConnectionPluginPackage.kt',
        'ConnectionPackageValidator.kt') | ForEach-Object { Join-Path $dataRoot $_ }
    $sources += Join-Path $PSScriptRoot 'tests\archive-startup\CompileDependencies.kt'
    $sources += Join-Path $PSScriptRoot 'tests\provider\android-api\Resources.kt'
    $sources += Join-Path $projectRoot 'app\src\androidTest\java\com\dafeiyu\mobile\data\DshArchiveRuntimeSmoke.kt'
    $javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
    & (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$evidenceRoot" -cp $compiler `
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Werror -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath `
        -d (Join-Path $evidenceRoot 'archive-instrumentation-android35.jar') @sources
    if ($LASTEXITCODE -ne 0) { throw 'Archive instrumentation strict Android35/AndroidX compilation failed' }
    Write-Output 'PASS real production runtime and Android35/AndroidX archive instrumentation strict Werror compilation; no device or native CLI executed'
    return
}
if ($Serial -notmatch '^emulator-\d+$') { throw 'This script operates only on the dedicated QA emulator, never a phone.' }
if (!$ExpectedManifest) { $ExpectedManifest = Join-Path $tempRoot 'runtime\assets\connection-plugin\manifest.json' }
$manifest = Get-Content -LiteralPath $ExpectedManifest -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.version -ne '0.3.0' -or $manifest.sha256 -notmatch '^[0-9a-f]{64}$') { throw 'Expected frozen Connect manifest is invalid' }
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
    if ($timedOut -or $process.ExitCode -ne 0) { throw "ADB failed or timed out ($Name); see evidence log" }
    return $output
}
function Export-OwnedRun([string]$OwnedPath) {
    if ($OwnedPath -notmatch '^dsh-archive-smoke/[0-9a-f-]{36}$') { throw 'Refusing to export an unverified owned path' }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo.FileName = $adb
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    foreach ($argument in @('-s', $Serial, 'exec-out', 'run-as', $package, 'tar', '-czf', '-', '-C', 'files', $OwnedPath)) {
        $process.StartInfo.ArgumentList.Add($argument)
    }
    if (!$process.Start()) { throw 'Cannot start ADB evidence export' }
    $stderr = $process.StandardError.ReadToEndAsync()
    $file = [IO.File]::Create((Join-Path $evidenceRoot 'owned-run.tar.gz'))
    $cancel = [Threading.CancellationTokenSource]::new(60000)
    try { $process.StandardOutput.BaseStream.CopyToAsync($file, $cancel.Token).GetAwaiter().GetResult() }
    catch {
        if (!$process.HasExited) { $process.Kill($true); $process.WaitForExit() }
        throw
    }
    finally { $file.Dispose(); $cancel.Dispose() }
    if (!$process.WaitForExit(10000)) { $process.Kill($true); $process.WaitForExit(); throw 'Owned evidence export timed out' }
    [IO.File]::WriteAllText((Join-Path $evidenceRoot 'export.log'), $stderr.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
    if ($process.ExitCode -ne 0) { throw 'Owned evidence export failed' }
}
$null = Invoke-Adb @('devices', '-l') 'devices'
$abi = Invoke-Adb @('-s', $Serial, 'shell', 'getprop', 'ro.product.cpu.abilist') 'abi'
if ($abi.Trim().Split(',')[0] -ne 'x86_64') { throw 'The selected QA device does not run native x86_64' }
$avd = Invoke-Adb @('-s', $Serial, 'emu', 'avd', 'name') 'avd'
if (($avd -split "`r?`n")[0].Trim() -ne 'Dafeiyu_QA_API_37') { throw 'Refusing to operate on another AVD' }
$null = Invoke-Adb @('-s', $Serial, 'shell', 'run-as', $package, 'rm', '-f', 'files/dsh-archive-smoke-report.json') 'remove-own-stale-report'
try {
    $instrumentation = Invoke-Adb @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'com.dafeiyu.mobile.data.DshArchiveRuntimeSmoke', ($package + '.test/androidx.test.runner.AndroidJUnitRunner')) 'instrumentation' 600000
} finally {
    $reportText = Invoke-Adb @('-s', $Serial, 'exec-out', 'run-as', $package, 'cat', 'files/dsh-archive-smoke-report.json') 'report'
    [IO.File]::WriteAllText((Join-Path $evidenceRoot 'report.json'), $reportText, [Text.UTF8Encoding]::new($false))
    $report = $reportText | ConvertFrom-Json
    if ($report.retainedFiles) { Export-OwnedRun $report.retainedFiles }
}
$frozen = $report.checks[0].evidence
if (!$report.passed -or $report.package -ne $package -or $report.paidModelRequests -ne 0 -or
    $report.syntheticCli -or $report.syntheticServices -or $report.count -ne 9 -or
    @($report.checks | Where-Object { !$_.passed }).Count -ne 0 -or
    $frozen.connectSha256 -ne $manifest.sha256 -or $frozen.connectBytes -ne $manifest.size_bytes -or
    $instrumentation -notmatch 'OK \(1 test\)') {
    throw 'Actual Android archive smoke failed or package evidence mismatched; read report.json and instrumentation.log'
}
# Only successful, exported test-owned paths are cleaned. No installed runtime,
# production DSH_HOME, identity, selected provider or pairing store is touched.
if ($report.retainedFiles -notmatch '^dsh-archive-smoke/[0-9a-f-]{36}$' -or
    $report.retainedVault -ne $report.retainedFiles) { throw 'Refusing cleanup of unexpected test-owned paths' }
$null = Invoke-Adb @('-s', $Serial, 'shell', 'run-as', $package, 'rm', '-rf',
    ('files/' + $report.retainedFiles), ('no_backup/' + $report.retainedVault)) 'clean-exported-own-run'
Write-Output 'PASS 9 actual Android/PRoot archive checks including genuine packaged preloader, first-party CLI deletion, cold tombstones and early journal refusal; zero paid requests'
Write-Output (Join-Path $evidenceRoot 'report.json')
