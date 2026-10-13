# 手机 DSH 网页主题研究

研究日期：2026-10-14。所有远程读取均使用本机 `http://127.0.0.1:7890`，未运行上游脚本或安装上游依赖。

| 来源 | 固定提交 | 许可证 | 实际审查 |
| --- | --- | --- | --- |
| `rdev/liquid-glass-react` | `ac48eab18d1f7f444ae30002d240cae29c863a21` | MIT，Copyright 2025 MAX ROVENSKY | README、LICENSE、`src/index.tsx`，包括 GlassFilter、GlassContainer、指针坐标与生命周期、边缘和高光层 |
| `shuding/liquid-glass` | `a2d2e847f793430e3409a52927af815a23f4d372` | MIT，Copyright 2025 Shu Ding | README、LICENSE、`liquid-glass.js`，包括位移图生成、SVG filter、事件和 destroy 清理 |

两个提交由 `git -c http.proxy=http://127.0.0.1:7890 ls-remote <repository> HEAD` 成功取得。之后使用 `curl.exe -x http://127.0.0.1:7890` 从 raw.githubusercontent.com 的精确提交路径读取文件，五次初始读取和额外 shader 源码读取均 exit 0。原始文件保留于同级 `Temp_Android/research-palette-themes/browser/`。这不是之前 Android 光学 commit API 的速率限制请求；不将那次失败改记为成功。

React 实现明确记录 Safari／Firefox 只部分支持位移，源码也有 Firefox 降级分支。它使用独立材料层保持内容清晰、真实指针坐标和 RGB SVG 位移。Shu Ding 示例通过 Canvas 生成位移图，并把背景 SVG filter 与拖动容器放进页面；示例直接安装事件，需要为生产宿主另做可撤销的完整生命周期。

本轮借鉴材料／清晰内容分层、受限跟手坐标、有限按压过渡以及浏览器降级原则，不复制上游源码，也不引入 React 或上游 npm 运行依赖。已有 DSH 页面由 React／Cordis 管理，主题只向已审查的手机 DOM 控件发布固定 CSS 变量，不移动业务节点、不注册新动作。固定 hex 颜色先转换为 rgba，数值先按 Android 1.0.2 的 76 参数范围校验；不能导入任意 CSS、图片、账户或脚本。

采用真实 CSS backdrop blur／saturation、颜色／透明度、阴影、描边、渐变反射及按压追光，原生 AGSL 内壁折射、RGB 色散和 SDF 粘连尚无网页实现。本轮使用 `css-backdrop`／`css-tint` 能力标记，不把 CSS 渐变写成光学等价。`srcIn` 在 CSS 图标混合路径中降级为 normal，`plus-lighter` 只在 CSS.supports 确认后使用。所有修改仅限显式 mobile 上下文，普通 PC 不启用；根和控件的 inline 变量逐项保存原值与优先级，退出时只还原仍属于本插件的值。

源码和实际 built ModuleLoader 夹具测试覆盖契约、八组主题、真实尺寸圆角、按压／取消、恢复／后写者、偏好及无空闲循环。它们验证 JavaScript／DOM 接线，不代表浏览器像素、Android WebView、设备性能或用户视觉已验收；这些状态由主 Agent 的实际设备证据另记。
