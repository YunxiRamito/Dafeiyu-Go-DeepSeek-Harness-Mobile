# 当前批次 · 0.1.24 / code25 · 2026-10-12

接续用户对 0.1.23 的四张反馈图及 DSHA 发布链接，完成阴影、侧栏粘连、真实下载源、Ubuntu/开发工具组件页与官方模型脚本工具。三名子 Agent 负责玻璃/组件 UI、侧栏/Fusion、Linux 安装运行器；主 Agent 读文档、研究固定 DSHA/PC 源码和许可证、审查原生准备脚本、整合下载/工具/工作区保护、构建和交付。改前备份位于 `../Temp_Android/backup-0.1.24/`，参考图位于 `docs/reference/ui-2026-10-12-runtime/`。实现、离线检查、整包构建、安装和用户验收分开记录；下面最终交付段补充最终状态。

- 圆按钮阴影降到最大黑色 alpha .08、48dp 按钮约 5.76dp 有限范围/1.2dp 下移、spread=0；单/双圆及 Fusion 共用参数。右上双圆与侧栏采用“显式按住，或真实尺寸/位移运动”驱动，再按实际表面间距门控；稳定按住保留连接，松开静止有限淡出。不会扩大命中区域，遵守减少动态、减少透明度和弹性开关。
- 对话归档为 `#FFCC00` 黄底/深色前景，空工作区删除为 `#D92D23` 红底/白色前景；两类动作均与正文共享实际测量轮廓、折射/反射/阴影，侧栏使用自身实色采样保障正文可读。共享材质接管时独立背景、采样、材质和阴影让位。原两次滑动许可、取消、精确动作 ID、空区保护和删除确认保留。
- 下载源默认国内 CDN（已有明确保存的选项保留），GitHub 镜像失败后官方回退；官方与后端为独立线路，GitHub API/uploads 不转第三方。后端固定现有 CA/SPKI pin 并保留主机验证；包/资源固定 SHA、大小上限、缓存复核、部分文件与提交分离、取消清理。npm 使用 npmmirror/官方；明确选择后端时沿用 PC `/api/npm/`、Git `/api/git/` 和端点 CA。模型后续执行使用当前下载源，不继承宿主 token/代理环境。
- “组件”“Linux 运行环境”“运行详情”均读取共享管理器实际状态，不再展示硬编码运行日志或伪 VM 硬件开关。Ubuntu、Node.js、Git、pnpm、Python 有真实安装/修复/检测/版本/进度/取消入口；安装由应用进程内共享 scope 继续，离页可返回查看，进程退出后需重新安装或检测，不声称系统后台服务/断点续传。READY 后可手动运行五类离线测试脚本，展示真实 stdout/stderr/退出码/超时/截断，离页取消测试；不会自动运行。
- Linux 采用 DSHA 的 PRoot 用户空间方式，Ubuntu 24.04.3 不带桌面，共享 Android 内核、无需 root，**不是独立内核 VM 或安全沙箱**。固定 DSHA `v0.2.0-rc2`/commit `8362bba747fcf84c19e436c3dbb594a0fc388a67`，首次下载并缓存完整 APK 286,371,170 bytes（约 273 MiB），再选取已校验 Ubuntu/工具资源；没有下载启动 DSH CLI/web bundle/设备桥。Node 在基础包内，Git/Python/pnpm 为可选工具包。
- 原生启动器改用官方 Termux PRoot 5.1.107.96、android-shmem 0.7、talloc 2.5.0，固定 DEB/ELF/源码/build-system hash；执行器与 loader 放在 APK nativeLibraryDir，不直接 exec 私有下载 ELF。GPL/LGPL/BSD 和完整对应源码随 APK 保留，见 `third_party/linux-runtime/`、打包 `linux-runtime/runtime-lock.json` 和 [研究记录](docs/research/dsha-runtime-20261012/REPORT.md)。没有声称可复现重建上游 DEB。
- 安装使用候选 slot、复制旧环境、保护 root/home/workspace 用户目录、真实启动版本 probe 后 AtomicFile 切换；失败/取消不发布候选，旧环境保留。解包拒绝越界路径、链接逃逸和特殊文件，展开大小/条目有界。取消以 PRoot 支持的 SIGQUIT 清理 tracees，核对准确 PID/UID/父 PID/exe/启动时间；普通 SIGTERM 被该版本忽略。stdout/stderr 有界，检测/执行/安装串行。脚本文件保存在按工作区 ID 划分的应用私有 Linux 工作副本；SAF 不直接挂载。非空工作副本纳入工作区删除的 IO 复核，避免索引移除后丢失文件入口。
- 官方轻引擎提供 `run_script`（bash/node/python、1–120 秒），真实结果进入既有工具回执/下一轮上下文；重复 call ID 不重复执行、取消不缓存成功、非零退出码/超时/截断不改成成功执行。缺少环境给出实际错误和组件安装提示。输出回执有界，保留截断标记。后台子 Agent 尚未新增脚本工具；完整 DSH CLI/宿主加载仍未完成，本批不冒称已接入。

架构边界：QA 模拟器实际 `ro.product.cpu.abilist=x86_64,arm64-v8a` 含 ARM 翻译，主 ABI 为 x86_64；**不能据次选 arm64 宣称 PRoot 可执行**。管理器按首选 ABI 仅接受原生 arm64-v8a，模拟器页面明确禁用安装并显示原因。PKB110 是真实运行目标；本轮按用户要求不自行截图或操作界面验效果，arm64 实际安装/脚本/取消行为仍需在真机主动验收。现有“沙箱权限”档位仍未控制真实执行，菜单说明已明确标为预留；实际脚本运行在应用私有 Linux 工作副本，不把选中“只读”误写成已实施的文件权限。

已通过的离线证据：`qa/glass-0.1.24` 47/47 真实 helper + Compose snapshotFlow/collectLatest（动画端点边界为夹具，无 AGSL/GPU），`qa/sidebar-fusion-0.1.24` 35/35 原手势 + 12/12 几何/连接/颜色和两文件完整 Compose 编译；Single 18 float/Pair 26 float uniform 声明、上传和使用对应。`qa/components-ui-0.1.24` 与 `qa/components-ui-smoke-0.1.24` 各 14 项静态接入检查和真实页面严格 Compose 编译，管理器边界为签名夹具。主线程 `qa/linux-integration-0.1.24` 13/13 下载/工具回放/取消/截断检查（synthetic transport/provider，没有 TLS/Android 进程）；`tools/test-official-web.ps1` 27/27 旧工具/Session 离线回归；`qa-workspace-delete-0.1.24.log` 28/28 生产删除/持久化检查，包含新 Linux 文件和 SAF 工作副本保护，Android AtomicFile/界面边界为 JVM 夹具。

整包第一次 `build-0.1.24-final.log` assembleDebug + lintDebug 成功 3m55s；第二次 `build-0.1.24-final2.log` 成功 3m44s，补原生 ABI 门禁和许可证。两次均早于最后工作区保护修改，**最终包以最终交付段所列构建/摘要为准**。

解包收尾发现并修复实际错误：Commons Compress 1.27.1 的 `TarArchiveEntry.isFile()` 会对非目录条目宽泛返回 true，不能用来区分符号链接/硬链接/特殊设备。最终改为显式 `linkFlag` 白名单，并逐级拒绝归档父目录符号链接。主线程 javap 与安全 fixture 证明旧实现失败、新类型分支可正常创建链接并拒绝设备/FIFO。真实五个 DSHA 资产 SHA、规范路径重复、条目/大小、197+433 个 symlink、零 hardlink/特殊条目、所需路径与 native 依赖/ELF 对齐均已实际审计。Windows JVM 生产解包在 Ubuntu 的合法 Linux 文件名 `gcc-14-base:arm64.list` 处失败（Windows 不允许冒号），因此完整真实归档解包及运行仍待 Android，不把 metadata 审计或前 26 个合成通过写成真实 Ubuntu 安装通过。失败日志 `qa-linux-runtime-security-final3.log` 保留，最终独立 synthetic 检查另列日志。

最终 Runtime 专项 `qa/linux-runtime-0.1.24/final-evidence.json`：`final-synthetic.log` **26/26** 安全解包/链接/修复/取消/主 ABI/共享状态检查通过；`final-android35-compile.log` 真实三文件严格 Kotlin Werror/Android35 API 编译通过，下载与 OS 运行边界为夹具。现成 WSL Ubuntu 位于 D 盘、挂载 G 可用，但没有 Java，未下载/安装工具或启动 C 盘 Kali；完整五包生产 Kotlin 解包仍待 Android。三个 Agent 收尾分别负责 runtime fixtures、APK 身份/ELF hash、下载/工具/配对合同只读审计，主线程整合；最后修正系统提示残留“no shell”与新 run_script 的矛盾，未新增模型调用。

配对接续（用户 2026-10-12 通知）：连接包仍 `dafeiyu-go-connect@0.2.0`、`dafeiyu-go.connection.v1`，本地重新核对 88,680 bytes、SHA `9DF7085CD5FA9ECF0E6F9A31AACE8D0272EF60C4A9AEF12A2EA96EFAFDF6AA1B`，未改 feed 或插件版本。Aries 0.0.3 名称接入沿用上一版。`pairing_unpair`、原子解除接口、稳定 Idempotency-Key/重签重 nonce、发起端确认状态和对端取消后的 terminal 接续均为待定/未来能力；不调用未发布接口，不以 presence/404/本地删凭据代替双方确认。后续配对 UX 需按能力提供取消配对，并在旧服明确提示/保留关系。PC 28/76 PASS 为用户报告的桌面边界，不代表 Android Provider、实际 DSH Loader 或手机互通通过。

配对只读审计发现既有 Android 下一阶段缺口：离线 snapshot 为 unknown、刷新成员可能让配对展示丢失已确认关系，`paired()` 仍有两成员判定，尚无安全取消入口；本轮没有修改该状态机，也不宣称通知已全部实现。需后续以持久关系独立于 presence、通用多人组和能力门控进行修复，见 `qa/contract-audit-0.1.24.log`。

## 跨仓库改动（0.1.24）

本轮只读 PC 下载源与进程环境实现、连接插件冻结包、Aries 协议通知及 DSHA/Termux 固定外部源码；未修改 PC Click-To-Run、DeveloperCenter.Server、Connect-Plugin 或 Aries 仓库。无付费模型调用，无清除数据/权限授予/手机自动界面测试。源码与完整上游许可记录在本仓，下载、构建、备份、APK 和测试临时产物均位于 G 盘 Temp_Android。

## 最终交付证据（0.1.24）

最终 APK `../Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.24.apk`，**105,345,742 bytes**，SHA-256 **`c98eea936eb243e888f00d87a47aee6ba83b40ef266d78a3881d9f12ff75129c`**。最终 `build-0.1.24-final6.log` 为 `assembleDebug + lintDebug` **BUILD SUCCESSFUL in 3m50s**，46 tasks（12 executed / 34 up-to-date）；Lint **0 Error / 143 Warning / 5 Information**。aapt 确认 package `com.dafeiyu.mobile.preview`、0.1.24/code25/min30/target35，apksigner v2 验证成功、证书 SHA 仍 `52fc036fd6fe45d400f354235068aab29cb27ca6f68e42c53368a5b00c887b23`。`qa/apk-audit-0.1.24.log` 已绑定 final6，五个 PRoot/loader/talloc/shmem 原生文件逐项 SHA 与 lock 一致，完整来源/许可证/源码随包；旧 final5 audit 另名保留，不能用其 hash。最后提示文案修改后的旧工具 Session 回归 `qa-official-web-0.1.24-final.log` **27/27** 再次通过。

最终 APK 保留数据安装：每轮先 `adb devices -l` 重新发现。QA `emulator-5558` 的 `install -r` 返回 Success，包管理器确认 0.1.24/code25；`am start -W` 返回 Status ok、MainActivity。真机 PKB110 无线 serial `adb-3B15AP01NZT00000-0X4cpb._adb-tls-connect._tcp` 的 `install -r` 返回 Success，包管理器确认 0.1.24/code25，lastUpdateTime `2026-10-12 16:29:35`；**没有替用户启动真机应用、操作界面、授予权限或清除数据**。安装日志 `install-0.1.24-qa-final.log`/`install-0.1.24-phone.log`，包信息分别 `package-0.1.24-qa-final.log`/`package-0.1.24-phone.log`。界面视觉、真机实际组件下载/完整解包、版本探测、脚本/超时/取消清理、模型实际调用脚本、双端配对仍待用户或后续针对性验证；不把安装成功写成这些通过。

`tools/RecordBuildEvidence.ps1` 已绑定最终 APK/构建/`qa/delivery-0.1.24.json` 更新 `docs/frontend-build.json` 的源码/配置/实际打包 runtime 输入指纹；日志 `record-evidence-0.1.24.log`。未发布 GitHub/未修改跨仓库代码/未复制 C 盘桌面。下一步用户在真机设置的组件页安装 Ubuntu（基础包包含 Node），再安装 Git、pnpm、Python并手动测试；遇到失败按实际进度、详情和结果定位，不从模型口述推定环境可用。

# 历史交付 · 0.1.23 / code24 · 2026-10-12

本轮接续 0.1.22 的六张补充反馈，并按用户随后明确选择“这轮一并接入设备名称”，合并 Aries 0.0.3 客户端接入。三个子 Agent 先分别修改玻璃、侧栏和主题，再按互不重叠文件分工实现 Aries Client、GroupStore 和 PairingScreen；主 Agent 负责审查、Provider 名称透传、设置展示、版本、测试与最终交付。源码版本为 0.1.23/code24，改前备份在 `../Temp_Android/backup-0.1.23/`。用户参考图在 `docs/reference/ui-2026-10-12-followup/`。

本批实际改动：

