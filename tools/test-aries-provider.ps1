param(
    [string]$WireFixture = '..\Temp_SetupandLauncher\ConnectionIntegration-20261011\PCProvider\runs\20261011-160900\wire-fixture.json'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot 'qa\aries-provider'
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
$bc = Find-CachedJar 'org.bouncycastle\bcprov-jdk18on\1.79'
$json = Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'
if (-not (Test-Path -LiteralPath $json)) { throw 'Missing offline org.json runtime' }
$wirePath = if ([IO.Path]::IsPathRooted($WireFixture)) { $WireFixture } else { Join-Path $projectRoot $WireFixture }
if (-not (Test-Path -LiteralPath $wirePath)) { throw "Missing frozen PC wire fixture: $wirePath" }
$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$classpath = @($stdlib, $coroutines, $annotations, $bc, $json) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$sources = @('AriesProviderScope.kt', 'AriesConnectionProvider.kt', 'AriesCrypto.kt', 'SettingsContract.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$sources += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests\provider') -File -Filter '*.kt' |
    Where-Object { $_.Name -ne 'AndroidCompileFixtures.kt' } |
    Select-Object -ExpandProperty FullName
$outputJar = Join-Path $testRoot 'aries-provider-checks.jar'
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath -d $outputJar @sources
if ($LASTEXITCODE -ne 0) { throw 'Aries provider fixture compilation failed' }
$runRoot = Join-Path $testRoot ('run-' + [Guid]::NewGuid().ToString('N'))
& (Join-Path $javaRoot 'bin\java.exe') -cp "$outputJar;$classpath" `
    com.dafeiyu.mobile.data.AriesProviderChecksKt $runRoot $wirePath
if ($LASTEXITCODE -ne 0) { throw 'Aries provider checks failed' }
$androidJar = Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
if (-not (Test-Path -LiteralPath $androidJar)) { throw 'Missing cached Android 35 API jar' }
$androidClasspath = @($stdlib, $coroutines, $annotations, $bc, $androidJar) -join ';'
$androidSources = @('AriesProviderScope.kt', 'AriesConnectionProvider.kt', 'AriesCrypto.kt',
    'SettingsContract.kt', 'AriesClient.kt', 'AriesDeviceName.kt', 'AriesProtocolPolicy.kt', 'AriesGroupStore.kt', 'AriesMembershipState.kt',
    'AriesIdentityStore.kt', 'AriesProviderVault.kt') | ForEach-Object { Join-Path $dataRoot $_ }
$androidSources += Join-Path $PSScriptRoot 'tests\provider\AndroidCompileFixtures.kt'
$androidSources += Join-Path $PSScriptRoot 'tests\provider\android-api\Resources.kt'
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $androidClasspath `
    -d (Join-Path $testRoot 'aries-provider-android-compile-only.jar') @androidSources
if ($LASTEXITCODE -ne 0) { throw 'Aries provider Android API compile-only verification failed' }
Write-Output 'PASS Aries provider and production dependencies compile against Android 35 API (no Gradle)'
