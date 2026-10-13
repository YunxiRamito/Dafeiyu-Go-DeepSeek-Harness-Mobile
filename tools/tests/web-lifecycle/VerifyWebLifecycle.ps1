$ErrorActionPreference = 'Stop'
$webRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$webTemp = [IO.Path]::GetFullPath((Join-Path $webRepo '../Temp_Android'))
$webRun = Join-Path $webTemp ('qa/web-lifecycle-compile/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $webRun | Out-Null
$webCache = Join-Path $webTemp 'caches/gradle/caches/modules-2/files-2.1'
function WebCachedJar([string]$relative) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $webCache $relative) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency $relative" }
    $jar
}
$stdlib = WebCachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$coroutines = WebCachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$annotations = WebCachedJar 'org.jetbrains/annotations/23.0.0'
$compilerClasspath = @((WebCachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'), $stdlib, $coroutines,
    $annotations, (WebCachedJar 'org.jetbrains.kotlin/kotlin-reflect/2.1.0'),
    (WebCachedJar 'org.jetbrains.kotlin/kotlin-script-runtime/2.1.20'),
    (WebCachedJar 'org.jetbrains.intellij.deps/trove4j/1.0.20200330')) -join ';'
$composePlugin = WebCachedJar 'org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.1.20'
$classes = Join-Path $webTemp 'build/app/tmp/kotlin-classes/debug'
$android = Join-Path $webTemp 'toolchains/android-sdk/platforms/android-35/android.jar'
$api = @(Get-ChildItem -LiteralPath (Join-Path $webTemp 'caches/gradle/caches/8.11.1/transforms') -Recurse -File -Filter '*api.jar' |
    Sort-Object FullName | Select-Object -ExpandProperty FullName)
$classpath = (@($stdlib, $coroutines, $annotations, $classes, $android,
    (WebCachedJar 'androidx.collection/collection-jvm/1.5.0'),
    (WebCachedJar 'androidx.lifecycle/lifecycle-common-jvm/2.8.7'),
    (WebCachedJar 'junit/junit/4.13.2')) + $api) -join ';'
$sources = @('app/src/main/java/com/dafeiyu/mobile/ui/DshWebContent.kt',
    'app/src/main/java/com/dafeiyu/mobile/ui/DshWebViewFactory.kt',
    'app/src/debug/java/com/dafeiyu/mobile/ui/DshWebLifecycleQaActivity.kt',
    'app/src/androidTest/java/com/dafeiyu/mobile/ui/DshWebLifecycleSmoke.kt') | ForEach-Object { Join-Path $webRepo $_ }
& 'C:/Program Files/Java/JDK21/bin/java.exe' "-Djava.io.tmpdir=$webRun" -Xmx384m -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -Werror -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$classes" "-Xplugin=$composePlugin" -classpath $classpath `
    -d (Join-Path $webRun 'web-lifecycle.jar') @sources *> (Join-Path $webRun 'compile.log')
if ($LASTEXITCODE -ne 0) {
    Get-Content -LiteralPath (Join-Path $webRun 'compile.log')
    throw 'Production WebView surface and actual lifecycle QA failed focused compilation'
}
Write-Output 'PASS real production DshWebContent/DshWebSurface, WebViewFactory, debug Activity and instrumentation strictly compile with Android35 and Compose'
Write-Output 'LIMIT device lifecycle, authenticated real DSH bundle and user visual acceptance are not proven by compilation'
Write-Output "Focused evidence: $webRun"