- 右上双圆连接改由连续 scale/translation 几何变化驱动，静止及稳定长按连接归零，运动/回弹触发，最后一次有效变化后有限淡出；不扩展命中区域，不新增常驻 timer。圆按钮采用缓存轮廓软阴影，Single/Pair/Fusion 共用归一化参数并扩大绘制记录边界，发送按钮调用方不再截断外阴影。
- 暗色主题色玻璃提高主题色自身的透射散射，Single/Pair 参数一致；主题 `onPrimary` 和发送按钮前景改白。减少透明度/无采样的既有不透明 fallback 仍使用完整主题色，不能将透光中心的离线白字对比度模型外推到 fallback。
- 侧栏对话和工作区使用同一两段左滑手势门槛，第一手势只展开完整动作胶囊，独立第二次滑动才可拉伸至整行并在真实抬手阈值提交；含阻尼、放大图标和文字。对话只归档，移除独立永久删除按钮；工作区为红色删除，实时检查空区资格并保留原确认。两类正文胶囊同为不透明局部主题底，保留玻璃边缘且不透主页 logo，动作不受 reveal 宽度矩形裁切。工作区加号接原目录选择器，删除独立文件夹按钮；应用内新建目录保留明确文字入口。
- Aries Node 读取能力数组，仅当前 endpoint 声明 `device_name` 才用原已签名 heartbeat 上传 Build 品牌/型号名称；完整节点 pin 在配对调用中再次核对。名称先拒绝控制字符/无效 Unicode，再 trim Unicode 空白并按 1–128 code point 校验；无能力继续旧请求 body，不给 enroll 添加新字段。
- 心跳、申请列表/详情及成员模型读取可选名称。名称只用于展示，missing/JSON null 保留缺名，合法字符串 `"null"` 仍是名称；模型共享严格 Unicode helper。Provider 的本机、对端和成员名称来自实际服务端成员字段，不再拿设备 ID 充当名称；未共享时显示“对方尚未共享设备名称”。ID、公钥、指纹、审批、内容密钥和成员权限继续沿用原协议。

已执行 UI 合并构建：第一轮因 `WorkspaceSidebar.kt` 的字符串插值标识边界失败，修为 `${actionLabel}` 后，`../Temp_Android/build-0.1.23-final2.log` 完整 `assembleDebug + lintDebug` 成功（3m56s）。此构建早于 Aries 接入，**不是最终合并交付证据**；本批最终构建、安装及验收记录将由下方最终交付证据覆盖。

最终交付包：`../Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.23.apk`，90,761,499 bytes，SHA-256 `d2947abdb64905bc226d94624f7952050f3d85504a8698f890fe19f8624647d4`；aapt 确认 `com.dafeiyu.mobile.preview` / 0.1.23 / code24，apksigner 证书 SHA-256 仍为 `52fc036fd6fe45d400f354235068aab29cb27ca6f68e42c53368a5b00c887b23`。最终 `../Temp_Android/build-0.1.23-aries-final.log` 的 `assembleDebug + lintDebug` **BUILD SUCCESSFUL in 3m32s**，46 tasks（13 executed / 33 up-to-date），Lint **0 Error / 140 Warning / 5 Information**。第一次因 PairingScreen 新增 scope guard 的 8 个 SuspiciousIndentation lint 错误停止；加文件级 suppress 后重新构建通过，未改变运行逻辑。

设备状态分开记录：每次先重新发现 serial。QA 模拟器 `emulator-5558` / `Dafeiyu_QA_API_37` 使用 `install -r` 成功，包管理器确认 code24/0.1.23，随后 `am start -W` 返回 `Status: ok`，当前前台为 `com.dafeiyu.mobile.preview/com.dafeiyu.mobile.MainActivity`，pid 14127；未操作界面和截图，视觉验收待用户。用户随后明确要求真机推包，已对 PKB110 无线 serial `adb-3B15AP01NZT00000-0X4cpb._adb-tls-connect._tcp` 执行 `install -r`，返回 `Success`，包管理器确认 code24/0.1.23；没有启动、清除数据或授予权限。真机视觉/功能验收待用户。

离线 UI：`../Temp_Android/qa/sidebar-swipe-0.1.23/verification.log` 的 **35/35 真实 Kotlin helper 场景通过**（严格 Werror，覆盖独立第二手势/UP/Stop/cancel/token/保护变化/精确动作/RTL/密度/窄屏几何）；未模拟完整 Compose pointer dispatch。`../Temp_Android/qa/glass-0.1.23/run.log` 与 uniform/motion/shadow/colour 报告通过：Single 18、Pair 26 个 float uniform 声明/上传/使用对应，独立几何和颜色数学模型通过，**不是 Kotlin 动画调度、AGSL 驱动或像素验证**。侧栏扩展先经 7890 读取 AndroidX 固定 commit `7ac433e44e797de53af85226797862687f37735f` 的源码、README 和 Apache-2.0 许可，记录 `docs/research/sidebar-swipe-20261012/REPORT.md`；沿用既有 Compose 依赖，没有复制上游代码或升级依赖。玻璃沿用既有固定 QWEA0/MIT 研究，源码和打包 NOTICE 已补本批适配。

Aries 离线接入证据：`../Temp_Android/qa/aries-client-name-0.1.23/run.log` **42/42** 真实 Client/Policy/Unicode helper 严格 Kotlin Werror 检查通过，覆盖当前端点能力变化、旧 body、pin mismatch 零 POST、128 emoji/控制字符、心跳名称响应和签名 canonical body hash/新 nonce；使用替代 Android Build/Context、身份签名器和 HTTPS connection，没有实际 TLS 握手/Ed25519 验签/线上请求。`../Temp_Android/qa/ui-0.1.23/aries-provider.log` **65/65** 真实 Provider/scope/crypto 配 synthetic IO 和固定 PC wire fixture 通过，包含新名称透传/缺名/literal `null`/重命名不改变配对和内容 key，另真实 Android35 API compile-only 通过；`aries-protocol.log` **32/32** 原生产协议规则通过；`aries-membership.log` 原成员 **17/17**、Group **9/9**、Balance **9/9** fixtures 通过。GroupStore 新名称专项最终目录为 `../Temp_Android/qa/aries-group-name-0.1.23/`；早期两个 Agent 的临时 QA 文件发生撞名，已拆目录并重新执行，生产源码不受该夹具错误影响，不把被覆盖的旧日志当最终证据。

Aries 服务端状态来源：用户报告 0.0.3 已部署、GitHub `main` commit `1ee8e99`、44 项公网响应冒烟通过；本 Agent 已读 `../Dafeiyu-GO-Aries/docs/LAUNCHER-PAIRING.md`，未替服务端重跑这 44 项，也未修改服务端。服务器 opaque 信封中继/ack 通过不等于本批 Android/Windows 的真实配对和密钥界面联调通过。手机端名称及 UI 的实际用户验收仍需用户执行。

## 跨仓库改动（0.1.23）

本轮仅只读 Aries 契约/源码和已有 PC wire fixture；没有修改 PC Click-To-Run、DeveloperCenter.Server、Connect-Plugin 或 Aries 仓库。没有清除数据、授予权限或进行付费 DeepSeek 调用；真机仅按用户明确要求覆盖安装，用户要求自行看效果，本轮不截图、不注入聊天数据、不操作界面做视觉 QA。

# 历史交付 · 0.1.22 / code23 · 2026-10-12

本轮接续用户说明的 Claude 修改后 **0.1.21 / code22**，按十张反馈图片修复界面，已编译并保留数据覆盖安装到独立 QA 模拟器。用户明确要求自己看效果，因此本轮没有截图、操作界面做视觉 QA、注入聊天数据或发起付费 DeepSeek 调用。**实现、最终构建、离线检查和安装启动已完成；界面、交互及用户验收待完成。** 下方暂停、0.1.20 源码及旧版本安装说明均为历史，不代表当前状态。

三个子 Agent 分别负责共享玻璃、聊天和设置；主 Agent 自己读四份交接/需求文档及玻璃规范，处理侧栏、共享开关布局、图标、版本、集成审查及交付。生产源码已冻结，保留原有共享工作树改动；改动前备份在 `../Temp_Android/backup-0.1.22/`。用户参考图保存在 `docs/reference/ui-2026-10-12/`。本轮没有新建按版本堆叠的 Markdown。

| 范围 | 当前实际实现 |
| --- | --- |
| 主页圆按钮材质与连接 | 单/双按钮统一短边光学、方向反射和颜色透射；右上双按钮连接按真实表面距离计算，移除按压门槛，移动和伸缩更新同一 frame。连接绘制不扩大点击区域。 |
| 侧栏和主题色胶囊 | 删除工作目录中的重复“任务与子 Agent”入口，右上任务菜单继续有效。侧栏图标入口透明；对话行改同目录的玻璃胶囊、选中白字、移除对号/实心内层/额外描边。左滑归档改为实际测量形状的共享折射轮廓，保留原两段左滑删除门槛。 |
| 设置主页 | 删除运行、账户与用量概览块，改半页公告板，底部“查看全部公告”接真实 notice 路由；完整公告/通知可展开阅读。共享 ToggleRow 修正首页横向 inset，未接通开关标预留并禁用。 |
| 聊天输入与指标 | 发送按钮为主题色玻璃。沙箱/模型入口透明。大输入框/指标采用 ReadablePanel（8dp blur、最高 .28 tint），从独立消息背景采样，composer 为兄弟 overlay，避免自采样。首轮真实 assistant MessageCompleted 后显示指标，由输入框分离成胶囊并文字渐显，共用 Fusion。指标使用 SF 秒表、整数 t/s、累计 tok、缓存命中率，前景单色，去掉步骤和“已完成”。无任务总量时圆环后为“未知”，没有捏造 30%。 |
| 回复操作与活动 | 缩紧活动前导图标占位/文字间距，移除常驻复制/引用/下载整排；正文长按使用真实 Compose 文本选择菜单，引用所选文本到输入框且不覆盖系统剪贴板。生成文件预览同样长按操作，导出在标题更多菜单中。 |

新增 `LiquidGlassFusionSurface.kt` 支持两个实测圆角矩形、椭圆/圆的共同距离场采样、颈部、折射、亮边及阴影；成功共享绘制时让两个独立材质让位，失败/低 API/减少透明度或动态效果路径恢复独立材质。侧栏采样独立主页 source，聊天采样独立消息 source。AGSL uniform 上传已核对。玻璃适配沿用已研究的 QWEA0/Liquid-Glass-Android 固定 commit `16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d`（MIT），没有新增上游依赖，源码/打包 NOTICE 已补适配范围。秒表来自现有固定 SF Symbols 数据集，出处和许可证保留。

交付证据：

- APK：`../Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.22.apk`，**88,865,960 bytes**，SHA-256 `0d32861844bb13a2aac80243549c74165aae4a774f01adbb6e75afd3045720a5`，同目录 `.sha256` 已核对。aapt 确认 `com.dafeiyu.mobile.preview` / 0.1.22 / code23；签名校验通过，证书 SHA-256 `52fc036fd6fe45d400f354235068aab29cb27ca6f68e42c53368a5b00c887b23` 与旧包一致。
- 最终单仓构建：`../Temp_Android/build-0.1.22-final.log`，`assembleDebug + lintDebug` **BUILD SUCCESSFUL in 3m19s**，46 tasks（18 executed / 28 up-to-date）；Lint **0 Error / 140 Warning / 5 Information**。较早 compile-only 通过不替代这个最终合并构建。
- 离线指标：`../Temp_Android/qa/chat-run-metrics-0.1.22/run.log`，真实 production metrics/info bar/GlassMotion 严格 Compose/Werror 编译及 **30/30 JVM 场景 PASS**，含首轮完成/失败/历史恢复。GlassSurface 为签名 fixture；没有设备菜单、动画或像素验收。既有仓库三份指标测试与备份 hash 一致，新增用例只在 G 盘临时目录。
- 玻璃检查：`../Temp_Android/qa/glass-0.1.22/uniform-and-geometry.json`，17 个单 shader / 26 个共享 shader float uniform 均有上传和使用，content 子输入绑定对应；独立数学几何模型检查通过。**这是静态/数学检查，没有执行 Kotlin 几何或 AGSL 像素。** 旧脚本的 press gate 契约已过时，本轮没有冒称旧专项通过。
- ADB 每次重新发现并确认 `emulator-5558` 为 `Dafeiyu_QA_API_37`，现有 host GPU 硬件加速实例；未重启或降级渲染。`install -r` 返回 **Success**，包管理器确认 code23/0.1.22；`am start -W` **Status: ok**，当前 PID 20846。短启动日志 `../Temp_Android/qa/ui-0.1.22/startup-pid.log` 无 FATAL EXCEPTION，含 `GlassPair Shared optical surface active`；这只证明启动及该绘制分支激活，不能算效果验收。设备时钟记录 lastUpdateTime 为 `2026-10-11 22:24:33`，不把设备时钟写成宿主日期。
- 实际范围记录：`../Temp_Android/qa/ui-0.1.22/acceptance.json`；`tools/RecordBuildEvidence.ps1` 已同步 `docs/frontend-build.json` 的最终包身份、源码/runtime 输入 hash 和验收边界。模拟器保留应用打开交给用户；没有安装手机、复制桌面、授予权限或清除数据。

待用户检查：深浅主题实际材质、相邻按钮运动时颈部、首轮状态条分离/文字渐显、长按复制引用、窄屏开关与公告、侧栏归档拉伸。归档各向异性圆角目前用 Rect 宽高/半径近似，不声称逐像素精确；历史长聊天、全部回执和持久化专项的待验收状态不因本轮编译通过而消失。

## 跨仓库改动

本轮没有修改 PC Click-To-Run、DeveloperCenter.Server、Connect-Plugin 或 Aries 仓库；构建脚本消费已有 runtime 输入。

# 本轮暂停交接 · 2026-10-12（历史）

用户于 2026-10-12 要求“把这轮的所有操作写进交接文档，这次先到这里了”。本节是当前批次的最高优先级状态；写入后已停止继续开发、编译、安装、设备调试和子 Agent 工作。下一轮必须先读本节，再参考下方历史章节，不能把历史 0.1.19 交付或本轮 0.1.20 中间产物误称为同一个最终包。

## 停止时的准确状态

