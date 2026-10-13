# DSH 远程网页：源码研究与边界

2026-10-12。通过 `http://127.0.0.1:7890` 读取 GitHub README、许可证及实现；未运行上游脚本。原始研究文件保存在 `../Temp_Android/qa/remote-web-research/`。

| 项目 | 固定版本 / commit | 许可证 | 审查内容与采用结论 |
| --- | --- | --- | --- |
| node-http-proxy | 1.18.1 / `9b96cd725127a024dabebec6c7ea8c807272223d` | MIT | README、LICENSE、ws-incoming.js；参考 Host/Origin 重写和 upgrade 生命周期，不直接公开任意目标代理。 |
| Java-WebSocket | v1.5.6 / `ae78d38da8800fe79b19550d37b074eebd1bf965` | MIT | README、LICENSE、Draft_6455.java；审查分帧、长度限制和取消。Android 本轮不引入新依赖。 |
| NanoHTTPD | nanohttpd-project-2.3.1 / `16ed41b22c2739838ec7cb0e6ba26d52f4c8db3e` | BSD-3-Clause | README、LICENSE、NanoHTTPD.java、NanoWSD.java；默认临时文件目录、连接数、frame 分配不符合本项目边界。NanoWSD 在分配前只限制到 Integer.MAX_VALUE，不能只在 onMessage 后检查。未复制源码。 |
| websockets/ws | 8.21.0 / `bca91adf15677e47dbe4f959653452727be28b94` | MIT | README、LICENSE、lib/websocket.js；参考 maxPayload、禁用压缩及有限发送缓冲，不运行上游脚本。 |
| deepseek-ai/deepseek-harness | 已安装 npm `@deepseek-ai/dsh-*` 0.2.1-alpha.1 | MIT | 审查 api-gateway、client-connection、host-webserver、session/workspace-controller 的实际源码及生成 descriptor。复用宿主 Gateway 和生产 typed adapter，不复制或另造 DSH 协议。 |

DSH 真实网页 POST RPC 为 `{type:"client-request",rpcId,method,payload:{args}}`，WebSocket 为 `/api/remote.mux`，client frame 为 open/item/end/cancel，server 为 item/end/error。模型选择必须通过实际持久化适配，不能通过 raw config RPC 绕过手机权限。

PC 在 loopback 私有 Cookie jar 内用 authenticatedUrl 交换 token，只返回网页资源和公开业务数据，不透传 token、Cookie、Set-Cookie 或配置凭据。Android 只监听 127.0.0.1，同端口承载 HTTP 与 WebSocket，检查 Host、Origin、消息大小和连接数；请求通过固定身份作用域和 Connect 的既有加密消息中继。

HTTP 采用有限正文、48 KiB 分片、精确顺序及结束摘要。WebSocket 采用有界 mux queue、流数量、帧大小和明确 generation；断线不重放未知写操作。PC 持久保存请求意图后执行，结果丢失时不能重新执行 POST。未知/凭据管理 RPC 拒绝；思考和工具活动采用既有正向公开投影。

实现、离线验证、真实 Aries/DSH 端到端及 Android 设备验证是不同状态。真实网页端到端未通过前，`remote_web_tunnel` 保持 false，UI 不把现有 web_broadcast 能力当成网页隧道已就绪。

## 2026-10-13：真实网页和 Android 宿主接续

已用隔离的 G 盘 `DSH_HOME` 真正启动安装的 `@deepseek-ai/dsh-*` 0.2.1-alpha.1，运行 Gateway、WebServer、workspace/session controller，拦截非 loopback fetch 且移除模型凭据，没有发出模型请求。`../Temp_Android/qa/remote-web-dsh/result.json` 记录 11 项通过：真实 HTML/bootstrap、7 个 JS/CSS/Modules 资源、实际模型目录、电脑目录、工作区 baseline、会话创建、真实浏览器 SessionWireEvent 校验，以及合成持久正文/公开思考/usage 和 direct-child descriptor。它只证明真实 DSH 边界，明确记录 `aries:false, android:false, enabled:false`。

实际源码差异已接入：Modules 的 `/plugins/??...&rev=<12 hex>` 组合地址约 3 KiB，统一上限 4096；应用组合 JS 的 identity 大于 8 MiB，采用 gzip、有界解压后审查认证数据并保持 gzip 传输；私有事件替换为带原序号的 `dafeiyu/hidden` ignorable 事件，防止空 `request/header` 被浏览器 validator 拒绝。真实 Windows DSH 选择了 native directory picker，原 `.list` 仅存在不代表能浏览；在独立 Cordis isolate 中加载已安装、精确版本的 `@deepseek-ai/dsh-host-directory-picker-browse` 0.2.1-alpha.1，保持 PC 原生 chooser。

网页没有会话 query/hash 深链接。实际选择存储是 `dsh-client-ui-workspace` 的 `dsh.sessions.current` localStorage 值 `{sessionId:...}`；`dsh-client-store` 直接读写 JSON。远程网页通过独立只读 Cordis 通知流提供 `$events`，不接管 PC operator 审批/认证 waterfall，拒绝 `$events/result`。

Android 新增 application-owned `ConnectPluginHost` 与私有 stdin/stdout worker，复用同一冻结包的 `createPlugin`、`AndroidBridgeTransport` 和 `IpcConnectionProvider`。不复制 Connect 实现，不下载未经审查脚本；Node 只绑定 app 自己启动的 PRoot→真实 Node 父链、UID、executable、start ticks，凭据只进入私有 stdin，remote scope 在同次 native serialized admission 再复核。Node 亲自核对加载的 package version、协议和 archive SHA/字节，调用真实 native Provider 成功后才发布 RUNNING；包准备成功仍不等于宿主运行成功。在线工作区为元数据，自动 workspace file sync/broadcast 保持关闭，产物不会被自动下载。

本次 Node 标准库研究通过 7890 读取 GitHub `nodejs/node` 固定 v22.6.0 的 README、LICENSE、`lib/async_hooks.js`、`lib/net.js`（Node 本体 MIT；二进制保留既有第三方许可）。采用内置 AsyncLocalStorage 分隔并发请求身份和已有 net socket 取消；未复制源码。两次猜测 internal AsyncLocalStorage 路径返回 HTTP 404，Git tree API 返回代理出口 `20.46.123.214` rate limit；之后读取实际 `lib/async_hooks.js` 中实现。原文保存在 `../Temp_Android/qa/connect-host-research/`。

LocalPluginIpcPolicy 27/27 离线检查已通过，新增 PRoot launcher 单次绑定和 remote scope 类型/拷贝校验；bridge 的 Android API compile-only 和 Host/provider 的严格 Werror 编译通过。真实 Node/Android socket 验证用 `ConnectPluginHostSmoke` instrumentation，构建安装和设备实测待执行；没有把 JVM 检查计为设备通过。PC/Android 长期事件日志容量和远程归档/删除/用户产物下载尚需专项实现及验收，不能凭网页专项推断已接通。
