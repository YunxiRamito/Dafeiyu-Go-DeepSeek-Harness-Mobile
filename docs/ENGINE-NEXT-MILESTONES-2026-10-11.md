# 轻引擎与 DSH 下一里程碑：真实缺口及可执行规划

日期：2026-10-11，Asia/Shanghai。状态：**只读审计及规划就绪；实施未授权。下一批有界子 Agent 工具会话在 0.1.19 验收、冻结，并由主线程判断解耦后实施。**

本文件承接 HANDOVER 文首及用户最新指令：0.1.19 成功后继续清单，先完善 Native 轻引擎，再推进受限工具子任务、脚本沙箱评估、真实 DSH runtime、App 独占移动适配。这里的 Native 轻引擎指现有 Android 官方模型/工具链；DSH 的 native Node 启动可行性属于后面的 runtime 阶段，不混为一项已交付能力。

本轮写入范围仅本文件与 `tools/tests/engine-roadmap-20261011/`。没有修改 production、HANDOVER、共享插件或发布元数据；没有 JVM/Kotlin/Gradle、ADB、付费模型、runtime 启动、外部脚本执行或外部 chat 消息。四聊天协调由主 Agent 负责，下方接口请求可供其转交，不表示已发出或收到回复。项目没有 `.git`；其他 worker 的并发修改不得回滚。

## 1. 审计依据与证据限度

- 阅读 HANDOVER 文首恢复说明、版本/实际证据及能力缺口；0.1.18/code19 已交付，0.1.19/code20 在所读记录中仍待整包与设备验收。本轮不替主线程宣布版本冻结。
- [本地快照与摘要](../tools/tests/engine-roadmap-20261011/local-20261011-232646/audit.json)：34 个源码/契约/准备脚本，逐文件读前后 SHA-256 一致；[带行号原文](../tools/tests/engine-roadmap-20261011/local-20261011-232646/numbered-sources.log) 可复核。行号按此快照引用，后续共享目录变动时以符号名定位。
- 审计期间 `DemoRepository.kt` 已被其他工作修改，当前图片门禁与 DSH 演示入口行号后移；重读确认子 runner、2,800,000 原始图片字节限制和演示状态结论仍成立。此快照不是全仓冻结或生产未被其他线程修改的证明。
- Node executable + 15 libraries：16/16 锁定摘要一致。静态 ELF64 仅按小块 seek/read 检查 interpreter/NEEDED/SONAME/RUNPATH，没有运行 Android 二进制。旧 `local-20261011-232509` 保留为先前 30 文件审计，最新以 232646 为准。
- [GitHub 搜索原始回执](../tools/tests/engine-roadmap-20261011/github-20261011-232509/summary.json)：本机 7890 代理四次 repositories 搜索成功；七次 metadata 403 配额失败完整保留。
- [13 份参考读取回执](../tools/tests/engine-roadmap-20261011/references-20261011-232646/summary.json) 全部 HTTP 200，含 URL、大小、时间与 SHA-256；[参考解读与 URL 索引](../tools/tests/engine-roadmap-20261011/REFERENCE-FINDINGS.md) 区分可借鉴机制和未核验条件。没有 clone、安装或运行外部未知脚本。
- 既有离线 fixture、历史测试数、截图和 PC/Aries 联调均按原记录归属。本轮没有复跑生产行为测试，静态检查不能证明设备网络、低内存、实际 DSH Loader 或付费模型端到端通过。

## 2. 已核实能力与真实缺口

