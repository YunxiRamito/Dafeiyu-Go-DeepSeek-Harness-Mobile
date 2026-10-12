# 共享玻璃按钮交互（0.1.9）

后续可点击按钮统一使用 `GlassMotion.kt` 和 `GlassButtonDeformation.kt`，不要在各页面重新定义拖拽阻尼、回弹曲线或press倍率。

```kotlin
val motion = rememberGlassButtonDeformationState()
GlassSurface(
    modifier = Modifier.size(48.dp)
        .applyButtonDeformation(state = motion, circularHit = true),
    cornerRadius = 24.dp,
    buttonDeformation = motion,
) {
    IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
        FishGlyph("add")
    }
}
```

圆形按钮设置 `circularHit=true`，圆外四角的DOWN不会触发子按钮。胶囊/行操作使用false，按实际轮廓clip；所有类型的位移限制都按向量长度计算，因此最大位移是6dp圆半径，而非X/Y各6dp的方形。`FishButton`、可点击`FishCard`、主要附件操作已内部接线，不再重复加modifier。0.1.9设置项和主题选择行按用户要求不拖拽，`SettingRow`已移除形变helper；不得再加回。

圆形控件（circularHit=true）按压缩小到0.95；其他可拖控件保留最大1.025按压比例。沿拖动方向伸长最多4.5%，垂直方向压缩最多1.8%；只有超过touch slop才消费移动，避免拖动结束误点击。减少动态效果时关闭几何移动与光学按压，禁用按钮关闭拖拽。滚动列表内的行使用 `consumeDrag=false`，保留视觉反馈但将MOVE交给滚动容器；独立按钮使用默认true。

`GlassMotion.EaseOut = CubicBezierEasing(.16, 1, .3, 1)`，press150ms、release360ms、morph380ms。归位使用贝塞尔缓出，不使用spring振荡。侧栏与模型菜单均使用该曲线，圆心移动略领先边界扩大，内容按最终尺寸布局保持文字清晰。

侧栏进度在layout/layer读取，工作目录内容和native renderer保留复用；完全关闭时0×0、不place子树，清除语义与焦点，避免遮住聊天区。输入框、整页、材质大面板和文字输入不作为可拖动控件。Slider与系统Switch保留标准手势。

`TitleMorphHeader`使用同一贝塞尔曲线，根据原始标题几何与真实滚动偏移连续移动和缩小同一条文字。不是两条标题crossfade。

0.1.9开闭轮廓统一使用 `centerProgress(p)=p+.12*p*(1-p)` 与 `sizeProgress(p)=p`，同时移动中心并展开边界。没有居中暂停，MorphMillis最终380ms；520ms试验已撤回。设置详情从单行胶囊展开，不从整组列表展开。

模型、沙箱、右上菜单使用 `AnchoredGlassMenu`，固定最终内容布局，geometry在layout读、透明度在graphicsLayer读；末段8%轮廓淡出，原入口渐接替。侧栏到设置另有并行背景crossfade，不得与普通单行展开混为一个状态。

`LocalGlassHaptics`使用Android确认触感，统一helper在有效UP触发，嵌套helper按事件时间和指针去重。拖动/移出/取消不震，动画完成不震；无helper的设置行在有效onClick确认，不必重新启用拖拽。