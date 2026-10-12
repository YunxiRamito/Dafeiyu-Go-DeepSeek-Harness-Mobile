# 右上双圆 Compose 局部融合适配评审

日期：2026-10-12，Asia/Shanghai。

状态：**待实现，当前包冻结后由主Agent排期。** 本文只记录已完成的只读评审，不代表接口已经存在、代码已经实现或设备验收通过。

当前 0.1.19 滚动修复新包已由主线程编译，先进行设备验收与交付。生产代码继续冻结；本文建议不并入当前 scroll 修复包。保存本文期间不编译、不联网、不运行 ADB、不扩大研究。

## 评审范围与结论

依据同目录 `REPORT.md`、Newton 保留的 QWEA0 固定提交 `16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d` 源码快照，以及评审时的本地生产 API。没有重新核实上游 HEAD，沿用报告对网络失败和固定版本证据的边界。

首批“右上双圆局部组合材质”方向可采纳，现有页面捕获层能够复用。但不能只给 `LensShader` 增加 secondary rect 就交付；必须同时补齐动画几何输出、单按钮材质与阴影让位、菜单 launcher 交接和降级路径。

上游 `source/liquidglass/src/main/java/com/example/liquidglass/GlassLensRenderer.kt:96` 的 smooth-min 同时服务于覆盖率和法线；本地 gap bridge 没有共同采样或共同光学。这一差异判断成立。首批只处理主页右上两个 48dp 圆按钮及其中间 8dp 间距，不扩展为通用 N-shape 引擎，不改侧栏连接，不引入整个上游 View/native 模块。

以下生产定位均相对项目根目录，行号对应本次只读评审快照，后续实施前应重新核对。

## 必须补齐的实现条件

### 同源动画变换

`app/src/main/java/com/dafeiyu/mobile/ui/components/GlassButtonDeformation.kt:51` 的 state 只有手势状态；最终 press scale、方向 stretch 和 translation 是第 137–154 行的动画值。

组合材质必须读取这组同源动画值，不能根据 `pressed/delta` 另算一套动画，否则松手回弹时图标和玻璃轮廓会错位。方向拉伸后圆会变成椭圆，单纯改变 rect、继续使用一个圆角半径，也不能准确复现当前非等比缩放。

建议在已有 deformation state 内增加内部视觉状态输出，由当前动画产生并供图标层、组合材质共用，不新增第二套 press/release 动画。touch 坐标需经过相同变换，再转换为组合宿主局部坐标。

### 单按钮材质与阴影让位

`app/src/main/java/com/dafeiyu/mobile/ui/components/GlassSurface.kt:193` 当前始终绘制单面材质，第 220 行又叠加触点白光；按钮软阴影来自外部 deformation modifier。

组合模式若只关闭 shader，仍可能留下双圆内侧阴影、白光或重复亮边。必须明确由组合宿主统一承担材质、rim、触点白光和阴影，两个原按钮保留前景图标、命中和语义。

`GlassSurface` 第 205 行的圆角矩形 clip 不适合作为双形状组合输出裁剪；组合覆盖率应由融合场产生，不能把 104×48dp 整体裁成胶囊。

### 菜单 launcher 交接

`app/src/main/java/com/dafeiyu/mobile/ui/components/AgentsTaskActions.kt:60` 和 `app/src/main/java/com/dafeiyu/mobile/ui/components/NewSessionActions.kt:43` 都使用 `launcherAlpha` 淡出整个原按钮，Popup 内另画 anchor 材质。

改为兄弟组合面后，原按钮的 alpha 不会自动作用于组合玻璃，可能出现图标已消失、原圆和颈部仍残留。必须传递 launcher 显隐状态，并定义组合面与 Popup anchor 在打开、关闭、取消和快速重开时的连续交接，不能只依赖 boolean 可见回调。

报告建议的文件边界需要补上这两个 action 控件的 launcher 参数或状态接线。材质关闭参数只传给原 launcher，不能通过宽泛 CompositionLocal 误关 Popup 内 anchor 的材质。

## 最小接口建议

以下仅为建议签名，不是现有 API，也未进行编译验证：

```kotlin
// 原有入口默认行为保持；仅组合中的两个 launcher 显式关闭。
GlassSurface(..., drawMaterial: Boolean = true, content: ...)
applyButtonDeformation(..., drawShadow: Boolean = true)

// 仅内部使用，仍只支持两形状。
LensShader.effect(..., pair: GlassPairFrame? = null)
LivePageGlassMaterial(..., pair: (() -> GlassPairFrame)? = null)
```

- `GlassPairFrame` 固定包含两个形状和 `blendPx`。每个形状需要基础局部 bounds、圆角、动画后的 `scaleX/Y`、translation，以及局部 touch、平滑 press、launcher alpha。坐标统一为组合绘制宿主的像素坐标；两圆变换绕各自中心，shader 按同一变换求场。
- 同源视觉状态由已有 deformation 动画输出；launcher alpha 由两个 action 控件补入。不能把二者误认为现有 `interactionStrength` 已经覆盖的状态。
- `drawMaterial=false` 跳过单按钮背景、rim 和触点白光，保留前景、圆形命中、点击语义和 indication 抑制；外部 `drawShadow=false` 避免原双圆内侧阴影与组合阴影叠加。
- 新增内部 `LiquidGlassButtonPairMaterial`，复用 `LocalPageGlassBackdrop` 和 `LivePageGlassMaterial`；在 `app/src/main/java/com/dafeiyu/mobile/MainActivity.kt:499` 的固定 104×48dp 交互布局后方绘制。绘制宿主包含两圆和外扩边，不挂 pointer/click/合并 semantics，颈部不增加可点击区域。
- 现有 `LiquidGlassBridge` 保留给侧栏；本批不改变侧栏行为。双圆材质可放专用小文件，避免把 gap-only 接口静默改成不同几何。

