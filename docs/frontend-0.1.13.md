# 0.1.13 Android 前端交付记录

日期：2026-10-11（Asia/Shanghai）。版本 **0.1.13 / versionCode 14**。本版为 Android 前端预览，未接入真实 Aries 网络、设备签名、DSH 后端或配对服务。

## 在线服务页

- `online` 路由已从设置首页接入实际二级页。
- 连接状态大卡明确显示未连接/前端预览，并保留检查连接入口。
- 双设备卡显示本机真实可读取的型号、Android/API、CPU ABI、内存、存储；GPU和网络按 Android 可用性标记未公开/未连接。电脑设备显示待配对，不伪造远端资源。
- 配对入口显示二维码占位与服务器码示例 `O4T5-834840`，明确不会扫描、定位服务器或建立连接。
- Aries 卡展示地址、节点短码、HTTPS 说明，未接入时显示未配置。
- 服务器缓存卡使用 Aries 协议字段 `uploading`、`available`、`cleanup_pending`、`logical_deleted`，清理按钮仅提示后端未接入。
- 同步工作区、DSH 网页广播是本地前端预览开关；下载源沿用现有选择状态。

## 验证

- `assembleDebug`、`lintDebug` 通过：0 错误、48 warnings、4 information。
- APK：`G:\DeepSeek DSH\DSH Works\Project\Dafeiyu-Go\Temp_Android\Dist\Dafeiyu-Go-Mobile-0.1.13-frontend.apk`
- SHA-256：`64398f4046e63bab4d64b24374fb89211379b2186086ace6f2fb33acb4f5fc21`
- 本轮未安装、未启动；在线页视觉验收、真实 Aries healthz/node/device enrollment、presence 与缓存 API 尚未接入。
- PC/Aries 源码只读参考，未修改。
