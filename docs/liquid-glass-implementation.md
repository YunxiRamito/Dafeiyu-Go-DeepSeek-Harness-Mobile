# Liquid Glass Android 适配记录

当前为 0.1.5 前端加入小型原生玻璃绘制组件，保留 Kotlin / Jetpack Compose 和现有 SDK 35 工具链。采用共享背景采样、Android RenderEffect 模糊与 API 33 的 AGSL 边缘折射。实际设备上的清晰度、性能和版本降级效果仍需验证；源码接入和 APK 构建不能代替设备视觉验收。

## 0.1.16 可读性修订

2026-10-11，覆盖下方 0.1.15 的 tint 分布、模糊参数和扫码禁用折射记录。仍使用固定上游提交；参考 Regular/Clear 的覆盖色与媒体背景压暗，保留现有窄边折射/色散/高光，移除中心 tint 的四分之一权重，改为均匀低比例覆盖色，不添加内发光。

Standard/FloatingMenu：3.5dp、tint 上限 16%；Button：2dp、浅色 12%/深色 10%；SidebarPanel：3dp、浅色 12%/深色 14%；MediaButton：2dp、tint 上限 14%、背景压暗 16%。调用者传入更低的 alpha 时遵循较低值，避免强制把所有控件变成白色。工作目录按主题提供浅/深色覆盖。减少透明度仍降级为实色。

扫码页使用独立相机背景，并统一到原生 AGSL 折射管线；MediaButton 的黑色压暗与 tint 分离，光学和降级路径各只施加一次。弹窗加号/X 的背景更新由 source/capture/modal 变化通知，跨窗口消费者更新，同根 revision 不形成反馈重绘循环。最终真机证据与未验收边界见 `frontend-0.1.16.md`。

## 0.1.15 材质修订（历史）

2026-10-11 根据用户最新图片修订。下述规则覆盖本文历史版本中的二次坡度、宽内发光、按钮整面增亮和高比例覆盖色。上游仍固定 `16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d`，已重新读取该提交的 GlassLensRenderer 和材料参数。

- 边缘坡度采用上游逆幂衰减，`falloff = 2`，向内采样在窄边缘产生背景压缩和镜像；按控件短边缩放折射位移，避免小按钮成为满面扭曲的透镜。
- RGB 通道沿同一法线分别采样，色散为 `0.10`。所有采样坐标钳制在控件输出范围与真实背景覆盖范围的交集，防止透明黑边和错误窗口像素。
- 高光只保留相反方向的两束窄反射瓣。移除 `glow` 与 `bodyLight`，不以宽内发光、整面渐变或发白底色模拟玻璃厚度。
- 工作目录 tint 上限为 6%；小按钮 tint 为 6%（浅色）/8%（深色）。AGSL tint 按 `0.25 + 0.75 * slope` 分配，中心仅保留四分之一权重，避免整面白雾。小按钮可略亮于周围，不添加额外按钮底色。
- 按钮 blur 为 1.5dp；普通玻璃、弹出菜单为 2.5dp，工作目录为 2dp。扫描专用模糊仍为 3.5dp，不套用聊天页面的透镜效果。
- 工作目录从 `LocalFloatingGlassBackdrop` 采样实际主页，与外面的模态 dim/blur 一致；设置返回按钮从独立录制的页面层采样。前景文字和控件不进入其自身采样源，避免递归。

当前实际对话背景没有参考图中的彩色桌面图标，折射边缘应显示页面本身的文字、颜色和明暗，不能为了制造色边加入无关装饰。API 30/31/32 的降级范围仍按下文执行；新版真机证据以 `frontend-0.1.15.md` 为准。

## 设计依据

