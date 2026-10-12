# Android 恢复迭代计划 · 2026-10-11

用户已明确恢复开发并授权使用子 Agent，以及在需要时联系四个既有研发聊天。此前停工段落保留为历史记录，以本计划和最新交付证据为准。

## 最新状态 · 2026-10-12

截至 2026-10-12（Asia/Shanghai），**0.1.19 / code20 已作为可用户验收的预览包交付桌面并保留数据安装至用户手机；整包成功，设备验收部分通过、长聊天失败未闭环。** 最终包已包含 FishButton `consumeDrag` 参数及 GeneratedFiles 标题 `consumeDrag=false` 修正。当前状态以本节与 [验收 JSON](acceptance-0.1.19-20261012.json) 为准；下方“早期 QA 与实施记录”起的旧包、待编译、未交付和里程碑状态均为当时历史，不覆盖当前事实。

- 最终构建：`../Temp_Android/build-0.1.19-scroll-fix.log`，**BUILD SUCCESSFUL 3m11s**，46 tasks（12 executed / 34 up-to-date）；Lint **0 Error / 138 Warning / 5 Information**。
- 最终产物：`../Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.19.apk`，**91,404,408 bytes**，SHA-256 `c7e3320b4c483c05be842caa62f8f1158b9502ad6870a8ab14a5e5c7cf8a41f3`；签名证书 SHA-256 `52fc036fd6fe45d400f354235068aab29cb27ca6f68e42c53368a5b00c887b23`。
- RecordBuildEvidence 已更新 [frontend-build.json](frontend-build.json)，记录 179 个源码/配置及 19 个实际 runtime 输入，绑定 [acceptance-0.1.19-20261012.json](acceptance-0.1.19-20261012.json)。这是构建后输入快照及真实验收范围记录，不是全功能 PASS。
- 交付：桌面 `C:/Users/KitamaruRamito/Desktop/Dafeiyu-Go-Mobile-0.1.19.apk` 已核验同 hash；手机 `192.168.5.201:33601` 执行 `install -r` Success，version 0.1.19 / code20、lastUpdateTime **2026-10-12 00:49:48**，手机 `base.apk` SHA-256 匹配。实际 delivery 对象已加入验收 JSON 并重新同步 frontend-build.json。保留数据，安装后未启动 App、无模型调用；手机 UI 仍由用户验收。

### 最终设备 QA 与当前清单

独立 `emulator-5558` 使用 host GPU / WHPX 硬件加速，完整矩阵尝试 run `tools/tests/resume-qa-20261011/runs/activity-019-30b3d08caa2b409c90848385ede716c9/` **EXIT 1，未完成全部矩阵**。原证据为该目录 `result.txt` 和绑定验收 JSON；合成会话不含真实模型生成、shell 工具执行或图片 bitmap 解码。

- [x] 终态过程折叠、展开后的分组活动/图片元数据回执、手动折叠后的最终正文关联、三次活动展开/收起 PASS。
- [x] finally 全事务 workspace/task/output roots、完整 membership、SHA-256 与原有 absence 恢复 PASS；App 保持停止。独立 QA 模拟器已于 00:51 左右经 `adb emu kill` 正常关闭释放资源，用户原 AVD 不变；必要时由主 Agent 再以 host GPU / WHPX 启动。
- [ ] 长聊天两端遍历：原限额 24+24 次滑动未找到 `QA_LONG_FIRST_019`，`FAIL: Label not found after bounded chat scrolling: QA_LONG_FIRST_019`。[Kuhn 只读分析](research/QA-LONG-CHAT-20261012.md) 显示轮次 19→12→19，有正常双向滚动；fixture 为每条回复约 2,870 字符、共 20 轮，24 次历史方向轨迹未覆盖第 0 轮。保持 partial/FAIL，不写完全卡死或唯一根因；验收 JSON 已引用此结论。本轮未再重复完整矩阵，后续按新变化安排针对性验证。
- [ ] 后续两端往返、320dp 信息条/composer、Interrupted 标签、当前 PID 有界日志与内存检查未执行；不标通过。
- [x] QA 脚本空子节点 bounds / 可见候选修正后离线 **37/37 PASS**，证据 `tools/tests/resume-qa-20261011/runs/offline-3706eb4576c94c4c966132b685811dcd/result.json`；仅覆盖选择器、fixture、门禁与恢复契约。
- [x] 新包短诊断 `activity-019-67829cab2b7744e79e4c4f273eaa5867` 已观察原 center 起点滚动且未 toggle；随后因 QA 空子节点 bounds 错误退出，不记完整诊断 PASS。中间 full run `ce85fece...` 部分回执 PASS 后同错，均完整恢复；保留各 run 原证据。
- [ ] 普通标题点击回归、流式/惯性/IME/会话切换、复制引用、空工作区删除及重启持久化、两段左滑、主题/玻璃视觉与真实解码内存仍待验收；focused/离线与设备分别记录。
- [x] 最终包构建、身份、metadata/验收绑定、桌面复制及手机保留数据安装完成；这是预览包交付，不代表完整设备或用户验收完成。
- [x] Newton [QWEA0 固定源码/MIT/几何研究](research/liquid-glass-20261012/REPORT.md)、Euler [Compose 适配评审](research/liquid-glass-20261012/COMPOSE-ADAPTATION-REVIEW.md) 与 [Kyant0 候选比较](research/liquid-glass-20261012/COMPOSE-CANDIDATE.md) 完成。QWEA0 固定提交 `16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d`，Kyant0 固定提交 `65ab177e90e5c1d8c62e70cf7755841982da65f6`；保留报告中的下载、许可及范围限制，不把固定源码研究当成最新 HEAD 或生产运行证明。
- [ ] 共同光学融合尚未实现：现有桥仍为 gap-only。下一轮由主 Agent 排期，无需新增用户审批；必须共享实际动画几何、让原材质/阴影/白光让位、同步菜单 `launcherAlpha`、补低 API 降级，不能只给 shader 增加 secondary rect。继续遵守 [AGENTS.md](../AGENTS.md) 的 7890 GitHub 研究与固定源码/许可留证要求。

