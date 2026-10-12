# Android 0.1.18 · 首包交付与专项验证记录

日期：2026-10-11，Asia/Shanghai。本记录对应已交付首包 **0.1.18 / versionCode19**；包名 `com.dafeiyu.mobile.preview`，包元数据 minSdk 30、targetSdk 35 已由主线程核对，compileSdk 35 为源码配置。当前 `app/build.gradle.kts` 已为 **0.1.19 / versionCode20**，四个 UI worker 与主线程仍在写第二阶段；本记录不将当前源码全部改动归入已交付 0.1.18，也不宣告 0.1.19 已构建/验收，待主线程 final2 证据后补新版本记录。

本文与 README、HANDOVER、清单和连接插件通知分开记录首包实现、离线协议、构建/安装及尚待补齐的设备 QA。0.1.17 及更早的构建、APK hash、设备结果与操作分工为历史（historical instructions），不代替本轮证据或授权。本次文档更新只读源码、既有日志与产物，并以 apply_patch 修改五份 Markdown；没有运行测试、Gradle、ADB、网络、runtime 或付费模型请求，不修改 `frontend-build.json` 或 `icon-provenance.md`。

## 本轮实现

| 范围 | 实际接线/实现 | 限制 |
| --- | --- | --- |
| 子 Agent 与父模型续接 | `OfficialToolSession` 同批先提交所有调用，再通过 dispatcher/engine 等待请求运行的子 Agent；真实结果放入 tool history 后继续父模型，记录子 Agent 正文与终态事件 | 默认等待上限 15 分钟；超时仍报告运行/等待超时，失败、停止和中断不冒充成功。后台任务保持独立。取消等待不等于取消子任务；应用级 engine 不是系统后台常驻服务 |
| 回复操作 | `ChatScreen` 对完成回复接复制、引用回当前 draft、次级 SAF 文本导出；主 UI 多轮 `responseText` 取当前模型轮次，较早正文留在 process，不重复拼接 | 未报告本轮设备剪贴板、引用、导出或真实生成结果，不写验收通过 |
| 生成文件 | `GeneratedFiles` 在 IO dispatcher 通过 helper 调用受管 `OfficialToolOutputStore.copyTo`，核对 metadata、大小和严格 UTF-8；默认展开全文预览，可滚动、选择、复制和重试 | 只按受管 output ID 读取；不按显示文件名/任意 URI 读取。读取有界，错误不显示成功 |
| 文件引用与导出 | 父侧已传 `onReferenceText(filename, text)`，引用文件名与全文回 composer draft；导出保留为次级图标 | 复用现有 repository 的 `responseFiles`、`exportGeneratedFile`、`updateNotice`，无需新增读取方法；文件 chooser/输出写入需设备验收 |
| 运行信息条 | composer 上方接 `responseRunMetrics`；实际状态、模型轮数、已执行工具步骤、累计用量、缓存命中比例、耗时及可计算速度 | 未知字段显示未知；只计算实际 RUNNING 工具步骤。tok/s 为报告输出 Tokens 除整个会话耗时，包含模型/工具等待，不宣称纯解码速度 |
| metrics 与运行状态恢复 | 活跃计时使用单调时钟，终态冻结；保存 duration/轮数/步骤和持久 `runStatus`，工具阶段取消及轮间重启保留真实状态，不把未完成运行误判为 completed | 缺少持久证据的历史计数/耗时保持未知，不填零或推测 |
| 紧凑后台活动 | `AssistantActivitySummary` 按真实事件和模型轮次归组，关联 parent call 的子 Agent 去重；普通长任务完成后整体折叠，完整 process 保留 reasoning 与旧轮正文，允许手动再开；leading glyph 最多 3 个 child icon，前导区域 44dp | compactSkill 是技能分类展示；不从公开思考文字推断工具活动，不表示技能、图片生成或命令执行器已接入；搜索/浏览可调用另有下列源码证据 |
| 思考与渐显 | 公开思考默认折叠，标题取最近公开原句并单行省略，手动展开后保持；正文/思考新增段渐显不逐 token 重播全文，保留选择与完整布局 | 最近原句不是另一次模型生成的摘要；历史重进不重新播放。设备长文本/持续流式布局尚待证据 |
| 玻璃互动 | 复用现有 FishButton/GlassSurface/按钮变形；新增操作有 48dp 目标及 tooltip/语义；折叠使用共享 GlassMotion 380ms，reduce motion 为 0ms | 沿用现有 Apple Liquid Glass 设计的 Android 材料适配，不声称 Apple 原生组件。离线编译不证明设备触感或视觉验收 |
| 搜索工具 | DemoRepository 注入 `OfficialWebClient`；contract/schema、dispatcher 与 Session 暴露真实 callable 的 `web_search`，优先 Bing RSS、失败后 DuckDuckGo HTML 回退；保留查询、服务来源、实际来源地址、标题/URL/摘要、时间和回退失败 | Android shell 生产源码已返回 3 条 Bing 结果；搜索排名/相关性、回退服务的当前可达性及付费模型自动选工具仍需各自证据 |
| 网页读取 | `browser` 已 callable，读取公开 HTTPS HTML/XML/纯文本；提取正文、标题、最终 URL 与链接，记录截断和时间，明确网络/解析/取消失败 | 不执行 JS、不登录、不带 cookie、不访问私网。example.com 已返回真实正文，但 QA h1 断言失败；Kotlin docs 复测尚未成功，不把 callable 写成完整设备验收 |
| 搜索/浏览返回链 | OfficialToolSession 将真实结果按 call ID 写事件与 tool history 后继续模型；OfficialChatHistory 保留完整成功历史，UI 来源取实际工具 envelope；URL/时间/截断与状态按返回字段解析 | focused 27 项及下一用户上下文补充 4 项均离线注入；不以模型正文/公开思考生成来源，不证明最终 APK 应用 UID 或真实模型调用 |