## 风险与降级边界

| 风险 | 最小处理建议 |
| --- | --- |
| 输出被裁成胶囊或肩部截断 | 组合面不经过现有单圆 clip，由融合场输出 alpha。区分视觉外扩边与 blur 采样 margin；后者不能解除 shader 的矩形硬裁。宿主尺寸须覆盖实际 press/stretch/translation 和平滑肩部。 |
| 非等比缩放后几何与图标不一致 | 共享实际动画变换，并在场计算中按对应变换处理；不能仅用变化后的 bounds 和单个 radius 冒充准确椭圆距离场。 |
| 低 API 或 shader 失败后出现整块矩形背景 | `LivePageGlassMaterial.kt:89` 的无光学路径依赖外层 clip，fallback 又使用 `drawRoundRect`。首批建议 API30、API31–32、reduceTransparency 或组合 shader 失败时恢复原双圆材质，关闭融合，不能直接让这些分支绘制组合宿主矩形。 |
| 动态几何未刷新或缓存无界 | `LiveGlassEffectKey` 必须比较形状、变换、blend、press/touch 的值快照，不能只比较可变 holder 引用。缓存仅保留当前 key/effect，不按帧保存历史。 |
| 对“零每帧分配”作错误承诺 | `LivePageGlassMaterial.kt:66` 当前动态参数改变会重建 RenderEffect 链。应承诺复用 shader/layer、无每帧 bitmap/路径累积，再实测 effect 分配与 GPU 内存；不能把 shader 复用等同于整条管线无分配。 |
| 两按钮光学串扰 | 当前 lens 只有一组 touch/interaction。建议固定两组局部 press/touch，以各按钮尺度限定影响半径；直接取最大 press 会让另一按钮也整体增强折射。 |
| 光学尺度因组合宿主变大而改变 | 现有 `glassLensGeometry` 按宿主短边推导 bevel/refraction，shader 的触点影响范围也参考宿主尺寸。组合外扩后不能无意扩大两按钮的光学尺度，应以按钮基础尺度明确约束。 |
| 临界细颈、尖点或接缝 | 静止 gap 8dp 的 k=16dp 是报告对称几何推导的零厚度临界，不能作为稳定连接值；参数须考虑实际变形和 density。smooth-min 不保证所有帧的法线或折射都稳定。 |
| 菜单残影或重复材质 | 显式处理 launcher alpha、融合颈部退场和 Popup anchor 接管；仅关闭两个 launcher 的原材质不足以完成交接。 |
| 减少动态效果不一致 | 沿用现有 GlassMotion 与 reduceMotion 语义，禁用相应动态变形/光学变化；仍需定义稳定的静态展示，不能让可访问性切换留下过渡中间态。 |

shader 的覆盖率、法线、折射和 rim 必须使用同一个融合场。菜单 handoff、组合材质失败和背景不可用时，恢复原按钮绘制必须与组合关闭协调，避免透明 launcher 或双层叠画。

## 内存与资源约束

继续使用现有页面 capture，只新增一个有界局部 copy layer、一个组合 RuntimeShader 和当前 effect 缓存。按扩边后的真实像素尺寸计算 RGBA 面，并计入 blur 中间面和 GPU driver 额外分配，不能只看 JVM heap。

不新增全屏 capture，不在每帧 decode 截图、创建 bitmap 或编译 shader，不启动无条件持续 invalidate 循环。动画结束后停止无意义更新；组件离开 composition 时释放其拥有的局部资源，不释放仍由其他消费者共享的页面源。

路径如用于有界 fallback 或阴影，应复用并 reset，不逐帧累加。初始几何无效、背景不可用或组合能力失败时，应回退原按钮材质。

## 下一版本验收要求

- 同一背景下检查两圆肩部、颈部、亮边与折射连续性；用可辨文字或网格区分真实采样与填色。
- 检查 press、拖动、松手回弹的图标/轮廓同源；覆盖 slop、outside、CANCEL、多指取消和快速重按。
- 检查两个原圆的命中、邻近按钮独立性、颈部不可点击，以及聊天滚动不受组合绘制层影响。
- 覆盖菜单打开、关闭、取消、快速重开及另一菜单互斥，验证 launcher/Popup anchor 没有残影、闪空或重复材质。
- 覆盖 density、融合临界前后、reduceMotion、reduceTransparency、背景失效和 shader 失败；分别记录 API30、API31–32、API33+ 的实际行为。
- 记录真实设备画面、帧时间、GPU/进程内存与资源回收；数学/源码检查不替代视觉和交互验收。

## 执行记录

此前评审仅进行本地文件读取和 API 对照；本文保存仅新增此文档。没有生产编辑，没有运行 JVM/Kotlin/Gradle、ADB、联网请求或新的研究脚本。未实施上述建议，未运行相关 shader、编译或设备用例。

用户已授权持续迭代；后续实施待主 Agent 冻结 0.1.19 设备验收与交付证据后，由主 Agent 排期并分配下一版本实施范围，不并入当前包，无需再次请求用户授权。实施分配属于主 Agent 内部协调，不构成新的用户审批门槛。若复制固定上游算法，沿用 `REPORT.md` 规定的 MIT 版权、许可证和 NOTICE 记录要求。
