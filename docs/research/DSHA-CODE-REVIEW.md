# DSHA 安卓实现可行性代码审查

审查对象是 `DSH-APP/DSHA` 固定提交 [`18eaee90616808455abb225a8ae8ce5ab37c7f40`](https://github.com/DSH-APP/DSHA/tree/18eaee90616808455abb225a8ae8ce5ab37c7f40)。仓库副本位于 `Temp_Android/DSHA`，仅作本次只读研究；没有运行它的下载、构建、部署或设备脚本，也没有修改该仓库。下面的链接全部固定到此 commit，避免把会变化的主分支内容当作证据。

## 结论先行

DSHA 已经证明“在 Android 11+、arm64 上以 Android 应用私有 UID 运行完整 glibc Linux、Node 和 DSH Web”是可行的。可借鉴的主线是：APK 内置或分包的 Ubuntu rootfs，Android 私有目录保存运行数据，`nativeLibraryDir` 中的 proot 启动器，Java 编排安装/生命周期，以及系统 WebView 承载本机回环 Web UI。它不需要 Android root，也不要求用户安装 Termux。

后续本地后端的候选主线是 **Android 11+ / arm64 / 单一 proot / 本地 DSH 服务 / 新的简洁原生壳**。DSHA 的移动页面和复杂设备能力不应作为 UI 复刻目标；可以复用运行时、生命周期、备份格式和安全边界的设计。proroot、Low（Android 6）、ADB/Shizuku/Root、虚拟屏、无障碍、LAN 代理和悬浮条都是后续可选能力，需独立验证和授权评估。

用户随后指定当前阶段先写前端：以 WinUI 3 的设计语言、Windows Phone 8 的页面组织及现代 Windows 11 审美设计可交互手机界面，后端之后再讨论。本报告保留为后续本地后端的研究材料，不能视为已经决定原生技术栈或立刻接入容器；现有 Aries 远程服务仅保留接口候选。

## 仓库与 README 事实

- README 声称免 ROOT、免 Termux、安装 APK 后运行完整 Ubuntu；Standard 为 Android 11+，Low 为 Android 6+，两者仅 arm64，发行包约 273/345 MiB（[README.md#L42-L71](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/README.md#L42-L71)）。
- README 把能力分为离线 rootfs/六步安装、Node 24 + pnpm 10、proot/proroot、前台看门狗、v5 备份、插件市场、WebView/Gecko、设备桥和应急恢复（[README.md#L111-L235](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/README.md#L111-L235)）。它明确承认包体大、仅 arm64、没有内核级 shell 沙箱，默认 DSH 权限档位是 `danger-full-access`（[README.md#L251-L264](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/README.md#L251-L264)）。
- 当前 Gradle 事实比 README 更可靠：`applicationId com.dsh.client`、`targetSdk 37`，Standard `minSdk 30`、Low `minSdk 23`，`arm64-v8a`，Java 17，版本码 170；标准版用系统 WebView，Low 依赖 GeckoView 143，终端依赖 Termux terminal-view（[app/build.gradle#L16-L45](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/build.gradle#L16-L45)、[app/build.gradle#L329-L348](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/build.gradle#L329-L348)）。
- Manifest 声明了联网、录音、安装包查询、前台服务（specialUse/connectedDevice/dataSync）、局域网、通知、悬浮窗、开机、唤醒锁、定位、外部存储/安全设置和 Shizuku 等广泛能力，且 `allowBackup=true`、`usesCleartextTraffic=true`（[AndroidManifest.xml#L5-L56](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/AndroidManifest.xml#L5-L56)）。新壳可以显著收窄权限集合。

## 可直接借鉴的运行路径

### 容器与进程

`ContainerRuntime.Proot` 将 `libproot.so` 从 APK 的 native library 目录启动，传入 `--rootfs`、`--cwd=/root`、`--kill-on-exit`，按存在性挂载 `/dev`、`/proc`、`/sys`、`/storage/emulated/0` 等；不支持硬链接时才启用 `--link2symlink`（[ContainerRuntime.java#L84-L126](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/ContainerRuntime.java#L84-L126)）。`RuntimeNativeFiles` 明确从 `ApplicationInfo.nativeLibraryDir` 取库，避免 Android 10+ 对 app 私有 `filesDir` 执行 ELF 的 W^X 问题（[RuntimeNativeFiles.java#L22-L34](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/RuntimeNativeFiles.java#L22-L34)）。

启动命令由 `WebLaunchCommand` 集中生成：设定 `DSH_HOME=/root/.dsh`、工作区映射、权限/确认模式、语言和 generation；先写 PID/身份文件及停止哨兵检查，最后 `exec dsh web --no-open --host 127.0.0.1 --port …`（[WebLaunchCommand.java#L3-L47](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/util/WebLaunchCommand.java#L3-L47)）。控制器等待实际鉴权输出，慢启动 60 秒只提示，不把慢启动当崩溃（[WebLifecycleController.java#L351-L410](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java#L351-L410)）。停止由 PID、进程出生身份和代次核验，避免端口猜 PID 或误杀 proot（[WebProcessManager.java#L175-L199](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/WebProcessManager.java#L175-L199)）。

### Android 前台服务与保活

`HarnessService` 负责常驻通知、3090 bridge、15 秒健康探测、连续失败后限冷却自动重启，以及屏幕熄灭时的 WakeLock/WifiLock（[HarnessService.java#L45-L55](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/HarnessService.java#L45-L55)、[HarnessService.java#L320-L410](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/HarnessService.java#L320-L410)）。Android 8+ 的 `startForegroundService` 五秒通知时限也在 `onStartCommand` 明确处理（[HarnessService.java#L132-L145](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/HarnessService.java#L132-L145)）。新壳可先只保留前台服务、通知、有限健康探测；不必立即实现 LAN、休眠策略和复杂自动重启。

### WebView 边界

标准 Web 预览启用 JavaScript，但关闭 file access，使用基于本机服务来源的 WebMessage listener，并检查当前页面、主 frame、URL 和 generation；Cookie 交换后才恢复页面状态（[WebPreviewActivity.java#L501-L552](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java#L501-L552)、[WebPreviewActivity.java#L605-L633](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java#L605-L633)）。Low 版另有 Gecko 会话、上传、下载和页面权限回调，但这不是 Android 11+ 第一阶段所需。

### 运行时补丁与兼容层

`RuntimeLauncher` 将受管 bridge shim 加入 `NODE_OPTIONS`；`RuntimeTools.prepare` 读取 `runtime-patches.json`，准备临时目录、DNS 兼容、PDF/浏览器 bootstrap、client combo 和 LAN 持久化补丁（[RuntimeLauncher.java#L73-L104](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.java#L73-L104)、[RuntimeTools.java#L213-L288](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/RuntimeTools.java#L213-L288)）。这说明 Android 可用“上游 DSH + 明确版本受管补丁”路线，前提是补丁按版本/摘要匹配，失败时拒绝启动。

## 功能实装与 README 差异

| README 承诺 | 代码证据/实际状态 | 立项判断 |
|---|---|---|
| 六步独立修复 rootfs、工具、Node、pnpm、DSH、补丁 | `InstallPipeline` 会探测环境、工具、pnpm、补丁；对 Node/DSH 失败路径主要提示用户备份后修复，不是完整独立重装器（[InstallPipeline.java#L20-L42](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/InstallPipeline.java#L20-L42)、[InstallPipeline.java#L124-L185](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/InstallPipeline.java#L124-L185)） | 新项目先做不可变 rootfs/一次性安装，后续再做组件修复 |
| Node 24、pnpm 10、DSH 0.2.0-rc.2 | `tools/dsh-runtime/package.json` 固定 DSH 版本；`runtime-tools.lock.json` 固定 pnpm 10.34.5。仓库没有把 Node 下载实现作为普通 APK 功能展示，Node 属离线运行包输入 | 运行时打包必须有可复现摘要和 ABI 证据，不能只依赖版本号 |
| proroot 性能提升及回退 | `ContainerRuntime.Proroot` 需要五个专有库和 `PROROOT_*` 环境；注释明确它没有 `--kill-on-exit`，必须另做精确停止（[ContainerRuntime.java#L163-L181](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/ContainerRuntime.java#L163-L181)、[ContainerRuntime.java#L231-L280](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/ContainerRuntime.java#L231-L280)） | 第一阶段排除 proroot；它增加许可证、进程和机型回归风险 |
| 无密码 `.tar.gz` 导出、旧加密读取 | v5 manifest/entry 逐文件 SHA-256、格式版本、范围和敏感策略都会校验；`copyUnencrypted` 明确把导出标成 `UNENCRYPTED`（[BackupArchive.java#L61-L79](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/backup/BackupArchive.java#L61-L79)、[BackupArchive.java#L153-L167](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/backup/BackupArchive.java#L237-L277)） | 可借鉴格式/校验；新壳首版可仅做显式用户导出 |
| 默认自动备份、保留最近副本 | `enabled` 默认 true，JobScheduler 每 15 分钟周期检查，策略另算到期；实际“最近 3 份”由备份策略/清理协作者负责，不是单一类的证明（[AutomaticBackups.java#L23-L78](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/backup/AutomaticBackups.java#L98-L106)） | 首版可先做手动/停止后备份，确认存储和电量策略后再默认开启 |
| 插件检查后自动启用，允许生命周期脚本/pnpmfile | `plugin-dependencies.py` 明确 `pnpm install --no-frozen-lockfile`、允许 build/lifecycle scripts；`plugin-manager.py` 记录 transaction/activation（[plugin-dependencies.py#L314-L336](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/assets/plugin-dependencies.py#L314-L336)、[plugin-manager.py#L599-L610](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/assets/plugin-manager.py#L599-L610)） | 这是同 UID 任意代码执行面；新项目首版应只允许内置/用户明确导入，默认停用第三方 |
| 技能导入不执行正文 | `SkillsActivity`/`SkillImports` 只复制和校验 `SKILL.md`，正文命令不自动执行（[SkillsActivity.java#L90-L115](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/ui/SkillsActivity.java#L90-L115)、[SkillImports.java#L57-L83](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/skills/SkillImports.java#L57-L83)） | 可直接借鉴；技能目录白名单和文件大小限制很有价值 |
| 脚本可从 GitHub 增量下载并离线验签 | 当前可定位到 APK 更新的 HTTPS、长度/SHA-256、包身份和已安装签名校验（[UpdateEngine.java#L596-L605](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/core/UpdateEngine.java#L596-L605)、[UpdateEngine.java#L638-L668](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/core/UpdateEngine.java#L638-L668)）；未找到可审计的内置脚本公钥/签名验签实现，README 该项不能视作已证实 | 立项时只支持签名 APK/固定摘要运行资产，不宣称热补丁验签 |

## 设备能力与安全边界

3090 bridge 用随机 token 文件、loopback 监听和端点门控；设备命令策略在 Java/特权执行侧共享。无障碍、ADB 无线配对、Shizuku、Root、虚拟屏、短信、定位、录音、共享存储都属于独立敏感能力，不是运行 DSH 所必需。Manifest 的权限规模（[AndroidManifest.xml#L5-L37](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/AndroidManifest.xml#L5-L37)）和安全模型都提醒：proot 的 guest root 仍是 Android 同 UID，插件/终端代码不是沙箱；LAN HTTP 也不是 TLS（[docs/security-model.md#L1-L30](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/docs/security-model.md#L1-L30)）。API key 使用 Android Keystore AES/GCM（[KeyVault.java#L18-L60](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/data/KeyVault.java#L18-L60)）。

## 许可证和可借鉴范围

顶层 DSHA 自有代码是 MIT（[LICENSE](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/LICENSE)），但 APK 不是“全部 MIT”：

- proot/loader 为 GPL-2.0；Termux terminal JNI 采用明确的 Apache-2.0 例外；Shizuku、GeckoView、AndroidX、SnakeYAML、BouncyCastle 和 npm 依赖各自保留许可证。
- `proroot v1.2.8` 是上游专有许可，仓库明确“不声称可重编、审计源码或任意修改后再分发”；`libandroidshmem.so` 来源/许可证未完全核验，标准 proot 原始二进制精确修订也标为未验证（[THIRD_PARTY_NOTICES.md#L28-L40](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/THIRD_PARTY_NOTICES.md#L28-L40)、[THIRD_PARTY_NOTICES.md#L66-L80](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/THIRD_PARTY_NOTICES.md#L66-L80)）。
- 移动插件 `dsh-web-mobile` 声明 MIT、固定上游提交但有本地补丁，不能当作未修改上游直接复制（[THIRD_PARTY_NOTICES.md#L16-L27](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/THIRD_PARTY_NOTICES.md#L16-L27)）。

因此可以借鉴架构和自有 MIT 代码，并分别保留 GPL/Apache/MPL/LGPL 等通知；不要复制 proroot 二进制，不要把整个 DSHA 或第三方 APK 资产笼统标成 MIT。新项目若只用 proot，应对原始来源、修改、对应 GPL 源码和动态链接边界做完整合规记录。

## 给 Dafeiyu-Go Android 立项的建议

1. **第一阶段（可行性最强）**：Android 11+、arm64，Java/Kotlin 新壳；APK 或受管分包携带固定 Ubuntu arm64 rootfs、Node/DSH 运行包和 proot；只暴露本机 WebView、API key、工作区、启动/停止、日志和显式导出。
2. **运行时门禁**：复用 rootfs 外状态、版本/摘要清单、generation、PID 出生身份、停止哨兵和 `exec dsh web --no-open --host 127.0.0.1`；不要用端口或裸 PID 杀进程。所有补丁按精确文件摘要和版本匹配。
3. **前台保活**：实现一个最小 foreground service + 通知 + 有界健康探测。把 WakeLock、LAN、自动重启冷却作为可选开关，先验证 Android 厂商后台限制。
4. **数据安全**：API key 用 Android Keystore AES/GCM；首版做 v5 类逐文件摘要导出，明确“无密码导出即明文”，自动备份默认值等真机存储/电量策略稳定后再开。
5. **插件/技能**：内置插件和 `SKILL.md` 导入可先做；第三方 npm 插件若允许脚本，必须明确同 UID 任意代码风险，默认停用或要求用户逐次批准。不要照搬 DSHA 当前“检查后自动启用”的高风险策略。
6. **后续候选**：Low/Gecko（需 API 23 兼容构建）、proroot（专有许可证和进程回收证据）、ADB/Shizuku/无障碍/虚拟屏/短信/传感器/LAN/悬浮条。每项都应独立权限、测试和威胁模型，不阻塞本地 DSH 主线。

## 证据限制

本审查是固定 commit 的静态代码研究，未执行 Gradle、下载器、rootfs 生成器、安装脚本、APK 安装或真机验收；README 所列 Android13 通过范围、性能百分比和冷安装时间来自仓库发布记录，不能推导到 Dafeiyu-Go 或其他厂商设备。`Temp_Android/DSHA` 为临时研究副本，研究结束后可删除；本报告的固定 GitHub 链接保留了可复核证据。主协调者检查时发现 `gradle/verification-metadata.xml`、`tools/i18n/ui-source-baseline.json`、`tools/recovery-runtime/lock.json` 有本地状态差异；本审查子 Agent 未写入这些文件，也未执行会修改它们的脚本，差异原因不在本审查中推断，代码事实以 commit 链接为准。
