# Anchored swipe research · 2026-10-12

通过本机 `http://127.0.0.1:7890` 读取 AndroidX Jetpack 固定提交 `7ac433e44e797de53af85226797862687f37735f` 的 `AnchoredDraggable.kt`、README 和 Apache-2.0 `LICENSE.txt`。没有引入 AndroidX 新依赖；项目已使用 Compose Foundation 的 AnchoredDraggable。

复用依据：固定锚点、`updateAnchors`、真实 offset、松手按 `positionalThreshold` 选择目标以及取消时回到锚点。项目需要一次左滑到归档锚点、第二次从稳定归档状态出发到删除/整行锚点，并在 DOWN 捕获当前 generation 与删除资格，避免动画中途授予删除。归档和工作区的提交动作由项目数据层分别执行，不能直接套用 AndroidX 示例。

来源：
- https://github.com/androidx/androidx/blob/7ac433e44e797de53af85226797862687f37735f/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/AnchoredDraggable.kt
- https://github.com/androidx/androidx/tree/7ac433e44e797de53af85226797862687f37735f

许可证：AndroidX Apache License 2.0，原始许可证文本保存在本目录 `LICENSE.txt`；本仓库没有复制其生产源码。

边界：只研究了现有 API 的锚点/阈值/取消语义，未声称上游提供归档 UI、阻尼参数或本项目数据保护逻辑。
