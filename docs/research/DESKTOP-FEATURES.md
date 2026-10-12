# 桌面功能迁移到 Android 的可行性矩阵

研究日期：2026-10-10。范围：只读桌面启动器 README、交接文档及相关源码，确定 Android 立项范围；没有开发界面、启动桌面服务、操作真实配置或调用生产写接口。本报告不评价 DSHA 实现；DSHA 应由独立代码研究报告对照。

桌面仓库：`G:\DeepSeek DSH\DSH Works\Project\Dafeiyu-Go\Dafeiyu-Go-DeepSeek-Harness-Click-To-Run`，研究时 HEAD 为 `4edfe298f2f327335d19e7edde0b709399bacbc8`。下文 `source/...:行号` 均相对该桌面仓库，`../HANDOVER-1.7.3-2026-10-09.md` 相对其父目录。引用指向本地所读源码，不表示代码与发布 ZIP 已逐文件核对。

没有在桌面目录、Mobile 目录及其直至盘根的父目录发现适用 `AGENTS.md`。本轮遵守当前用户指定的 `Temp_Android` 临时目录；桌面交接中的 `Temp_SetupandLauncher` 是历史桌面任务约束。

## 阅读结论与版本基线

README 描述了六组桌面能力：余额与用量、通用配置与备份、插件技能、服务与组件、更新、关于诊断（`README.md:112` 起）。但是 `README.md:43` 仍写 1.6.0 已发布，`:45` 写 1.7.0 收尾中，不能作为最新版本事实。`HANDOVER.md:3`—`:10` 明确记载 **2026-10-10 12:40 v1.7.3 已发布**，且主页通知按钮补修已包含在其资产中（`:12`—`:14`）。父目录 1.7.3 交接和本文件底部保留了旧暂停/未部署/未发布状态，须按最新且适用的记录判读。

1.7.3 模型页的产品边界是管理由启动器启动的 **DSH 提供商和模型目录**，支持排序、增改删、连通检测、刷新；**没有默认模型选择与网页会话同步**（`../HANDOVER-1.7.3-2026-10-09.md:73`，`HANDOVER.md:1339`）。通知原 ID 编辑避免重送、模型留空 Key 保持旧凭据、下载来源立即生效等验收记录见 `HANDOVER.md:19`—`:24`。父目录暂停点 `:120` 的“模型页仍不可用”是历史问题，后续验收已有模型 RPC 40 项和真实 WinUI 模型 41 项通过（`HANDOVER.md:23`）；这仍不能当成 Android runtime 已可用的证据。

## 分级方法

- **可直接实现**：功能逻辑或 HTTP/数据协议与 Windows 无关，Android 可重新实现；不表示 C#/WinUI 文件能够直接编译进 APK。
- **需重新设计**：用户价值可以保留，但存储、权限、生命周期、通知或更新机制必须换成 Android 方式。
- **依赖 DSH runtime**：需要本地 Android DSH 环境或可授权连接的远程 DSH/管理桥接；在 runtime 未验证前不能承诺。
- **不迁移**：Windows 平台专属机制、已移除行为或与移动端目标无关的入口。

## 功能矩阵

