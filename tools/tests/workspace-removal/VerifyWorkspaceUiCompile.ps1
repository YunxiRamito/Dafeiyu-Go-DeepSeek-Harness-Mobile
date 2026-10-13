param([ValidateRange(192, 768)][int]$MaxHeapMiB = 512)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches/gradle/caches'
$moduleRoot = Join-Path $cacheRoot 'modules-2/files-2.1'
$testRoot = Join-Path $tempRoot ('qa/workspace-ui/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:/Program Files/Java/JDK21' }
function Find-CachedJar([string]$RelativeDirectory) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $moduleRoot $RelativeDirectory) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing offline dependency: $RelativeDirectory" }
    return $jar
}
$stdlib = Find-CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$annotations = Find-CachedJar 'org.jetbrains/annotations'
$compilerClasspath = @(
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'),
    $stdlib, $coroutines, $annotations,
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-reflect'),
    (Find-CachedJar 'org.jetbrains.intellij.deps/trove4j')
) -join ';'
$transforms = @(Get-ChildItem -LiteralPath (Join-Path $cacheRoot '8.11.1/transforms') -Recurse -File -Filter 'classes.jar' |
    Where-Object { $_.FullName -match '[\\/]jars[\\/]classes\.jar$' })
$compiledProject = Join-Path $tempRoot 'build/app/tmp/kotlin-classes/debug'
$androidJar = Join-Path $tempRoot 'toolchains/android-sdk/platforms/android-35/android.jar'
$rJars = @(Get-ChildItem -LiteralPath (Join-Path $tempRoot 'build/app/intermediates') -Recurse -File -Filter 'R.jar' |
    Select-Object -ExpandProperty FullName)
$classpath = (@($stdlib, $coroutines, $annotations,
    (Find-CachedJar 'androidx.collection/collection-jvm'),
    (Find-CachedJar 'androidx.lifecycle/lifecycle-common-jvm'),
    (Find-CachedJar 'com.squareup.okhttp3/okhttp/4.12.0'),
    (Find-CachedJar 'com.squareup.okio/okio-jvm/3.6.0'), $compiledProject, $androidJar) +
    $transforms.FullName + $rJars) -join ';'
$sourceRoot = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile'
$sources = @('ui/WorkspaceSidebar.kt', 'ui/ConversationArchiveScreen.kt', 'data/WorkspaceStore.kt',
    'data/WorkspaceLifecycle.kt', 'data/WorkspaceFileRemoval.kt', 'data/DemoRepository.kt',
    'data/RemoteWorkspacePresentation.kt', 'data/RemoteDshApplicationController.kt',
    'data/PushIdentity.kt', 'data/PushChannelPolicy.kt', 'data/PushRegistrationClient.kt',
    'data/PushSettingsController.kt', 'ui/SettingsScreens.kt', 'ui/ChatScreen.kt',
    'data/RemoteArtifactDownloadController.kt', 'data/RemoteArtifactLoopback.kt', 'data/RemoteArtifactProtocol.kt',
    'data/ArtifactDownloader.kt', 'ui/RemoteArtifactsContent.kt', 'data/RemoteAttachmentComposer.kt',
    'data/RemoteAttachmentTransfer.kt', 'data/RemoteDshClient.kt', 'data/RemoteDshSessionCoordinator.kt') |
    ForEach-Object { Join-Path $sourceRoot $_ }
$composePlugin = Find-CachedJar 'org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.1.20'
Write-Output "Workspace UI compile-only output: $testRoot"
& (Join-Path $javaRoot 'bin/java.exe') "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp $compilerClasspath `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -Werror `
    "-Xplugin=$composePlugin" "-Xfriend-paths=$compiledProject" -classpath $classpath `
    -d (Join-Path $testRoot 'workspace-ui.jar') @sources (Join-Path $PSScriptRoot '../remote-artifacts/RemoteAttachmentComposerChecks.kt') 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Owned UI production Compose compilation failed' }
Write-Output 'PASS actual Repository, remote Controller, WorkspaceSidebar and ConversationArchiveScreen Compose compilation'
& (Join-Path $javaRoot 'bin/java.exe') "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp `
    "$(Join-Path $testRoot 'workspace-ui.jar');$(Join-Path $tempRoot 'qa/workspace-presence/json-20240303.jar');$classpath" `
    com.dafeiyu.mobile.data.RemoteAttachmentComposerChecksKt (Join-Path $testRoot 'composer-files')
if ($LASTEXITCODE -ne 0) { throw 'Owned remote attachment composer checks failed' }
Write-Output 'Compilation only. No runtime Compose, shader, task, ADB, remote mutation or device validation.'
