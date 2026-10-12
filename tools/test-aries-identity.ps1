$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\Temp_Android'))
$moduleRoot = Join-Path $tempRoot 'caches\gradle\caches\modules-2\files-2.1'
$testRoot = Join-Path $tempRoot 'qa\aries-identity'
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
$bouncyCastle = Find-CachedJar 'org.bouncycastle\bcprov-jdk18on\1.79'
$compilerClasspath = @($compiler, $stdlib, $coroutines, $annotations, $reflect, $trove) -join ';'
$classpath = @($stdlib, $annotations, $bouncyCastle) -join ';'
$outputJar = Join-Path $testRoot 'aries-identity-checks.jar'
$dataRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\data'
$fixtures = Join-Path $PSScriptRoot 'tests\aries'
$sources = @((Join-Path $dataRoot 'AriesIdentityStore.kt'), (Join-Path $dataRoot 'BalanceStore.kt'),
    (Join-Path $fixtures 'TestKeySpec.kt'), (Join-Path $fixtures 'TestAtomicFile.kt'),
    (Join-Path $fixtures 'BalanceStoreChecks.kt'))
$sources += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests\identity') -File -Filter '*.kt' |
    Select-Object -ExpandProperty FullName
& (Join-Path $javaRoot 'bin\java.exe') -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath -d $outputJar @sources
if ($LASTEXITCODE -ne 0) { throw 'Aries identity checks compilation failed' }
& (Join-Path $javaRoot 'bin\java.exe') -cp "$outputJar;$classpath" com.dafeiyu.mobile.data.AriesIdentityChecksKt
if ($LASTEXITCODE -ne 0) { throw 'Aries identity checks failed' }
