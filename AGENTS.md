# Agent 工作规则

适用于本项目的主 Agent 和所有子 Agent。开工前先读本文件，再读 [HANDOVER.md](HANDOVER.md)。改液态玻璃前必须读 [docs/LIQUID-GLASS-SPEC.md](docs/LIQUID-GLASS-SPEC.md)。需求原文在 [清单.md](清单.md)。

## 1. 磁盘与目录

- **不往 C 盘写任何文件。** 临时脚本、备份、日志、APK、QA 产物一律放 `G:\DeepSeek DSH\DSH Works\Project\Dafeiyu-Go\Temp_Android\`（相对本仓为 `../Temp_Android`），不要用 `/tmp`、`%TEMP%` 或 Agent 的 job 目录。用完清理。唯一例外：用户明确要求时，APK 可以复制到桌面。
- 仓库**没有 git**，改动无法回滚。改文件前先把原文件复制到 `../Temp_Android/backup-<版本>/`。
- 共享工作树里会有其他人的改动，保留，不要回退。

## 2. 改代码

- 用编辑工具逐处修改，**禁止用 sed/awk/正则批量改 Kotlin**。曾因此删掉过一整块逻辑。
- 构建（PowerShell 或 bash 均可）：
  ```
  export JAVA_HOME='C:\Program Files\Java\JDK21'
  pwsh -NoProfile -File tools/build.ps1 -MaxWorkers 2 > ../Temp_Android/<名字>.log 2>&1
  ```
  只编译 Kotlin 时加 `-Tasks :app:compileDebugKotlin`。默认任务是 `assembleDebug` + `lintDebug`，APK 会复制到 `../Temp_Android/Dist/Dafeiyu-Go-Mobile-<版本>.apk`，并生成 `.sha256`。
- **构建输出必须先写进日志文件再查。** 终端输出会被截断，只看得到 "BUILD FAILED"。查错误用：`grep -aE "^e:" <log> | sed 's/\x1b\[[0-9;]*m//g'`。
- 升版本时要同时改三处：`app/build.gradle.kts` 的 `versionCode`/`versionName`，以及 `tools/build.ps1` 第 39 行硬编码的 APK 文件名。版本号按 `0.1.x` 递增。
- 整仓构建同一时间只跑一个（build.ps1 会检查可用内存是否 ≥1536 MiB），也不要和模拟器启动叠在一起。

## 3. 子 Agent

- **用户自费，要控制成本。** 同时在跑的子 Agent 不超过 4 个。**每个子 Agent 的指令里都要写明“不得再派生子 Agent”。** 读文档、整理文档这类工作由主 Agent 自己做，不要外包。
- 派活时写清楚：文件所有权（各 Agent 之间不重叠）、具体要改什么、完成标准、备份位置（本文件第 1 节）。
- 子 Agent 的报告不等于事实。主 Agent 要对照代码核对关键改动，尤其是 shader uniform、数据层逻辑和“已完成”的说法。

## 4. 重大功能先查 GitHub

- 开始新的大功能之前，先通过本机代理 `http://127.0.0.1:7890` 在 GitHub 上找完成度高的现成实现，不要直接从零自研。
- 要读源码、README 和许可证，只看简介、星数不算研究过。研究记录写进 `docs/research/`，包括日期、仓库、固定的 commit/tag、许可证、复用了哪些部分、为什么。
- 引入依赖或复制源码时，版本要固定，许可证和出处要保留（见 `third_party/`），不要跟随上游主分支，也不要运行没审查过的仓库脚本。
- 代理或 GitHub 不可用时，把真实的报错记下来，不要写成“没有成熟实现”。

## 5. 设备与测试

- ADB：`adb`（在 PATH 里，也就是 `G:\Android SDK\platform-tools\adb.exe`）。每条命令都要用 `-s <serial>` 指定设备。设备 serial 每次先用 `adb devices -l` 重新查，无线调试的端口会变。
- QA 模拟器：独立 AVD `Dafeiyu_QA_API_37`（`../Temp_Android/qa-avd/`，通常是 `emulator-5558`），启动参数 `-gpu host -accel on`，不要退回软件渲染。用户自己的 AVD 不要动。
- 用户真机是 PKB110（无线 ADB）。只用 `install -r` 覆盖安装，保留数据。不清除数据，不替用户授予相机、照片、通知等权限，不读取其他应用的数据。
- 测试不要发起付费的 DeepSeek 调用，用离线数据或合成数据。
- 用户正在设备上测试时，不要去操作那台设备。

## 6. 界面原则

- 视觉方向是 Apple Liquid Glass，和现有组件保持一致。所有出现、过渡、消失都要有动画，优先用贝塞尔曲线（统一走 `GlassMotion`）。按钮的命中区域要准确。
- 动画不能阻挡触摸、滚动、取消或无障碍操作。要遵守“减少动态效果”、“减少透明度”以及个性化页里的几个开关（具体见 LIQUID-GLASS-SPEC）。
- 图标不要自己画，优先用 SF Symbols 转换来的现成数据（来源记录在 `third_party/sf-symbols/`）。
- 不要做只弹 Toast、没有实际作用的“假按钮”。功能还没接通的开关，标注“（预留）”并禁用。

## 7. 如实记录

- 实现、编译通过、离线测试、设备验证、用户验收是五个不同的状态，要分开写。没验证过的就写“待验收”。
- 不编造百分比、测试结果或执行记录。截图、脚本或目录存在，不等于测试通过。
- 用户判断有误时，拿出证据直接指出。
- 改动了其他仓库（PC 端 Click-To-Run、DeveloperCenter.Server、Connect-Plugin、Aries），必须写进 HANDOVER.md 的“跨仓库改动”一节。
- 交接时**只更新 HANDOVER.md**，不要再新建 `frontend-0.1.x.md`、`ITERATION-*.md`、`docs/changes/*.md` 这类按版本堆叠的文档。版本历史在 HANDOVER 里每版写一两行。