| 能力 | 源码/证据定位 | 核实结论与下一步 |
| --- | --- | --- |
| 父 Agent 工具 | `OfficialToolContract.kt` 46–54、schema；`DemoRepository.kt` 父 dispatcher / `OfficialToolSession.generate` | 八种工具已接：创建子 Agent/后台任务、写受管文本、列任务/读任务、天气、搜索、静态浏览。用户已确认真实 web_search；不再列为完全缺失。 |
| 子 Agent/后台 runner | `DemoRepository.kt` 578–657；`DeepSeekChatClient.Request.tools` 默认空列表 | 独立 client + 单次文本生成，system 明写无 tools/web/live data；收尾还拒绝任何 toolCalls。子 Agent 不能调用父 Agent 已有搜索工具，是真实优先缺口。 |
| 有界调度基础 | `BackgroundTaskEngine.kt` 43、110–123、185–226；`BackgroundTaskModels.kt` 46–51 | 已有全 engine 并发 2、任务 50、RunnerExecution 禁递归创建/执行/嵌套等待、输入 32768 字符、结果 65536 字符、进度 4096、store 24 MiB。不能宣称这些全部缺失。 |
| 父子返回与取消 | `OfficialToolSession.kt` 90–175；`OfficialToolDispatcher.kt` 53–91 | 先提交所有兄弟，后并发等待，真实结果进入 tool history 后父模型继续。等候取消/超时不自动停止子任务；默认 15 分钟等待、最高 30 分钟。后台任务独立，杀 App 后活跃任务恢复为 INTERRUPTED。 |
| 工具配额/去重 | `OfficialToolDispatcher.kt` 98–139；`OfficialToolSession.MAX_ROUNDS` | 每用户 turn 的 ledger、同 ID/参数缓存、ID 变参拒绝；batch 4、calls 16、tasks 4、files 4、rounds 6 已有。尚无父子树共同预算。 |
| 搜索/浏览范围 | `OfficialWebClient.kt` 73–160、213–221、311–345 | Bing RSS / DuckDuckGo fallback、公开 HTTPS 静态文本/标题/链接，响应实际 1 MiB、正文 12000 字符、链接 20；有网络取消。正文设备专项仍未闭环；JS/登录/cookie/内网/任意 POST/API/下载解析没有执行通道。 |
| 文件与脚本 | `OfficialToolOutputStore.kt`；session system | 受管 `.txt`、256 KiB/文件、最多 100 文件/16 MiB store；没有 shell、Python/JS 执行器或安全脚本沙箱。Skill/终端图标不证明执行能力。 |
| 附件与图片 | `ConversationAttachmentStore.kt` 33–44、205–226；`DemoRepository.kt` requiredImages 门禁 | 下层附件每件 10 MiB、总 25 MiB、store 128 MiB、图片 data URL 总 36 MiB、最多 6 图；**实际聊天全部当前/历史原图合计另限 2,800,000 bytes，模型请求 4 MiB**。不能把下层上限说成正常聊天实收 36 MiB。 |
| decode | `AttachmentPanel.kt` 338–346；`ScannerScreen.kt` 258–265 | 已有源像素 100M 上限、缩略图最长边 256、扫码图最长边 1400及 software allocation。还缺共享 decode 并发/临时内存准入与释放证明；不能说当前全部原尺寸解码。 |
| 请求历史分配 | `DeepSeekChatClient.kt` 291–371；`OfficialChatHistory.kt`；session history | 已有字符预检/4 MiB 字节门禁，但 `JSONObject -> toString -> UTF-8 bytes` 最终门禁在分配后；validateRequest 与 generate 各编码一次；base64 decode/re-encode 有额外缓冲。history.toList 是列表结构复制，不能认定每轮深拷贝全部字符串；重建/join 才会分配新文本。 |
| DSH 启动 | `DemoRepository.kt` animateStartup、DSH send 分支；Manifest | 仍为延时演示，明确“未运行真实 DSH”；没有真实运行启动/停止/健康/网页切换链。Manifest INTERNET 权限存在，注释“network disabled”已过时；不能据注释否认真实网络。 |
| 连接 Provider | `MainActivity.kt` 100；Settings；`LocalPluginBridge.kt` 27、89 | AriesConnectionProvider 已实例化并使用，旧文“未实例化”过时。私有 Unix IPC 类已有身份/代次/nonce 策略，但未找到生产构造/启动 caller 或 DSH JS Provider 注入链；Provider 存在不等于 runtime loaded。 |
| App 独占插件 | `mobile-dsh-plugin/package.json`、`HOST-CONTRACT.md`、core/client/lib | `dafeiyu-go-mobile-dsh` 0.1.0，有官方 slot/设置 owner、设备/工作区展示与固定宿主动作；src/index 的 apply 为空，web lib/client 为实际加载入口。Android WebView host 注入、实际 origin/session enforcement 缺失。离线 fixture 非真 DSH。 |

仍未接：跨会话长期记忆、定时唤醒/调度、邮件短信/日历外发、公共下载 URL、任意设备操作、音视频/语音/图片生成、通用 HTTP/PDF/CSV 下载解析。本文件不将这些全部列入下一批，也不把联网子会话规划说成它们已实现。

## 3. 里程碑顺序与门禁

