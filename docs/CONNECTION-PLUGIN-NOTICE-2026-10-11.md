# 当前交付补记（2026-10-12）

Android **0.1.19 / code20 预览包已复制到用户桌面并保留数据安装至 PKB110 手机**。桌面文件和手机已安装 base.apk 的 SHA-256 均为 `c7e3320b4c483c05be842caa62f8f1158b9502ad6870a8ab14a5e5c7cf8a41f3`；包体 91,404,408 bytes，手机 lastUpdateTime 为 2026-10-12 00:49:48。整包构建/Lint 成功，设备 UI 验收为 partial：活动折叠、最终正文关联和三次展开收起通过，长对话有界遍历未覆盖首轮，不能标整体验收通过。实际范围见 [绑定该 APK 的验收记录](acceptance-0.1.19-20261012.json) 与 [构建元数据](frontend-build.json)。

本次交付不改变连接插件与 DSH runtime 的接入状态；真实宿主加载、DSH 启动及 App 专属网页适配仍待完成。重大功能先研究 GitHub 的用户要求已记录于 [AGENTS.md](../AGENTS.md)。下方 2026-10-11 的版本和协调说明保留为历史记录，当前版本以本节为准。

# 恢复协调说明（2026-10-11，历史）

用户已恢复 Android 迭代，并授权按需联系 PC、连接插件、开发者中心和 Aries 研发聊天。手机端先完成 0.1.19 UI 与交互验收；共用连接插件和 App 独占网页适配仍分别记录，prepared 不等于 loaded。当前跨聊天接口读取失败，尚未收到新的协议/资产状态，详见 [迭代计划](ITERATION-PLAN-2026-10-11.md)。下方停工和 runtime 状态保留为历史与待接续基线。

# 停工说明（2026-10-11，历史）

用户要求停止全部开发/调试，六个 worker 已关闭，现有改动保留；随后仅完成用户给定手机的无线 ADB 配对/连接，未安装或启动应用。完整需求、未完成项与证据见 [HANDOVER.md](../HANDOVER.md) 文首和 [清单.md](../清单.md)。

已交付仍为0.1.18/code19，源码0.1.19/code20未整包构建。用户真实确认web_search成功；browser正文专项、活动设备QA尚未闭环。最新RFC9110 HTML超1MiB被拒，不能写浏览成功。App专属DSH网页移动适配插件与共用连接插件是不同职责，DSH/Android宿主实际运行加载仍未完成；prepared不等于loaded。下文“开发中”“继续”“等待final2”等为停工前快照，不作为恢复工作的授权。

# Android 0.1.18 首包 / 0.1.19 开发与连接 runtime 状态（停工前快照）

日期：2026-10-11，Asia/Shanghai。已交付 Android 首包 **0.1.18 / code19**，当前开发源码 **0.1.19 / code20**。0.1.18 final3 assembleDebug + lintDebug BUILD SUCCESSFUL 4m15s，46 tasks（14 executed / 32 up-to-date），Lint XML 0 Error / 137 Warning / 5 Information；首包已保留数据安装至 emulator-5554 / API37 / x86_64 并提供用户查看。完整产物、签名与范围见 [frontend-0.1.18.md](frontend-0.1.18.md)。0.1.19 第二阶段实现中，等主线程 final2 证据；浏览和活动设备 QA 尚未完整通过。

首包 `../Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.18.apk`，100,160,952 bytes，SHA-256 `98D38E61F5ADF8A9785EE5CAF3CE4F29C65A8E46E1DECF01FC94173AA45D9B73`，包元数据 code19 / 0.1.18 / min30 / target35。主线程报告 apksigner v2 verify 成功，证书 SHA-256 `52fc036fd6fe45d400f354235068aab29cb27ca6f68e42c53368a5b00c887b23`；构建日志 `../Temp_Android/build-0.1.18-final3.log`。