上述四份 Markdown 的同步由文档子 Agent 使用 apply_patch 完成；该子 Agent 未修改 production、metadata 或研究材料，未运行网络、ADB、编译、测试或模型调用。这仅说明文档同步工作的范围，不否定主 Agent 与其他子 Agent 已完成并在上文记录的研究、构建、验证及交付。

## 早期 QA 与实施记录（历史）

以下保留早期失败、旧包身份和当时计划原文。此处“当前”“待重编译”“未交付”等均限定于记录时点，最新状态见文首。

### 第三次设备 QA 记录

原证据目录：`tools/tests/resume-qa-20261011/runs/activity-019-b2e24879535048608476476508124a89/`（项目根相对路径），`gate.json` 开始时间为 `2026-10-12T00:00:14.8000665+08:00`。本次为合成 fixture UI，未请求模型；其 credential 检查仅限被测 App 家族，不能扩大为整机无凭据证明。

`result.txt` 记录 **`FAIL: Scene drift: chat navigation missing`**。finally 已完整恢复 workspace/task/output roots，complete membership、SHA-256 和 original absence verified；恢复后 App 保持停止。恢复事务通过不能代替活动 UI QA 通过。计入 fresh App 缺 no_backup 和 Copy alias 冲突两次前置失败后，此为第三次 QA 失败；此前各次记录不覆盖或改写。

002–019 XML 的聊天列表 bounds `[0,231][1080,1854]` 与 45 个后代内容/位置未变化，17 次 swipe 没有产生可观察的内容移动。中心起点 `(540,556)` 落入文件标题 `[63,556][1017,685]` 的 clickable 父按钮区域；描述子节点“收起 qa-output-a.txt”本身不是 clickable。当次源码审查把 FishButton 消费拖动列为候选，尚未完成设备起点/空白 padding 对照；后续短诊断见下一节，**不单独称根因已确认（root confirmed）**。

019 XML 缺失导航、信息条与 composer 等列表外兄弟节点，而相邻 PNG 仍显示这些控件；XML/PNG 为顺序采集，不是原子成对快照。这支持不完整 accessibility dump 候选，但不能证明实际场景没有漂移。场景拒绝与 17 次拖动停滞是两个问题，不能合并为一个已确认根因，也不能绕过场景门禁。

QA Agent 已整理只读诊断及有界设备对照方案，见 [DIAGNOSE-SCROLL.md](../tools/tests/resume-qa-20261011/reviews/scroll/DIAGNOSE-SCROLL.md)。方案的脚本实现/离线检查与设备短诊断分别记录；本次文档任务没有执行该方案，以下补记来自主 Agent 已执行结果及现有日志。

### 两起点短诊断与局部修正待验收

主 Agent 短诊断 exit 0，原证据目录为 `tools/tests/resume-qa-20261011/runs/activity-019-f97afe6f64654eaab269c7b3f3e2c8e2/`；`gate.json` 开始时间 `2026-10-12T00:18:22.6722273+08:00`，模式 `DIAGNOSE_SCROLL_TWO_SWIPES`，安装包仍为上述 SHA-256 的 0.1.19。`result.txt` 明确为 DIAGNOSTIC ONLY，没有 full activity UI PASS。

