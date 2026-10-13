# Dafeiyu-GO · Android

Kotlin / Jetpack Compose Android 客户端，面向手机本机模型任务及通过 Aries 连接电脑 DSH。视觉采用 Liquid Glass 方向；电脑和手机共享 Connect 插件协议。

当前正式版本为 **0.1.28 / code29**，正式包名 **com.dafeiyu.mobile**，调试包为 **com.dafeiyu.mobile.dev**。本轮接入调色盘主题导入、持久保存、主题页、主题市场和手机 DSH 网页主题同步；0.1.28 已部署到 stable，公网签名清单与八路分段下载验证通过。QA 模拟器已安装并完成页面、主题、网页生命周期和发布检查；真机稳定性与用户视觉仍待验收。整体功能对齐继续分阶段实现。版本历史、构建日志、APK 摘要及安装结果以 [HANDOVER.md](HANDOVER.md) 为准；编译、离线检查、设备验证和用户验收分别记录。

## 开发基线

- [AGENTS.md](AGENTS.md)：备份、磁盘目录、研究、子 Agent、构建和设备规则。
- [移动端与 PC 对齐定稿](docs/MOBILE-PC-PARITY-SPEC.md)：唯一持续维护的需求基线。
- [液态玻璃规范](docs/LIQUID-GLASS-SPEC.md)：共享材质、动画、命中区域及省电开关。
- [源码与许可证研究](docs/research/mobile-pc-parity/REPORT.md)：固定上游来源与复用范围。

DeepSeek 官方通道保留原生接入，真实模型能力控制推理档位；其他提供商使用按需 Node worker，需安装模型运行组件。工作区、兼容对话历史、归档、权限用途桥、真实分段下载和 `.dym` 备份在 Android 数据层实现。组件采用 Ubuntu / PRoot 用户空间，共享 Android 内核；独立内核虚拟机及安全沙箱需要单独探测和验证。

在线模式以电脑为存储和执行端：手机接收目录元数据、对话与活动，产物由用户手动下载。远程任务、全局设置、网页隧道和插件宿主按能力声明开放；尚未接通的入口标注预留。厂商或 FCM 推送需要对应平台注册配置，无配置时不声明应用被回收后的离线推送可用。

## Dafeiyu调色盘

独立模块 [glass-lab](glass-lab/) 的应用名称为“Dafeiyu调色盘”，正式身份仍为 `com.dafeiyu.mobile.glasslab`，版本 `1.0.2 / code3`，覆盖安装保留已调参数。共享生产玻璃组件，提供 74 项可见数值滑条、16 项颜色及浅／暗八组独立参数；JSON 保留两项旧字段，共 76 个数值字段。新增玻璃厚度、内圈折射与色散、连续圆角，以及无、压暗、发光、追光和抬升五种按压效果。松手后融合颈部逐渐收细，按压和移动使用同一接近范围。

预览底图提供 12 种纯色、10 种渐变、HSV／RGBA 调色盘与图片背景，独立于按钮自身颜色。系统照片选择器只读取选中的图片；相册图片限制尺寸并转成 JPEG，随完整 JSON 保存与导出，重启后不依赖原相册 URI。参数自动续存，支持精确数值、撤销／重做、命名方案、对照、放大及真实按压／拖动。右上角软盘保存完整快照并打开 Dafeiyu-GO 的主题页；“方案与导出”提供文件保存、分享、复制及导入。导入主题只改变材质与颜色，生产按钮功能、图标身份和布局仍由主 App 管理；预览照片不发送给 DSH 网页。调色盘无网络权限，不读取主 App 的账户、模型凭据或对话。

用户提供的参考图片由可选本地资产目录 `../Temp_Android/material-lab-assets/preview-backgrounds/reference.png` 打包，不包含在公开 MIT 源码中；没有该资产时参考图片入口禁用，相册选择和其他背景可正常使用。该图不因项目许可证而获得 MIT 授权，其来源说明随本机交付保留。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\JDK21'
pwsh -NoProfile -File tools/build-glass-lab.ps1 -Variant release *> ../Temp_Android/build-glass-lab.log
```

未配置发行签名时使用 `-Variant debug`；设备专项包加 `-WithTests`，需要核对生产 App 编译时加 `-VerifyMainApp`。交付 APK 为 `../Temp_Android/Dist/Dafeiyu-Palette-1.0.2.apk`，调试包以 `-dev.apk` 结尾。构建、数据检查、设备安装与用户验收分别记录在 HANDOVER。

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
