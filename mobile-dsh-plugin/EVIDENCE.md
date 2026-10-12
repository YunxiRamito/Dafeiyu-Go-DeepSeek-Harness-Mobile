# 官方入口证据与交付清单

核实日期：2026-10-11。证据来自本机实际安装的官方包 `0.2.1-alpha.1`，并非 HTML preview 的自建扩展接口。只读读取源码、类型与 manifest，没有安装/启用用户配置。

## 本机证据路径

| 事实 | 官方文件 |
| --- | --- |
| platform web、client 导出、惰性 factory 协议 | `G:/DeepSeek DSH/node_modules/@deepseek-ai/dsh-client-modules/README.zh.md` |
| 实际 bundle 的 ModuleLoader.load 格式 | `G:/DeepSeek DSH/node_modules/@deepseek-ai/dsh-client-ui-layout/lib/client.js` |
| slots.inject/register 生命周期 | `G:/DeepSeek DSH/node_modules/@deepseek-ai/dsh-client-ui-renderer/lib/types/client/registry.d.ts` |
| 设置启动器 owner 与 openSettings | `G:/DeepSeek DSH/node_modules/@deepseek-ai/dsh-client-ui-settings/lib/types/client/contract/slots.d.ts:170` |
| 真实设置 launcher owner 回调传递 | `G:/DeepSeek DSH/node_modules/@deepseek-ai/dsh-client-ui-settings-general/lib/client.js` |
| 官方账号默认占据 launcher priority 0 | `G:/DeepSeek DSH/node_modules/@deepseek-ai/dsh-client-ui-settings-account/lib/client.js` |
| 左下 footer list 及 wide 参数 | `G:/DeepSeek DSH/node_modules/@deepseek-ai/dsh-client-ui-sidebar/lib/types/client/contract/slots.d.ts:76` |
| shell.bottom / shell.overlay 声明 | `G:/DeepSeek DSH/node_modules/@deepseek-ai/dsh-client-ui-layout/lib/types/client/index.d.ts` |
| Workspace list 可订阅真实快照 | `G:/DeepSeek DSH/node_modules/@deepseek-ai/dsh-api-workspace-controller/lib/types/client/service.d.ts:29` |
| WorkspaceSnapshot.items / phase / state | `G:/DeepSeek DSH/node_modules/@deepseek-ai/dsh-api-workspace-controller/lib/types/client/model.d.ts` |
| 官方图标名称与 size 参数 | `G:/DeepSeek DSH/node_modules/@deepseek-ai/dsh-client-ui-primitives/lib/types/icons/index.d.ts` 与 `props.d.ts` |
| 实际 #root 挂载与平台模块表 | `G:/DeepSeek DSH/node_modules/@deepseek-ai/dsh-web-frontend/dist/assets/index-DjTxlw_T.js` |
| 实际官方 slot 优先级、卸载、重新声明实现 | `G:/DeepSeek DSH/node_modules/@deepseek-ai/dsh-client-ui-slots/lib/index.js` |

同级 PC 项目的 token-stats 插件及 npm 缓存只用于最初发现；最终客户端入口以实际官方 client 模块证据为准。DSH `dsh-web` 是搜索/抓取能力，不能误当网页外壳扩展 API。

## 清单

- [x] 独立 `mobile-dsh-plugin/` manifest、源码与真实格式 client 产物
- [x] 显式宿主能力门控；普通网页休眠；权限/会话撤销清理
- [x] 官方 settings.launcher 回调打开真实 DSH 设置
- [x] 固定 App 设置请求；等待/错误/重试/取消/忽略迟到回包
- [x] 左下设备统计；过期数据降级；成员语义不等于密钥核验
- [x] Workspace 官方列表匹配；不伪造当前目录
- [x] 安全区、VisualViewport、resize/overlay 键盘算法与恢复
- [x] 玻璃材质、150/360/380ms 动效、按压阻力、reduceMotion/reduceTransparency
- [x] 15 项 Node 契约测试通过，含 4 项本机官方 SlotCore 生命周期集成
- [x] 复用官方平台模块的离线 Playwright 交互/截图测试通过
- [ ] 真实 DSH profile/plugin loader 启用与完整 UI 联调
- [ ] 原生 WebView host 注入、origin/session 验证与真实设备事件
- [ ] ARM64 runtime bootstrap 与 Termux SONAME 依赖解决
- [ ] 真机/Emulator 键盘、横竖屏、safe area 与原生 App 设置联调

## 验证结果

2026-10-11，使用本机 Node `26.7.0`、已安装 Playwright 和系统 Chrome headless。在 `390×844`、`320×640`、`1280×800` 下均通过：中文设备状态、member/keyVerified 区分、同一行设置按钮、官方 owner 回调调用、固定原生动作、取消/迟到回包/错误重试、按压形变、reduceMotion/reduceTransparency、键盘 overlay 几何、dialog 无水平溢出、停用/卸载恢复。另通过普通网页休眠、显式注入激活与卸载场景。

截图位于 `G:/DeepSeek DSH/DSH Works/Project/Dafeiyu-Go/Temp_Android/mobile-dsh-plugin/`。夹具明确标记“移动插件离线验证”，并在官方 loader 提供平台模块时主动中止真实宿主引导；不得把截图或测试结果当成实际 DSH runtime 已运行。设备和目录内容在夹具中均明确标注离线。
