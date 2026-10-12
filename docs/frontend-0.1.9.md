# 0.1.9 Android 前端交接补充

日期：2026-10-10（Asia/Shanghai）。当前交付 **0.1.9 / versionCode10**，仍只做前端。真实DSH、模型服务、设备配对和沙箱执行权限没有接入。本文件记录本轮最终保留的行为，覆盖此前中途试验参数。

## 当前交付

`G:\DeepSeek DSH\DSH Works\Project\Dafeiyu-Go\Temp_Android\Dist\Dafeiyu-Go-Mobile-0.1.9-frontend.apk`

- 大小32,679,844字节；SHA256 `186dcc8e73952a50a177bcae4b88cef921522c1cd66be10163a95b9415e19c2f`。
- 包名com.dafeiyu.mobile.preview；min30/target35；签名沿用此前Debug证书。
- assembleDebug/lintDebug通过；Lint **0错误、49 Warning、4 Information**，包含长向量路径、未使用历史资源、配置尺寸、绘制分配、频繁状态读取及KTX建议。
- 已按用户授权安装最终包并冷启动，手机包管理核对0.1.9/code10。
- 最终构建日志 `Temp_Android/build-0.1.9-final.log`，106项编译输入指纹与产物元数据见 `docs/frontend-build.json`。

## 最新用户确认的交互

1. 设置浅色背景#F2F2F7，组件#FFFFFF；暗色背景#000000，组件#1C1C1E。设置项/选择行不拖拽，点击松手才确认触感。
2. 设置详情从**点击的单行**实际Rect展开。该行先呈胶囊轮廓，中心移动途中左右/上下边缘同步扩大；不是整组设置卡片、没有居中停顿。其他行保留在后面模糊，目的页内容只在扩大轮廓内显现。关闭/预见式返回缩回同一行。
3. 设置首页返回缩回主页左上48dp圆按钮。左侧栏进入设置的特殊接缝必须保留：**侧栏缩回圆形时，背景仍模糊，主页同时渐变到设置页，再恢复清晰**；不能先让主页清晰一帧再跳设置。
4. 所有形变复用GlassMotion：EaseOut(.16,1,.3,1)，MorphMillis380；centerProgress=p+.12*p*(1-p)，sizeProgress=p。曾试验520ms，已按用户纠正退回380ms。圆按钮按住缩小至.95，松手缓出归位；拖拽仍径向阻尼6dp，设置项不套拖拽。
5. 触感已从“完全展开时震动”改为**有效点击的UP松手确认**；拖动/取消不震，嵌套helper按UP时间/指针去重，遵循系统触感设置。不能声称一定能识别马达类型或用户已主观验收手感。
6. 模型、沙箱、右上加号菜单共用AnchoredGlassMenu的来源轮廓、中心/尺寸轨迹、预测返回与末段交接。关闭最后8%淡出轮廓，原入口逐渐接替，Popup多保留透明交接帧。
7. 加号右侧新增36dp无背景沙箱图标；只读/工作区内修改/完全权限，默认工作区内修改，rememberSaveable仅记录前端选择。不授予系统权限，不执行文件操作。
8. 右上菜单新增配对码输入预览；扫码图标采用现有SF数据中的viewfinder。配对码不发送给服务、不连接电脑。
9. 主页与设置页顶部都有实际页面渐变模糊，覆盖状态栏；底部反向渐变延展到导航手势条，平台system bar颜色透明，禁用系统额外实色contrast遮罩。扫码页自己处理安全边距与浮动操作。

## 实时扫码与性能修复

ScannerScreen使用CameraX1.4.2 PreviewView COMPATIBLE + ImageAnalysis，ZXing3.5.3本机解码，优先640×480、只保留最新帧、分析约5Hz。每个ImageProxy在finally关闭；crop、rowStride、pixelStride、rotation和缓冲边界经过检查，亮度/旋转数组复用。识别内容只展示，不执行链接；灯光读真实torchState，无闪光灯时禁用；离页解绑自己的use cases，不调用unbindAll。

用户反馈相机卡顿后，删除主线程全尺寸PreviewView.bitmap及再次缩放分配。玻璃只从安全可用TextureView写入长边384px的复用bitmap，至少750ms一次；任务去重不堆积。首次采样>8ms自动降至2000ms；>32ms或读回异常停止更新，保留缓存。找不到安全TextureView就暗材质回退，不用昂贵截图兜底。取样降频只影响按钮玻璃背景，不修改真实相机预览帧率。

