# 移动端 Aries 配对协议审计

日期：2026-10-11（Asia/Shanghai）

本文是对移动端 Aries 接入现状的只读审计，依据 PC/服务端协议文档和当前移动端源代码整理。本文不修改 Kotlin 实现，也不把尚未在 `API-REFERENCE.md` 中定义的二维码格式当作服务端正式契约。

## 审计范围

对照文件：

- PC/服务端协议：`Dafeiyu-Go-Aries/docs/API-REFERENCE.md`
- 移动端：`app/src/main/java/com/dafeiyu/mobile/data/AriesClient.kt`
- 移动端身份：`app/src/main/java/com/dafeiyu/mobile/data/AriesIdentityStore.kt`
- 移动端配对页：`app/src/main/java/com/dafeiyu/mobile/ui/PairingScreen.kt`
- 移动端在线页：`app/src/main/java/com/dafeiyu/mobile/ui/SettingsScreens.kt` 中的 `OnlineContent`
- 移动端在线统计客户端：`app/src/main/java/com/dafeiyu/mobile/data/AriesPresenceClient.kt`
- 移动端设置契约：`app/src/main/java/com/dafeiyu/mobile/data/SettingsContract.kt`

`OnlineContent` 不是独立文件，而是 `SettingsScreens.kt` 第 883 行附近的私有 Composable。`AriesPresenceClient` 使用的是 DeveloperCenter 的另一套匿名安装心跳/在线人数接口，不能视为 Aries 公共 API 的设备心跳或群组 presence。

## PC Aries 正式协议

### 服务地址和路径

`API-REFERENCE.md` 第 5–19 行规定：

- 健康检查是公开的 `GET /healthz`。
- 设备、群组、消息和文件客户端使用 `/api/v1/public/...`。
- 管理 API 使用 `/api/v1/admin/...`，移动端不应使用管理 token 或管理路径。
- 非 loopback 连接必须使用 HTTPS；只有服务端开启 `allow_insecure_loopback` 时，loopback 才可能接受明文 HTTP。
- JSON 请求和响应使用 UTF-8，请求体上限为 4 MiB；未知字段和尾随第二个 JSON 值会被拒绝。

设备客户端应使用完整 `node_id` 识别节点。`server_code` 只是供人阅读的短码，可能碰撞，不能替代 `node_id`。

### 注册设备的字段和顺序

正式顺序如下，不能跳过挑战，也不能用设备 ID 代替本地签名身份。

1. **本地生成身份**

   客户端生成 Ed25519 密钥对。私钥只能留在客户端。公钥必须是原始 32 字节，以无填充 base64url 编码。

2. **申请新设备挑战**

   ```http
   POST /api/v1/public/devices/challenge
   Content-Type: application/json
   ```

   新设备请求体：

   ```json
   {"public_key":"<无填充 base64url 的 32 字节 Ed25519 公钥>"}
   ```

   已登记设备也可以请求绑定设备挑战：

   ```json
   {"device_id":"dev_..."}
   ```

   两种字段二选一。响应至少包含：

   ```json
   {
     "challenge_id":"ch_...",
     "nonce":"<32 字节随机值的无填充 base64url>",
     "expires_at":"<RFC3339 时间戳>"
   }
   ```

   挑战有效期为两分钟。挑战申请有来源 IP 限流。

3. **生成注册签名**

   以 UTF-8 编码、使用单个 LF 连接以下四行，末尾不追加换行：

   ```text
   enroll
   <challenge_id>
   <nonce>
   <public_key>
   ```

   `nonce` 和 `public_key` 必须使用挑战请求/响应中的原始无填充 base64url 字符串。使用本地 Ed25519 私钥签名，签名结果也用无填充 base64url 编码。

4. **提交注册**

   ```http
   POST /api/v1/public/devices/enroll
   Idempotency-Key: <8–128 个字符>
   Content-Type: application/json
   ```

   请求体：

   ```json
   {
     "challenge_id":"ch_...",
     "signature":"<注册签名的无填充 base64url>"
   }
   ```

   首次注册返回 `201 Created`；相同设备再次注册可返回 `200`。响应包含：

   ```json
   {
     "device_id":"dev_<公钥 SHA-256 前 16 字节的小写十六进制>",
     "status":"registered"
   }
   ```

   注册的 `Idempotency-Key` 只用于这一次业务操作。网络超时后必须使用完全相同的 key 和完全相同的 body 重试。