- 当前源码版本是 **0.1.20 / versionCode 21**。本轮的目标是把五组相关功能合并后统一编译验收：共享双按钮液态玻璃、流式聊天手动滚动/回底、空工作区和两段左滑、真实椭圆运行指标、活动回执时序/分组/复制引用。
- 用户最新指定后续子 Agent 使用 **6.1 Sol Max**。已将 [AGENTS.md](AGENTS.md) 中的协作规则改为 `model="gpt-6.1-sol"`、`reasoning_effort="max"`，并明确不能把 high 档位当作 Max。之后新建的规划子 Agent 确实按 Sol Max 派发；本轮原有 high 任务只做收尾，没有声称已经切换模型。
- 本轮结束时没有运行中的 `java`、`gradle`、`emulator` 或 `qemu-system-x86_64` 进程。已关闭本轮新建的规划子 Agent，原有专项 Agent 也已完成或关闭；不要从旧的 Agent ID 推断仍有后台任务。
- 未把 0.1.20 APK 复制到桌面，未通过 ADB 安装 0.1.20，也没有启动本轮 APK。用户手机的上一轮 0.1.19 安装状态保持不变。

## 本轮实际代码和文档操作

五组修改从前序 worker 接续到本轮，均保留在共享工作树中；这些是实现状态，设备验收仍见下文：

| 功能组 | 落盘文件与实际处理 |
| --- | --- |
| 右上双圆玻璃 | 新增 `ui/components/GlassPairLensShader.kt`、`LiquidGlassButtonPair.kt`，共享页面采样/平滑形状融合/折射/亮边/阴影；前景和材料读取同一动画 frame，104×48dp 原布局和独立 48dp 点击目标保持准确。`GlassButtonDeformation.kt` 发布 frame，`GlassSurface.kt` 支持材质让位；`AgentsTaskActions.kt`、`NewSessionActions.kt` 上报实际菜单 alpha，`MainActivity.kt` 接入共享 pair。补 RTL 中心映射、去掉重复 alpha 乘法、加入实际绘制后的单次 `GlassPair` active 日志。低 API、无采样、减少动态/透明度和失败路径保留降级。 |
| 手动滚动/回底 | `ui/ChatScreen.kt` 将滚动跟随、回底和指标缓存绑定当前会话/生成/回复，避免旧会话状态串入；跟随等待用户交互结束并检查 pointer/drag/fling/manual pause，回底可被触摸取消。已有按钮位于输入框右上方。 |
| 工作区与两段左滑 | `ui/WorkspaceSidebar.kt` 把删除许可绑定 DOWN generation、UP 阈值、Drag Stop 和当前 revealed 行；在 interaction 发射时记录 Start/Stop/Cancel，取消反弹或旧事件不能误删，新 DOWN 取消旧 rebound。订阅任务/存储错误变化刷新空区删除资格；目录选中呈椭圆主题色底、白字/玻璃边缘及颜色过渡。具体 frozen 文件为 `tools/tests/workspace-delete/FROZEN-0.1.20.md`。 |
| 指标胶囊 | `ui/ChatRunInfoBar.kt` 按实际测量宽高取半短边半径，支持 FlowRow 换行；轮数、步骤、平均速度、累计 tok、缓存命中率来自真实记录。`中` 表示缓存命中，不表示上下文容量；任务完成进度暂无总量，显示未知，不编造 15%。 |
| 回执与文本动作 | `ui/AssistantActivitySummary.kt` 仅合并同轮相邻同类型活动；搜索→命令→搜索保留三处，思考/正文打断分组，晚到 child result 更新原行。`AssistantActivityFeed.kt` 回执复制与 `GeneratedFiles.kt` 的复制/引用/导出动作改 `consumeDrag=false`，使展开区域可正常滚动。 |

`app/build.gradle.kts` 和 `tools/build.ps1` 已递增为 0.1.20/code21 及对应 APK 文件名。`docs/ITERATION-0.1.20.md` 已有五组计划和前置 focused 记录，但其未完成框早于本节，不反映后来已执行的所有专项；停工时以本节为准。`third_party/liquid-glass/NOTICE.md` 和打包的 `app/src/main/assets/third-party/liquid-glass/NOTICE.txt` 已记录 pair 适配来源，MIT LICENSE 保留。

重大玻璃功能研究沿用已读取 README/许可证/源码的 QWEA0 固定 commit `16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d`（MIT）和 Compose 候选评审：`docs/research/liquid-glass-20261012/REPORT.md`、`COMPOSE-ADAPTATION-REVIEW.md`、`COMPOSE-CANDIDATE.md`。没有新增上游依赖；此前重新核对上游 HEAD 失败，不能声称使用了最新主分支。本轮继续遵循 GitHub 先研究、固定引用、许可和真实设备视觉验收规则。

本轮没有回滚其他人的改动。主线程先检查现有工作树、上一轮构建和 QA 证据，然后修正了一个生产条件表达式：`app/src/main/java/com/dafeiyu/mobile/ui/components/LiquidGlassButtonPair.kt` 去掉绘制分支中已经由 `supported` 保证的冗余 `capture != null`，让严格 `-Werror` 的 focused 编译不再产生“条件恒为 true”的警告。这个改动保持 `supported` 定义中的无背景采样降级检查不变。

液态玻璃专项的测试适配器随后出现了 JVM 命名冲突：验证脚本同时抽取了生产扩展函数 `GlassPairShape.optics` 和同名顶层包装函数。主线程在 `tools/test-liquid-glass-buttons.ps1` 的 synthetic adapter 包装函数上加了 `@JvmName("opticsForChecks")`，没有改变生产算法。该问题是真实的 focused 测试夹具错误，已记录为修正而非生产功能失败。

已尝试读取上一轮四个关联研发聊天，但四次 `codex_app/read_thread` 都返回 `Invalid app tool request`，没有取得新的 PC、连接插件、DeveloperCenter 或 Aries 协议状态。本轮没有把任何跨项目聊天内容写成已确认事实，也没有向这些聊天发送消息。

## 已取得的验证证据

下面的结果都是真实执行记录，宿主离线检查和设备检查严格分开：

- 初始 0.1.20 完整构建与 Lint 已通过：`../Temp_Android/build-0.1.20-batch.log`，**BUILD SUCCESSFUL in 3m55s**，46 tasks（18 executed / 28 up-to-date），Lint XML 为 **0 Error / 138 Warning / 5 Information**。当时生成的 `../Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.20.apk` 为 88,651,997 bytes，SHA-256 为 `6ce977be14c19b65ae20fc84061aeae9760f7bbb69f8a23be72ee28a9ed6cd11`。这个包对应冗余条件修正前的源码快照，不能作为当前工作树的最终交付包。
- 修正测试适配器和生产条件后，重新启动了最终构建，使用 `tools/build.ps1 -MaxWorkers 2`，构建开始时可用内存约 5102 MiB，并设置 `JAVA_TOOL_OPTIONS=-Xmx1536m -XX:MaxMetaspaceSize=512m`。构建已完成 `assembleDebug` 和 APK 打包，但用户要求暂停时正处于 Lint 收尾阶段，尚未出现 `BUILD SUCCESSFUL`。最新中间 `app-debug.apk` 为 89,331,540 bytes，SHA-256 为 `d5b8c4f5ab3b6f9a9b6f0cdfa68a9052d9465112e7fe259ff32c4e323fe0af39`；它不是最终交付包，也没有安装或复制。
- 共享玻璃 Integrated focused 检查在修正 adapter 后通过：真实 Compose/Werror 编译、5618 个命中边界、240 个桥几何、12 个 verbatim pair transform、10 个 alpha 边界、触摸取消/多指/父滚动、provider/ripple 隔离、缓存和 AGSL 边界均有 PASS。证据目录：`../Temp_Android/qa/liquid-glass-buttons/run-eaf98e7b3d4344358ae095a458944793/`。该结果明确写着 device/AGSL visual rendering not executed，因此不能替代 Android 画面验收。
- 工作区左滑专项通过 **14/14**：`../Temp_Android/qa/workspace-delete/swipe-3cacf3ebf0b94389b574e16464af8d77/`。覆盖首次左滑不授予删除、第二次需归档稳定、旧 token/generation、取消许可/切换行对象、低于阈值、主题色与白字等。它抽取真实生产 helper，但不模拟完整 Compose pointer dispatch 或实际像素。
- 工作区删除与持久化专项通过 **25/25**：`../Temp_Android/qa/workspace-delete/run-46fd54d18f964583862f2e18051c844d/`。覆盖最后空区替换、草稿/附件/任务/输出保护、异步 IO 重检、SAF/链接/占用路径和恢复写入；UI、任务状态和 Android AtomicFile 的部分是 JVM fixture，仍需设备验收。
- 前置的聊天滚动专项仍有效：真实 ChatScreen/GlassMotion Compose 编译、16 个状态场景、14 个接线检查、5 个会话/空闲边界检查通过，证据在 `tools/tests/chat-scroll/output/run-f4faabfbcc9d45e8bb7e1a873249c738/`。
- 前置的指标专项仍有效：真实指标组件 Compose 编译、27 个行为场景、12 个源码契约通过，证据在 `../Temp_Android/qa/chat-run-metrics/run-31e95db4384a406888541df4d398f9f6/`。信息条现按真实尺寸计算胶囊半径，并保持无任务总量时不伪造百分比。
- 前置的回执专项仍有效：真实 Feed/Summary/Details/Protocol Compose 编译与六个检查入口通过，包含 21 个时序场景和 9 个思考标题场景，证据在 `../Temp_Android/qa/chat-stream-ui/run-74e55e3cf7604f32a26c62f128288e98/`。本轮没有再宣称 Android 设备上的间距、视觉或完整流式验收通过。
- 0.1.20 QA runner 的离线准备为 **44/44 PASS**，没有执行 ADB、JVM、构建或模型调用。结果为 `tools/tests/resume-qa-20261011/runs/offline-5b04aa469d324d9ebae32f7727beb12e/result.json`，SHA-256 `BFAD5B98AB7CA8CB97FD031FDBE4C3281D9A7C367676B05FEE26978872A85B74`；独立冻结文件 `tools/tests/resume-qa-20261011/freeze-020-manifest.json`，SHA-256 `7E983156AAC455D843BBC8BF6CD1C7E6D691BFD3DB25A520F6CC9B8990D70E5F`。冻结绑定 0.1.20/code21，但只证明 QA 输入和离线门禁，不证明设备行为。
- 通过 `G:\Android SDK\emulator\emulator.exe -accel-check` 核对到 **WHPX installed and usable**。本轮没有启动独立 QA AVD，所以没有共享玻璃实际 AGSL、流式滚动、工作区触摸或内存峰值的设备截图/日志。

最终构建的日志为 `../Temp_Android/build-0.1.20-final.log`；session 1598 在收到暂停要求后通过 Ctrl-C 结束（exit 1），不是 Kotlin 编译失败。当前 `../Temp_Android/build/app/outputs/apk/debug/app-debug.apk` 已与 Dist 包不同，最后一次文件身份核对时间约为 **01:40 +08:00**。磁盘 Lint XML 的时间仍为 **01:31:05**，属于第一次成功构建，不能用来证明第二次被中止构建的 Lint。`docs/frontend-build.json` 和 `docs/acceptance-0.1.19-20261012.json` 继续保留旧交付基线；本轮没有调用 `RecordBuildEvidence`，也没有生成虚构 0.1.20 acceptance JSON。

此前失败的 Shared run `../Temp_Android/qa/liquid-glass-buttons/run-6464907bb93e4420997dfc7a723df270/` 保留为 adapter JVM 冲突证据；后续 Integrated 成功已包含 Shared 全部真实来源，不需要为了计数再重复 Shared。脚本阶段的 60 个源码契约与真实 Compose/JVM/设备结论区分记录。本轮验证堆上限为 512 MiB，整仓构建与模拟器未并发，未观察到 OOM；这不证明宿主峰值或长期无泄漏。

无线 ADB 在本轮只做了设备列表和已安装包的只读核对：手机型号 PKB110，serial 当时为 `adb-3B15AP01NZT00000-0X4cpb._adb-tls-connect._tcp`，确认 version 0.1.19/code20、lastUpdateTime **2026-10-12 00:49:48**。没有读取凭据内容、发起付费模型请求或修改手机数据。ADB 服务保持连接；下一轮先重新发现动态 serial。

本轮 worker 收尾：Plato（工作区）、Beauvoir（回执）和旧滚动 worker 已在此前完成后关闭；Halley（玻璃测试）确认冗余条件可等价删除后关闭；Locke（QA runner）交付离线 44/44、独立 freeze 及 Short/LongChat 入口后关闭。Lagrange 是本轮唯一新建的 Sol Max 规划 worker（`01a12c07-56e9-7071-9989-16556278b959`），用户暂停后从 running 关闭并收到 shutdown 通知，未交付规划文件。旧 worker 无需重新等待或重建其成果。

## 本轮尚未完成

- 当前工作树没有与最新条件修正绑定、同时通过 assemble 和 Lint 的 0.1.20 最终 APK。下一轮必须从当前源码重新完成整包构建，并重新生成/核对包元数据、签名、SHA-256 和 `docs/frontend-build.json`；不能继续使用上面的旧 Dist 包或被中止的 `app-debug.apk`。
- 尚未启动硬件加速 AVD，尚未检查 RTX/GPU、SurfaceFlinger、`GlassPair` active/failure 日志和真实亮/暗主题像素。WHPX 检查通过不等于模拟器已经运行。
- 尚未运行绑定最终 APK 的 020 Short/LongChat 设备矩阵。应使用独立模拟器和独占临时剪贴板，先完成 receipts/copy-reference/320dp，再做有界双向 LongChat；PNG 的 capsule 间距、回执行距、流式显隐、回底按钮、玻璃边缘和左滑形变要人工复核。LongChat runner 会把未到达端点写成 `INCOMPLETE`，不可强行改成 PASS。
- 尚未把最终 0.1.20 APK 放到桌面或安装到用户手机。下一轮如需交付，先完成 APK 身份和设备验收，再按用户此前授权复制桌面、按当前 `adb devices -l` 发现的确切手机 serial 执行 `install -r`，安装后保留数据并核对包 hash；不要把旧的配对码或动态端口写死。
- 思考/搜索/再次思考的真实事件顺序、工具回执统一间距、信息条最终窄屏布局、用户滑动时流式不抢位置、空工作区真实 UI 删除、归档图标/两段左滑动画、工作目录选中外观和共享玻璃实际画面都没有通过本轮设备验收。已有 focused/源码不等于用户验收。
- 轻引擎脚本执行/沙箱、受限子 Agent 工具会话、联网文件下载解析及搜索质量仍有缺口。子 Agent 结果回父 Agent、附件/图片等已有实现/部分用户证据，应接续真实多轮、模型兼容性和失败取消回归，不能把它们整体说成未实现。DSH runtime/网页插件未完成；连接插件 package prepared 不等于 Android runtime loaded，不能把已有连接协议或离线 fixture 写成端到端互通。
- 后续规划子 Agent 原计划只写 `docs/NEXT-ITERATIONS-20261012.md`，但因本轮暂停在写入前关闭，本文件不存在；下一轮需要按当前清单重新安排规划，且重大功能先经 `http://127.0.0.1:7890` 检索 GitHub 并记录固定 commit/tag、许可证和源码证据。

