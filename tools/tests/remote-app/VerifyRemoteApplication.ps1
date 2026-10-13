param([string]$ProviderClassJar = '')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android'))
$runRoot = Join-Path $tempRoot ('qa/remote-app/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
$cacheRoot = Join-Path $tempRoot 'caches/gradle/caches/modules-2/files-2.1'
function CachedJar([string]$relative) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $relative) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency $relative" }
    return $jar
}
$stdlib = CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$coroutines = CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$annotations = CachedJar 'org.jetbrains/annotations/23.0.0'
$compilerClasspath = @((CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'), $stdlib, $coroutines,
    $annotations, (CachedJar 'org.jetbrains.kotlin/kotlin-reflect/2.1.0'),
    (CachedJar 'org.jetbrains.kotlin/kotlin-script-runtime/2.1.20'),
    (CachedJar 'org.jetbrains.intellij.deps/trove4j/1.0.20200330')) -join ';'
$classes = Join-Path $tempRoot 'build/app/tmp/kotlin-classes/release'
$json = Join-Path $tempRoot 'qa/workspace-presence/json-20240303.jar'
$android = Join-Path $tempRoot 'toolchains/android-sdk/platforms/android-35/android.jar'
$api = @(Get-ChildItem -LiteralPath (Join-Path $tempRoot 'caches/gradle/caches/8.11.1/transforms') -Recurse -File -Filter '*api.jar' |
    Sort-Object FullName | Select-Object -ExpandProperty FullName)
$classpath = (@($stdlib, $coroutines, $annotations, $classes, $json, $android) + $api) -join ';'
if ($ProviderClassJar) {
    if (-not (Test-Path -LiteralPath $ProviderClassJar)) { throw 'Missing focused real Provider classes' }
    $classpath = "$ProviderClassJar;$classpath"
}
$dataRoot = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/data'
$sources = @('RemoteDshApplicationController.kt', 'RemoteDshSessionCoordinator.kt', 'RemoteDshClient.kt', 'LocalPluginIpcPolicy.kt',
    'RemoteDshPersistence.kt', 'RemoteWorkspaceStore.kt', 'RemoteDshActivityMapper.kt', 'AssistantStreamProtocol.kt',
    'ChatMessage.kt', 'NativeModelContext.kt', 'NativeTranscriptStore.kt', 'PiAiChatAdapter.kt',
    'RemoteDshWebClient.kt', 'RemoteDshLoopbackProxy.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $projectRoot 'tools/tests/StreamTestAtomicFile.kt'
$sources += Join-Path $PSScriptRoot 'RemoteApplicationChecks.kt'
$jar = Join-Path $runRoot 'remote-app-checks.jar'
$java = 'C:/Program Files/Java/JDK21/bin/java.exe'
& $java "-Djava.io.tmpdir=$runRoot" -Xmx256m -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -Werror -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$classes,$ProviderClassJar" -classpath $classpath -d $jar @sources `
    *> (Join-Path $runRoot 'compile.log')
if ($LASTEXITCODE -ne 0) {
    Get-Content -LiteralPath (Join-Path $runRoot 'compile.log')
    throw 'Remote application focused compilation failed'
}
& $java "-Djava.io.tmpdir=$runRoot" -Xmx256m -cp "$jar;$classpath" com.dafeiyu.mobile.data.RemoteApplicationChecksKt $runRoot `
    *> (Join-Path $runRoot 'checks.log')
Get-Content -LiteralPath (Join-Path $runRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw 'Remote application fixtures failed' }
Write-Output "Focused production Werror and offline evidence: $runRoot"
