param(
    [string]$TempRoot,
    [string]$JavaRoot,
    [switch]$ScriptOnly,
    [switch]$CompileOnly,
    [string]$ChecksJar
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $TempRoot) { $TempRoot = Join-Path $projectRoot '..\Temp_Android' }
$TempRoot = [IO.Path]::GetFullPath($TempRoot)
$sourceRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile'
if ($ScriptOnly -and ($CompileOnly -or $ChecksJar)) { throw 'ScriptOnly cannot start another stage' }
if ($CompileOnly -and $ChecksJar) { throw 'Choose CompileOnly or ChecksJar, not both' }

# These structural checks guard contracts; they do not replace Kotlin execution or pixel QA.
# ScriptOnly returns here before discovering dependencies, starting Java or writing output.
$ui = [IO.File]::ReadAllText((Join-Path $sourceRoot 'ui\ChatRunInfoBar.kt'))
$metricsSource = [IO.File]::ReadAllText((Join-Path $sourceRoot 'data\ChatRunMetrics.kt'))
function Assert-LayoutContract([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw "Info bar contract failed: $Name" }
    Write-Output "PASS $Name"
}
Assert-LayoutContract ($ui -match 'FlowRow\(' -and $ui -notmatch 'horizontalScroll|softWrap\s*=\s*false|maxLines\s*=\s*1') 'bounded wrapping instead of horizontal scrolling or forced single lines'
Assert-LayoutContract ($ui -match 'Modifier\.weight\(1f,\s*fill\s*=\s*false\)') 'text receives remaining group width beside its fixed glyph'
Assert-LayoutContract ($ui -match 'cornerRadius\s*=\s*capsuleRadius' -and $ui -match 'minOf\(surfaceSize.width, surfaceSize.height\) / 2f' -and $ui -match 'onSizeChanged') 'glass capsule follows measured size when groups wrap'
Assert-LayoutContract ($ui -match 'FishGlyph\("usage"' -and $ui -match 'FishGlyph\("archive"') 'existing SF assets reused'
Assert-LayoutContract ($ui -match 'clearAndSetSemantics' -and $ui -match 'chatRunInfoDescription\(shown\)') 'single exact accessible description includes metric meanings'
Assert-LayoutContract ($ui -match 'MutableTransitionState\(false\)' -and $ui -match 'fadeIn\(' -and $ui -match 'fadeOut\(' -and $ui -match 'animateContentSize\(') 'appearance layout transition and exit share motion policy'
Assert-LayoutContract ($ui -match 'if \(reducedMotion\) 0 else GlassMotion\.MorphMillis') 'reduced motion removes layout animation duration'
Assert-LayoutContract ($ui -notmatch 'CircularProgressIndicator|drawArc|rememberInfiniteTransition') 'unknown denominator has no percentage arc or perpetual motion'
Assert-LayoutContract ($ui -match 'remember\(shown\) \{ chatRunInfoItems' -and $ui -match 'remember\(shown\) \{ chatRunInfoDescription') 'animation frames reuse bounded labels for unchanged metrics'
Assert-LayoutContract ($metricsSource -match 'MAX_METRIC_TRACKED_IDS = 256' -and $metricsSource -match 'MAX_METRIC_ID_LENGTH = 256' -and $metricsSource -match 'ids\.clear\(\)') 'ID deduplication has capacity and length limits and releases references on overflow'
Assert-LayoutContract ($metricsSource -match 'if \(!known\) return' -and $metricsSource -match 'if \(known\) ids\.size else null') 'over-budget counters remain unknown rather than evicting and overcounting'
Assert-LayoutContract ($metricsSource -notmatch 'TextDelta\s*->|ReasoningDelta\s*->|joinToString|\.toList\(' -and $metricsSource -match 'steps = executedTools\.count') 'collector snapshots neither copy event histories nor accumulate long text'
if ($ScriptOnly) {
    Write-Output 'PASS script-only source contracts; no JVM/Kotlin/Gradle, no output files; executable and device QA pending'
    return
}
if (-not $JavaRoot) {
    $JavaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
}
$java = Join-Path $JavaRoot 'bin\java.exe'
$moduleRoot = Join-Path $TempRoot 'caches\gradle\caches\modules-2\files-2.1'
$transformRoot = Join-Path $TempRoot 'caches\gradle\caches\8.11.1\transforms'
$androidJar = Join-Path $TempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
function Find-CachedJar([string]$RelativeDirectory) {
    $directory = Join-Path $moduleRoot $RelativeDirectory
    if (-not (Test-Path -LiteralPath $directory)) { throw "Missing cached dependency: $RelativeDirectory" }
    $jar = Get-ChildItem -LiteralPath $directory -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached jar: $RelativeDirectory" }
    return $jar
}
$compiler = Find-CachedJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20'
$stdlib = Find-CachedJar 'org.jetbrains.kotlin\kotlin-stdlib\2.1.20'
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.9.0'
$reflect = Find-CachedJar 'org.jetbrains.kotlin\kotlin-reflect\2.1.0'
$scriptRuntime = Find-CachedJar 'org.jetbrains.kotlin\kotlin-script-runtime\2.1.20'
$annotations = Find-CachedJar 'org.jetbrains\annotations\23.0.0'
$trove = Find-CachedJar 'org.jetbrains.intellij.deps\trove4j\1.0.20200330'
$composePlugin = Find-CachedJar 'org.jetbrains.kotlin\kotlin-compose-compiler-plugin-embeddable\2.1.20'
$collection = Find-CachedJar 'androidx.collection\collection-jvm\1.5.0'
if (-not (Test-Path -LiteralPath $androidJar) -or -not (Test-Path -LiteralPath $transformRoot)) {
    throw 'Missing cached Android/Compose APIs; this harness downloads nothing'
}
$apiJars = @(Get-ChildItem -LiteralPath $transformRoot -Recurse -File -Filter '*api.jar' |
    Sort-Object FullName | Select-Object -ExpandProperty FullName)
