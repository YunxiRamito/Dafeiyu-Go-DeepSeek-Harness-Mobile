param([switch]$CompileOnly, [switch]$SkipLongTransfer)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot 'qa\aries-provider-bulk'
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
$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$classpath = @($stdlib, $coroutines, $annotations, $bc, $json) -join ';'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$sources = @('AriesProviderScope.kt', 'AriesBulkPolicy.kt', 'AriesConnectionProvider.kt', 'AriesCrypto.kt', 'LocalPluginIpcPolicy.kt',
    'AriesProtocolPolicy.kt', 'SettingsContract.kt', 'RemoteDshPersistence.kt', 'RemoteDshClient.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $PSScriptRoot 'tests\provider\TestContext.kt'
$sources += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests\aries-bulk') -File -Filter '*.kt' | Select-Object -ExpandProperty FullName
$outputJar = Join-Path $testRoot 'aries-bulk-checks.jar'
& (Join-Path $javaRoot 'bin\java.exe') "-Djava.io.tmpdir=$tempRoot" -Xmx384m -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -Werror -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath -d $outputJar @sources
if ($LASTEXITCODE -ne 0) { throw 'Aries bulk production fixture strict compilation failed' }
Write-Output 'PASS Aries bulk production Provider/crypto/policy/client/scope strict Werror compile'
$androidJar = Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
if (-not (Test-Path -LiteralPath $androidJar)) { throw 'Missing cached Android 35 API jar' }
$androidClasspath = @($stdlib, $coroutines, $annotations, $bc, $androidJar) -join ';'
$androidSources = @('AriesProviderScope.kt', 'AriesBulkPolicy.kt', 'AriesConnectionProvider.kt', 'AriesCrypto.kt', 'LocalPluginIpcPolicy.kt',
    'SettingsContract.kt', 'AriesClient.kt', 'AriesDeviceName.kt', 'AriesProtocolPolicy.kt', 'AriesGroupStore.kt', 'AriesMembershipState.kt',
    'AriesIdentityStore.kt', 'AriesProviderVault.kt', 'RemoteDshClient.kt', 'RemoteDshPersistence.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$androidSources += Join-Path $PSScriptRoot 'tests\provider\AndroidCompileFixtures.kt'
$androidSources += Join-Path $PSScriptRoot 'tests\provider\android-api\Resources.kt'
& (Join-Path $javaRoot 'bin\java.exe') "-Djava.io.tmpdir=$tempRoot" -Xmx384m -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -Werror -no-stdlib -no-reflect -jvm-target 17 -classpath $androidClasspath `
    -d (Join-Path $testRoot 'aries-bulk-android35.jar') @androidSources
if ($LASTEXITCODE -ne 0) { throw 'Aries bulk production Android35 strict compilation failed' }
Write-Output 'PASS Aries bulk production Provider/HTTP/identity/vault/client/scope strict Android35 Werror compile (execution not tested)'
if (-not $CompileOnly) {
    $runRoot = Join-Path $testRoot ('run-' + [Guid]::NewGuid().ToString('N'))
    $arguments = @($runRoot)
    if ($SkipLongTransfer) { $arguments += 'quick' }
    & (Join-Path $javaRoot 'bin\java.exe') "-Djava.io.tmpdir=$tempRoot" -Xmx384m -cp "$outputJar;$classpath" com.dafeiyu.mobile.data.AriesBulkChecksKt @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Aries bulk production fixture checks failed' }
}