跨轮重复 provider call ID 已有 session 拒绝入口，达到调用/轮次上限发出未完成终态而不继续执行。search/browser 的 history/reducer 与后续序列化请求已有下述 focused 离线证据；真实付费端到端与未报告的其他场景仍待验证，本轮文档不以源码检查代替这些验收。

## 网络与搜索回执

`web_search` 与 `browser` 已实际接线，不再沿用此前“当前轻引擎没有通用 search tool”的状态。`OfficialToolContract.names/schemas` 包含两项，dispatcher 只在 webProvider 注入时公布 schema，DemoRepository 使用真实 OfficialWebClient，OfficialToolSession 把工具执行结果返回下一模型请求。搜索参数为 query/limit；浏览参数为公开 HTTPS url。

搜索回执的实际字段包括 `query`、`source`、`source_url`、`retrieved_at`、`results[{title,url,snippet}]`、`fallback_errors`；浏览包括 `url`、`final_url`、`title`、`text`、`links`、`text_truncated`、`links_truncated`、`retrieved_at`。外层 `ok/data` 或 `ok:false/error/details` 与 call ID、状态事件关联；网页导航 links 不冒充检索来源，未知时间不填造。读取最多 1 MiB、正文最多 12,000 字符、链接最多 20 项，超限/截断如实记录；JS 和网页内指令不执行。

Android [生产源码日志](../../Temp_Android/qa/emulator-0.1.18/production-web-bdd55404713745a4a0970a16183f09bd/android-results.log) 显示 `Kotlin official documentation` 的 Bing RSS 检索 PASS，返回 3 条标题、实际 URL、摘要和检索时间；source_url 为实际重定向后的 Bing 地址。该 runner 在 **Android shell app_process** 下运行单独编译的生产源码，未调用模型、未访问用户数据；不是最终 APK 应用 UID 的工具调用或完整 UI QA。其 [device evidence](../../Temp_Android/qa/emulator-0.1.18/production-web-bdd55404713745a4a0970a16183f09bd/device-evidence.json) 关联 emulator-5554、已安装 0.1.18 与本记录 APK hash，runner_exit_code 为 2。

同份日志的 example.com 浏览实际取得最终 URL、`Example Domain` 标题与真实正文；正文抽取选择 main，测试却错误要求 h1 出现在正文，因此记录 **FAIL**，summary 为 1/2。主线程改用 Kotlin docs 复测，已有 [docs 复测日志](../../Temp_Android/qa/emulator-0.1.18/production-web-f685c307003a4d839388f97e699d8a6d/android-results.log) 仍为 FAIL：最终 URL 与 `Kotlin Docs | Kotlin Documentation` 标题存在，`text` 为空，summary 仍 1/2。等待主线程新证据，不能预写浏览通过或把断言修复计划当结果。

