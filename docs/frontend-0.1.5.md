# 0.1.5 交互与真机验证

用户明确要求三个现有子Agent分工：glass_drag负责加号动作/按钮弹性，workspaces负责侧栏渐变，glass_diagnosis负责设置玻璃材料；主Agent负责合并、Apple来源资源、扫码和手机验证。

## 仓库阅读与实现

固定QWEA0/Liquid-Glass-Android commit16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d。已读LiquidGlassButton.kt、LiquidGlassView.kt约2104–2255行触摸/按压/方向弹性、GlassLensRenderer.kt触点局部凸起与折射、GlassMaterial.kt、LiquidGlassListGroup.kt、LiquidGlassListItem.kt。

按钮采用独立按压缩放与方向伸缩相乘、最多10dp阻尼位移、slop后消费拖动取消误点击，释放420ms CubicBezier回弹；光学press约180ms增强/320ms衰减，shader增加触点高斯凸起。减少动态效果时关闭运动。面板整体拖动抓手已从使用路径撤下，不再把它当按钮变形。

右上加号来自Apple/Swift DocC矢量，点击旋转45°变关闭符号，动作菜单可新建会话或扫码。扫码为外部相机拍照/系统照片选择后本机ZXing识别二维码；当前无实时相机取景扫描、不会自动打开识别出的链接或执行内容。

侧栏保持组合，用360ms slide/fade进入、240ms退出和贝塞尔缓动，宽296dp露出下页；目录和会话选中色连续变化。默认app私有filesDir/Dafeiyu-Works/默认工作区真实创建，会话索引AtomicFile保存。外部SAF目录仅授权引用，当前对话不写进用户外部目录。

设置按上游MERGED list group组织：整组玻璃外沿，内部行不逐行叠透明材料；正文前景显式主题色，20/24dp圆角、.40/.36覆盖，滚动标题轻微渐隐。实际glass仍只采独立共享纹理，不采聊天正文。API33+AGSL、31/32模糊、30纹理采样降级保持。

## 图标

已停止Canvas绘制控件图标。Apple/Swift DocC来源图标许可和源码哈希见icon-provenance.md及third_party/swift-docc-icons/provenance.json。不是SF Symbols完整库。没有相机、扫码、全部设置项对应资源，采用文字，不自造官方符号。应用自己的既有品牌资源保留。

## 手机证据

用户明确授权ADB安装与截图操作。无线调试连接192.168.5.201:39871，设备SDK37。0.1.5安装Success、启动Status:ok。日志显示AGSL lens compiled及hardware draw lens=true/reducedMotion=false。

截图在指定Temp_Android/qa/phone-0.1.5：home、plus-actions、sidebar、settings-real、plus-touch-held、plus-touch-released。held与released对比可见右上按钮位移/放大/方向拉伸后恢复。静态截图不能证明全部动画曲线、帧率或性能通过；未实测QR相机结果/所有权限授权/全部跨系统降级。

此前错误的长swipe截图和点错模型菜单的settings.png仅探索记录，不作为设置验收；有效设置截图是settings-real.png。没有读取或操作手机其他应用数据，后续logcat仅按本应用PID过滤；早期宽过滤采到的其他应用历史错误与本项目无关，不应继承为本应用异常证据。

最终构建与摘要以frontend-build.json、HANDOVER.md为准。仍没有真实DSH运行、模型调用或提权执行。Shizuku/root仅检测线索和说明，未实现授权SDK/命令。