| 功能 | 主分类 | Android 判断与建议 | 桌面证据 |
| --- | --- | --- | --- |
| DeepSeek 账户余额 | 可直接实现 | HTTPS GET 与 Bearer Key 可在 Android 实现。Key 存储另行设计，余额按账户隔离；余额变化保持“观测差额”，不描述为完整账单。 | `source/BalanceSupport.cs:156`、`:175`、`:182`、`:191`；`README.md:124`—`:126` |
| 本机余额历史与阈值告警 | 需重新设计 | 聚合与阈值逻辑可保留；本地数据库、刷新时机、系统通知渠道由移动端管理。不要承诺应用退后台仍按桌面频率查询。 | `source/BalanceSupport.cs:192`；`README.md:124` |
| Token 用量、缓存命中、按模型估算费用 | 依赖 DSH runtime | 图表和聚合可重写，但数据来自 DSH 插件生成的 JSONL，不能从余额 API 推导出完整 Token 账单。远程模式需明确用量 API/数据同步；本地模式需先让统计插件成功运行。未知价格模型保留未计价标识。 | `source/TokenUsageService.cs:86`—`:100`、`:105`—`:117`、`:292`—`:301` |
| 公告、当前通知、Markdown 详情、已读状态 | 可直接实现 | HTTP 模型和本地按 ID 去重可实现；Markdown 关闭原始 HTML/远程资源。主页浏览不产生 Read，用户明确阅读/成功动作才更新回执。 | `source/ClientNoticeClient.cs:120`、`:159`、`:213`—`:228`；`source/ClientNoticeModels.cs:26`、`:55`—`:58`；`source/SettingsWindow.HomeNotices.cs:40`、`:53`；`HANDOVER.md:14` |
| 后台通知、音效、全屏延后与回执 | 需重新设计 | 采用 Android 通知渠道和系统允许的后台策略；前台轮询、系统推送、低频后台同步需要分开。不能照搬持续 60 秒轮询与监测其他应用全屏。系统接受投递不等于用户已看到，应重新定义 Displayed/Deferred，避免与桌面指标混算。 | `source/ClientNoticeClient.cs:292`；`source/WinUIProgram.cs:4525`—`:4541`、`:4600`、`:4610`；`source/ClientNoticeModels.cs:29`—`:31` |
| 通知 HTTPS 链接、设置页动作、关闭 | 可直接实现 | HTTPS 链接使用系统浏览器或受控详情页；桌面页名映射到移动端路由。动作成功后再 Click/Read；取消或失败不确认。 | `source/ClientNoticeModels.cs:67`—`:82`、`:91`—`:92`；`HANDOVER.md:14` |
| 通知 PowerShell 动作 | 不迁移 | Android 无桌面 PowerShell 执行环境。遇到此类消息显示平台不支持，不能默默替换成任意 shell，也不能误报动作成功。服务端最好增加平台定向或客户端能力协商。 | `source/ClientNoticeModels.cs:76`—`:78`；`source/WinUIProgram.cs:4723`—`:4732` |
| 公共反馈、我的反馈、状态筛选、补充 | 可直接实现 | 现有 REST 接口可重写客户端；终态限制与封禁保持服务端裁决。匿名身份必须移动端重新设计，不能读取 Windows 注册表，也不能声称跨设备天然是同一个用户。 | `source/FeedbackClient.cs:41`—`:74`、`:84`—`:136`；`source/ClientNoticeStore.cs:66`—`:106`；`HANDOVER.md:98` |
| 反馈图片、可选日志上传、新回复通知 | 需重新设计 | Android 图片选择器、压缩与缓存可以实现；保持最多 5 张、每张 5 MiB，日志默认不附。只收集移动端自己的有限脱敏日志；后台回复提醒遵守通知限制。 | `source/FeedbackImageSupport.cs:35`—`:38`、`:121`—`:128`；`source/FeedbackClient.cs:139`、`:158`—`:178`；`HANDOVER.md:60`、`:75`—`:77` |
| 在线插件/技能目录、搜索、分类、排序与详情 | 可直接实现 | 元数据浏览、推荐、收藏或待安装列表可实现。不要将目录可见等同插件能在手机运行。大的列表应分页/惰性加载，避免照搬桌面一次加载 2,500 条的内存成本。 | `source/PluginCatalogService.cs:99`、`:126`；`source/SkillMarketService.cs:86`；`../HANDOVER-1.7.3-2026-10-09.md:92` |
| 插件安装/卸载/更新、修复、GitHub 链接安装 | 依赖 DSH runtime | 桌面优先调用官方 `dsh plugin`，需要 CLI、profile、pnpm/Git 及插件依赖。Android UI 可以管理队列；真正安装应由验证过的 runtime/桥接执行。带原生模块、构建脚本或桌面权限的插件须单独判定兼容。 | `README.md:156`—`:157`；`source/DshPluginCliService.cs:21`—`:26`、`:571`、`:732`—`:764` |
| 技能集多选安装/卸载 | 依赖 DSH runtime | Markdown/资源下载、选择目录、记录和回滚可实现；被 DSH 发现与使用仍要求正确的 DSH_HOME 和目录约定。移动端文件存储和导入入口需重写。 | `README.md:158`；`HANDOVER.md:660`；`source/Backup/DshDataImportService.cs:96`—`:104` |
| 技能/插件说明翻译为中文 | 可直接实现 | 用户点击后请求、缓存、Markdown 分块与代码原样保留可迁移。须显示 API 调用/消耗而不后台自动翻译。当前代码使用普通对话 messages 协议。 | `source/TranslationService.cs:88`—`:120`、`:203`、`:260`；`source/TranslationCore.cs:74`、`:94`、`:201` |
| 1.7.3 提供商/模型目录、增改删、连通检测、排序 | 依赖 DSH runtime | HTTP RPC 的逻辑可迁移；底层是 DSH 注册的管理能力，既不是普通模型 `/models` 请求，也不是本地 UI 表单保存即可完成。远程需要新增可信桥接/授权方案；本地需验证必需插件与 RPC。 | `source/SettingsWindow.Models.cs:128`—`:152`、`:475`、`:947`、`:1029`；`source/DshModelRpcClient.cs:176`—`:186` |
| API Key 存储与提供商密钥编辑 | 需重新设计 | 余额/翻译 Key 改用 Android Keystore 保护；Windows DPAPI 密文不能直接在手机解密。DSH 提供商保留仅查询凭据状态、留空不清除、revision 冲突检测、失败清理临时凭据的语义。 | `source/BalanceSupport.cs:68`—`:90`；`source/SettingsWindow.Models.cs:521`、`:691`、`:894`—`:905`、`:947`、`:965`—`:968` |
| DSH 启停、重启、端口、日志、打开网页 | 依赖 DSH runtime | 桌面本质是启动 Node 子进程运行 DSH web。Android 是否本地运行需独立验证 Node ABI、依赖、可执行文件和长期服务；远程模式只能经授权管理桥接控制远端，手机自身无法读取 Windows last-url 文件。 | `source/WinUIProgram.cs:5134`—`:5164`、`:740`；`README.md:171`—`:174` |
| `.dym` 与原版 DSH ZIP/目录的备份导入导出 | 需重新设计 | 可保留分组、预览、取消、覆盖/跳过/两个都留、路径校验和原子替换。Android 通过系统文件选择与内容 URI 读写。ZIP 较易实现；`.dym` 是真 7z，需要 Android 压缩库或 JNI，不能运行内嵌 7zr.exe。恢复插件文件不保证其依赖能在 Android 执行。 | `source/Backup/DymArchive.cs:26`—`:31`、`:314`、`:537`；`source/Backup/UserDataBackup.cs:75`—`:81`、`:772`—`:805`；`source/Backup/DshDataExportService.cs:64`—`:80`；`source/Backup/DshDataImportService.cs:320`—`:362` |
| 下载中心、源/镜像选择、SHA-256、暂停/重试/取消 | 需重新设计 | 通用下载状态机可重写，持久任务、内容 URI、应用重启恢复与后台策略使用 Android 机制。保留官方/CDN/后端隔离和“新操作即时读取新设置”；不要切换设置就替换正在进行的连接。 | `README.md:189`；`../HANDOVER-1.7.3-2026-10-09.md:154`—`:158` |
| App 更新与 DSH 更新 | 需重新设计 | Android App 更新改为 APK/应用商店安装流程；DSH 更新仍由 runtime 负责。不能照搬 EXE 文件替换器或安装器互相修复。 | `README.md:186`—`:188`、`:213`—`:218`；`source/DshUpdateService.cs` |
| page/resource 补丁 | 需重新设计 | Android 可以更新允许的数据资源，需独立定义资源消费者、事务和回滚；WinUI/XAML 页面补丁不能直接用于原生 Android。二进制/脚本补丁继续排除；桌面当前亦拒绝安装。 | `source/PatchApplyService.cs:64`—`:82`；`README.md:190` |
| 健康检查、诊断导出、版本/更新日志、项目链接 | 可直接实现 | 迁移诊断“仅必要结构信息”的原则；Android 输出设备/App/runtime/连接状态，移除 .NET/Windows 字段；导出通过分享或文件选择，默认不含原始日志、路径和配置。 | `source/LauncherHealthReport.cs:18`—`:30`、`:39`—`:57`；`README.md:202`—`:205` |
| 主题、搜索、分组导航 | 需重新设计 | 浅色/深色/跟随系统和设置搜索可保留；按移动任务重新组织页面，不直接照搬六组桌面设置或 WinUI 控件。 | `README.md:84`、`:101`、`:142`；`HANDOVER.md:260` 起 |
| 在线人数/版本分布/服务器监控、管理员中心 | 可直接实现 | REST 数据展示可重写；手机在线定义应区别前台活跃、连接中与后台离线。普通版优先保留用户功能，管理员 Token、公告发布与封禁等管理入口建议后置。 | `source/ClientNoticeClient.cs:178`、`:194`—`:204`；`../HANDOVER-1.7.3-2026-10-09.md:39`；`HANDOVER.md:137` |
| Windows 托盘、UAC、计划任务、自带 .NET/WinAppSDK 补装、Win10/Win11 材质 | 不迁移 | 均为桌面平台机制；用户目标如服务状态与后台工作由移动端通知/生命周期表达。登录计划任务不能等同 Android 开机自动运行。 | `README.md:142`、`:174`、`:269`—`:274`；`source/WinUIProgram.cs:866`、`:1043`；`source/ComponentService.cs:68` |

