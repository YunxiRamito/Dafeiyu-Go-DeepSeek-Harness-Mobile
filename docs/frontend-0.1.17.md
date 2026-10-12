# Android 0.1.17 · 最终交付与验证

日期：2026-10-11，Asia/Shanghai。最终交付 **0.1.17 / versionCode18**，包名 `com.dafeiyu.mobile.preview`，minSdk 30、compile/targetSdk 35。短屏几何、菜单互斥等 P2 修复已合入，最终构建、包验证、安装、指定任务菜单 QA、打开浮层时收起 IME 及日志采样已完成。本文记录已提供的证据与范围，不据此宣告所有场景完成。

本文覆盖 README、HANDOVER、清单及 0.1.16 文档中与本批进展冲突的当前状态说明；旧章节、旧 APK hash 和旧设备结果保留为历史证据。最终 APK hash 已提供并在本地核对，主 Agent 已更新 `frontend-build.json` 的 version/hash/build/QA，四文档冻结后由主 Agent refresh 输入 hash；本次文档任务不修改该文件。旧包结果不能代替本批证据。

## 最终交付证据

主 Agent 回报最终构建与模拟器 QA 结果；文档负责 Agent 只读核对 APK 字节数/SHA-256、构建日志、Lint XML、几何测试日志、QA 脚本与截图文件存在，没有再次启动 Gradle、设备或付费 API。以下最终记录覆盖后文首次构建和修复前阶段的当前状态。

| 项目 | 最终证据 |
| --- | --- |
| APK | `../Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.17.apk`（仓库根目录相对路径），93,408,741 bytes |
| APK SHA-256 | `75aef8043afe6425b1075b56863579ac0d6ee2d7575ad15e0a83e7b0d8646d2c` |
| 构建 | assembleDebug + lintDebug SUCCESS，3m55s；46 tasks，13 executed / 33 up-to-date |
| 构建日志 | [build-0.1.17-final.log](../../Temp_Android/build-0.1.17-final.log) |
| Lint XML | [lint-results-debug.xml](../../Temp_Android/build/app/reports/lint-results-debug.xml)：0 Error / 137 Warning / 5 Information，非零警告 |
| 签名 | apksigner verify 成功，沿用证书 SHA-256 `52fc036fd6fe45d400f354235068aab29cb27ca6f68e42c53368a5b00c887b23` |
| 包元数据 | aapt 确认 versionCode18 / versionName0.1.17 / min30 / target35 |
| 安装 | API37、emulator-5554 安装最终 APK 成功 |
| 几何验证 | Franklin 实际生产文件独立 Compose -Werror 编译；288 bounded cases 通过 |
| send-persistence 复查 | 第四参数适配后 13/13 通过；日志目录 [send-persistence-harness-recheck-20261011-183006](../../Temp_Android/qa/send-persistence-harness-recheck-20261011-183006) |

137 条 Warning 中有 4 条 ConfigurationScreenWidthHeight，位于 AnchoredGlassMenu 安全窗口尺寸回退（line119/123）；保留实际 Lint 结果，不称零警告。首次构建的 0/135/5 是较早阶段，最终以 XML 的 0/137/5 为准。

## 最终任务菜单 QA

已完成 [verify-task-menu.ps1](../../Temp_Android/qa/emulator-0.1.17/verify-task-menu.ps1)：子 Agents/后台任务分组与实际 fixture 状态、对应详情 prompt/result 准确、返回主页、plus 菜单功能保持、320x400dp 短屏 footer 完整可点，以及查看全部任务后的全部/后台/子 Agent filter 路由通过。详情采样来自真实任务页面，未用独立假详情替代。此前同一轮已验证 48dp 群聊位于加号左侧、长标题省略、返回两次回首页、外部点击及边缘返回关闭、320dp 窄屏布局；这些证据仍保留为各自已测范围。

主 Agent 已检查以下最终截图：

