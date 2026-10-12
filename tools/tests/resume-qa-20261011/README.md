# 0.1.19 QA 准备交付

更新日期：2026-10-12，Asia/Shanghai。输出仅在本目录。本轮最小 QA 修正针对真实 009 XML 的空 bounds 扫描失败，准备阶段 offline **37/37 PASS**；最终结果路径、输入 hash 和冻结时间以 `freeze-manifest.json` 为准。主 Agent 已有新包默认中心滑动成功证据，完整活动矩阵仍未通过。本 QA worker 未修改生产代码、未执行设备操作、未启动 JVM/Kotlin/Gradle、未联网、未调用模型。主 Agent 的构建和设备结果独立记录；准备冻结不绑定最终 APK hash。

历史冻结状态：00:18 诊断后的上一轮仅更新 README/review，按当时要求未重新冻结，曾导致 README 与旧清单不一致；旧 32/32 结果保留为历史证据，随后环境说明已重新冻结。本轮按主线程授权修正空 bounds 候选、离线回归和记录后，依次执行 offline、读取 PASS、refreeze、source contract 校验。冻结后不再修改 QA 输入，不能绕过冻结门禁。

当前实际设备安排为独立 AVD **`Dafeiyu_QA_API_37` / `emulator-5558`**，使用 **host GPU + WHPX**，启动覆盖 **4096MB 内存 / 4 core**；此配置由主 Agent 提供，本 QA worker 不执行设备或加速探测。主 Agent 报告新包全构建 `BUILD SUCCESSFUL`（3m11s），即将验收；构建成功不等于新包设备通过，新 APK 仍须使用自身 hash 过门禁。本目录不启动、停止或修改 emulator，不触碰用户原 stalled qemu 进程。Serial 无默认值，当前执行明确传入 emulator-5558，拒绝手机 serial；主 Agent 的安装、启动及 QA 必须串行且互斥。

历史安排保留：早期曾计划复用/较小内存重启 emulator-5554，也曾考虑独立 port5556、`-read-only -no-snapshot-load -memory1536`（约1.5GiB）。这些是当时的候选方案，不是当前5558实例的启动配置；当前4096MB/4core覆盖取代旧低内存安排，不据历史方案推断实际设备状态。

## 当前 metrics 合约

`VerifyActivity019.ps1` 使用 `Get-QaCompleted019Metrics`，严格验证**同一个**合并 accessibility 节点中的字段：`已完成`、`模型轮数 2`、`实际执行工具步骤 13`、`累计用量 777777 token`、`耗时 125.0 秒`、`任务进度未知，未提供任务总量`。使用字段精确匹配，拒绝 20 轮/130 步的子串误匹配；检查信息条与 composer 不重叠。

当前生产组件使用 FlowRow + clearAndSetSemantics，可视为 `2轮13步骤 · … t/s`、`777.8k tok · 中 …`、`未知 · 已完成`。可视子项可能不出现在 UI XML，故可视缩写、图标、换行和遮挡以 PNG 人工复核为准。本批不再安排旧横向 metrics 手势。旧 `轮数 2`、`工具步骤 13`、`Tokens 777777`、`耗时 125.0 s` 仅用于明确标记的 0.1.18 历史 XML 回归，不用于实际 019 runner。

`offline-current-run-info.xml` 是合成 accessibility 合约样本，不是实际新 APK 的布局截图。速度/缓存样本数值不作为 fixture 实际速度/缓存的验收结果。

## 被测 App 配置门禁

**当前 QA 目标是 emulator-5558。00:18 诊断已记录当时被测 App 凭据 family 不存在；新包每次运行仍须重新过实时存在性门禁，不能沿用旧快照。**历史 emulator-5554 的被测 App 凭据状态未认证。没有声称整个设备无凭据，也不要求证明其他 App 无凭据。

runner 在最终 APK/已安装 APK 版本及 hash 门禁后，且在 force-stop、备份、上传、注入、launch 之前，使用 run-as **仅检查存在性**：

- `no_backup/.deepseek_api_key.v1.bin`
- `no_backup/.deepseek_api_key.v1.bin.bak`
- `no_backup/.deepseek_api_key.v1.bin.new`

