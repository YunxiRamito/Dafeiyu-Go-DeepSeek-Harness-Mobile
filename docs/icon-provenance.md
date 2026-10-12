# 图标来源（当前补记 0.1.22）

## 0.1.22 秒表图标（2026-10-12）

新增 `sf_stopwatch.xml`，取同一固定 UsePreside/sf-symbols 数据集的 `stopwatch.bold` 原始路径，仅做方形 viewport 和居中平移。数据集 SHA-256 已重新核对为 `06320f4811b01150f14f389b340e365574318d0034f8a300ddc7f7a41033ef72`，未下载或自绘；当前选取共 43 个 bold 符号。FishGlyph 的 `timer` / `stopwatch` 映射用于运行指标。

## 0.1.19 归档图标（2026-10-11）

`sf_archive.xml` 改为固定数据集中 `archivebox.bold` 的原始路径，采用方形 viewport 与居中 group 平移；归档使用档案盒，删除继续使用 `sf_trash.xml`。来源记录新增 1 项，当前共 **42 个 bold 符号**。本地 `symbolSet.json` 沿用下方固定 SHA-256，未重新下载来源。

## 0.1.17 新增删除与群聊图标（2026-10-11）

当前来源计数为 **41 个 bold 符号**：既有 39 个与本批新增的 `trash.bold`、`bubble.left.and.bubble.right.bold`。资源 `sf_trash.xml` 用于附件删除，`sf_groupchat.xml` 用于主页子 Agents/后台任务入口；沿用 UsePreside/sf-symbols 同一固定数据集，保留 SVG path，只调整方形 viewport 与 group 居中平移。该计数是来源选取记录，不是 drawable 目录中所有 `sf_*.xml` 文件数量。

沿用下方 commit、Git blob 与数据集 SHA-256。2026-10-11 重新核对本地 `Temp_Android/sf-symbols/symbolSet.json`，SHA-256 与固定来源一致；没有重新下载上游数据。`third_party/sf-symbols/provenance.json` 已补齐两个新增条目。来源仓库为第三方转换项目，不是 Apple 官方仓库；NOTICE 只记录来源，不授予许可。以下保留旧版本记录，39 个历史计数由本节补记为当前 41 个。

## 0.1.9 历史来源

用户要求控件图标不自绘。本版通过 Fastly jsDelivr CDN 下载 UsePreside/sf-symbols 的 `src/symbolSet.json`，基线 commit `9c12b2ea060e397ac6c554338599ae13d8578666`。下载时使用 `@main`，随后计算Git blob `f334065489ab590808027d71907b4daea000f0f3`与固定提交元数据比对，确认数据一致。

从数据中选取39个bold符号，保留原始SVG path，仅做方形viewport和居中group平移，转换为Android VectorDrawable；没有重画控件图标。数据版本4.0，SHA256 `06320f4811b01150f14f389b340e365574318d0034f8a300ddc7f7a41033ef72`。

仓库 `https://github.com/UsePreside/sf-symbols`；CDN `https://fastly.jsdelivr.net/gh/UsePreside/sf-symbols@main/src/symbolSet.json`。这是第三方SF Symbols转换数据，不是Apple官方仓库。README与package未提供明确资产再分发许可；NOTICE只记录来源事实，不构成许可授予。正式发布前需要确认资产授权。

原始README、来源和hash记录在 `third_party/sf-symbols/`，NOTICE与README随APK保存于 `app/src/main/assets/third-party/sf-symbols/`。不分发整个数据集或字体。应用 `ic_whale` 为自有品牌资源。

上一版Apple/Swift DocC来源的Apache许可与历史资源仍保留在 `third_party/swift-docc-icons/`，当前FishGlyph主要映射SF转换资源。QWEA0算法的MIT许可是单独来源，不为SF图标资产授予许可。

0.1.9新增lock、lock.open、folder.badge.gearshape、viewfinder、flashlight.on.fill、flashlight.off.fill、number.square，分别用于沙箱、扫码、灯光和配对码。取景引导框为简单几何，控件图标仍使用原始路径资源。

## 0.1.16 DeepSeek 首页图标

首页与回复头像改用 DeepSeek 官方仓库 `deepseek-ai/DeepSeek-LLM` 的 `images/logo.svg` 中独立鲸鱼路径，Git blob `425494404f7e63f4909667003097511217664579`。由 XML 解析器提取最外层路径并转换为 Android VectorDrawable，保留原始路径与 `#4D6BFE` 颜色，使用鲸鱼部分的 56.24 × 41.36 viewport；未重画图形。资源为 `ic_deepseek.xml`。此品牌资产作为来源标识使用，仓库代码许可不被解释为商标授权。启动器与关于页仍使用用户提供的应用图标。


## 0.1.11 启动器图标

用户提供 `dafeiyu-hair-app-icon-white-rounded-4k.png`（4096×4096 RGBA；SHA-256 `cf50ecfca98b0096473635bb430332b1109765c73bef56e743c05be680620da4`）。仅使用 FFmpeg Lanczos 缩放到512×512 RGBA，没有裁剪、改色、加字或添加玻璃滤镜。APK资源：`app/src/main/res/drawable-nodpi/ic_whale_512.png`（167808 bytes；SHA-256 `36108e9ec59fbf7957ff34b90ee2c4215525a4c9a656692e15367ffa0a534bb3`）。`AndroidManifest.xml` 已指向此资源；原来的矢量`ic_whale.xml`保留但不再作为启动器图标。