## 关键实现边界

### 模型管理不能仅修改主机名

`DshModelRpcClient.cs:40`—`:43` 描述 GET `/` 携带启动 token 换取浏览器会话 cookie；`:63`—`:80` 使用干净的 `/api/<endpoint>`，发送 `client-request` / `rpcId` / `method` / `payload.args`，并验证响应 `server-response` 和对应 rpcId（`:106`—`:116`）。`:176`—`:186` 仅接受 HTTP loopback，代理关闭（`:198`）。因此远程模式需要新的授权入口、TLS 与实例身份绑定，不能直接放开 localhost 限制后发布。

模型刷新用 `session/modelCatalog`、`settings/describe`、可选 `llm/listProviders` / `llm/listConfigurableProviders`；连通检测为 `llm/discoverModels`。保存先写新的 credential ref，再 `settings/mutate` 携带 `expectedRevision`，不先覆盖活动凭据（`SettingsWindow.Models.cs:894`—`:947`）。Android 移植应保留冲突与凭据清理语义。桌面最新错误分类已区分组件未就绪、参数无效、revision 冲突等（`DshModelRpcClient.cs:133`—`:173`）；移动端也应返回有限、脱敏的错误，不输出服务器详情或 Key。

### 公告/反馈后端可以复用协议，身份与指标需调整