- [final-task-open.png](../../Temp_Android/qa/emulator-0.1.17/final-task-open.png)
- [final-task-detail.png](../../Temp_Android/qa/emulator-0.1.17/final-task-detail.png)
- [final-task-short.png](../../Temp_Android/qa/emulator-0.1.17/final-task-short.png)
- [final-task-short-all.png](../../Temp_Android/qa/emulator-0.1.17/final-task-short-all.png)

互斥已采用双向 surfaceVisible + 点击 gate；plus 减少透明度、tasks route 独立 floatingBackdrop 已接入。几何生产文件及 288 bounded cases 验证包含受限窗口、IME/横屏等布局边界，但这不等于输入法持续流式的设备端验收。

fixture 不调用模型。最终 QA finally 已复制回原 task data，经 cmp identical 确认一致，backup 和临时 fixture 已删除，wm size reset 已执行，显示恢复 1080x2400、density420。两类暂无的空状态属于此前阶段测试，不表示最终恢复后的用户数据为空；用户现有 selftest-A/B 任务保留。[final-restored-home.png](../../Temp_Android/qa/emulator-0.1.17/final-restored-home.png) 是恢复后当前页面截图，最后模拟器存在用户交互，截图包含用户任务菜单，不称纯主页或最终空任务。

最终 IME 实测先为 mInputShown=true / mImeWindowVis=3，点击群聊按钮打开浮层后为 false / 0；[final-task-from-ime.xml](../../Temp_Android/qa/emulator-0.1.17/final-task-from-ime.xml) 确认子 Agents/后台任务两组及关闭入口。runtime PID15474，AndroidRuntime `-d -t 240 -s AndroidRuntime` 采样无输出；dumpsys 已安装版本为 v18 / 0.1.17 / min30 / target35。主 Agent 核对所有生产源码 lastwrite 均不晚于 APK package 时间 2026-10-11 18:31:33。该 IME 结果仅证明打开任务浮层时键盘收起，不替代持续流式或真实付费生成测试。

## 源码状态

