# 大肥鱼 Go · Android

截至 **2026-10-12（Asia/Shanghai）**，**0.1.19 / code20 已交付桌面并保留数据安装至用户手机，作为可用户验收的预览包；整包成功，设备验收部分通过、长聊天仍失败**。新包已包含 FishButton 参数及 GeneratedFiles 标题 `consumeDrag=false` 修正。当前证据见 [验收记录](docs/acceptance-0.1.19-20261012.json)、[迭代计划](docs/ITERATION-PLAN-2026-10-11.md) 和 [交接文首](HANDOVER.md)；下方旧版本、停工和未交付说明保留为历史。

最终 APK `../Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.19.apk`：**91,404,408 bytes**，SHA-256 `c7e3320b4c483c05be842caa62f8f1158b9502ad6870a8ab14a5e5c7cf8a41f3`，签名证书 SHA-256 `52fc036fd6fe45d400f354235068aab29cb27ca6f68e42c53368a5b00c887b23`。`../Temp_Android/build-0.1.19-scroll-fix.log` 为 **BUILD SUCCESSFUL 3m11s**，46 tasks（12 executed / 34 up-to-date）；Lint **0 Error / 138 Warning / 5 Information**。[构建元数据](docs/frontend-build.json) 已由 RecordBuildEvidence 记录 179 个源码/配置及 19 个实际 runtime 输入，并绑定验收记录。

最终设备 run `activity-019-30b3d08caa2b409c90848385ede716c9` **EXIT 1**：终态折叠、分组活动、最终正文关联和三次活动展开/收起 PASS；长聊天 24+24 次滑动未找到 `QA_LONG_FIRST_019`，FAIL。[Kuhn 分析](docs/research/QA-LONG-CHAT-20261012.md) 显示正常双向滚动 19→12→19，未覆盖第 0 轮，不认定完全卡死或唯一根因。全事务 membership、SHA-256 与原有 absence 恢复 PASS，App 停止。后续两端往返、320dp、Interrupted 和当前 PID 日志/内存检查未执行。新包短诊断已观察到原 center 起点滚动且未 toggle，随后遇到脚本空子节点 bounds 错误；QA 脚本修正后离线 **37/37 PASS**。这些结果不代表完整设备验收，普通标题点击、流式/IME、复制引用、删除持久化与两段左滑等仍待验收。本轮未再重复完整矩阵，后续按新变化安排针对性验证。独立 QA 模拟器已于 00:51 左右正常关闭，用户原 AVD 不变；必要时再以 host GPU / WHPX 启动。

桌面 `C:/Users/KitamaruRamito/Desktop/Dafeiyu-Go-Mobile-0.1.19.apk` 已核验同 hash；手机 `192.168.5.201:33601` 的 `install -r` 返回 Success，version 0.1.19 / code20、lastUpdateTime **2026-10-12 00:49:48**，手机 `base.apk` SHA-256 匹配。实际 delivery 对象已记录于验收 JSON 并同步构建元数据。保留数据，安装后未启动 App、无模型调用；手机安装不等于手机 UI 通过。

[QWEA0 固定源码研究](docs/research/liquid-glass-20261012/REPORT.md)、[Compose 适配评审](docs/research/liquid-glass-20261012/COMPOSE-ADAPTATION-REVIEW.md) 和 [Kyant0 候选比较](docs/research/liquid-glass-20261012/COMPOSE-CANDIDATE.md) 已完成；**现有 gap-only 桥尚未实现共同光学融合**。下一轮由主 Agent 排期，无需新增用户审批；落实同源实际动画几何、原材质/阴影/白光让位、`launcherAlpha` 同步与低 API 降级。继续遵守 [AGENTS.md](AGENTS.md) 的本机 `127.0.0.1:7890` GitHub 研究、固定 commit/tag 和许可留证要求。

上述四份 Markdown 的同步由文档子 Agent 完成；该子 Agent 未修改源码/metadata，未启动网络、ADB、编译、测试或模型调用。这仅说明文档同步工作的范围，不否定上文已记录的研究、构建、验证及交付。

## 停工交接（2026-10-11）

用户已要求停止全部开发并交接，六个子 Agent 已关闭；随后只完成用户授权的无线 ADB 配对，没有安装或启动手机应用。**已交付 0.1.18 / code19；源码 0.1.19 / code20 有未验收改动，尚未整包编译。** 下一轮先读 [HANDOVER.md](HANDOVER.md) 文首和 [停工清单](清单.md)，保留现有修改。

