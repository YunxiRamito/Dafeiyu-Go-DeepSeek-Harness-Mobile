$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$tempRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../Temp_Android'))
$runRoot = Join-Path $tempRoot ('qa/remote-workspace-ui/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
$cacheRoot = Join-Path $tempRoot 'caches/gradle/caches/modules-2/files-2.1'
function CachedJar([string]$relative) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cacheRoot $relative) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency: $relative" }
    return $jar
}
$stdlib = CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$coroutines = CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$annotations = CachedJar 'org.jetbrains/annotations/23.0.0'
$compilerClasspath = @((CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'), $stdlib, $coroutines,
    $annotations, (CachedJar 'org.jetbrains.kotlin/kotlin-reflect/2.1.0'),
    (CachedJar 'org.jetbrains.intellij.deps/trove4j/1.0.20200330')) -join ';'
$classes = Join-Path $tempRoot 'build/app/tmp/kotlin-classes/debug'
$json = Join-Path $tempRoot 'qa/workspace-presence/json-20240303.jar'
$android = Join-Path $tempRoot 'toolchains/android-sdk/platforms/android-35/android.jar'
$api = @(Get-ChildItem -LiteralPath (Join-Path $tempRoot 'caches/gradle/caches/8.11.1/transforms') -Recurse -File -Filter '*api.jar' |
    Sort-Object FullName | Select-Object -ExpandProperty FullName)
$classpath = (@($stdlib, $coroutines, $annotations, $classes, $json, $android) + $api) -join ';'
$dataRoot = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/data'
$sources = @('RemoteWorkspacePresentation.kt', 'RemoteDshPersistence.kt', 'RemoteWorkspaceStore.kt') |
    ForEach-Object { Join-Path $dataRoot $_ }
$sources += Join-Path $projectRoot 'tools/tests/StreamTestAtomicFile.kt'
$sources += Join-Path $PSScriptRoot 'RemoteWorkspacePresentationChecks.kt'
$jar = Join-Path $runRoot 'remote-workspace-checks.jar'
$java = 'C:/Program Files/Java/JDK21/bin/java.exe'
& $java "-Djava.io.tmpdir=$runRoot" -Xmx256m -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -Werror -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$classes" -classpath $classpath -d $jar @sources `
    *> (Join-Path $runRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $runRoot 'compile.log'); throw 'Remote workspace production Werror compile failed' }
& $java "-Djava.io.tmpdir=$runRoot" -Xmx128m -cp "$jar;$classpath" com.dafeiyu.mobile.data.RemoteWorkspacePresentationChecksKt `
    *> (Join-Path $runRoot 'checks.log')
Get-Content (Join-Path $runRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw 'Remote workspace confirmation checks failed' }
Write-Output "Production confirmation/archive projection evidence: $runRoot"
Write-Output 'No Repo coroutine, Android UI, actual PC or Aries mutation was exercised.'
