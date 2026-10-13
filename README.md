# 大肥鱼 GO · Android

Kotlin / Jetpack Compose Android 客户端，面向手机本机模型任务及通过 Aries 连接电脑 DSH。视觉采用 Liquid Glass 方向；电脑和手机共享 Connect 插件协议。

当前源码版本为 **0.1.26 / code27**，正式包名 **com.dafeiyu.mobile**，调试包为 **com.dafeiyu.mobile.dev**。正式 0.1.26 已发布并安装 QA 模拟器与 PKB110，修复本机 DSH 网页生命周期问题，真实网页与更新专项通过，用户视觉待验收。整体功能对齐仍在分阶段实现和验证。版本历史、真实构建日志、APK 摘要及安装结果以 [HANDOVER.md](HANDOVER.md) 为准；编译、离线检查、设备验证和用户验收分别记录。

## 开发基线

- [AGENTS.md](AGENTS.md)：备份、磁盘目录、研究、子 Agent、构建和设备规则。
- [移动端与 PC 对齐定稿](docs/MOBILE-PC-PARITY-SPEC.md)：唯一持续维护的需求基线。
- [液态玻璃规范](docs/LIQUID-GLASS-SPEC.md)：共享材质、动画、命中区域及省电开关。
- [源码与许可证研究](docs/research/mobile-pc-parity/REPORT.md)：固定上游来源与复用范围。

DeepSeek 官方通道保留原生接入，真实模型能力控制推理档位；其他提供商使用按需 Node worker，需安装模型运行组件。工作区、兼容对话历史、归档、权限用途桥、真实分段下载和 `.dym` 备份在 Android 数据层实现。组件采用 Ubuntu / PRoot 用户空间，共享 Android 内核；独立内核虚拟机及安全沙箱需要单独探测和验证。

在线模式以电脑为存储和执行端：手机接收目录元数据、对话与活动，产物由用户手动下载。远程任务、全局设置、网页隧道和插件宿主按能力声明开放；尚未接通的入口标注预留。厂商或 FCM 推送需要对应平台注册配置，无配置时不声明应用被回收后的离线推送可用。

## 构建

要求 JDK 21、固定 Gradle 8.11.1 与 Android SDK 35。工具链、缓存、备份、日志和产物默认位于相邻的 `Temp_Android`，不写入 C 盘。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\JDK21'
pwsh -NoProfile -File tools/build.ps1 -MaxWorkers 2 *> ../Temp_Android/build.log
```

默认构建 `assembleRelease` 和 `lintRelease`。正式签名读取 `DAFEIYU_SIGNING_PROPERTIES` 指向的本机私有配置，或 `../Temp_Android/signing/release.properties`。发行私钥与密码不进入源码仓。未配置签名的开发者可使用调试任务：

```powershell
& ./tools/build.ps1 -Tasks @(':app:assembleDebug', ':app:lintDebug') -MaxWorkers 2 *> ../Temp_Android/build-dev.log
```

同一时间只运行一个整仓构建。APK 及 `.sha256` 交付到 `../Temp_Android/Dist/`；具体版本和证据由 HANDOVER 绑定。专项检查脚本在 `tools/test-*.ps1`，采用离线数据、合成模型响应或隔离本地服务，不调用付费模型。

## 许可证

项目代码为 [MIT](LICENSE)。第三方依赖、图标、PRoot 及运行组件保留各自许可证和固定来源，见 [third_party](third_party/)。正式图标使用 Microsoft Fluent；历史 `sf_` 资源 ID 仅为兼容名称。仓库不包含发行私钥、个人 API Key、配对身份或管理凭据。