按钮通过camera-only leaf取材，普通tint.14/选中.18；不是整块实色圆。首次耗时日志tag DafeiyuCameraMetrics。性能修复已编译，**最终相机流畅度、玻璃对齐、torch及二维码成功率尚未完成可重复真机验收**，不能把代码优化写成已彻底解决卡顿。

## 材质最终状态与两份新参考

仍采用QWEA0/Liquid-Glass-Android原生AGSL/RenderEffect，未引入WebView/JS/SVG渲染器。曾按childrentime参考试验低位移/色散参数，用户要求回退后，已恢复原QWEA0式法线折射位移和色散。当前新增的是来源层一致性、清晰的方向轮廓与性能修复，不能写成已完整移植childrentime或shuding。

用户指出菜单背景与外部模糊层不搭：根页面已压暗/模糊，但菜单取了处理前正文。因此新FloatingBackdropSource只读取独立页面GraphicsLayer，再施加与外部一致的模态模糊/压暗，通过单独leaf给跨窗口Popup按屏幕位置取样。不是捕获包含菜单的祖先。移除了capture.revision到根invalidations的反馈链，页面按需更新。

材质profile都使用3.5dp材料模糊；菜单tint最终.55，外层模态模糊最大2.8dp、压暗.12。中途18dp/.84~.86实验值已撤回。主页模态effect预缓存分档，轮廓动画只更新layout/layer，菜单文字按最终尺寸测量，避免每帧重组列表或编译shader。

按钮轮廓按上游同一SDF法线驱动迎光/背光双瓣；有效亮边宽度约1.2dp，迎光侧向内柔光。浅色背景加少量方向性明暗分离，暗色背景保持白色反射；API33已用光场时不再叠加常亮整圈描边，降级时用约1.15dp渐变边线。用户请求白色柔光通过迎光侧glow实现，不是整块白色底。

材料参考比较见 `docs/glass-material-0.1.9.md`。childrentime固定345a3c4f…的README声明MIT但缺完整LICENSE；shuding固定a2d2e847…有MIT Copyright(c)2025 Shu Ding。shuding普通玻璃用CPU位移图+SVG feDisplacementMap，默认图可缓存；diamond多次ray bounce不作为手机UI候选。两者均未将源码/JS渲染管线嵌入应用。

## 文件和接线

- MainActivity：双层设置过渡、独立页面chrome、模态背景、侧栏到设置crossfade、预测返回；transferringSettings/settingsTransfer不得移除。
- SettingsPageTransition：单行来源、真实胶囊半径、源内容小块裁取、独立页面blur/alpha交替；来源和目的页过渡期触摸阻断。动画progress在draw/layer读，blur0/2/4/6/8dp缓存。
- AnchoredGlassMenu：模型/沙箱/右上菜单的公共Popup生命周期和轮廓；最终内容固定测量，动画geometry在layout读。
- GlassButtonDeformation/GlassMotion/GlassHaptics：统一手势、圆形命中、按压缩小、径向阻尼与松手触感。
- GlassMaterialProfile/GlassSurface/LivePageGlassMaterial：跨窗背景bridge、材料profile、AGSL与系统降级；采样源必须不含当前材料自身。
- CameraQrScanner/ScannerScreen：实时相机、图像分析、本机QR、玻璃缓存、灯光/相册。
- PairingScreen/SandboxMenu：前端选择/输入预览，后端未接。

图标已扩展为39个原SF路径转换资源，包括锁/解锁、folder.badge.gearshape、viewfinder、灯光和number.square。来源仍UsePreside/sf-symbols第三方转换数据，非Apple官方仓库，无明确资产再分发许可；来源hash和NOTICE保留，不自绘按钮图标。取景方框是扫描引导几何，区别于控件图标。

## 证据和未验证部分

Temp_Android/qa/phone-0.1.9包含home-contour-final、sandbox、plus-material、appearance-current等截图。截图证明原生页面可用与控件布局，但旧中途材质截图不能证明最终参数。尝试录制sidebar-to-settings时手机同时在使用，部分录制/坐标操作被干扰，不作为完整过渡验收。QA脚本会检查前台包，非大肥鱼即跳过点击/截图；没有更改其他app配置。

旧gfxinfo和新抽样不在相同负载/操作条件，不提供“卡顿下降百分比”结论。最终暗色轮廓、相机性能、完整预测返回取消、触感手感、所有动画帧率仍需用户预览和后续控制条件测试。已核对源码/构建/签名/最终包版本，未读取用户其他app数据作为项目证据。

没有Git提交/推送/发布，未改桌面/Aries/真实DSH配置。所有工具链、下载、日志、APK和QA留Temp_Android。此前自动审批拒绝的临时截图清理未绕过，本轮不重试已拒绝删除。
