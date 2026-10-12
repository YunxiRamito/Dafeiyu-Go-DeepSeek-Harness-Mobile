# Android 0.1.16 · 界面与后端第一批

日期：2026-10-11，Asia/Shanghai。版本 0.1.16 / code17。本记录覆盖历史材料参数、扫码专用关闭折射和官方引擎仅演示的说明。

## 交接状态

本节是 0.1.16 的功能边界摘要，接手时优先于旧版本材料和历史批次描述。

### 已接入

- ✅ 液态玻璃主页、侧栏、设置过渡、快捷菜单和输入区：QWEA0 AGSL/RenderEffect、窄边折射、色散、方向高光、均匀低比例覆盖色和跨窗口背景来源已接入；没有恢复宽内发光。
- ✅ 右上加号旋转 X、向左下展开，顺序为新建会话/扫码/配对码；配对码按当前 endpoint/node/group/device 的签名成员证据隐藏。
- ✅ DeepSeek 官方鲸鱼路径、工作区/会话本地保存、模型首回执后标题生成、草稿按会话保留。
- ✅ 官方轻引擎 `/models`、`/chat/completions` 文本链路，流式/非流式、公开思考、用量、停止、取消和错误状态。
- ✅ 思考、正文、命令/工具/子 Agent/搜索/引用活动的折叠状态模型和快照保存；官方引擎设置中显示思考链与默认展开已开启。协议层只保存工具提案，不执行工具。
- ✅ Keystore AES/GCM、系统 IV、AtomicFile、Aries 身份和在线服务基础协议；DeveloperCenter presence、匿名 heartbeat、服务器 metrics 和双设备在线人数数据层。
- ✅ 设置偏好、权限入口、通知/个性化/模型/插件/技能/虚拟机/组件/缓存/备份/更新/反馈/关于页面及相应主要数据层。
- ✅ CameraX/ZXing 扫码、相册/文件选择器和相机缩略图玻璃背景；二维码只展示，不自动执行链接。

### 已实现但待验收

- 🟡 真实 DeepSeek 账号的模型兼容性、余额和长时间流式输出；本轮未读取 Key，也未发起付费调用。
- 🟡 输入法打开时的持续流式布局、菜单反复展开关闭、设置收回、跨窗口玻璃采样、浅深色对比度和页面滚动手感。
- 🟡 扫码成功率、暗色视觉、灯光/相册识别、相机长时间稳定性和实际显示帧率。
- 🟡 Aries 真实 PC/手机审批、成员状态、密钥投递/确认、双设备传输和服务器数据持续刷新。`AriesConnectionProvider`、`LocalPluginBridge` 和 `ConnectionPluginPackage` 已有基础代码，但尚未发现主应用调用桥接入口；复用前需要审查、接线和端到端验证。
- 🟡 模拟器已验证安装、冷启动、主页显示和应用日志；实物相机、触感、真实手机性能和厂商输入法仍需真机验证。

### 尚未接入

- ⬜ DSH 模型目录、凭据、排序同步、RPC、本地运行环境和真实模型执行。
- ⬜ 运行命令、子 Agent 调度、搜索执行器和工具执行权限。
- ⬜ 附件编码及向模型传输。
- ⬜ Aries 审批、密钥投递/确认、双设备传输/工作区同步、网页广播和群组缓存清理的完整用户流程；连接 provider 的相关方法已有代码基础，尚未接通应用并完成验收。
- ⬜ Android APK 更新 manifest、下载、SHA-256/签名校验和系统安装确认。
- ⬜ 插件/技能真实下载、安装、更新、卸载和受限执行的完整用户流程；现有页面以私有目录扫描为主，另有连接插件包验证与本地桥基础代码。
- ⬜ 主动公告/连接设备通知和 Live Notification；当前只有通知渠道状态和设置入口。

### 交付证据

- APK：`G:\DeepSeek DSH\DSH Works\Project\Dafeiyu-Go\Temp_Android\Dist\Dafeiyu-Go-Mobile-0.1.16.apk`
- `versionCode 17`，包名 `com.dafeiyu.mobile.preview`，minSdk 30，targetSdk 35，SHA-256 `df6c2cd4c707c676e1819b773039cd9e3566d24d0361fec996ef7b62dd517353`。
- `assembleDebug`、`lintDebug` 通过；Lint 0 errors、133 warnings、5 information。
- 离线测试：assistant stream 21、workspace presence 31、DeepSeek protocol 36、Aries protocol 32、Aries identity 11，以及成员/群组 fixture 测试通过。
- 使用 `G:\Android SDK\platform-tools\adb.exe` 安装到 `Medium_Phone_API_37.0`，本次设备号 `emulator-5554`。模拟器使用 NVIDIA RTX 3070 Laptop GPU 和 WHPX；冷启动、主页显示、AGSL 编译和应用日志通过，最近 300 条应用日志无 FATAL EXCEPTION 或 ANR。
- 主页截图：`G:\DeepSeek DSH\DSH Works\Project\Dafeiyu-Go\Temp_Android\qa\emulator-0.1.16\home.png`。该截图不作为键盘流式、菜单、扫码或真机验收证据。

## 界面

参考 QWEA0/Liquid-Glass-Android 固定提交 `16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d` 的 Regular/Clear 材料：保留既有窄边折射、色散和方向高光，把覆盖色改为均匀低比例，不恢复内发光。当前 Standard/FloatingMenu 3.5dp 模糊、tint 上限 16%；Button 2dp、上限 12%/10%；Sidebar 3dp、上限 12%/14%；MediaButton 2dp、上限 14% 和一次 16% 背景压暗。调用者可传入更低 alpha；减少透明度继续提供实色降级。

