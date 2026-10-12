# 0.1.8 前端验证记录

本轮按手机截图修复冷启动输入边框、方形发送、重复返回、渐变顶部、同一标题移动、返回按钮通透度、模型胶囊与侧栏形变；并按用户要求统一按钮逻辑。

构建版本0.1.8/code9，签名、最终产物与源hash见frontend-build.json。最终assembleDebug/lintDebug构建通过。Lint并非零提醒：遗留DocC资源未使用、SF转换长向量path、配置宽高读取、频繁动画状态读取、旧系统返回属性以及KTX建议仍存在。未为保留原图标路径而擅自简化其形状。

真机SDK37，授权ADB目标192.168.5.201:39871。仅本应用操作，不读取其他app数据；截图过程偶有系统通知覆盖，覆盖图不作为UI证据。照片、相机、通知权限未为验收擅自授权。

证据存于Temp_Android/qa/phone-0.1.8。root逐张检查homepage/model-menu/sidebar/settings/live-back-button以及短详情页标题。返回按钮已显示后方蓝色控件；实际page采样与正文模糊可见。同一title从大字移到topbar，隐藏占位标题无重复语义。

滚动行新增拖动反馈后曾拦截MOVE，已统一consumeDrag=false修复，最终手机已复查设置滑动和备份短页折叠，标题从大字移到顶栏小字且返回键只有一个。独立按钮仍消费专用形变拖动。侧栏renderer保留复用以减少重新初始化，但不宣称完成帧率profiling。

未验收：低API/GPU兼容、键盘与屏幕旋转全路径、授权/相机完成、QR结果、全部动态帧率、真实DSH/模型后端。

最终APK已覆盖安装：0.1.8/code9。assembleDebug与lintDebug通过，0错误、38个Warning、4个Information；签名验证通过。产物SHA256与所有编译输入hash已同步frontend-build.json。应用已回到聊天主页。