两次滑动均从 y=556 到 y=1529、时长 400ms。center x=540 的聊天 fingerprint 前后均为 `cbd1c02e25b7e3e2694e86468f0a3da522ded72d273e98abb25accb70cdc2154`（changed=False）；同 y 的 right-padding x=1048 滑动后变为 `6a048a648f7fca2af8bcf2a3a147dfda0710849991f1a13bb8a037689e6ce1b3`（changed=True）。日志确认 finally 完整恢复 workspace/task/output roots、complete membership、SHA-256 与 original absence，App 保持停止。该对照支持文件标题区域 FishButton 消费拖动候选；两次滑动是顺序对照，不据此单独确认唯一根因，也不覆盖此前 Scene drift 失败或完整设备门禁。

Einstein 负责 FishButton 增加 `consumeDrag` 参数、GeneratedFiles 标题设为 `false` 的授权局部修正。**当前 88,558,229 bytes、SHA-256 `8830c7789bcee3275d952197ebb3c619a8499535e18678e9ac697f4dfbb7e6d7` 的 0.1.19 APK 尚未包含此修正，待重编译。** 后续须绑定新包身份，复测标题区域拖动能滚动、标题点击仍能展开/收起，再独立完成完整 UI QA；当前不宣称修正通过或全设备 PASS。

本次只用 apply_patch 更新 HANDOVER.md、README.md、清单.md 与本计划，保留历史章节；未修改 production、构建 metadata 或研究材料，未启动 JVM/Gradle、ADB、测试、联网或付费调用。

## 本轮目标

先交付可验收的 0.1.19 / code20 APK，然后继续按交接与清单推进后续功能。已有 0.1.18 / code19 包保留，源码版本不代表新包已完成。0.1.19 是首个里程碑，不能据此把整个持续迭代目标标记完成。

| 分工 | 范围 | 验收 |
| --- | --- | --- |
| 回执 Agent | 思考与工具真实时序、统一间距、可复制详情 | 多轮交错与分组检查，不跨思考边界合并 |
| 指标 Agent | 椭圆信息条、真实 token/速度/缓存指标 | 未知不造数，窄屏与大字体检查 |
| 工作区 Agent | 空区删除、两段左滑、选中椭圆 | 数据保护、持久化与取消检查 |
| 玻璃 Agent | 顶栏桥接、放大、阴影、触点光晕 | 生产组件编译、边界命中与减弱效果 |
| QA Agent | 新包门禁、合成活动验收、网页正文验证准备 | 场景检查、原数据备份恢复、证据分类 |
| 引擎规划 Agent | 受限子任务工具、执行器与 DSH 接线规划 | 实际源码核对与 GitHub 成熟实例调研 |
| 主 Agent | 整合、版本、编译、设备验收与交付 | assemble/lint、包签名、安装、截图与交接 |

临时文件使用项目目录或 `../Temp_Android`；手动修改用 apply_patch。共享目录没有 Git 仓库，保留所有既有改动。测试使用离线响应或无任务队列的合成数据，不自动消费付费模型调用。

用户追加内存要求：本机约 16 GiB RAM，重编译串行调度，整仓构建单 worker、关闭并行且启动前要求至少 2560 MiB 可用。保持现有 2 GiB Gradle heap 和 in-process Kotlin，避免同时启动额外编译 daemon。已确认并正常停止 Android Studio 所属的闲置 Gradle daemon（15292），可用内存由约 2 GiB 回升至 4.4 GiB；没有强杀其他应用。模拟器按实际内存预算安排，不与重型编译竞争。应用缩略图已有 256px 解码、附件/网络读取上限，新增内容继续避免无界缓存。

用户进一步允许整机内存占用到 95–98%；这是可用上限而非需要维持的目标。必要的单项工作可以使用预算，但避免同时叠加多个 JVM、模拟器与解码峰值造成持续换页或 OOM。focused 编译采用 512 MiB JVM 并按组串行。

最新补充：用户希望充分使用内存提升效率，并明确不会运行其他高占用工作。调整为六组实现并行、最多两组 512 MiB focused 编译并行；整仓 Gradle 单实例、两个 worker、无并行项目构建，启动可用内存门槛 1536 MiB。按实时可用内存收紧并发，不人为分配无用内存以提高占用数字。此前更保守的单 worker/2560 MiB 记录为调整前配置。

## 里程碑

