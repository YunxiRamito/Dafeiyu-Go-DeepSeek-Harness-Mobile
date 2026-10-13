# 远程产物的显式下载

2026-10-13，Asia/Shanghai。经本机 `http://127.0.0.1:7890` 读取 `pillarjs/send` 固定 tag `1.2.0` / commit `7c92a68b67600992d877cf1869d171d9fb3a033f` 的 README、MIT LICENSE 与 index.js，三份原文保存在 `../Temp_Android/qa/remote-artifact-research/`。审查了单范围的起止语义、Content-Range、If-Match/If-Range、HEAD、流结束取消，以及 dotfiles/根目录边界。没有复制源码或引入依赖，没有运行上游脚本。第一次请求 `jshttp/send` 返回 Repository not found；真正仓库为 pillarjs/send，版本标签没有 v 前缀。

复用现有 Connect 的加密请求、身份作用域和取消语义，以及 Android 的 ArtifactDownloader。PC 模块只读已登记工作区的用户文件；不开放任意电脑路径，不启用自动文件同步。用户在目录元数据中选中文件后，服务为该关系准备有期限的只读 ticket，返回实际字节数和 SHA-256。每次有界分块读取都校验原文件身份、版本、工作区路径与调用关系；文件变化时失败，下载器不会提交混合内容。

Android 仅在用户下载期间建立绑定 127.0.0.1 的随机路径 Range 入口，将远程有界分块转换为本机 HTTP 流。4/8 线程仍由统一下载器负责；loopback 权限限定到本次端口，不能用测试专用 HTTP 开关为生产放宽外部地址。摘要校验后再次核对精确配对关系，才把文件显示给用户。取消、配对撤销、关系变化和资源关闭必须终止传输；断线保留受版本绑定的分片，恢复不能改变已选择的文件。

实现、离线检查、真实冻结包、Android、公网传输与视觉验收分别写入 HANDOVER，不以此研究记录声称下载已经通过。

2026-10-13 分块接线继续审查项目已有的 `Kotlin/kotlinx.coroutines` 固定 tag `1.9.0`。经7890读取 README、Apache-2.0 LICENSE.txt 与 `kotlinx-coroutines-core/common/src/sync/Semaphore.kt`，原文保存到 `../Temp_Android/qa/aries-bulk-research/coroutines-1.9.0/`。源码提供 FIFO、可取消 acquire 和 `withPermit` 的 finally 释放；直接复用已有固定依赖限制8个传输请求，不复制实现或增加另一套线程池。普通 Provider 请求仍走原串行锁，native 分块须有应用登记的精确请求和真实 UID/PID/进程租约，JSON 中声明作用域不能授予并发权限。

沿用统一 Connect 已有加密 `dsh_remote_ack`，新增安全分块确认的精确正文 `{protocol,request_id,target_device_id,operation,sequence}`。仅 artifact.list/read/close 与 attachment.write/status 可在消费、持久记录和本地ACK后回收；task、prepare、commit 和 settings intent 保留。PC Provider 从已认证请求记录 operation、peer、公钥、epoch 和内容密钥摘要，发送前绑定精确终态 ciphertext，待对端 ACK 及宿主的本地消费确认都完成才原子提交回收。区间压缩只覆盖真实验证的本机消息或已消费外来消息，不跨过未知/未确认消息。真实 PC crypto/DPAPI 与合成 HTTP 的专项、Host 边界、真实 Android 和公网传输分别记录，不用离线并发数代替网络验收。