| 阶段 | 交付范围 | 进入条件 | 可验收退出条件 |
| --- | --- | --- | --- |
| M0 Native 轻引擎基线 | 主线程完成 0.1.19 清单；真实活动顺序/回执、浏览证据、取消/持久化；记录 APK/hash 与未覆盖项 | 当前 UI/交互工作继续，保留用户数据 | 0.1.19 验收并冻结；历史测试不借作新包通过；未知用量/进度不造百分比。子会话批次单独登记源码基线。 |
| M1 受限工具子任务 | bounded child sessions，白名单、树预算、真实工具 history、取消和内存准入 | M0 完成；主线程确认解耦、授予明确文件范围 | 第 4–6 节契约与失败矩阵通过；无孙任务/超额 side effect；父返回不丢、不重复；不引入脚本或 DSH 依赖。 |
| M2 脚本沙箱评估 | QuickJS-NG / WAMR / Wasmtime 对照、ABI/许可/生命周期与隔离可行性；首选纯数据转换 | M1 门禁关闭，新的重大功能参考版本固定 | 形成 go/no-go ADR 和独立安全/内存证明；评估通过且另获实施授权后才注册脚本工具。解释器 API 不等于 OS 沙箱。 |
| M3 真实 DSH runtime | native Node 可行性门禁 → 完整锁定 CLI/Web bundle → 生命周期/鉴权/Provider/loader | M2 决策可先保留 no-go；M3 本身另获授权、资产/接口齐备 | Node 依赖闭包与目标设备实跑、真实 DSH health/RPC/web、停止退出证明、共享连接插件实际 loaded；失败不显示演示已就绪。 |
| M4 App 独占移动适配 | 真实 WebView 原生 host + 官方 web slot 插件、DSH/App 设置、工作区、在线设备、IME/safe area | M3 真实 loader/web ready；主线程确认四团队契约 | 只在本 App 当前受信顶层页面激活；导航撤权；原生设置动作和设备/目录/键盘在真实宿主及设备通过。普通网页保持休眠。 |

不承诺固定工期。M1 是下一批优先实施项，M2–M4 的证据收集可分派，但不能绕过上述实施门禁。0.1.19 发布不等于整个需求清单完成。

## 4. 下一批改动范围：供主线程判断解耦

下表是**拟改范围，尚未创建生产文件**。目录前缀为 `app/src/main/java/com/dafeiyu/mobile/data/`。主线程冻结后应逐文件指定 owner 并记录输入 SHA-256；`DemoRepository.kt` 当前共享热点，只由主线程完成最终接线或显式授予独占小范围。

| 改动单元 | 拟新增/拟修改 | 目的与受影响行为 |
| --- | --- | --- |
| 纯策略与预算 | 新增 `ChildToolPolicy.kt`、`ToolRunBudget.kt` | root/child/depth/credentialGeneration、白名单、原子预算、byte permits；可先离线逻辑验收，不依赖 UI/DSH/Aries。 |
| 工具执行接口 | 修改 `OfficialToolSession.kt` / `OfficialToolDispatcher.kt`；新增 `OfficialToolExecutor.kt` 与 `RestrictedChildToolDispatcher.kt` | session 从具体父 dispatcher 解耦，父 dispatcher 适配默认既有策略；child dispatcher 仅持有允许的只读 provider，不能持有可创建任务的 engine 或全会话任务查询能力。避免复制整套 parent executor。 |
| 独立 child runner | 新增 `BoundedChildToolRunner.kt` | 调用共享有界 session 核心，使用 child Config、小历史、独立 reducer/序列和真实终态；替换现单次文本生成的 SUBAGENT 路径。 |
| Session 收尾 | 修改 session 内部结果契约，保留父 generate 调用兼容适配 | 明确 completed/failed/cancelled/limit 与最终当前轮正文。不能沿用“snapshot.toolCalls 必须为空”拒绝前面成功工具轮，也不能把 earlier-round 正文拼成终稿。 |
| 任务准入与生命周期 | 小范围修改 `BackgroundTaskEngine.kt`、`BackgroundTaskModels.kt`；复用 credential boundary | 运行期绑定 root budget/ownership，不把凭据存进任务；保留 RunnerExecution、任务 store 和并发上限。初批只启用 SUBAGENT，BACKGROUND 保持文本 runner，避免扩展范围。 |
| Native 最终接线 | `DemoRepository.kt` initialize runner / 父 dispatcher 参数 | 注册 child runner、web/weather provider 与 root budget；任务文本、活动/回执沿现有 reducer/UI，当前 UI 文件和版本/构建脚本不归此批修改。 |
| bounded 结果路径 | 优先用 child `DeepSeekChatClient.Config` 现成限额；必要小改 session 的事件累计/结果生成 | 拒绝必须在 client 内部 reducer 和输出累积前生效。不能只在外部 onEvent 或 BackgroundTaskEngine 的最终 require 时截大文本。 |
| 离线验收 | 单独目录 `tools/tests/bounded-child-sessions-*`，生产真源码 + fake model/provider | 验证预算、递归、错误、取消、history pairing、流式限额；授权后才能建/跑 harness。本轮未创建 JVM harness。 |

`DeepSeekChatClient.kt` 全局 serializer/历史策略、`OfficialWebClient.kt` 全局 parser 大改及 AttachmentPanel/ScannerScreen 的 decode 重构应拆为后续内存子批，避免触碰 0.1.19 多模态/显示热点。M1 通过更低 child Config、无图片输入、已有 1 MiB web bound 和共享准入降低风险；若无法在这些边界以内保证 child 累积有界，则对应共享内存修复成为 M1 阻塞，不能以“后面再优化”交付。

