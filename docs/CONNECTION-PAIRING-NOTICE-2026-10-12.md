# 给手机端负责人的配对协议接续通知

2026-10-12，Asia/Shanghai。本文件只同步桌面端本轮实测与服务端待办，不修改或验收 Android 应用。手机端版本、安装和 DSH runtime 状态以手机负责人自己的记录为准。

PC / Android 继续共用同一份 `dafeiyu-go-connect@0.2.0`，协议 `dafeiyu-go.connection.v1`。冻结包为 `../../Dafeiyu-GO-Connect-Plugin/Temp/Runtime-20261011/package/dafeiyu-go-connect-0.2.0.tgz`，88,680 字节，SHA-256 `9DF7085CD5FA9ECF0E6F9A31AACE8D0272EF60C4A9AEF12A2EA96EFAFDF6AA1B`。本轮未更新插件版本或部署在线 feed。

## 已有服务契约

以 [Aries 配对与名称契约](../../Dafeiyu-GO-Aries/docs/LAUNCHER-PAIRING.md) 为准。`GET /api/v1/public/node` 的 capabilities 是字符串数组；包含 `device_name` 时，才在本人已签名 heartbeat 中上传可选 device_name，不修改 enroll 请求。members/applications 返回登记公钥与可选名称。名称仅展示，仍以设备 ID、公钥和指纹授权。名称缺失显示“对方尚未共享设备名称”，不猜测型号。

已配对时配对入口应显示“取消配对”；离线或 presence unknown 仍保留已确认关系。尚未配对才生成或刷新邀请；刷新生成新码不代表其他未过期码已作废。

## 尚未落地的能力

完整缺口与验收要求见 [全流程审计及 Aries 通知](../../Dafeiyu-GO-Aries/docs/LAUNCHER-CAPABILITY-AUDIT-2026-10-12.md)。以下为未来合同，不能当作当前可用接口：

- 安全原子取消配对：capability `pairing_unpair`；已签名 `POST /api/v1/public/groups/{expected_group_id}/unpair`，body `{}`、稳定 Idempotency-Key，每次重试重新生成 nonce 和签名。仅收到精确匹配当前 group_id、本人 device_id、status `unpaired` 的持久成功响应后清除旧组凭据，保留设备身份和 node pin。旧服不支持时明确提示并保留配对。
- 发起端读取对方密钥确认：当前服务端在投递时即提供成员资格，但 recipient ack 前后 sender 无法从现有接口区分确认状态。不要把 presence 在线或成员存在当作用户已确认。新增状态字段/接口及 capability 尚待 Aries 负责人确定。
- 另一端取消后重连：需明确 terminal 状态和客户端接续；不能把任意 404 当成配对解除，也不能仅在本机删除凭据冒充双方取消成功。

## 桌面本轮证据边界

本地真实 Aries + 两个桌面 Provider + 生产同用户管道 + 最终共享 tgz 的工作区/广播测试为 28 PASS，Provider 76 PASS；文件包含 700000B、多块、空文件、冲突、队列恢复及缓存物理统计。证据在 `../../Temp_SetupandLauncher/Revision180-20261012/ConnectionAudit/`。这不证明 Android Provider、实际 DSH Loader 或手机互通已通过；手机负责人按自身宿主接入后再做真机双端验收。
