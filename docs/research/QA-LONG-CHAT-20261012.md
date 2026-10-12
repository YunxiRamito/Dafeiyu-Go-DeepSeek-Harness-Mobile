# 长聊天 QA 只读结论

日期：2026-10-12，Asia/Shanghai。仅分析已保存日志、XML、fixture 与脚本；本轮只新增本文档，未修改 QA/生产、未运行 ADB/JVM/网络、未改变 cap 或安排重跑。

**结论：本次为 partial。长列表确实向历史移动，随后反向返回；有界搜索的实际覆盖范围没有到达首轮。方向错误、全程拦截或列表完全不动均不符合已观察轮次。有限跨度不足有直接证据支持，但不能据此认定“纯跨度问题”是唯一根因，或排除个别手势被拦截。**

## 证据范围

证据根目录：`tools/tests/resume-qa-20261011/runs/activity-019-30b3d08caa2b409c90848385ede716c9/`。
`gate.json` 记录 emulator-5558、0.1.19/code20、91,404,408 bytes，host/installed APK SHA256 均为 `c7e3320b4c483c05be842caa62f8f1158b9502ad6870a8ab14a5e5c7cf8a41f3`。

`result.txt` 已记录终态收起/展开活动及图片回执、手动折叠后的最终正文关联、3 次活动展开/折叠循环通过；随后 `FAIL: Label not found after bounded chat scrolling: QA_LONG_FIRST_019`。finally 记录完整成员/SHA256/原缺失状态恢复，App 停止。长聊天两端、重复循环、后续窄屏/中断场景及最终当前 PID 崩溃/OOM 日志检查未到达，不能认作 PASS。

## 指纹与轮次

只读重算冻结脚本的 `QaChatSurfaceFingerprint`：48 个 `long-first` XML 均与对应 `swipe-log.jsonl` 的滑动前指纹一致。全 run 有 52 次 Swipe，其中长聊天 48 次；这 48 次均中心轨迹、400ms、`command_succeeded=true`，列表 bounds 固定为 `[0,231][1080,1964]`。全部长聊天帧通过原聊天场景断言，且各只有一个 scrollable 节点；日志没有 scene-recapture rejection。

| 阶段 | 轨迹 | 相邻指纹变化 / 相同 | XML 可识别的回复轮次，编号从 0 起 |
| --- | --- | --- | --- |
| 24 次 earlier=True | `(540,578)` → `(540,1617)` | 阶段内 17 / 6，18 个不同指纹 | 19 → 18 → 17 → 16 → 15 → 14 → 13 → 12 |
| 24 次 earlier=False | `(540,1617)` → `(540,578)` | 阶段内 16 / 7，17 个不同指纹 | 12 → 13 → 14 → 15 → 16 → 17 → 18 → 19 |

两阶段交界也相同；合计 47 次相邻比较为 **33 次变化、14 次相同，27 个不同指纹**，因此不能写“指纹持续变化”，也不能写“连续卡住 48 次”。

关键帧：`025-long-first-True-0-scene-0.xml` 为回复19；028 出现回复18及 user19；038 出现回复15；047 出现回复12及 user13；048 为回复12；049（第24次向历史手势后的首帧）仍为回复12；072 返回回复19。48 个帧均没有 `QA_LONG_FIRST_019`，也没有回复0–11。

026/027 的指纹同为 `6575b605bc02b8739a69eba0d6aef785635cd604136066982cf30ec99de90f00`；048/049 同为 `bd27f6cb7d454c2ea127933608182c53746925872b84e6801d96b6f9f34bd67e`。这些帧的列表仅含两个后代、一个正文 text 节点，正文 bounds 都是 `[63,231][1017,1964]`。XML 导出的是整条回复文本及裁剪后的节点 bounds，不是仅屏上可读行；段内滚动可能保留相同文本/bounds。因此相同指纹不能单独证明该次手势无位移，XML 中出现回复头尾 sentinel 也不证明该行实际露出。

## fixture 与跨度

冻结 fixture `fixtures/workspace-index.json` 中 `qa-long-019` 为 40 条消息/20 轮，共 **58,901 字符**；20 条 assistant 回复共 57,532 字符，每条 2,870–2,878 字符，均包含 48 次重复句。首 sentinel 位于最早 user0，而本次最深只识别到回复12；更早回复0–11自身即有 34,516 字符，另有用户消息及行间距尚未覆盖。

每次命令轨迹为 1,039px，24 次向历史的命令路径合计24,936px；这是手指轨迹，不能等同正文净位移，未据此反推精确全文高度或需要多少次手势。可观察事实是24次只跨过约7个回复编号，随后立即反向。**24+24 不等于48次持续向历史搜索**，反向阶段主要重走已覆盖的12–19区间。

只读源码 `ChatScreen.kt:189` 为 `LazyColumn(reverseLayout = true)`，`:207` 为 `messages.asReversed()`。本次下降轨迹对应编号变小、反向对应编号变大，实际轨迹与向历史/向最新语义一致。

## 解释边界

48 次长聊天 start_hits 均无 `clickable=true` 或 `long_clickable=true` 节点，只涉及 View/TextView；结合轮次双向移动，不支持重现旧文件标题导致全程无法滚动的现象。但 accessibility 未导出全部手势处理，命令成功也不等于每次均有净位移，不能完全排除局部选择/手势消费。现有证据足以报告“有界跨度未覆盖首轮，长聊天验收未通过”，不足以认证唯一生产根因、首轮永远不可达、精确滚动距离或内存稳定。

不修改 cap、不改 fixture、不重跑。冻结 QA 保持原 SHA256 `DD98F11F12543F15D4B65FAE5061F5A3604456DF208E9746332C2DA4B8DF15EF`。

证据 SHA256：`result.txt` = `f7022552e8d3d38f98a0943baea101ffd2ff067a23424742a97caf8b873c9211`；`swipe-log.jsonl` = `37eb54440b78ca383dd4c80d45bde1ed2f3ee787a9504b998d14bfeddffba568`；`fixtures/workspace-index.json` = `5fbec8f0ddfebbaa3c298bc3ba6dfaed7ff14797dec435994b61b449a723e229`。本文的 fixture 路径均相对 `tools/tests/resume-qa-20261011/`，帧名相对上述 run 根目录。
