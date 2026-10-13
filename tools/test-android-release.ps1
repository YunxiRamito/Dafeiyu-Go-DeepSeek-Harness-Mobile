param([string]$Apk)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$testRoot = Join-Path $tempRoot 'qa\android-release-0.1.25'
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$env:TEMP = $testRoot
$env:TMP = $testRoot
if (-not $Apk) { $Apk = Join-Path $tempRoot 'Dist\Dafeiyu-Go-Mobile-0.1.25.apk' }
function Cached([string]$relative) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $relative) -Recurse -File -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency $relative" }
    return $jar
}
$stdlib = Cached 'org.jetbrains.kotlin\kotlin-stdlib\2.1.20'
$annotations = Cached 'org.jetbrains\annotations'
$coroutines = Cached 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0'
$gson = Cached 'com.google.code.gson\gson\2.11.0'
$apksig = Cached 'com.android.tools.build\apksig\8.9.2'
$compilerClasspath = @((Cached 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20'), $stdlib, $coroutines,
    $annotations, (Cached 'org.jetbrains.kotlin\kotlin-reflect'), (Cached 'org.jetbrains.intellij.deps\trove4j')) -join ';'
$classpath = @($stdlib, $annotations, $gson, $apksig) -join ';'
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
$jar = Join-Path $testRoot 'android-release.jar'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$sources = @('AndroidReleaseManifest.kt', 'AndroidApkIntegrity.kt') | ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $PSScriptRoot 'tests\workspace-delete\WorkspaceDeleteJsonReaderFixture.kt'
$sources += Join-Path $PSScriptRoot 'tests\android-release\AndroidReleaseChecks.kt'
& (Join-Path $javaRoot 'bin\java.exe') '-Xmx384m' "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -Werror -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath -d $jar @sources 2>&1 | Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Release validation compilation failed' }
& (Join-Path $javaRoot 'bin\java.exe') '-Xmx384m' "-Djava.io.tmpdir=$testRoot" -cp "$jar;$classpath" com.dafeiyu.mobile.data.AndroidReleaseChecksKt $Apk $testRoot `
    2>&1 | Tee-Object -FilePath (Join-Path $testRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw 'Release validation failed' }
