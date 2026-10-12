# 两种前端模式与未来接缝

日期：2026-10-10。用户已确认自有轻量编辑器在此阶段就是轻量对话，不加入独立文本 / Markdown 页。当前范围仍为 Android 前端；本轮不实现真实 DSH 启动、WebView 注入、DSH 插件或新网络协议。

## 真实 DSH 前端参考已经在本机

**有可读的前端实现，但主要是 npm 发布的 JavaScript bundle 和 TypeScript 声明，不是完整 TSX 工程。** 位于 `G:/DeepSeek DSH/node_modules/@deepseek-ai/`，实际 `@deepseek-ai/dsh` 及所查界面包版本均为 `0.2.1-alpha.1`：

| 本地包/文件 | 可核验内容 |
|---|---|
| `dsh-client-ui-layout/lib/client.js:95` | `AppFrame` 三栏：sidebar、中心 conversation/global panel、rightbar；根布局由 slots 组合。`:117` 的 `MainPanel` 通过 `main` slot 选择活动 panel，缺省 conversation。 |
| `dsh-client-ui-sidebar/lib/client.js:402` | 底部先渲染 `sidebar.footer.action`，随后 `sidebar.settings`；`:487` 起声明这些子槽。 |
| `dsh-client-ui-sidebar/lib/types/client/contract/slots.d.ts:67` | `sidebar.settings` 是 root/single；`:76` 的 `sidebar.footer.action` 是 root/list，传入 `wide` 表示宽栏/收拢状态。 |
| `dsh-client-ui-chat/lib/client.js`、`dsh-client-ui-conversation/lib/client.js` | 可读聊天正文、会话与输入器的实际 React 编译实现及内嵌 CSS；适合研究布局分工，不等于适合逐段复制进 Compose。 |
| `dsh-client-ui-slots/lib/types/index.d.ts` | slot 注册/组件/props 的类型契约，可作为后续扩展前端入口的审查起点。 |

`package.json` 的 `repository.directory` 指向上游 `packages/client/ui-layout` 等目录；`files` 仅发布 `lib/index.js`、`lib/client.js`、`lib/types/**/*.d.ts`。未发现所查包的本地 `src` 目录；文件尾虽有 sourcemap 注释，不能据此声称完整原源码已下载。参考 bundle SHA-256：sidebar `1737ff31a7febe50ea386c016fbe08ae3c0a4b7c3e4df7b378cec3d8b7ac4bff`；layout `519c5c412d908dd51d66e9ff778a61613116eace6a20089b42a09980b3cac2e5`。

桌面 Click-To-Run 是 C#/XAML 启动器，不是完整 DSH 网页源码；其 `source/assets/dsh-token-stats` 是 Host 用量插件。`G:/DSH_Official/resources/app.asar` 存在，本轮未解包执行。此前研究过的 DSHA 固定提交 `18eaee90616808455abb225a8ae8ce5ab37c7f40` 提供 WebView 宿主、移动适配 bundle、类型及补丁；当前 Temp_Android 中已无可读取的 DSHA clone，保留证据见 [DSHA-CODE-REVIEW.md](research/DSHA-CODE-REVIEW.md)。没有为本轮重新下载运行资产。

## DSH 布局示意应遵循的结构

- **左栏**：品牌和侧栏折叠按钮 → 新建会话 → 全局 panel 入口 → 工作区/会话浏览 → 底部扩展动作和设置（`dsh-client-ui-sidebar/lib/client.js:340–406`）。顶部的新建主要属于侧栏，不应误称 DSH 本来在聊天标题右边放同款原生按钮。
- **对话顶部**：常驻 leading 导航、当前 Session 标题和可用 Views；blank/sessionless 时隐藏部分会话 chrome（`dsh-client-ui-conversation/lib/client.js:21190`）。
- **输入卡底部左侧**：加号命令菜单、权限模式、Plan 和 left 扩展槽；文件多选 input 为隐藏控件，不能把加号简单描述成只有附件按钮。
- **输入卡底部右侧**：right 扩展、模型选择、可选 activity 控件、停止/发送。模型选择实际是 `conversation.input.model`，位于 composer，**不是顶部的固定模型开关**（conversation bundle `:22320–22445`；model-selection bundle `:1114` 说明、`:1882` 注册）。发送为向上箭头；运行中停止为方块；附件和输入 dock/ContextMeter 有各自区域。
- **窄屏**：所查官方 alpha layout 在 `<1024px` 自动折叠成 56px 图标 rail，手动展开仍占栏宽（`dsh-client-ui-layout/lib/client.js:10–43`）。手机抽屉、48dp 按钮和键盘避让是本产品适配目标，不能宣称官方已经实现。

