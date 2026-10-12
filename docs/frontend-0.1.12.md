# 0.1.12 Android 前端交付记录

日期：2026-10-11（Asia/Shanghai）。版本 **0.1.12 / versionCode 13**。本版仍为 Android 前端预览，未接入真实 DSH、Aries、模型服务或设备配对。

## 本轮设置重置

- 设置首页改为大页面总览，顶部展示固定的演示余额、Token 用量图表、DSH 状态和最新公告入口，并明确标注演示数据。
- `--引擎--`、`--线上--`、`--常规--`、`--模型--`、`--环境--`、`--数据--`、`--支持--` 均作为板块标题，不是选项。
- 加入官方轻引擎/DSH 引擎单选，本地/在线/混合连接模式单选，以及自动启动、同步工作区、DSH 网页广播等前端预览开关。
- 保留旧 `models`、`extensions`、`permissions`、`appearance`、`notifications`、`backup`、`network`、`notice`、`about`、`runtime`、`usage` 路由，避免聊天页和运行页的既有入口失效。
- 新的 `online`、引擎、环境、缓存、更新、反馈入口已建立首页来源几何，但详情页后端和完整内容仍待下一阶段实现。
- 继续使用现有单行来源测量、胶囊展开、触感和预测返回接缝；未改 PC/Aries 源码。

## 验证

- `assembleDebug`、`lintDebug` 通过：0 错误、48 warnings、4 information。
- APK：`G:\DeepSeek DSH\DSH Works\Project\Dafeiyu-Go\Temp_Android\Dist\Dafeiyu-Go-Mobile-0.1.12-frontend.apk`
- SHA-256：`16eb541bd7a76ae9b0a41d2222da28cfc467c64ada11e843007308e7d8e0175c`
- 已将0.1.12/code13安装到PKB110（ADB设备192.168.5.201:39871），包管理器已核对版本；本次未启动应用。设置页视觉细节、快速滑动吸附和后端在线状态仍待后续验收。
