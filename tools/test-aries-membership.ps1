$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot 'qa\aries-membership'
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
$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$classpath = @($stdlib, $annotations) -join ';'
$outputJar = Join-Path $testRoot 'aries-membership-checks.jar'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath -d $outputJar `
    (Join-Path $dataRoot 'AriesMembershipState.kt') (Join-Path $PSScriptRoot 'tests\AriesMembershipChecks.kt')
if ($LASTEXITCODE -ne 0) { throw 'Membership checks compilation failed' }
& (Join-Path $javaRoot 'bin\java.exe') -cp "$outputJar;$classpath" com.dafeiyu.mobile.data.AriesMembershipChecksKt
if ($LASTEXITCODE -ne 0) { throw 'Membership checks failed' }
$androidJar = Join-Path $tempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath "$classpath;$androidJar" `
    -d (Join-Path $testRoot 'balance-store-check.jar') (Join-Path $dataRoot 'BalanceStore.kt')
if ($LASTEXITCODE -ne 0) { throw 'BalanceStore compilation failed' }
Write-Output 'PASS BalanceStore Android API compilation'
$jsonJar = Join-Path $tempRoot 'qa\workspace-presence\json-20240303.jar'
if (-not (Test-Path -LiteralPath $jsonJar)) { throw 'Missing cached org.json test runtime' }
$fixtures = Join-Path $PSScriptRoot 'tests\aries'
$fixtureClasspath = "$classpath;$coroutines;$jsonJar"
$fixtureJar = Join-Path $testRoot 'aries-storage-fixture-checks.jar'
$sources = @('AriesMembershipState.kt', 'AriesGroupStore.kt', 'AriesDeviceName.kt', 'SettingsContract.kt', 'BalanceStore.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$sources += Get-ChildItem -LiteralPath $fixtures -File -Filter '*.kt' | Select-Object -ExpandProperty FullName
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $fixtureClasspath -d $fixtureJar @sources
if ($LASTEXITCODE -ne 0) { throw 'Aries/storage fixture compilation failed' }
& (Join-Path $javaRoot 'bin\java.exe') -cp "$fixtureJar;$fixtureClasspath" com.dafeiyu.mobile.data.AriesGroupChecksKt
if ($LASTEXITCODE -ne 0) { throw 'Aries group fixture checks failed' }
$balanceRoot = Join-Path $testRoot ("balance-fixture-" + [Guid]::NewGuid().ToString('N'))
& (Join-Path $javaRoot 'bin\java.exe') -cp "$fixtureJar;$fixtureClasspath" `
    com.dafeiyu.mobile.data.BalanceStoreChecksKt $balanceRoot
if ($LASTEXITCODE -ne 0) { throw 'Balance fixture checks failed' }
