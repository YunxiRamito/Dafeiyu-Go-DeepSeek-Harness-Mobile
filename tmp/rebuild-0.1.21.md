# 0.1.21 修改与构建计划

## 版本号
- versionCode: 22 (from 21)
- versionName: 0.1.21 (from 0.1.20)
- APK: Dafeiyu-Go-Mobile-0.1.21.apk

## 并行修改（4 个 Agent）

| Agent | 文件所有权 | 改动范围 | 完成标志 |
|---|---|---|---|
| 聊天交互 | ChatScreen / Feed / Summary / GeneratedFiles / ActivityReceipts | 删除确认、扫码导航、滑动穿透、失败重试、Markdown 链接 | docs/changes/0.1.21-chat.md + 编译通过 |
| 设置逻辑 | SettingsScreens / FontSettings / PermissionScreen / ExtensionsScreen | 布局重构、备份流程、开关隐藏、确认框、字体页修复 | docs/changes/0.1.21-settings.md + 编译通过 |
| 液态玻璃 | GlassSurface / Material / Shader / LiquidGlassButton* | 暗色 tint、饱和度、亮边、采样、缓存复用 | docs/changes/0.1.21-glass.md + 编译通过 |
| 侧栏全局 | WorkspaceSidebar / MainActivity / FishComponents | 保持打开、删除确认、交互逻辑 | docs/changes/0.1.21-sidebar.md + 编译通过 |

## 构建
- 所有 Agent 改动完成 → 等待通知
- 串行构建：`tools/build.ps1 -MaxWorkers 2`
- 验证：Lint 0 Error，APK 签名，hash 核对

## 设备验收
- 在 emulator-5558 上安装 0.1.21
- 逐页截图（设置重构、聊天、侧栏、玻璃渲染）
- 验收要点：删除确认、开关隐藏、布局舒服、暗色可见、无 ANR

## 最后交接
- 更新 HANDOVER.md 0.1.21 部分
- 列出改动文件和理由
- 清理临时文件
