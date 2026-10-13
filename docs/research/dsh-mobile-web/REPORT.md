# DSH 手机网页插件研究

日期：2026-10-13，Asia/Shanghai。经本机 `http://127.0.0.1:7890` 读取固定上游源码、README 与许可证；下载证据保存 `../../../Temp_Android/qa/pc-dsh-web-customization-20261013/plugin-audit/upstream/`。本记录持续维护，不作为版本交接或设备验收。

| 来源 | 固定版本／commit | 许可证 | 已审部分及用途 |
| --- | --- | --- | --- |
| [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness/tree/5badb15009ae1756c3afe0ae0cef1faafc290ccc) | `dsh-v0.2.1-alpha.1`，`5badb15009ae1756c3afe0ae0cef1faafc290ccc` | MIT | slots、ui-renderer、layout AppFrame、theme、client modules、web-styling、package manifest；沿用官方插件注册、稳定 slot 标记及生命周期，不修改 SDK |
| [wenbin-wb/dsh-bridge](https://github.com/wenbin-wb/dsh-bridge/tree/06ef0e273c9cec8f405b50d4599d541d623552ee) | 2.14.0，`06ef0e273c9cec8f405b50d4599d541d623552ee` | MIT | README、LICENSE、mobile-yield、mobile-styles、client/index；查明现有手机顶栏来源及让出机制，不复制第三方全套页面或远程授权逻辑 |

官方 tag 的准确名字带 `dsh-` 前缀；首次请求 `v0.2.1-alpha.1` 返回真实 404，之后通过 tags API 解析上述正确 commit。不能将失败写成“无成熟实现”。两套固定源码实际下载均 HTTP 200。

实际服务的官方 `SlotOutlet` 输出 `[data-slot="…"]` 且容器为 `display: contents`。皮肤样式必须落在其真实子节点或经 slot 找到的父节点上，不能给空盒子计算布局。主要区域包括 `conversation.header`、`conversation.session.header.*`、`conversation.composer.bar`、`conversation.input.model`、`conversation.input.permission`、`sidebar`、`shell.overlay`。CSS Modules 哈希不作为插件公共接口。

单一插槽最小 priority 生效，不能直接包装旧 occupant：所有者必须声明 child slots，错误复用会抛 `SlotOwnershipError`。因此保留原业务组件，使用门控样式和独立手机导航。官方图标与既有控件回调继续使用，不新画图标或伪造模型、任务和在线统计。

标准插件包声明 `dsh.client.platform = web` 并导出自包含 `./client` 的 ModuleLoader factory，Host 入口与 Client 分离。Client 使用 `ctx.effect` 释放 DOM、style、观察器和事件；Host 不读 `window`。运行时创建 style 必须声明 `data-plugin`／`data-plugin-css`，否则 HMR 可能误认归属。官方主题 token API要求同时定义浅、深值；当前皮肤使用页面级变量，不写全局服务器偏好。

启用条件为 Android 宿主的版本化访问上下文，或电脑显式 `dafeiyu-preview=mobile`。窄屏和 UA 只用于布局验证，不能单独开启皮肤。所有 CSS 以 `html[data-dafeiyu-surface="mobile"]` 门控。皮肤仅当前页面生效，普通电脑原主题与在线设备页脚保留。第三方 bridge 的 mobile-yield 是兼容线索，不能成为插件独立运行的依赖。

官方 web profile 的 `patchReload=live` 和 client modules 支持启停后的完整图同步与效果清理；安装仍须核对具体加载结果，不能以 package.json 多一条依赖冒称客户端加载成功。开发预览不触发付费模型调用、不重放旧工具、不读取或输出凭据；浏览器产物全部在 G 盘。Android 接入和真机渲染稳定性另行验收。

2026-10-13 实现与验证：标准 Host/Client 分离的 `dafeiyu-mobile-ui@0.1.0` 已通过开发 junction 安装到正在运行的 PC web profile，未重启 DSH、未改第三方包。门控导航保持官方回调；实际浏览器发现展开侧栏后官方 `sidebar.toggle.badge` 会卸载，现用布局服务与展开来源接续，并有离线及实际点击回归。独立 Node 边界 **12/12 PASS**，实际 DSH 页面 **13/13 PASS**，真实 iframe 预览 **9/9 PASS**；证据在上述 QA 目录，未提交模型消息。

源码资源最终为 49,743 bytes / SHA-256 `2a4bd6f961cb4b0694f5ce2119b580f46fc198ec8418e5045098c8e0760ccdf7`。官方 `dsh-client-modules` 的 `prepareSource`、`buildComboScript`、`comboScript` 为每个 factory 加 `;\n`，并加包对应的相对 source-map 注释，因此 HTTP 资源为 49,817 bytes，不能直接把完整响应摘要和源码摘要当成同一份字节。已核对固定 SDK 源码，只去除精确匹配包名、revision 的官方尾部后，源字节与摘要完全一致；原始失败比对与最终校验均保留在 `local-install/`。

样式静态 CSSOM **80 个顶层规则、98 个选择器**均有效且门控。实际截图已查看：输入卡片为半透明与 16px blur，圆形按钮覆盖官方 superellipse，小范围轻阴影；显式主题与底层 DSH 主题相反时附件加号仍可读，材质关闭后真实 blur 为 none。普通桌面及窄屏普通页面无皮肤 marker/style/nav；这仍是电脑浏览器证据，不代表 Android WebView 或 Aries 网页隧道通过。

## 启动动画、原生展示桥与沙箱图标接续研究

2026-10-13 追加研究仍通过 `http://127.0.0.1:7890`，实读源码、README 和许可证。Motion GitHub API tag 查询返回 HTTP403，原记录保留；固定 tag 的 raw 源码成功，不能把 API 限制描述成没有成熟实现。

- `motiondivision/motion` 固定 `v12.23.24`，MIT，Copyright 2024 Motion B.V.，审阅 `README.md`、`LICENSE.md` 与 `packages/motion-dom/src/animation/waapi/start-waapi-animation.ts`。借鉴有限动画、完成通知和退出清理的生命周期；本插件用 CSS animationend 加有界后备 timer，不引入 Motion 包或复制其实现。研究副本存 G 盘 QA `hub-settings/upstream-motion/`。
- 真实 DSH connection SDK `0.2.1-alpha.1` 在 generation readiness handshake 成功后才发布 `connected`。启动提示等待该快照，同时要求真实 root、composer 与左上按钮尺寸就绪；连接在揭示期间丢失会回到失败提示，不把网页 load 事件当引擎就绪。
- AndroidX WebKit 固定已安装 `1.14.0` 的 sources.jar（SHA-256 `714bc9cebdba29d5e336e026367c50ff235bd0090e1949efcbd81537a213176e`），审阅 WebMessageListener、JavaScriptReplyProxy、ScriptHandler；固定 AndroidX 参考 commit `078de553b861299af03d7a548377cc1307db776d` 的 README、LICENSE 与 WebViewCompat 也经代理取得。Apache-2.0；只用官方 API，不复制实现。GitHub API403与 raw 下载证据保存在 `hub-settings/upstream-webkit/`。设计采用精确 loopback origin、顶层入口路径、document-start 及固定无参数操作，不提供任意 URL／命令调度；设备状态逐字段构造公开投影。
- DSH 官方 `ReadOnlyArtwork`、`WorkspaceWriteArtwork`、`FullAccessArtwork` 来自上述已审 `5badb15009ae1756c3afe0ae0cef1faafc290ccc` SDK，原始 16×16 路径转换为 Android VectorDrawable，2／5／3 个路径逐项保留。完整 MIT 及来源在 `third_party/dsh-ui/NOTICE.md` 与资源注释，转换脚本及 XML 解析证据在 G 盘 QA。此处仅替换图标，不增加或修改沙箱权限能力。
- 网页扳手用 Microsoft Fluent `a347af95478f989038cccbf085978865264c048a` 的 Wrench24，齿轮用同版 DSH 的 Settings16。原路径及完整 MIT 通知随插件打包，来源和源码在 QA `upstream-icons/`。

上述桥接源码与资源检查不代表 Android 已编译或设备已验收。最新网页检查、源码冻结摘要、候选插件包和 Android 待办由 HANDOVER.md 统一记录。

## 触屏侧栏与启动动画预览

2026-10-13 经 `http://127.0.0.1:7890` 阅读 [pmndrs/use-gesture](https://github.com/pmndrs/use-gesture/tree/%40use-gesture%2Freact%4010.3.1) 固定 tag `@use-gesture/react@10.3.1` 的 README、MIT LICENSE 和 `packages/core/src/engines/DragEngine.ts`（12,885 bytes）。研究借鉴单指身份、轴向阈值、纵向滚动、点击过滤及取消／失焦清理的处理方式；本插件继续使用原生 PointerEvents，不引入包、不复制其实现。源码和实际下载日志保留在 G 盘 QA `touch-sidebar/upstream-use-gesture/`。

同版 DSH `Rows.tsx`、`SubagentHeaderLineage.tsx` 与 `JobListAction.tsx` 已按上述固定 commit 实读：子 Agent 入口数量来自真实目录，后台任务数量含 live 与 settled，不能仅凭通用 trigger 的存在判断当前会话有活动。触屏入口应转发官方列表操作及原有确认流程，不复制 React 管理的列表，不在预览验收中归档或删除用户原有数据。新增动画预览仅接受 8791 准确父页面发给显式移动预览的固定消息，播放展示不改变真实 DSH connection 状态。

真实 Chrome 触摸会把文字节点的隐式 capture 交给整行的显式 capture，子节点 `lostpointercapture` 不代表行的手势取消。松手后自动释放 capture 也不能取消刚排队的原生删除菜单；只有活跃整行失捕获才取消。卸载行需先复位手势再释放属性，避免在清理后重新写入位移。这两项实现错误已保留失败日志并修正。最终 QA 的首次工作区失败另由触点引起：touchStart 会揭示鼠标悬停按钮，预检命中 SPAN 但实际 DOWN 为原生按钮内 SVG path，按交互控件规则应排除；改从行正文开始检查后通过，原版按钮行为继续保留。

网页工作区删除仍使用官方既有语义（解除登记、保留目录和日志、会话归 Ungrouped），未实现 Android 独立生命周期中的整组归档、任务停止和磁盘删除扩展。实际浏览器验证拦截原生归档／删除回调，只核对触屏转发、菜单和布局，不证明删除持久化已执行。

轻引擎在线后台任务投影另核对固定 DSH `packages/shell/tool-bash`、`tool-pwsh`、`packages/jobs/tool-jobs` 和 `packages/api/job-controller`：真实创建回执来自 `bash`／`pwsh` 的 `run_in_background`，不是本机 `create_background_task`。精确成功创建回执可作为历史存在证据；`job.list`／`job.follow` 是独立实时流，不能将历史文本当当前运行状态。promotion 回执含任意 stdout，本轮不据字符串搜索生成任务。

## Android 标准 profile 接入

2026-10-13 用户确认网页后恢复手机接入。同一固定 SDK 的实际 `dsh-app-boot` `initProfile`／`readProfileManifest` 和 `dsh-atomic-write` `withFileLock`／`writeFileAtomic` 已复核；沿用官方 manifest 的 dependencies、`dsh.profile.bundles` 和普通 Node 包解析，不修改 SDK 或全局页面注入。APK 内单独冻结零依赖手机 UI 包，校验归档摘要和逐文件摘要、拒绝链接／额外文件／错误身份；仅在服务启动前更新本应用拥有的 profile 依赖及源链接，外来同名安装拒绝覆盖。不运行包脚本，不改冻结 Connect 字节，普通 PC 是否启用仍由当前网页上下文决定。

`qa/android-mobile-plugin-0.1.27/profile-report.json` 为真实固定 SDK 独立 G 盘夹具的9项通过：默认 profile、Node resolve、重复启动、已有依赖／设置保留、外来 spec 拒绝、错误版本拒绝、标准 profile reader、固定 pnpm 10.34.5 的离线 hoisted install 后来源保持，以及官方 loadProfileDirectory／composeEntries 加载真实 UI 客户端。来源使用 `link:`，避免 pnpm 把 `file:` 复制进 store 后丢失签名资产的内容摘要路径。未运行安装脚本、重启用户电脑服务或调用模型；实际窗口绘制另行检查，不以 profile 专项代替。