provider/weather 请求、搜索/页面执行、模型自动选工具分别验收；模型自称联网、正文链接或公开思考提及搜索都不能代替回执，也不推定 DeepSeek 服务内部搜索。第一阶段工具实现及 0.1.18 首包 build + install 已完成并给用户查看，剩余浏览/活动专项 QA 待补。滚动、工作区、左滑、按钮玻璃及详细回执页属于正在写的 0.1.19 第二阶段，等生产 worker 完成与主线程 final2 证据后补录，不归入 0.1.18 已完成项。

## 已报告离线证据

以下只读核对已有日志/源文件，没有在文档任务中重跑。不同入口和阶段不相加为“全仓测试通过”；UI 编译、纯 helper 检查和注入 runner 各自有明确边界。

| 检查 | 已报告/核对的结果 | 证据与边界 |
| --- | --- | --- |
| 生成文件 UI 与安全读取 | 真实 GeneratedFiles/helper/store/contract + GlassMotion 经 Kotlin/Compose `-Werror` 编译；16 PASS、0 skipped | `tools/test-generated-file-preview.ps1`；[本次日志](../../Temp_Android/qa/generated-file-preview/run-a99205c47d6b496fb2424b5cf7f7bb6b/fixture-results.log)。仅外部 repository/玻璃组件签名为编译夹具 |
| 生成文件读取边界 | 中文/多行/emoji、空文件、字符上限、metadata 关联漂移、非法 ID、缺文件、损坏 UTF-8、大小不符、字节/metadata/字符超限、非法路径/控制字符和符号链接逃逸均拒绝或正确返回 | 使用真实受管 store 与 org.json；不是 Android UI、剪贴板或 SAF 执行检查 |
| assistant-stream 协议与恢复 | 30/30 PASS | `tools/test-assistant-stream.ps1`；[日志](../../Temp_Android/qa/assistant-stream/run-aa96f80116d14dd48610c8683abb5fc8/fixture-results.log)。覆盖事件重排/重复/缺口、tool 状态、取消与终态、OpenAI/Anthropic 边界、JSON snapshot、工具等待和轮间重启；纯离线协议/恢复检查，不启动 Android UI 或网络 |
| 运行 metrics/信息条 | 18/18 PASS | `tools/test-chat-run-metrics.ps1`；[日志](../../Temp_Android/qa/chat-run-metrics/run-86e9040c4338467ebfd7ad5c2ba2ab71/fixture-results.log)。覆盖未知值、三轮累计、用量快照替换、去重、单调计时、终态冻结、取消/不完整、持久 runStatus、缓存/速度、历史和会话隔离；真实 Compose 信息条编译，不启动 Android UI、网络、密钥或整包构建 |
| 后台任务 engine | 40 项基础 + 9 项 focused await/recursion PASS；AndroidStore 和任务页面 compile-only PASS；script exitCode 0 | `tools/test-background-tasks.ps1`；[结果](../../Temp_Android/qa/background-await-terminal-20261011-190059/results.json)、[日志](../../Temp_Android/qa/background-await-terminal-20261011-190059/test-background-tasks.log)。使用注入计算 runner，无模型/网络；不单独证明父模型完整循环 |
| 等待语义 | 成功/失败/停止/中断准确返回，草稿不执行，取消 waiter 保留任务，未知/删除任务不挂起，engine close 唤醒等待，外部等待不占 worker，递归调度/等待被拒绝 | 属于上行 9 项，不能据此声称 Android 杀进程后仍持续运行 |
| 渐显/公开思考边界 | 7 项原句提取；完整布局/选择文本、append-only span、Unicode/组合字符、替换/终态/空文本及 380ms/reduced 0ms 有 PASS 日志 | [日志](../../Temp_Android/qa/chat-stream-ui/run-6f24d6143cda4a6c99a83bd60a1eb327/fixture-results.log)；对应检查源位于 `tools/tests/chat-stream-ui`，属于纯逻辑边界而非设备流式测试 |
| 紧凑活动/完整 process | Peirce focused summary/Compose/reveal PASS：按消息归组、parent-call 去重、成功动作标题、最多 3 个 child glyph、safe Skill 名称、真实通知/耗时、持久 runStatus 优先级、普通长任务终态折叠、reasoning 与旧轮正文过程保留、disclosure 生命周期 | [日志](../../Temp_Android/qa/chat-stream-ui/run-6f24d6143cda4a6c99a83bd60a1eb327/activity-summary-results.log)；检查 `use_skill` 分类且思考文字不生成虚假活动，Compose/reveal 范围不等于设备视觉 QA |
| 搜索/浏览 focused 生产源码协议 | 27 PASS / 0 FAIL | [复测日志](../../Temp_Android/qa/official-web-focused/run-0fa326b39af041c9ac9cf3ffa2a286bb/fixture-results.log)、[序列化请求](../../Temp_Android/qa/official-web-focused/run-0fa326b39af041c9ac9cf3ffa2a286bb/fixtures/session-web-request-history.json)。真实 schema/parser/dispatcher/Session/reducer 执行，覆盖 Bing/DDG、真实字段、URL/DNS/重定向/大小/取消与 search → browser → 后续模型请求精确调用 ID；模型和网页响应离线注入 |
| 下一用户 history / serialized context 补充 | 4 PASS / 0 FAIL | [日志](../../Temp_Android/qa/pauli-handoff-exclusive-20261011/run-e45efb0faa8a4ee7a78ddcfbfcfa2c41/fixture-results.log)、[证据](../../Temp_Android/qa/pauli-handoff-exclusive-20261011/run-e45efb0faa8a4ee7a78ddcfbfcfa2c41/evidence.json)。真实 OfficialChatHistory 与下一用户序列化请求保留精确 ID/参数/结果/公开思考/最终回答，UI 来源保留 redirect final URL，无重复最终回答；排除 incomplete/nonfinal/cancelled/missing-result。无 ADB、网络或真实模型请求 |

