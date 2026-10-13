param(
    [string]$TempRoot = (Join-Path (Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent) '..\Temp_Android'),
    [string]$EvidenceName = 'material-lab-data-r1'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
$TempRoot = [System.IO.Path]::GetFullPath($TempRoot)
$evidence = Join-Path $TempRoot "qa\$EvidenceName"
$output = Join-Path $evidence 'classes'
New-Item -ItemType Directory -Force $evidence,$output | Out-Null
$env:TEMP = Join-Path $TempRoot 'caches\tmp'
$env:TMP = $env:TEMP
$env:TMPDIR = $env:TEMP
$javaPath = 'C:\Program Files\Java\JDK21\bin\java.exe'
$libraryRoot = Join-Path $TempRoot 'toolchains\gradle-8.11.1\lib'
$stdlib = Join-Path $libraryRoot 'kotlin-stdlib-2.0.20.jar'
$android = Join-Path $TempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
$json = Join-Path $TempRoot 'qa\workspace-presence\json-20240303.jar'
$compilerLibraries = @(
    'kotlin-compiler-embeddable-2.0.20.jar', 'kotlin-stdlib-2.0.20.jar',
    'kotlin-script-runtime-2.0.20.jar', 'kotlin-reflect-2.0.20.jar',
    'kotlin-daemon-embeddable-2.0.20.jar', 'trove4j-1.0.20200330.jar', 'annotations-24.0.1.jar',
    'kotlinx-coroutines-core-jvm-1.6.4.jar'
) | ForEach-Object { Join-Path $libraryRoot $_ }
foreach ($dependency in @($javaPath,$stdlib,$android,$json) + $compilerLibraries) {
    if (-not (Test-Path -LiteralPath $dependency)) { throw "Missing fixed local dependency: $dependency" }
}
$source = Join-Path $projectRoot 'glass-lab\src\main\java\com\dafeiyu\mobile\glasslab\LabPresets.kt'
$test = Join-Path $PSScriptRoot 'MaterialLabContractMain.kt'
$compileArguments = @(
    "-Djava.io.tmpdir=$($env:TEMP)", '-cp', ($compilerLibraries -join ';'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-jvm-target','17','-no-stdlib','-no-reflect','-classpath', "$stdlib;$android;$json", '-d',$output,$source,$test
)
& $javaPath @compileArguments > (Join-Path $evidence 'compile.log') 2>&1
if ($LASTEXITCODE -ne 0) { throw "Focused Kotlin compile failed. See $evidence\compile.log" }
$report = Join-Path $evidence 'report.json'
& $javaPath "-Djava.io.tmpdir=$($env:TEMP)" '-cp' "$output;$stdlib;$json;$android" 'com.dafeiyu.mobile.glasslab.MaterialLabContractMainKt' $report > (Join-Path $evidence 'test.log') 2>&1
if ($LASTEXITCODE -ne 0) { throw "Data contract checks failed. See $evidence\test.log" }
Get-Content -Raw -LiteralPath $report
