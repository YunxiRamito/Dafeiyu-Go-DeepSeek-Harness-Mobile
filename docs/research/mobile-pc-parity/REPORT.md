# 移动端与 PC 对齐研究

日期：2026-10-12，Asia/Shanghai。GitHub 与官方文档访问均使用本机 `http://127.0.0.1:7890`。研究完成不代表 Android 运行、后端部署或设备验收通过。

## 模型与运行组件

- 官方 DeepSeek `/models` 和 Chat Completion 文档实读：`effort.supported_levels` 为开启思考时的推荐顺序，`default_level` 必须属于列表；`none` 为独立关闭值。生产解析器、缓存、菜单和请求均保留真实能力。没有能力的模型不补造档位。来源：https://api-docs.deepseek.com/api/list-models 、https://api-docs.deepseek.com/api/create-chat-completion 。
- `earendil-works/pi` 的 `@earendil-works/pi-ai` v0.87.1，固定 commit `f07218c4d4bbc12bef056a7058c3dd49dfe41abe`，MIT，Node >=22.19.0。实际读取 README、LICENSE、目录、credential store、lazy adapters 源码；使用独立 Node worker 复用十协议及原认证，Android 保留权限和工具调度。https://github.com/earendil-works/pi/tree/f07218c4d4bbc12bef056a7058c3dd49dfe41abe 。
- 既有 Ubuntu/PRoot 资源和许可证继续见 `third_party/linux-runtime/`。PRoot 共享 Android 内核，不将它描述为独立内核虚拟机或安全沙箱。完整内核 VM 仍须提交真实探测结果后由用户确认。

## WebView 当前会话的安全初始化

2026-10-13，r12 真 Android 主帧返回 HTTP 403；固定 first-party DSH 根认证仅返回 303/401，产品非同源 request 拦截返回 403。旧 `loadDataWithBaseURL` 后脚本重定向的合成导航不作为实际认证网页的文档开始钩子继续使用。保留精确 loopback Host/Origin 和资源拦截，改为原生 `loadUrl` 完成 Cookie 交换；用 AndroidX 的正式 document-start API 在 first-party store 运行前设置选中会话。

经 7890 实读 GitHub `androidx/androidx` 固定 commit `078de553b861299af03d7a548377cc1307db776d` 的 README.md、Apache-2.0 LICENSE.txt、`webkit/webkit/src/main/java/androidx/webkit/WebViewCompat.java`。依赖固定为 Google Maven `androidx.webkit:webkit:1.14.0`，实际该版本 sources.jar **SHA-256 `714bc9cebdba29d5e336e026367c50ff235bd0090e1949efcbd81537a213176e`**；另实读其 WebViewCompat/WebViewFeature/ScriptHandler，确认注入在页面 JavaScript 前、需在 loadUrl 前注册、必须检查 DOCUMENT_START_SCRIPT，且支持精确 origin 规则。GitHub commit 是审查参照，不冒充 1.14.0 的发行 commit。未运行上游脚本或复制实现。官方参考页下载超过 35 秒超时，记录为真实失败，未将部分 HTML 当作成功证据。

注入仅限当前 `http://127.0.0.1:<port>` 顶层根文档；每 WebView 的 sessionStorage marker 防止 reload 把用户后续选择改回。缺特性的旧 WebView 明确要求更新组件，不进入来源不受控的 fallback。实际 r13 WebView 与续切验收待执行。来源与完整许可证保留在 `third_party/androidx-webkit/`，研究原文位于 `../Temp_Android/qa/webview-bootstrap-research/`。

## Android 历史的原子发布

2026-10-13，Android 真环境的 PRoot 与固定 DSH 持久化都观察到 `link(2)` 返回 `EACCES`；真实 flock、临时写入、fsync 已通过。保留 `@deepseek-ai/dsh-session-persistence-jsonl` 0.2.1-alpha.1 的锁、格式、压缩、目录同步和“不覆盖既有历史”检查，仅在受控 Android Linux 启动过程中将最终 POSIX 发布替换为 libc `renameat2(RENAME_NOREPLACE)`。普通覆盖 rename 不具备相同语义，未使用。错误必须失败并清理未发布临时文件，不降级为覆盖写。

调用使用 DSH 固定 bundle 里已有的 Koffi **3.1.1 / MIT**，没有引入浮动依赖。已读实际固定 npm 包的 README、package.json、LICENSE.txt、`src/koffi/index.cjs` 和 `src/koffi/src/ffi.cc`：确认 native/JS 版本匹配、`dlopen`/`dlsym` 符号加载、同步 libc 调用及 `errno` 获取。固定包许可证为 Copyright (C) 2026 Niels Martignène，与 GitHub 镜像较旧许可证不同，原文保留在 `third_party/koffi/LICENSE.txt`。bundle SHA-256 为 `5bcf1cd3888f7db53567ef87ab797f9e1b5f7046f369bc90e5087fbdc1d01e08`。