## 下一轮入口（仅供接续，不是本轮执行指令）

1. 先读本节、`AGENTS.md`、`docs/ITERATION-0.1.20.md`、`清单.md` 和 `tools/tests/resume-qa-20261011/BATCH020.md`，确认当前源码是否又有变化。
2. 串行完成最终 `assembleDebug` + `lintDebug`；现有 focused 已通过，只有新增源码变化、失败或覆盖缺口才安排对应回归。设备验收报告绑定最终 APK 后，再运行 `tools/RecordBuildEvidence.ps1` 更新 `docs/frontend-build.json`。
3. 用 `Dafeiyu_QA_API_37` 以 `-gpu host`、WHPX、合理内存/CPU 启动独立 AVD，主 Agent 独占设备；安装当前最终 APK，验证 `GlassPair`、滑动、回执、信息条、删除与内存日志，结束后恢复/关闭设备。
4. 运行 020 QA runner 时使用独立 freeze：`tools/tests/resume-qa-20261011/freeze-020-manifest.json`、ExpectedVersionCode 21、`-FinalReady -MainAgentOwnsEmulator`，并把实际 APK SHA-256 和 emulator serial 填入。LongChat 的不完整结果必须如实保留。
5. 设备证据完整后才考虑生成桌面副本和手机安装，再更新本节及构建/验收元数据。后续新建子 Agent 使用 `gpt-6.1-sol` + `reasoning_effort=max`，并为每个 Agent 指定不重叠文件归属。

# 上一轮 0.1.19 恢复状态 · 2026-10-12（历史）

截至 2026-10-12（Asia/Shanghai），**0.1.19 / code20 已作为可用户验收的预览包交付桌面并保留数据安装至用户手机；整包成功，设备验收仅部分通过，长聊天仍失败**。新包已包含 FishButton `consumeDrag` 参数与 GeneratedFiles 标题 `consumeDrag=false` 修正。当前证据以本节、[迭代计划](docs/ITERATION-PLAN-2026-10-11.md) 和 [验收记录](docs/acceptance-0.1.19-20261012.json) 为准；下方停工、旧包身份及未交付说明保留为历史。

- 最终包：`../Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.19.apk`，**91,404,408 bytes**，SHA-256 `c7e3320b4c483c05be842caa62f8f1158b9502ad6870a8ab14a5e5c7cf8a41f3`；签名证书 SHA-256 `52fc036fd6fe45d400f354235068aab29cb27ca6f68e42c53368a5b00c887b23`。
- 构建：`../Temp_Android/build-0.1.19-scroll-fix.log`，**BUILD SUCCESSFUL 3m11s**，46 tasks（12 executed / 34 up-to-date）；Lint **0 Error / 138 Warning / 5 Information**。`RecordBuildEvidence` 已更新 [frontend-build.json](docs/frontend-build.json)，记录 179 个源码/配置及 19 个实际 runtime 输入，并绑定上述验收记录；输入哈希快照不替代设备验收。
- 设备结果：独立硬件加速 `emulator-5558` 最终 run `activity-019-30b3d08caa2b409c90848385ede716c9` **EXIT 1**。终态折叠、分组活动/图片元数据回执、最终正文关联及三次活动展开/收起 PASS；长聊天原限额 24+24 次滑动未找到 `QA_LONG_FIRST_019`，FAIL。[Kuhn 只读分析](docs/research/QA-LONG-CHAT-20261012.md) 显示轮次 19→12→19，有正常双向滚动，但未覆盖第 0 轮；不写完全卡死或唯一根因。全事务 workspace/task/output membership、SHA-256 与原有 absence 恢复 PASS，App 停止。后续两端往返、320dp、Interrupted 和当前 PID 日志/内存检查未执行；不宣称完整验收。本轮未再重复完整矩阵，后续按新变化安排针对性验证。独立 QA 模拟器已于 00:51 左右经 `adb emu kill` 正常关闭并释放资源，用户原 AVD 不变；必要时由主 Agent 再以 host GPU / WHPX 启动。
- 修正观察与 QA 边界：新包短诊断 `activity-019-67829cab2b7744e79e4c4f273eaa5867` 中原 center 起点已滚动且未 toggle，随后因 QA 空子节点 bounds 报错；不是完整诊断 PASS，普通标题点击回归仍待验收。QA 已仅修脚本空子节点/可见候选并离线 **37/37 PASS**；不能据此勾选生产设备行为。旧包、Scene drift、两起点对照及中间失败保留在迭代计划历史记录。
- 交付：桌面 `C:/Users/KitamaruRamito/Desktop/Dafeiyu-Go-Mobile-0.1.19.apk` 已复制并核验同 hash；手机 `192.168.5.201:33601` 执行 `install -r` Success，version 0.1.19 / code20，lastUpdateTime **2026-10-12 00:49:48**，手机 `base.apk` SHA-256 匹配。实际 delivery 对象已写入验收 JSON 并重新同步 frontend-build.json。保留应用数据，安装后未启动 App、无模型调用；安装成功不等于手机 UI 验收通过。
- 研究已完成：[QWEA0 固定源码/MIT/几何研究](docs/research/liquid-glass-20261012/REPORT.md)、[Compose 适配评审](docs/research/liquid-glass-20261012/COMPOSE-ADAPTATION-REVIEW.md)、[Kyant0 候选比较](docs/research/liquid-glass-20261012/COMPOSE-CANDIDATE.md)。现有 gap-only 桥尚非共同光学融合面；下一轮由主 Agent 排期，无需新增用户审批。须同源实际动画几何、关闭原材质/阴影/白光、同步 `launcherAlpha` 并提供低 API 降级，不能只加 secondary rect。研究完成不等于实现或设备通过。
- 继续按 [AGENTS.md](AGENTS.md) 执行重大功能先经本机 `127.0.0.1:7890` 研究 GitHub、固定 commit/tag 并留存源码/许可依据的要求。流式滚动/IME、复制引用、删除与重启持久化、两段左滑、主题与玻璃视觉仍待逐项验收。

上述四份 Markdown 的同步由文档子 Agent 完成；该子 Agent 未修改源码、metadata 或研究材料，未启动网络、ADB、编译、测试或模型调用。这仅说明文档同步工作的范围，不否定主 Agent 与其他子 Agent 已完成并在上文记录的研究、构建、验证及交付。

# 停工交接 · 2026-10-11（历史）

**用户明确要求停止全部开发/调试，记录需求后交给下一轮继续。六个子 Agent 已关闭，已知验证进程已结束，没有继续构建、安装或执行模型请求。** 保留共享工作区现有修改，不回滚。随后用户提供无线 ADB 配对信息，仅授权并完成手机配对/连接；不据此恢复功能开发。当前没有活动 worker，下文旧章节“正在开发”“主线程继续”“等待 final2”的表述为停工前快照，以本节为准。

后续交付补记：用户随后明确要求“把最新客户端apk发到桌面，然后通过adb发到我的手机”。已将已验证的0.1.18包复制到 `C:\Users\KitamaruRamito\Desktop\Dafeiyu-Go-Mobile-0.1.18.apk` 并核对上述发布SHA-256；指定PKB110无线serial执行 `install -r` 返回Success，包管理器确认versionName0.1.18 / versionCode19，lastUpdateTime为2026-10-11 20:33:28。保留应用数据，没有启动App、重新构建0.1.19或恢复功能开发。文内此前“未安装手机应用”为该次配对时的历史状态，以本交付补记为准。

## 版本与真实证据

- 已交付：0.1.18 / code19；APK 为 `G:\DeepSeek DSH\DSH Works\Project\Dafeiyu-Go\Temp_Android\Dist\Dafeiyu-Go-Mobile-0.1.18.apk`，100,160,952 bytes，SHA-256 `98D38E61F5ADF8A9785EE5CAF3CE4F29C65A8E46E1DECF01FC94173AA45D9B73`。
- 首包 assembleDebug + lintDebug 成功，4m15s，Lint 0 errors / 137 warnings / 5 information；安装至 API37 x86_64 模拟器时保留了应用数据。
- 当前源码：0.1.19 / code20，`tools/build.ps1` 的交付文件名也已递增；**尚未执行 0.1.19 整包构建、安装或设备验收，没有可交付的 0.1.19 APK**。
- 用户已在 0.1.18 实测确认模型真实调用 `web_search` 成功。不能再说“轻引擎只有天气工具，没有通用搜索”；中文搜索相关性/时效性差仍是待优化问题，网络成功不代表结果正确。
- 网页 `browser` 已接 callable 静态读取；正文成功设备专项证据仍未闭环。example.com 返回正文但测试错误断言 h1 位于正文；Kotlin Docs 标题可达、静态正文为空；最新 RFC9110 HTML 被 1 MiB 上限正确拒绝 `web_response_too_large`。最后一次 smoke 是 1/2，不标 PASS，不为通过而放宽上限。日志：`../Temp_Android/qa/emulator-0.1.18/production-web-78509a296b8f4ed9a8a9cb43d1d3bb4f/android-results.log`。
- 协议验证：27 项 focused + 4 项下一用户历史序列化检查通过，均为真实源码配离线注入，不是付费模型端到端验收。
- 活动设备 QA 失败 `No chat scroll surface`，实际失败帧位于 Sidebar，旧脚本缺少场景校验；状态漂移原因未明。finally 已恢复原数据并比较一致，应用当时保持停止。脚本导航修正离线 11 PASS，**没有重跑设备验证**。
- `docs/frontend-build.json` 仍是 0.1.17 历史快照，输入 hash 未更新；不要将它当成 0.1.18/0.1.19 当前证明。图标来源新增 archivebox，来源文件有改动，仍需随下一包核对。

## 用户需求与未完成清单

以下未勾选表示尚未完整交付/验收；注明已有源码时，应先审查接续，避免重写或提前宣称完成。所有组件继续遵循 Apple Liquid Glass 的设计意图，动作顺滑、准确命中，减少动态效果/透明度设置有效。

| 要求 | 停工状态与下一步 |
| --- | --- |
| 流式输出时手动上滑阅读不被强拉到底；输入框右上方出现回底按钮 | `ui/ChatScreen.kt` 已落源码，16 个状态场景及 14 项接线检查通过；0.1.19 未构建。需设备验证惯性、连续增量、输入法、切换会话和回底中再次触摸。 |
| 空工作区可删除，删除选中区后正确切换，重启不复活 | `DemoRepository.kt`、`WorkspaceStore.kt`、`WorkspaceDeletePolicy.kt` 已有改动，Sidebar 已调用 `canDeleteWorkspace/deleteEmptyWorkspace/deleteConversation`；worker 在运行中停止，不能当成最终完成。检查草稿/附件/任务/输出保护与异步重检，外部 SAF 目录不能递归删除。当前保护最后一个工作区；是否以自动创建新空区允许删除最后一个，需下一轮评估用户“空区可删”目标。 |
| 归档用档案盒图标；左滑再左滑删除 | `sf_archive.xml` 已改 archivebox，Sidebar 已加 Closed/Archive/Delete 锚点；第一次停在归档入口，第二次滑动才允许删除。未编译验收，检查锚点更新、revealed 状态竞态、取消动作及精确删除对象。 |
| 左滑归档按钮先轻微放大，再向左拉伸；与主按钮重叠时呈水滴藕断丝连 | Sidebar 已有放大/宽度变化/桥接初稿，视觉未验收；检查桥被前景遮住、位置与真实边缘衔接，不能以常驻线条替代液态效果。 |
| 工作目录按钮椭圆，选中去对号，主题色背景 + 玻璃边框 + 白字 | `WorkspacePill` 已有初稿；未设备验收。检查材质 tint 是否足够呈现主题底色/白字对比，长名与窄屏。 |
| 悬浮圆按钮周围淡黑软阴影；按下像水滴变大，松开顺滑恢复 | 共享材料/形变源码已完成 focused 检查，圆形按下 1.035，阴影 4dp；整包/实际像素和手感待验收。 |
| 删除 Android 默认深色扩散 ripple，改为跟手白色光晕且范围不越按钮边界 | 已有 indication/ripple provider 和有界触点高光；需全入口设备检查，尤其菜单、禁用、取消、多指和越界松手不误触。 |
| 相邻按钮有临时水滴连接，暗色玻璃边缘高光提亮 | `LiquidGlassBridge.kt`、共享材料已落源码；MainActivity 群聊/加号独立形变状态与临时桥刚集成，**尚未编译或 review**。连接只在交互时出现，不改变命中区域。深色、减少透明度和减少动态效果均需实测。 |
| 思考、搜索、再次思考严格按发生顺序排列 | **尚未实现**。现 `AssistantActivityFeed` 先遍历全部 reasoning，再 summary rows，导致几轮思考挤到全部搜索前；应依据真实 `activityOrder`/轮次/事件顺序插入，保持调用 ID 和同轮同类型合并，不跨思考边界合并。 |
| 思考、搜索及其他活动行间距统一，排版紧凑 | **尚未实现/未验收**，不要让思考项明显比搜索项松散。统一前导图标、基线、行高和垂直间距，保留合理点击目标。 |
| 信息条改为紧凑椭圆，速度/存储图标与圆形进度 | **尚未实现**。目标：`【速度图标】3轮23步骤 · 200 t/s  【存储图标】3k/m/b tok · 中 96%  【圆形进度条】15%`。入口 `ui/ChatRunInfoBar.kt`，数据 `data/ChatRunMetrics.kt`；数字须来自真实用量，k/m/b 按数值缩写。“中96%”当前最接近缓存命中率，但不能擅自解释为上下文容量；进度15%的分母尚无已确认来源，未知显示未知，不能造百分比。现 t/s 为整个会话输出 token / 时间（含工具等待），不要称瞬时生成速度。 |
| 回执详情可读、可复制，来源/调用/参数/结果/错误准确 | `AssistantActivityFeed.kt`、`AssistantActivitySummary.kt`、新 `ActivityReceiptDetails.kt` 已有改动；worker 未提交最终冻结结果。搜索“搜索了XXXX”、文件/图片/终端/Skill 图标分类，默认紧凑不展开，必要时详情可查；继续验证取消和失败，不从模型叙述虚构执行。 |
| 子 Agent 结果通知父 Agent 自动继续；同时间同类型任务合并；长任务完成自动折叠只显示结果 | 0.1.18 父模型等待/结果返回、活动合并和自动折叠已有源码与离线证据，用户验收过后台任务/子 Agent；仍需真实多轮完整回归。不能因本批新增详情破坏折叠、父子关联或先提交全部兄弟再等待。 |
| 正文/附件不要求下载 txt，文本可复制、引用；思考默认收起并显示最近一句；流式文字从左到右渐显 | 0.1.18 已接线，保留行为并验证设备复制/引用/动态效果，不重播整段。仅为公开 reasoning_content，不能声称读取隐藏推理。 |