是否需要改变 `BackgroundTaskAndroidStore` schema，应先优先采用仅运行期策略映射；重启一律 INTERRUPTED，不重建失效权限继续运行。若确需持久化 policyVersion/rootId，只保存非秘密标识、兼容旧文本任务，单独申明迁移范围。任务 regex 过滤已有，但不能当成全部秘密隔离。

成熟实例的采用方式：M1 采用 Pydantic AI 的预计用量准入、LangGraph 的有界执行与命名空间思想，在现有 Kotlin coroutine/store 模式内实现，不引入 Python/JVM 图框架或模型 SDK。引用 URL、观察文本与 SHA-256 已落研究目录；这些借鉴不取代本项目深度/取消/计费语义。

## 5. M1 协议与行为约束

1. **权限由 Native 定义**：root 是用户 turn，child depth 固定 1；depth 与 allowTaskCreation 不接受模型参数。child schemas 和 dispatcher 都只开放 `web_search`、`browser`、`get_weather`。没有 provider 时不宣告该工具。`write_text_file` 暂缓，`create_subagent/create_background_task/list_tasks/get_task` 永不进入 child allowlist。
2. **执行层拒绝**：即使模型伪造未宣告名称、重新命名/编码调用或 direct API 调用，也必须在任何 side effect 前失败。保留 engine RunnerExecution 对 create/enqueue/unfinished await 的硬门禁；prompt “不得递归”只是说明，不能作为安全控制。
3. **仅传明确子任务输入**：保留 task prompt 上限，补 UTF-8 byte 上限；初批不继承父完整历史、图片、附件 base64、未保存草稿、其他会话或 API Key。必要背景由父明确提炼且有界。网页结果是数据，不升级为 system 指令。
4. **父子共享额度**：每个 root 用不重新创建的预算 handle，所有孩子原子扣减；给 child 子配额不能超树剩余额度。计入失败执行、网络 attempts、每次模型物理请求；重试/新 ID/同组兄弟不能重置预算。缓存命中同 ID/同参数不重复消费或执行，变参拒绝；作用域 key 至少包含 rootId/childId/callId，跨 child 相同 callId 不混淆。
5. **准入与等待分离**：保留先提交全部兄弟再等候。父等待时释放模型/byte permits，网络 mutex/预算锁不得包住长等待；否则 2 个运行槽可能互相等待。任务 engine 全局并发 2 包含 BACKGROUND 与 SUBAGENT，不能额外开 child worker 绕过。
6. **真实协议历史**：assistant tool call 与对应 tool result ID/args/公开 reasoning 按真实顺序写入 child 历史；每次成功/失败结果明确，随后 child 模型轮实际完成才成为 task SUCCEEDED。预算/超时/断线/存储失败保留错误，不能伪造无工具终稿。父只消费该拥有 child 的真实终态。
7. **取消语义不偷换**：父停止等候/15 分钟等待超时沿用当前“不自动停止已提交孩子”；已经运行的孩子只在自身 wall time/额度内继续。用户明确停子任务、Key 清除/替换、provider/model/engine 改动按现有配置边界停止并撤权；迟到事件不能写成功。后台独立语义保持。若以后引入“停父同时停所属孩子”，单独定义 UI/API 后改变。
8. **取消到网络**：child 与其所有 web/model calls 属同一取消作用域；Native 停止要 cancel client/OkHttp、停止排队并释放 permits。普通网络调用禁止 NonCancellable 包装；小范围已准入 commit 仍沿既有原子语义。取消 waiter 不释放正在独立运行 child 的预算所有权。
9. **结果有界且诚实**：正文截断有 `truncated`、原字节/显示字节和原因；对完整性必要的回答失败而非静默裁剪。子任务最终摘要和网页引用可回父；大原始文本不向父再次复制。初批不新建通用 artifact read/file 工具，避免借引用引入越权入口。
10. **可观测性不暴露凭据**：回执包含 task/parent call/root、tool 名、来源 URL/时间、真实状态、限额错误；不记录 Key/token/raw credential、不把远端 presence 猜为 verified。恢复中的 task 无有效授权不再运行。

### 建议初始数值，均未实现