7890 代理实读 GitHub 镜像 `Koromix/koffi` 固定 commit `2294ba0fa05b6edf1c87c6d9fbe8c66e98b313fe` 的 README/许可证均 HTTP 200，顶层 index.js 为真实 404；镜像没有 3.1.1 tag，因此不把镜像 commit 当作精确 npm 3.1.1 的来源证明。原文在 `../Temp_Android/qa/history-publication-research/`，实际包源码来自已核摘要的固定 DSH bundle，没有运行包安装脚本。

Linux Node/PRoot 的真实生产适配诊断 `qa-history-atomic-publication-r10-diagnostic2.log` 五项 PASS：首次写/读/列举、相同内容幂等、不同内容拒绝覆盖、竞态 EEXIST 保留旧字节并清临时、真实 flock 第二持锁拒绝且释放后可写。此诊断不能代替下一 APK 的 RuntimeSmoke 或双引擎设备验收。

## 正式发行图标

原 `UsePreside/sf-symbols` 转换资源没有可分发授权证明，不适用于正式 Android 发行。44 个保留 `sf_` 兼容资源 ID 的 drawable 已改用 Microsoft Fluent 原始路径；旧前缀不再表示内容来源。

- 来源：https://github.com/microsoft/fluentui-system-icons
- 固定 commit：`a347af95478f989038cccbf085978865264c048a`
- 许可证：MIT，Copyright (c) 2020 Microsoft Corporation。
- 实读 README、LICENSE、每个实际使用的 SVG；不新增图形依赖，不自绘。Window Console 使用上游已有 20 regular，Folder Edit 不存在，选择已有 Folder Open。每份 VectorDrawable 注释保留精确上游路径、固定 commit 和完整 MIT。
- GitHub API 返回真实 403；经代理的 git ls-remote 与 raw 固定源码成功。不存在的 Window Console 24、Folder Edit 24 返回 404，修正为实际资源，未声称对应资源存在。
- 新扩展页拼图和灯泡使用同一 commit 的 `assets/Puzzle Piece/SVG/ic_fluent_puzzle_piece_24_regular.svg` 与 `assets/Lightbulb/SVG/ic_fluent_lightbulb_24_regular.svg`，源码注释保留完整许可。

## 推送平台注册边界

用户表示无法注册部分在线平台，没有提供任何厂商推送项目或凭据。

- 通过代理实读 Firebase Android 设置和 FCM Android client 官方文档（HTTP 200）：https://firebase.google.com/docs/android/setup 、https://firebase.google.com/docs/cloud-messaging/android/client 。这些接入说明没有企业资质前置，要求创建 Firebase 项目和注册 Android 应用；FCM 客户端依赖兼容 Google Play services。未据此声称用户已经能访问 Firebase Console 或已注册项目。
- OPPO 文档入口 HTTP 200 但返回前端应用壳，未得到足以证明个人可开通推送的条款正文；不能把页面成功当作准入验证。
- 未配置通道不声称已注册、已投递或离线可达。真实厂商/FCM 验收需要项目配置和相应设备；通知分类、系统权限、任务持续通知和后端数据能力可以独立推进。

2026-10-13 继续经 7890 实读 `firebase/quickstart-android` 固定 commit `5de53b25d4bc4e862cbe0d222cd966eb29c02f98` 的 README、Apache-2.0 LICENSE、`messaging/app/src/main/java/com/google/firebase/quickstart/fcm/java/MyFirebaseMessagingService.java`（均 HTTP 200）。研究了 data 消息经过应用分类控制、系统默认通知的后台差异、注册/注销通知后端以及长任务交给 WorkManager 的边界；不复制源码，不运行示例，不生成虚假的 Firebase 配置。固定源码仍要求 Firebase 项目和正式包注册，缺少账号配置不会自动获得离线投递能力。

Android 官方 Live Updates 与 Notification.ProgressStyle 文档已实读（HTTP 200）：https://developer.android.com/develop/ui/views/notifications/live-update 、https://developer.android.com/reference/android/app/Notification.ProgressStyle 。平台要求 manifest 声明非运行时 `POST_PROMOTED_NOTIFICATIONS`、显式请求 promotion、ongoing/title、无自定义 RemoteViews/分组摘要/colorized，最终还受用户与 OEM 资格策略控制。标准持续通知也属于可申请提升的样式；API 36+ 通过公开平台 Builder 方法请求，在旧系统保留持续任务通知。声明或请求不等于系统实际提升，设备证据必须单独记录。

## 反馈状态与回复的持久提醒

2026-10-13，补齐反馈提醒时经 `http://127.0.0.1:7890` 实读 Microsoft/.NET Foundation 的 `dotnet-architecture/eShopOnContainers` 固定 commit `b6965936842cab32553543c1abe8a68714956f44`：README.md、MIT LICENSE、`src/BuildingBlocks/EventBus/IntegrationEventLogEF/Services/IntegrationEventLogService.cs`，三份读取均成功。源码的 `SaveEventAsync` 将事件日志加入业务数据库的同一 `IDbContextTransaction`，提交后再读取待发布事件；据此核对本项目反馈更改与提醒事件同事务持久化，避免业务成功但提醒丢失。该示例使用 SQL Server 和消息总线，本项目沿用既有 PostgreSQL/SQLite 与按本人游标补读，不引入消息代理或复制上游源码。每个反馈身份的顺序、平台隔离、删除/撤回过滤及初次基线是本项目契约，须独立验证，不能由参考示例推断通过。研究原文位于 `../Temp_Android/qa/feedback-outbox-research/`，未运行上游脚本。

