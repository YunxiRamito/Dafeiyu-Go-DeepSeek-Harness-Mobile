param([ValidateRange(256, 768)][int]$MaxHeapMiB = 384, [string]$InstalledModulesRoot = 'G:/DeepSeek DSH')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android'))
$testRoot = Join-Path $tempRoot ('qa/provider-transaction/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$env:TMP = $testRoot
$env:TEMP = $testRoot
$cacheRoot = Join-Path $tempRoot 'caches/gradle/caches/modules-2/files-2.1'
function Cached([string]$relative) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $relative) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (!$jar) { throw "Missing offline dependency: $relative" }
    return $jar
}
$stdlib = Cached 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$coroutines = Cached 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$annotations = Cached 'org.jetbrains/annotations'
$compilerClasspath = @((Cached 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'), $stdlib, $coroutines,
    $annotations, (Cached 'org.jetbrains.kotlin/kotlin-reflect'), (Cached 'org.jetbrains.intellij.deps/trove4j')) -join ';'
$compiled = Join-Path $tempRoot 'build/app/tmp/kotlin-classes/debug'
if (!(Test-Path -LiteralPath (Join-Path $compiled 'com/dafeiyu/mobile/data/DshSharedPersistenceBridge.class'))) {
    throw 'Existing production dependency classes are missing; main Agent must build them first.'
}
$classpath = @($compiled, $stdlib, $coroutines, $annotations, (Cached 'com.squareup.okhttp3/okhttp/4.12.0'),
    (Cached 'com.squareup.okio/okio-jvm/3.6.0'), (Join-Path $tempRoot 'qa/workspace-presence/json-20240303.jar'),
    (Join-Path $tempRoot 'toolchains/android-sdk/platforms/android-35/android.jar')) -join ';'
$dataRoot = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/data'
$sources = @('DshSharedConfigurationAdapter.kt', 'DshSharedPersistenceBridge.kt', 'ModelProviderConfig.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $PSScriptRoot 'ConfigurationChecks.kt'
$sources += Join-Path $PSScriptRoot 'ProviderTransactionChecks.kt'
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:/Program Files/Java/JDK21' }
$java = Join-Path $javaRoot 'bin/java.exe'
$jar = Join-Path $testRoot 'provider-transaction.jar'
Write-Output "Provider transaction evidence: $testRoot"
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Werror -no-stdlib -no-reflect -jvm-target 17 `
    "-Xfriend-paths=$compiled" -classpath $classpath -d $jar @sources *> (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $testRoot 'compile.log'); throw 'Strict focused compilation failed' }
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp "$jar;$classpath" `
    com.dafeiyu.mobile.data.ConfigurationChecksKt *> (Join-Path $testRoot 'results.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $testRoot 'results.log'); throw 'Provider transaction regression checks failed' }
& node (Join-Path $PSScriptRoot 'candidate-credentials.mjs') `
    (Join-Path $projectRoot 'app/src/main/assets/dsh-runtime/shared-persistence.mjs') $InstalledModulesRoot `
    (Join-Path $testRoot 'candidate-stage-frame.json') (Join-Path $testRoot 'pinned-credentials') *> (Join-Path $testRoot 'pinned-credentials.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $testRoot 'pinned-credentials.log'); throw 'Pinned credential staging/storage checks failed' }
Get-Content -LiteralPath (Join-Path $testRoot 'results.log')
Get-Content -LiteralPath (Join-Path $testRoot 'pinned-credentials.log')
Write-Output 'Focused fresh production compile; JVM local fixtures only. No Gradle, Android runtime, public service, login or paid model request.'