## DeepSeek 反馈中的能力缺口

用户给出的模型回复是反馈材料，不能当成全部需求已实现或实际工具清单的替代证据。下一轮需要核对并明确下面尚未实现的能力，优先完善轻引擎，再接 DSH：

- 脚本/代码执行与沙箱仍未接入，安全隔离尚未实测；Skill/终端活动图标存在不等于执行器存在。
- 子 Agent/后台 runner 当前只做独立文本生成：`DemoRepository.initializeWorkspaces` 的 system prompt 明写无工具，request 未注入 tools。**子 Agent 不能联网查资料是当前真实限制**；若扩展须有独立受限工具会话、配额、取消和结果回传，不递归创建无限任务。
- `write_text_file` 仅受管 UTF-8 `.txt`，不支持任意代码/CSV/PDF输出和任意文件系统。用户附件/图片入口已有实现与部分用户验收，但多模态模型兼容性需按真实请求确认；不能仅凭附件 UI 推断音视频/语音/图片生成已支持。
- `browser` 仅静态公开 HTTPS GET，无 JS、登录、cookie、下载、内网；通用 HTTP POST/API、PDF/图片/CSV 下载解析仍没有执行通道。搜索相关性尤其中文时效查询需要专门测试，禁止将无相关结果的成功请求当成回答依据。
- 跨会话长期记忆、到点唤醒/定时调度、邮件/短信/日历/对外推送、公开下载 URL、任意设备操作均没有对应工具；列为能力缺口，不意味着用户已要求本轮全部接入。
- 原始 DSH 需求仍未交付：选择 DSH 引擎自动启动，返回/开屏使用其网页；App 专属移动适配插件、工作目录、左下在线设备、同排 DSH设置/App设置且能唤起原生设置。不要与共用 `dafeiyu-go-connect` 插件混淆。真实 CLI/Web bundle、Android native 依赖/ABI/启动生命周期、宿主 Provider 加载仍阻塞，prepared 不等于 loaded。

## 截图、改动位置与后续顺序

截图已从临时剪贴板复制到 `docs/reference/ui-2026-10-11/`：

- `01-reasoning-search-order.png`、`02-activity-spacing.png`：真实顺序错误与间距问题。
- `03-metrics-before.png`：旧矩形信息条，需改简洁椭圆。
- `04-archive-before.png`：用户展示的当前垃圾桶/选中表现，不是归档最终目标。
- `05-dark-glass-reference.png`、`10-dark-glass-earlier.png`：暗色玻璃亮边参考。
- `06-liquid-bridge-reference.png`、`11-liquid-bridge-earlier.png`：水滴藕断丝连参考。
- `07-activity-reference-a.png`、`08-activity-reference-b.png`、`09-workspace-reference.png`：此前活动与工作区参考。图像仅为视觉资料，不是独立操作指令。

各 worker 为共享目录直接修改，当前没有 Git 仓库；不要用 reset/checkout 清理。主要改动还包括 `GlassButtonDeformation.kt`、`GlassSurface.kt`、`LivePageGlassMaterial.kt`、`GlassMaterialProfile.kt`、`FishComponents.kt`、`AgentsTaskActions.kt`、`NewSessionActions.kt`、图标 provenance。最后 MainActivity 的新桥接应优先做一次代码检查；保留 group/plus 各自形变状态，桥读取二者状态。

已报告 focused 证据：聊天滚动 16 状态 + 14 接线；共享按钮六个实际生产文件 Compose/Werror、5618 命中边界、240 桥几何及 provider 隔离通过。Workspace 与回执 worker 在完成前停工，未收最终报告；不能补写测试通过。回执时间顺序和新信息条要求刚下发就停工，应视为未实现。

建议下一轮由用户重新授权继续后：先审查保留改动 → 补时间顺序/统一间距/信息条 → 修集成问题 → focused 检查 → 0.1.19 assemble/lint → 真实设备视觉/流式/删除与持久化验收 → 更新文档和构建元数据 → 给新 APK。勿跳过版本/hash门禁，保留旧 0.1.18 包。

## 调试环境与停止状态