if (-not ($apiJars | Where-Object { [IO.Path]::GetFileName($_) -like 'material3*' })) {
    throw 'Missing cached Material3 API; this harness downloads nothing'
}
$compilerClasspath = @($compiler, $stdlib, $coroutines, $reflect, $scriptRuntime, $annotations, $trove) -join ';'
$classpath = (@($stdlib, $coroutines, $annotations, $collection, $androidJar) + $apiJars) -join ';'
$fixtureRoot = Join-Path $PSScriptRoot 'tests\chat-run-metrics'
$sources = @(
    (Join-Path $sourceRoot 'data\AssistantStreamProtocol.kt'),
    (Join-Path $sourceRoot 'data\ChatRunMetrics.kt'),
    (Join-Path $sourceRoot 'ui\ChatRunInfoBar.kt'),
    (Join-Path $sourceRoot 'ui\components\GlassMotion.kt'),
    (Join-Path $fixtureRoot 'ChatRunInfoCompileFixtures.kt'),
    (Join-Path $fixtureRoot 'ChatRunMetricsChecks.kt')
)
if ($ChecksJar) {
    $outputJar = [IO.Path]::GetFullPath($ChecksJar)
    $tempPrefix = $TempRoot.TrimEnd([char[]]'\/') + [IO.Path]::DirectorySeparatorChar
    if (-not $outputJar.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $outputJar -PathType Leaf) -or
        [IO.Path]::GetExtension($outputJar) -ne '.jar') {
        throw 'ChecksJar must be an existing focused jar under TempRoot'
    }
    $testRoot = Split-Path $outputJar -Parent
    & $java "-Djava.io.tmpdir=$testRoot" -Xms16m -Xmx128m -XX:MaxMetaspaceSize=96m -cp "$outputJar;$classpath" com.dafeiyu.mobile.ui.ChatRunMetricsChecksKt |
        Tee-Object -FilePath (Join-Path $testRoot 'fixture-results.log')
    if ($LASTEXITCODE -ne 0) { throw 'Chat run metrics checks failed' }
    return
}
$testRoot = Join-Path $TempRoot ('qa\chat-run-metrics\run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$outputJar = Join-Path $testRoot 'chat-run-metrics-checks.jar'
Write-Output "Chat run metrics QA output: $testRoot"
# Compile real metrics, info bar and motion against cached Compose APIs.
# GlassSurface/FishGlyph rendering and the reduce-motion local are signature fixtures.
& $java "-Djava.io.tmpdir=$testRoot" -Xms32m -Xmx384m -XX:MaxMetaspaceSize=192m -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -Werror "-Xplugin=$composePlugin" `
    -classpath $classpath -d $outputJar @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Chat run metrics/info bar Compose compilation failed' }
Write-Output 'PASS real metrics/info bar Compose compilation (glass signature fixture; no Gradle)'
if ($CompileOnly) {
    Write-Output "Prepared focused checks jar: $outputJar"
    Write-Output 'Executable metrics checks are pending; run this script with -ChecksJar using the path above'
    return
}
& $java "-Djava.io.tmpdir=$testRoot" -Xms16m -Xmx128m -XX:MaxMetaspaceSize=96m -cp "$outputJar;$classpath" com.dafeiyu.mobile.ui.ChatRunMetricsChecksKt |
    Tee-Object -FilePath (Join-Path $testRoot 'fixture-results.log')
if ($LASTEXITCODE -ne 0) { throw 'Chat run metrics checks failed' }

