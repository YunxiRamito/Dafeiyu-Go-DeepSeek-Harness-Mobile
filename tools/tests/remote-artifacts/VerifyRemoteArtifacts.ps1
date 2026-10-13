param([ValidateRange(256, 512)][int]$MaxHeapMiB = 384)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android'))
$cacheRoot = Join-Path $tempRoot 'caches/gradle/caches/modules-2/files-2.1'
$testRoot = Join-Path $tempRoot ('qa/remote-artifacts/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
function CachedJar([string]$name) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $name) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency: $name" }
    return $jar
}
$stdlib = CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$annotations = CachedJar 'org.jetbrains/annotations'
$coroutines = CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$compiler = @((CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'), $stdlib, $annotations, $coroutines,
    (CachedJar 'org.jetbrains.kotlin/kotlin-reflect'), (CachedJar 'org.jetbrains.intellij.deps/trove4j')) -join ';'
$compiledProject = Join-Path $tempRoot 'build/app/tmp/kotlin-classes/debug'
$classpath = @($stdlib, $annotations, $coroutines, $compiledProject,
    (Join-Path $tempRoot 'qa/workspace-presence/json-20240303.jar'),
    (Join-Path $tempRoot 'toolchains/android-sdk/platforms/android-35/android.jar'),
    (CachedJar 'com.squareup.okhttp3/okhttp/4.12.0'), (CachedJar 'com.squareup.okio/okio-jvm/3.6.0')) -join ';'
$sources = @('ArtifactDownloader.kt', 'RemoteArtifactProtocol.kt', 'RemoteArtifactLoopback.kt', 'RemoteDshPersistence.kt') |
    ForEach-Object { Join-Path $projectRoot "app/src/main/java/com/dafeiyu/mobile/data/$_" }
$jar = Join-Path $testRoot 'remote-artifacts.jar'
$java = 'C:/Program Files/Java/JDK21/bin/java.exe'
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror "-Xfriend-paths=$compiledProject" -classpath $classpath -d $jar `
    @sources (Join-Path $PSScriptRoot 'RemoteArtifactChecks.kt') *> (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $testRoot 'compile.log'); throw 'Production remote artifact strict compilation failed' }
& $java "-Xmx${MaxHeapMiB}m" "-Djava.io.tmpdir=$testRoot" -cp "$jar;$classpath" com.dafeiyu.mobile.data.RemoteArtifactChecksKt `
    (Join-Path $testRoot 'files') *> (Join-Path $testRoot 'checks.log')
Get-Content -LiteralPath (Join-Path $testRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw 'Remote artifact checks failed' }
Write-Output "Evidence $testRoot; real loopback/download, synthetic encrypted transport; Android/Aries E2E pending."
