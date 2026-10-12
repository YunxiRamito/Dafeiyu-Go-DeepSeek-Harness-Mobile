# Android Linux 运行环境与下载源研究

2026-10-12，Asia/Shanghai。用户要求研究 DSHA 的实际发布实现；GitHub 请求通过 `http://127.0.0.1:7890` 执行，原始响应、README、许可证、源码和已验证 APK 位于 `../Temp_Android/research-dsha-20261012/`。没有运行上游仓库脚本。

## 固定来源与实际读取

- DSHA：`https://github.com/DSH-APP/DSHA`，发布标签 `v0.2.0-rc2`，固定提交 `8362bba747fcf84c19e436c3dbb594a0fc388a67`。HEAD `ee16a165ba1972d4a892825afd4669e689a62960` 只用于文件定位，不作为发布负载身份。
- 阅读发布 README、MIT LICENSE、THIRD_PARTY_NOTICES、`RuntimeLauncher.java`、`RuntimeNativeFiles.java`、`RootfsInstaller.java`、`ProotBootstrap.java` 和 `ContainerRuntime.java` 等源码。APK 下载后实际 SHA-256 与独立 Release `.sha256` 相同：`1fedf883d431af658d89c28b86d85974cc1d11b74e0c5d46ef3d3cf8650b1961`，286,371,170 bytes。
- PC 下载源复用本地同项目 `GitHubAccelerator.cs`、`BackendDownloadSource.cs`、`PackageDownloadEnvironment.cs` 的规则，MIT，读取源码及 LICENSE；本仓无 git，固定本次读取文件 SHA 在 `pc-source-hashes.json`，不是跟随未固定上游。后端 npm `/api/npm/` 与 Git `/api/git/` 转发取自实际进程环境实现，保留 TLS 校验和端点 CA。

## 确认的架构

DSHA 在 Android 运行内置 PRoot/可选 proroot，解包 Ubuntu 24.04 arm64 用户空间，并从 APK 的可执行 nativeLibraryDir 启动。Ubuntu 共用 Android 内核；它不是完整虚拟机，也不是系统权限隔离边界。PRoot 处理 guest 路径和动态 loader，避免直接把 Linux ELF 当 Android bionic 程序执行。仅有下载归档或 chmod 不等于运行成功。

发布 APK 资产分为 Ubuntu 基础、Ubuntu 工具、Python、Python 支持库、pnpm 和 DSH 运行包。基础包内实际含 `/usr/local/bin/node`；因此脚本环境可以复用基础及工具包，跳过约 122 MB 的 DSH 运行包。基础包约 85.8 MB 压缩，rootfs 约 294.7 MB，不带桌面；不删除 `/usr/share/doc` 及 common-licenses。其实际发布 SHA `b9fe8a0460bc0d0e96f1cec0d65d46c9ff71a10c6a8d05a390b732a81d30cef3` 与源码 descriptor 中的未优化输入不同，采用已验证 Release APK 的实际字节和内置 hash，不照抄 descriptor 的错误对象身份。

DSHA 的首次下载是整个已验证 APK（约 273 MiB）；本客户端再选择其中 Ubuntu/工具链归档，缓存复用，组件不会重复下载整包。没有把这个传输成本说成仅 85.8 MB 下载。独立下载包的发布需要另行提供可信固定 manifest。

## 复用边界与许可证

客户端使用 DSHA 分包安装、native 启动、guest bind/env 的架构思路，并读取固定发布归档；没有复制 DSHA UI、Shizuku、设备桥、WebView 或专有 proroot 二进制。DSHA MIT 全文保存在 `third_party/dsha/` 和打包 assets，Ubuntu/npm/Python 各自原许可在实际归档内保留。

DSHA 自己的 NOTICE 标明 Standard PRoot 二进制精确源码修订未验证、androidshmem 原始二进制来源未知。为避免扩大这条来源缺口，本客户端使用固定官方 Termux PRoot/talloc/android-shmem 软件包及对应 recipe、源码和许可证；实际版本/包 hash 由准备脚本生成清单记录，不伪称 Standard 资产已复现。PRoot GPL-2.0、talloc LGPL 及其他组件许可分别保留，不用顶层 MIT 覆盖。

下载逻辑按 PC 分流：默认国内 CDN，GitHub 镜像在前、官方兜底；npm 走 npmmirror；官方只官方；后端是独立选择，不混入 CDN 自动回退。GitHub API/uploads 不发往第三方镜像。后端使用私有 CA 和既有 SPKI pin、保留 HTTPS 主机校验，下载内容再按固定 SHA-256 校验。取消、字节上限、失败切源、未完成文件与提交分开处理。

## 验证边界

准备脚本实际执行后固定了官方 Termux PRoot `5.1.107.96`、android-shmem `0.7`、talloc `2.5.0`。PRoot 源码提交 `a179d3e8a4e045aaa1fb8cc3284f23509d96d353`，Termux build system 提交 `3e13ceaa08e462bb3e0eb6529135ad67f9a3a9c5`；DEB、五个原生文件及完整源码归档 SHA 保存在打包的 `linux-runtime/runtime-lock.json`。源码归档和固定 build system 随 APK 提供，许可证按文件保留；未声称完成了二进制可复现重建。首次准备脚本下载 Termux build system 时遇到 `curl (35) schannel: failed to receive handshake`；随后校验研究阶段已下载归档的固定 SHA，复用本地缓存重跑成功，未关闭 TLS 验证。

Termux PRoot 源码 `tracee/event.c` 表明 SIGTERM 被忽略，SIGQUIT 走 tracee 清理路径；取消使用准确 PID、应用 UID、父 PID、执行器路径和启动时间检查。Android shell 仅通过独立参数列表记录 PID 后 exec 固定启动器，不把模型脚本拼进宿主 shell。该信号策略的源码依据不能代替 arm64 实际进程验证。

代码阅读、下载及 hash 校验已经执行。运行环境实现、严格编译、离线检查和设备脚本执行分别记录在 HANDOVER.md；本文件不把研究成功写成 Android 执行成功。DSHA 发布是 arm64，当前 QA x86_64 模拟器不能执行 arm64 Linux native 链路，不通过假状态绕开架构检测。
