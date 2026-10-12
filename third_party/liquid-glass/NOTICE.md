# Liquid Glass algorithm attribution

`app/src/main/java/com/dafeiyu/mobile/ui/components/GlassSurface.kt` adapts a
small portion of the AGSL lens algorithm from QWEA0/Liquid-Glass-Android.

- Repository: https://github.com/QWEA0/Liquid-Glass-Android
- Commit: `16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d`
- Source: `liquidglass/src/main/java/com/example/liquidglass/GlassLensRenderer.kt`
- Copyright: (c) 2025-2026 pandadog
- License: MIT, preserved in the adjacent `LICENSE` file

The upstream library is not included as a dependency. This adaptation uses the
rounded rectangle distance field, numerical edge normals, inward inverse-power
bevel refraction, short-edge optical scaling, RGB dispersion, sampling bounds,
and narrow directional reflection. The adaptation deliberately removes the
wide internal glow and full-surface brightness fill. It omits
upstream native code, custom View controls, gesture pipeline, arbitrary backdrop
capture, and other materials. Compose page capture, the independent fallback
backdrop, and the control wrapper are specific to this project.

The 0.1.20 adaptation also includes `GlassPairLensShader.kt` and
`LiquidGlassButtonPair.kt`: polynomial smooth-min for two transformed circular
shapes, a common field for coverage and numerical normals, refraction and rim
reflection. The Compose integration, inverse-scaled ellipse field correction,
per-launcher opacity and bounded two-touch/shadow handling are local changes.
The same pinned upstream commit and MIT license apply to the adapted portions.

The 0.1.22 repair adds `LiquidGlassFusionSurface.kt` for two measured rounded
rectangles, with the same smooth-min lens and separate page capture. Connection
strength follows actual surface separation, not press state. Coloured-medium
absorption/scattering follows the same fixed `GlassLensRenderer.kt` source;
per-shape tint, Compose geometry and fallback ownership are local adaptations.

The 0.1.23 repair gates the toolbar neck by measured transform changes and a
bounded fade after motion stops. Cached contour shadows and the shared SDF
shadow use locally normalized user reference parameters. Dark coloured-medium
scattering is increased without replacing page transmission with an opaque
white fill. These motion, shadow and tuning changes are local adaptations.