### 已登记设备请求签名

除公开路径外，需要设备认证的请求（包括 GET）必须发送：

```text
X-Device-ID: dev_<server-issued-id>
X-Device-Timestamp: <RFC3339 时间戳>
X-Device-Nonce: <本请求唯一、12–128 字符的随机值>
X-Device-Signature: <Ed25519 签名，无填充 base64url>
```

设备请求签名原文是以下六行以单个 LF 连接后的 UTF-8 字节，不含最后换行：

```text
<HTTP method>
<原始 request URI，包含 query string>
<X-Device-Timestamp 的原值>
<X-Device-Nonce 的原值>
<Idempotency-Key 的原值；非幂等请求为空行>
<base64url(SHA-256(原始请求体字节))>
```

签名必须覆盖实际发送的 body 字节，而不是解析 JSON 后重新序列化的结果。时间戳与服务器最多相差五分钟；nonce 只能使用一次。正常的持久化写操作要求幂等 key；心跳接口是文档明确列出的例外。

### 配对后入群顺序

API 文档没有定义一个独立的“六位配对码注册 API”。正式的邀请入群流程是：

1. 已登记设备读取 `GET /api/v1/public/invites/{code}`。
2. 该查询是公开 GET，响应包含：

   ```json
   {
     "group_id":"...",
     "group_name":"...",
     "authority_node":"...",
     "member_count":0,
     "approval_policy":"...",
     "expires_at":"..."
   }
   ```

3. 已登记设备提交：

   ```http
   POST /api/v1/public/invites/{code}/applications
   ```

   body 可为空或 `{}`，必须带设备请求签名和 `Idempotency-Key`。响应包含 `application_id`、`group_id`、`status`、`required_approvals`。

4. 审批、加密密钥信封投递和最终成员建立由群组成员/服务端完成：

   - 申请状态通常为 `pending_approval`、`awaiting_key`、`approved` 或 `rejected`。
   - 活跃成员通过 `POST /groups/{group}/applications/{application}/approvals` 审批。
   - 活跃成员通过 `POST /groups/{group}/applications/{application}/key-delivery` 投递 opaque 加密信封。
   - 新成员随后读取 `GET /groups/{group}/key-deliveries` 并确认投递。

移动端当前只实现了前两个客户端可直接调用的邀请步骤中的 `lookupInvite` 和 `applyInvite`，尚未实现审批、密钥投递、密钥确认、成员状态和群组事件处理。

### 设备心跳和群组 presence

正式 Aries 设备心跳是：

```http
POST /api/v1/public/devices/heartbeat
```

body 可为空或：

```json
{"client_version":"launcher/1.0"}
```

它需要设备请求签名，但文档明确说明不要求幂等 key。响应包括 `device_id`、`online:true`、服务端生成的 `last_seen_at`、`expires_at`，以及可选的 `client_version`。

群组在线状态另由已登记且有成员资格的设备读取：

```http
GET /api/v1/public/groups/{group}/presence
```

这与移动端 `AriesPresenceClient` 使用的以下接口不是同一协议：

```text
GET  /api/presence
POST /api/installations/heartbeat
```

后两者连接 DeveloperCenter，使用随机 installation UUID 和 launcher 版本，不使用 Aries Ed25519 设备身份。

## 移动端当前实现

### `AriesIdentityStore`

当前实现基本符合 Aries 身份边界：

- 使用 Android `KeyPairGenerator("Ed25519")` 生成本地密钥对。
- 提取 32 字节原始公钥并以无填充 base64url 保存。
- 将 PKCS#8 私钥序列化后用 Android Keystore 中的 AES/GCM 密钥加密。
- 私钥密文和 IV 放在应用私有 SharedPreferences；AES 密钥留在 `AndroidKeyStore`。
- 使用 `Signature("Ed25519")` 对调用方提供的原文签名。
- 只保存服务端返回的 `device_id`，并校验为 `dev_` 加 32 位小写十六进制。
- 私钥解密失败时拒绝静默生成替代身份，这一点符合“设备身份不能因注册状态丢失而改变”的要求。

