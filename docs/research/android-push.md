# Android push registration and server transports

Research date: **2026-10-13**, Asia/Shanghai. All GitHub searches, README, source and license retrieval used `http://127.0.0.1:7890`. Raw research artifacts are under `../Temp_Android/push-research-20261013/`; repository examples and scripts were not executed.

## Reviewed implementations

| Repository / fixed reference | License | Examined source and decision |
|---|---|---|
| `firebase/firebase-admin-dotnet`, v3.7.0, `62553e0f375e77f40f479b7a3a0bbc50808f9216` | Apache-2.0 | README, license, `FirebaseMessagingClient`, `AndroidConfig`, project dependencies. Confirms FCM HTTP v1 `message` envelope, Android TTL and the distinction between platform acceptance and delivery. No Firebase Admin source or dependency copied. |
| `googleapis/google-api-dotnet-client`, v1.73.0, `6386ed813d1de3556ac5a4f211160b3765ba1c10` | Apache-2.0 | README, license, `ServiceAccountCredential`, `GoogleCredential`. Reuse **Google.Apis.Auth 1.73.0** for service-account OAuth; the server binds its own reviewed fields and fixed Google token endpoint rather than accepting arbitrary credential types or writing JWT refresh logic. |
| `HMS-Core/hms-push-serverdemo-csharp`, `dce2c626379648c5ecb9baea393329667463f151` | Apache-2.0 | README, license, `AGConnectMessagingClient`, `AppOptions`. Verifies Huawei OAuth form, fixed token/send endpoints, `80000000` success and request ID. Protocol only; no demo source copied. |
| `meetleev/go-multipush`, `4a4e16c94d2083e06e8da18595b167d4f6f07c06` | Apache-2.0 | README, license, MiPush/OPPO/vivo request and response types, authentication and HTTP helpers. Useful protocol fields and official links; rejected as a runtime dependency due to secret logging, unbounded HTTP, OPPO expiry comparison and unchecked vivo business failures. |
| `commonprogress/PushAll`, `18f2ed4071a62a93a3d48412bec4ebcb44fccc75` | No independent license found | Inspected tree/README: old Android example bundle with binary artifacts. Not copied, run or redistributed. |

The initial Huawei guessed paths `MessagingClient.cs`, `Auth/Credential.cs` and `Admin/AGConnectAdmin/AppOptions.cs` returned HTTP 404. The repository tree supplied the actual `src/AGConnectAdmin/AppOptions.cs` and `AGConnectMessagingClient.cs`, which were retrieved and read. These are path errors, not evidence that GitHub or the proxy was unavailable.

License and source records are retained in `third_party/android-push/`.

## Implemented registration boundary

Installation identity is a separate Android Keystore **P-256** private key. Its identifier is the lowercase SHA-256 of DER SPKI. `.dym`, Aries identity and existing anonymous installation UUIDs do not export or authorize this key. This is key possession, **not application attestation** or a claim that the token was issued to an unmodified app.

The signed envelope uses exact camelCase fields: `installationKey`, `publicKey`, `timestamp`, `nonce`, `payload`, `signature`. SPKI, random 16-byte nonce, exact UTF-8 JSON payload and DER ECDSA signature use canonical unpadded base64url. Requests sign this UTF-8 content, including literal line breaks:

```text
dafeiyu-go.android-push.v1
POST
/api/android/push/{operation}
{SHA-256 SPKI hex}
{Unix timestamp decimal seconds}
{nonce base64url}
{SHA-256 exact payload hex}
```

Operations are `register`, `preferences`, `unregister`, `state`. Clock window is ±180 seconds. Nonces persist and reject replay; state queries also require fresh proof. Writes use `expectedRevision`. A lost response is recovered with a new nonce and a state query, not by replaying the old registration. Duplicate JSON names, unknown payload fields, invalid Unicode token bytes, oversized bodies and wrong curves are rejected. POST routes use the actual `android-push` limiter; registration additionally limits active per-identity nonce records.

`register` sends `expectedRevision`, `channel`, SDK `token`, formal `packageName` (`com.dafeiyu.mobile`) and six booleans in `preferences`: `enabled`, `live`, `devices`, `announcements`, `messages`, `backend`. Preference updates carry revision and preferences. Unregister carries only revision. State payload is `{}`. Responses never contain a token or encrypted token.

Token ownership has a unique `(channel, tokenHash)` binding. Token content is AES-GCM encrypted, with installation/channel bound as associated data. An explicitly provisioned persistent 32-byte server key is required via `AndroidPush:TokenKeyFile`; missing key or accounts leave capability channels empty. No temporary in-memory key or manufactured SDK token can make configuration appear usable.