参考 [Apple Liquid Glass 技术概览](https://developer.apple.com/documentation/technologyoverviews/liquid-glass)，将层级、和谐、一致性、内容优先，以及控制与导航中的克制用色作为布局依据。正文和操作标签保持 Compose 绘制，玻璃只作为控制表面的材料。浅色采用接近白色的底色，深色使用主题背景；蓝灰细节只存在于共享背景的边缘。

这是 Android 原生实现，未使用 Apple 代码或视觉资产，也不等同于 Apple 平台的系统 Liquid Glass。上游算法是一种视觉近似，本轮没有物理光学精度或完整上游特性的承诺。

## 上游基线与许可

- 仓库：[QWEA0/Liquid-Glass-Android](https://github.com/QWEA0/Liquid-Glass-Android)。
- 固定提交：`16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d`。
- 许可：[MIT 原文](../third_party/liquid-glass/LICENSE)，版权 `(c) 2025-2026 pandadog`；[NOTICE](../third_party/liquid-glass/NOTICE.md) 记录适配范围，组件文件头也保留归属。相同完整许可位于 `app/src/main/assets/third-party/liquid-glass/LICENSE`，随 APK 分发。
- [GlassLensRenderer.kt](https://github.com/QWEA0/Liquid-Glass-Android/blob/16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d/liquidglass/src/main/java/com/example/liquidglass/GlassLensRenderer.kt#L54) 的 `LENS_AGSL` 是算法来源。圆角矩形距离场、数值法线、二次边缘坡度、向内折射、RGB 色散与方向高光被简化适配；[输入模糊与 AGSL 链](https://github.com/QWEA0/Liquid-Glass-Android/blob/16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d/liquidglass/src/main/java/com/example/liquidglass/GlassLensRenderer.kt#L552) 是绘制链的参考。

上游 [README](https://github.com/QWEA0/Liquid-Glass-Android/blob/16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d/README.md#L69) 明确不建议 Compose 优先项目把整个 `LiquidGlassView` 包进 `AndroidView`。其 [Gradle 模块](https://github.com/QWEA0/Liquid-Glass-Android/blob/16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d/liquidglass/build.gradle.kts#L10) 需要 SDK 36、NDK 27.0.12077973、CMake 3.22.1 和原生管线。因此本工程只适配小段算法，未导入上游模块、控件或原生库，未升级工具链。

## 绘制结构

源码位于 `app/src/main/java/com/dafeiyu/mobile/ui/components/GlassSurface.kt`。

1. `rememberGlassBackdropState()` 创建共享背景状态，`GlassBackdrop` 绘制独立的叶子 View 纹理。该纹理不包含聊天消息、输入文字或前景控件。
2. `LocalGlassBackdrop` 将同一纹理提供给普通页面与弹出菜单。每个 `GlassSurface` 通过 `getLocationOnScreen` 求与源纹理的屏幕坐标差，在自己的位置采样，弹窗也使用这一坐标空间。
3. 原生 `GlassDrawingView` 只绘制背景材料；实际按钮、文字和输入控件作为同一 `Box` 的前景由 Compose 绘制。绘制 View 不响应点击、不能获得焦点并从辅助功能树排除。
4. API 31 以上先将独立背景录入 `RenderNode`，外扩模糊边界，再应用 `RenderEffect`。API 33 以上加 AGSL 圆角距离场和边缘折射链。
5. 背景主题和尺寸改变时通过弱引用消费者列表触发重绘。没有常驻帧循环、传感器输入或持续动画。

背景捕获始终指向独立叶子 View，不捕获 Compose 根节点、窗口或包含玻璃自身的祖先，避免祖先 RenderNode 回录造成递归。其限制是 **聊天文字、消息气泡和实际页面内容不会被玻璃折射**。可以看到共享背景纹理在控制表面的采样变化，不能据此宣称已实现任意内容的实时背景折射。后续若扩大捕获范围，需要单独设计内容和材料的绘制分层并完成性能验证。

## 版本降级与偏好

| 条件 | 实际效果 |
| --- | --- |
| Android 13 / API 33 及以上且硬件绘制正常 | 真实背景纹理采样、RenderEffect 模糊、AGSL 边缘折射、低幅 RGB 色散、静态方向高光、主题色覆盖 |
| Android 12 / API 31-32 且硬件绘制正常 | 真实 RenderEffect 背景模糊与主题色覆盖；没有 AGSL 光学折射 |
| Android 11 / API 30，或硬件管线失败 | 复用低分辨率 Bitmap 采样背景纹理与主题色覆盖；没有真实背景模糊或光学折射 |
| 缺少有效背景，或减少透明度启用 | 不透明主题表面 |
| 减少动态效果启用 | 关闭折射位移与 RGB 色散，保留模糊和静态高光；按钮由独立按压/方向伸缩组件驱动，当前减少动态时关闭 |

`LocalReduceGlassTransparency` 和 `LocalReduceGlassMotion` 为应用偏好提供入口，最终设置页面和持久化方式由前端集成负责。AGSL 初始化失败会降为硬件模糊，RenderEffect 绘制失败会降为 Bitmap。错误降级并不代表对应设备已完成兼容性验收。

## 验证边界

本组件提交时未运行独立构建或启动模拟器，由主任务统一构建、Lint 和打包。此前 API 35 模拟器未就绪，本轮不重启模拟器，已在用户授权SDK37手机取得和检查截图；早期模拟器未就绪。最终 APK、版本与构建状态以当前交接和构建记录为准。

设备验收需覆盖 API 30、31/32、33 及以上，浅色和深色、弹出菜单、软键盘、旋转、大字体、减少透明度与减少动态效果。尤其检查 AGSL 实际成功启用、屏幕坐标对齐、文本对比度、菜单覆盖关系，以及低端设备的 GPU 时间与内存占用。


## 0.1.5按钮交互更新

用户纠正拖拽指按钮方向形变，不是面板整体移动。已撤下使用中的抓手拖动，GlassButtonDeformation按指定仓库触摸/press/elastic逻辑接入局部缩放、伸缩、有界位移和贝塞尔回弹；触点及180/320ms按压渐变传入AGSL凸起。设置采用MERGED glass group，侧栏与右上动作有可见进出动画。真机日志证明hardware lens=true，截图检查证明按钮按住/拖动后变形并在松手恢复。背景仍不包含聊天正文。


## 0.1.8 实际页面采样与共享交互

普通GlassSurface仍采独立共享背景，不能声称折射聊天正文。设置顶部和返回按钮现在是单独分层：`capturePageBackdrop`只录制页面内容的Compose GraphicsLayer；平级 `ScrollingGlassHeader` 用两级 RenderEffect 模糊与渐变DST_IN mask形成软边；`LivePageGlassMaterial`采同一内容层在按钮屏幕位置的图像，API33再使用AGSL透镜。因此顶栏与返回按钮能显示滚动控件颜色，前景图标和唯一移动标题保持清晰，没有把材料自身录入源层。

模糊effect和RuntimeShader按组件remember复用，不在每个尺寸动画帧编译shader。普通原生材质首次尺寸变化/附着补绘。按钮光学press与几何形变统一 `GlassMotion` 贝塞尔时序；侧栏renderer和内容保留，收起0×0不放置。

真机SDK37已看到返回按钮区域透出后方蓝色演示按钮以及渐变模糊；该采样结构尚未做低端机GPU profiling或API30/31兼容验收。减少透明度退回实色；低API回退不宣称原生Liquid Glass。