前端预览可以保留这些功能位置关系，同时使用新的 WP8/Win11 审美。未实现的权限、Plan、文件、模型和活动控件只做可识别演示，不显示为真实已开启。

## 界面路由

两模式共用原生设置、模型、拓展和运行状态入口，主要工作区不同。以下路由是产品界面草案，**不是已存在的 DSH RPC 或插件 API**。

| 入口/动作 | DSH 模式 | 自有轻量编辑器模式 |
|---|---|---|
| 开屏 | 进入 DSH 工作区；自动开始一次启动流程，显示准备、连接、失败/重试状态，不要求先绕去“运行”页点击启动。当前只模拟。 | 直接进入轻量对话，可立即输入；不因打开它就启动 DSH。 |
| 主工作区 | 保留 DSH 的会话、工作区、消息与输入布局概念；手机窄屏将侧栏收为抽屉，避免三栏同时挤在屏幕。 | 自有原生简洁对话区：留白欢迎语、底部输入卡、少量工具和模拟流式回复。 |
| 左下按钮 | DSH 对话区左下“软件设置”和共用抽屉底部“设置”进入原生设置；当前是原生演示点击，未来才由 DSH 前端插件/桥接发出导航请求。 | 原生导航直接进入同一设置页，不经过网页。 |
| 模式切换 | 顶部模式下拉或左上菜单可切到轻量对话；保留当前会话视图，运行服务是否继续需后续确认。 | 可切回 DSH 工作区；保留草稿，启动流程应幂等，禁止因重组/返回重复启动。 |
| 返回 | 先关闭抽屉/弹层，再返回原生设置的来源工作区；离开网页不自动等同停止服务。 | 先关闭工具/详情，再回编辑器；不把返回动作当成清空草稿。 |

当前开屏自动启动只针对选择 DSH 模式后进入工作区，不表示 Android 开机自启或后台唤醒；界面不能显示真实服务“已就绪”来冒充后端已实现。

## 左下按钮的可行接缝

本机 `0.2.1-alpha.1` 的 sidebar 已明确拥有 `sidebar.footer.action` list slot，这是将来新增“应用设置”按钮的真实候选位置；`sidebar.settings` 为 single seat，已有 DSH 设置占用时不应未经审查直接替换。未来先选独立 footer action，保留 DSH 本身设置，再验证窄屏抽屉中的位置、折叠 `wide=false` 状态与可访问名称。这里不提供未经验证的 `ctx.slots.register` 示例代码，也不宣称选定 Android DSH 版本已有相同契约。

跨网页/原生只需要“打开一个受支持的原生设置页”的窄导航能力。后续约定的消息必须版本化、带当前页面/运行代次校验，原生端白名单映射 destination；不接受任意 URL、Intent、shell 命令或密钥正文。优先研究 AndroidX WebView 的来源限定消息桥；DSHA 在 `ui/WebPreviewActivity.java:612` 使用 `WebViewCompat.addWebMessageListener`，并检查当前页面、主 frame 与本机服务来源，是已核验参考。当前前端仅调用本地 Kotlin 导航回调，无需 INTERNET 或 JS bridge。

桌面 `source/DshModelRpcClient.cs:34` 的根路径 token→cookie 交换和干净 `/api/{endpoint}` JSON-RPC 是已有模型管理接缝，**不能据此推导完整聊天、编辑器、插件跳转或 Android 生命周期 API**。轻量编辑器未来也应通过明确适配层接入能力；本轮不将文本编辑动作伪装为远程文件修改。

## 待验证

- Android 最终固定 DSH 版本（此前 DSHA 是 `0.2.0-rc.2`，本机 bundle 是 `0.2.1-alpha.1`），slot/API 兼容和插件实际加载顺序。
- footer action 的公开注册契约、卸载清理、不同 layout/profile 的存在性，以及 DSH 原设置与应用设置的职责。
- 真正 WebView 鉴权、页面刷新/进程重建、系统返回和手机键盘；当前只读研究不能声称已通过。
- 自动启动与停止/恢复的生命周期语义，以及切模式后服务是否保持；前端模拟与真实后端状态必须分离。
- 轻量对话的模型 / 会话连接协议、持久化与文件授权留待后端讨论；本轮消息与草稿仅当前进程保留，不做 Markdown / 文本文件编辑。

本轮只新增本报告；未修改 Kotlin、Manifest、构建文件、桌面安装资产或真实 DSH 数据，未新增临时文件。

