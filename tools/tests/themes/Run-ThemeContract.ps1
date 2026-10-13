param([string]$EvidenceName = 'app-theme-contract-r1')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
$themeTempRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$evidence = Join-Path $themeTempRoot "qa\$EvidenceName"
$classes = Join-Path $evidence 'classes'
New-Item -ItemType Directory -Force -Path $evidence,$classes | Out-Null
$env:TEMP = Join-Path $themeTempRoot 'caches\tmp'
$env:TMP = $env:TEMP
$env:TMPDIR = $env:TEMP
$javaPath = 'C:\Program Files\Java\JDK21\bin\java.exe'
$libraryRoot = Join-Path $themeTempRoot 'toolchains\gradle-8.11.1\lib'
$stdlib = Join-Path $libraryRoot 'kotlin-stdlib-2.0.20.jar'
$coroutines = Join-Path $libraryRoot 'kotlinx-coroutines-core-jvm-1.6.4.jar'
$android = Join-Path $themeTempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
$json = Join-Path $themeTempRoot 'qa\workspace-presence\json-20240303.jar'
$cachedJars = @(rg --files (Join-Path $themeTempRoot 'caches\gradle\caches\modules-2\files-2.1'))
$okhttp = @($cachedJars | Where-Object { $_.EndsWith('okhttp-4.12.0.jar') })
$okio = @($cachedJars | Where-Object { $_.EndsWith('okio-jvm-3.6.0.jar') })
if ($okhttp.Count -ne 1 -or $okio.Count -ne 1) { throw 'Expected fixed local OkHttp 4.12.0 and Okio 3.6.0 jars' }
$compilerLibraries = @(
    'kotlin-compiler-embeddable-2.0.20.jar', 'kotlin-stdlib-2.0.20.jar', 'kotlin-script-runtime-2.0.20.jar',
    'kotlin-reflect-2.0.20.jar', 'kotlin-daemon-embeddable-2.0.20.jar', 'trove4j-1.0.20200330.jar',
    'annotations-24.0.1.jar', 'kotlinx-coroutines-core-jvm-1.6.4.jar'
) | ForEach-Object { Join-Path $libraryRoot $_ }
$dependencies = @($stdlib,$json,$android,$coroutines,$okhttp[0],$okio[0])
foreach ($dependency in @($javaPath) + $dependencies + $compilerLibraries) {
    if (-not (Test-Path -LiteralPath $dependency)) { throw "Missing fixed local dependency: $dependency" }
}
$sources = @(
    'glass-lab\src\main\java\com\dafeiyu\mobile\glasslab\LabPresets.kt',
    'app\src\main\java\com\dafeiyu\mobile\data\ThemeDocuments.kt',
    'app\src\main\java\com\dafeiyu\mobile\data\ThemeMarketClient.kt',
    'app\src\main\java\com\dafeiyu\mobile\data\ArtifactDownloader.kt',
    'app\src\main\java\com\dafeiyu\mobile\data\ComponentDownloadClient.kt'
) | ForEach-Object { Join-Path $projectRoot $_ }
$sources += Join-Path $PSScriptRoot 'ThemeContractMain.kt'
$compileArgs = @("-Djava.io.tmpdir=$($env:TEMP)", '-cp', ($compilerLibraries -join ';'),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-jvm-target','17','-no-stdlib','-no-reflect',
    '-classpath', ($dependencies -join ';'), '-d', $classes) + $sources
& $javaPath @compileArgs *> (Join-Path $evidence 'compile.log')
if ($LASTEXITCODE -ne 0) { throw "Theme focused compile failed; see $evidence\compile.log" }
& $javaPath "-Djava.io.tmpdir=$($env:TEMP)" '-cp' ((@($classes) + $dependencies) -join ';') `
    'com.dafeiyu.mobile.data.ThemeContractMainKt' $evidence *> (Join-Path $evidence 'test.log')
if ($LASTEXITCODE -ne 0) { throw "Theme contract failed; see $evidence\test.log" }
Get-Content -LiteralPath (Join-Path $evidence 'report.json') -Raw
