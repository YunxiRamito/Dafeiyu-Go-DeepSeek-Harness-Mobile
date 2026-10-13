param([switch]$SkipProductionCompile)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$testRoot = Join-Path $tempRoot 'qa\pi-npm-archives'
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$env:TMP = $testRoot
$env:TEMP = $testRoot
if (-not $SkipProductionCompile) {
    & (Join-Path $PSScriptRoot 'test-model-worker.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Production model worker compilation failed' }
}
function Cached([string]$relative) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $relative) -Recurse -File -Filter '*.jar' |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency $relative" }
    return $jar
}
$stdlib = Cached 'org.jetbrains.kotlin\kotlin-stdlib\2.1.20'
$coroutines = Cached 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0'
$annotations = Cached 'org.jetbrains\annotations'
$compilerClasspath = @((Cached 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20'), $stdlib, $coroutines,
    $annotations, (Cached 'org.jetbrains.kotlin\kotlin-reflect'), (Cached 'org.jetbrains.intellij.deps\trove4j')) -join ';'
$productionJar = Join-Path $tempRoot 'qa\model-worker-0.1.25\runtime-worker.jar'
$classpath = @($productionJar, $stdlib, $coroutines, $annotations,
    (Cached 'org.apache.commons\commons-compress\1.27.1'), (Cached 'commons-io\commons-io\2.16.1'),
    (Cached 'org.apache.commons\commons-lang3\3.16.0'), (Cached 'com.squareup.okhttp3\okhttp\4.12.0'),
    (Cached 'com.squareup.okio\okio-jvm\3.6.0'), (Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'),
    (Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar')) -join ';'
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
$fixtureJar = Join-Path $testRoot 'pi-npm-archive-checks.jar'
$sources = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests\pi-npm-archives') -File -Filter '*.kt' | Select-Object -ExpandProperty FullName
& (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Werror -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$productionJar" `
    -classpath $classpath -d $fixtureJar @sources
if ($LASTEXITCODE -ne 0) { throw 'npm archive fixtures strict compilation failed' }
$runRoot = Join-Path $testRoot ('run-' + [Guid]::NewGuid().ToString('N'))
& (Join-Path $javaRoot 'bin\java.exe') '-Xmx256m' "-Djava.io.tmpdir=$testRoot" -cp "$fixtureJar;$classpath" `
    com.dafeiyu.mobile.data.PiNpmArchiveChecksKt $runRoot `
    (Join-Path $projectRoot 'app\src\main\assets\model-worker\runtime-lock.json') `
    (Join-Path $tempRoot 'qa\model-worker-android-fixtures\model-worker-cache.zip')
if ($LASTEXITCODE -ne 0) { throw 'npm archive production extraction fixtures failed' }
