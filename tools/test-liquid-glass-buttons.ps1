param(
    [string]$TempRoot,
    [string]$JavaRoot,
    [ValidateSet('Script', 'Shared', 'Chrome', 'Integrated', 'All')][string]$Stage = 'All',
    [string]$SharedJar,
    [ValidateRange(128, 512)][int]$CompilerHeapMb = 512
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $TempRoot) { $TempRoot = Join-Path $projectRoot '..\Temp_Android' }
$TempRoot = [System.IO.Path]::GetFullPath($TempRoot)
if ([System.IO.Path]::GetPathRoot($TempRoot) -eq 'C:\') { throw 'Use a project/Temp_Android output directory outside C:' }
$sourceRoot = Join-Path $projectRoot 'app\src\main\java\com\dafeiyu\mobile\ui\components'
$fixtureRoot = Join-Path $PSScriptRoot 'tests\liquid-glass-buttons'
$activityPath = Join-Path $sourceRoot '..\..\MainActivity.kt'
$surface = Get-Content -LiteralPath (Join-Path $sourceRoot 'GlassSurface.kt') -Raw
$deformation = Get-Content -LiteralPath (Join-Path $sourceRoot 'GlassButtonDeformation.kt') -Raw
$live = Get-Content -LiteralPath (Join-Path $sourceRoot 'LivePageGlassMaterial.kt') -Raw
$fish = Get-Content -LiteralPath (Join-Path $sourceRoot 'FishComponents.kt') -Raw
$bridge = Get-Content -LiteralPath (Join-Path $sourceRoot 'LiquidGlassBridge.kt') -Raw
$pair = Get-Content -LiteralPath (Join-Path $sourceRoot 'LiquidGlassButtonPair.kt') -Raw
$pairShader = Get-Content -LiteralPath (Join-Path $sourceRoot 'GlassPairLensShader.kt') -Raw
$actions = Get-Content -LiteralPath (Join-Path $sourceRoot 'AgentsTaskActions.kt') -Raw
$newSession = Get-Content -LiteralPath (Join-Path $sourceRoot 'NewSessionActions.kt') -Raw
$activity = Get-Content -LiteralPath $activityPath -Raw
$chromeStart = $activity.IndexOf('private fun PageChrome(')
if ($chromeStart -lt 0) { throw 'Current PageChrome start marker missing' }
$chromeEnd = $activity.IndexOf('/** Keep outgoing controls', $chromeStart)
if ($chromeEnd -lt 0) { throw 'Current PageChrome end marker missing' }
$chrome = $activity.Substring($chromeStart, $chromeEnd - $chromeStart).TrimEnd()
$testRoot = Join-Path $TempRoot ('qa\liquid-glass-buttons\run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
Write-Output "Liquid glass focused QA ($Stage): $testRoot"

# Compile exact production CPU helpers without constructing Android's RuntimeShader stub.
# Delimited spans stay verbatim: these are not independent copies of the algorithms.
function Get-SourceSpan([string]$Source, [string]$Start, [string]$End) {
    $startIndex = $Source.IndexOf($Start, [StringComparison]::Ordinal)
    if ($startIndex -lt 0) { throw "Production helper start missing: $Start" }
    $endIndex = $Source.IndexOf($End, $startIndex + $Start.Length, [StringComparison]::Ordinal)
    if ($endIndex -lt 0) { throw "Production helper end missing: $End" }
    return $Source.Substring($startIndex, $endIndex - $startIndex).TrimEnd()
}
$shapeHelper = Get-SourceSpan $pair 'fun shape(' 'val firstShape ='
$frameAssembly = Get-SourceSpan $pair 'val frame = GlassPairFrame(' 'val key = GlassPairEffectKey('
$shaderHelpers = Get-SourceSpan $pairShader 'private fun GlassPairShape.sanitized(' 'private companion object'
$cpuHeader = @'
package com.dafeiyu.mobile.ui.components
import androidx.compose.ui.geometry.Offset
import kotlin.math.ceil
import kotlin.math.min

// Generated verbatim production spans; only these test adapters are synthetic.
internal object CurrentPairCpuHelpers {
    fun sanitize(shape: GlassPairShape, limit: Float): GlassPairShape = shape.sanitized(limit)
    @JvmName("opticsForChecks")
    fun optics(shape: GlassPairShape, density: Float): GlassLensGeometry = shape.optics(density)
'@
$shapeAdapter = @'
    fun map(density: Float, center: Offset, state: GlassButtonDeformationState,
        visual: GlassButtonVisualFrame, alpha: Float): GlassPairShape {
'@
$cpuPath = Join-Path $testRoot 'CurrentPairCpuHelpers.kt'
($cpuHeader + [Environment]::NewLine + $shaderHelpers + [Environment]::NewLine +
    $shapeAdapter + [Environment]::NewLine + $shapeHelper + [Environment]::NewLine +
    'return shape(center, state, visual, alpha)' + [Environment]::NewLine + '}' + [Environment]::NewLine + '}') |
    Set-Content -LiteralPath $cpuPath -Encoding utf8

# Pure source contracts run without Java, Android processes or dependency scans.
$contracts = [ordered]@{
    'Material3 ripple disabled only within glass provider' = $surface.Contains('LocalRippleConfiguration provides null')
    'Foundation indication locally overridden' = $surface.Contains('LocalIndication provides GlassNoRipple')
    'Shadow explicitly avoids its own clipping' = $deformation.Contains('shape = shape, clip = false')
    'Gesture observer outside visual deformation' = $deformation.IndexOf('.pointerInput(deformation, circularHit, consumeDrag)') -lt $deformation.IndexOf('.graphicsLayer {')
    'Shadow follows visual deformation' = $deformation.IndexOf('.graphicsLayer {') -lt $deformation.IndexOf('Modifier.glassButtonShadow(if (circularHit)')
    'Circular and rectangular press targets retained' = $deformation.Contains('if (circular) 1.035f else 1.025f')
    'Glow inside surface clip' = $surface.IndexOf('.clip(RoundedCornerShape(cornerRadius))') -lt $surface.IndexOf('drawRoundRect(Brush.radialGradient(')
    'Glow honors both accessibility flags' = $surface.Contains('buttonStyle && !reduceTransparency && !reduceMotion && opticalPress > 0f')
    'Opaque rendering retained' = $surface.Contains('if (reduced || source == null')
    'Native rim uses shared profile' = $surface.Contains('edgeProfile.edgeAppearance(darkTheme)')
    'Live fallback rim uses shared profile' = $live.Contains('profile.edgeAppearance(darkTheme)')
    'AGSL brighter dark rim and baseline' = $surface.Contains('buttonStyle && darkTheme) 1.05f') -and $surface.Contains('uniform float rimFloor;')
    'Parent scroll cancellation observed in Final' = $deformation.Contains('awaitPointerEvent(PointerEventPass.Final)')
    'Rejected UP consumed before child' = $deformation.Contains('if (!accepted) change.consume()')
    'Cancelled or dragging gesture clears press before UP' = $deformation.Contains('val active: Boolean get() = !cancelled && !dragging') -and $deformation.Contains('deformation.pressed = gesture.active')
    'Additional pointer cancels even on first DOWN' = $deformation.Contains('currentEvent.changes.any { it.id != down.id && it.pressed }')
    'Fish button external clickable has no indication' = $fish.Contains('interactionSource = interactionSource, indication = null')
    'Bridge has no perpetual animation or pointer handler' = -not ($bridge -match 'rememberInfiniteTransition|LaunchedEffect|pointerInput|clickable|semantics')
    'Bridge uses finite shared entrance and release' = $bridge.Contains('GlassMotion.PressMillis else GlassMotion.ReleaseMillis') -and $bridge.Contains('animationSpec = liquidBridgeMotion(target, reducedMotion)')
    'Bridge height belongs to caller' = -not ($bridge -match 'Modifier\.height|modifier\.height|heightIn|fillMaxHeight')
    'Bridge opaque accessibility fill' = $bridge.Contains('drawPath(body, tint.copy(alpha = 1f))')
    'Opaque bridge collapses continuously toward zero' = $bridge.Contains('(.25f + .19f * active) * active')
    'Reduced motion immediately overrides animated bridge' = $bridge.Contains('if (reducedMotion) target else animatedStrength')
    'Three bridge paths retained per composition and reset per draw' = ([regex]::Matches($bridge, 'remember \{ Path\(\) \}').Count -eq 3) -and ([regex]::Matches($bridge, 'reset\(\)').Count -eq 3)
    'Bridge color brushes cached and alpha applied during draw' = $bridge.Contains('val bodyBrush = remember(tint)') -and $bridge.Contains('val edgeBrush = remember') -and $bridge.Contains('drawPath(body, bodyBrush, alpha = active)')
    'No bridge bitmap or growing collection' = -not ($bridge -match 'Bitmap|mutableListOf|ArrayList|mutableMapOf')
    'Bitmap allocation only on changed sample dimensions' = $surface.Contains('if (bitmap == null || bitmap.width != bw || bitmap.height != bh)')
    'Bitmap longest edge explicitly capped at 120 or 48 pixels' = $surface.Contains('val limit = if (useRefraction) 120 else 48') -and ([regex]::Matches($surface, 'coerceIn\(1, limit\)').Count -eq 2)
    'Bitmap released on opaque/no-source switch and detach' = $surface.Contains('if (reduced || state == null) releaseBitmapBackdrop()') -and $surface.Contains('fallbackBitmap?.recycle(); fallbackBitmap = null; fallbackCanvas = null')
    'Native rim shader has one bounded size/profile cache' = $surface.Contains('rimWidth != width || rimHeight != height || rimAppearance != edge')
    'Live pipeline retains one effect key without bitmap cache' = $live.Contains('var key: LiveGlassEffectKey? = null') -and -not ($live -match 'createBitmap|mutableMapOf|ArrayList')
    'Pair preserves 104x48dp host, 48dp launchers and 8dp gap' = $pair.Contains('Box(Modifier.width(104.dp).height(48.dp))') -and $chrome.Contains('Arrangement.spacedBy(8.dp)') -and $actions.Contains('Modifier.size(48.dp)') -and $newSession.Contains('Modifier.size(48.dp)')
    'Chrome delegates optics to one pair instead of a local gap bridge' = $chrome.Contains('LiquidGlassButtonPair(') -and $chrome.Contains('first = actionsButton, second = plusButton') -and -not ($chrome -match 'LiquidGlassBridge\(|delta\.getDistance')
    'Chrome actions share actual deformation states' = $chrome.Contains('deformation = actionsButton') -and $chrome.Contains('deformation = plusButton')
    'Chrome supplies separate live launcher alpha channels' = $chrome.Contains('firstAlpha = { actionsLauncherAlpha }, secondAlpha = { plusLauncherAlpha }') -and $chrome.Contains('onLauncherAlphaChanged = { actionsLauncherAlpha = it }') -and $chrome.Contains('onLauncherAlphaChanged = { plusLauncherAlpha = it }')
    'Chrome forwards shared material ownership to both launchers' = ([regex]::Matches($chrome, 'drawLauncherMaterial = drawLauncherMaterial').Count -eq 2)
    'Pair falls back for old API, absent capture and both accessibility flags' = $pair.Contains('Build.VERSION.SDK_INT >= 33 && capture != null && !reducedTransparency && !reducedMotion')
    'Pair ownership waits for successful effect readiness' = $pair.Contains('val ownsMaterial = supported && lens != null && ready') -and $pair.Contains('content(!ownsMaterial)') -and $pair.Contains('if (!ownsMaterial) return@drawWithContent')
    'Pair failure restores launcher ownership and prevents retries' = $pair.Contains('cache.failed = true') -and $pair.Contains('if (bounds == null || cache.failed)') -and ([regex]::Matches($pair, 'if \(ready\) ready = false').Count -eq 2)
    'Pair does not draw over individual material on first readiness frame' = $pair -match '(?s)if \(!ready\)\s*\{\s*ready = true\s*return@drawWithContent\s*\}'
    'Pair optical drawing adds no gesture or semantic target' = -not ($pair -match '\.pointerInput\(|\.clickable\(|\.semantics\(|\.clip\(')
    'Pair records and samples the same page in root coordinates' = $pair.Contains('it.localToRoot(Offset.Zero)') -and $pair.Contains('margin + capture.origin.x - origin.x') -and $pair.Contains('margin + capture.origin.y - origin.y') -and $pair.Contains('translate(sourceLeft, sourceTop) { drawLayer(capture.layer) }') -and $pair.Contains('translate(-margin.toFloat(), -margin.toFloat()) { drawLayer(copy) }')
    'Pair uses each published visual frame, including release press' = $pair.Contains('val firstVisual = first.visualFrame') -and $pair.Contains('val secondVisual = second.visualFrame') -and $shapeHelper.Contains('press = visual.opticalPress') -and -not ($pair -match 'interactionStrength|animateFloatAsState')
    'Pair alpha scales blend once in shader only' = $frameAssembly.Contains('24f * density * maxOf(firstShape.press, secondShape.press)') -and -not ($frameAssembly -match 'alpha') -and $pairShader.Contains('float k = blendK * min(shape1.w, shape2.w);')
    'Pair centers follow launcher order in RTL' = $pair.Contains('LocalLayoutDirection.current == LayoutDirection.Rtl') -and $pair.Contains('val firstCenter = Offset((if (rightToLeft) 96f else 40f) * density, 40f * density)') -and $pair.Contains('val secondCenter = Offset((if (rightToLeft) 40f else 96f) * density, 40f * density)')
    'Pair active log occurs once per effect cache after successful draw' = $pair.Contains('if (!cache.reportedActive) {') -and $pair.Contains('cache.reportedActive = true') -and ([regex]::Matches($pair, 'Log\.i\("GlassPair"').Count -eq 1) -and $pair.IndexOf('if (!cache.reportedActive) {') -gt $pair.IndexOf('translate(-margin.toFloat(), -margin.toFloat()) { drawLayer(copy) }')
    'Foreground reads the same visual frame for all four transform axes' = $deformation.Contains('val frame = deformation.visualFrame') -and $deformation.Contains('scaleX = frame.scaleX') -and $deformation.Contains('scaleY = frame.scaleY') -and $deformation.Contains('translationX = frame.translation.x') -and $deformation.Contains('translationY = frame.translation.y')
    'Visual publication observes animation inputs without frame feedback' = $deformation.Contains('snapshotFlow {') -and $deformation.Contains('}.collect { deformation.visualFrame = it }') -and $deformation.Contains('onDispose { deformation.visualFrame = GlassButtonVisualFrame() }')
    'Combined material can suppress individual shadow' = $deformation.Contains('drawShadow: Boolean = true') -and $deformation.Contains('if (drawShadow && (circularHit || state != null))')
    'Surface material gate retains the interaction provider and content' = $surface.Contains('drawMaterial: Boolean = true') -and $surface.Contains('if (drawMaterial) {') -and $surface.Contains('if (drawMaterial && buttonStyle && !reduceTransparency && !reduceMotion && opticalPress > 0f)') -and $surface.IndexOf('GlassInteractionProvider {') -gt $surface.IndexOf('if (drawMaterial) {')
    'Pair effect cache is bounded and keyed by frame and safe sample bounds' = $pair.Contains('var key: GlassPairEffectKey? = null') -and $pair.Contains('darkTheme, frame, bounds)') -and -not ($pair -match 'createBitmap|mutableMapOf|ArrayList|rememberInfiniteTransition')
    'Shader removes zero-alpha shapes before smooth union' = $pairShader.Contains('if (shape1.w <= 0.0) {') -and $pairShader.Contains('if (shape2.w <= 0.0) {') -and $pairShader.Contains('max(shape1.w, shape2.w) <= 0.0')
    'Shader opacity preserves opaque survivor through overlap' = $pairShader.Contains('return max(mix(shape2.w, shape1.w, h), max(own1, own2));')
    'Shader uses common field for contour, normals and shadow' = $pairShader.Contains('float2 field = pairField(p);') -and $pairShader.Contains('pairField(p + float2(1.0, 0.0)).x') -and $pairShader.Contains('(1.0 - cov) * opacity * shadowStrength')
    'Shader guards recording overflow and finite sample bounds' = $pairShader.Contains('width.toLong() + margin.toLong() * 2L') -and $pairShader.Contains('recordingWidth <= Int.MAX_VALUE && recordingHeight <= Int.MAX_VALUE') -and $pairShader.Contains('sampleBounds.left.isFinite()') -and $pairShader.Contains('require(left <= right && top <= bottom)')
    'Shader clamps dispersion samples and premultiplies final output' = $pairShader.Contains('content.eval(clamp(p, sampleLo, sampleHi))') -and $pairShader.Contains('sample.rgb / sample.a') -and $pairShader.Contains('min(red.a, min(green.a, blue.a))') -and $pairShader.Contains('float alpha = materialAlpha * surface;')
}
$launcherContracts = foreach ($launcher in @(
    [pscustomobject]@{ Name = 'Actions'; Source = $actions },
    [pscustomobject]@{ Name = 'NewSession'; Source = $newSession }
)) {
    $code = $launcher.Source
    [pscustomobject]@{ Name = "$($launcher.Name) reports current alpha, observes menu fade and resets disposal"; Value =
        $code.Contains('rememberUpdatedState(onLauncherAlphaChanged)') -and
        $code.Contains('latestLauncherAlphaChanged(state.launcherAlpha)') -and
        $code.Contains('snapshotFlow { state.launcherAlpha }.collect { latestLauncherAlphaChanged(it) }') -and
        $code.Contains('onDispose { latestLauncherAlphaChanged(1f) }') }
    [pscustomobject]@{ Name = "$($launcher.Name) shares alpha and material/shadow ownership with foreground"; Value =
        $code.Contains('drawMaterial = drawLauncherMaterial') -and
        $code.Contains('drawShadow = drawLauncherMaterial') -and
        $code.Contains('.graphicsLayer { alpha = state.launcherAlpha }') }
}
foreach ($contract in $launcherContracts) { $contracts[$contract.Name] = $contract.Value }
$contractLog = foreach ($contract in $contracts.GetEnumerator()) {
    if (-not $contract.Value) { throw "FAIL $($contract.Key)" }
    "PASS $($contract.Key)"
}
$contractLog | Tee-Object -FilePath (Join-Path $testRoot 'source-contracts.log')
Get-FileHash -Algorithm SHA256 -LiteralPath @(
    (Join-Path $sourceRoot 'GlassButtonDeformation.kt'), (Join-Path $sourceRoot 'GlassSurface.kt'),
    (Join-Path $sourceRoot 'GlassMaterialProfile.kt'), (Join-Path $sourceRoot 'LivePageGlassMaterial.kt'),
    (Join-Path $sourceRoot 'LiquidGlassBridge.kt'), (Join-Path $sourceRoot 'FishComponents.kt'),
    (Join-Path $sourceRoot 'LiquidGlassButtonPair.kt'), (Join-Path $sourceRoot 'GlassPairLensShader.kt'),
    (Join-Path $sourceRoot 'AgentsTaskActions.kt'), (Join-Path $sourceRoot 'NewSessionActions.kt'), $activityPath
) | Select-Object Path, Hash | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $testRoot 'reviewed-sources.json') -Encoding utf8
if ($Stage -eq 'Script') {
    Write-Output "PASS $($contracts.Count) source contracts only; no JVM/Compose execution or device rendering"
    return
}
if (-not $JavaRoot) {
    $JavaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\JDK21' }
}
$java = Join-Path $JavaRoot 'bin\java.exe'
$moduleRoot = Join-Path $TempRoot 'caches\gradle\caches\modules-2\files-2.1'
$transformRoot = Join-Path $TempRoot 'caches\gradle\caches\8.11.1\transforms'
$androidJar = Join-Path $TempRoot 'toolchains\android-sdk\platforms\android-35\android.jar'
$resourceJar = Join-Path $TempRoot 'build\app\intermediates\compile_and_runtime_not_namespaced_r_class_jar\debug\processDebugResources\R.jar'
function Find-CachedJar([string]$RelativeDirectory) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $moduleRoot $RelativeDirectory) -Recurse -File -Filter '*.jar' |
        Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached dependency: $RelativeDirectory (no downloads)" }
    return $jar
}
foreach ($required in @($java, $androidJar, $resourceJar, $transformRoot)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing offline dependency: $required" }
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
$androidxAnnotations = Find-CachedJar 'androidx.annotation\annotation-jvm\1.9.1'
$lifecycleCommon = Find-CachedJar 'androidx.lifecycle\lifecycle-common-jvm\2.8.7'
$compilerClasspath = @($compiler, $stdlib, $coroutines, $reflect, $scriptRuntime, $annotations, $trove) -join ';'
$apiJars = @(Get-ChildItem -LiteralPath $transformRoot -Recurse -File -Filter '*api.jar' |
    Sort-Object FullName | Select-Object -ExpandProperty FullName)
if ($apiJars.Count -eq 0) { throw 'Missing cached Compose APIs (no downloads)' }
$classpath = (@($stdlib, $coroutines, $annotations, $androidxAnnotations, $collection, $lifecycleCommon, $androidJar, $resourceJar) + $apiJars) -join ';'
$names = @('GlassButtonDeformation.kt', 'GlassSurface.kt', 'LivePageGlassMaterial.kt',
    'GlassMaterialProfile.kt', 'FishComponents.kt', 'LiquidGlassBridge.kt',
    'LiquidGlassButtonPair.kt', 'GlassPairLensShader.kt',
    'GlassMotion.kt', 'GlassDrag.kt', 'GlassHaptics.kt', 'GlassLensGeometry.kt', 'ScrollingGlassHeader.kt')
$sources = @($names | ForEach-Object { Join-Path $sourceRoot $_ }) +
    @((Join-Path $fixtureRoot 'LiquidGlassChecks.kt'), (Join-Path $fixtureRoot 'LiquidGlassApiChecks.kt'),
        (Join-Path $fixtureRoot 'LiquidGlassPairChecks.kt'),
        (Join-Path $fixtureRoot 'OfflineTrace.kt'))
if ($Stage -ne 'Chrome') { $sources += $cpuPath }
function New-ChromeFocusedSources {
    # Extract the current production method verbatim; compile other agents' boundaries as fixtures.
    $header = @'
package com.dafeiyu.mobile
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.ui.*
import com.dafeiyu.mobile.ui.components.*
@Composable
'@
    $chromePath = Join-Path $testRoot 'CurrentPageChrome.kt'
    ($header + [Environment]::NewLine + $chrome) | Set-Content -LiteralPath $chromePath -Encoding utf8
    @($chromePath, (Join-Path $sourceRoot 'AgentsTaskActions.kt'),
        (Join-Path $sourceRoot 'NewSessionActions.kt'), (Join-Path $sourceRoot '..\AnchoredGlassMenu.kt'),
        (Join-Path $sourceRoot '..\..\data\BackgroundTaskModels.kt'),
        (Join-Path $fixtureRoot 'ChromeDataFixtures.kt'), (Join-Path $fixtureRoot 'ChromeUiFixtures.kt'))
}
if ($Stage -eq 'Integrated') {
    $sources += @(New-ChromeFocusedSources)
    $frozenRoot = Join-Path $testRoot 'frozen'
    New-Item -ItemType Directory -Path $frozenRoot | Out-Null
    $frozenManifest = foreach ($source in $sources) {
        $sourceHash = Get-FileHash -Algorithm SHA256 -LiteralPath $source
        $frozenPath = Join-Path $frozenRoot ([System.IO.Path]::GetFileName($source))
        if (Test-Path -LiteralPath $frozenPath) { throw "Duplicate frozen source name: $frozenPath" }
        Copy-Item -LiteralPath $source -Destination $frozenPath
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $frozenPath).Hash -ne $sourceHash.Hash) {
            throw "Source changed while freezing: $source"
        }
        [pscustomobject]@{ Source = $sourceHash.Path; Frozen = $frozenPath; Hash = $sourceHash.Hash }
    }
    $frozenManifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $testRoot 'frozen-sources.json') -Encoding utf8
    $sources = @($frozenManifest | Select-Object -ExpandProperty Frozen)
    Write-Output "Frozen $($sources.Count) source inputs; Integrated uses exactly one compiler invocation"
}
$jvmOptions = @('-Xms32m', "-Xmx${CompilerHeapMb}m", '-XX:MaxMetaspaceSize=256m',
    "-Djava.io.tmpdir=$testRoot", "-XX:ErrorFile=$testRoot\jvm-error-%p.log")
