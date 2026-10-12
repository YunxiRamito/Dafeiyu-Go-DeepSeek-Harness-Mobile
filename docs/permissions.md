# 权限与系统页面

`ui/PermissionScreen.kt` 提供原生 Compose `PermissionsScreen(onBack: () -> Unit, notify: (String) -> Unit)`，由主任务接入设置路由。页面使用 FishCard/FishButton；按钮遵循现有玻璃组件，内容卡片使用普通可读表面。

- 通知：Android 13+ 用 `RequestPermission(POST_NOTIFICATIONS)`；其他情况打开本应用系统通知设置。读取实际运行时许可和 `NotificationManagerCompat.areNotificationsEnabled()`，不使用演示偏好充当许可。
- 相机：用户主动选择后用 `RequestPermission(CAMERA)`。授权是可选项，拒绝后允许相册/附件路线。
- 相册：系统 `PickVisualMedia(ImageOnly)`，只取得所选 URI。本页仅验证选择器，不读取/复制图片，也不把图片添加到对话或长期保存 URI。无需 READ_MEDIA_IMAGES、READ_EXTERNAL_STORAGE 等广泛读取权限。系统设置按钮打开 `ACTION_APPLICATION_DETAILS_SETTINGS`。
- 电池：`PowerManager.isIgnoringBatteryOptimizations()` 读取系统名单状态，打开 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` 让用户自行管理；不声明或调用 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 的直接豁免请求。
- Shizuku：仅用包管理器检查已知应用包 `moe.shizuku.privileged.api`；已安装不代表运行、已授权或具备可用 binder。当前没有集成授权 SDK，不会请求或调用 Shizuku 服务。
- root：仅检查常见 su 文件路径存在与否，是候选线索，不是 root/授权认证。没有 shell、su 执行、提权或授权弹窗。
- 页面注册生命周期观察器，返回前台 `ON_RESUME` 时重新读取状态；也有手动刷新按钮。权限请求回调同样刷新。

## 主任务集成

请在 Manifest 添加 `POST_NOTIFICATIONS`、`CAMERA` 的 uses-permission，并添加 `<queries><package android:name="moe.shizuku.privileged.api" /></queries>` 保证 Android 11+ 对已知包的可见性。不得为了这些设置添加广泛照片/文件权限或直接电池豁免许可。

**相机接线注意**：一旦 Manifest 声明 CAMERA，现有对话 `TakePicture()` 入口在许可拒绝时需要先经过运行时请求/拒绝处理，否则某些系统可能拒绝外部相机 Intent。主任务应在原 AttachmentPanel 增加许可判断后再声明；不能只改 Manifest。

本文件由子任务新增，不改 MainActivity、Manifest、设置路由和相机入口，由主任务统一接线、构建、Lint 和真机验证。实际授权行为与 UI 验证仍以主任务记录为准。
