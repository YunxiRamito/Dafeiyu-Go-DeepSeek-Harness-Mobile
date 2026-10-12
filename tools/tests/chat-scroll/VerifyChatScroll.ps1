param([string]$TempRoot)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if (-not $TempRoot) { $TempRoot = Join-Path $projectRoot '../Temp_Android' }
$TempRoot = [IO.Path]::GetFullPath($TempRoot)
$cacheRoot = Join-Path $TempRoot 'caches/gradle/caches'
$moduleRoot = Join-Path $cacheRoot 'modules-2/files-2.1'
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:/Program Files/Java/JDK21' }
$java = Join-Path $javaRoot 'bin/java.exe'

function Find-CachedJar([string]$RelativePath) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $moduleRoot $RelativePath) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing offline dependency: $RelativePath" }
    return $jar
}

$stdlib = Find-CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.1.20'
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0'
$annotations = Find-CachedJar 'org.jetbrains/annotations/23.0.0'
$compilerJars = @(
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20'),
    $stdlib, $coroutines, $annotations,
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-reflect/2.1.0'),
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-script-runtime/2.1.20'),
    (Find-CachedJar 'org.jetbrains.intellij.deps/trove4j/1.0.20200330')
)
$plugin = Find-CachedJar 'org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.1.20'
$collection = Find-CachedJar 'androidx.collection/collection-jvm/1.5.0'
$hostJar = Join-Path $TempRoot 'build/app/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar'
$androidJar = Join-Path $TempRoot 'toolchains/android-sdk/platforms/android-35/android.jar'
foreach ($required in @($java, $hostJar, $androidJar)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing offline dependency: $required" }
}
$transforms = @(Get-ChildItem -LiteralPath (Join-Path $cacheRoot '8.11.1/transforms') -Recurse -File -Filter 'classes.jar' |
    Where-Object { $_.FullName -match '[\\/]jars[\\/]classes\.jar$' })
if ($transforms.Count -eq 0) { throw 'Missing cached Compose implementation jars' }
$runRoot = Join-Path $PSScriptRoot ('output/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
$hostSnapshot = Join-Path $runRoot 'host-classes.jar'
Copy-Item -LiteralPath $hostJar -Destination $hostSnapshot
$deps = @($stdlib, $coroutines, $annotations, $collection, $androidJar, $hostSnapshot) + $transforms.FullName
$source = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/ui/ChatScreen.kt'
$glassMotion = Join-Path $projectRoot 'app/src/main/java/com/dafeiyu/mobile/ui/components/GlassMotion.kt'
$outputJar = Join-Path $runRoot 'chat-scroll-checks.jar'
Write-Output "Offline chat scroll output: $runRoot"
& $java -cp ($compilerJars -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 "-Xplugin=$plugin" "-Xfriend-paths=$hostSnapshot" `
    -classpath ($deps -join ';') -d $outputJar `
    $source $glassMotion (Join-Path $PSScriptRoot 'ChatScrollChecks.kt') 2>&1 |
    Tee-Object -FilePath (Join-Path $runRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Real ChatScreen Compose compilation failed' }
Write-Output 'PASS real ChatScreen + GlassMotion Compose compilation, cached peer classes, no Gradle'
& $java -cp ((@($outputJar) + $deps) -join ';') com.dafeiyu.mobile.ui.ChatScrollChecksKt 2>&1 |
    Tee-Object -FilePath (Join-Path $runRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw 'Production ChatScrollIntent checks failed' }

# Wiring checks supplement executable state tests; they do not claim device UI coverage.
$text = [IO.File]::ReadAllText($source)
function Assert-Wiring([string]$Pattern, [string]$Name) {
    if ($text -notmatch $Pattern) { throw "Missing scroll wiring: $Name" }
    Write-Output "PASS $Name"
}
Assert-Wiring 'key\(conversationId, composerGeneration\) \{ rememberLazyListState\(\) \}' 'list identity includes conversation and generation (A-B-A guard)'
Assert-Wiring 'repository.selectedConversationId == submittedConversation &&\s+repository.composerGeneration == submittedComposer\) \{\s+scrollIntent.acceptSend\(submittedScrollRevision\)' 'successful persistence validates conversation and generation before accepting scroll revision'
Assert-Wiring 'val submittedScrollRevision = scrollIntent.revision' 'send captures scroll intent before persistence'
Assert-Wiring 'awaitFirstDown\(requireUnconsumed = false, pass = PointerEventPass.Initial\)\s+scrollIntent.beginGesture\(\)\s+returnToLatestJob\?\.cancel\(\)' 'pointer DOWN cancels return before drag state sampling'
Assert-Wiring 'source == NestedScrollSource.UserInput && available.y != 0f' 'nested user scrolling pauses follow without intercepting automatic animation'
Assert-Wiring 'LaunchedEffect\(listState, scrollIntent, lastMessageId, scrollIntent.followingLatest, imeBottom\)' 'IME remeasure participates in guarded latest alignment'
Assert-Wiring '!scrollIntent.pointerPressed && !dragging && !listState.isScrollInProgress' 'automatic alignment yields to pointer, drag and fling'
Assert-Wiring 'if \(reducedMotion\) listState.scrollToItem\(0\)\s+else listState.animateScrollToItem\(0\)' 'reduced motion bypasses return animation'
Assert-Wiring 'Modifier.align\(Alignment.BottomEnd\).padding\(end = 16.dp, bottom = 10.dp\)' 'return button overlays list above composer without shrinking list'
Assert-Wiring 'glyph = "arrowdown"' 'return control requests downward glyph'
Assert-Wiring 'FishGlyph\(if \(glyph == "arrowdown"\) "arrow" else glyph' 'down glyph reuses existing FishComponents arrow mapping'
Assert-Wiring 'rotationZ = if \(glyph == "arrowdown"\) 90f else 0f' 'existing right arrow rotates downward'
Assert-Wiring 'size: Dp = 48.dp' 'glass button retains 48dp circular target'
Assert-Wiring 'GlassMotion.scalar\(GlassMotion.PressMillis, reducedMotion\)' 'button visibility reuses reduced-motion GlassMotion'