| 项目 | 初批建议 | 解释 |
| --- | --- | --- |
| 子任务数/执行并发 | 每 root ≤4，engine 全局 ≤2 | 复用现有 tasks 4 与 concurrent 2，不按父会话各开并发池。 |
| 子 session rounds/batch/calls | rounds ≤3，batch ≤2，calls ≤4 | 最后一轮仍有未执行 tool proposals 时标 incomplete；父 rounds 6 默认保留。 |
| 整树 calls/model attempts | tools ≤16；模型物理请求 ≤12 | parent 与 child 合计；每 child 上限不是额外额度。四孩子各自请求 3 次也可能被整树提前拒绝，不能保证每个都满配额。 |
| wall time | 每 child 从准入计时 ≤5 分钟，排队也计入 | 单次 web 沿 30 秒；model read timeout 降到 ≤60 秒且外层总 deadline 兜底。超时失败可诊断。 |
| child 输入/请求 | prompt ≤32768 字符且 ≤64 KiB UTF-8；整请求 ≤128 KiB | 包含 system/schema/tool history/转义，不只 prompt。无图片/附件。 |
| child 模型流 | 每 HTTP response ≤256 KiB，line ≤32 KiB，event ≤64 KiB | 利用现有 Config 可注入；累计各轮公开思考/正文/参数另受树流量与 history 限额，不能靠结果取 tail 控内存。 |
| child 工具回包/最终结果 | 每 encoded tool reply ≤16 KiB；final ≤16384 字符且 ≤32 KiB UTF-8 | 超额显式拒绝/标截断；原 task MAX_RESULT 65536 仍作兼容底线，不把它当正常 child 目标。 |
| 整树累计内容 | tool replies ≤256 KiB；模型接收字节 ≤1 MiB | 包含已丢弃或失败接收内容以限制反复；每次还要重算 history/request实际字节。 |
| 全局模型/web I/O | 模型在途 ≤2，web response/parse 在途 ≤2 | engine 两孩子与父模型共享门禁；父处于等待不占 permit。bytes 分配另受第 6 节准入。 |

Tokens/cost 使用真实 provider usage，仅有 byte/attempts 不能声称费用已严格封顶；未知 usage 显示未知。初始数值可经主线程在实现前调整，调整须整树同步，不能放宽单层后忘记共享额度。

## 6. OOM 风险与内存验收

字节上限、字符上限、持久化总容量与堆峰值是四个不同概念。1 MiB HTML 仍会生成 byte buffer、解码字符串、DOM、正文/JSON；base64 有编码膨胀，JSON 转义还会额外膨胀；多个父子任务可重叠。取消后的 callback/reducer/bitmap 若保留引用，预算下降不代表实际释放。本轮没有 heap/PSS 采样，不写“不会 OOM”。

| 分配点 | 现有保护 | 必须补齐/未来验收 |
| --- | --- | --- |
| web/tool 结果 | web 真读 1 MiB、正文/link 限额、tool reply 128 KiB | 解压后读/HTML 解析前准入，结构深度/节点规模或替代解析策略有界；输出 UTF-8/转义预算先计再构造 JSON；同树累计与全局两个 parser 的峰值计算。上限失败不能为过 smoke 而放宽。 |
| 子任务流与进度 | concurrent 2、progress takeLast4096、结果终值 require | client 内部 reducer 累积前的 byte/event 限额，公开 reasoning 也计入；避免每 100ms `join` 全历史再取 tail，保留有界 tail/counters；终值超额不先构造巨型字符串。 |
| 请求/历史 | 字符预检、history4096、最终 4 MiB；完整 tool transactions | 计数式 bounded writer 或流式 serializer，在分配大 String/ByteArray 前验证 UTF-8/JSON escaping；validation 不再完整编码两遍；保持 tool call/result配对，显式 context-too-large，不静默删数据。 |
| 附件/base64 | store 有界，实际 chat 原图 2.8M bytes/请求4MiB | 独立 base64长度/格式检查避免完整 decode+re-encode；受管可信图片与任意字符串分开；历史图片重复引用不反复读入，发送结束释放；无 child 继承。下层 25/36MiB API 复用仍须守更低发送限制。 |
| 图片/扫码 decode | 源 100M、target256/1400、software | 初始 decode permit=1；按目标像素×通道+扫码 IntArray/codec scratch 预留，不只文件大小；极端比例/损坏/动态图 reject，取消/离屏不更新旧 view，缓存项/字节双上限。不能靠 catch Exception 或 catch OOM 代替提前准入。 |
| task/store/output | 50 tasks/24MiB、100 outputs/16MiB；附件 metadata restore | 测 load/save 的 JSON串、AtomicFile、列表+字符串副本峰值，分页/摘要/懒读取；不能把“metadata-only附件恢复”误说成启动全读128MiB；不在 UI 收藏多份全文。 |
| IPC/DSH/WebView | 连接 IPC 请求1MiB/响应2MiB/clients4 已有 | 512KiB chunk base64/加密额外92bytes及并发缓冲纳入共享预算；Native/Node/WebView分别采样。不能把 Java heap 限制当 Node/WebView 总 RSS 限制。 |

建议 M1 新增临时 byte reservation 总量先设 **32 MiB**，只覆盖可计数的 tool/model/history缓冲，不是全 App 内存承诺。每个 admission 保守计入缓冲副本，取消/异常 finally 释放；无法量化的 parser/codec overhead 以低内存实测决定倍数与降级。decode 另设 ≤16 MiB 预留且最多 1 个。解释器初期拟 heap ≤8 MiB、单任务并发1，实际 native/module overhead 另计，M2 不直接注册生产工具。

