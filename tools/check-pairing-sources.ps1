$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches'
$moduleRoot = Join-Path $cacheRoot 'modules-2\files-2.1'
$testRoot = Join-Path $tempRoot 'qa\aries-membership'
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
$lifecycleCommon = Find-CachedJar 'androidx.lifecycle\lifecycle-common-jvm\2.8.7'
$bouncyCastle = Find-CachedJar 'org.bouncycastle\bcprov-jdk18on\1.79'
$annotations = Find-CachedJar 'org.jetbrains\annotations'
$reflect = Find-CachedJar 'org.jetbrains.kotlin\kotlin-reflect'
$trove = Find-CachedJar 'org.jetbrains.intellij.deps\trove4j'
$composePlugin = Find-CachedJar 'org.jetbrains.kotlin\kotlin-compose-compiler-plugin-embeddable\2.1.20'
$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$appJar = Join-Path $tempRoot 'build\app\intermediates\compile_app_classes_jar\debug\bundleDebugClassesToCompileJar\classes.jar'
$resourceJar = Join-Path $tempRoot 'build\app\intermediates\compile_and_runtime_not_namespaced_r_class_jar\debug\processDebugResources\R.jar'
$androidJar = Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
foreach ($path in @($appJar, $resourceJar, $androidJar)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing cached compilation input: $path" }
}
$dependencies = Get-ChildItem -LiteralPath (Join-Path $cacheRoot '8.11.1\transforms') -Recurse -File -Filter 'classes.jar' |
    Select-Object -ExpandProperty FullName
$classpath = @($stdlib, $coroutines, $lifecycleCommon, $bouncyCastle, $annotations, $androidJar, $appJar, $resourceJar) + @($dependencies)
$sourceRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile'
$sources = @('data\AriesMembershipState.kt', 'data\AriesGroupStore.kt', 'data\AriesClient.kt',
    'data\AriesProtocolPolicy.kt', 'data\AriesIdentityStore.kt', 'data\AriesCrypto.kt', 'data\SettingsContract.kt',
    'data\BalanceStore.kt', 'ui\PairingScreen.kt') |
    ForEach-Object { Join-Path $sourceRoot $_ }
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath ($classpath -join ';') `
    "-Xplugin=$composePlugin" "-Xfriend-paths=$appJar" `
    -d (Join-Path $testRoot 'pairing-sources-check.jar') @sources
if ($LASTEXITCODE -ne 0) { throw 'Targeted pairing source compilation failed' }
Write-Output 'PASS PairingScreen and actual Aries/Balance sources compilation (cached app/dependency ABI, no Gradle)'
