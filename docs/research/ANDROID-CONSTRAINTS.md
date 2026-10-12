# 安卓运行边界与路线建议

研究日期：2026-10-10（北京时间）。这是立项可行性研究，不是实现或真机验收报告。研究期间未构建 APK、未下载大型 rootfs、未执行第三方安装脚本、未接入用户 DSH profile 或凭据。

## 结论与证据范围

用户已确定最终同时支持手机本地与远程，**本地优先**；远程微端已有 `G:\DeepSeek DSH\DSH Works\Project\Dafeiyu-Go\Dafeiyu-GO-Aries` 项目，本轮仅预留其 API 接缝，不重复建设远程后端。本研究建议未来本地运行首版采用 **独立安卓应用壳 + 私有 glibc Linux rootfs + 随 APK 交付的 proot + 锁定版本 Node/DSH**，首个支持基线暂定 Android 11+、arm64。运行层保留 `RuntimeAdapter` 抽象及 Aries adapter。Termux 外部运行与直接内嵌 Android Node 作为备选研究，不增加首版部署路径。proroot、老 Android、设备自动控制与 LAN 暴露均不进入首个验证范围。

**用户最新实施顺序：先写前端，风格结合 WP8、WinUI3 与 Windows11，后端以后讨论。** 本文保留后端研究依据，以下 runtime PoC 全部是后续待做，本轮不执行；当前可交互前端必须明确展示演示状态，不能将模拟启动、模拟用量或模拟备份描述为已连接真实运行时。

这是源码支持的实施方向，尚未证明本项目能在任何安卓机上启动。DSHA 已提供同类架构、具体适配代码和上游发布者记载的设备验证，可减少探索成本；它的布局、全部功能与历史发布身份不应成为本项目的产品约束。

证据分三类：

- **已读源码事实**：桌面 checkout、DSHA checkout 的指定文件与行号。
- **平台规则背景**：Android 官方文档链接与已有平台知识；本次对六个 developer.android.com 页面进行只读网络访问，均未在设定超时内返回，因而不宣称已在线核对最新条文。发行前须重新核对。
- **设计建议/未验证假设**：本项目的模块、支持范围、验收条件；尚未实测性能、耗电、存储大小或 OEM 兼容性。