不读取、解密、hash、删除或移动凭据内容；目录、符号链接、AtomicFile sibling 存在均拒绝，run-as 错误及未知返回也拒绝。成功时仅记录“当前被测 App 凭据 family 不存在”。当前生产 BalanceStore 的路径合约被 source hash 绑定；launch 前再查一次。主 Agent 必须独占设备，不在 QA 期间配置 Key。

另提供 `VerifyAppUnconfiguredUi.ps1`，**只读取主 Agent 已安全取得的模型设置页 XML，不调用 ADB、不导航设置、不点击按钮**。要求 DeepSeek/模型目录场景、唯一空 password EditText、禁用的“安全保存”，并拒绝“已加密保存”“清除 Key”“获取模型”“读取中…”及未知场景。它只证明保存的 UI 快照，不替代 runner 的实时存在性门禁。不要在状态未知且原任务可能存在时，为取得 UI 快照贸然启动 App；不要点保存、清除、获取模型、检查配置或查询余额。

若 App 是否未配置仍未知、存在任一凭据文件、run-as 不可用，或主 Agent 不愿对既有 App 执行此只查存在性的门禁，**device QA 必须使用 fresh emulator / 新的临时 App sandbox**。同 AVD 的 `-read-only -no-snapshot-load` 可能仍沿用已有 userdata，不自动保证 App 未配置；新实例也要过门禁。不要清理既有实例/手机的凭据来强行通过。

## 可执行入口

以下命令均从共享项目根目录运行。前两条只运行 PowerShell 和本地数据，不触发 ADB/JVM/联网：

```powershell
pwsh -NoProfile -File ./tools/tests/resume-qa-20261011/VerifyOffline.ps1
pwsh -NoProfile -File ./tools/tests/resume-qa-20261011/VerifyActivity019.ps1
pwsh -NoProfile -File ./tools/tests/resume-qa-20261011/VerifyFreeze.ps1 -CheckSourceContracts
```

只有主 Agent 提供最终 0.1.19/code20 APK 及 SHA256 后，运行原生 aapt + host hash 门禁。此入口不启动 JVM/ADB：

```powershell
$apk = '<main-agent final 0.1.19 APK absolute path>'
$sha = '<main-agent final APK SHA256, 64 hex characters>'
pwsh -NoProfile -File ./tools/tests/resume-qa-20261011/VerifyFinalApk.ps1 -ApkPath $apk -ExpectedApkSha256 $sha
```

主 Agent 完成安装/模拟器准备、授予该 App QA 独占执行后，才运行下面**会操作模拟器**的命令。本目录不会安装 APK：

```powershell
pwsh -NoProfile -File ./tools/tests/resume-qa-20261011/VerifyActivity019.ps1 -Run -FinalReady -MainAgentOwnsEmulator -Serial emulator-5558 -ApkPath $apk -ExpectedApkSha256 $sha
```

主 Agent 独占 ADB 时，可先运行专用两次滑动诊断。Serial 使用实际独占实例，以下示例为 emulator-5558：

```powershell
pwsh -NoProfile -File ./tools/tests/resume-qa-20261011/VerifyActivity019.ps1 -Run -DiagnoseScroll -FinalReady -MainAgentOwnsEmulator -Serial emulator-5558 -ApkPath $apk -ExpectedApkSha256 $sha
```

诊断仍通过全部版本/hash、被测 App 凭据、backup 根及事务门禁。它直接验证初始收起态、点击完成标签并验证展开，只做一次中心滑动和一次从当前 XML 推导的右侧 padding 滑动，各保存 fresh XML/PNG。没有 Find/48帧检索、会话切换或窄屏操作；退出仍经过原完整 finally 恢复。右侧候选必须位于实际列表 bounds 内，所有有文字/描述/点击/滚动语义的子节点右界与列表右界间至少有 8px；候选与中心 x 必须不同，缺乏明确区域时拒绝。此区域仅是 accessibility 证据支持的诊断候选，不能证明所有未导出手势层均不接收事件，也不用于默认完整 runner。