官方轻引擎已接父模型收集子 Agent 结果、回复/生成文本复制与引用、运行信息条和紧凑活动。`responseText` 取当前模型轮次，旧正文保留在 process；持久 runStatus、真实终态、长任务折叠与最多 3 个 child glyph 已接入。**`web_search` 与 `browser` 已真实 callable**，schema/dispatcher/Session/实际来源与返回 tool history 链已接通；compactSkill 仍只表示技能活动分类。轻引擎工具、文本输出和搜索/浏览可调用均不表示连接插件加载或 script/sandbox、DSH runtime 接入。

Android shell `app_process` 的生产源码检索 `Kotlin official documentation` 已返回 Bing 3 条真实结果；这是独立工具网络证据，不能写成最终 APK 应用 UID 或付费模型自动选工具通过。example.com 返回真实正文但 QA 错误 h1 断言失败，改用 Kotlin docs 复测的现有日志仍 FAIL（text 为空），待主线程成功证据。活动设备脚本也未完整通过，报 `No chat scroll surface`，finally 原数据 byte compare/cmp verified。focused 27 PASS / 0 FAIL 与下一用户 OfficialChatHistory 追加 4 PASS / 0 FAIL 使用真实源码、离线模型/网络注入；不推定 DeepSeek/provider 服务内部搜索。

