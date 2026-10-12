# 液态玻璃组件规范

适用目录：`app/src/main/java/com/dafeiyu/mobile/ui/components/`。行号对应 2026-10-12 版本，改动后请自行重新核对。研究依据见 `docs/research/liquid-glass-20261012/`（REPORT.md、COMPOSE-ADAPTATION-REVIEW.md、COMPOSE-CANDIDATE.md）。

## 必须遵守的规则

1. AGSL 里每个 `uniform` 都要在 Kotlin 侧有对应的 `setFloatUniform`，而且 shader 主体必须真正用到它。AGSL 编译会剪掉没被使用的 uniform，这时调用 `setFloatUniform` 会抛 `IllegalArgumentException`，App 直接闪退。不要把调用方会设置的 uniform 在 shader 里写死成常量（反例：`falloff` 曾被写死成 `2.5`）。
2. AGSL 是运行时编译，Kotlin 编译发现不了 shader 错误。改 shader 后必须逐行自查语法，并运行 uniform 对照检查（见“修改清单”）。也不要用 `sample`、`distance` 这类 GLSL 内建名或保留字作变量名。
3. API 分级：`RuntimeShader`/AGSL 只在 API 33+ 使用；`RenderEffect` 和 blur 从 API 31 开始可用；API 30（minSdk）走 bitmap 低分辨率采样降级。新代码必须带 `Build.VERSION.SDK_INT` 检查或 `@RequiresApi`，失败时用 `runCatching`/`try` 回退，不能让异常传上去。
4. 不要在 draw 阶段每帧新建 `Brush`、`Shader`、`Path`、`Bitmap`、`RuntimeShader`。用 `remember(key)`、`drawWithCache`，或者只在参数变化时重建（参考 `LiveGlassEffectCache`）。缓存只保留当前 key，不要用无界 Map。
5. 必须遵守 `LocalReduceGlassTransparency`（减少透明度；“液态玻璃材质 关”也走这个 Local，见 `MainActivity.kt:225`）和 `LocalReduceGlassMotion`（减少动态效果）。开启后不能采样、不能 blur、不能折射，也不能出现跟手白光或形变动画。
6. 不得改变命中区域。融合颈部、阴影、外扩绘制区都不能接收输入；圆按钮的命中由 `GlassButtonGesture.contains`（`GlassButtonDeformation.kt`）按圆形判定。
7. 不使用 Android 默认 ripple。玻璃内容放在 `GlassInteractionProvider` 内部（`GlassSurface.kt:116`），自定义可点击行要自己 `clip` 并且不再额外叠 ripple。
8. 动画统一走 `GlassMotion`（贝塞尔 `EaseOut`）。时长使用其中的常量，不要散写数字。
9. 暗色模式下的可见性主要靠亮边（`edgeAppearance` / `glassRimFloor` / `glassSpecScale`），不要通过加厚白色 tint 实现。
10. 改动后至少要过：`compileDebugKotlin` 无 `e:`、uniform 对照脚本无 PROBLEM，再加上“修改清单”里需要真机核对的项。没有在设备上看过的项目要写“待验收”。
## 架构

| 文件 | 职责 |
| --- | --- |
| `GlassSurface.kt` | `GlassSurface` 是公共入口，负责选择材质路径、按钮阴影、跟手白光、拖动面板和前景 `GlassInteractionProvider`。文件里还有 `GlassDrawingView`（View 路径，包括 API30 bitmap 降级）、`HardwareGlassRenderer`（API31+ RenderNode + blur）、`LensShader`（API33+ 单形状 AGSL）以及 3 个 CompositionLocal。 |
| `LivePageGlassMaterial.kt` | 页面级采样材质，直接复用 `PageBackdropCapture` 的 GraphicsLayer，按 sample bounds 采样后应用 `LensShader`+blur。用 `LiveGlassEffectCache` 只缓存当前 effect。 |
| `GlassPairLensShader.kt` | 两个圆共用的 smooth-min 融合 AGSL（API33+），覆盖、法线、折射、亮边和阴影都来自同一个距离场。 |
| `LiquidGlassButtonPair.kt` | 主页右上 104×48dp 双圆的组合材质宿主，外扩 136×80dp，本身不处理输入。条件不满足时（API<33、无 capture、减少透明度/动态效果、shader 失败）会回传 `drawLauncherMaterial = true`，让两个按钮各自画材质。 |
| `LiquidGlassBridge.kt` | 侧栏行内 gap 的填色贝塞尔连接片（`WorkspaceSidebar.kt`）。不采样、没有光学效果，不能称为表面融合。 |
| `GlassMaterialProfile.kt` | 材质档位，以及 `materialTint`、`edgeAppearance`、`glassSpecScale`、`glassRimFloor`、`glassVibrancy`、`glassReducedEdgeColor`；另有 `FloatingBackdropSource`/`PagePixelsView`（弹层背景源）。 |
| `GlassLensGeometry.kt` | 根据短边推导 bevel/refraction/hairWidth，计算 blur 采样 margin 和安全 sample bounds。 |
| `GlassMotion.kt` | 共享时长、贝塞尔曲线和径向阻尼。 |
| `GlassDrag.kt` | 可拖动面板的顶部 18dp 把手拖动和回弹。 |
| `GlassHaptics.kt` | 平台 `CONFIRM` 触感，遵循系统设置。 |
| `GlassButtonDeformation.kt` | 按钮手势仲裁（slop、圆形命中、多指/滚动取消）、按压放大、方向拉伸、软阴影 `glassButtonShadow`，以及 Q 弹开关 `LocalGlassButtonSpring`。 |

