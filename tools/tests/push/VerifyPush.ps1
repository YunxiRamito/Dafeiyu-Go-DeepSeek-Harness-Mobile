param([ValidateRange(192, 512)][int]$MaxHeapMiB = 384)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android'))
$testRoot = Join-Path $tempRoot ('qa/push-client/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$cacheRoot = Join-Path $tempRoot 'caches/gradle/caches/modules-2/files-2.1'
function CachedJar([string]$relative) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $relative) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency: $relative" }
    return $jar
}
$stdlib = CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$coroutines = CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$annotations = CachedJar 'org.jetbrains/annotations'
$compiler = @((CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'), $stdlib, $coroutines,
    $annotations, (CachedJar 'org.jetbrains.kotlin/kotlin-reflect'), (CachedJar 'org.jetbrains.intellij.deps/trove4j')) -join ';'
$android = Join-Path $tempRoot 'toolchains/android-sdk/platforms/android-35/android.jar'
$json = Join-Path $tempRoot 'qa/workspace-presence/json-20240303.jar'
$classpath = @($stdlib, $coroutines, $annotations, $json, $android) -join ';'
$sources = @('PushIdentity.kt', 'PushChannelPolicy.kt', 'PushRegistrationClient.kt', 'PushSettingsController.kt') |
    ForEach-Object { Join-Path $projectRoot "app/src/main/java/com/dafeiyu/mobile/data/$_" }
$java = 'C:/Program Files/Java/JDK21/bin/java.exe'
& $java "-Djava.io.tmpdir=$testRoot" "-Xmx${MaxHeapMiB}m" -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $classpath -d (Join-Path $testRoot 'android-api.jar') @sources `
    *> (Join-Path $testRoot 'android-api-compile.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $testRoot 'android-api-compile.log'); throw 'Production Android push strict API compile failed' }
$fixtureSources = @((Join-Path $PSScriptRoot 'PushChecks.kt'), (Join-Path $projectRoot 'tools/tests/identity/TestBase64.kt'),
    (Join-Path $projectRoot 'tools/tests/workspace-delete/WorkspaceDeleteJsonReaderFixture.kt'))
$runtime = $classpath + ';' + (CachedJar 'com.google.code.gson/gson/2.10.1')
$jar = Join-Path $testRoot 'push-checks.jar'
& $java "-Djava.io.tmpdir=$testRoot" "-Xmx${MaxHeapMiB}m" -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $runtime -d $jar @sources @fixtureSources `
    *> (Join-Path $testRoot 'fixture-compile.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $testRoot 'fixture-compile.log'); throw 'Push fixture strict compile failed' }
& $java "-Djava.io.tmpdir=$testRoot" "-Dfile.encoding=UTF-8" -Xmx256m -cp "$jar;$runtime" com.dafeiyu.mobile.data.PushChecksKt `
    *> (Join-Path $testRoot 'checks.log')
Get-Content -LiteralPath (Join-Path $testRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw 'Push client checks failed' }
Write-Output "Evidence: $testRoot; Keystore/TLS/OEM receipt remain device/real-account checks."