第一阶段工具已实现、0.1.18 首包已 build + install 并给用户查看；用户查看期间四个 UI worker 与主线程继续全部所列滚动/工作区/按钮玻璃及详细回执 UI。具体实现/验证分项见 [分阶段清单](../清单.md#最新分阶段清单2026-10-11)，0.1.19 未收到 final2 证据不写完成，连接宿主/runtime 状态独立记录。本次修改五份 Markdown，只读源码/日志/产物，没有 Gradle、ADB、网络或付费请求；不修改 `frontend-build.json`、图标来源或生产代码。

连接包 0.2.0 的冻结包校验、prepared receipt、上一 slot 回退和 build 前 assets 准备已有前序实现/离线证据。`current()` 指向已准备包，**prepared 不等于 runtime loaded，也不等于设备已配对**；Android 宿主注入 Provider、实际 DSH Loader、桥生命周期与双设备端到端仍待接线/验收。较早“Package 尚未验证”的草稿说明在包准备范围由 0.1.17 记录更新，不扩展到宿主加载。

DSH 只读调查发现本地 `../Temp_Android/runtime` 只有 arm64-v8a Node 24.18.0 与依赖库/连接插件资产；15 个锁定库存在且摘要匹配。未找到真实 DSH CLI/Web bundle、rootfs/proot 或 x86_64 runtime，同级 `lib/bin.js` 命中项是测试替身。DeveloperCenter 的 DSH bundle 服务限定 win-x64/node22，不能直接作为 Android 执行资产。Termux 固定路径、SONAME 别名、ABI 与宿主启动/停止仍需验证；没有启动 runtime 或发起模型请求。

落地契约保留为显式 `DSH_HOME`，真实 bundle 启动 `web --no-open --host 127.0.0.1 --port <动态端口>`，解析本代鉴权 token 换 cookie 后访问 RPC；Provider 在插件 apply 前注入。优先验证原生 Node 依赖与启动可行性，完整 native 依赖无法适配时再评估研究记录的 rootfs/proot 路线；两者目前均无 Android 实际运行证据。

## 历史桌面通知与协议资料

下方原始通知保留桌面候选、协议和当时 Android 草稿的事实。关于负责人分工、停止、冻结、构建、网络、部署或验证的措辞仅属于当时批次（historical instructions），不是本轮文档维护者的自称或操作授权；当前交付、源码开发和待验收状态以文首及 0.1.18 记录为准。历史 Android 草稿的“未编译/未测试”不覆盖后来包管理与首包证据，也不能倒推 runtime 已加载。

## 给手机端负责人的连接插件接入通知（历史）

日期：2026-10-11，Asia/Shanghai。

用户最新分工：手机端由另一位负责人继续开发。本轮桌面端工作不再修改、编译或安装 Android 应用；本文件仅用于对齐协议与已有实现。工作区已有的 Android 修改需由手机端负责人检查接续，不应直接回滚。

## 共用插件包

PC 和 Android 共用 `dafeiyu-go-connect` 插件，当前开发版本 `0.2.0`，协议 `dafeiyu-go.connection.v1`。不要另建一份手机版插件或复制 PC 的密钥实现。

- 插件源码：`../../Dafeiyu-GO-Connect-Plugin/src/`。
- 高层 Provider 契约：`../../Dafeiyu-GO-Connect-Plugin/docs/contract.md`。
- 当前运行时接入说明：`../../Dafeiyu-GO-Connect-Plugin/docs/runtime-020.md`。
- 更新分发说明：`../../DeveloperCenter.Server/docs/CONNECTION-PLUGIN-RELEASE.md`。
- 已验收的内容加密 wire：`../../Temp_SetupandLauncher/ConnectionIntegration-20261011/PCProvider/WIRE-v1.md`。
- 互通样本：同目录 `runs/20261011-160900/wire-fixture.json`，其中密钥是合成测试数据。

最终本地候选已于 2026-10-11 16:38:26（UTC+8）冻结，测试、构建和 `npm pack --ignore-scripts` 通过：

- 文件：`../../Dafeiyu-GO-Connect-Plugin/Temp/Runtime-20261011/package/dafeiyu-go-connect-0.2.0.tgz`。
- 大小：88,680 字节；74 个包文件，32 个源模块与 32 个生成模块。
- SHA-256：`9DF7085CD5FA9ECF0E6F9A31AACE8D0272EF60C4A9AEF12A2EA96EFAFDF6AA1B`。
- 桌面官方 DSH CLI 隔离安装与实际 Loader/Commands 执行通过；全部 74 个安装文件等于冻结输入。
- 最终包通过管理后端与桌面下载器 33 项隔离验收。后端可用目录：`../../Temp_SetupandLauncher/ConnectionIntegration-20261011/Prepared-Releases-Final/0.2.0/`。

这是本地候选，尚未正式发布或部署；早期同版本 tgz 具有不同字节与哈希，应使用上述确切包。以后更新已分发包时应升版本，不能覆盖已发布的相同版本。

Cordis 入口为 `lib/index.js` 的 `apply(ctx, config)`，依赖宿主 `commands` 服务。实际 DSH 命令注册使用 `ctx.commands.register`，单词命令 `/connection` 分发 `status`、`sync <action>` 和 `broadcast <action>`，命令结果为 `{ kind, text }`。

## Android 宿主需要提供什么

插件加载前由 Android 的 DSH 运行时注入 `connectionProvider`。插件通过 `ctx.get()` 查找可选服务，Provider 在 `apply()` 时确定，必须先注入再加载：

```js
const removeProvider = ctx.provide('connectionProvider', androidProvider)
const fiber = ctx.plugin(connectPlugin)
```

`androidProvider` 实现 `docs/contract.md` 中的 snake_case 方法：

- `get_connection_state`、`subscribe_connection_state`。
- `send_encrypted_message`、`list_incoming_messages`、`acknowledge_message`。
- `create_file_transfer`、`upload_file_chunk`、`complete_file_transfer`。
- `list_incoming_file_transfers`、`download_file_chunk`、`acknowledge_file_transfer`。
- `get_group_file_cache_stats`、`cleanup_group_file_cache`。

JS Provider 边界的文件块是 `Uint8Array`。本地 JSON 传输时 PC 使用标准 base64：上传为 `params.bytes`，下载为 `data.bytes`。Android 适配器负责把本地桥的编码转换成这个 JS 类型，并对齐超时、取消、请求编号和失败映射。

Aries 内容 envelope 内的加密字段使用无 padding Base64URL，与 IPC 标准 Base64 区分。高层 IPC 明文块上限 512 KiB；加密块布局为 nonce 12 字节、密文、GCM tag 16 字节、Ed25519 签名 64 字节，固定额外 92 字节。空文件也传一个已认证块。文件只有所有块通过认证且完整明文 hash 一致后才能 ack。消息 ack 是宿主本地持久消费记录，Aries v1 没有消息 ack HTTP 端点；文件 ack 使用真实服务端接口。算法、AAD、元数据和幂等恢复细节以 wire 文档为准。

IPC 请求形状：

```json
{
  "protocol": "dafeiyu-go.connection.v1",
  "request_id": "req_...",
  "method": "get_connection_state",
  "params": {}
}
```

成功响应：`{ "success": true, "request_id": "...", "data": ... }`。
失败响应：`{ "success": false, "request_id": "...", "error_code": "...", "message": "...", "retryable": false }`。

文件、消息和缓存操作使用当前 `group_id`。Android 应接受与原生宿主当前群组完全一致的 `params.group_id`，拒绝跨组或过期上下文；不能把所有 `group_id` 都当作禁止参数。身份、私钥、内容密钥、Aries token、签名、原始 HTTP 和 epoch 仍由原生宿主管理，不能由插件传入。

`server_connected` 与远端在线状态分开。只有 Aries 真实 presence 支持时才显示远端 `online` 或 `offline`；没有有效数据时显示 `unknown`。连接状态页面只展示被连接设备，缺失硬件信息不使用本机数据填充。

## 工作区配置与状态

宿主与 DSH CLI 必须使用同一 `DSH_HOME`，不能把配置写进另一套 home。原生偏好桥文件为 `DSH_HOME/dafeiyu-connection-preferences.json`：

```json
{
  "schema_version": 1,
  "workspace_sync": { "enabled": false, "root": null },
  "web_broadcast": { "enabled": false }
}
```

运行时每两秒读取偏好变化。`root` 必须是用户明确选择的、DSH Node 进程实际能访问的绝对目录；未选择为 `null`。Android 的 SAF URI 不能直接当作 Node 文件路径，需要由手机宿主提供可访问工作区映射。

插件自己的配置、暂停状态、队列、已处理消息编号、冲突和文件基线保存在 `DSH_HOME/dafeiyu-connection-plugin/{state,queue}.json`。没有明确 home 时只用内存，不猜目录。网页广播历史正文仅保留在内存中，最多 100 条。

当前运行时支持上传、接收、完整 hash 校验、同目录临时文件和原子提交、持久队列、冲突保留、广播收发与去重。以下能力不能作为已完成宣传：远端删除/重命名自动应用、OS 文件事件 debounce、部分下载块恢复、手机通知 UI 投递。它们需要另行接续并验证。

## 在线更新来源

更新管理后端是 DeveloperCenter；Aries 负责配对和加密中继，不承担插件下载。

1. `GET /api/connection-plugin/releases/current`，响应 `Cache-Control: no-store`。
2. 根据清单读取 `GET /api/connection-plugin/releases/{version}/package`。
3. 校验 `protocol`、Android 平台、大小、SHA-256、包身份、运行时入口和安全 tar 路径，再安装同一 tgz。

清单字段：`protocol`、`version`、`sha256`、`size_bytes`、`file_name`、`platforms`、`minimum_host_version`、`archive_path`。`platforms` 同时包含 `windows` 和 `android`。`minimum_host_version: "1.8.0"` 表示 Windows 启动器版本，不是 Android APK 最低版本；Android 要校验自身 Provider 的协议和能力兼容性。

限制：清单最多 8 KiB、压缩包最多 16 MiB、展开累计最多 32 MiB、最多 1024 条归档记录；拒绝绝对路径、`..`、链接、重复路径和安装生命周期脚本。更新过程保留原插件和用户配置，失败不能显示安装成功。下载包本身不代表设备已配对。

后端接口目前为本地实现与隔离验证，尚未部署生产。生产未提供清单时应如实显示不可用。正式发布与选择 current 版本仍需独立执行，不能把历史文档中的部署记录当成本轮授权。

## 桌面端已有证据

2026-10-11，本地 Aries、两套隔离设备身份、两个生产 `CurrentUserOnly` 管道和真实 Node 共享插件链路通过 28 项检查，包含：

- 700000 字节文件实际内容与完整 SHA-256 一致，原子提交后无残留 staging 文件。
- 空文件、默认 ask 冲突保留本机修改、keep_both、插件重启后保留文件基线。
- 加密网页广播收发、稳定幂等键、消息 acknowledgement。
- 相同已有文件先验证传入块再 acknowledgement，真实 Aries 缓存逻辑删除和物理清理。

最终冻结 tgz 复测证据：`../../Temp_SetupandLauncher/ConnectionIntegration-20261011/PCProvider/runs/20261011-164145/results.log`，28 PASS、0 FAIL，另有 2 项生产身份/管道检查通过。该 run 的 `package-hash.log` 与本通知的最终包哈希一致。

这证明桌面端插件与本地 Aries 的真实链路，不证明 Android 已加载插件、手机与 PC 已互通或生产服务器已部署。手机端负责人需单独完成这些验收。

## 现有 Android 草稿交接

用户调整分工前，子 Agent 已新增或修改以下 10 个文件；停止后全部保留，未回滚其他人改动：

| 文件（相对移动端仓库） | 当前草稿内容 |
| --- | --- |
| `app/src/main/java/com/dafeiyu/mobile/data/AriesCrypto.kt` | Ed25519、X25519、HKDF、AES-GCM 及配对/消息/文件 wire。 |
| `app/src/main/java/com/dafeiyu/mobile/data/AriesIdentityStore.kt` | 受控 seed 使用及用后清零。 |
| `app/src/main/java/com/dafeiyu/mobile/data/AriesClient.kt` | 签名 mutation 与受限字节 GET/PUT。 |
| `app/src/main/java/com/dafeiyu/mobile/data/AriesProviderVault.kt` | Keystore 保护的状态和缓存。 |
| `app/src/main/java/com/dafeiyu/mobile/data/LocalPluginBridge.kt` | Unix socket、UID/PID、capability/generation/nonce 校验；尚未接真实宿主。 |
| `app/src/main/java/com/dafeiyu/mobile/data/AriesConnectionProvider.kt` | 高层消息、文件、缓存及配对状态。 |
| `app/src/main/java/com/dafeiyu/mobile/data/AriesProviderScope.kt` | 当前组校验已落源码，尚未测试。 |
| `app/src/main/java/com/dafeiyu/mobile/data/ConnectionPluginPackage.kt` | 同包验证、更新和 active 记录草稿，尚未验证。 |
| `app/build.gradle.kts` | 加密/压缩依赖及 Temp_Android runtime 输入。 |
| `tools/prepare-node-runtime.ps1` | arm64 Node 下载校验和提取脚本。 |

停止时间为 2026-10-11 16:27（UTC+8）。未留下本任务 Android 构建、下载或运行时进程；共用 ADB server 未终止。

现有 `Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.16.apk` 早于最新修改，不能代表当前源码编译通过。最新 Provider 修改、Scope 和 Package 更新器尚未重新编译或行为测试。Node arm64 包已下载提取，但 SONAME 别名、许可证打包和手机执行仍待接续。

没有共享 tgz 在 Android 加载、真实 DSH 宿主运行、双向加密互通、在线更新成功或手机 E2E 的证据。真实宿主启动、前台服务、原生指纹确认和 UI 尚未接线。最后一次目标 ADB 连接被拒绝，本任务停止后没有重试。

请重点检查原生桥调用方身份与生命周期、Provider 当前群组验证、JS 返回字段/base64 编码、稳定消息幂等键、PC/Android 密文格式和签名互通、配对指纹原生确认，以及实际 DSH 加载路径。Scope 应覆盖同组、跨组、缺失、null、错误类型与空参数 state。更新激活失败必须保留旧包；下载及 active 写入成功不能替代宿主实际加载验证。以当前源码和后续负责人报告为准，不直接将草稿或本文件视为构建通过证明。