### 渲染管线与降级（`GlassSurface` 第 213–239 行）

1. `reduceTransparency == true`：不透明的 `colorScheme.surface`，加 1dp 发丝边 `glassReducedEdgeColor`（暗色为白色 18%，浅色为黑色 10%）。不采样、不 blur、不跑 shader、没有白光。
2. 有 `LocalPageGlassBackdrop`：走 `LivePageGlassMaterial`。API33+ 用 `LensShader` 链接 blur；API31–32 只做 blur，并补画 tint、dim 和描边亮边；API30 没有 blur，直接画未模糊的页面副本，再叠 tint 和亮边；采样区为空时画不透明 surface 加亮边。
3. 其他情况：`AndroidView(GlassDrawingView)` 采样 `LocalGlassBackdrop` 的 source View。API33+ 用 `HardwareGlassRenderer` 加 `LensShader`；API31–32 用 RenderNode blur；API30、软件画布或失败时走 `drawBitmapBackdrop`（低分辨率重采样，不提供光学折射）；没有 source 时画不透明色。
4. 任何 AGSL 构造或设置异常都会让该实例永久降级为 blur（`lensFailed`/`rendererFailed`），并记录 `DafeiyuGlass` 日志。

shader 处理顺序（两个 shader 一致）：折射采样 → 受保护的饱和度提升 → tint 混合 → dim → 方向亮边（hair × (rimFloor + 0.70·lobes·specScale)）→ clamp。

### CompositionLocal

| Local | 定义 | 谁 provide |
| --- | --- | --- |
| `LocalReduceGlassTransparency` | `GlassSurface.kt:102` | `MainActivity.kt:225`，取值 `reduceTransparency || !glassMaterial` |
| `LocalReduceGlassMotion` | `GlassSurface.kt:103` | `MainActivity.kt:226` |
| `LocalGlassBackdrop` | `GlassSurface.kt:101` | `MainActivity.kt:222`；扫描页 `ScannerScreen.kt:123` 提供相机背景 |
| `LocalFloatingGlassBackdrop` | `GlassMaterialProfile.kt:83` | `MainActivity.kt:223`、`:409` |
| `LocalPageGlassBackdrop` | `LivePageGlassMaterial.kt:24` | `ScrollingGlassHeader.kt:163`；`AnchoredGlassMenu.kt:206`、`SidebarTransition.kt:151`、`ScannerScreen.kt:125` 显式置为 null |
| `LocalGlassMaterialProfile` | `GlassMaterialProfile.kt:82` | `AnchoredGlassMenu.kt:207`（FloatingMenu）、`SidebarTransition.kt:150`（SidebarPanel）、`AgentsTaskActions.kt:101`/`NewSessionActions.kt:78`（Button）、`ScannerScreen.kt:209`（MediaButton） |
| `LocalGlassButtonSpring` | `GlassButtonDeformation.kt:83` | `MainActivity.kt:227` |
| `LocalGlassHaptics` | `GlassHaptics.kt:14` | `MainActivity.kt:228` |
## 调用方式

普通玻璃面板（默认 Standard，跟随 Local 自动降级）：

```kotlin
GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
    Text("内容", Modifier.padding(16.dp))
}
```

可拖动面板（顶部自动预留 18dp 把手）：

```kotlin
GlassSurface(modifier = Modifier.fillMaxWidth(), draggable = true) { PanelContent() }
```

圆形悬浮按钮（参考 `NewSessionActions.kt:54-68`）。形变 modifier 要挂在 GlassSurface 的 modifier 上，命中和语义交给内部的 IconButton：

