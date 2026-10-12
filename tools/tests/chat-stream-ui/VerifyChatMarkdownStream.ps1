param([string]$TempRoot)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if (-not $TempRoot) { $TempRoot = Join-Path $projectRoot '../Temp_Android' }
$TempRoot = [System.IO.Path]::GetFullPath($TempRoot)
$cacheRoot = Join-Path $TempRoot 'caches/gradle/caches'
$moduleRoot = Join-Path $cacheRoot 'modules-2/files-2.1'

function Find-CachedJar([string]$RelativePath) {
    $directory = Join-Path $moduleRoot $RelativePath
    $result = Get-ChildItem -LiteralPath $directory -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $result) { throw "Missing cached dependency: $RelativePath" }
    return $result
}

$compilerJars = @(
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'),
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'),
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-script-runtime/2.1.20'),
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-reflect/2.1.0'),
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-daemon-embeddable/2.1.20'),
    (Find-CachedJar 'org.jetbrains.intellij.deps/trove4j/1.0.20200330'),
    (Find-CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0'),
    (Find-CachedJar 'org.jetbrains/annotations/23.0.0')
)
$plugin = Find-CachedJar 'org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.1.20'
$collection = Find-CachedJar 'androidx.collection/collection-jvm/1.5.0'
$parserPath = Join-Path $moduleRoot 'org.commonmark/commonmark/0.24.0'
$parser = if (Test-Path -LiteralPath $parserPath) {
    Find-CachedJar 'org.commonmark/commonmark/0.24.0'
} else { Join-Path $TempRoot 'markdown/commonmark-0.24.0.jar' }
$hostJar = Join-Path $TempRoot 'build/app/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar'
$androidJar = Join-Path $TempRoot 'toolchains/android-sdk/platforms/android-35/android.jar'
foreach ($dependency in @($hostJar, $parser, $androidJar)) {
    if (-not (Test-Path -LiteralPath $dependency)) { throw "Missing cached dependency: $dependency; no downloads are performed." }
}
$testRoot = Join-Path $TempRoot ('qa/chat-stream-ui/markdown-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$hostSnapshot = Join-Path $testRoot 'host-classes.jar'
Copy-Item -LiteralPath $hostJar -Destination $hostSnapshot
$transforms = Get-ChildItem -LiteralPath (Join-Path $cacheRoot '8.11.1/transforms') -Recurse -File -Filter 'classes.jar' |
    Where-Object { $_.FullName -match '[\\/]jars[\\/]classes\.jar$' }
$deps = @($transforms.FullName) + $compilerJars + @($collection, $androidJar, $parser, $hostSnapshot)
$sourceRoot = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/ui'
$sources = @(
    (Join-Path $sourceRoot 'components/ChatMarkdown.kt'),
    (Join-Path $sourceRoot 'components/StreamRevealText.kt'),
    (Join-Path $sourceRoot 'AssistantActivityFeed.kt'),
    # This independent runner compiles the helper once, with the real Markdown renderer.
    (Join-Path $sourceRoot 'AssistantActivitySummary.kt'),
    (Join-Path $PSScriptRoot 'ChatStreamUiChecks.kt'),
    (Join-Path $PSScriptRoot 'ChatMarkdownStreamChecks.kt')
)
$outputJar = Join-Path $testRoot 'chat-markdown-stream-checks.jar'
Write-Output "Offline test output: $testRoot"
& java -cp ($compilerJars -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 "-Xplugin=$plugin" "-Xfriend-paths=$hostSnapshot" `
    -classpath ($deps -join ';') -d $outputJar @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Actual three UI source compilation failed.' }
foreach ($entry in @('com.dafeiyu.mobile.ui.ChatStreamUiChecksKt', 'com.dafeiyu.mobile.ui.components.ChatMarkdownStreamChecksKt')) {
    & java -cp ((@($outputJar) + $deps) -join ';') $entry
    if ($LASTEXITCODE -ne 0) { throw "Offline fixture failed: $entry" }
}