- ADB：`G:\Android SDK\platform-tools\adb.exe`；手机配对成功，mDNS 自动连接型号 PKB110，连接端点在本次为 `192.168.5.201:33601`。端口动态，下一轮先 `devices -l`，按确切 serial 操作；配对码不写入文档，**本次只配对，未装包、未启动 App、未读取手机内容**。
- 模拟器原 AVD 为 `Medium_Phone_API_37.0`；`G:\Android SDK\AndroidUser\avd\Medium_Phone.avd\config.ini` 的 `image.sysdir.1` 已改成 `G:\Android SDK\system-images\android-37.0\google_apis_playstore\x86_64\`。上次在线 serial emulator-5554，最新 ADB 只列手机，不能假定模拟器仍在线；Android Studio 旧报错可能保留缓存。
- JDK：`C:\Program Files\Java\JDK21`；后续构建入口 `pwsh -NoProfile -File tools/build.ps1 -TempRoot '../Temp_Android'`（需设置 JAVA_HOME）。这是交接参考，不是本次继续执行指令。
- 设备 QA 修正：`../Temp_Android/qa/emulator-0.1.18/verify-activity-receipts.ps1` 与 `activity-qa-common.ps1`；仍有 0.1.18 版本/hash门禁，不能直接用于 0.1.19。审计/下一包计划见 `tools/tests/pauli-handoff-exclusive-20261011/AUDIT-HANDOFF.md` 与 `QA-0.1.19-PLAN.md`。只用合成数据，写前备份、finally 恢复；不触发用户队列或付费模型。
- 六个子 Agent 均关闭：Mill、Godel、Dirac、Euclid、Popper、Fermat。已知 production smoke 已退出 code1；停止后未再启动测试/编译。SDK ADB 服务因用户配对请求启动并保留连接，不属于继续开发。

---

# Android 交接 · 0.1.18 首包交付 / 0.1.19 开发中（停工前快照）

日期：2026-10-11（Asia/Shanghai）。已交付首包为 **0.1.18 / versionCode19**，已完成构建、Lint、包验证及保留数据安装，已提供用户查看。当前 `app/build.gradle.kts` 已改为 **0.1.19 / versionCode20**，四个 UI worker 与主线程仍在实现第二阶段；源码版本递增不表示新包已交付。实现、离线检查和设备验收分别记录，完整证据见 [0.1.18 记录](docs/frontend-0.1.18.md)。浏览与活动设备 QA 尚未完整通过，0.1.19 等主线程提供 final2 证据后补录。

- 首包构建：`../Temp_Android/build-0.1.18-final3.log`，assembleDebug + lintDebug **BUILD SUCCESSFUL in 4m 15s**；46 tasks，14 executed / 32 up-to-date。Lint XML **0 Error / 137 Warning / 5 Information**，不是零警告。
- 首包产物：`../Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.18.apk`，**100,160,952 bytes**，SHA-256 `98D38E61F5ADF8A9785EE5CAF3CE4F29C65A8E46E1DECF01FC94173AA45D9B73`。包元数据 code19 / 0.1.18 / min30 / target35；主线程报告 apksigner verify 成功、v2 签名，证书 SHA-256 `52fc036fd6fe45d400f354235068aab29cb27ca6f68e42c53368a5b00c887b23`。已安装至 emulator-5554 / API37 / x86_64，保留原应用数据；现有 device evidence 与活动日志关联到同一 APK hash。

- 已接线：同批 `run=true` 子 Agent 先全部提交，再等待结果；成功正文、失败、停止、中断、等待超时分别保留真实状态，结果写入 tool history 后父模型才继续。后台任务保持独立；取消等待不等于取消子任务，应用级 engine 不代表系统杀进程后继续运行。
- 已接线：完成的助手回复支持复制、引用回当前 composer draft 和次级文本导出；多轮 `responseText` 取当前模型轮次，较早正文留在 process，不重复拼接。生成文本文件在 IO 上从受管输出 store 安全读取，默认可读预览、选择/复制、引用文件名与全文回 draft；导出不再是唯一入口。
- 已接线：composer 上方运行信息条显示实际状态、模型轮数、已执行工具步骤、Tokens、缓存比例、耗时及有依据时的 tok/s；未知字段保留未知。活跃计时使用单调时钟，历史保存耗时和计数，不按重启后的时钟重建。`runStatus` 已持久化，工具阶段取消和轮间重启保留真实状态，不把未完成运行误判为 completed。
- 已实现：紧凑后台活动按真实事件/模型轮次归组，关联 parent call 的子 Agent 不重复计数；普通长任务终态整体折叠，完整 process 保留 reasoning 与旧轮正文，允许用户再次展开。compactSkill 指技能活动分类展示，不表示技能/脚本执行器已接入。公开思考默认折叠，标题取最近公开原句；渐显和折叠继续使用共享 GlassMotion，reduce motion 为 0ms，新增操作沿用现有玻璃样式和 48dp 目标。leading glyph 支持最多 3 个 child icon，保持 44dp 前导区域。
- 已报告离线证据：GeneratedFiles/helper/store/contract Compose 严格编译及安全读取 **16/16**；assistant-stream **30/30**；运行信息条/metrics **18/18**；后台任务 **40 项基础 + 9 项等待/递归**；渐显/思考检查含 **7 项原句提取**及布局/Unicode/reduce-motion 边界；Peirce 紧凑活动/完整 process、runStatus 优先级、glyph 上限和 disclosure 有 PASS 日志。分属不同入口和阶段，不汇总为全仓验收；本次文档更新没有重新运行测试。
- 已接线：`web_search` 与 `browser` 已在官方轻引擎真实 callable，DemoRepository 注入 OfficialWebClient，contract/dispatcher/Session 与返回 tool history 链已接通。搜索使用 Bing RSS、DuckDuckGo HTML 回退；浏览读取公开 HTTPS 页面正文、标题和链接，保留最终 URL、时间、截断标记与失败状态，不执行网页脚本。真实来源取工具结果，不从模型正文或思考推断。
- 网络证据：Android shell `app_process` 运行单独编译的生产源码，Bing 检索 `Kotlin official documentation` 返回 3 条真实结果。example.com 返回真实正文，但 QA 错误要求正文含 h1，记录仍为 FAIL；主线程正在改用 Kotlin docs 复测，已出现的 docs 日志仍为 FAIL（标题/最终 URL 有值，text 为空），不预写通过。这是生产源码设备网络证据，不等同最终 APK 应用 UID 或付费模型自动选工具验收。
- 协议证据：focused **27 PASS / 0 FAIL**，真实 parser/dispatcher/Session/reducer 和序列化请求覆盖 search → browser → 后续模型请求；追加 **4 PASS / 0 FAIL** 覆盖下一用户 OfficialChatHistory、精确 ID/参数/结果/公开思考及完整终态筛选。模型与网络响应为离线注入，不能扩写为真实模型端到端通过。
- 活动设备 QA：脚本尚未完成，现有结果为 `FAIL: No chat scroll surface`，主线程报告设置页导航问题正在修脚本；finally 已恢复原 index/recovery/task AtomicFile families 与 output tree，byte compare/cmp verified。数据恢复通过不表示活动 UI QA 通过。
- 仍待：浏览成功复测、活动/回执完整设备 QA、script 沙箱、DSH runtime 与 Android 连接插件实际加载。技能/命令活动图标不代表执行器已接入；搜索与浏览的可调用性已有独立源码证据。
- DSH 只读调查：本地 runtime 仅见 arm64-v8a Node 24.18.0 与依赖，15 个锁定库存在且摘要匹配；未找到真实 CLI/Web bundle、rootfs/proot 或 x86_64 runtime。Termux 固定路径/SONAME 适配、真实 bundle、ABI 与启动生命周期仍是阻塞。准备资产和连接插件 prepared 均不等于 runtime loaded。

本次文档维护只读源码、日志与产物，并用 apply_patch 修改五份 Markdown；未发起 Gradle、ADB、网络或付费模型请求。`docs/frontend-build.json` 与 `docs/icon-provenance.md` 由主线程维护。离线计算、编译和本地 fixture 不证明设备剪贴板/SAF、持续流式或输入法行为。

最新状态见 [分阶段清单](清单.md#最新分阶段清单2026-10-11)：第一阶段搜索/浏览及返回链已实现，0.1.18 首包已 build + install 并给用户查看，浏览/活动专项 QA 待补；第二阶段正在写手动滚动/回底、空 workspace 删除、双阶段左滑归档/删除及正确图标/拉伸连接、椭圆 workspace 选中样式、圆按钮阴影/按压放大/去 ripple/跟手白光与连接、暗玻璃亮边和详细回执页面。0.1.19 实现与验证分开记录，未收到 final2 和专项证据前不勾选完成，不宣告全软件完成。

# Android 交接 · 0.1.17 历史交付与验证

以下为 0.1.17 及更早的历史快照，其中“当前”“最终”、无通用搜索、负责人分工、构建/安装/清理指令仅指当时批次（historical instructions），不作为当前能力结论或本轮操作授权。当前状态以文首“0.1.18 首包 / 0.1.19 开发中”为准；下方 SDK 模拟器调试约定作为一般参考，本次文档任务不执行。

日期：2026-10-11（Asia/Shanghai）。最终交付 **0.1.17 / versionCode18**。本节与 [0.1.17 记录](docs/frontend-0.1.17.md) 覆盖下方 0.1.16 及更早章节中冲突的当前状态；旧章节和设备结果完整保留。短屏几何、菜单互斥等 P2 修复已合入，最终构建、包验证、安装、指定任务菜单 QA、打开浮层时收起 IME 及日志采样均已完成，不扩写为所有场景验收。主 Agent 已更新 `docs/frontend-build.json` 的 version/hash/build/QA，文档冻结后由主 Agent refresh 输入 hash；本次文档任务不修改该文件。

- 已实现：发送候选用户消息在 IO 上原子提交后才调用 provider；persistenceMutex、cancelAndJoin 旧 writer、pending 去重、未保存阻断和重试。UI 等待发送成功，且仅清空同一会话/编辑版本的输入；默认按钮、沙箱和模型控件目标 48dp，群聊按钮尺寸与所列任务导航场景已有模拟器实测，未覆盖的交互不补写通过。
- 已实现：8 MiB 实际序列化索引校验，包括 modelInput 重复和 UTF-8 转义；64 MiB 有界完整恢复 journal，启动读取与重试回收。两处写入都失败时如实报告内存中的未保存内容，不静默裁剪源正文。
- 已实现：附件 metadata-only 启动恢复、选择时读取文本、保留本地文件/图片与托盘、显式删除未使用项并保护已保存和待提交引用。store 总 payload 上限 128 MiB，不表示启动读入同等全文缓存。
- 最新附件修复：Linnaeus 已将历史附件缺 catalog 时的静默丢失改为严格失败，保留草稿。本次回报附件预检 14、chat 37、tools-vision 35 项通过；为离线检查，不证明真实模型调用或图片兼容性。
- 已验证：workspace-storage 21、assistant-stream 21 为前序阶段基线；send-persistence 原 13 项包含预检失败不取消旧 writer/不写索引与 pending 拒绝重复发送，第四参数适配后再次 13/13 通过，日志 `Temp_Android/qa/send-persistence-harness-recheck-20261011-183006`。三个入口阶段结果合计 55 项，不是最终全仓总数；history/图片/schema/校验/provider 是离线替身，不代表真实模型工具链路通过。
- 最终构建已包含：OfficialToolSession 发送调用、图片与历史完整请求提交前预检、ChatMarkdown/GeneratedFileStrip 回复入口、保存/清除 Key 停止后台任务边界。源码和离线检查不代替真实付费端到端生成、全部配置切换、导出或输入法场景验收。
- 主页接线：加号左侧独立 48dp 双气泡 AgentsTaskActions 仅列子 Agents/后台任务，不含 Outputs/Sources；StateFlow + lifecycle 订阅、空状态、滚动、标题省略、对应详情及查看全部任务已接线，复用 AnchoredGlassMenu 形变/预测返回/外部关闭。双向 surfaceVisible + 点击 gate 实现互斥，plus 减少透明度和 tasks route 独立 floatingBackdrop 已接入；回复生成文件保持独立范围。
- 几何与最终模拟器 QA：Franklin 实际生产文件独立 Compose -Werror 编译 + 288 bounded cases 通过。emulator-5554 / API37 安装最终包后，verify-task-menu.ps1 完成分组状态、对应详情 prompt/result、返回主页、加号菜单保持、320x400dp footer 完整可点及全部/后台/子 Agent filter 路由检查；此前 48dp 位置、长标题省略、外部点击与边缘返回关闭、320dp 窄屏已测。四张最终截图已由主 Agent 检查，详情取自实际任务页。
- 最终构建：assembleDebug + lintDebug SUCCESS，3m55s；46 tasks，13 executed / 33 up-to-date。Lint XML 0 Error / 137 Warning / 5 Information，非零警告；4 条 ConfigurationScreenWidthHeight 位于安全窗口尺寸回退。日志 `Temp_Android/build-0.1.17-final.log`。首次 0/135/5 仅为前阶段结果，以本条为准。
- 明确边界：get_weather 使用固定 Open-Meteo 实现；没有通用网页搜索。DSH 移动插件 fixture 存在，runtime 未接线。连接包 0.2.0 当前/上一 slot 为 prepared，可回退；Android 宿主 runtime loaded pending。
- 付费请求与恢复：fixture/模拟器检查未调用模型，真实付费生成、模型触发工具及图片兼容性未因此验收。最终 QA finally 已复制回原 task data、cmp identical、删除 backup/临时 fixture、wm size reset。空状态已在阶段测试检查，不代表最终恢复数据为空；恢复后用户 selftest-A/B 任务保留，final-restored-home.png 是恢复后当前页面截图，包含用户任务菜单，不称纯主页。
- 最终 IME/运行检查：群聊打开前 mInputShown=true / mImeWindowVis=3，打开后 false / 0；final-task-from-ime.xml 有两组与关闭入口。runtime PID15474，AndroidRuntime -d -t 240 -s AndroidRuntime 采样无输出。dumpsys installed v18 / 0.1.17 / min30 / target35；wm 恢复 1080x2400、density420。生产源码 lastwrite 均 <= APK package 时间 18:31:33。IME 结果限于此操作，不代表持续流式设备验收。
- 来源更新：SF 当前记录 41 个 bold 符号，含 trash.bold / sf_trash.xml 与新增 bubble.left.and.bubble.right.bold / sf_groupchat.xml；沿用同一第三方固定数据集来源，[图标来源](docs/icon-provenance.md) 由主 Agent 维护。历史 39 个计数仅属于旧批次。

当前 APK：`G:\DeepSeek DSH\DSH Works\Project\Dafeiyu-Go\Temp_Android\Dist\Dafeiyu-Go-Mobile-0.1.17.apk`，93,408,741 bytes；SHA-256 `75aef8043afe6425b1075b56863579ac0d6ee2d7575ad15e0a83e7b0d8646d2c`。apksigner verify 成功，沿用证书 SHA-256 `52fc036fd6fe45d400f354235068aab29cb27ca6f68e42c53368a5b00c887b23`；aapt 确认 versionCode18 / v0.1.17 / min30 / target35，安装至 emulator-5554 成功。最终 QA 目录 `Temp_Android/qa/emulator-0.1.17`：`verify-task-menu.ps1`、`final-task-open.png`、`final-task-detail.png`、`final-task-short.png`、`final-task-short-all.png`。完整证据与未覆盖范围见 0.1.17 记录。

后续仍按下方当前调试约定使用 SDK ADB 与 Android Studio Emulator，并指定 `-s emulator-*`。0.1.16 主页烟测、旧 hash 和历史手机结果不能替代最终 0.1.17 验收。连接包准备脚本已由 build 在 Gradle 前调用，接受 `-TempRoot`；文档任务不启动 Gradle、设备或真实付费 API。

# Android 交接 · 0.1.16

以下保留 0.1.16 及更早的历史记录；“当前”“已接入”“尚未接入”“最终”等措辞和验证结果均以各节标注版本为限。工具仅提案、附件未发送、旧安装/执行状态等冲突说明以文首最新状态为准。下方调试约定仍可参考，但设备编号与当前批次验证结果需重新确认。

日期：2026-10-11（Asia/Shanghai）。当前版本 **0.1.16 / versionCode17**。本节和 [0.1.16交接记录](docs/frontend-0.1.16.md) 覆盖下方历史版本的材料参数、扫码效果、配对判定和“模型仍为演示”结论。

## 当前调试约定（2026-10-11）

用户已明确要求后续软件调试使用 Android Studio 的 SDK/ADB 与本地模拟器，便于持续调试。默认不再连接或操作用户手机；下方历史记录中的手机地址不是当前默认目标。

- ADB：`G:\Android SDK\platform-tools\adb.exe`。
- Emulator：`G:\Android SDK\emulator\emulator.exe`；AVD：`Medium_Phone_API_37.0`，x86_64、API 37。
- 用户启动入口：Android Studio 右侧 Device Manager，或 `Tools > Device Manager`，点击上述 AVD 的三角形按钮；调试应用时在顶部设备下拉中选择该模拟器，再运行 `app`。
- 硬件加速：AVD 保存 `hw.gpu.enabled=yes`、`hw.gpu.mode=host`；Windows 为 emulator.exe 和 qemu-system-x86_64.exe 保存高性能 GPU 偏好。2026-10-11 重启日志确认 Vulkan 选中 NVIDIA GeForce RTX 3070 Laptop GPU，OpenGL ES Translator 同样使用 RTX 3070；CPU 加速为可用的 WHPX。日志：`G:\Android SDK\Temp\emulator-gpu.stdout.log`。
- 独立模拟器窗口已缩至屏幕可用区域内，避免标题栏在屏幕外；若更换显示器或系统缩放后再次越界，应按当前显示器工作区重新调整窗口，不修改 Android 的逻辑分辨率来规避桌面窗口问题。
- AVD 文件：`G:\Android SDK\AndroidUser\avd`；启动前设置 `ANDROID_HOME=G:\Android SDK`、`ANDROID_USER_HOME=G:\Android SDK\AndroidUser`、`ANDROID_AVD_HOME=G:\Android SDK\AndroidUser\avd`。
- 先用上述 ADB 执行 `devices -l`，确认当前在线的 `emulator-*` 编号；每条安装、启动、截图、日志与交互命令都指定 `-s <模拟器编号>`。编号可能改变，不能默认选择列表第一台设备。
- 安装使用 `install -r` 保留应用数据；包名 `com.dafeiyu.mobile.preview`，启动组件 `com.dafeiyu.mobile.preview/com.dafeiyu.mobile.MainActivity`。
- Android Studio 的 Run、Device Manager 和 Running Devices 与命令行共用 ADB；Android Studio 并不替代 ADB。本约定切换的是 SDK 中的 ADB 路径和调试目标，编译仍使用现有 API 35 配置。
- 模拟器用于界面、导航、键盘、流式状态、安装及崩溃检查；实物相机、扫码质量、触感和真实手机性能需另行真机验证，不把模拟器证据写成真机验收。

Android Studio 安装、配置、缓存、插件、日志、Gradle 和 AVD 已迁至 `G:\Android SDK`；旧目录通过 junction 兼容。启动入口为 `G:\Android SDK\Start-AndroidStudio.ps1`，迁移审计保存在 `G:\Android SDK\MigrationLogs`。本机 HTTP 代理为 `127.0.0.1:7890`，无需用户名或密码。

2026-10-11 使用 SDK ADB 在 `emulator-5554` 上完成最终 0.1.16/code17 安装，包管理器核对 min30/target35、x86_64。host 独显模式冷启动后再次成功打开应用，SurfaceFlinger 确认 NVIDIA OpenGL ES Translator，应用日志确认 AGSL lens 编译成功；最近 300 条按应用 PID 采样的日志未发现 FATAL EXCEPTION 或 ANR。主页截图：`Temp_Android/qa/emulator-0.1.16/home.png`。本次仅为安装、启动和主页烟测，键盘流式、菜单跨窗口与扫码仍待专项验证；模拟器证据不替代真机验收。

## 0.1.16 当前变更

- 玻璃保留现有窄边折射、RGB 色散和方向高光，增加均匀的低比例覆盖色保障可读性；没有恢复宽内发光。镜头按钮共用材料与原生折射管线，压暗只施加一次。
- 加号到 X 旋转与展开共用动画进度，菜单向左下展开；新建会话、扫码、配对码按顺序排列，没有描述。配对码只有收到当前节点/群组的已验证成员响应才隐藏。
- 主页和助手标识改用 DeepSeek 官方 SVG 中的鲸鱼路径；应用图标不变，来源见 `docs/icon-provenance.md`。
- 官方轻引擎接入 DeepSeek `/models` 和 `/chat/completions`；用户显式保存 Key、获取模型、选择并发送文本。支持流式/非流式、公开 `reasoning_content`、用量、停止与错误状态。服务端工具调用仅保存为待执行提案，未加入执行器。
- 凭据使用 Android Keystore AES/GCM、系统生成 IV 和 AtomicFile；保留 v1 文件兼容，解密不会创建遗失的密钥，Key 不参与备份。
- 下一轮上下文只包含完整、成功的 BACKEND 用户/正文对和当前输入，排除公开思考、演示、失败、取消、截断与工具调用。
- 离线测试和设备验证的实际结果以当前交接记录与 `docs/frontend-build.json` 为准。没有使用真实 Key 发起付费模型调用。
- 清单剩余重点：DSH RPC/真实运行环境、附件传输、工具/子 Agent/搜索执行器、Aries 审批与密钥投递、双设备工作区同步、Android 更新协议。

## 当前功能接入状态（0.1.16 交接基线）

以下状态优先于本文后面的历史批次记录。标记含义：

- ✅ 已接入：源码、构建或离线测试已有证据。
- 🟡 已实现但待验收：页面或数据层已接入，但依赖真实账号、真实服务、用户手感或专项设备测试。
- ⬜ 尚未接入：当前仍是演示、只读、提案保存或明确的空状态。

### 已接入并有证据

- ✅ **液态玻璃界面**：主页、侧栏、设置过渡、浮动菜单、输入区域和按钮使用 QWEA0 原生 AGSL/RenderEffect 管线；保留窄边折射、色散、方向高光和背景采样，使用均匀低比例覆盖色改善可读性，没有恢复宽内发光。扫码按钮使用启用折射的 `MediaButton` profile，相机缩略图作为采样源，压暗只施加一次；旧版本关闭扫码折射的说明已失效。
- ✅ **主页与导航**：右上加号按同一进度旋转为 X，快捷项向左下展开，顺序为新建会话、扫码、配对码；已配对状态只由当前 endpoint/node/group/device 的签名成员证据决定。主页和助手使用 DeepSeek 官方鲸鱼路径；关于页使用应用图标。
- ✅ **页面形变与返回**：设置从点击的单行胶囊展开，返回收回原行；侧栏进入设置保留模糊背景和页面交叉渐变；模型、沙箱和快捷菜单共用来源轮廓、预测返回和末段交接。有效点击在 `UP` 松手确认触感，拖动和取消不追加震动。
- ✅ **本地工作区与会话**：`WorkspaceStore` 使用 AtomicFile、Mutex 和防抖保存本地工作区索引；默认目录位于应用私有目录，限制工作区、会话和索引大小。标题会在模型首次回执后由首段用户输入生成主题；输入草稿按会话保留。外部工作区目前只保存 SAF 授权引用。
- ✅ **官方轻引擎文本链路**：保存/清除 DeepSeek Key、读取服务端 `/models`、选择真实模型、发送文本到 `/chat/completions`，支持流式和非流式正文、公开 `reasoning_content`、用量、停止、取消、HTTP/解析/截断/不完整 EOF 错误。未知模型遵循服务端默认思考策略，不猜测模型能力。
- ✅ **输出活动显示模型**：正文、公开思考、命令/工具/子 Agent/搜索/引用活动使用可折叠状态模型并保存快照；官方引擎设置支持显示思考链且默认展开。当前协议层可以保存服务端工具调用提案，但未执行提案。
- ✅ **安全存储**：DeepSeek Key 和 Aries 身份使用 Android Keystore AES/GCM、系统生成 IV、AtomicFile 和并发锁；Key 位于不参与备份的私有目录。缺少密钥或数据损坏时保留原数据并报告错误，不生成替代身份。
- ✅ **Aries 基础协议**：私有 CA/TLS/hostname 校验、healthz/node、Ed25519 身份、challenge/enroll、设备签名 heartbeat、邀请查询、`invite_code`/二维码解析、node_id 校验、请求 ID 和严格响应校验已接入。设备注册和入组申请不会伪装成已完成配对。
- ✅ **在线设备统计**：DeveloperCenter `/api/presence`、历史采样和服务器 metrics 数据层已接入；前台匿名 heartbeat 使用持久化 installation UUID，每 60 秒最多一次。可展示 PC 与移动端合计人数；旧 PC-only 降级响应时保持未知，不伪造移动端人数。
- ✅ **设置数据层**：引擎、连接模式、通知四项、主题/主题色、材质、页面切换、按钮动效、字体/字号/字重、界面缩放、下载源、同步、网页广播、更新和缓存等偏好使用应用私有存储，重启后恢复。
- ✅ **设置辅助功能**：权限页四组 16 项、通知/个性化/模型/插件/技能/虚拟机/组件/缓存/备份/更新/反馈/关于入口已接入；缓存白名单和统计、过期清理、活动附件保护、失败明细，以及工作区/会话/消息备份快照、SAF 导入导出和严格恢复校验已实现。
- ✅ **扫码和附件入口**：CameraX 取景、ImageAnalysis、ZXing 本地解码、相册、灯光状态和文件/照片选择器已接入；二维码内容只展示，不自动执行链接。附件 URI 可进入草稿边界，但当前还未上传模型。

### 已实现但需要专项验收

- 🟡 **真实 DeepSeek 账号链路**：模型目录、文本生成、余额查询和公开思考的传输实现已完成；没有读取用户 Key，也没有发起付费调用，需要使用用户自己的账号在模拟器或真机上验收模型兼容性、余额和长时间流式输出。
- 🟡 **输入法与流式布局**：源码已按输入法打开时的布局变化处理消息流状态，模拟器中已完成安装、启动和主页烟测；键盘打开后持续流式回复是否仍抖动、抽搐，需要单独输入长文本并持续观察。
- 🟡 **快捷菜单和跨窗口玻璃**：菜单来源层、根页面模态模糊和 Popup 坐标接缝已修复；仍需逐项验收加号展开、关闭、再次打开、配对码隐藏、设置收回和多次旋转动画。
- 🟡 **扫码页**：材质和相机缩略图管线已完成，待验证实际扫码成功率、暗色对比度、灯光、相册识别、长时间预览和 CameraX 实际帧率。请求 60fps 不等于设备一定显示 60fps。
- 🟡 **设置与数据页面手感**：NestedScroll 分区吸附、字体/字重/缩放、浅深色对比度、液态玻璃强度、缓存长时清理、备份真实文件选择器和反馈网络状态仍需用户体验验收。
- 🟡 **Aries 真实双设备流程**：节点检查、身份、登记、heartbeat、邀请解析和入组申请已接入；密钥投递/确认与双设备传输仍需完成应用接线及真实服务端端到端验收。仓库中的 `AriesConnectionProvider` 已包含申请保存、投递查询/确认、消息与文件传输、缓存清理方法；`LocalPluginBridge` 已包含私有 socket、UID/PID、capability 和 nonce 校验。当前未发现主应用实例化这些桥接入口，不能将其记为用户流程已接通。
- 🟡 **服务器数据页面**：在线人数、历史采样、版本分布和资源指标数据层已接入；持续刷新、网络失败重试和真实服务端数据质量仍待验收。
- 🟡 **Android Studio 模拟器验证范围**：`Medium_Phone_API_37.0`、API 37、x86_64、host GPU 和 WHPX 已确认；模拟器不能证明实物相机、触感、真实手机性能和不同厂商输入法行为。

### 尚未接入或明确关闭

- ⬜ **DSH 后端**：DSH 模型目录、提供商凭据、排序同步、RPC、本地运行环境和真实模型执行仍未接入，DSH 路径保持明确的本地演示。
- ⬜ **工具执行器**：运行命令、子 Agent 调度和搜索执行器尚未接入；当前只解析/保存 READY 工具提案，不声明可用工具，不执行命令，不产生虚假搜索结果。
- ⬜ **附件传输**：照片、文件和相机内容尚未编码并发送到模型；检测到附件时保留草稿并提示，避免无提示丢失。
- ⬜ **Aries 写操作后半段**：成员审批、密钥投递/确认、双设备文件传输、工作区同步、网页广播和缓存清理尚未接通完整用户流程。现有页面读取群组 applications/members/presence/file-transfer cache；`AriesConnectionProvider` 的写操作基础代码需要继续接线与验证，接手者应复用并审查现有实现。
- ⬜ **Android 应用更新**：Android 专用 manifest、APK 下载、大小/版本/SHA-256/签名校验和系统安装确认尚未接入。Windows DSH bundle manifest 与 Android APK 更新严格分开。
- ⬜ **插件与技能运行**：现有设置页面扫描应用私有目录并保存启用状态。仓库另有 `ConnectionPluginPackage` 包验证及 `LocalPluginBridge` 基础代码，尚未完成实际安装、更新、执行与受限沙箱的用户流程验收，不能视为功能完成。
- ⬜ **系统通知主动推送**：通知渠道状态和设置入口已接入；公告推送、连接设备推送和 Live Notification 尚未完成。
- ⬜ **真实账号数据展示**：用量页读取本地 DSH `usage.jsonl`，公告页读取校验缓存；无数据时不显示固定示例。余额/Token/公告的真实账户或服务数据验收仍待账号和服务条件。

### 当前交付物与验证记录

- APK：`G:\DeepSeek DSH\DSH Works\Project\Dafeiyu-Go\Temp_Android\Dist\Dafeiyu-Go-Mobile-0.1.16.apk`
- 版本：`0.1.16` / `versionCode 17` / 包名 `com.dafeiyu.mobile.preview` / minSdk 30 / targetSdk 35；本次模拟器验证的进程 ABI 为 x86_64。
- SHA-256：`df6c2cd4c707c676e1819b773039cd9e3566d24d0361fec996ef7b62dd517353`。
- 构建：`assembleDebug` 和 `lintDebug` 通过；Lint 0 errors、133 warnings、5 information。
- 离线测试：assistant stream 21 项、workspace presence 31 项、DeepSeek protocol 36 项、Aries protocol 32 项、Aries identity 11 项，以及成员/群组 fixture 测试通过。
- 模拟器：使用 `G:\Android SDK\platform-tools\adb.exe` 安装到 `Medium_Phone_API_37.0`，本次设备号为 `emulator-5554`；冷启动和应用 PID 日志通过，最近 300 条应用日志未发现 FATAL EXCEPTION 或 ANR，AGSL lens 编译成功。
- 图形：AVD `hw.gpu.mode=host`；Vulkan、OpenGL ES Translator 和启动日志均确认使用 NVIDIA GeForce RTX 3070 Laptop GPU，WHPX 可用。
- 主页截图：`G:\DeepSeek DSH\DSH Works\Project\Dafeiyu-Go\Temp_Android\qa\emulator-0.1.16\home.png`。该截图只证明主页显示，不代表键盘流式、菜单、扫码或真机验收。
- 详细版本记录：`docs/frontend-0.1.16.md`；构建和输入指纹：`docs/frontend-build.json`；清单逐项状态：`清单.md`。

## 历史交接 · 0.1.14

日期：2026-10-11（Asia/Shanghai）。当前构建交付 **0.1.14 / versionCode15**，仍只做前端。真实DSH、模型服务、设备配对和沙箱执行权限没有接入。本交接记录最终保留的行为，覆盖此前中途试验参数；接手时以此为准。

## 当前交付

`G:\DeepSeek DSH\DSH Works\Project\Dafeiyu-Go\Temp_Android\Dist\Dafeiyu-Go-Mobile-0.1.14-frontend.apk`

- 大小31,909,460字节；SHA256 `9aaf91f9aa323aa87084a3ec63cca1ab9680abb1d6529f57d88f6517ebd596fd`。
- 包名com.dafeiyu.mobile.preview；min30/target35；签名沿用此前Debug证书。
- aassembleDebug/lintDebug通过；Lint **0错误、48 Warning、4 Information**。
- 已将0.1.11/code12安装到PKB110（ADB设备192.168.5.201:39871）；包管理器已核对版本。本次没有启动应用，扫码及图标运行效果仍待验收。
- 最终构建日志 `Temp_Android/build-0.1.14-final.log`，107项编译输入指纹与产物元数据见 `docs/frontend-build.json`。
- 扫码页保持无AGSL折射，现以约30Hz、最长边96px的YUV缩略图驱动模糊背景，并叠加低成本玻璃高光；Preview仍请求60fps，未测得真实显示帧率。
- 设置首页已按新大纲重置为“首页总览 + 引擎/线上/常规/模型/环境/数据/支持”板块；新入口先以 Android 前端预览承载，后端未接入。
- 在线服务二级页已加入：连接状态、双设备信息、本机系统资源概况、配对码预览、Aries节点字段、缓存统计字段、同步工作区、网页广播与下载源；均明确标注前端预览。
- 启动器使用用户提供的4096×4096图像，经Lanczos缩至512×512；无裁切、无额外图像滤镜。

## 最新用户确认的交互

1. 设置浅色背景#F2F2F7，组件#FFFFFF；暗色背景#000000，组件#1C1C1E。设置项/选择行不拖拽，点击松手才确认触感。
2. 设置详情从**点击的单行**实际Rect展开。该行先呈胶囊轮廓，中心移动途中左右/上下边缘同步扩大；不是整组设置卡片、没有居中停顿。其他行保留在后面模糊，目的页内容只在扩大轮廓内显现。关闭/预见式返回缩回同一行。
3. 设置首页返回缩回主页左上48dp圆按钮。左侧栏进入设置的特殊接缝必须保留：**侧栏缩回圆形时，背景仍模糊，主页同时渐变到设置页，再恢复清晰**；不能先让主页清晰一帧再跳设置。
4. 所有形变复用GlassMotion：EaseOut(.16,1,.3,1)，MorphMillis380；centerProgress=p+.12*p*(1-p)，sizeProgress=p。曾试验520ms，已按用户纠正退回380ms。圆按钮按住缩小至.95，松手缓出归位；拖拽仍径向阻尼6dp，设置项不套拖拽。
5. 触感已从“完全展开时震动”改为**有效点击的UP松手确认**；拖动/取消不震，嵌套helper按UP时间/指针去重，遵循系统触感设置。不能声称一定能识别马达类型或用户已主观验收手感。
6. 模型、沙箱、右上加号菜单共用AnchoredGlassMenu的来源轮廓、中心/尺寸轨迹、预测返回与末段交接。关闭最后8%淡出轮廓，原入口逐渐接替，Popup多保留透明交接帧。
7. 加号右侧新增36dp无背景沙箱图标；只读/工作区内修改/完全权限，默认工作区内修改，rememberSaveable仅记录前端选择。不授予系统权限，不执行文件操作。
8. 右上菜单新增配对码输入预览；扫码图标采用现有SF数据中的viewfinder。配对码不发送给服务、不连接电脑。
9. 主页与设置页顶部都有实际页面渐变模糊，覆盖状态栏；底部反向渐变延展到导航手势条，平台system bar颜色透明，禁用系统额外实色contrast遮罩。扫码页自己处理安全边距与浮动操作。
10. 侧栏当前选中的工作区与对话行都使用亮蓝胶囊按钮外观、细白高光；图标及文字白色，工作区副标题也保证对比度。未选中的行维持原样，工作区用文件夹图标。

## 实时扫码与性能修复

ScannerScreen使用CameraX1.4.2 `PreviewView.PERFORMANCE` + `ImageAnalysis`，ZXing3.5.3本机解码。预览偏好960×720，分析偏好640×480，均为4:3；解码仍约5Hz、只保留最新帧。相机AE范围支持时向Preview请求60fps，绑定失败时仅解绑本页use cases并回退默认帧率。CameraX文档说明该值是请求，受设备与并发use case影响，不能当成实际显示帧率；日志测的是分析帧时间戳交付率，不是Preview屏幕帧率。

扫码页材质profile关闭AGSL折射与shader编译，按钮采用RenderEffect模糊、轻tint、细轮廓和低成本方向高光。移除TextureView.getBitmap主线程读回：只在分析线程每约33ms从YUV_420_888生成最长边96px的复用相机缩略图（目标最多30Hz），再安全交给主线程更新独立leaf。每个ImageProxy仍在finally关闭，crop/rowStride/pixelStride/rotation/边界检查和本机QR解码保留。API30没有RenderEffect时，blur-only玻璃使用低分辨率重采样作为柔化回退；API31+使用硬件模糊。识别内容只展示，不执行链接；灯光读真实torchState，无闪光灯时禁用；离页只解绑本页use cases，不调用unbindAll。

这组改动减少了相机预览面的读回及折射开销，并对60fps作出有条件请求；**尚未完成真机相机帧率、视觉叠层、扫码成功率与长时稳定性验收**，不能声称设备已稳定显示60fps。

## 材质最终状态与两份新参考

仍采用QWEA0/Liquid-Glass-Android原生AGSL/RenderEffect，未引入WebView/JS/SVG渲染器。曾按childrentime参考试验低位移/色散参数，用户要求回退后，已恢复原QWEA0式法线折射位移和色散。当前新增的是来源层一致性、清晰的方向轮廓与性能修复，不能写成已完整移植childrentime或shuding。

用户指出菜单背景与外部模糊层不搭：根页面已压暗/模糊，但菜单取了处理前正文。因此新FloatingBackdropSource只读取独立页面GraphicsLayer，再施加与外部一致的模态模糊/压暗，通过单独leaf给跨窗口Popup按屏幕位置取样。不是捕获包含菜单的祖先。移除了capture.revision到根invalidations的反馈链，页面按需更新。

材质profile都使用3.5dp材料模糊；菜单tint最终.55，外层模态模糊最大2.8dp、压暗.12。中途18dp/.84~.86实验值已撤回。主页模态effect预缓存分档，轮廓动画只更新layout/layer，菜单文字按最终尺寸测量，避免每帧重组列表或编译shader。

按钮轮廓按上游同一SDF法线驱动迎光/背光双瓣；普通玻璃有效亮边宽度约1.2dp，迎光侧向内柔光。按钮profile浅色边缩至约0.86dp并略提亮，中心极轻微压暗以强调轮廓；暗色边缩至约0.78dp，不追加中心暗化。API33使用光场时不叠加常亮整圈描边，降级边线依按钮profile约0.86/0.78dp、普通玻璃约1.15dp。用户请求白色柔光通过迎光侧glow实现，不是整块白色底。

材料参考比较见 `docs/glass-material-0.1.9.md`。childrentime固定345a3c4f…的README声明MIT但缺完整LICENSE；shuding固定a2d2e847…有MIT Copyright(c)2025 Shu Ding。shuding普通玻璃用CPU位移图+SVG feDisplacementMap，默认图可缓存；diamond多次ray bounce不作为手机UI候选。两者均未将源码/JS渲染管线嵌入应用。

## 文件和接线

- MainActivity：双层设置过渡、独立页面chrome、模态背景、侧栏到设置crossfade、预测返回；transferringSettings/settingsTransfer不得移除。
- SettingsPageTransition：单行来源、真实胶囊半径、源内容小块裁取、独立页面blur/alpha交替；来源和目的页过渡期触摸阻断。动画progress在draw/layer读，blur0/2/4/6/8dp缓存。
- AnchoredGlassMenu：模型/沙箱/右上菜单的公共Popup生命周期和轮廓；最终内容固定测量，动画geometry在layout读。
- GlassButtonDeformation/GlassMotion/GlassHaptics：统一手势、圆形命中、按压缩小、径向阻尼与松手触感。
- GlassMaterialProfile/GlassSurface/LivePageGlassMaterial：跨窗背景bridge、材料profile、AGSL与系统降级；采样源必须不含当前材料自身。
- CameraQrScanner/ScannerScreen：实时相机、图像分析、本机QR、玻璃缓存、灯光/相册。
- PairingScreen/SandboxMenu：前端选择/输入预览，后端未接。

图标已扩展为39个原SF路径转换资源，包括锁/解锁、folder.badge.gearshape、viewfinder、灯光和number.square。来源仍UsePreside/sf-symbols第三方转换数据，非Apple官方仓库，无明确资产再分发许可；来源hash和NOTICE保留，不自绘按钮图标。取景方框是扫描引导几何，区别于控件图标。

## 证据和未验证部分

Temp_Android/qa/phone-0.1.9包含home-contour-final、sandbox、plus-material、appearance-current等截图。截图证明原生页面可用与控件布局，但旧中途材质截图不能证明最终参数。尝试录制sidebar-to-settings时手机同时在使用，部分录制/坐标操作被干扰，不作为完整过渡验收。QA脚本会检查前台包，非大肥鱼即跳过点击/截图；没有更改其他app配置。

旧gfxinfo和新抽样不在相同负载/操作条件，不提供“卡顿下降百分比”结论。最终暗色轮廓、相机性能、完整预测返回取消、触感手感、所有动画帧率仍需用户预览和后续控制条件测试。已核对源码/构建/签名/最终包版本，未读取用户其他app数据作为项目证据。

没有Git提交/推送/发布，未改桌面/Aries/真实DSH配置。所有工具链、下载、日志、APK和QA留Temp_Android。此前自动审批拒绝的临时截图清理未绕过，本轮不重试已拒绝删除。

## 文档入口与版本规则

版本按用户要求继续使用0.1.x修订号。本轮为0.1.14/code15，未采用0.2.0。下列文档均以当前源码与构建记录为依据：

- [构建清单和107项编译输入指纹](docs/frontend-build.json)
- [0.1.14单独版本记录](docs/frontend-0.1.13.md)
- [历史0.1.12版本记录](docs/frontend-0.1.12.md)
- [历史0.1.11版本记录](docs/frontend-0.1.11.md)
- [历史0.1.10版本记录](docs/frontend-0.1.10.md)
- [历史0.1.9版本记录](docs/frontend-0.1.9.md)
- [共享交互组件用法](docs/glass-interactions.md)
- [本轮玻璃材料研究比较](docs/glass-material-0.1.9.md)
- [QWEA0实现与许可](docs/liquid-glass-implementation.md)
- [图标来源](docs/icon-provenance.md)
- [权限接口边界](docs/permissions.md)
- [模型推理选项示例](docs/reasoning-preview.md)
- [历史0.1.8验证](docs/frontend-0.1.8.md)

历史文档中的spring、520ms时长、整组卡片来源、展开完成震动、相机全尺寸截图、中途18dp/.84~.86菜单材料均已被本轮最终规则覆盖，不能重新当成当前要求。扫码相机当前移除TextureView读回并使用ImageAnalysis缩略图；旧750ms采样实现不再适用。

## 前端数据和后端接手

轻量聊天、工作目录与会话数据通过DemoRepository管理。默认私有目录 `filesDir/Dafeiyu-Works/默认工作区`，WorkspaceStore用AtomicFile保存workspace-index.json，IO经Mutex串行，180ms防抖；上限200个工作目录、100个会话、索引8MiB。输入草稿按会话ID由Compose保留，不等同跨进程落盘；附件URI、API Key不写入索引。

外部工作目录目前只保存SAF授权引用，不解析外部DSH会话、不写入用户目录。照片/文件选择与相机预览真实接线；内容不发送模型，不自动执行二维码URL。通知/相机按功能请求，电池入口交给系统；Shizuku/root只是可用线索检测。模型、运行、流式回复与用量数据仍为模拟。配对码和沙箱档位只是前端选择，不改变操作系统或DSH执行权限。

后端应替换模拟调用而保留前端状态契约。首要目标仍是手机本地DSH；电脑连接后续复用 `Dafeiyu-GO-Aries`，不在本轮接入。桌面功能迁移和DSHA静态研究保存在docs/research；两模式下拉已撤下，不应因历史研究重新显示。

## 关键状态和不可遗漏的接缝

- **侧栏进入设置**：MainActivity中的 `transferringSettings`、`settingsTransfer` 与 SettingsPageTransition 的 `crossfadeProgress` 必须一起保留。侧栏关闭与交叉渐变并行，源主页保持模糊，设置页随交接恢复清晰。撤回渲染试验不等于撤回这段交接。
- **设置详情**：`sourceBoundsInWindow`只取 `clickedRowInWindow`；`sourceGroupInWindow`仅为元数据。胶囊半径来自单行高度的一半，明确的主页圆按钮24dp半径优先。不能再以整组列表为来源。
- **共享轨迹**：中心和尺寸从同一进度开始变化。没有先居中、没有delay停顿；速度已恢复380ms，改善卡顿要减少绘制/重组，不要单纯拉长时长。
- **光学与背景**：保留QWEA0原生方案；Popup的背景来源必须包含外侧同样的模态blur/dim，不得继承错误的toolbar窗口坐标，也不得捕获菜单自己。
- **触感**：只在有效松手确认，取消/拖动不震；不要在动画展开完成后追加第二次震动。
- **相机缓存**：保留384px复用缓存和超时降级，不恢复每200ms全尺寸PreviewView.bitmap。
- **过渡期输入**：隐藏源页不能点；目的页未完全展开时阻断点击；系统预见式返回由根控制，取消时归位到当前层级。

## 构建与交付命令

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\JDK21'
.\tools\build.ps1
```