```kotlin
val deformation = rememberGlassButtonDeformationState()
GlassSurface(
    buttonDeformation = deformation,
    modifier = Modifier.size(48.dp)
        .applyButtonDeformation(state = deformation, circularHit = true),
    cornerRadius = 24.dp,
    tint = MaterialTheme.colorScheme.surface.copy(alpha = .18f),
) {
    IconButton(onClick = onClick, modifier = Modifier.matchParentSize()
        .semantics { contentDescription = "打开快捷操作" }) { FishGlyph("add", Modifier.size(23.dp)) }
}
```

只要传了 `buttonDeformation`，profile 就会自动从 Standard 切到 Button。阴影由 `applyButtonDeformation` 绘制（`drawShadow = true`）；GlassSurface 只在“按钮档位但没有 deformation”时才自己画阴影（第 206–207 行），所以同一个按钮不会出现两层阴影。

按钮对（只用于右上两个 48dp 圆，参考 `MainActivity.kt:509`）：

```kotlin
LiquidGlassButtonPair(first = a, second = b, firstAlpha = { aAlpha }, secondAlpha = { bAlpha }) { drawLauncherMaterial ->
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 两个按钮都传：GlassSurface(drawMaterial = drawLauncherMaterial, ...)
        //            applyButtonDeformation(..., drawShadow = drawLauncherMaterial)
    }
}
```

页面级采样材质：放在 `ScrollingGlassHeader` 的 content 中，它会 provide `LocalPageGlassBackdrop`，`GlassSurface` 自动走 `LivePageGlassMaterial`，不需要直接调用这个 internal 函数。

选择 profile：

```kotlin
CompositionLocalProvider(LocalGlassMaterialProfile provides GlassMaterialProfile.MediaButton) {
    GlassSurface(...) { ... }   // 相机/照片上的按钮：带 16% 压暗
}
```

Standard 用于一般面板，Button 用于圆按钮，FloatingMenu 用于弹出菜单，SidebarPanel 用于侧栏（安静亮边、低饱和），MediaButton 用于媒体上方的控件。

### 不要这样做

```kotlin
// 错：draw 中每帧新建 Brush
Modifier.drawBehind { drawRect(Brush.radialGradient(listOf(Color.White, Color.Transparent))) }
// 对：drawWithCache { val b = Brush...; onDrawBehind { drawRect(b, alpha = press) } }

// 错：shader 里写死调用方在设置的 uniform → 被剪掉 → setFloatUniform 抛异常闪退
//   float slope = pow(1.0 + 4.0 * t, -2.5);   // 而 Kotlin 侧仍 setFloatUniform("falloff", ...)

// 错：暗色通过加大白色 tint alpha 提高可见度 → 灰白块，白字对比度下降
// 错：给玻璃按钮加 Modifier.clickable 的默认 indication / ripple
// 错：用 padding/外扩绘制区扩大命中区域；在融合颈部挂 pointerInput
// 错：绕开 LocalReduceGlassTransparency 直接画采样或白光
// 错：写 tween(300) 之类的散值；应使用 GlassMotion.scalar(...)
```
## 关键参数

材质档位（`GlassMaterialProfile.kt:31-35`）。tint alpha 取“调用方 tint alpha”和档位上限中较小的那个：

| Profile | blurDp | 浅/深 tint α 上限 | dim | 实际 tint 颜色 |
| --- | --- | --- | --- | --- |
| Standard | 3.5 | .16 / .16 | 0 | 调用方 tint |
| Button | 2 | .12 / .10 | 0 | 白色（暗色不加厚） |
| FloatingMenu | 3.5 | .16 / .16 | 0 | 调用方 tint |
| SidebarPanel | 3 | .12 / .14 | 0 | 浅色白、深色 #1C1C1E |
| MediaButton | 2 | .14 / .14 | .16 | 调用方 tint |

依据：QWEA0 `GlassMaterial.kt` 的 REGULAR 档 tint 为 `0x24FFFFFF`（约 14%），CLEAR 档 dim 为 0.16、blur ×0.35（见 `docs/research/liquid-glass-20261012/source/liquidglass/.../GlassMaterial.kt`、REPORT.md）。

描边亮边 `edgeAppearance`（front / middle / back 白色 α，线宽 dp），用于没有 AGSL 光学的路径：

| 条件 | 浅色 | 深色 |
| --- | --- | --- |
| SidebarPanel | .14 / 0 / .06，0.65 | .24 / .04 / .12，0.65 |
| Button / MediaButton | .44 / .08 / .30，0.86 | .72 / .16 / .52，0.90 |
| 其他 | .50 / .08 / .38，1.15 | .62 / .12 / .46，1.15 |