## 真正跨端的 .dym 数据

2026-10-13，经7890代理实读PC仓库`YunxiRamito/Dafeiyu-Go-DeepSeek-Harness-Click-To-Run`固定commit`ef6d1bd761a7604c041d85c85f93c83b198f5c14`的README、备份源码和MIT许可证（HTTP200）。复用PC现有7zr真7z分组、冲突策略及有界路径校验；实际本地源码核对显示原四组不能处理`.dafeiyu/preferences/*`、提供商元数据、根凭据或仅有手机原生对话的包，不能把Android单端codec通过算成PC双向兼容。

持久化适配使用实际固定DSH`0.2.1-alpha.1`的MIT配置、验证器、凭据锁和会话存储，以及固定pi-ai`0.87.1`认证模型；以受控存储方式转换正文，不重放提示或工具。PC/Android偏好独立保存，平台Key重新安全存储，身份、推送token、系统授权和宿主路径排除。Node脚本随第一方源码固定嵌入，不从备份执行代码。PC7zr→Android生产codec→PC恢复、真实DPAPI/CAS/部分失败和固定DSH读回的离线证据在`../Temp_Android/qa/dym-pc-parity/`；Android实际Repo/Keystore/运行租约的设备结果另记HANDOVER，不由这些离线结果推断。

## 正式 APK 更新与图片下载

2026-10-13 开发者管理继续经 7890 读取 PC 仓库固定 commit `ef6d1bd761a7604c041d85c85f93c83b198f5c14` 的 README、MIT LICENSE、`source/DeveloperNoticeAdminClient.cs` 和 `source/DeveloperFeedbackAdminClient.cs`，四项均 HTTP 200。复用其短期 Bearer 凭据、请求取消、有界响应、反馈状态/回复/封禁和公告草稿/发布/撤回分工，Android 请求使用既有私有 CA/SPKI pin 及独立 Android 通道。版本五击只开放页面，不赋予服务端权限。手机推荐沿 GitHub Contents 的 blob SHA 条件更新，账号 token 只驻内存，不导出到备份或项目。未运行上游脚本；公网写入与设备交互验收另记 HANDOVER。

2026-10-12 经 7890 代理读取成熟更新应用 `ImranR98/Obtainium` 的固定 commit `af286fa8d31d7406d6db167e2314d376d74f7696`：README、LICENSE.txt（GPL-3.0）、apps_provider.dart、apps_provider_install.dart、app.dart 和 Android MainActivity。研究了下载取消、候选 APK 与显式用户安装的分工；GPL 源码没有复制或引入 MIT 工程。LICENSE/README 之外的不存在路径返回真实 404，不能当作可用模块。系统安装依据 Android PackageInstaller／安装来源授权官方接口，并保持每次安装由用户发起。

APK 签名选用 Android Open Source Project 的 `com.android.tools.build:apksig:8.9.2` 公共 ApkVerifier，Apache-2.0。GitHub 镜像 `venshine/apksig` 固定 commit `c0314349dc9f9ccaf9300a39b592af478dc497e9` 的 README、LICENSE 和 ApkVerifier.java 已实读；实际依赖使用 Google Maven 的固定 8.9.2，并下载对应 source jar。源码和二进制 SHA 见 `third_party/apksig/NOTICE.md`。GitHub API 曾返回 rate-limit 403，随后经 git ls-remote 与 raw 固定路径取得源码，没有运行上游脚本。upstream README 指出主要用于 Android 外部，因此 JVM 真实 APK 验签不代替 Android 设备运行验收。

更新复用既有签名服务契约，严格解析 `fileName` 等字段及 JSON 类型，拒绝重复字段、非法 UTF-8、版本越界、静默安装或降级策略。签名原始清单持久保存，下载和安装重新验证；APK 验证真实签名、摘要、证书、原生 ABI，并由 PackageManager 校对包名、版本与 SDK。统一下载层区分 `/api/download?` 缓存代理与原生 release/image 下载路由，后者不会被包装或当作准备状态 JSON。

反馈附件复用项目已有 OkHttp 4.12.0，实读固定 tag `parent-4.12.0`／commit `4984568367caaf359b82c452bd28b5e192824d1c` 的 README、Apache-2.0 LICENSE 和 MultipartBody.kt，采用其长度计算、分界及字段转义，不复制上游源码、不增加网络库。服务器图片 GET/HEAD 提供实际 SHA-256、强 ETag 和 Range，Android 在受 pin 的 HEAD 后使用统一下载器，仍核对 JPEG、字节和尺寸。离线结果与公网部署分别记录到 HANDOVER。