需要后续实机验证的工程风险：当前直接依赖 Android provider 对 Ed25519 的支持；代码把 provider 不可用作为错误返回，没有兼容实现。该风险不是 API 字段不一致，但会影响低版本设备能否完成注册。

### `AriesClient`

| 能力 | 当前实现 | 与 API-REFERENCE 对照 |
|---|---|---|
| `healthz` | `GET /healthz`，解析 `api_version`、`build_version`、`status`、`started_at` | 路径和字段一致 |
| `node` | `GET /api/v1/public/node`，解析 `node_id`、`server_code`、`api_version` | 路径和字段一致 |
| 新设备挑战 | 无 `device_id` 时发送 `{"public_key":...}` | 字段和顺序一致 |
| 已登记设备挑战 | 有本地 `device_id` 时发送 `{"device_id":...}` | 字段一致；仍不能替代后续每次请求签名 |
| 注册签名 | `enroll\nchallenge_id\nnonce\npublic_key`，Ed25519 + 无填充 base64url | 原文格式一致 |
| 注册提交 | `POST /api/v1/public/devices/enroll`，body 为 `challenge_id` 和 `signature`，带幂等 key | 路径、字段和幂等要求一致 |
| 设备请求签名 | 设置四个 `X-Device-*` 头；按 method、raw URI、timestamp、nonce、key、body hash 六行签名 | 规范顺序一致 |
| 请求 nonce | 生成 18 字节随机值并无填充 base64url 编码 | 长度约 24 字符，满足 12–128 字符要求 |
| 请求时间 | `DateTimeFormatter.ISO_INSTANT` | RFC3339 兼容 |
| body hash | 对实际发送的 UTF-8 byte array 做 SHA-256，再无填充 base64url | 与规范一致 |
| 设备心跳 | `POST /api/v1/public/devices/heartbeat`，可发送 `client_version`，签名但不传幂等 key | 与明确的心跳例外一致 |
| 邀请查询 | `GET /api/v1/public/invites/{code}` | 路径和响应字段一致 |
| 入群申请 | `POST /api/v1/public/invites/{code}/applications`，发送 `{}`，签名并带幂等 key | 路径、body 和认证要求一致 |
| HTTP 错误 | 解析 `error.code` 和 `error.message` | 基本兼容，但未保留 `request_id` |
| TLS | 仅建立 `HttpsURLConnection`，使用 APK 内置 `dafeiyu_download_ca`，平台 hostname verifier | HTTPS 和 hostname 校验方向正确，但部署范围比 API 文档更窄 |

`inspect()` 的行为是先读取 healthz/node；如果已经有本地 device ID，再发送一次设备心跳。它把心跳成功映射为 `REGISTERED`，没有 device ID 映射为 `UNREGISTERED`。

### `PairingScreen`

当前配对页已经接通一段真实的 Aries 客户端流程：

- 输入可以是普通 invite code，也可以是移动端解析器接受的 JSON/URI 配对内容；`pairingCandidate()` 对普通 code 允许 1–128 个非控制字符。
- 点击“验证配对信息”后，页面使用解析出的 endpoint 调用 `/node` 和公开的 `GET /invites/{code}`，展示 server code、group 和邀请结果。
- 用户确认后，如果本地没有 `device_id`，页面按 `requestChallenge(deviceId = null) → enroll()` 注册设备；已有本地设备则跳过注册。
- 注册完成后调用 `applyInvite()`，提交带设备签名和幂等 key 的入组申请，并展示申请状态。
- 页面有验证中、注册中、提交申请中、成功和错误状态，也会把扫码内容带回输入框。

当前仍未完成的后续流程包括：服务端审批状态刷新、密钥信封投递读取与确认、群组成员/事件同步、入组后设备心跳和 presence 接线。页面还没有核对二维码中的 node ID 与 `/node` 实际返回值，也没有持久化本次 endpoint、node ID、group ID 和申请状态到设置仓库。
### `OnlineContent`

