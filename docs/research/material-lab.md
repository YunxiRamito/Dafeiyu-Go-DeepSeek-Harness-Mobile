# Android 材质实验台研究

2026-10-14，Asia/Shanghai。先通过 `http://127.0.0.1:7890` 查询 GitHub，取得固定树、README、MIT LICENSE 与实际 Demo 源码，未执行上游脚本。

- 仓库：[QWEA0/Liquid-Glass-Android](https://github.com/QWEA0/Liquid-Glass-Android)。固定提交 `16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d`，MIT，Copyright 2025–2026 pandadog。
- 精读 `ProfessionalDemoActivity.kt:834–1024` 的可收起调参面板、有限范围滑条、直接渲染属性更新及背景源拓扑；并核对 README 的 API33 光学与旧 API 降级说明、许可证。
- 复用交互思路：固定实时预览配滚动调参面板、每项当前值、切换不同背景检查光学。实验台不导入整个 Demo，也不复制 Demo Activity 或运行其构建脚本。
- 实际渲染共享本仓已审计的生产 Compose 组件；其 QWEA0 光学改编出处仍在源文件及 `third_party/` 中。图标共享固定 Microsoft Fluent 资源及每文件 MIT 声明。
- 独立 `glass-lab` 模块无网络与模型权限；导入导出只包含材质数据。分别保存四种组件与两种主题，有限范围校验和撤销记录避免错误参数进入 shader。

本次 HTTP200 的树、源码、README、LICENSE 及原 headers 保存于 `../Temp_Android/research-glass-lab/`。构建、设备验证与用户验收只记到 HANDOVER。

## 调色盘、内圈折射与主题导入

2026-10-14续接：仍经7890代理读取上述固定QWEA0提交的README、MIT及`GlassLensRenderer.kt`，审查有符号距离场斜面、逆幂厚度和RGB分别采样的内圈压缩／色差实现。采用同源光学计算思路接入现有单形状与双形状shader，不复制其演示页面。真实请求与源码保存在 `../Temp_Android/research-palette-themes/`；本轮GitHub API出口限流响应保留在 `picker-commit.json`，该失败不代表不存在可复用实现。

保存图标使用Microsoft Fluent System Icons固定提交 `a347af95478f989038cccbf085978865264c048a` 的 `Save24Regular`；资源文件保留完整MIT声明和固定来源。用户提供的九色玻璃图标单独声明为用户选择的素材，未将其声明为项目MIT作者作品。

主题文件分享审查AndroidX固定提交 `078de553` 的 `FileProvider` 源码、README及Apache-2.0许可，原件在 `../Temp_Android/research-palette-theme/`。复用现有AndroidX FileProvider受限content URI与临时只读授权，不传外部长期URI；主App使用完整数据文档的大小、UTF-8、范围和枚举校验。预览照片不进入网页主题上下文，主题不承载脚本或账户凭据。

设备测试使用 Compose 既有测试框架。首次 API37 专项由传递依赖 Espresso 3.5.0 的 `InputManager.getInstance` 反射失败阻断，还未运行 App 检查。读取 Google Maven 固定 `espresso-core:3.7.0` sources 的 `InputManagerEventInjectionStrategy.java`（Apache-2.0）：API23 以上改用 `Context.getSystemService(InputManager.class)`，不再反射该方法。将实验台测试依赖固定为 Espresso 3.7.0、runner 1.7.0、ext.junit 1.3.0；只进入测试包，不进入正式实验台。原 sources jar 在上述研究目录 `test-runtime/`，未执行其脚本；实际设备是否通过另以 HANDOVER 的专项结果为准。
