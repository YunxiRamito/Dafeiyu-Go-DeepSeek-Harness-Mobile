# 0.1.1 原生界面与 PC 样式依据

日期：2026-10-10。使用 Compose 适配桌面设计规则；没有复制 Windows 二进制控件、字体或系统材料。用户提供的五张开屏图仅作为信息布局参考：左上菜单、留白主区、底部固定输入。未复用它们的品牌图像、收费/语音入口或无关功能。

| 样式 | 已读 PC 源码 | 手机适配 |
|---|---|---|
| 实色背景 | Click-To-Run/source/LauncherAppearance.cs:298，浅 #F3F3F3、深 #202020 | 相同基础色，深卡 #2B2B2B |
| 页面标题 | Click-To-Run/source/SettingsWindow.xaml:29，28 / SemiBold | 28–29sp，适配系统字号 |
| 卡片边界 | SettingsWindow.xaml:41，主题背景 + 1px 描边 | 1dp 细边，6dp 角 |
| 控件圆角 | source/CornerRadiusHelper.cs:53，Win11 控件4 / 卡片6 / 浮层8 | 4 / 6 / 8dp，按钮触摸高度至少48dp |
| 普通/强调按钮 | SettingsWindow.xaml:65 | FishButton，中性按钮与蓝色强调按钮 |
| 侧栏选中项 | 本机 WinUI SDK generic.xaml:32395，3×16 指示器；:1591 的中性底 | FishNavigationRow，中性浅底 + 3×16dp 窄蓝条 |

桌面工程路径：`G:/DeepSeek DSH/DSH Works/Project/Dafeiyu-Go/Dafeiyu-Go-DeepSeek-Harness-Click-To-Run`。
WinUI SDK：`G:/DeepSeek DSH/.nuget-packages/microsoft.windowsappsdk.winui/1.8.260803003/lib/native/Microsoft.UI/Themes/generic.xaml`。
桌面默认跟随 Windows 系统 accent；手机固定浅色 #0067C0 / 深色 #60CDFF，不能将这两个值称为所有桌面设备的实际强调色。

默认轻量对话，顶部模式下拉和左上菜单均可切到 DSH 接入布局。两模式消息、草稿与滚动状态独立；切模式会结束旧的模拟生成，新建对话会清除当前模式草稿。侧栏收起设置、插件/技能与运行管理。DSH 接入布局采用启动状态行和左下软件设置入口，真实 DSH 前端槽研究见 frontend-modes.md；这里由 Kotlin 回调导航，尚无插件注入。

界面没有真实语音、搜索、附件上传或模型调用；深度思考只预演按钮选中状态。当前保留单行工具区，模型选择使用剩余宽度，保证发送/停止操作的触摸区域；消息输入支持多行。父容器消费窗口 inset，输入区和详情页处理 IME。输入法可见时主导航 BackHandler 不拦截系统返回。

Android Studio 的 FrontendPreviews.kt 提供浅色、深色、320dp短屏/1.5倍字号和DSH布局预览入口；这些源码入口不代表已完成截图或设备验收。本轮只完成静态审查、构建、Lint和APK校验，用户真机观感与输入法行为待反馈。