$savedTemp = $env:TEMP
$savedTmp = $env:TMP
try {
$env:TEMP = $testRoot
$env:TMP = $testRoot
if ($Stage -in @('Shared', 'Integrated', 'All')) {
    $SharedJar = Join-Path $testRoot 'liquid-glass-checks.jar'
    $sourceHashes = @(Get-FileHash -Algorithm SHA256 -LiteralPath $sources | Select-Object Path, Hash)
    & $java @jvmOptions -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
        -no-stdlib -no-reflect -jvm-target 17 -Werror "-Xplugin=$composePlugin" `
        -classpath $classpath -d $SharedJar @sources 2>&1 |
        Tee-Object -FilePath (Join-Path $testRoot 'compile.log')
    if ($LASTEXITCODE -ne 0) { throw 'Liquid glass real Compose source compilation failed' }
    foreach ($sourceHash in $sourceHashes) {
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $sourceHash.Path).Hash -ne $sourceHash.Hash) {
            throw "Shared source changed during compilation: $($sourceHash.Path)"
        }
    }
    Write-Output 'PASS shared production sources including both pair files and real pipeline dependencies compile with Compose/Werror'
    & $java -Xms16m -Xmx128m "-Djava.io.tmpdir=$testRoot" -cp "$SharedJar;$classpath" `
        com.dafeiyu.mobile.ui.components.LiquidGlassChecksKt |
        Tee-Object -FilePath (Join-Path $testRoot 'checks.log')
    if ($LASTEXITCODE -ne 0) { throw 'Liquid glass behavior checks failed' }
    $sourceHashes | ConvertTo-Json | Set-Content -LiteralPath "$SharedJar.sources.json" -Encoding utf8
    if ($Stage -eq 'Integrated') {
        Write-Output 'PASS frozen current PageChrome, real actions/menu/task models compile in the same invocation; repository/Sidebar/title boundaries are fixtures'
        $currentActivity = Get-Content -LiteralPath $activityPath -Raw
        $currentStart = $currentActivity.IndexOf('private fun PageChrome(')
        $currentEnd = $currentActivity.IndexOf('/** Keep outgoing controls', $currentStart)
        $currentChrome = $currentActivity.Substring($currentStart, $currentEnd - $currentStart).TrimEnd()
        $freezeStatus = foreach ($entry in $frozenManifest) {
            [pscustomobject]@{
                Source = $entry.Source
                Hash = $entry.Hash
                Unchanged = (Get-FileHash -Algorithm SHA256 -LiteralPath $entry.Source).Hash -eq $entry.Hash
            }
        }
        $freezeStatus | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $testRoot 'freeze-status.json') -Encoding utf8
        if (@($freezeStatus | Where-Object { -not $_.Unchanged }).Count -or $currentChrome -ne $chrome) {
            throw 'Frozen checks passed, but live inputs changed; see freeze-status.json before claiming current-source validation'
        }
        Write-Output 'PASS frozen inputs still match live sources and current PageChrome'
    }
    Write-Output "SharedJar for separately scheduled Chrome stage: $SharedJar"
}
if ($Stage -in @('Chrome', 'All')) {
    if (-not $SharedJar -or -not (Test-Path -LiteralPath $SharedJar) -or
        -not (Test-Path -LiteralPath "$SharedJar.sources.json")) {
        throw 'Chrome requires -SharedJar from a successful current Shared stage'
    }
    $manifest = @(Get-Content -LiteralPath "$SharedJar.sources.json" -Raw | ConvertFrom-Json)
    foreach ($source in $sources) {
        $sourceHash = Get-FileHash -Algorithm SHA256 -LiteralPath $source
        $entry = @($manifest | Where-Object { $_.Path -eq $sourceHash.Path })
        if ($entry.Count -ne 1 -or $entry[0].Hash -ne $sourceHash.Hash) {
            throw "SharedJar source manifest is stale: $source"
        }
    }
    $chromeSources = @(New-ChromeFocusedSources)
    $chromeHashes = @(Get-FileHash -Algorithm SHA256 -LiteralPath $chromeSources | Select-Object Path, Hash)
    & $java @jvmOptions -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
        -no-stdlib -no-reflect -jvm-target 17 -Werror "-Xplugin=$composePlugin" `
        "-Xfriend-paths=$SharedJar" -classpath "$SharedJar;$classpath" -d (Join-Path $testRoot 'page-chrome-checks.jar') @chromeSources 2>&1 |
        Tee-Object -FilePath (Join-Path $testRoot 'chrome-compile.log')
    if ($LASTEXITCODE -ne 0) { throw 'Current PageChrome/action integration Compose compilation failed' }
    foreach ($sourceHash in $chromeHashes) {
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $sourceHash.Path).Hash -ne $sourceHash.Hash) {
            throw "Chrome source changed during compilation: $($sourceHash.Path)"
        }
    }
    $currentActivity = Get-Content -LiteralPath $activityPath -Raw
    $currentStart = $currentActivity.IndexOf('private fun PageChrome(')
    $currentEnd = $currentActivity.IndexOf('/** Keep outgoing controls', $currentStart)
    if ($currentActivity.Substring($currentStart, $currentEnd - $currentStart).TrimEnd() -ne $chrome) {
        throw 'PageChrome changed during focused compilation'
    }
    $chromeHashes | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $testRoot 'chrome-sources.json') -Encoding utf8
    Write-Output 'PASS extracted current PageChrome, real actions/menu/task models compile; repository/Sidebar/title boundaries are fixtures'
}
Write-Output 'PASS requested focused stage complete; device/AGSL visual rendering not executed'
} finally {
    $env:TEMP = $savedTemp
    $env:TMP = $savedTmp
}
