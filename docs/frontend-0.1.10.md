# 0.1.10 Android 前端交付记录

日期：2026-10-11（Asia/Shanghai）。版本 **0.1.10 / versionCode 11**。本版仍是前端演示，没有接入真实 DSH、模型推理、配对服务或系统沙箱权限。

## 交付包

`G:\DeepSeek DSH\DSH Works\Project\Dafeiyu-Go\Temp_Android\Dist\Dafeiyu-Go-Mobile-0.1.10-frontend.apk`

- 大小：32,778,264 bytes
- SHA-256：`a8bbf5376a7abd9d115521430b5f3c0003e5e0f514a2e7195d201150160af49e`
- applicationId：`com.dafeiyu.mobile.preview`；minSdk 30 / targetSdk 35
- Debug 签名沿用0.1.9；证书 SHA-256：`52fc036fd6fe45d400f354235068aab29cb27ca6f68e42c53368a5b00c887b23`
- `assembleDebug`、`lintDebug` 通过：0 lint error、48 warnings、4 information

## 本版改动

- 侧栏选中的工作区与对话行改为亮蓝色胶囊按钮样式，延续“启动演示”主按钮的高光边缘与轻微渐变；选中图标、标题和工作区副标题使用白色。未选中行保留原样，工作区仍用文件夹图标。
- 通用玻璃按钮：浅色模式边缘更细、更亮，中心略压暗；暗色模式仅收细边缘。
- 扫码玻璃使用独立 `ScannerBlur` profile，关闭AGSL折射和shader编译，只保留模糊、tint与细轮廓。API31+用硬件RenderEffect；API30以低分辨率重采样柔化。
- 删除相机 `TextureView.getBitmap()` 读回。玻璃背景缩略图从ImageAnalysis的YUV帧每500ms至多更新一次，最长边160px；QR本机分析仍节流约5Hz并在finally关闭每帧ImageProxy。
- 预览偏好960×720、分析偏好640×480；预览使用`PreviewView.PERFORMANCE`，当AE支持范围允许时请求60fps，绑定失败则仅对本页use cases回退默认帧率。日志报告分析帧交付率，不假称为实测预览60fps。

## 验证与边界

- 代码编译、Lint及APK Debug签名检查通过。
- 已将APK安装到PKB110（ADB设备192.168.5.201:39871），设备包管理器报告versionName 0.1.10 / versionCode 11；本次未启动应用，也未运行真机扫码。相机实际Preview帧率受设备、AE帧率范围、并发use cases和分辨率影响；选中行外观、PERFORMANCE SurfaceView与Compose叠层、相机画面模糊取材、torch/二维码成功率仍待运行验收。
- 详细106项源码指纹与产物元数据见 `docs/frontend-build.json`；历史交互边界见 `HANDOVER.md`。