未来低内存验收至少记录 memoryClass、Android/API/ABI、基线/峰值 Java heap、native heap、进程 PSS；父+两孩子、长历史、最大合法中文/转义文本、近限额 HTML、图片与扫码并发、取消与重复打开为固定场景。先跑 20 轮离线压力，若第 11–20 轮静置后内存持续上升或可追踪存活内容超预算，不通过；GC/平台噪声需归因，不能用单次“无崩溃”判 PASS。剩余预算不足时优先拒绝新工作/停大输入，保留草稿与真实失败状态，不能清用户数据脱困。

本轮不跑这些设备/编译测试。M1 可以先用纯逻辑累计与 bounded fake transport 证明拒绝位置，后续主线程获相应测试授权才做 JVM/设备与 heap 验证。

## 7. M1 验收矩阵

| 场景 | 必须可观察的结果 |
| --- | --- |
| 正常 child 搜索→浏览→终稿→父继续 | 实际注入 provider调用；调用ID/参数/结果/URL/时间配对；父只收所属 child 真实终态；无 claims-only 搜索。 |
| child伪造创建后台/子任务、嵌套等待、list/get其他任务 | 白名单及 engine双层拒绝；任务数、store/文件无新增；不越权读取。 |
| 同批兄弟与全局并发 | 先准入再等待，最多2 active；父释放permit；多个root/后台占用不突破全局上限，无互锁。 |
| quota并发竞争/失败/新ID重试 | 预算原子；第N+1次在网络/副作用前拒绝；失败和物理attempt不免费；缓存不重复扣费/执行；callId变参失败。 |
| tool JSON/中文/转义/HTML/流式reasoning超额 | 实际字节计数，进入大对象/reducer前拒绝；result_truncated准确；父历史和任务 store 不膨胀为多份全文。 |
| tool error、无最终正文、第三轮仍要工具 | 不记录 task SUCCEEDED；父收到 failed/incomplete而非伪造答案；保留诊断和已成功工具记录。 |
| 停父等待、明确停child、超时、Key/engine切换 | 分别按第5节语义；network cancel且permits归还；旧代次/迟到回包不写成功。 |
| 持久化失败、杀进程/恢复 | success只在原子保存后发布；未知结果不重放；恢复活跃任务INTERRUPTED，不偷偷付费续跑。 |
| 回归0.1.19父工具/多模态/活动顺序 | 既有8工具、round/call限制、父子折叠、完整tool history和附件失败保留草稿不退化；测试按冻结输入hash关联。 |

首轮必须用实际生产源码与离线 model/web fake，禁止调用付费服务。离线 PASS 只证明协议/调度，真实网络成功、模型自动选择工具和设备峰值分别需要独立授权/证据。

## 8. M2 安全脚本能力评估

优先评估纯 JSON/文本转换，输出 schema 固定，无 shell、命令反射、进程、文件系统、环境变量、网络、包安装、FFI、native模块和任意 import。模型给脚本文本不代表可直接执行；源长度、输入/输出、循环CPU、栈、heap、promise job和host callback都有硬上限。初期建议 source≤32KiB、input≤64KiB、output≤32KiB、wall≤1秒、并发1，明确是评估参数。

QuickJS-NG 有内存/栈/interrupt/allocator接口，适合JS数据转换候选；必须验证采用的Android wrapper暴露这些控制且正确在runtime线程销毁。WAMR有Android目标、pool/实例内存/terminate，可评估WASM；Wasmtime的fuel/epoch/stack可做机制对照，但包体/Android信号/虚拟预留与ABI未核验，不默认选用。

解释器限制与独立Android隔离服务分别验收。与App同UID的新进程只隔离崩溃/堆，不能保证私有文件或凭据不可访问；isolatedProcess需要宿主最小IPC、无法访问Key和目录的证明，还要验证native取消/卡死/崩溃后Native回收。JNI callback必须有界且不能通过取消绕过host权限。不得把DSHA proot称为轻引擎脚本安全沙箱。

评估交付：固定候选版本/许可证/源码摘要、ABI与Android兼容证明、威胁模型、允许输入输出、内存和取消日志、失败恢复、go/no-go。本轮没有构建解释器或执行脚本；未通过前不向模型广告script工具。一般Python/终端/pnpm生命周期脚本另列能力，不在这一小沙箱内暗示支持。

## 9. M3 真实 DSH runtime 阻碍及执行门禁

### 已核实的阻碍

`app/build.gradle.kts` 配置 `../Temp_Android/runtime/{jniLibs,assets}` 与legacy native packaging。资产根仅见连接tgz/manifest、Node runtime-lock；只有arm64-v8a。没有在该资产根发现完整Android DSH CLI/Web部署包、rootfs/proot或x86_64 runtime。

