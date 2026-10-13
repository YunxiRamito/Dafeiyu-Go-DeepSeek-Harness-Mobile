# QWEA0 液态玻璃源码审计

日期：2026-10-12，Asia/Shanghai。范围：只读生产源码，通过 `http://127.0.0.1:7890` 获取固定提交的文本证据，只在本研究目录写文件。未启动 JVM/Kotlin/Gradle、ADB、模型请求或外部仓库脚本；生产冻结保持。

## 2026-10-13 WebView 绘制修复接续

用户反馈本机 DSH 正式主页在 PKB110 闪退、QA 加载后白屏。只读 ADB 证据确认手机 code26 与 code27 均在 Chromium Vulkan `drawVk → WebViewFunctor → renderLayersImpl` 崩溃；准确 CHECK/FATAL 未取得，不能把上游某个断言分支当作已知根因。QA 的真实窗口中央区域为纯白，旧 JS/DOM 与隔离生命周期专项没有覆盖正式主页的完整玻璃采样，不能证明实际绘制正常。

固定上游 README 379–394 与 `LiquidGlassView.kt` 779 附近明确建议为被玻璃捕获的 WebView 设置独立硬件层。生产 `DshWebViewFactory` 已在 owner 回调、配置与导航前加入 `setLayerType(View.LAYER_TYPE_HARDWARE, null)`，让玻璃重放其纹理，保留原 GPU、玻璃和认证逻辑；未复制上游模块。该最小候选须经完整 `DafeiyuApp` 的实际 Window PixelCopy、动态内容变化和正式 RESUMED 主入口验证，模拟器通过也不代替 WebView154 真机稳定性。当前源码实现与构建、设备、用户验收状态分别维护于 HANDOVER。

## 结论与证据边界

已实际下载并精读 QWEA0 的固定提交源码。上游双形状融合是单一 shader 内的平滑并集：覆盖率、法线、折射和高光共同使用融合距离场。项目现有 `LiquidGlassBridge` 是两块独立玻璃之间的 gap-only 贝塞尔填色片，没有共同距离场或背景采样，不能算完整玻璃表面融合。建议复用上游的融合机制，适配现有 Compose 内容捕获层；不要直接导入整个 View 模块。

**当前最新 HEAD 的再次确认 blocked。** 首轮工具响应曾成功返回 HEAD JSON，内容指向项目原有锁定提交；保留的 `api/head.json` 是该较早响应，不是最终失败请求的新响应。随后真实复核发生 TLS 失败及 API 403 限流，代理 `git ls-remote` 也因 TLS EOF 失败。因此只能确定本次成功获取的固定提交实现，不能写“当前最新 HEAD 已重新确认”，也不能断言旧 reference 与此刻上游没有差异。停止扩大联网研究。

| 证据 | 实际结果与解释 |
| --- | --- |
| 仓库 | https://github.com/QWEA0/Liquid-Glass-Android |
| 固定审计提交 | `16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d`；与本项目原有 reference 完全相同 |
| 固定提交 URL | https://github.com/QWEA0/Liquid-Glass-Android/commit/16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d |
| 提交日期 | JSON author/committer 均为 `2026-09-29T10:25:08Z`，北京时间 2026-09-29 18:25:08 |
| 提交说明 | README / llms.txt 补充 WebView 硬件层说明；backdropSource 注释同步 |
| 源码树 | `708d7ba066714d9399ab10d79ad1f639486255cd`，保存的 tree 未截断；真实 HTTP 200 的响应日期 2026-10-12 00:11:40 +08:00 |
| 固定源码下载 | 8 个文本文件，逐文件保留代理 HTTP 200 header，响应日期约 2026-10-12 00:11:57 至 00:12:03 +08:00；详见 `snapshot-manifest.json` |
| 较早 HEAD body | SHA256 `05a98b22d07b327f4f7a2e40f5f7ea458162390a9e6f9ebf36de65eeb4e54076`；原成功 HEAD header 已被后续失败请求 header 覆盖，不伪造恢复 |
| HEAD 复核失败 | 00:13:21 至 00:13:29 请求 curl 35 / HTTP 000；00:14:49 请求 HTTP 403，`X-RateLimit-Remaining: 0` |
| HTTPS git fallback | `git -c http.proxy=http://127.0.0.1:7890 ls-remote https://github.com/QWEA0/Liquid-Glass-Android.git HEAD`；exit 128，`unexpected eof while reading` |
| 用户截图 | `user-bridge-reference.png`；SHA256 `a64dba1a827a25a97a7bdeae2ffd8d4dc8d1551247aa13faf7e0fabf4e4a7c75` |

