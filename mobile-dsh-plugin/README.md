# Dafeiyu Go 移动 DSH 插件

本目录是独立插件源码、客户端产物、宿主契约及离线测试。适配目标是本机安装的官方 DSH `0.2.1-alpha.1`。没有修改 Android、PC 配置或顶级文档，没有连接真实账户。

## 真实扩展入口

使用官方 `package.json.dsh.client.platform = "web"`、`./client` 导出及 `window.__ModuleLoader__.load({id, factory})`。客户端 factory 只依赖平台已有的 `react` 与 `@deepseek-ai/dsh-client-ui-primitives`，不依赖私有 DOM 类名。`dsh.client.inject` 是包启动图元数据，源码 `inject = ["slots", "workspaces"]` 是服务依赖。

通过 `ctx.slots.inject()` 等待正式声明，再使用 `ctx.slots.register()`：

| 官方入口 | 插件用途 |
| --- | --- |
| `settings.launcher` | 展开侧栏提供同一行 DSH 设置和 App 设置；折叠侧栏提供可触达的设置按钮，打开同一行双入口 |
| `sidebar.footer.action` | 左下真实在线设备状态与设备列表 |
| `shell.bottom` | 选中工作目录，只按宿主 workspaceId 匹配 `ctx.workspaces.list` |
| `shell.overlay` | 设备/设置请求的原生 HTML dialog，支持焦点、Escape、取消、等待、错误与重试 |

`settings.launcher` 使用 `priority: -100`，移动模式有意遮蔽官方账号快捷启动菜单；完整 DSH 设置仍由 owner 提供的 `openSettings()` 打开。插件卸载恢复原菜单。`shell.bottom` 同样遮蔽较低优先级的已有底部占位；没有替换 `root` 或 `sidebar`。

安全区只调整已在官方 web frontend 核实的挂载点 `#root`，不猜内部选择器。样式仅覆盖插件自身的 `.dm-*`，并在停用时恢复插件写入的几何属性。

## 启用与宿主接线

普通浏览器默认休眠。原生宿主必须显式安装 `window.__DAFEIYU_MOBILE_HOST__`，提供本目录 [HOST-CONTRACT.md](HOST-CONTRACT.md) 所列能力，再发出 `dafeiyu-mobile-host-ready` 事件；插件也支持 apply 前预注入。单纯修改 UA、窗口大小或触发事件不会启用。

App 设置是本插件定义的固定 host callback 协议，**不是 DSH 官方原生设置 API**。唯一动作是 `open_app_settings`，不接受网页提供的 URL、路径、命令、脚本或额外参数。JS 的形状校验与会话比较只负责页面一致性；origin/session 的真实性和权限必须由原生 host 独立核验。尚未接入 Android host。

所有新控件使用半透明材质、背景模糊、边缘高光、按压形变与径向阻力。按压 150ms、释放 360ms、入场 380ms，与现有 GlassMotion 时序一致；同时遵从宿主及系统 reduceMotion/reduceTransparency。CSS 玻璃没有声称实现原生光学折射。

设备时间戳超过 60 秒显示待刷新，不以旧在线状态冒充当前连接。成员资格不能代表密钥核验；设备行仅在 `member && keyVerified` 时显示“密钥已核验”。状态来源仍由 host 负责。

## 构建与测试

无需联网安装依赖：

```powershell
node scripts/build.mjs
node --test tests/core.test.mjs tests/slots.test.mjs
```

`lib/client.js` 已生成，是可供官方插件 loader 消费的真实格式产物。构建使用自包含 factory 的源码序列化，不进行脆弱的 export 文本替换；依赖不隐藏在闭包外。

官方 SlotCore 集成测试默认读取 `G:/DeepSeek DSH/node_modules`。其他机器设置 `DSH_NODE_MODULES` 为实际 node_modules；缺失时对应测试明确跳过。

离线浏览器测试还需要已存在的 Playwright 与浏览器：

```powershell
$env:DSH_NODE_MODULES = 'G:/DeepSeek DSH/node_modules'
$env:PLAYWRIGHT_MODULE = '<已安装的 playwright/index.mjs>'
$env:BROWSER_CHANNEL = 'chrome'
node tests/browser.mjs
```

浏览器夹具在官方 frontend 的 loader seam 捕获其真实 React、图标和 SlotCore，随后在宿主启动前抛出标记错误停止引导。其 Workspace、设备、设置回包及 slot owner 是明确标注的离线夹具；没有伪装成运行中的 DSH。测试用 localhost 临时端口、阻止外部请求，测试结束关闭服务和浏览器。截图写入 `../Temp_Android/mobile-dsh-plugin/`（相对移动项目根目录）。

## 未验证边界

尚未启用真实 DSH profile；没有完成 Android WebView host 注入、原生 origin/session 校验、真实设备事件、ARM64 进程引导、Termux SONAME 依赖、真实键盘/旋转/系统 inset 联调或 emulator 验证。离线测试通过仅证明插件在明确契约和本地官方平台模块下工作。

入口证据及完成清单见 [EVIDENCE.md](EVIDENCE.md)。