Windows宿主 `G:/DeepSeek DSH/node_modules/@deepseek-ai/dsh-web-frontend` 实际存在0.2.1-alpha.1及dist/assets，所以“完全没有官方Web前端”不准确。缺的是可重现、锁定、Android可执行的整套CLI/Web依赖和部署链。已有DSHA参考基线DSH0.2.0-rc.2与此Windows包版本不同，不拼接版本后宣称兼容。

Node24.18.0静态ELF为AArch64，interpreter `/system/bin/linker64`，RUNPATH `/data/data/com.termux/files/usr/lib`。NEEDED与当前准备文件名不完全一致：

| 动态加载需求 | runtime-lock中的restore/包文件事实 | 缺口 |
| --- | --- | --- |
| `libz.so.1` | restore `libz.so.1.3.2`，包 `librt_libz_so_1_3_2.so`，ELF SONAME `.1` | 需要实际可解析的加载名称/别名；仅SONAME元数据不等于文件搜索成功。 |
| `libsqlite3.so` | restore `libsqlite3.54.0.so` / `libsqlite3.so.3.54.0`，ELF无SONAME | 需要明确唯一来源及 `.so`映射，不能猜其中一个覆盖。 |
| `libicui18n.so.78` / `libicuuc.so.78` /间接 `libicudata.so.78` | restore名带 `.78.3`，包统一 `librt_*`，SONAME `.78` | 验证别名与间接依赖完整闭包。 |
| `libcares.so`、crypto/ssl、`libc++_shared.so`等 | 文件被重命名为 `librt_*`，不少RUNPATH仍指Termux | 启动前正确私有布局/loader搜索/ABI与NDK系统依赖，不能依赖不存在的Termux安装。 |

prepare脚本注释说启动恢复SONAME名称，但未找到production恢复/启动实现。16/16摘要一致只是材料完整，不是加载成功。也不能据静态名称宣布在所有Android版本“必然不可执行”；必须先补布局再在授权目标设备验证。

### 按顺序的runtime交付

1. native feasibility：固定ABI/Node lock，静态NEEDED递归闭包、restore映射及重复库来源，确认当前target Android的可执行位置/loader规则；申请独立实际启动验收。native不能满足时再比较nodejs-mobile重新构建或DSHA私有rootfs/proot路线，记录成本/内存/包体和安全差异。不能静默切换路线。
2. 真实bundle：PC/DeveloperCenter提供DSH入口、CLI/Web/client/插件loader版本、完整lock/摘要/native-addon清单；Windows node22/win-x64 bundle不能当arm64 Android包。验证闭包、离线解包安全与native addon Bionic/ABI；不运行未知install脚本。
3. 原生RuntimeOwner：prepare→starting→health-ready→web-ready→stopping→stopped/failed状态，以实际进程/health/RPC证明；单代次，固定私有HOME/DSH_HOME/TMP与cwd，动态loopback端口不对LAN开放，启动日志有界且脱敏。
4. 鉴权网页：实际`web --no-open --host 127.0.0.1 --port <动态端口>`及token/cookie流程必须以所选DSH版本协议验证。明文loopback不是只有本App可访问；密钥/token不进入模型/插件普通快照，不能只按端口存活认为本代服务ready。
5. 停止与恢复：跟踪Process、UID、PID出生身份、generation，TERM/超时/必要force均验证退出；不按端口/进程名/裸PID杀进程。未知停止保持屏障，不能更新资产/切换home后宣告成功。系统后台/前台服务通知策略需按实际target和当前Android官方规则在实施前核验，现manifest无该runtime服务。
6. 共享连接：LocalPluginBridge实际构造/start/bindRuntime，提供JS `connectionProvider`并在shared plugin apply前注入；RPC/IPC编号、能力、取消、组ID/代次撤销、恢复顺序实测。prepared→loaded→paired/sync各自独立证据。
7. 验收：真实Node版本输出/DSH health、加载官方web、启动/停止/再启动、无残留owned进程、坏bundle/无ABI/拒绝权限/端口冲突、用户工作区不受损、密钥不泄漏、低内存Native/Node/WebView峰值。x86_64模拟器没有运行资产时明确unsupported，不能拿仅UI预览证明arm64 runtime。

## 10. M4 App 独占移动适配验收

使用独立 `dafeiyu-go-mobile-dsh`，共用 `dafeiyu-go-connect`只负责连接。官方loader/web模块真实可用后从`lib/client.js`加载；不得以src/index空apply判断网页插件已加载。当前官方slots：settings.launcher、sidebar.footer.action、shell.bottom、shell.overlay，继续沿官方owner/callback，不复制私有页面DOM。

Native宿主协议沿 `mobile-dsh-plugin/HOST-CONTRACT.md` 的 `dafeiyu.mobile/1`。在当前受信顶层document安装桥；校验实际来源/端口、当前session与权限，导航/登出/host更换撤销。来自页面的origin/session字符串不是身份。普通网页/iframe/旧回调/桥对象替换均不能获取App动作。