| 范围 | 2026-10-11 基线 | 验收边界 |
| --- | --- | --- |
| 发送与本地保存 | 用户候选消息先用实际索引序列化器在 IO 上原子保存；取消并等待旧防抖 writer，使用 persistenceMutex 串行保存。未保存错误阻止下一次发送，并提供重试 | 保存成功才接受发送；草稿仅在同一会话、同一编辑版本且返回成功时清空。并发发送被 pending 状态拦截 |
| 回复恢复 | 主索引上限 8 MiB；完整快照恢复文件上限 64 MiB，带基准索引 SHA。启动读取有效恢复快照，重试成功后回收；拒绝过期恢复文件覆盖更新索引 | 最终回复超限或索引 IO 失败可进入恢复文件；两处都失败时如实报告内容仍在内存，不能声称已持久化。恢复不是无限容量保证 |
| 附件生命周期 | 启动只恢复本地文件元数据；选择文本时再读取内容，图片与未引用文件保留在附件托盘。显式删除未使用项检查已保存引用及待提交引用 | 本地总 payload 128 MiB、最多 512 项；单文件 10 MiB、选择合计 25 MiB、单文本 256 KiB、本轮文本合计 512 KiB。持久容量不等于启动全文缓存 |
| 图片与完整请求 | JPEG/PNG/GIF/WebP 有受限本地读取入口；生产发送已调用图片读取、OfficialChatHistory 和完整请求校验，位于取消旧 writer 及候选保存之前。Linnaeus 已修复历史附件缺 catalog 静默丢失，改为严格失败保留草稿 | 回报附件预检 14、chat 37、tools-vision 35 项离线通过；不能承诺所有 DeepSeek 模型支持图片，离线 fixture 不代表真实账号兼容性 |
| 后台任务 | 独立任务模型、队列、存储与应用级 engine 已有源码；保存/清除 Key 已调用任务停止边界，配置通知入口已接线 | 应用级 scope 不代表 Android 杀进程后仍持续执行。所有配置切换取消行为及前台工具联动仍需最终验证 |
| 主页任务浮层 | MainActivity 已在加号左侧接入 AgentsTaskActions，独立 48dp 双气泡群聊按钮；列表仅子 Agents/后台任务，通过 StateFlow + lifecycle 订阅真实状态，支持空状态、滚动、标题省略、对应详情及查看全部任务 | emulator-5554 / API37 最终指定 QA 已完成，详见上方；不含 Outputs/Sources，复用 AnchoredGlassMenu 形变、预测返回与外部关闭。短屏几何及互斥修复已纳入最终构建 |
| 模型工具循环 | 生产 sendDeepSeekMessage 已调用 `OfficialToolSession.generate`；受限 dispatcher、工具协议及本地输出 store 包含任务创建/查询、文本文件与天气工具，已纳入最终构建 | 源码调用和独立 fixture 不等于真实付费请求的整条用户链路验收 |
| 天气与搜索 | `get_weather` 使用固定 Open-Meteo 地名与天气端点；工具参数接受城市及可选国家/地点选择，不接受任意 URL | 没有通用网页搜索或浏览器。重名地点需要选择；公网天气可用性、离线 fixture 和模型触发是不同验证范围 |
| Markdown 与生成文件 | `ChatScreen` 已调用基于 CommonMark 的 `ChatMarkdown`，助手回复已调用 `GeneratedFileStrip`，生成文件导出入口已有源码 | 长文本、链接、流式布局、导出及最终 APK 效果待验收；回复生成文件不属于任务浮层，浮层排除 Outputs/Sources 不表示移除回复文件入口 |
| 连接插件包 | 冻结 0.2.0 包校验、准备、原子 receipt、上一 prepared slot 回退与 build assets 准备已有实现和离线测试 | receipt 为 prepared；`current()` 指向当前已准备包。没有 Android 宿主 runtime 激活证据，loaded pending |
| DSH 移动插件 | 已有移动插件 fixture/协议基础 | DSH runtime 尚未接线，不能写成真实模型或插件执行完成 |
| 图标 | SF 转换来源当前计数由历史 39 增至 41，含 `trash.bold` / `sf_trash.xml` 与新增 `bubble.left.and.bubble.right.bold` / `sf_groupchat.xml`，沿用同一固定数据集 | 来源是第三方 UsePreside/sf-symbols，不是 Apple 官方仓库；icon-provenance 由主 Agent 维护，来源记录不授予再分发许可 |

8 MiB 限制按真正保存的 UTF-8 JSON 字节计算，包含转义、消息快照和 `modelInput`；附件正文重复进入索引时也计入容量。预检不只按可见正文长度估算。恢复文件保留完整序列化快照，不靠静默裁剪源正文绕过上限。既有集合保留范围仍为最多 100 个会话、每会话 1000 条消息，恢复文件并不取消这些范围。

图片/工具请求预检位于 `sendPending` 的 try 内、取消旧保存 writer 之前；预检失败返回失败并保留草稿。生产发送和工具循环已纳入最终构建，本记录中的测试次数只对注明的阶段及入口成立。

本轮已核对 MainActivity 与 AgentsTaskActions 的源码入口、lifecycle 订阅、任务分组和导航调用。最终构建及任务菜单 QA 已记录在文首，后文早期测试与首次构建作为阶段证据保留，不据此扩展未测试功能的验收范围。

## 连接包冻结与 prepared 边界

冻结包：`../../Dafeiyu-GO-Connect-Plugin/Temp/Runtime-20261011/package/dafeiyu-go-connect-0.2.0.tgz`（从仓库根目录引用为 `../Dafeiyu-GO-Connect-Plugin/Temp/Runtime-20261011/package/dafeiyu-go-connect-0.2.0.tgz`）。