脚本设置Temp_Android下的TMP、Gradle缓存、Android用户目录、项目缓存和build输出，运行aassembleDebug/lintDebug后复制APK和.sha256到Dist。修改版本需同步app/build.gradle.kts、设置关于页、tools/build.ps1产物名称及文档。

历史版本使用 `Temp_Android/toolchains/android-sdk/platform-tools/adb.exe` 和手机 `192.168.5.201:39871`。当前调试目标和 ADB 路径以本文顶部“当前调试约定”为准。启动component必须是 `com.dafeiyu.mobile.preview/com.dafeiyu.mobile.MainActivity`，不是preview.MainActivity；每个命令指定设备，日志按本应用PID。不得改动全局ADB服务或其他应用设置。

交付前应核对APK版本/签名/hash、刷新frontend-build.json源码指纹，再分开记录代码检查、实际真机证据和未测试边界。本轮0.1.14已核对最新 APK hash/签名与编译输入指纹，并再次安装到 PKB110；设置页面、配对页和无截图烟测已完成，Aries 审批/密钥投递、Android 更新服务和最终界面验收仍待后续。



## 2026-10-11 无人值守增量（设置清单第二批）

- 当前 APK 仍为 `0.1.14 / versionCode15`，最新 SHA256：`376f3768cb0560c5e031a6d8ea7251b3d38f5fe9fc04ef4b7f3386069b07432b`。
- 已通过 `aassembleDebug` 与 `lintDebug`；最新包已安装并启动于 `192.168.5.201:39871`，包进程存活，最近 300 条 logcat 未发现 FATAL、ANR 或进程退出。
- 个性化新增主题色预设并接入 `FishTheme`；通知页接 Android 系统通知授权与设置入口；缓存/备份/更新页补充错误与本地状态展示。
- Aries 配对页已接入二维码/配对码解析、节点与邀请验证、challenge/enroll、设备签名 heartbeat 和 applyInvite；审批、密钥投递/确认、最终成员 presence 仍待服务端流程。
- Aries 协议审计见 `docs/research/mobile-aries-pairing.md`；OnlineContent 已改为使用保存 endpoint，但 DeveloperCenter presence 仍不能当作 Aries 群组 presence。
- 项目内 `tmp/` 临时截图、UI XML 和图像已清理；构建缓存、日志与 APK 仍保留在父目录 `Temp_Android`。