`swipe-log.jsonl` 记录实际列表 bounds、from/to、400ms 时长、起点命中子节点、滑动前聊天子树 SHA256 和命令成功状态；`result.txt` 记录滑动前后子树 hash 是否变化，排除外部时钟变化。诊断成功完成只标 `DIAGNOSTIC ONLY`，不标完整活动 UI PASS，主 Agent 应同时检查 PNG 和恢复证据。

聊天场景每次最多取得 3 个 fresh XML/PNG（已有 FirstDocument 计入第 1 次），每次仍通过原 navigation/title/composer/metrics 断言，失败帧保留且不做手势；已看到 sidebar/生成中立即拒绝。3 次仍失败则中止并恢复，不用列表子树替代完整场景。raw Capture 无嵌套重试。默认完整 runner 仍每方向最多 24 个检索帧、两方向，默认 Swipe 中心坐标及检索上限均未改变。

`Find` 仅遍历真实聊天列表内的匹配节点，跳过精确空 bounds `[0,0][0,0]`，并要求实际 Tap 中点处于列表及祖先 bounds 的可见交集，再通过原 Tap 门禁；先空后可见同名不会漏掉后者。无可见匹配继续原有界真实列表滚动，不点击空节点。`Swipe` 起点命中日志及 padding 推导只跳过精确空子节点；其他损坏 bounds 仍拒绝。`Get-QaChatSurface` 排除有已验证滚动祖先的空/完全屏外 nested scroll 候选；真实 root、composer、metrics 和 Tap 的 bounds 校验保持严格。未把旧 padding 路径改为默认路径。

实际 device 顺序：冻结 inputs/source 合约检查 -> host APK gate -> 已安装版本/code/debuggable/单 APK/hash gate -> App 凭据 family 门禁 -> 备份比较 -> 上传逐文件 hash -> 有界 fixture 注入 -> launch/UI -> finally 停止及逐项恢复。0.1.18 hash 不可代入；不从 build 目录推定 FINAL_READY。

fresh 安装还有明确的首次启动前置条件：`no_backup` 必须已经是正常目录，不能是符号链接。runner 在 App 凭据 family 门禁后、任何 force-stop/备份/上传/注入之前只查询目录状态；缺失或异常立即拒绝，日志说明 `Fresh install prerequisite`。由主 Agent 在已确认 App 无凭据的隔离模拟器上先启动并停止一次，使 App 初始化目录后再运行 QA。runner 不创建或删除 `no_backup` 根目录，保留现有精确删除 allowlist，无需声称恢复原本缺失的根。未满足条件时 runner 不改变 App 生命周期。

主 Agent 如已有无敏感内容的 App Models XML，可离线执行：

```powershell
pwsh -NoProfile -File ./tools/tests/resume-qa-20261011/VerifyAppUnconfiguredUi.ps1 -UiXmlPath '<main-agent saved Models XML absolute path>'
```

若 metrics/凭据/Web source 合约或 QA 文件变更，先审查预期并重跑 offline，再显式重新冻结；不要绕过 hash 失败。`VerifyOffline.ps1` 输出新的日志绝对路径，用该路径生成冻结：

```powershell
pwsh -NoProfile -File ./tools/tests/resume-qa-20261011/VerifyFreeze.ps1 -Freeze -OfflineResultPath '<new passing offline run/result.json absolute path>'
```

## 历史失败与检查结果

已核对 HANDOVER 文首、Pauli 0.1.19 plan 和 `../Temp_Android/qa/emulator-0.1.18`。旧 `No chat scroll surface` 的 `grouped-expanded-True-2.xml` 实际在 Sidebar；后来帧还有 unexpected generation 和 metrics drift。场景 guard、composer/实际 scroll bounds、有限 recapture、禁止屏幕坐标 fallback 已纳入离线回归。**不能把脚本缺 guard 说成漂移唯一根因，根因仍未明确。**旧 finally 恢复通过不等于活动 UI 通过。

