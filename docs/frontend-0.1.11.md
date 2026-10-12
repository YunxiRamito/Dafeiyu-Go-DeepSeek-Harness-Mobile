# 0.1.11 Android 前端交付记录

日期：2026-10-11（Asia/Shanghai）。版本 **0.1.11 / versionCode 12**。本版仍是前端预览，没有真实DSH、模型推理、配对服务或系统沙箱执行。

## 交付包

`G:\DeepSeek DSH\DSH Works\Project\Dafeiyu-Go\Temp_Android\Dist\Dafeiyu-Go-Mobile-0.1.11-frontend.apk`

- 大小：31,909,460 bytes；SHA-256：`9aaf91f9aa323aa87084a3ec63cca1ab9680abb1d6529f57d88f6517ebd596fd`
- applicationId：`com.dafeiyu.mobile.preview`；minSdk 30 / targetSdk 35
- Debug证书 SHA-256：`52fc036fd6fe45d400f354235068aab29cb27ca6f68e42c53368a5b00c887b23`
- `assembleDebug`、`lintDebug`通过：0错误、48 warnings、4 information

## 本轮改动

- 扫码背景采用YUV分析帧生成的最长边96px的低分辨率缩略图，最多约30Hz更新；每次使用约7千像素的RGB转换，不做`TextureView.getBitmap()`。玻璃RenderEffect模糊和tint继续作用在缩略背景上。
- 扫码玻璃新增低成本顶部柔光渐变与方向性细边高光；继续禁用折射shader，QR解码节流仍约5Hz。
- 启动器图标替换为用户提供的发丝海豚PNG：4096×4096 RGBA经Lanczos压至512×512 RGBA，无裁切与额外滤镜。资源为`app/src/main/res/drawable-nodpi/ic_whale_512.png`，清单已更新引用。
- 预览仍按设备AE支持条件请求60fps；这不是对实际显示帧率的测量或保证。

## 验证及边界

- Android编译、Lint与Debug签名验证通过；icon资源尺寸/格式由FFprobe核对。
- 已将0.1.11/code12安装到PKB110（ADB设备192.168.5.201:39871），包管理器核对版本；此次只安装，未启动应用。实际30Hz模糊观感、相机预览帧率、Compose叠层、QR/torch与新启动器图标仍待运行验收。
- 106项既有编译输入加新增icon共107项指纹见`docs/frontend-build.json`；图标来源细节见`docs/icon-provenance.md`。