现有客户端协议依赖 installationId、匿名 machineId 和持久化已读集合。installationId 是本地 GUID（`ClientNoticeStore.cs:55`—`:63`）；machineId 主要来自 Windows MachineGuid 的产品限定 hash（`:90`—`:106`）。Android 需自行定义匿名安装身份及卸载/重装/备份恢复行为。没有依据宣称现有身份方案能够跨 PC 与手机合并“我的反馈”。

通知接口已有 Delivered/Displayed/Deferred/Read/Click（`ClientNoticeClient.cs:209`—`:221`）。桌面 Displayed 是 helper 确认渲染，手机系统通知无法完全等价；建议新增平台/能力与明确事件定义后再汇总统计。桌面已部署 Deferred 的最新记录是 `HANDOVER.md:24`、父交接 `:13`，旧“尚未部署”不应写进最新立项结论。本轮未访问生产服务器验证该状态，只依据交接。

传输使用指定主机与 8787 端口的 SPKI 校验（`NoticeTransport.cs:14`、`:35`—`:49`）；仅 GET/HEAD 自动只读直连回退，写请求不重放（`:53`—`:76`）。Android 必须保留请求取消、凭据域限制与防重复提交语义；不能为了联网方便使用全局信任所有证书。

### 备份兼容分三层验收

1. **文件格式兼容**：`.dym` 按 7z 处理，DSH 原版 ZIP 按 ZIP 处理，不靠扩展名猜测成功；拒绝越界路径和不支持的档案项。
2. **目录与数据语义兼容**：区分官方 DSH_HOME 的 profiles/sessions/storages/skills/plugins 与桌面便携根目录 `.dsh`；插件启用清单、相对引用、源路径、冲突和原子替换须迁移。证据为 `DshDataImportService.cs:65`—`:66`、`:96`—`:119`、`:384` 起，以及 `UserDataBackup.cs:885`—`:900`。
3. **运行兼容**：备份读取成功与恢复成功均不证明插件可执行；Windows 原生依赖、路径引用和 DPAPI 密钥不能随归档直接成为 Android 可运行配置。需要重装/重新授权的项应明确标识。

Android 首期建议优先兼容 ZIP 与技能/会话/可移植配置；`.dym` 完整读写在选择压缩实现、许可证与资源上限后纳入独立验收。桌面本身也有备份导入导出历史问题，不能只照着交接中的“已发布”就跳过跨平台用例。

## 建议立项阶段

**当前阶段：先确定移动前端。** 用户进一步确认直接制作原生 Android 页面，借鉴 WinUI 3 与 Windows Phone 8 的任务组织，结合 Windows 11 审美；后端稍后讨论。原生前端展示运行、对话、拓展、设置等任务流，模拟状态不表达为真实 runtime 已实现。前端构建与检查结果另记 Mobile/HANDOVER.md，本研究本身仍为静态调研。

**技术实施第一阶段：先验证手机本地 runtime PoC。** 用户已确认最终兼顾本地和电脑服务微端，优先本地运行。先对 DSHA 可借鉴的 Node/runtime、DSH_HOME、权限、服务生命周期与 Web/RPC 做最小验证，以手机本地成功启动、授权并完成一次真实对话为起点，再验证模型与配置接口；不先制作独立管理 App。远程模式仅预留 Dafeiyu-GO-Aries adapter，当前不研究或推导 Aries 的既有能力，不重复设计电脑微端。

**技术实施第二阶段：围绕可运行的本地 DSH 开放管理能力。** 成功后再接模型管理、插件/技能安装、Token 用量与 DSH 启停，逐项验证兼容性；随后加入余额、翻译、公告/反馈、目录浏览、版本/诊断与基本设置。不能以“能打开聊天网页”作为所有管理功能通过的标准。

**第三阶段：补齐移动端长期使用。** 验证屏幕关闭、Doze、进程被杀、网络切换后的恢复与通知；完善可取消备份、ZIP/.dym 互通、下载恢复、App 更新与插件兼容清单。管理员功能和资源补丁按实际需要后置。

以上是立项建议，不是已完成 Android 功能清单。本轮只读取和建立源码证据；没有 Android 编译、真机测试或 runtime 可用性实证。
