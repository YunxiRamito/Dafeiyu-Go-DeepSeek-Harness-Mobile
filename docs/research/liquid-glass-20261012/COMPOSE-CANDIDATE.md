# Compose 候选有界比较：Kyant0/AndroidLiquidGlass

研究日期：2026-10-12，Asia/Shanghai。独立于已冻结的 QWEA0 `REPORT.md`；本次只新增本报告和 `kyant0/`，不改写原报告、源码快照及原 manifest。未修改 production，未启动 JVM/Kotlin/Gradle/ADB，未安装依赖或运行第三方脚本。

## 决策结论

Kyant0 是与本项目 Compose 体系匹配的材质/交互参考；本次固定提交源码不足以证明它提供所需的双圆 smooth-union，不能把“Compose-first”理解为“双形状融合现成可用”。已读 `Lens.kt` 的折射入口只支持圆角矩形类形状，其他形状会抛异常；已读 `LiquidButton` 是单个 Capsule 的材质与 press 示例。

建议维持现有 Compose 背景捕获、手势与降级体系，当前包冻结记录完成后由主 Agent 排期/分派，优先做局部双圆共享材质，融合算法依据已冻结的 QWEA0 smooth-min 实现；Kyant0 借鉴 press 的受限位移、方向缩放及 Compose 材质接口划分。本次仅完成文档研究，未引入 Kyant0 依赖、复制其源码或修改生产代码。

## 固定来源与请求边界