只接受固定六字段 `open_app_settings`请求，不接受任意URL/命令/参数或反射。插件一次pending、12秒等待、取消丢弃迟到reply；Native还需message长度/速率/幂等/防重入。DSH设置用官方openSettings，App设置由原生路由打开，同排入口真实可用。成功reply只表示已交接原生入口。

设备源是Aries当前真实快照，member与keyVerified分离，unknown/stale不写online；最多64设备、60秒失效沿现插件策略，host不得刷新旧观测时间伪装新鲜。workspaceId必须匹配官方workspaces list；用户SAF URI必须映射为进程可访问目录，不能将URI写成Node path。

safeArea/keyboard单位CSS px；Native已消费的inset注入0，resize/overlay/VisualViewport只消费剩余遮挡一次。真实窄屏、横竖屏、IME、导航返回、无设备/过期事件、插件卸载与Host session切换验收。动画应有出现/过渡/消失、沿共享贝塞尔/减少动态效果设置，图标优先现有库；不以离线截图代替真实host/设备结果。

内存验收：快照不可原地修改，revision单调，事件合并但不能把旧数据盖新；subscription卸载/导航释放、设备上限、pending最多1，不反复clone大历史或暴露Key/base64。录制可复核loader/host/native动作链与状态，不新增付费验证默认授权。

## 11. 四团队接口请求：由主线程协调

| 对接方 | 需要提供的冻结材料/接口 | 手机端依赖与验收 |
| --- | --- | --- |
| PC | 实际DSH CLI/Web/client/loader版本与插件入口、完整lock/native-addon列表；运行命令/health/RPC鉴权与DSH_HOME；Provider生命周期、wire合成fixtures及其hash | Android不照搬win-x64/node22资产；明确哪个协议跨平台稳定、workspace冲突/原子提交/幂等与取消。PC28项历史联调不是手机互通验收。 |
| 共用连接插件 | 0.2.0冻结tgz精确SHA/入口/contract/runtime文档；`ctx.provide('connectionProvider',...)`先于apply；能力协商、dispose/reload、失败和取消契约 | 当前候选88680bytes，SHA `9df7085cd5fa9ecf0e6f9a31aace8d0272ef60c4a9aef12a2ea96efafdf6aa1b`来自通知记录；上线前重验实际包。JS Uint8Array与IPC标准base64适配，禁止复制一份手机密钥协议。 |
| DeveloperCenter | `GET /api/connection-plugin/releases/current`及package端点的可用环境/冻结manifest；Android可执行DSH bundle单独协议、平台/ABI/Node/CLI/Web锁与大小；release/current授权与回退 | 已有Windows后端bundle不是Android供给；min_host_version1.8.0指Windows启动器，Android按Provider协议/能力校验。prepared receipt与真实loaded回执分别归档；生产尚未部署时如实不可用。 |
| Aries | presence/member/keyVerified/observedAt/stale；group/epoch变化撤权、认证失败/超时/重试语义；512KiB chunk、92byte加密开销、空文件认证、hash完成后ack及合成双端fixtures | IPC standard base64不同于envelope无padding Base64URL；当前group必须一致，身份/私钥/Aries token由Native持有。消息ack本地持久消费、文件ack真实服务端端点；实际两端加密/冲突/断网/重启互通独立验收。 |

共有约束：显式同一DSH_HOME，偏好文件`dafeiyu-connection-preferences.json`，shared plugin状态/队列目录不得当缓存清掉。JS/IPC与Native能力分界不扩展到轻引擎script或child任务。四方材料需要标版本、hash、时间、适用平台和未验收部分；没有返回材料时保留pending，不编造承诺或任务完成。

## 12. 主线程可接续的交付包

1. 先确认0.1.19的APK/hash/QA与源码冻结；本次审计不代替冻结报告。
2. 审阅第4节拟改文件owner和第5节协议/默认预算，判断SUBAGENT与BACKGROUND分离及session兼容改动可解耦后授予M1范围。
3. M1分三段交付：纯policy/budget与失败矩阵 → restricted dispatcher/child runner → 主线程Repository接线及父流程回归。各段先证据后勾选，不扩大到script/DSH/UI重构。
4. 主线程转交第11节四方接口需求；本负责人未消息外部chat，也未收到本轮新承诺。
5. 完成M1后继续M2–M4清单，按门禁逐项提供源码、离线、真实runtime、设备证据，不以“规划就绪”或“prepared”宣告产品全部完成。

研究复跑入口为 `Research-GitHub.ps1` / `Read-ReferenceText.ps1` / `Audit-Local.ps1`；只在项目研究目录生成新时间戳日志。收束校验仅检查PowerShell语法、现有JSON/hash与文档链接，不运行生产源码。用户已要求不再扩大research，本轮不继续网络请求或新增研究脚本。所有研究日志保留为证据，不清除其他线程临时文件。