`SettingsScreens.kt` 中的 `OnlineContent` 当前做了以下事情：

- 使用无参数 `AriesClient.Config()`，即使用 `AriesClient` 内置默认 endpoint。
- 点击检查时只调用 `healthz()` 和 `node()`。
- 页面明确提示“设备注册仍未执行”，没有调用 `inspect()`、`requestChallenge()` 或 `enroll()`。
- 设备卡片和电脑卡片仍是本地预览/待配对状态。
- “配对 / 端口”打开的是二维码占位弹窗，显示固定示例服务器码，不定位服务器、不建立连接。
- 该页面没有读取 `SettingsContract.AriesConnectionSettings.endpoint`，也没有把扫码得到的 endpoint 或 node ID 写回设置。

## 与 API 文档不一致或需要主线程修复的地方

下面区分“协议不一致”和“实现缺口”。前两项是会直接阻断真实配对的高优先级问题。

### 1. UI 文案仍把配对码称为“6 位”，但正式 API 没有六位约束

`PairingScreen.kt` 的输入标签仍显示“6 位配对码或二维码内容”，但当前 `pairingCandidate()` 实际允许 1–128 个非控制字符，`AriesClient.requireInviteCode()` 也允许 1–128 个非控制字符。`API-REFERENCE.md` 第 148–149 行只把 `{code}` 定义为 invite code，没有规定它必须是六位数字。

当前实现不会因为这个标签直接拒绝字母或短横线邀请码，但文案会让用户和 PC 端误以为协议要求六位数字。应改成“配对码或二维码内容”，或者把六位短码定义为另一个有正式文档的转换层。
### 2. PairingScreen 已执行注册/申请，但仍没有完成入组闭环

当前页面已经执行：

```text
验证 node + lookupInvite → requestChallenge → enroll → applyInvite
```

这与 API 文档的设备注册和入组申请顺序相符。缺口在于申请提交后的服务端审批、密钥信封投递/确认、成员状态读取、群组事件同步和入组后 presence 尚未接入，因此“申请已提交”不能被 UI 当作“设备已连接”或“已成为群组成员”。
### 3. 二维码/URI 字段属于移动端自定义格式，API-REFERENCE 没有定义

`AriesClient.parsePairingPayload()` 接受以下未在 `API-REFERENCE.md` 中定义的约定：

- JSON：`endpoint` 或 `server`、可选 `code`、`group`、`node_id`。
- URI：`aries://...` 或 HTTPS URL，query 中可放 `code`、`group`、`node_id`。
- `aries://` 未提供端口时默认使用 `10240`。

当前 PC/服务端 API 文档只定义 HTTP 路由和 invite code，没有定义二维码序列化、URI scheme、字段命名、默认端口或 node ID 校验规则。移动端可以暂时保留解析器，但它只能被标记为“客户端预览扩展格式”；若 PC 端要生成此二维码，必须把格式补充进正式协议，并增加兼容/版本字段。

### 4. 解析出的 node_id 没有和 `/node` 返回值核对

移动端会把二维码中的 `node_id` 解析到 `PairingPayload`，但没有在连接后比较实际 `/api/v1/public/node` 的 `node_id`。API 文档明确要求使用完整 `node_id` 识别节点，并说明 `server_code` 可能碰撞。

主线程接入时应在接受二维码后读取 `/node`，核对 endpoint 对应的完整 node ID；不一致时应拒绝继续注册或至少要求用户重新确认。显示层也应优先显示完整 node ID，不能只把 `server_code` 当节点身份。

### 5. `OnlineContent` 使用固定默认 endpoint，没有接入已保存或扫码 endpoint

`OnlineContent` 通过 `AriesClient.Config()` 使用客户端默认地址。`SettingsContract` 已经定义了 `AriesConnectionSettings.endpoint`，但该页面没有读取它，也没有使用 `parsePairingPayload()` 的 endpoint。