- 仓库：[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)。
- 固定提交：[65ab177e90e5c1d8c62e70cf7755841982da65f6](https://github.com/Kyant0/AndroidLiquidGlass/commit/65ab177e90e5c1d8c62e70cf7755841982da65f6)。
- 本次 HEAD 确认：2026-10-12 00:37:36 +08:00，唯一备选 `git -c http.proxy=http://127.0.0.1:7890 ls-remote https://github.com/Kyant0/AndroidLiquidGlass.git HEAD` 成功、exit 0，输出上述完整 SHA。日志为 `kyant0/api/head-git-fallback.log`。这证明该时刻远端 HEAD 的值，不证明其后仍为 HEAD。
- 提交的作者/提交者日期：**未取得**。HEAD API 和固定提交 recursive-tree API 均 HTTP 403，正文为 API rate limit。请求时间、HTTP Date 和许可证年份均不能代替提交日期。
- 所有网络经 `http://127.0.0.1:7890`。每个 URL 仅一次；一次 git 备选已用完，未循环重试。总计 11 次操作：2 次 API、1 次 git、8 次固定 SHA raw 文件请求；7 个文件 HTTP 200，1 个推定路径 HTTP 404。未下载整仓压缩包/APK/图片。
- HEAD 备选成功后即固定该 SHA，无需 release 替代。版本目录和构建脚本是该提交的声明，不等于 Maven 已发布、依赖可解析或本机能构建。
- 完整逐请求 URL、时间、状态、字节数、SHA256 与 header SHA256 见 `kyant0/manifest.json`；失败正文保留为 `.partial`，不是成功源码。

原 QWEA0 固定提交仍为 `16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d`；本次 Kyant0 HEAD 成功不能补证 QWEA0 的最新 HEAD。QWEA0 最后失败请求与旧缓存的区分继续以原报告为准。

## 文件与哈希

下表 URL 全部固定为上述 SHA。快照在 `kyant0/source/` 下保持仓库相对路径；该目录内 Kotlin/Gradle 文件仅作为文本阅读。

| 文件 / 固定来源 | 结果与 SHA256 |
| --- | --- |
| [README.md](https://raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/65ab177e90e5c1d8c62e70cf7755841982da65f6/README.md) | 200；`8aecd8bde9985e31c127ea7b3d76bdb96315ce4f6dd5e9e205be4d486d72673c` |
| [LICENSE](https://raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/65ab177e90e5c1d8c62e70cf7755841982da65f6/LICENSE) | 200；`4b9f7e7c2821a5a1822a6cf1e4a34e877bba48a99b9bba1f03e5e2eccf6dec56` |
| [gradle/libs.versions.toml](https://raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/65ab177e90e5c1d8c62e70cf7755841982da65f6/gradle/libs.versions.toml) | 200；`eaabed5ad27f76ed44afb5222cd3b6c2312793c3819ca7263c44cc898a08b94d` |
| [backdrop/build.gradle.kts](https://raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/build.gradle.kts) | 200；`3e5e6d67bf55b94238219a5f807f4395112299eec04786360621d5ad66208053` |
| [LiquidButton.kt](https://raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidButton.kt) | 200；`7b95a0c1b33ff984f59b1e7c7a910b7038a0a8ad4377a2fdbe77400711a8244b` |
| [InteractiveHighlight.kt](https://raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/InteractiveHighlight.kt) | 200；`d0af46b531eba19bd0fed886892f02193f05d5a61db0925da016ff6059984f83` |
| [DrawBackdrop.kt（推定路径）](https://raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/DrawBackdrop.kt) | 404；失败正文 SHA `d5558cd419c8d46bdc958064cb97f963d1ea793866414c025906ec15033512ed`，不是渲染源码 |
| [Lens.kt](https://raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/effects/Lens.kt) | 200；`28e2a61c6f994d6ef793e4bea5e97b95d712a6d8536e35bd98634ad9999027dd` |

目录 API 受限后，README 的示例链接用于定位 `LiquidButton`，其 import 用于定位 `InteractiveHighlight` / `Lens`；构建文件及 `DrawBackdrop.kt` 路径按常规目录推定，其中后者失败。未把路径推定当作目录确认。8 次额度按尝试计，404 也占一次；不再换文件名探测。

## 实际能力

### Compose 材质与双形状融合

`README.md:5,15` 声明 Compose Multiplatform，并明确库不包含高层组件，需要自行创建。`LiquidButton.kt:55-62` 对单个 `Capsule()` 调用 `drawBackdrop`，叠加 vibrancy、blur 和 lens；这证明示例的 Compose 使用方式，不证明内部背景捕获、递归防护或图层分配策略已审计。

`Lens.kt:16-25` 接受 refractionHeight、refractionAmount、depthEffect、chromaticAberration；不支持 RuntimeShader 或参数非正时直接返回。`:29-58` 根据角半径选择内部圆角矩形折射 shader，并设置 size、offset、cornerRadii 等 uniform。`:62-118` 只识别 `RoundedRectangularShape`、`AbsoluteRoundedCornerShape`、`CornerBasedShape`，其他 shape 抛 `UnsupportedOperationException`。

**所读文件没有 secondary shape、smooth-min 或多形状 SDF 接口。** 任意双圆 union Path 不能仅靠传入 `lens` 就获得该实现的折射；单个外包 Capsule 也不能保留两个独立圆的融合轮廓。内部 shader 字符串、实际 `drawBackdrop` 入口和全目录未取得，因此不能断言仓库其他部分绝无融合实现，也不能给出未读 shader 的 SDF/法线公式。

### Press 与输入

`LiquidButton.kt:68-86` 将 press 进度用于整体缩放，以 `tanh` 限制每轴拖动位移，并根据拖动角度、距离及宽高比作方向缩放。这里是每轴有界，不等于对角线总位移限制在同一半径内。`:101-106` 保留 Compose `clickable` 和 `Role.Button`，高亮与手势 modifier 另行组合。

`InteractiveHighlight.kt:27-38` 使用两个 `Animatable` 和 spring；`:41-94` 保存 RuntimeShader，按 press 绘制局部亮区，不支持 shader 时用平色高亮；`:95-123` 分别处理开始、结束和取消，结束/取消均将进度及位置动画归位。

这可以借鉴“材质参数与输入分离、press 亮区、有限位移”。但 helper `inspectDragGestures` 未在额度内读取，多指/滚动竞争和取消顺序未确认；所读代码未展示本项目所需的减少动态/透明度设置。spring 不能直接替换项目共享贝塞尔动效规范。拖动回调中存在 coroutine launch，绘制中构造 ShaderBrush，不能据此宣称每帧零分配或无内存风险。

### 构建与许可

| 项目 | Kyant0 固定提交声明 | 本项目当前声明与影响 |
| --- | --- | --- |
| 编译工具链 | AGP `9.3.2`、Kotlin `2.4.10`、Compose Multiplatform `1.12.0` | AGP `8.9.2`、Kotlin/Compose compiler `2.1.20`、Compose BOM `2025.04.01`；不是同版本体系，二进制/metadata 兼容未验证 |
| Android | minSdk `21`、compileSdk `37`、buildTools `37.0.0`、JVM `11` | minSdk `30`、compile/targetSdk `35`、JVM `17`；源码整体移植需要评估工具链，JVM 11 本身不是 JVM 17 的升级阻碍 |
| 核心依赖声明 | Compose foundation/ui/ui-graphics、`io.github.kyant0:shapes:1.2.1`、annotations `26.1.0` | 不能只按“一行库依赖”估成本；完整传递依赖与 AAR minCompileSdk 未取得 |
| 产物声明 | `io.github.kyant0:backdrop:2.0.1`，源码同时配置 desktop/js/wasm/iOS/macOS | 未请求 Maven，不能称该版本已发布或可解析；消费 Android 产物不等于必须迁移整个项目到 Multiplatform |
| 光学降级 | `lens` 与高亮均检查 `isRuntimeShaderSupported()` | 平台支持检查实现未读；库 minSdk 21 不代表 API 21 能运行折射。本项目现有路径 API 33 折射、31 blur、30 可读性降级继续保留 |
| 许可证 | Apache-2.0，LICENSE 文末 Copyright 2025 Kyant | 与 QWEA0 的 MIT 分开管理；复制或分发时保留相应许可证/来源/修改声明，若上游存在 NOTICE 需核实并保留 |

版本证据：`gradle/libs.versions.toml:2-8`，`backdrop/build.gradle.kts:12-19,38-44,84`。本地证据：根 `build.gradle.kts:2-4`、`app/build.gradle.kts:9-22,47`。这是文本核对，没有依赖解析、编译、ABI 或设备验证。目录 API 失败，NOTICE 是否存在尚未确认。

## 三条路线及成本

| 路线 | 可复用部分 | 为双圆目标仍须解决 | 本次判断 |
| --- | --- | --- | --- |
| 直接使用 Kyant0 库 | Compose backdrop/effects 接口及单形状 lens；按钮为 catalog 示例，仍需自建 | 版本与 metadata 兼容、背景源接线/防自采样、双形状融合、现有降级/可访问性/动效策略；发布可用性未知 | 成本中到高且未验证，不作为本轮双圆修复的已证实捷径 |
| 摘取 Kyant0 算法 | 已读 press 高亮、tanh 每轴限幅、方向缩放 | 不能从未读 shader 摘出融合；还须保留本项目取消、圆形命中、滚动竞争与共享动效，履行 Apache-2.0 来源管理 | 局部借鉴成本低到中，但不能单独解决连接棱尖 |
| 延续现有 Compose 适配 | 已有页面 GraphicsLayer 捕获、局部材质副本/效果缓存、输入与 semantics | 从 gap-only 桥改为双圆同一局部 SDF/覆盖/法线/折射/亮边，采用已读 QWEA0 smooth-min 思路并实机验收 | 对当前双圆范围最小；仍是未来实现，不能当作已完成融合 |

本地现状复核：`ScrollingGlassHeader.kt:36-64` 提供页面捕获；`LivePageGlassMaterial.kt:23-89` 复用该捕获、局部图层和效果；`GlassSurface.kt:559-620` 仍为单圆角矩形 LensShader；`GlassButtonDeformation.kt:72-100,201-209` 已有取消规则和形变；`MainActivity.kt:499-505` 仍将连接限制在 104×48dp 组内的 8×48dp gap。`LiquidGlassBridge.kt` 是独立填色/描边 Canvas，不是统一的光学表面。

后续最小生产边界仍可限制在 `LiquidGlassBridge.kt`、`GlassSurface.kt`、`LivePageGlassMaterial.kt` 及 `MainActivity.kt` 的双圆接线；保留现有 `GlassButtonDeformation.kt` 输入、`ScrollingGlassHeader.kt` 捕获作为接口使用，不顺带改 sidebar 或全局 N-shape 架构。该边界是评估建议，当前包冻结记录完成后由主 Agent 排期/分派，无需新增用户审批。

## 棱尖、扭曲与内存约束

Kyant0 已读单形状 press/lens 不能证明会修复用户截图中的尖锐缝桥。当前 gap-only 独立轮廓和亮边不能形成两个圆肩部的共同距离场；QWEA0 smooth-min 对两形状距离平滑混合，再统一计算覆盖与法线，才是相关机制。具体公式、截图 SHA 和现有桥几何证据沿用冻结 `REPORT.md`，本次不重算、不改写。

smooth-union 也不自动保证连接全过程无尖点：两个圆首次连通附近存在拓扑过渡及低梯度，数值法线、折射强度、采样 clamp 和独立 press 坐标不一致都可能导致扭曲。未来验收须覆盖未连通/初接触/完全融合、单侧与双侧 press、拖出取消、滚动和缩放；不能只看一帧连接图或数学测试。

OOM/性能边界：优先复用现有页面捕获和一个局部双圆材质层，缓存 shader/效果；不增加第二份全屏捕获、不每帧 Bitmap/decode、不强制无变化刷新。两圆实际 scale/translation/touch 必须转换到同一局部坐标后再求场；保留分别点击与无障碍语义，避免材质变化复制两套手势。候选库的内部捕获/图层生命周期未读，不能宣称它比当前路径省内存；GPU/CPU 峰值与分配率需以后实测。

## Blocked 与交付状态

1. HEAD API、tree API 均真实 403；唯一 git 备选成功。提交日期、完整目录、NOTICE 与维护历史未核实。
2. 第 7 个参考文件的推定 `DrawBackdrop.kt` 路径真实 404，实际入口、背景自采样防护、内部折射 shader 未读；8 次额度已用完，不再探测或扩研究。
3. 已读 lens 接口未提供融合；全仓是否另有融合能力保持未知。未安装或解析 `backdrop:2.0.1`，不承诺可直接引入本项目。
4. 当前完成固定提交文本对比、快照及哈希证据。没有库接入、编译、设备视觉、内存或用户验收结论；后续生产更改在当前包冻结记录完成后由主 Agent 排期/分派，无需新增用户审批。

离线核验由本地编写的 `kyant0/Validate-Candidate.ps1` 完成，输出 `kyant0/validation.json`：核对成功与失败正文/header 哈希、完整固定 SHA、8 次文件额度、一次备选，以及原 REPORT/两个原 manifest 相对于本次本地核验基线的哈希。它只处理文本/哈希，不执行下载源码、不联网。下载器 `Read-Candidate.ps1` 保留作审计，不需再次运行网络阶段。