`download-manifest.json` 当前是两条失败 HEAD 尝试记录，不是成功源码列表。首条 TLS 失败的 bytes/hash 来自仍存的较早 body，不代表失败请求收到了 9026 bytes。`api/head.json.headers.txt` 与 `.partial` 是后一次 403 的 header/body；不要与保留的成功 body 配对。成功固定源码、URL、大小、SHA256、原 header 状态和响应日期统一见 `snapshot-manifest.json`。

## 实际文件与定位

完整源码树里没有 `LiquidGlassShader.kt` 或 `LiquidGlassTouch.kt`。不能按用户给出的概念名称虚构类或实现。对应 shader 在 `GlassLensRenderer.kt`，touch/press 在 `LiquidGlassView.kt`，`LiquidGlassButton.kt` 继承后者。

下表 URL 均固定到审计提交，`source/` 中保留原始文本及 headers：

| 文件 / 行 | 可验证行为 | 固定 URL |
| --- | --- | --- |
| `LiquidGlassView.kt:1082` / `1095` | primary / secondary RectF、圆角和 smoothing 入口 | [View](https://github.com/QWEA0/Liquid-Glass-Android/blob/16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d/liquidglass/src/main/java/com/example/liquidglass/LiquidGlassView.kt#L1095) |
| `GlassLensRenderer.kt:84` / `96` / `102` | 圆角 SDF、sminPoly、lensSDF | [Shader](https://github.com/QWEA0/Liquid-Glass-Android/blob/16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d/liquidglass/src/main/java/com/example/liquidglass/GlassLensRenderer.kt#L96) |
| `GlassLensRenderer.kt:115` / `130` / `164` | 矩形硬裁、融合场梯度、press 和局部凸起 | [光学](https://github.com/QWEA0/Liquid-Glass-Android/blob/16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d/liquidglass/src/main/java/com/example/liquidglass/GlassLensRenderer.kt#L115) |
| `LiquidGlassView.kt:2104` / `2150` / `2197` | DOWN/MOVE/UP/CANCEL、press 动画、方向伸缩 | [Touch](https://github.com/QWEA0/Liquid-Glass-Android/blob/16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d/liquidglass/src/main/java/com/example/liquidglass/LiquidGlassView.kt#L2104) |
| `GlassLensRenderer.kt:355` / `431` / `503` | 屏幕坐标背景录制，blur 内层、lens 外层 | [Capture](https://github.com/QWEA0/Liquid-Glass-Android/blob/16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d/liquidglass/src/main/java/com/example/liquidglass/GlassLensRenderer.kt#L431) |
| `BackdropCapture.kt:1` / `64` | 防 RenderNode 重入、引用循环及邻居重复捕获 | [防循环](https://github.com/QWEA0/Liquid-Glass-Android/blob/16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d/liquidglass/src/main/java/com/example/liquidglass/BackdropCapture.kt#L64) |
| `README.md:69`；模块 build `:10` | Compose-first 建议；SDK/NDK/CMake/ABI | [README](https://github.com/QWEA0/Liquid-Glass-Android/blob/16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d/README.md#L69)、[build](https://github.com/QWEA0/Liquid-Glass-Android/blob/16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d/liquidglass/build.gradle.kts#L10) |

## Shape smooth union 与尖颈

`setSecondaryShape` 复制 RectF、保存圆角，将 smoothing 限到 `0..200 px`，不自动寻找或融合另一个 View。主、副形状都在一个宿主的局部坐标系内定义；`tryDrawLensGlass` 将各自中心、半宽、半高和圆角传入同一个 renderer。两圆必须通过正方形 rect 和半径设置表达；输出仍在宿主矩形内硬裁。

`lensSDF` 先计算主形状距离 `a`，副形状存在时计算 `b`。`blendK > 0.5 px` 使用以下多项式；更小时直接 `min(a,b)`，避免除零：

```text
h = clamp(0.5 + 0.5*(b-a)/k, 0, 1)
d = (1-h)*b + h*a - k*h*(1-h)
```

融合改变两形状邻近区域的整个等值轮廓，不是在空隙里补一条线。该 `d` 同时用于 alpha、1px 中心差分法线、边缘 slope、RGB 采样位移和方向高光，因此正常融合轮廓能够共享光学响应。shader 外仍不能叠回两个独立圆边，否则重复 alpha、高光和裁剪会重新造成接缝。

它是平滑场，但融合后一般不再是单位梯度的精确距离场。连接/分离临界处的等值轮廓可能不规则：对两相同圆、沿中心线表面 gap 为 `g`，中点 `a=b=g/2`，所以 `d=g/2-k/4`。`k=2g` 恰到零且对称中点梯度为零，细颈/尖点与法线 fallback 风险仍在；`k>2g` 才有非零厚度的连通颈部。这个推导只适用于上述对称几何，不能当任意圆角矩形的通用阈值。

已用本目录自写 PowerShell 做标量核算，未运行 shader：48dp 两圆、gap 8dp，k 为 8/16/20/24dp 时中点场为 2/0/-1/-2dp。真实 uniform 要换为像素；上游默认 48px 不能不经 density 换算照搬为 48dp。动画跨过临界时需考虑覆盖率渐入/渐出和可见颈部厚度，不能承诺 smooth-min 在所有帧都无尖点。

## 现有 gap-only 桥的差异

本地 `LiquidGlassBridge.kt:29`、`:71`、`:80` 用固定 gap/span、高度和 strength 生成两条 cubic path，再填垂直 tint gradient，描独立亮边。没有背景 shader、第二形状或法线输入。`MainActivity.kt:499` 把它限制在 104×48dp 双按钮容器中间 8×48dp；两个 48dp 按钮仍各自绘制、clip 和形变。侧栏 `WorkspaceSidebar.kt:480` 也使用该组件，但它的行/归档连接属于另一种几何，不能统一硬套两圆参数。

| 维度 | 上游同一融合面 | 本地当前桥 |
| --- | --- | --- |
| 几何范围 | 两个完整形状及其肩部共同改变 | 仅 8dp gap；不改变按钮圆弧 |
| 材料 | 同一背景输入、blur、SDF、法线、折射、高光 | 两按钮材料独立；桥是填色和描线 |
| 运动 | 宿主局部 shape 参数必须一致 | 按钮各自 scale/translation；桥只收 strength，不收实际圆心/形变 |
| 触摸 | shader 几何与点击逻辑仍是不同职责 | 桥不接收输入；现有按钮保留各自命中 |
| 取舍 | 需要双按钮局部材质层和独立前景语义 | 实现小，但不能宣称 smooth union |

具体几何：strength=1、height=48dp 时桥端半高 21.12dp、腰半高 10.1376dp。静止左圆中心 `(24,24)`、半径 24，桥从 x=48 开始；桥顶 y=2.88 对应圆轮廓 x≈35.3994，缺少约 12.6006dp 的圆弧肩部融合区。它不能靠只改 gap 内控制点保证圆弧切线衔接。独立按钮按压放大/拖移会改变交点，但桥没有这组输入，不能保证全动画区间对齐。

用户新截图已实际查看：黑色两圆、居中亮色窄连接，顶部细尖突起与底部尖颈、亮边接缝可见。截图没有纹理足以单独证明折射坐标翻折，也没有本审计提供的设备/version 元数据，不把它冒充本轮 APK QA。较早 `docs/reference/ui-2026-10-11/06-liquid-bridge-reference.png` 是白胶囊/黄圆参考，不能替代新截图的生产判断。

## Press 与背景捕获

上游 press 分两层：`pressDepth` 180ms 按下、320ms 松开，用 DecelerateInterpolator 驱动光学 uniform；`basePressScale` 的 200ms 动画与方向 `stretchScaleX/Y` 相乘，走 View transform，避免 onDraw 内缩放被裁。默认 pressScale=0.95 可调。shader 中 press 增加折射到 `1+0.6*press` 倍，并按 touchPos 施加高斯局部放大；这不是把 SDF 直接变成任意弹性曲面。UP/CANCEL 都复位，detach 取消动画并释放 renderer。

本地已有 `GlassButtonDeformation` 的 Compose 手势仲裁、圆形命中、slop、多指取消、finally 复位、径向位移限制、graphicsLayer 伸缩以及共享贝塞尔时序；`GlassSurface.kt:182` 已传 opticalPress 给单形状 shader。应该保留这些语义，不照抄上游矩形 UP 判点击或让融合颈部增加可点区域。共享双形状光学还必须明确两个触点/press 的组合策略；上游一个宿主只提供一组 touch/press，不天然解决两个独立按钮同时按压。

背景方面，上游默认采直接父容器，也支持显式 backdropSource。先算玻璃与 source 屏幕位置差，再用 RenderNode 录真实绘制、blur→lens 合成。source 为自身/后代被拒绝；任意祖先通过 BackdropCapture 逐层公开 draw、暂时跳过玻璃分支，避免 display-list 重入及引用成环。嵌套捕获会隐藏 host 的其他直接玻璃子级，限制互相捕获的放大效应。其注释明确跨层级分支补画顺序、缩放/旋转/透明度语义存在取舍，不是任意树万能捕获。

滚动 source 注册重绘监听；其他动态内容需 invalidate 或启用 enableDynamicBackground，后者有持续 invalidate 循环。当前提交补充 WebView 必须开硬件层来避免 functor 在窗口/离屏不同变换下反复 invalidate；代价是额外 WebView 尺寸纹理。这是上游说明，本次未做设备验证，不自动用于项目未来 DSH runtime。

本地 `ScrollingGlassHeader.kt:36` / `:61` 已有 PageBackdropCapture、GraphicsLayer、origin/revision，内容与玻璃平级，避开自身回录；`LivePageGlassMaterial.kt:43` 复用 shader/blur，使用同一内容层、采样交集与局部 copy。`GlassSurface.kt:208` 在有 page capture 时优先这条路径。可复用这套分层给双按钮一次局部采样，不引入上游临时隐藏 Compose 根树的策略。

折射风险独立于轮廓融合：过大的位移、渐变近零法线、press 增幅、采样钳制及矩形 clip 都可能产生扭曲或接缝。上游 `LiquidGlassView.kt:1468` 有可选 refractionNoFold：逆幂参数 p 时 base cap=`bevel*(1-5^-p)/(4p)`，平方剖面为 bevel/2；默认 false。shader 后续仍乘 press 并加局部凸起，因此该基础 cap 不能证明按压和融合处整体无翻折。本地没有该开关；若未来加限制，需要约束实际总位移，而不是把 smooth union 当作抗扭曲保证。

## Compose 适配与最小范围建议

README 固定版本明确建议 Compose-first 使用 Compose 方案，不要将整个 LiquidGlassView 包 AndroidView。这里不再扩大候选仓库研究，也不导入另一个库。原因落实到源码：上游 FrameLayout、子 TextView、MotionEvent、View ancestor 捕获和 Compose 现有 GraphicsLayer/语义所有权不同；整模块 compileSdk36、NDK27.0.12077973、CMake3.22.1、minSdk24、ABI arm64-v8a/armeabi-v7a，项目为 compileSdk35/minSdk30/JVM17，直接引入增加工具链/native 依赖，也不自动获得 Compose 双按钮融合。

冻结后如获授权，第一批仅针对右上双圆：保留两个按钮与点击语义，增加局部双形状材质模式；完整两圆+颈部一次背景采样、一次融合光学、一次 tint/rim，前景图标独立绘制。动态 shape 须来自两按钮相同坐标空间的实际变换；单独按钮材料在该模式下必须让位，不能下面多画融合面、上面仍叠两个镜面边。宿主扩边覆盖按压/平滑肩部，不能继续把输出限制为 8dp gap。

建议文件边界为 `LiquidGlassBridge.kt`（双形状局部材质/几何）、`GlassSurface.kt`（LensShader 可选双形状参数与内部材质绘制开关）、`LivePageGlassMaterial.kt`（共享局部采样接入），`MainActivity.kt` 仅右上组合 wiring。是否增加一个专用双按钮材质文件由实施时决定，避免先造通用 N-shape 引擎。`WorkspaceSidebar.kt` 连接暂保留原行为，单独评估，避免本批扩大交互改动。这里是审计建议，尚未生产编辑或实施授权。

内存/性能必须局部有界：复用一个局部 GraphicsLayer 和 RuntimeShader，不为每帧 decode 截图/创建 bitmap/编译 shader，不为每组按钮新增全屏捕获，不启用无条件帧循环。预算按真实像素的 `(unionWidth+2margin)*(unionHeight+2margin)*4` 估算每个 RGBA 面，还需计入 blur 中间面与 GPU driver 额外分配，不能只看 Java heap。复用页面源的引用；动画结束或 detach 释放组合消费者，低 API/失败优先普通圆材质或有界实色降级，不宣称完整光学。

有意义的后续验收：等半径/不等半径与 k 临界前后、density 换算、变形后共同坐标、颈部非点击、slop/CANCEL/多指/快速重按、减少透明度/动态效果；用可辨背景文字/网格检查融合肩部的法线与采样连续性、亮边不叠加、裁剪无尖角、press 不大范围扭曲；API30/31-32/33+分别记录降级。标量测试不能替代真机截图、帧时间和 GPU/内存验收。本次仅完成来源/几何核算，没有运行上述生产测试。

## MIT 与交付

已读取真实 [LICENSE](https://github.com/QWEA0/Liquid-Glass-Android/blob/16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d/LICENSE)：MIT，Copyright (c) 2025-2026 pandadog。保留完整版权与许可通知、免责段；不把 README 的“只保留版权”简写当成删除许可正文的依据。

下载的 LICENSE 与 `third_party/liquid-glass/LICENSE`、`app/src/main/assets/third-party/liquid-glass/LICENSE` SHA256 完全一致：
`b7b41578ce6b8d42fd7ebbb89d7c85d0e7085cdeabf8920d8e1b68b77d60f682`。
如后续复制 smin / secondary shape 或 press 算法，需同步适配文件头和 NOTICE 的范围；本次研究快照保留原文件与 LICENSE，未导入 build/sourceSet 或运行外部代码。

离线完整性检查已完成，见 `validation.json`：8 个源码快照 SHA256 与对应 HTTP 200 header、两个自写 PowerShell 脚本的语法解析、上述标量计算、三份许可证一致性均通过。没有运行 JVM、shader 或生产测试，也没有在验证阶段再联网。

交付物：`REPORT.md`；`source/` 8 个固定提交文本快照及 headers；`api/head.json`、`api/tree.json`；`snapshot-manifest.json`（成功固定源码清单）；`download-manifest.json`、403 header/body、`git-ls-remote-fallback.log`（blocked）；`geometry-evidence.json`、`validation.json`；本地研究脚本 `Fetch-Evidence.ps1` / `Inventory-Evidence.ps1`；主线程提供的 `user-bridge-reference.png`。请求已停止，不因 HEAD blocked 循环重试。
