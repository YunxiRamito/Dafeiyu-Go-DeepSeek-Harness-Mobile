$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$moduleRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot 'qa\local-plugin-bridge'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
function Find-CachedJar([string]$RelativeDirectory) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $moduleRoot $RelativeDirectory) -Recurse -File -Filter '*.jar' |
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
$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$jsonJar = Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'
if (-not (Test-Path -LiteralPath $jsonJar)) { throw 'Missing cached org.json test runtime' }
$classpath = @($stdlib, $annotations, $jsonJar) -join ';'
$outputJar = Join-Path $testRoot 'local-plugin-ipc-policy-checks.jar'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath -d $outputJar `
    (Join-Path $dataRoot 'LocalPluginIpcPolicy.kt') `
    (Join-Path $PSScriptRoot 'tests\LocalPluginIpcPolicyChecks.kt')
if ($LASTEXITCODE -ne 0) { throw 'Local plugin IPC policy checks compilation failed' }
& (Join-Path $javaRoot 'bin\java.exe') -cp "$outputJar;$classpath" com.dafeiyu.mobile.data.LocalPluginIpcPolicyChecksKt
if ($LASTEXITCODE -ne 0) { throw 'Local plugin IPC policy checks failed' }
$androidJar = Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
if (-not (Test-Path -LiteralPath $androidJar)) { throw 'Missing cached Android 35 API jar for compile-only bridge verification' }
$bridgeClasspath = @($classpath, $coroutines, $androidJar) -join ';'
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $bridgeClasspath `
    -d (Join-Path $testRoot 'local-plugin-bridge-compile-only.jar') `
    (Join-Path $dataRoot 'LocalPluginIpcPolicy.kt') `
    (Join-Path $dataRoot 'LocalPluginBridge.kt') `
    (Join-Path $PSScriptRoot 'tests\local-plugin-bridge\BridgeCompileFixtures.kt')
if ($LASTEXITCODE -ne 0) { throw 'Local plugin bridge Android API compile-only verification failed' }
Write-Output 'PASS LocalPluginBridge compiles against cached Android 35 API (compile only, Provider fixture)'