首次 019 设备失败证据 `runs/activity-019-6a5c32567cd64f57a97c9c8f499f7529/result.txt` 表明远端 fixture 路径被宿主 `Copy-Item` 解析，随后 finally 完整恢复 verified；此结果不是 UI PASS。内部函数和全部直接调用、备份/恢复 scriptblock 引用现已改为 `QaCopy/QaCompare/QaRemove`，避开 PowerShell 默认 `Copy` alias；App 日志采样变量改为 `$appProcessId`，避开只读 `$PID`。

修正后离线检查增加两项：提取真实 helper、保留旧名称 alias 并通过 mocked private shell 调用，确认远端复制/比较/删除实际解析到 QA helper 且越界删除拒绝；模拟正常/缺失/未知 backup 根门禁并核对它在 lifecycle/备份操作前执行。此前 24/24 PASS 的冻结已被本轮修正替代，最新通过数量、日志路径及 SHA256 以重新生成的 `freeze-manifest.json` 为准。只验证 PowerShell/样本/内存 mock，不证明修正后的实际 Android 事务成功。

第二次正式失败 `runs/activity-019-b2e24879535048608476476508124a89` 为 `Scene drift: chat navigation missing`，finally 完整恢复 PASS。002–019 的聊天列表 bounds `[0,231][1080,1854]` 及 45 个后代的语义和坐标相同；019 丢失列表外导航/信息条/composer，而相邻 PNG 仍显示这些控件，支持“偶发不完整 accessibility dump”候选，尚不能证明实际场景没有漂移。旧中心 Swipe `(540,556)->(540,1529)` 落在文件标题 `[63,556][1017,685]`，描述子节点为“收起 qa-output-a.txt”，同 bounds 父节点 clickable=true。17 次无内容移动与不完整 dump 是两个需分开核对的问题；不据此直接归责生产手势实现。详见 `reviews/scroll/DIAGNOSE-SCROLL.md`。Einstein/Euler 的最终根因结论尚未取得，主线程应结合其 review 和新诊断证据判断。

历史主 Agent 实际诊断 `runs/activity-019-f97afe6f64654eaab269c7b3f3e2c8e2` 于 2026-10-12 00:18 在 emulator-5558 执行，主线程报告 exit0。`gate.json` 确认 0.1.19/code20，host/installed APK SHA256 均为 `8830c7789bcee3275d952197ebb3c619a8499535e18678e9ac697f4dfbb7e6d7`。依据该 run 的 `swipe-log.jsonl` 和 `result.txt`，相同列表 bounds `[0,231][1080,1854]`、相同 y=556→1529、相同时长400ms：中心 x540 命中文件标题 clickable 父节点，聊天子树 hash `cbd1c02e25b7e3e2694e86468f0a3da522ded72d273e98abb25accb70cdc2154` 前后不变；右侧 x1048 无起点子节点命中，hash 变为 `6a048a648f7fca2af8bcf2a3a147dfda0710849991f1a13bb8a037689e6ce1b3`。002/003 XML 中早轮正文 bounds 均为 `[95,346][1017,383]`，004 中移至 `[95,1742][912,1779]`，证明右侧滑动后实际内容位移，不只是外部时钟变化。本次四个场景均为 scene-0，未触发重捕，不能据此认证偶发子树缺失已解决。

该历史 run 的 `result.txt` 明确记录 `DIAGNOSTIC ONLY`、完整根成员/SHA256/原缺失状态恢复及 App 保持停止；没有完整活动 UI PASS。随后主线程安排 Einstein 对 FishButton 的 `consumeDrag` 参数和 GeneratedFiles 标题传入 false 做局部生产修复；本 worker 未修改生产。

新包 SHA256 `c7e3320b4c483c05be842caa62f8f1158b9502ad6870a8ab14a5e5c7cf8a41f3`、91,404,408 bytes 已在主 Agent 的 `runs/activity-019-67829cab2b7744e79e4c4f273eaa5867` gate 中绑定。其原中心 `(540,556)->(540,1529)` 后聊天 hash 从 `cbd1...` 变为 `ebc547...`；002/003 XML 中早轮正文、最终回复、文件 A 标题均下移 923px，标题仍为“收起”，精确三行预览仍在。XML/PNG 共同支持真实滚动且没有误 toggle。该诊断后来因 padding 扫描空子节点失败；不能写成完整诊断 PASS。