前序 0.1.17 的附件、发送持久化、天气、工具/图片及菜单几何记录继续保留，但不写成本轮最终源码重测。27 项与追加 4 项是不同入口，不汇总为全仓通过。0.1.18 final3 已完成整包编译；父子模型真实付费循环、所有设备操作与当前 0.1.19 完整源码验证仍需各自证据。

## 首包构建与设备证据

| 项目 | 本轮状态 | 证据与范围 |
| --- | --- | --- |
| assembleDebug / lintDebug | 已成功 | [final3 日志](../../Temp_Android/build-0.1.18-final3.log)：BUILD SUCCESSFUL in 4m 15s，46 tasks，14 executed / 32 up-to-date；不用早期 final/中途构建替代 |
| Lint XML | 0 Error / 137 Warning / 5 Information | 已只读核对 `../Temp_Android/build/app/reports/lint-results-debug.xml`，没有 Error，非零警告；主线程后续构建可能更新同一报告，因此本条锁定 0.1.18 final3 的报告统计 |
| APK | 已产出并给用户查看 | `../Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.18.apk`，100,160,952 bytes；本文下方 SHA-256 已只读重新计算一致 |
| 签名与包元数据 | 主线程已核对 | apksigner verify 成功，v2 签名；证书 SHA-256 `52fc036fd6fe45d400f354235068aab29cb27ca6f68e42c53368a5b00c887b23`；code19 / 0.1.18 / min30 / target35。本次文档 worker 未重新调用签名/Android 工具 |
| 安装 | 已保留数据安装 | emulator-5554 / API37 / x86_64，主线程报告 install 保留数据成功；已有 device evidence 和 activity result 关联到相同 installed APK hash，不把安装通过扩大为全部启动/交互通过 |
| 搜索/浏览网络 | 搜索 PASS；浏览 QA FAIL，待补 | 上述 Android shell 生产源码日志：Bing 3 条结果；example 实际正文但 h1 断言失败，Kotlin docs 复测 text 为空仍 FAIL。不是最终 APK 应用 UID/模型自动触发验收 |
| 实际回执/协议链 | 已实现及 focused 离线通过 | 27 PASS / 0 FAIL 与追加下一用户 history 4 PASS / 0 FAIL，具体范围见离线证据表；设备来源展示、点击、返回/滚动仍待完整 QA |
| 活动专项设备 QA | 尚未完整通过，脚本修复中 | [result.txt](../../Temp_Android/qa/emulator-0.1.18/activity-018-11b0f8b60fc84e3384dd5b23e1b4e6af/result.txt)：Installed final APK SHA256 verified 后 `FAIL: No chat scroll surface`；主线程报告运行到设置页出现导航问题，不从已有截图推断全项通过 |
| 原数据恢复 | finally 恢复并 cmp verified | 同 result：original index/recovery/task AtomicFile families 与 output tree 已恢复，原不存在路径保持不存在，byte compare 通过；脚本记录恢复后刻意停应用，后续是否重开以主线程为准。恢复通过不代表活动 QA 通过 |
| 未报告设备范围 | 待实际专项结果 | 活动运行/终态/手动折叠、标题、文本预览/复制/引用、运行信息条、短屏/IME/返回、logcat 采样、显示尺寸恢复及清理不补写全通过 |
| 完整模型端到端 | 待完整验收证据 | provider 请求不等于完整父子循环、图片兼容性、自动选搜索/浏览与引用结果；本次文档维护无付费请求 |