用户确认真实搜索成功；思考/搜索顺序错误、统一活动间距和新的椭圆信息条尚未实现。滚动回底、空区删除、两次左滑、工作区选中及玻璃按钮有源码初稿，不能当成设备验收完成。参考截图已保存在 `docs/reference/ui-2026-10-11/`。下文开发状态和更早版本记录仅为历史快照。

## 当前交付 0.1.18 / 源码 0.1.19 开发中（2026-10-11）

已交付首包为 **0.1.18 / code19**，已完成 build + install 并提供用户查看。当前 `app/build.gradle.kts` 为 **0.1.19 / code20**，第二阶段 UI 由四个 worker 与主线程继续实现，待主线程提供 final2 和专项验证证据，不标记新版本完成。0.1.18 父模型已接入同批子 Agent 结果等待与收集；完成回复和生成文本支持复制、引用回 composer，下载/导出为次级入口。主 UI 的多轮 `responseText` 取当前模型轮次，较早正文留在 process，不重复拼接。生成文件在 IO 上从受管应用输出 store 安全读取，提供默认展开、可选择的全文预览。composer 上方已接实际运行信息条，显示轮数、工具步骤、Tokens、缓存比例、耗时及有依据时的速度；缺失数据保持未知。

紧凑后台活动按真实轮次/事件归组，关联子 Agent 去重；普通长任务完成后整体折叠，完整 process 保留公开思考和旧轮正文并可手动再展开，最多显示 3 个 child glyph。compactSkill 仅为真实技能活动的分类展示。公开思考默认折叠，标题取最近公开原句，新增互动沿用现有 Apple Liquid Glass 设计的 Android 玻璃材料、按钮变形、48dp 目标及共享 380ms/reduce-motion 规则。

已报告离线验证：生成文件 Compose 严格编译与 **16/16** 安全读取；assistant-stream **30/30**；运行 metrics/信息条 **18/18**；后台任务 **40 项基础 + 9 项等待/递归**；渐显/思考的 **7 项原句提取**及边界检查；Peirce 紧凑活动、完整 process、runStatus、最多 3 个 child glyph 和折叠生命周期有 PASS 日志。它们是不同范围的离线证据，不代表整包或真实模型验收。记录、日志位置和限制见 [0.1.18 实现与验证](docs/frontend-0.1.18.md)。

0.1.18 构建日志为 `../Temp_Android/build-0.1.18-final3.log`：assembleDebug + lintDebug **BUILD SUCCESSFUL in 4m 15s**，46 tasks（14 executed / 32 up-to-date）。Lint XML **0 Error / 137 Warning / 5 Information**。首包 `../Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.18.apk` 为 **100,160,952 bytes**，SHA-256 `98D38E61F5ADF8A9785EE5CAF3CE4F29C65A8E46E1DECF01FC94173AA45D9B73`；包元数据 code19 / 0.1.18 / min30 / target35。主线程报告 v2 签名 verify 成功，沿用证书 SHA-256 `52fc036fd6fe45d400f354235068aab29cb27ca6f68e42c53368a5b00c887b23`；已保留数据安装至 emulator-5554 / API37 / x86_64。

官方轻引擎的 **`web_search` 与 `browser` 已真实 callable**，DemoRepository 注入 OfficialWebClient，schema、dispatcher、Session、回执事件和返回 tool history 链已接通。搜索使用 Bing RSS 与 DuckDuckGo HTML 回退；浏览读取公开 HTTPS 页面正文、标题、最终 URL、链接与时间，不执行网页脚本。来源和成功/失败状态依据实际工具结果，模型自称联网或回答附链接不代替执行证据。

生产源码在 Android shell `app_process` 中执行的检索已成功：`Kotlin official documentation` 返回 Bing 3 条真实结果。example.com 返回真实正文，但 QA 错误要求正文含 h1 而 FAIL；主线程改用 Kotlin docs 复测，现有 docs 日志仍 FAIL（正文为空），浏览待补成功证据。活动设备 QA 也未完整通过：现有结果 `No chat scroll surface`，脚本修复中；finally 恢复原数据，byte compare/cmp verified。上述 shell 检查与最终 APK 应用 UID、完整设备 UI、付费模型自动选工具验收分别记录。