SHA-256：`9DF7085CD5FA9ECF0E6F9A31AACE8D0272EF60C4A9AEF12A2EA96EFAFDF6AA1B`。

`tools/prepare-connection-plugin.ps1` 无参数默认使用仓库父目录 `Temp_Android`，接受 build 的 `-TempRoot`；`tools/build.ps1` 已在 Gradle 调用前以 `&` 调用它。脚本精确复制冻结 TGZ 与对应 manifest 到 `runtime/assets/connection-plugin`，SHA 不符则失败，不修改外部共享插件。

验证器限制 manifest 8 KiB、archive 16 MiB、解包 32 MiB、条目 1024；拒绝路径穿越、链接、重复条目及安装生命周期 scripts，普通 test/check/build scripts 允许。安装失败保留旧已准备状态，`prepared.json` 记录当前和上一 slot；`rollbackPrepared()` 可供未来宿主加载失败后回退。回退只切换已校验 prepared 包，不表示宿主加载成功。

[连接插件通知](CONNECTION-PLUGIN-NOTICE-2026-10-11.md) 中较早的 Android“未验证”描述，由本批包准备/离线攻击与失败回滚测试在该范围内更新；通知中的桌面 E2E 仍不能作为 Android runtime 验收。Windows launcher 的 minimum_host_version 不等于 Android APK 版本要求。

## 已执行测试

持久化负责 Agent 观察到以下结果；workspace-storage/assistant-stream 为前序阶段基线，send-persistence 已在父 Agent 新图片/工具预检合入后适配并重新执行。这些次数不能代替父 Agent 对最终完整应用重新运行的结果。

| 测试入口 | 观察到的结果 | 范围 |
| --- | --- | --- |
| `tools/test-workspace-storage.ps1` | 21 PASS | 8 MiB 边界、实际 UTF-8/转义与 modelInput、原子写入故障、完整回复恢复、64 MiB 上限、过期 journal、回收与引用读取失败 |
| `tools/test-assistant-stream.ps1` | 21 PASS | 流式事件、状态与快照协议 |
| `tools/test-send-persistence.ps1` | 13 PASS，预检合入后复测；原 11 项加 2 项 | 提取生产 persistence/precommit 函数体，在 JVM 单线程 dispatcher 上验证先保存后调用、容量/IO 失败、未保存阻断与重试、重复发送、旧 writer 等待、配置/会话变更及取消；新增预检失败不取消旧 writer/不写索引、pending 预检拦截重复发送 |

上表现有结果合计 55 项，阶段不同，不是全仓总数。发送 harness 只替换 Main 为专用 JVM 单线程 dispatcher；生产 IO、Mutex、取消、序列化、原子保存及 precommit 分支保持实际代码。history、图片读取、工具 schema、请求校验和 system message 是离线替身，provider 仅为计数器；不测试这些替身所代替的模型/图片/工具功能。较早五参数 sendDeepSeekMessage fixture 当时匹配 history 参数；最新第四参数适配后的复查仍为 13/13，见文首日志。不把旧 fixture 签名当作当前接口，没有调用真实付费 API，也没有证明 Android 杀进程时的设备级行为。

本次先确认旧 harness 因新增依赖编译失败，再适配 `tools/tests/workspace-storage/SendPersistenceHarness.kt.txt` 并新增上述两项回归。复测生成文件位于仓库父目录 `Temp_Android/qa/send-persistence/run-036a9ef85c6e411bb895f98586938686`，控制台报告 13 PASS。没有启动 Gradle、设备或真实天气/付费 API。