首包 SHA-256：`98D38E61F5ADF8A9785EE5CAF3CE4F29C65A8E46E1DECF01FC94173AA45D9B73`。安装/网络 device evidence 与活动日志使用同一摘要；0.1.17 hash 不参与本轮产物核验。

后续设备验证由主线程按 [HANDOVER 调试约定](../HANDOVER.md#当前调试约定2026-10-11) 执行，本次文档 worker 不调用 ADB。QA 目录、截图、脚本或 fixture 存在不等于成功。浏览/活动待主线程新证据；0.1.19 待生产 worker 完成和 final2 证据，不从当前源码版本、准备目录或正在运行构建推测交付。

## DSH 只读调查

本地 `../Temp_Android/runtime/jniLibs` 仅有 arm64-v8a。Node 为 24.18.0，ELF64 AArch64；可执行库 SHA-256 与 [runtime-lock.json](../../Temp_Android/runtime/assets/node-runtime/runtime-lock.json) 一致，15 个锁定库均存在且摘要一致。这证明准备资产完整性，不证明 ELF 能从 Android 宿主成功启动。

未找到真实 DSH CLI/Web bundle、rootfs/proot 或 x86_64 runtime；同级 `node_modules/@deepseek-ai/dsh/lib/bin.js` 命中项经检查是 HTTP 测试替身。DeveloperCenter `DshBundleService` 当前限定 win-x64/node22 包，不能直接复用为 Android bundle。准备库采用 APK JNI 名称，Node 中发现 Termux 固定路径和版本化依赖名，实际 SONAME 别名/搜索路径、许可证与 native addons 仍需适配验证。

可落地路径：先验证现有 arm64 Node 启动与依赖，准备锁定版本的真实 CLI/Web bundle，显式设置 `DSH_HOME`，以 `web --no-open --host 127.0.0.1 --port <动态端口>` 启动；捕获本代鉴权 token 换 cookie，再调用 `/api/<endpoint>` RPC。桌面客户端 envelope 为 `client-request`/`rpcId`/`method`/`payload.args`，响应须核对 `server-response`、同一 rpcId 和 result；这只是可复用契约，尚未由 Android runtime 运行验证。

完整 native 依赖无法适配时，可按 [DSHA 研究](research/DSHA-CODE-REVIEW.md) 另行评估 arm64 rootfs/proot；当前未发现其准备资产。x86_64 Emulator 的壳应用可用性不证明 arm64 Node 或 DSH runtime 可运行。宿主生命周期、实际加载、Provider 注入和 ABI 资产仍是阻塞。

连接包 0.2.0 的 prepared/上一 slot/失败回退属于前序包管理边界。`current()` 返回当前已准备包，不代表宿主 loaded、实际 paired 或已完成双设备传输；Provider 需先注入再加载。协议与桌面历史证据见 [连接插件通知](CONNECTION-PLUGIN-NOTICE-2026-10-11.md)。

## 仍未完成

- script/sandbox、命令执行与 DSH runtime 尚未接入；紧凑技能/命令分类不是执行能力。`web_search` 和 `browser` 已 callable，但浏览成功复测、最终 APK 应用 UID 与活动/回执完整设备 QA 仍待补。
- 0.1.19 第二阶段滚动/工作区/左滑/按钮玻璃/详细回执 UI 实现和验证分开记录，未收到生产 worker 完成与主线程 final2 证据，不宣告阶段完成。
- 真实付费模型触发工具、父子模型完整循环、图片模型兼容性及未报告的持续流式/剪贴板/引用/SAF 设备场景。
- Android 连接宿主实际加载、Aries 完整审批/传输/同步与 Android APK 更新流程的端到端接线/验收。

本轮源码功能、离线结果和待补交付证据分别记录；不宣告原始清单全部完成，历史负责人指令不作为本轮授权或操作结论。