DSHA 研究基线固定为 commit [`18eaee90616808455abb225a8ae8ce5ab37c7f40`](https://github.com/DSH-APP/DSHA/tree/18eaee90616808455abb225a8ae8ce5ab37c7f40)，版本 build170。下文 `DSHA:` 均指该提交的仓库相对路径，临时 clone 清理后仍可按提交复查。桌面当前 Git HEAD 为 `4edfe298f2f327335d19e7edde0b709399bacbc8`，但研究对象是**当时工作树文件**，可能包含未提交改动，因此桌面文件引用不可误称已由该提交固化。

## 桌面代码实际承担什么

桌面项目不是 DSH 本体。`source/DeepSeekHarness.csproj:5` 指定 `net8.0-windows10.0.19041.0`，`:11–14` 指定 x64、win-x64 与 WinUI；`:45` 排除旧 `Program.cs`。因此服务行为应以 `WinUIProgram.cs` 为准，不能把旧入口当作当前发布实现。

`source/WinUIProgram.cs:5139–5176` 创建 Node 子进程，运行 DSH 的 `web --port … --no-open`，设置 `DSH_HOME`，注入用户选择的服务代理环境，再读取 stdout/stderr。`source/WinUIProgram.cs:5700–5747` 停止服务依赖 Windows `taskkill.exe /T /F`，还可能回退到端口查 PID。安卓必须重写进程管理，尤其不能照搬按端口猜 PID 的终止方式。

可沿用的是业务语义与数据契约：

| 业务 | 可复用的证据 | 安卓实现边界 |
|---|---|---|
| 模型/供应商管理 | `source/DshModelRpcClient.cs:34–79` 用启动 token 访问根入口换 cookie，再 POST `/api/<endpoint>`；`:103–127` 核对响应 ID 与结果 | 重新实现 HTTP 会话与 cookie；对锁定 DSH 版本做真实合约测试。当前 C# 客户端在 `:176–188` 只允许本机 HTTP，不能直接用作远程客户端 |
| Token 用量 | `source/assets/dsh-token-stats/lib/index.js:22–24,39–45,58–60,120–127` 只用 Node 内置模块，写 `DSH_HOME/storages/token-stats/usage.jsonl` | 适合优先移植，仍需验证上游 `llm/stream` 事件及数据格式；停止前刷新与大文件增量读另做验证 |
| 余额与告警 | `source/BalanceSupport.cs:182` 使用 Bearer API Key；`:71–89` 使用 Windows DPAPI | HTTPS 查询逻辑可重写；凭据改 Android Keystore，告警改通知，后台轮询不承诺精确即时 |
| 插件 | `source/DshPluginCliService.cs:732–824` 用官方 Node/DSH CLI 并终止子进程；`source/PackageManagerRunner.cs:37–48` 查找 Windows pnpm shim | 目录展示、来源、版本与任务状态可迁移；安装需在 Linux guest 内进行，二进制依赖按 arm64/glibc 重建 |
| 备份 | `source/Backup/DymArchive.cs:26–42` 的 `.dym` 实为 7z 并嵌入 `7zr.exe`；`source/Backup/DshDataExportService.cs:64,79–88` 导出普通 ZIP | 不能执行 Windows 7zr；Android 引入可审计的 7z 读写库或 guest 工具后再支持 `.dym`；先支持可验证 ZIP 数据迁移 |
| 公告、反馈、下载任务 | 属于 JSON/HTTP、Markdown、文件选择与任务语义 | 原生 Android 重新实现交互；服务端协议需确认移动客户端身份与兼容范围 |
| 通知按钮 | `source/ClientNoticeModels.cs:23,76` 含 `powershell` 动作；`source/PowerShellRunner.cs:24` 使用系统 Windows PowerShell | URL、受控设置跳转可支持；PowerShell 标注平台不支持。不得自动翻译成 shell 或静默执行 |

托盘、WinUI 信息窗、最高权限计划任务、UAC、.NET 桌面运行库修复与 Explorer 集成没有安卓同构实现。它们对应的用户目标分别转为持续通知、原生状态页、用户主动启动、系统授权与运行资产诊断。

## DSHA 证明了哪条实现路径

| 已读实现 | 固定提交中的源码证据 | 对本项目的含义 |
|---|---|---|
| 双安卓 flavor | [DSHA: app/build.gradle:23](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/build.gradle#L23)：standard minSdk30、low minSdk23、targetSdk37、arm64-v8a | 说明同一运行思路有低版适配，但不能据此承诺所有旧机型；本项目先做单 standard 范围，targetSdk 独立选择并核对发行要求 |
| 应用私有 rootfs | [DSHA: runtime/ProotBootstrap.java:54](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/ProotBootstrap.java#L54) 在 `filesDir/linux/ubuntu` 安置环境 | Node/DSH 与用户数据可以同包部署；应用卸载会删除私有数据，必须有用户导出 |
| 随包 native 启动器 | [DSHA: runtime/RuntimeNativeFiles.java:22](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/RuntimeNativeFiles.java#L22) 从 `nativeLibraryDir` 查找二进制 | 避开把下载的 ELF 放到可写 filesDir 直接 `execve` 的错误方案；glibc guest 通过运行时加载器启动，不表示解除 Android 系统限制 |
| argv/env 统一组装 | [DSHA: runtime/RuntimeLauncher.java:32](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.java#L32) 建 proot argv，追加 guest bash 与受控命令 | 可参考分层，不复制与 UI/桥强耦合的全类；传入路径/参数应使用结构化 argv 或可靠引用 |
| DSH Web 启动契约 | [DSHA: util/WebLaunchCommand.java:16](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/util/WebLaunchCommand.java#L16)：DSH_HOME、权限模式、确认开关、generation 与本机 host；`:44` 明确 `--no-open --host 127.0.0.1 --port` | 捕获本次真实鉴权 URL 与端口；不要依赖固定裸 URL，也不要把带 token URL 写入公开日志 |
| 前台服务 | [DSHA: HarnessService.java:130](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/HarnessService.java#L130) 及时立通知，用户 STOP 返回不重启；`:168` 使用 START_STICKY；`:195–198` 申请有限时 WakeLock；`:451–461` API34+ 使用 specialUse、LAN 时加 connectedDevice | 可见运行通知和用户停止必须成为基本能力；START_STICKY/WakeLock 不提供“永不被杀”的保证 |
| 停止与身份验证 | [DSHA: runtime/WebProcessManager.java:334](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/WebProcessManager.java#L334) 核对 PID、出生信息、cmdline、UID；`:281` 操作停止哨兵 | 起停、看门狗与更新应共享运行代次和退出证据；停止不成功时阻止覆盖运行文件 |
| 受管更新真实试运行 | [DSHA: runtime/RuntimeTrial.java:172](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/RuntimeTrial.java#L172) 在独立 DSH_HOME 与动态端口运行候选 | 更新先隔离验证，事务提交，至少保留前代；APK UI 版本与基础环境版本分离 |
| Keystore 凭据 | [DSHA: data/KeyVault.java:37](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/data/KeyVault.java#L37) AES/GCM；`:109–125` 区分锁屏、缺密钥、不可读，不生成替代解密密钥 | 可参考状态模型；加密静态配置不能隐藏执行期交给同 UID 插件的明文 |

原生依赖不是抽象风险：[DSHA: tools/dsh-runtime/package-lock.json:1193](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/tools/dsh-runtime/package-lock.json#L1193) 引入 `sharp`，同锁文件 `:70,96–99` 含 filesystem/sandbox 包，`:3251` 含 native-command。上游版本需要精确锁定；“纯 JavaScript 可跑”不足以证明完整 DSH、PTY、图像处理与工具链可跑。

**两处历史说明不能直接继承**：

1. DSHA `AGENTS.md` 历史章节称必须 `--link2symlink`、私有目录不支持硬链接；当前 [ContainerRuntime.java:95–104](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/ContainerRuntime.java#L95) 仅探测不支持时使用，探测实现是 [RuntimeHardlinks.java:23–65](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/RuntimeHardlinks.java#L23)。本项目应在目标私有根实际探测，分别验证两条路径，不能把历史结论写死。
2. 历史章节称插件脚本禁用/默认停用、导出要求密码；build170 顶部及[现行安全模型](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/docs/security-model.md#L23) 说明插件允许生命周期脚本、pnpmfile，用户导出为无密码 tar.gz。研究参考其运行机制，不自动继承该策略。

DSHA [build170 报告:9–15](https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/docs/releases/v0.2.21-build170-20261009.md#L9) 记载 Android13 真机覆盖与启动、附件选择及草稿恢复；它同时明确未重测完整冷安装、Android6/7、16KiB 真机与原生 SAF 最终保存，且未发送聊天或调用模型。该证据只能称为上游报告，不计入本项目测试通过项。

## 三种运行方案比较

| 方案 | 能得到什么 | 主要代价/不确定项 | 立项建议 |
|---|---|---|---|
| 随 APK 的 proot + Linux rootfs | 保留 Linux glibc、shell、Node 原生包与官方 CLI；用户不用额外安装 Termux | APK/展开体积大，OEM 子进程限制、ptrace 开销、CPU/电量、资产更新维护 | 主线先验证；运行时与壳解耦，仅用户启动，首版 Android11+ arm64 |
| 单独内嵌 Node / nodejs-mobile / Android native Node | 启动链短，有机会缩小 APK，适合纯 JS 与 JNI 桥场景 | Android bionic 与 Linux glibc 不同；native addons、node-pty、shell工具、动态加载与平台判断都需逐项适配；Node版本支持也须确认 | 另设有限时 PoC，只在完整依赖清单、实际调用全部通过后考虑替换主线 |
| 外部 Termux + proot-distro | 可较快人工验证 DSH Linux依赖；终端/工具已有生态 | 单独安装与用户操作，跨应用 UID 文件不能直接访问，RUN_COMMAND 需要实际权限/版本支持，生命周期和更新不归本应用完全控制 | 开发参考与高级外部模式备选；不能声称免Termux/装完即用 |
| 远程 DSH / Aries | 手机上的 CPU/存储压力小；远端可使用完整桌面/服务器工具；用户已有Aries API | 手机必须在线；项目文件在远端；TLS、配对、远端凭据、会话断线需对既有API逐项确认 | 最终必须支持，当前仅预留Aries adapter接缝；先完成本地运行再接远程，不重复开发远端API |

proot 的“root”是路径与用户身份模拟，不是 Android root；同 UID 的插件、终端、Node 和宿主可触达该 UID 有权限的文件。不能把 proot 或 DSH 的 permissionMode 描述成已验证内核沙箱。远程模式同样不意味着能远程任意管理桌面启动器：必须有经过认证的受控管理 API；当前 C# RPC 的本机限制不能简单删除来实现。

## 安卓平台约束与产品决策

下表的官方链接本轮均**未成功在线核对**。内容作为平台背景和待验证清单，不作为“2026 最新发行政策已经审查”的声明。

| 约束 | 官方参考 | 本项目决策/验证 |
|---|---|---|
| 可写应用目录执行限制 | [Android10 execute permission](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission) | native 启动器经 APK 安装到 nativeLibraryDir；不能下载 node ELF 后直接在 filesDir exec。guest ELF 的实际运行机制在目标设备验证 |
| 后台启动前台服务限制 | [Foreground service background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) | 以可见页面用户启动为主；及时立前台通知，保留停止操作；开机自启不作为等价桌面承诺 |
| 服务类型与版本差异 | [Foreground service types](https://developer.android.com/guide/topics/manifest/service-element#foregroundservicetype) | 本地交互开发服务器考虑 specialUse，并核对申报场景与发行渠道；不能把持续运行伪装成 dataSync 来规避限制 |
| 外部/派生子进程回收 | [Android12 behavior changes](https://developer.android.com/about/versions/12/behavior-changes-12#phantom-processes) | Node/shell/编译器形成的子进程树需压测；不写死一个“所有系统都最多32”的规则；测前后台、锁屏、OEM优化与内存压力 |
| 动态代码加载风险/政策 | [Android security tips: dynamic code](https://developer.android.com/training/articles/security-tips#DynamicCode) | 随包运行资产锁定版本与摘要；插件执行是单独信任决定；Google Play动态下载代码条款需发行前另核对，不凭能侧载推导能上架 |
| 16KiB 页大小 | [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes) | 查 APK 中所有 native ELF/ZIP 对齐，再在16KiB环境执行；上游“未测”范围不能继承为通过 |
| SAF与私有目录 | [Android storage overview](https://developer.android.com/training/data-storage)（本页仅列参考，未请求） | 默认私有工作区；用SAF导入/导出与应用内副本。SAF content URI不是Node普通路径，不能直接 `cd content://…`；首版不依赖所有文件访问 |
| 应用更新 | [PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller)（本页仅列参考，未请求） | APK更新经系统安装与用户确认，校验签名身份/包名/版本；运行时更新独立事务。Windows“退出后替换exe”不可移植 |
| 商店敏感权限与动态代码 | [Google Play permissions](https://support.google.com/googleplay/android-developer/answer/9888170) / [Device and network abuse](https://support.google.com/googleplay/android-developer/answer/9888379)（仅列核对入口，未请求） | 用户尚未指定发行渠道；侧载与Play上架是两个验收目标，后者需单独审查MANAGE_EXTERNAL_STORAGE、FGS用途、下载可执行内容等实际实现 |

权限以完成首版目标所需为准：INTERNET、前台服务及适用通知权限；选择文件走系统选择器。不为了复制 DSHA 直接加入 Root、Shizuku、ADB、无障碍、全部文件访问、后台位置、悬浮窗或虚拟屏。研究它们可行，不代表首版有这些必要性。

## 数据、插件、密钥与更新边界

**数据迁移**：优先迁移对话、附件、技能文本、配置与插件清单/源码；Windows 的 node_modules、`.node`/DLL/EXE、绝对 `G:\…`/`E:\…` 路径和 DPAPI 密文不作为可直接恢复的执行资产。桌面官方 ZIP 导出仍可包含插件模块（`source/Backup/DshDataImportService.cs:115–116`），因此“都是官方 ZIP”不等于平台可互通；需解析、显示迁移预览并重装平台依赖。

**备份格式**：首版建议先做有版本/范围/摘要的 ZIP 数据格式和官方数据 ZIP 导入；`.dym`读取另设PoC，之后决定首版是否承诺。DSHA tar.gz/v5归档含宿主映射与策略，不能把后缀相同或压缩成功视为结构兼容。备份/恢复应先停止写入者并取维护锁，预检路径逃逸、符号链接、大小上限与重复条目，在候选槽位验证后提交。不要把Windows凭据和设备授权一起恢复。

**插件安装**：目录可跨平台，能力不必可跨平台。先标注纯JS、Linux-native、桌面专属、设备桥接四种要求，再用锁定 DSH CLI 在 guest 安装。下载与构建生命周期脚本可执行任意同UID代码；应把安装授权、来源摘要、构建授权、启用/禁用意图、失败回切形成明确状态。GitHub归档fallback、重试、取消可复用桌面经验，但不能把Windows测试视为Android通过。

**密钥**：Android Keystore负责静态加密；解密失败应显示“暂不可读/密钥缺失/设备锁定”，不能清掉原配置或把错误当未配置。用户API Key必要时会进入guest配置/环境，插件运行时可访问明文，因此宿主加密不等于插件隔离。导出默认排除机器token与Keystore密文；若用户要迁移API Key，必须通过便携导出另作明确选择。Windows DPAPI密文不能在Android直接解开。

**更新**：Android壳、Node/DSH/受管插件、基础rootfs三个版本轴独立。下载到候选槽位并验证摘要/身份；真实启动候选检查端口与鉴权、数据读写、native模块；确认旧进程退出后切换，失败回到健康前代。磁盘不足、取消、掉电不能留下“环境已就绪”的假标记。自动重试限次数；未知写操作结果不重放。

**网络**：localhost端口也需认证，避免“只有本应用能连接”的假设；启动token不放公开日志/分享，内嵌网页权限只授权当前来源与运行代次。服务代理和安卓壳HTTP代理分别控制；DNS重试不等于可以重放API写请求。远程预留必须经过TLS与独立配对/认证，不公开裸本机管理接口。

## 后续真实运行时开发的 PoC 与验收门槛

本表是后续待执行计划，本轮没有对应通过结果。最先验证运行链，再做产品布局。

| PoC | 最小验证 | 通过门槛与失败处置 |
|---|---|---|
| P0 随包运行资产 | arm64 Android11+，APK nativeDirectory 启动proot，加载Bash/Node，检查native依赖、解压失败/磁盘不足 | 正式候选包可在目标设备冷安装；无权限/ELF/loader错误；记录包摘要、设备API/架构/页大小、展开字节和耗时。失败先修运行资产，前端继续用明确的演示adapter，不声称具备本地运行能力 |
| P1 DSH本机完整链 | 锁定DSH版本启动、动态端口/token、cookie会话，模型管理只读与隔离配置写后重开；使用明确测试模型完成一次流式任务 | 实际网页与原生HTTP都能鉴权；一次任务包含工具读/写与shell执行、会话重启仍存在。测试凭据不进日志；未调用模型只能称服务/协议通过 |
| P2 服务生命周期 | 前台/后台/锁屏、通知停止、系统杀宿主、子进程异常、旋转、端口占用、PID复用模拟、服务晚到START | 无双实例、无错杀；停止后不被看门狗拉起；可识别未知退出并阻止更新。至少在一台AOSP/Pixel系与一台目标国产OEM做Android11/13及14+覆盖；记录无电池豁免时行为 |
| P3 文件与备份 | SAF导入/导出、中文空格长路径、用户取消、ZIP迁移；.dym独立解包/预检；Windows-native插件重装 | 数据逐文件摘要匹配；拒绝路径逃逸/链接/超限；取消不改正式数据；凭据/机器状态不混入；.dym未过则明确不列首版支持 |
| P4 插件与技能 | 内置token-stats、一个纯JS插件、一个带native依赖插件、SKILL.md技能；安装重试/取消/停用 | 真正启动且发生对应调用；用量落盘/重启可读；native失败明确标记平台不支持，不影响全服务恢复；停止维护屏障覆盖安装事务 |
| P5 更新与恢复 | 候选native/插件故障、低存储、中断写、重启、版本回退、健康前代保留 | 不改坏既有会话/配置；未确认退出不覆盖资产；故障后能回到同一可用版本并留下脱敏诊断 |
| P6 资源基线 | 冷安装/热启动/流式任务，后台息屏30分钟与代表性长任务，CPU/RSS/耗电、16KiB环境 | 给出实测分位/设备条件，不借上游“+58%”或“42秒”当本项目指标；确定可接受目标后才能决定缩包/直接Node路线是否值得投入 |

建议设计接缝：`RuntimeAdapter`（start/stop/status/exec/descriptor，Local与Aries两个实现位置）、`DshSessionClient`（鉴权/cookie/RPC/流）、`StorageGateway`（私有路径与SAF搬运）、`PluginOperations`（任务/维护锁/来源/启用）、`BackupTransactions`、`CredentialVault`。这些是职责边界，不要求一次搭完所有抽象。当前前端通过演示adapter供交互评审；用户开始讨论后端后，首个runtime PoC可以是最小原生操作面，只提供准备、启动、停止与脱敏日志。

首版成功定义应为：用户在支持设备安装独立应用、准备运行环境、配置API Key并完成一次可保存的DSH任务，能可靠停止、备份核心数据和在失败后恢复。余额、用量、模型管理与插件/技能逐项加入；远程、设备控制、旧系统兼容、复杂补丁和完整桌面备份互通按各自PoC结果排期。

## 研究执行记录

- 仅读取桌面交接/README/相关源码，及临时DSHA clone中的AGENTS/README、Gradle、Manifest、运行层、服务、Keystore、锁文件与安全/发布说明。
- developer.android.com 六个网络请求各18秒超时；未把未获取到的页面写成已在线核对证据。其余官方链接仅作为复查入口。
- 未使用用户真机或凭据，未构建或执行仓库脚本，未产生APK/rootfs，未改变桌面项目。
- 本文为正式研究产物；临时DSHA源码的清理由主Agent按用户指定`Temp_Android`规则统一完成，本文不依赖临时文件保留。