连接包测试入口 `tools/test-connection-package.ps1` 已在前一任务完成有效包、攻击包与失败原子回滚测试，含 prepared 状态与准备脚本隔离 fixture；本记录不猜测其测试项总数。附件、任务、工具、天气、图片与工具循环的相关入口已存在，包括 `test-conversation-attachments.ps1`、`test-attachment-lifecycle.ps1`、`test-background-tasks.ps1`、`test-official-tools.ps1`、`test-official-weather.ps1`、`test-deepseek-tools-vision.ps1`。本次文档编写没有重新执行这些入口，最终结果需由负责 Agent/父 Agent 明确提供。`test-official-weather.ps1 -PublicSmoke` 是单独的公网天气检查，不应与离线测试混算。

## 首次构建与阶段证据（历史，主 Agent 回报）

2026-10-11 收到以下阶段结果；文档负责 Agent 未重新执行构建、设备或这些离线检查。当时 P2 修复与重建尚在进行，现已完成并由文首最终证据覆盖；保留下表作为历史记录，不把离线 fixture 写成真实模型执行。

| 检查 | 回报结果 | 范围与限制 |
| --- | --- | --- |
| 首次 assembleDebug + lintDebug | 成功；Lint 0 errors / 135 warnings / 5 info | 修复前阶段构建；最终构建与包证据见文首 |
| `tools/test-official-attachment-preflight.ps1` | 14 PASS | Linnaeus 修复历史附件缺 catalog 时静默丢失；严格失败并保留草稿 |
| `tools/test-deepseek-chat.ps1` | 37 PASS | 离线 chat 回归，不代表真实付费生成 |
| `tools/test-deepseek-tools-vision.ps1` | 35 PASS | 离线工具/图片回归，不代表真实模型触发工具或图片兼容性 |
| emulator-5554 / API37 主页任务浮层 | 指定交互与 320dp 窄屏布局通过 | 使用本地离线 fixture，不调用模型；当时等待恢复，后续已 cmp identical 恢复并清理，见文首 |

阶段模拟器已测范围：48dp 群聊按钮在加号左侧；分组仅子 Agents/后台任务、不含 Outputs/Sources；显示实际 fixture 任务状态；长标题省略；点击任务打开对应详情；返回两次回首页；外部点击和边缘返回关闭浮层；加号菜单功能保持；320dp 窄屏布局通过。真实状态展示来自本地 fixture 存储，不表示付费模型实际创建或执行任务。短屏几何修复后的最终 320x400dp QA 已另列于文首。

此前 55 项持久化/流式结果和本次 14/37/35 结果属于不同入口与阶段，不汇总为最终全仓测试数。DSH runtime 尚未接线，通用网页搜索不存在；本轮未调用真实付费模型，真实生成、工具循环与图片模型兼容性仍无付费端到端验收证据。

## 范围与后续验证

- 最终构建、包验证、安装、指定任务菜单 QA 与原 task data 恢复已完成；frontend-build.json 由主 Agent 更新，文档负责 Agent 不改写。
- 阶段空状态、最终浮层收起 IME、AndroidRuntime 日志采样及数据恢复已有结果，范围如上；生产几何用例及本次打开浮层操作不代替设备端持续流式行为检查。
- 真实付费生成、工具循环与图片模型兼容性未做端到端验收；输入法展开时持续输出、发送/停止/重复点击、保存失败草稿、附件生命周期、任务取消、Markdown 长文本与生成文件导出等未报告设备结果不补写通过。
- 后续调试使用 `G:\Android SDK\platform-tools\adb.exe` 与 Android Studio `Medium_Phone_API_37.0`，每条命令明确指定在线 emulator 编号。本批为 emulator-5554；0.1.16 烟测仅为历史证据，模拟器也不等于实物相机、触感或真实手机性能验收。
- Android 连接宿主加载、DSH runtime、通用搜索、Aries 完整审批/传输/同步及 Android APK 更新流程仍需后续接入；不得据本批源码或 fixture 宣告全部清单完成。

0.1.17 最终 APK、构建、签名、包元数据、安装与上述任务菜单 QA 已有证据；仍保留未报告场景和运行环境的边界，不宣告原始清单全部完成。文档交接不触发构建、设备操作或付费模型调用。