本轮主要失败证据为 `runs/activity-019-ce85fece8aba414faf508540b4b2b60d/result.txt` 和 `009-collapse-True-0-scene-0.xml`：原中心成功，已记录 `PASS terminal collapse and expanded bounded activity/image receipts`，随后 `Empty UI bounds`，finally 完整恢复 verified，App 停止。009 没有“已完成 · 用时 2 分 5 秒”标签，只有一个空 bounds 的非 scrollable Button，真实 root `[0,231][1080,1854]`、文件标题 `[63,1484][1017,1613]` 均有效。旧 `Find` 没找到完成标签后进入 `Swipe`，起点命中日志对每个子节点调用严格 `Get-QaBounds` 才触发异常；不是已经返回空完成标签，也不是该原始帧的 nested scroll 候选抛错。所有 bounds 调用已核对，本批同时覆盖 nested 候选和 Find 可见性，避免相同类别的后续失败。新增五项离线回归直接使用该 009 XML 及其明确派生样本，覆盖同名排序、完全屏外、实际中心轨迹、48 帧上限、空 nested 候选和损坏 root 拒绝。

按主线程最新收束要求，本批未增加文件标题点击回归，不改变默认首个中心 Swipe 或完整验收流程。标题点击收起/再展开及前后精确预览一致性保留为独立下一小批；实际收起态 accessibility 是“预览 qa-output-a.txt”，不能写成“展开”。新脚本仍须主 Agent 重跑完整默认 runner；历史恢复成功和部分活动 PASS 不代替完整矩阵。

小型真实主机 HTTPS probe 已完成 2/2 PASS，证据 `runs/host-https-e5a370e4ce7d476ab0e18758bb8fbb89/result.json`：RFC768 官方静态 HTML 为 HTTP200 / 6615 bytes，SHA256 `6e7add725657d1d5b785b5acf7de93cdb253f387b5c797c331f0b7fe1a86a7e8`；Bing 经记录的 302 跳转至 cn.bing.com 后 HTTP200 / 5430 bytes / 10 项。每次请求 deadline15s、最多3跳、32KiB/64KiB cap，无 cookie/proxy/model。

首次 `runs/host-https-39de4bf2708e427e81febdff44288c07/result.json` 为 1/2，Bing HTTP302 失败，保留原日志。后续通过仅表示 host .NET 公开 HTTPS/RSS shape，搜索相关性和生产 fallback 未验收。

生产 `OfficialWebClient` focused smoke 入口准备好，**本批没有编译/运行**。主 Agent 单独安排内存后执行：

```powershell
pwsh -NoProfile -File ./tools/tests/resume-qa-20261011/InvokeProductionWebSmoke.ps1 -CompileWhenScheduled -LiveHost
```

它使用缓存依赖、不走 Gradle、不下载，顺序编译/运行；编译 heap256MiB + metaspace128MiB/direct32MiB/code32MiB，runtime heap96MiB，总进程内存仍包含原生开销，需要主 Agent 调度。TEMP/TMP/java.io.tmpdir 和输出均落本目录。验证真实生产 browser 正文及 search，记录 source/dependency hash；host JVM 证据不冒充 APK UID/模型自动工具调用。

browser happy path 选真正小的官方 `https://www.rfc-editor.org/rfc/rfc768.html`。该页面是无 title 的 preformatted HTML fragment，应校验 `RFC 768` / `User Datagram Protocol` 正文，不能再断言不存在的 h1/title。生产 1MiB 上限未修改；RFC9110 超限是正确拒绝，保留超限负例。

## 验收矩阵与内存

