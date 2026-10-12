param([switch]$PublicSmoke)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot ('qa\official-weather\run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
function Find-CachedJar([string]$RelativeDirectory) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $RelativeDirectory) -Recurse -File -Filter '*.jar' |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency: $RelativeDirectory" }
    return $jar
}
$compiler = Find-CachedJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20'
$stdlib = Find-CachedJar 'org.jetbrains.kotlin\kotlin-stdlib\2.1.20'
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0'
$annotations = Find-CachedJar 'org.jetbrains\annotations'
$reflect = Find-CachedJar 'org.jetbrains.kotlin\kotlin-reflect'
$trove = Find-CachedJar 'org.jetbrains.intellij.deps\trove4j'
$jsonJar = Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'
$androidJar = Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
foreach ($dependency in @($jsonJar, $androidJar)) {
    if (-not (Test-Path -LiteralPath $dependency)) { throw "Missing local dependency: $dependency" }
}
$classpath = @($stdlib, $coroutines, $annotations, $jsonJar) -join ';'
$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$source = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data\OfficialWeatherClient.kt'
$fixture = Join-Path $PSScriptRoot 'tests\official-weather\OfficialWeatherChecks.kt'
$outputJar = Join-Path $testRoot 'official-weather-checks.jar'
Write-Output "Weather QA output: $testRoot"
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $classpath -d $outputJar $source $fixture
if ($LASTEXITCODE -ne 0) { throw 'Weather checks compilation failed' }
& (Join-Path $javaRoot 'bin\java.exe') -cp "$outputJar;$classpath" `
    com.dafeiyu.mobile.data.OfficialWeatherChecksKt 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'fixture-results.log')
if ($LASTEXITCODE -ne 0) { throw 'Weather fixture checks failed' }
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath "$stdlib;$coroutines;$annotations;$androidJar" `
    -d (Join-Path $testRoot 'weather-android-compile.jar') $source
if ($LASTEXITCODE -ne 0) { throw 'Weather Android API compilation failed' }
Write-Output 'PASS weather client cached Android API compilation (no Gradle)'
if ($PublicSmoke) {
    & (Join-Path $javaRoot 'bin\java.exe') -cp "$outputJar;$classpath" `
        com.dafeiyu.mobile.data.OfficialWeatherChecksKt --public-smoke $testRoot
    if ($LASTEXITCODE -ne 0) { throw 'Weather public GET smoke failed' }
}