这会导致用户配置或扫描了不同 Aries 节点后，页面“检查连接”仍检查内置默认地址。需要把 endpoint 的来源统一为设置/配对结果，并同步保存实际 `node_id` 与检查时间。

### 6. 移动端永久拒绝 API 文档允许的 loopback HTTP

`API-REFERENCE.md` 第 19 行允许服务端在 `allow_insecure_loopback` 开启时接受 loopback 明文 HTTP；`SettingsContract.normalizeAriesEndpoint()` 第 42–50 行和 `AriesClient.request()` 只接受 HTTPS，并且强制转换为 `HttpsURLConnection`。

这是移动端刻意收紧的安全策略，远程生产连接没有问题，但它与服务端“可选 loopback HTTP 开发模式”不完全一致。若移动端需要连接同机/局域网开发服务，必须明确支持范围；否则应在 UI/文档中说明移动端只支持 HTTPS，不要把 HTTP endpoint 显示为普通配置错误后让用户反复尝试。

### 7. TLS 信任范围比 API 文档规定的部署方式更窄

API 文档规定远程连接使用 HTTPS 反向代理，但没有要求所有部署都使用移动端 APK 内置的 `dafeiyu_download_ca`。`AriesClient` 自建只包含该 CA 的 trust store，因此使用其他合法公网 CA 或另一套受管私有 CA 的 Aries endpoint 会失败。

这不是签名字段错误，而是部署契约不完整：如果产品只允许这一套 CA，应在 PC/移动端接入说明中固定 CA 指纹和轮换策略；如果要支持任意标准 HTTPS endpoint，当前 trust store 需要重新设计。无论选择哪种方式，都要保留严格 hostname 校验。

### 8. API 的 `X-Request-ID` / error request_id 没有保留

API 文档规定每个请求返回 `X-Request-ID`，错误 JSON 也有 `error.request_id`。移动端只解析 `error.code` 和 `error.message`，`AriesException` 没有携带 request ID。

这不会阻断配对，但会明显降低服务端排障能力。真实接入时应把 request ID 放入可见错误或诊断日志，同时避免记录设备私钥、签名原文或敏感 body。

### 9. 挑战和注册响应校验仍可加强

当前实现依赖服务端返回值，但没有在客户端严格校验：

- `challenge.nonce` 是否解码后正好 32 字节、是否为无填充 base64url。
- `challenge_id` 和 `expires_at` 是否非空且在提交前仍有效。
- 注册成功的 `status` 是否严格为 `registered`。
- 返回 `device_id` 的公钥派生值是否与本地公钥一致。

服务端仍是最终权威，这些校验属于客户端防止错配和错误提示的补强项，不是当前 API 路径缺失。

### 10. 已登记设备身份没有绑定到 node 设置

`AriesIdentityStore` 只保存一个全局 `device_id`，`SettingsContract` 也只保存一个 Aries endpoint/node 状态。若用户切换到另一个 Aries 节点，当前客户端可能继续使用旧节点的 `device_id` 请求 challenge 或签名请求，随后得到未注册/不存在等错误。

主线程需要决定产品语义：每个 endpoint/node 保存独立设备注册，或切换节点时清除该节点之外的注册状态并重新走 challenge/enroll。API 文档强调 node ID 是节点身份，但移动端当前没有把它纳入设备注册状态键。

## 当前可以确认的结论

- `AriesIdentityStore` 和 `AriesClient` 的核心 Ed25519 注册、请求签名、心跳、邀请查询和入群申请字段，整体按照 `API-REFERENCE.md` 实现。
- `PairingScreen` 已把验证、设备注册和入组申请串起来，但 `OnlineContent` 仍停留在 healthz/node 健康检查和预览配对层；完整入组闭环仍未完成。
- 当前 UI 的“配对码”六位数字限制、未定义的二维码字段格式、默认 endpoint、只显示短码和未核对 node ID，是主线程修复时最需要优先处理的协议边界。
- `AriesPresenceClient` 和 Aries 设备心跳属于两套不同服务，不能用 DeveloperCenter 的在线人数结果证明 Aries 设备注册或群组 presence 已完成。