| 场景 | 准备/执行范围 | 当前状态及通过条件 |
| --- | --- | --- |
| Final APK | 0.1.19/code20、preview package、安装包与最终 SHA256 一致 | 主 Agent 报告新包全构建BUILD SUCCESSFUL（3m11s）；新包hash及安装一致性门禁/设备验收待执行，不沿用00:18诊断旧包hash |
| 当前 metrics | 同一 accessibility 节点的完整字段、窄屏 composer 分离 | offline PASS；device DEFERRED；PNG 人工核对 2轮13步骤、777.8k tok、未知进度、图标与换行 |
| 活动/最终正文 | 完成收起、分组摘要、展开、早轮正文只在 process、重新收起、最终正文关联 | offline selectors PASS；device DEFERRED；新事件时间顺序和统一间距还需人工核对 |
| 长文本内存 | 40条/20轮，有界长回复；两端滚动3循环 | device DEFERRED；首末 sentinel 到达、会话无漂移；保存首轮/重复后 meminfo |
| 图片回执内存 | 合成 display-only 回执，process 展开/收起3循环 | device DEFERRED；仅 metadata，无真实图片或 bitmap decode |
| 真实位图内存 | 仅 fresh sandbox 中主 Agent 选用合成小图片，最多3张，每张<=1024x1024、编码<=512KiB、总解码预算12MiB；逐张预览/返回3轮 | DEFERRED，现 fixture 未包含真实 bitmap；不用用户图片、不付费生成，不并行加载/无限增量 |
| 中断状态 | 保存的 RUNNING 在恢复后显示未完成、失败、取消等待；task queue空 | device DEFERRED；无 resume/send/retry/stop generation |
| 内存结果解释 | baseline、展开、3循环后、长文本首轮/重复后 meminfo；当前PID日志样本 | device DEFERRED；记录实际 PSS/Java/native heap，不以单次无OOM证明无泄漏；异常增长需同生命周期稳定后复测 |
| IME/流式/回底 | 输入法开关、长文本锚点、惯性、真实流式增量 | DEFERRED，静态 fixture 不证明真实持续流式 |
| Copy/reference | 精确全合成正文/生成文件内容与关联 | DEFERRED，自动入口不修改剪贴板、不输入文本 |
| Sidebar/archive/footer | 切换仅合成会话；归档/删除/长名溢出/footer另验 | 基本选择入口已准备；其余 DEFERRED，旧 metrics 横滑不再适用 |
| 大字体/320dp | 320dp XML+PNG；大字体/IME分别验 | device DEFERRED；脚本仅改wm size并恢复，不改density/font |
| 恢复与重启 | 原完整成员/字节、原本不存在的路径，staged reopen持久化 | mock故障注入PASS；最新两次Swipe诊断的实际restore成员/SHA256/原缺失状态verified，App停止；reopen持久化仍DEFERRED，失败保留backup |
| 网络 | native host 2/2；真实生产 focused host；APK UID | .NET host PASS；production JVM/APK UID/model端到端 DEFERRED |

fixture 6文件共 77,003 bytes，总预算512KiB、每消息8192字符、每文本output64KiB；无用户附件引用、SAF URI、真实任务、Key。临时 XML 每帧上限1MiB，日志仅当前PID有限样本；截图不在主机批量解码。旧空 aborted offline run 未当作成功记录。

完整恢复 allowlist 为 `files/Dafeiyu-Works`、`no_backup/background-tasks`、`no_backup/official-tool-outputs`、`no_backup/conversation-attachments`、`shared_prefs`，含 AtomicFile siblings 与启动新增目录。用户 payload 不拉回主机，device 内比对完整目录/文件成员及 SHA256，拒绝 symlink/特殊成员。所有备份验证及上传 hash 通过后才开始替换。finally 逐项恢复，某项失败仍尝试其他项，任何失败保留 device backup；恢复后 App 保持停止，以免原队列触发付费 provider。最新诊断的实际恢复已由主 Agent 执行并记录 verified，此结果不证明完整活动矩阵或重启持久化通过。

没有把已准备脚本、离线 PASS、主机网络 PASS、两次 Swipe 诊断完成或新包构建成功扩写为“0.1.19 已完成设备验收”。本轮按授权在文档更新后重跑 offline/refreeze，并校验 source contracts；实际成功状态以生成的结果和冻结清单为准。此后 QA 输入冻结，剩余设备执行由主 Agent 在新 APK、内存和 App 配置门禁满足后安排。