AGSL 亮边与色彩（`GlassMaterialProfile.kt:58-80`）：

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `specScale` | 按钮 .80 / 深 1.05；面板 .60 / 深 .80；侧栏 .45 / 深 .60 | 方向反射瓣强度 |
| `rimFloor` | 浅色 0；深色：按钮 .10、面板 .06、侧栏 .04 | 与方向无关的发丝亮线，只在暗色启用 |
| `satFactor` | 1.25；侧栏 1.10 | QWEA0 受保护的饱和度曲线（`GlassLensRenderer.kt:213-224`）：对低饱和像素提升较多，对高饱和和接近白色的像素提升较少，最后 clamp。上游默认 1.40，这里取低一些，因为页面本身已有主题色 |
| `falloff` | 2.0（`GlassSurface.kt:603`；pair shader 固定为 2.0，对应常量 0.04 = 5^-2） | 逆幂折射剖面，取上游默认值 `refractionFalloff = 2f` |
| 减少透明度发丝边 | 深色白 .18 / 浅色黑 .10，1dp | `glassReducedEdgeColor` |

几何（`GlassLensGeometry.kt:14-21`）：bevel = min(16dp, 短边×0.30)；refraction ≤ 40dp；hairWidth = min(1.2dp, 短边×0.05)。

动画与交互（`GlassMotion.kt`、`GlassButtonDeformation.kt`）：

| 项 | 值 |
| --- | --- |
| 曲线 | `CubicBezierEasing(.16, 1, .3, 1)` |
| 按下 / 松手 / 形变 | 150ms / 360ms / 380ms（`MorphMillis`，展开面板共用） |
| 按压放大 | 圆按钮 1.035，其他 1.025 |
| 方向拉伸 | 沿拖动方向 +4.5%，垂直方向 −1.8%（相对 52dp 距离归一化），位移径向阻尼上限 6dp |
| 软阴影 | elevation 4dp，ambient 黑 12%，spot 黑 16%，clip=false |
| 跟手白光 | 白色 18% × opticalPress，半径为长边的 0.65，被按钮外层 clip 限制在轮廓内 |
| 减少动态效果 | 所有时长为 0，没有放大、拉伸、白光，refractPx=0 |
`LocalGlassButtonSpring = false`（Q 弹开关关闭）时，放大和拉伸这类几何变化会关闭，具体以 `GlassButtonDeformation.kt` 为准。

## 修改清单

改动前：

- 先把要改的文件备份到 `../Temp_Android/` 下的备份目录。项目没有 git。
- 重大功能先按 `AGENTS.md` 的要求研究 GitHub，并在 `docs/research/` 留下记录。

改动后，离线检查（必须执行）：

- 运行 `export JAVA_HOME='C:\Program Files\Java\JDK21'; pwsh -NoProfile -File tools/build.ps1 -Tasks :app:compileDebugKotlin -MaxWorkers 2`，确认日志中没有 `^e:`。
- 逐个核对两个 shader（`GlassSurface.kt` 的 `LensShader.SOURCE`、`GlassPairLensShader.kt` 的 `SOURCE`）：每个声明的 uniform 都在 shader 主体中被使用，都有对应的 `setFloatUniform`，反过来也一样。可以用一个小脚本解析 `uniform … name;` 和 `setFloatUniform("name"` 后做比对，pair shader 的 `$suffix` 会展开为 1/2。
- 自查 AGSL：类型（float/float2/half4）、分号、没有使用保留字作变量名、没有除以零（分母要 max(…, ε)）。
- 检查 draw lambda 里没有新建 Brush/Shader/Path/Bitmap。

设备检查（看过真实画面才能勾选，没看过就标“待验收”）：

- 浅色和深色主题下，分别在浅色、深色、彩色背景上看玻璃的亮边、文字对比度和是否过饱和。
- 减少透明度和“液态玻璃材质 关”：不透明 surface 加发丝边，文字可读，没有白光、没有采样。
- 减少动态效果：按压不放大、无白光、无形变，命中照常。
- 按压、松手、拖出取消、滚动取消、多指取消：都能恢复，没有 ripple，白光不会越过按钮轮廓，阴影没有叠成两层。
- 分别记录 API30（bitmap 降级）、API31–32（blur）、API33+（AGSL）的实际表现，再确认 shader 失败时会回退且不闪退（logcat 中 `DafeiyuGlass`/`GlassPair`）。
- 性能：滚动和动画期间检查帧时间与 GPU 内存，确认没有持续 invalidate 循环。