## 2026-10-11 无人值守增量（设置清单第三批）

- Aries 配对结果现在写入 endpoint、node_id、server_code、device_id 和连接状态；二维码 node_id 必须与服务端 /node 返回值一致。
- AriesClient 增加 X-Request-ID/error.request_id 诊断保留、challenge nonce 32 字节规范校验、enroll status/device_id 严格校验。
- 模型目录排序/启用状态、虚拟机档位/硬件加速/后台策略已通过 SettingsRuntimeStore 持久化并由设置页面调用。
- 本批 aassembleDebug 与 lintDebug 通过；APK 已安装启动于 PKB110，进程存活，最近 500 条 logcat 无 FATAL、ANR 或进程退出。
- 最新 APK SHA256：57a2ac5d6136aeac06e54ca29640523aa0755d362660756b08040ae8fb96369b。




## 2026-10-11 无人值守增量（设置清单第四批）

- 用量页读取应用私有 DSH `usage.jsonl`，公告页读取校验公告缓存；缺少数据时不使用固定示例。
- Android 通知渠道状态数据层已接入，但没有主动发送通知或实现伪 Live Notification。
- Aries 群组只读查询已接入 applications、members、presence 和 file-transfer cache；审批、密钥投递、清理写操作仍关闭。
- 在线服务页在完成配对后读取群组在线成员、缓存统计和本机心跳。
- 最新 APK SHA256：`2af3d7648616cf0fcc010306525da8f59a1a37aa7969f15c78e17c3beee766b0`；已通过 aassembleDebug/lintDebug 并安装启动。


## 2026-10-11 无人值守增量（设置清单第十一批）

- 反馈页面已接入支持/取消支持、补充反馈和封禁状态查询；公告页已接入 DeveloperCenter feed 和指标回执。
- 字体设置已拆成独立详情入口，继续只改变应用内设置，不安装系统字体。
- 最新 APK `0.1.14 / versionCode15`，SHA256 `12c46df7229b33f96585127d9cd4dfa24035771183a59b883010d988c59fbd5d`，已安装启动并通过 aassembleDebug/lintDebug。