右上加号使用同一动画进度旋转 45 度成为 X，来源按钮固定，菜单从其下方向左下展开。顺序为新建会话、扫码、配对码，操作延迟到收起完成后执行。只有当前 endpoint/node/group/device 的签名成员证据才隐藏配对码；离线或身份范围改变不会沿用旧“已配对”状态。

DeepSeek 标识来自 `deepseek-ai/DeepSeek-LLM/images/logo.svg` 的鲸鱼 path，Git blob `425494404f7e63f4909667003097511217664579`，56.24 x 41.36 viewport；未重新描绘。主页和助手使用该标识，启动器和关于页保留应用图标。

扫码按钮复用原生折射链，背景为分析线程产生的相机缩略图；文字不进入自身采样源。CameraX 请求帧率不等于实测显示帧率。扫码成功率、相机性能和暗色视觉仍待真机验收。

## 后端

设置中显式保存 DeepSeek API Key、获取 `/models` 后选择返回的真实 ID，再发送 `/chat/completions` 文本。没有自动请求模型或固定模型 ID 回退。目录元数据和选中 ID 本地缓存；Key 变更/清除使目录失效。只对官方文档确认支持二元思考开关的 ID 展示选项；未知/旧模型不发送 thinking 覆盖参数，遵循服务端默认，不能从模型目录成员身份推导能力。

凭据在不参与备份的私有目录中，经 Keystore AES/GCM 加密并用 AtomicFile 保存。加密 IV 由系统生成，兼容已有 v1 文件；解密不会创建丢失的密钥，多实例共用存储锁。

Aries 身份存储同样改用 Keystore 生成 IV、跨实例锁；已有身份损坏或密钥丢失时保留数据并报错，不生成替代身份。设备 enroll 的 unsigned POST 可携带经过校验的 Idempotency-Key，其他 unsigned 请求仍不允许；扫码接受 PC 使用的 `invite_code` 与原有 `code`，冲突字段拒绝。响应读取有字节上限，取消异常向上传播。

传输只使用固定官方 HTTPS，不跟随重定向、不自动重试、不记录 Key/提示词/服务端错误正文。SSE 支持 UTF-8 分片、CR/LF/CRLF、多行 data、注释、BOM、终止标记，并限制请求、行、事件和累计大小。缺少 finish_reason 的 EOF 标为未完成。

公开 `reasoning_content` 与正文分块存储，可在官方引擎设置中显示/默认展开。加密或隐藏思考字段不展示。停止请求立即断开连接；晚到回调有生成序号校验。模型返回工具调用仅作为 READY 提案保存，没有声明工具能力或执行命令。

上下文只发送完整、成功的 BACKEND 正文对以及当前用户输入。思考、演示、失败、取消、截断、含工具的回复均不进入下一轮；不静默截断超限历史。附件尚未接传输，拒绝发送时保留草稿并提示。

DSH 模式仍是明确的本地演示。本批没有完成工具执行、子 Agent 调度、搜索执行、Aries 审批/密钥投递的应用接线、同步、Android 更新或插件安装的端到端流程；连接 provider、加密传输与插件桥已有基础代码，具体边界见本文顶部。

## 验证

- `test-assistant-stream.ps1`：21 项通过。
- `test-workspace-presence.ps1`：31 项通过。
- `test-deepseek-chat.ps1`：36 项通过；包含取消阻塞读、HTTP/格式错误、截断、用量、公开思考、工具提案、目录、上下文过滤和可空思考参数策略。
- `test-aries-membership.ps1`：17 项成员判定、9 项群组传输 fixture、9 项 Keystore/AtomicFile fixture 通过；另通过 BalanceStore Android API 编译。
- `test-aries-protocol.ps1`：32 项协议策略与扫码输入测试通过。
- `test-aries-identity.ps1`：11 项身份存储、缺失密钥、并发实例与实际 JVM 加密/签名测试通过；不是 Android Keystore 真机测试。
- 上述是离线 fixture 验证；未读取用户 API Key，也没有发起真实付费生成。
- 最终构建、签名、APK 指纹、ADB 版本和日志结果以 `frontend-build.json` 为准。

`Temp_Android/qa/phone-0.1.16/home.png` 是本轮前端中途包的已展开快捷菜单截图，可以证明顺序与 X；不能证明最终后端包、键盘行为或扫码效果。此前 `home-closed.png` 实际为设置页，不能用作主页证据。

最终 APK：`Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.16.apk`。构建日志：`Temp_Android/build-0.1.16-final.log`。

2026-10-11 用户要求后续默认使用 Android Studio 模拟器调试。已用 `G:\Android SDK\platform-tools\adb.exe` 在 `Medium_Phone_API_37.0`（本次编号 `emulator-5554`）安装最终 APK，核对 0.1.16/code17、min30/target35、x86_64 并成功启动。AVD 保存 host GPU 渲染，启动日志和 SurfaceFlinger 确认 NVIDIA RTX 3070 Laptop GPU；WHPX 可用，桌面窗口已调整至屏幕工作区内。

按应用 PID 采样的最近 300 条日志未发现 FATAL EXCEPTION 或 ANR，AGSL lens 编译成功。最终包主页截图为 `Temp_Android/qa/emulator-0.1.16/home.png`，已实际检查图像；这张图只证明主页显示，不证明菜单、键盘流式、扫码或真机性能。默认调试路径、用户启动方法与目标选择规则见 `HANDOVER.md` 顶部。