- [x] 读取四份交接文档，核对 0.1.18 交付和 0.1.19 源码状态。
- [x] 建立目标并启动六组独立子 Agent。
- [x] 完成回执、信息条及已有交互改动的代码审查与必要修复。
- [x] 运行对应 focused 检查；记录真实通过项和残余问题。
- [x] 完成 0.1.19 assembleDebug / lintDebug 与包元数据、签名验证。
- [ ] 在明确指定的模拟器验收布局、滚动、复制引用与工作区交互；合成数据恢复校验。
- [ ] 更新构建元数据、清单、交接，提供新 APK。

阶段证据：主 Agent 已重跑真实 ChatScreen Compose 编译、16 个滚动状态和 14 项接线检查；父子任务等待的真实生产编译、12 个等待场景和 26 个会话场景均通过。指标信息条真实 Compose 编译和 27 个场景通过，回执 Agent 完成真实 Feed/Summary/Details focused 编译及六组检查（其中 15 项时序、9 项原句标题）。这些是离线与 focused 证据，不代替最终 APK 和设备布局验收。

设备变化：PKB110 手机和 emulator-5554 已重新在线。现有默认内存模拟器占用约 4.9 GiB，主机只余约 1 GiB；主 Agent 已通过 ADB 正常关闭该调试实例，AVD 数据保留，回收后约 6 GiB 可用。整包编译结束再以较小内存启动，不叠加编译和模拟器峰值。新增 `tools/RecordBuildEvidence.ps1` 为最终 APK 记录版本、签名、SHA-256、当前源码/配置与外部 runtime 哈希；没有成功包及绑定验收报告时拒绝更新元数据。

整包证据：`../Temp_Android/build-0.1.19-final.log`，BUILD SUCCESSFUL in 3m41s，46 tasks（26 executed / 20 up-to-date）。Lint XML 为 0 Error / 138 Warning / 5 Information。产物 `../Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.19.apk`，88,558,229 bytes，SHA-256 `8830c7789bcee3275d952197ebb3c619a8499535e18678e9ac697f4dfbb7e6d7`，code20；签名证书与 0.1.18 一致。此前遗漏的 composer draft 删除保护接线已包含在这个整包中。包体变化不表示移除 runtime：两包原生条目均 28、解压后原生字节均 100,871,656。

浏览专项：真实 `OfficialWebClient` 主机 JVM 编译/执行 4/4 PASS，RFC768 返回 6,615 bytes、真实正文与来源；1 MiB 上限检查保留。证据为 `tools/tests/resume-qa-20261011/runs/production-web-50467b2eac23488e9281339c455ea5b8/`，不是 APK UID 或付费模型实测；中文日志编码与中文时效相关性另行检查。

独立测试设备：新建 `../Temp_Android/qa-avd/Dafeiyu_QA_API_37.avd`，serial `emulator-5558`，没有复用用户 AVD userdata。API37 强制把 1536 MiB 调高为 4096 MiB，因此不能声称该镜像能以 1.5 GiB 运行。首次启动排查短暂采用软件 GPU，用户指出 CPU 高占用后已正常停止并恢复 `-gpu host -accel on`（NVIDIA RTX3070 / WHPX）；后续默认硬件加速。新 APK 已安装，正式活动验收仍待修正脚本并通过。

设备 QA 的两次前置失败如实保留：fresh App 未初始化时缺 `no_backup`；初始化后 runner 的 `Copy` 被 PowerShell `Copy-Item` 默认 alias 抢占。第二次 finally 已校验完整恢复 membership/文件哈希和原有缺失，尚无成功 UI QA。QA Agent 正在修复别名冲突并重新冻结输入。

## 后续里程碑

轻引擎优先：为子任务建立有配额、可取消、禁止递归创建的独立工具会话；另行评估脚本执行和真实隔离。然后验证 Android DSH 的 CLI/Web bundle、ABI、依赖与生命周期，再接 App 专属移动适配插件。图标、prepared 包、模型口述和规划文档均不作为执行能力已交付的证据。

## 跨项目协调

用户授权联动的聊天为：PC「修复1.8.0设置、在线统计与启动延迟」、插件「实现设备连接扩展插件」、开发者中心「实现安卓APK更新协议与独立公告通知渠道」、Aries「继续 Aries 服务微端设计」。

本轮已对四个引用分别调用 read_thread；接口返回 `Invalid app tool request`，省略 hostId 重试仍失败，list_threads 同样失败。尚未取得聊天内容或发送协调消息，不推断其当前状态。手机端独立工作继续；待接口可用时先读内容，再根据具体依赖发送协议/资产需求。