Android `PushRegistrationClient` uses the existing fixed DeveloperCenter HTTPS host and SPKI pin, rejects redirects, bounds requests/responses and supports cancellation. Registration success must match the submitted channel, preferences and revision increment. SDK selection never substitutes a different explicitly chosen channel. This build has **no configured FCM/OEM SDK channels** and does not register for push.

## Platform adapters and durable events

| Channel | Fixed service | Auth / successful business response |
|---|---|---|
| FCM | `https://fcm.googleapis.com/v1/projects/{projectId}/messages:send` | Reviewed service-account OAuth through Google.Apis.Auth; correct `projects/{projectId}/messages/` response name. `UNREGISTERED` revokes the token. |
| Huawei | `https://oauth-login.cloud.huawei.com/oauth2/v3/token`; `https://push-api.cloud.huawei.com/v1/{appId}/messages:send` | `client_credentials` OAuth; code `80000000` and nonempty request ID. |
| MiPush | `https://api.xmpush.xiaomi.com/v3/message/regid` | Header app secret, URL-encoded registration/package/payload fields; code `0`. Uses pass-through data, subject to future account acceptance and client SDK integration. |
| OPPO | `https://api.push.oppomobile.com/server/v1/auth`; `/server/v1/message/notification/unicast` | Reviewed SHA-256 auth form; code `0`, token and send message ID. Requires console-approved notification channel/category configuration. |
| vivo | `https://api-push.vivo.com.cn/message/auth`; `/message/send` | Protocol-required MD5 auth signature, not identity encryption; result `0`, task ID and no invalid recipient. Explicit invalid token is classified. Requires console-approved notification category/channel. |

All HTTP destinations are fixed. Production adapters have no arbitrary URL override or redirect; request timeout and response body limits apply. Errors contain safe classification codes, not platform bodies or authentication headers. Empty/incomplete credentials and missing success fields remain failures.

Four persistent tables store installation registrations, nonces, stable events and per-installation deliveries. Dispatch claims a limited queue with database compare-and-set leases, rechecks current registration revision/classification/expiry and source notice visibility, applies bounded retry and preserves unfinished work across dispatcher recreation. Successful platform submission is **`accepted`**, never `delivered`.

The server polls persisted **Android** publication records. Stable event IDs are `notice-{message UUID}`. Publication must occur after the recipient registration; old historical feed content and expired/withdrawn messages do not become new push notifications. There is no anonymous arbitrary-target send endpoint. Task/device/backend enqueue is an internal contract for trusted server workflows; associating Aries authority with a push identity is **not yet implemented**, so no public client is allowed to invent that entitlement.

SSE and local task notifications remain separate. OEM SDK token callbacks, durable receipt deduplication across SSE/SDK events, real factory/FCM background receipt and console category approval require the actual registered applications. These are **pending**, not validated by the server protocol adapters.

## Evidence

- `../Temp_Android/qa-push-server-build-r2.log`: actual DeveloperCenter service strict build, zero warnings/errors, fixed Google.Apis.Auth restored into G-drive NuGet cache. The first attempt's default HTTP cache targeted C and was denied; `NUGET_HTTP_CACHE_PATH` / `NUGET_SCRATCH` were moved to G before the successful retry.
- `../Temp_Android/qa-push-server-r4.log`: **86 PASS** with real loopback Kestrel requests, actual SQLite state and AES-GCM, registration/replay/route/curve/CAS/token ownership, classification changes, unregister, durable retry, actual additive SQLite migration preserving unrelated rows, generated PostgreSQL migration SQL, and five real loopback platform-protocol fixtures. Includes empty HTTP 401/429/503 response classification and missing/null configuration. FCM OAuth token itself is injected; these checks do not contact real push platforms.
- `../Temp_Android/qa-push-client-r4.log`: strict compilation against real Android APIs plus **36 PASS** for the production client/controller in actual coroutines. Includes exact serialization, classification/unregister receipts, fresh proof, malformed replies, missing configuration, explicit channel preservation, cancellation and response-loss recovery. Signing key is a JVM P-256 fixture; JSON/Base64 runtime is substituted. **Android Keystore/TLS and platform receipt are not claimed by this result.**
- `PushRegistrationSmokeTest`: real isolated Android Keystore/canonical verification and read-only pinned deployed capability test is written, awaiting the next APK/deployment/device queue. It emits no vendor send or SDK registration; its public token-free proof can additionally be validated by production .NET.

No production message, feedback, push registration or platform-send write request was issued by these checks. Platform accounts/credentials and matching devices remain required for real offline delivery acceptance.
