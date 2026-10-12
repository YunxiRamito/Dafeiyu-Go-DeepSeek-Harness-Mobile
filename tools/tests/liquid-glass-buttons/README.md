# Focused Liquid Glass Checks

The runner is `tools/test-liquid-glass-buttons.ps1`. Keep output under the G: project or
`../Temp_Android`; the runner rejects C: output. Production sources are read-only.

## Evidence Boundaries

- `Script` checks source contracts, saves reviewed production SHA256 hashes, and generates
  `CurrentPairCpuHelpers.kt`. It runs no JVM, Compose layout, Android shader, or device render.
- `Shared` compiles the real shared Compose sources, including `LiquidGlassButtonPair.kt`
  and `GlassPairLensShader.kt`, API consumers, and CPU checks with `-Werror`; then runs
  gesture, ripple scope, fallback allocation, bridge, pair transform, sanitation, and sample checks.
- `Chrome` compiles the extracted current `PageChrome` and real actions/menu/task models
  against a successful, hash-matched Shared jar. `-Xfriend-paths` permits its intended
  access to the internal shared pair. Repository, Sidebar and title boundaries are fixtures.
- `Integrated` freezes all selected inputs, compiles shared sources and current Chrome in
  one invocation, runs CPU checks, and verifies the frozen inputs still match live sources.

The generated CPU adapter embeds the current production `shape` local function and shader
sanitation/optics helpers **verbatim**, with only callable test wrappers. Extraction fails if
its source markers disappear. These tests execute production Kotlin, not a manually duplicated
formula, and do not pretend to execute AGSL. The generated file is included in Shared/Integrated
input hashes and the Integrated freeze manifest; both original pair files are also hashed.

The CPU checks cover independent geometric landmarks at four densities, anisotropic scale,
translation invariance, clamped and invalid touches, retained visual release state, original
circular hit boundaries, continuous and invalid alpha inputs, invalid shape removal,
short-edge optics invariants, and partial/disjoint/one-pixel capture intersections.

Static shader contracts retain common-field contour/normal/shadow use, zero-alpha exclusion,
opaque survivor preservation, recording overflow checks, clamped backdrop taps, and
premultiplied output. Pair contracts also guard against double alpha scaling of blend,
verify RTL center order, and require the active log after drawing once per effect cache.
Static launcher contracts retain initial alpha publication, current
callback observation, disposal reset, and shared material/shadow ownership. The standalone
bridge remains checked; Chrome now delegates its optics to the pair.

## Main Thread Commands

Run JVM stages serially from the project root. The default TempRoot resolves to the existing
G: `../Temp_Android`; pass `-JavaRoot` explicitly when the main thread uses a different JDK.

```powershell
& ./tools/test-liquid-glass-buttons.ps1 -Stage Script
& ./tools/test-liquid-glass-buttons.ps1 -Stage Shared -CompilerHeapMb 512
# Use the exact SharedJar path printed by the preceding successful Shared run.
& ./tools/test-liquid-glass-buttons.ps1 -Stage Chrome -SharedJar $sharedJar -CompilerHeapMb 512
& ./tools/test-liquid-glass-buttons.ps1 -Stage Integrated -CompilerHeapMb 512
```

`$sharedJar` must refer to that run's `liquid-glass-checks.jar`; a neighboring
`.sources.json` file is required and stale shared inputs fail. Integrated is the definitive
single-module integration check; Shared plus Chrome also checks the isolated module boundary.

## Device Optical Acceptance

Offline passes do not establish any of the following. Verify on a supported API 33+ device
with real recorded page pixels, plus unsupported/no-capture and accessibility fallback paths:

- Rest, press, release and drag at several densities: fused silhouette, rim, normal and shadow
  follow one surface; no bright seam appears at the symmetric neck.
- Open/dismiss each launcher menu, interrupt with predictive back, and dispose/re-enter:
  fading alpha, survivor opacity and shadow remain continuous with no ghost launcher.
- Translate/scroll the page and touch both launchers: backdrop alignment and foreground/material
  transform remain synchronous, including the asynchronous publication hand-off frames.
- Shader construction/effect failure and first successful readiness frame: individual/shared
  material ownership has neither duplicated material nor a visible missing frame.
- Gap and outer transparent padding: no extra clickable/semantic target; each original 48dp
  circular control retains correct gesture cancellation and accessible actions.
- Transparent capture content: chromatic taps and reflections preserve valid premultiplied
  RGBA without fringes; record screenshots/video and device/API details separately.