focused **27 PASS / 0 FAIL** 使用真实源码覆盖 search → browser → 后续模型请求的协议序列化；追加 **4 PASS / 0 FAIL** 覆盖下一用户的 OfficialChatHistory、精确调用关联与结果上下文。模型和网络响应为离线 fixture，完整证据与范围见 [0.1.18 记录](docs/frontend-0.1.18.md)。最新 [分阶段清单](清单.md#最新分阶段清单2026-10-11) 已分开勾选实现、构建/安装和待补 QA；用户查看首包期间继续所列滚动、工作区、左滑、按钮玻璃和详细回执 UI，第二阶段仍开发中。

script/sandbox 与 DSH runtime 仍未接入，不能宣告全软件完成。连接包 prepared 不表示 runtime loaded。只读调查仅确认 arm64 Node/依赖已准备且锁定库摘要匹配，真实 DSH CLI/Web bundle 和 x86_64 runtime 未找到，Termux 路径/SONAME 和宿主生命周期仍需适配。本次仅修改五份 Markdown，未执行 Gradle、ADB、网络或付费请求；`docs/frontend-build.json` 与 `docs/icon-provenance.md` 留主线程维护。

## 0.1.17 历史基线（2026-10-11）

以下 0.1.17 及更早章节中的“当前”“最终”、无通用搜索、负责人分工和构建/安装指令仅指对应历史批次（historical instructions），不构成当前能力结论或本轮操作授权。当前交付、源码版本与待补证据以文首为准。

当前交付为 **0.1.17 / code18**。本节与 [0.1.17 交付与验证记录](docs/frontend-0.1.17.md) 优先于下方保留的 0.1.16 历史说明。OfficialToolSession、图片/历史请求预检、回复 Markdown 与生成文件入口、后台任务凭据变更停止已纳入最终构建。Franklin 的短屏几何与菜单互斥修复已合入，最终 `assembleDebug` + `lintDebug` **SUCCESS，3m55s，46 tasks（13 executed / 33 up-to-date）**。Lint XML 为 **0 Error / 137 Warning / 5 Information**，不是零警告；其中 4 条 ConfigurationScreenWidthHeight 位于安全窗口尺寸回退。`docs/frontend-build.json` 由主 Agent 更新，本次文档任务不改写。

主页任务浮层已接线：MainActivity 在加号左侧接入 AgentsTaskActions，使用独立 48dp 双气泡群聊按钮。列表仅子 Agents/后台任务，不含 Outputs/Sources；StateFlow + lifecycle 订阅真实状态，支持空状态、滚动、标题省略、对应详情及查看全部任务，复用 AnchoredGlassMenu 形变、预测返回与外部关闭。双向 surfaceVisible + 点击 gate 保证互斥，加号菜单接入减少透明度，tasks route 使用独立 floatingBackdrop。生产几何独立 Compose `-Werror` 编译及 288 bounded cases 通过。最终 APK 已安装至 API37 `emulator-5554`；最终 QA 验证任务分组/状态、对应详情 prompt/result、返回主页、加号菜单保持、320x400dp 短屏 footer 完整可点及全部/后台/子 Agent 筛选路由。此前已测长标题省略、外部点击和边缘返回关闭。fixture 未调用模型，finally 已恢复原 task data 并 cmp identical、删除 backup/临时 fixture、wm size reset；空状态只属于阶段测试，恢复后现有 selftest-A/B 用户任务保留，final-restored-home.png 是恢复后当前页面截图，不是纯主页或最终空任务证据。回复生成文件保持独立范围。

最终 IME 实测：打开群聊前 `mInputShown=true / mImeWindowVis=3`，打开后 `false / 0`；final-task-from-ime.xml 确认两组及关闭入口。AndroidRuntime `-d -t 240 -s AndroidRuntime` 采样无输出，runtime PID15474；dumpsys 确认已安装 v18 / 0.1.17 / min30 / target35，恢复显示为 1080x2400、density420。生产源码 lastwrite 均不晚于 APK package 时间 18:31:33；本次 IME 结果限于打开任务浮层时收起键盘，不扩写为付费流式生成验收。

最终 APK：`../Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.17.apk`，**93,408,741 bytes**；SHA-256：`75aef8043afe6425b1075b56863579ac0d6ee2d7575ad15e0a83e7b0d8646d2c`。apksigner verify 成功，沿用证书 SHA-256 `52fc036fd6fe45d400f354235068aab29cb27ca6f68e42c53368a5b00c887b23`；aapt 确认 code18 / v0.1.17 / min30 / target35。构建日志 `../Temp_Android/build-0.1.17-final.log`，QA 脚本与最终截图见交付记录。

发送前本地原子提交、8 MiB 索引精确容量校验、64 MiB 有界完整恢复文件及保存失败重试已有实现；workspace-storage、assistant-stream 的阶段基线各 21 项。send-persistence harness 第四参数适配后复查 **13/13 通过**，日志位于 `../Temp_Android/qa/send-persistence-harness-recheck-20261011-183006`。附件启动恢复元数据、选择后读取文本，保留图片和托盘文件，并显式删除未使用项。harness 的图片/history/schema/请求校验及 provider 是离线替身，不代表真实模型或工具执行。

Linnaeus 已修复历史附件缺 catalog 时静默丢失的问题，改为严格失败并保留草稿；本次回报附件预检 14、chat 37、tools-vision 35 项通过。上述离线结果不代表真实付费请求或模型图片兼容性已验收，本轮未调用付费模型。

`get_weather` 已有固定 Open-Meteo 实现，通用网页搜索尚未接入。连接插件 0.2.0 冻结包准备与失败回滚已有实现/测试，但状态仅为 prepared，Android runtime loaded pending；DSH 移动插件有 fixture，runtime 尚未接线。SF 来源记录当前为 41 个 bold 符号，含 `trash.bold` 与新增 `bubble.left.and.bubble.right.bold` / `sf_groupchat.xml`，沿用同一固定数据集；[图标来源](docs/icon-provenance.md) 由主 Agent 维护。本批不宣告原始清单全部完成。

## 0.1.16 历史基线

下方至文末保留 0.1.16 历史快照，其中“当前交互”“当前 APK”“最新”等措辞仅指该批次。工具仅保存提案、附件尚未传输及旧图标计数等说明由文首最新状态及对应专项记录覆盖；旧构建和设备结果不作为当前批次通过证据。构建/调试方法可作参考，不授权本次文档 worker 执行。

原生 Android 应用，使用 Kotlin / Jetpack Compose。当前版本 **0.1.16 / code17**，按 0.1.x 修订号递增。设计方向为 Apple Liquid Glass 风格，按 QWEA0/Liquid-Glass-Android 的材料与 Scroll Edge 思路适配 Android。官方轻引擎已接入 DeepSeek 文本对话、模型目录和公开思考流；DSH 执行仍为本地演示。DeveloperCenter 在线统计、部分设置数据和 Aries 配对/只读查询已接入各自服务。

## 当前交互

- 开屏进入轻量对话；输入框由 Compose 首帧轮廓保证可见，附件/发送/停止为圆形玻璃按钮。
- 模型与推理在一行显示；在设置中保存 API Key、主动获取服务端模型目录后，可选择真实模型发送文本。凭据经 Android Keystore 加密保存在不参与备份的私有目录。
- 左上圆按钮展开为工作目录侧栏，关闭收回原圆；默认应用私有 `Dafeiyu-Works/默认工作区`，目录中的对话本地持久化。
- 工作目录使用实际页面背景的边缘折射、RGB 色散与窄方向高光，移除宽内发光。无边框图标操作，底部合计 PC 与 Android 在线设备；数字是服务端心跳窗口内的设备数。
- 玻璃中心使用均匀低比例覆盖色保障文字可读性；扫码页复用同一折射链，镜头按钮单独压暗背景。减少透明度设置继续提供实色降级。
- 右上加号旋转为 X，菜单向左下展开，依次为新建会话、扫码、配对码；只有当前节点和群组的已验证成员状态才隐藏配对码。
- 主页与助手标识采用 DeepSeek 官方仓库中的鲸鱼路径；应用启动器与关于页保持应用图标。
- 新对话隐藏顶部主题，收到回复起始回执后显示输入主题。聊天列表从底部锚定，流式文本不逐 token 发起滚动。
- 单条回复支持折叠公开思考、命令、工具、子 Agent、搜索和引用。官方引擎的显示思考与默认展开初始为开启；快照本地保存，恢复不自动执行。
- 设置首页收纳运行管理、模型、插件与技能、权限、数据等；二级页只有根顶栏一个返回入口。
- 同一个标题从正文大标题真实移动、缩小到顶栏；说明文字随滚动渐隐。短详情页保留足够折叠距离。设置行无拖拽，点击后从该行胶囊扩大为页面，预测返回缩回原行。
- 顶部渐变模糊采样实际页面，返回按钮也采样同一页面。普通玻璃表面仍采独立共享背景。
- 按钮统一使用 `GlassMotion` 与 `applyButtonDeformation`：圆形运动边界、低幅形变、贝塞尔缓出归位；大输入框、页面与侧栏没有永久拖动。
- 附件通过系统照片/文件选择器和外部相机读取本次选择；扫码使用 CameraX 实时取景与本地 ZXing，支持灯光和相册。

API33以上为AGSL折射，31–32保留模糊，30降级为纹理/渐变覆盖。减少透明度和减少动态效果偏好生效。顶栏采样层与前景分离，避免捕获玻璃自身。所有效果是 Android 适配，不是 Apple 原生组件。

控件图标采用用户提供的 UsePreside/sf-symbols 数据，经 Fastly jsDelivr CDN 下载并核对固定提交Git blob；39个SVG路径转为VectorDrawable。该仓库是第三方转换项目，不能称为Apple官方仓库；DeepSeek 标识的固定 Git blob 和来源也记录在 [图标来源](docs/icon-provenance.md)。

通知/相机仅在用户使用时请求；照片与文件使用主动选择的URI，不读取整个相册。Shizuku/root仅检测可用线索，没有授权或提权接入。外部目录目前仅记录SAF授权引用，对话仍保存应用私有索引。

## 构建与交付

包名 `com.dafeiyu.mobile.preview`，最低Android11/API30，compile/target35，沿用Debug签名覆盖旧预览包。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\JDK21'
.\tools\build.ps1
```

工具链、缓存、下载、日志、APK、截图均位于父目录 `Temp_Android`。当前APK：

`../Temp_Android/Dist/Dafeiyu-Go-Mobile-0.1.16.apk`

最终构建、签名、版本与真机验证详情记录在 [交接](HANDOVER.md)、[构建清单](docs/frontend-build.json) 与 [0.1.16交接与验证](docs/frontend-0.1.16.md)。

### Android Studio

用 File > Open 打开本仓库根目录（包含 `settings.gradle.kts`），不需要新建项目或复制 `app`。Gradle 使用仓库 wrapper 8.11.1，Gradle JDK 使用 JDK 21。当前本机的 `local.properties` 指向 `Temp_Android/toolchains/android-sdk`，已有 API 35、Build-Tools 和 Platform-Tools；该文件不进入版本控制。应用保持现有包名 `com.dafeiyu.mobile.preview`、minSdk 30 和 targetSdk 35。Android Studio Run/设备镜像底层仍通过 ADB 通信，命令行构建和设备验证可以并用。

后续日常调试按用户要求使用 `G:\Android SDK\platform-tools\adb.exe` 与 Android Studio 的 `Medium_Phone_API_37.0` 模拟器。先执行 `devices -l` 确认 `emulator-*` 编号，再给每条调试命令指定 `-s`；默认不再连接用户手机。具体路径与验证边界见 [当前调试约定](HANDOVER.md#当前调试约定2026-10-11)。

启动模拟器：打开 Android Studio 右侧 Device Manager（或 `Tools > Device Manager`），点击 `Medium_Phone_API_37.0` 旁的三角形按钮。运行应用时在顶部设备列表选中它，再运行 `app`。本机 AVD 已配置 host GPU 渲染，重启日志确认使用 NVIDIA RTX 3070 Laptop GPU；CPU 使用 WHPX 加速。

## 文档

- [当前交接](HANDOVER.md)
- [流式协议、适配器与持久化](docs/assistant-stream-protocol.md)
- [共享玻璃交互用法](docs/glass-interactions.md)
- [Liquid Glass实现与许可](docs/liquid-glass-implementation.md)
- [图标来源](docs/icon-provenance.md)
- [权限与系统](docs/permissions.md)
- [推理档位示例](docs/reasoning-preview.md)
- [立项研究](docs/PROJECT.md)
- [桌面迁移矩阵](docs/research/DESKTOP-FEATURES.md)
- [DSHA代码研究](docs/research/DSHA-CODE-REVIEW.md)
- [Android限制](docs/research/ANDROID-CONSTRAINTS.md)

桌面1.7.3交接和DSHA commit `18eaee90616808455abb225a8ae8ce5ab37c7f40`为 DSH 后端研究基线；DSH 启动/执行仍是本地模拟。DeepSeek 文本请求已接线并通过离线协议测试，真实账户生成、IME 下持续输出和模型兼容性仍需实测。

最新玻璃材料、快捷菜单、配对成员状态与真实模型请求的接线和未验证边界见 [0.1.16交接](docs/frontend-0.1.16.md)；历史规则由最新版本记录覆盖。
