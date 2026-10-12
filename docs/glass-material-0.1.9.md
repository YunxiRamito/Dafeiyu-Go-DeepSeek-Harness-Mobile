# 0.1.9 玻璃材质研究：连续位移、低模糊与真实背景

记录日期：2026-10-10（Asia/Shanghai）。本文件是只读研究和建议参数，不是 shader 已落地或真机材质验收记录。本轮仅新增本文、下载研究材料；玻璃主实现仍是 QWEA0/Liquid-Glass-Android 的 MIT 圆角 SDF / 原生 Android AGSL / RenderEffect 路线，未切换 WebView、CSS 或 SVG 渲染器。

## 固定来源与许可事实

用户新参考：[中文效果说明](https://github.com/childrentime/liquid-glass/blob/345a3c4f3b07756239537f57ca88e29c0c04952b/LIQUID_GLASS_EFFECT_CN.md)。固定提交 `345a3c4f3b07756239537f57ca88e29c0c04952b`，提交日期 2025-08-04。研究副本位于 `../Temp_Android/childrentime-glass`，保存 commit.json、tree.json、中文说明、README、package.json、src/LiquidGlass.tsx、src/App.css 和 source-metadata.json（各文件 SHA256）。没有下载文中远程图片，也没有构建它的 React 项目。

该提交 README 第153行声明 MIT，但递归文件树共23项，**没有 LICENSE/COPYING 文件**；package.json 没有 license 字段，GitHub repository license 元数据为空。因此只能记录“README 宣称 MIT、完整许可文件未找到”，不能编造版权主体或完整许可证。当前仅研究通常的数学概念，不把这个项目的源码/图片/文章段落复制到 APK 或永久文档。QWEA0 已有的完整 MIT 与归属继续保留。

## 参考实际做了什么

`src/LiquidGlass.tsx:21–34` 是自定义平滑插值与圆角矩形距离场；69–76 用归一化中心向量和连续 scale 计算新的采样位置，97–155 将每像素位移写进 SVG displacement map；251–253 叠加阴影与 CSS 滤镜。中文说明39行的滤镜参数是 blur 0.25 CSS px、contrast 1.2、brightness 1.05、saturate 1.1。

这些网页参数不是 Apple 官方组件参数，也不是经过物理校准的折射率。CSS px、Android dp、RenderEffect 像素不能直接等同。其自定义 smoothStep 接受递减端点；移植思路到 AGSL 时应写 `1 - smoothstep(0, width, insideDistance)`，不要直接复制递减端点调用。默认示例的归一化半宽、半高、radius 是展示用固定值，不能用于任意长宽比手机按钮。

可借鉴的重点是：保留真实背景细节、用空间连续的向量场把边缘采样平滑移向中心、只使用很轻的模糊，利用边缘局部明暗而不是整圈实心白线建立体积。

## 检查时当前原生实现的风险

以下为研究读取时基线，root后续编辑会改变数值；不是断言最终APK仍如此。

- GlassSurface.kt:386 位移 `(11 + interaction * 8)dp`，按下最大19dp；389 RGB dispersion 从 .045 增到 .070。边缘色通道相对绿色偏移约 .50–1.33dp，红蓝间总间隔约1–2.66dp，高清文字边缘容易变色条。
- LensShader 当前沿 SDF 数值法线，`edge * edge` 随边界增强。圆角直边交接、接近矩形中心的法线退化、触摸径向扰动会改变向量方向；大位移叠加后存在方向条纹风险。不能仅凭源码认定 GPU 绘制出错，需真机图像验证。
- 当前 LivePageGlassMaterial.kt 的 blur 是 `3.5 * density` 原生像素，即3.5dp；与新参考0.25 CSS px的低模糊方向明显不同。强模糊损失文字细节，再强折射并不能恢复细节。
- LivePage 路径以控件尺寸record，传 margin=0；shader采样又夹紧到控件内部1px边界。较大偏移在边界被重复夹紧到同一列/行，会形成拉长条纹。首要修复是合适的外扩录制区域和对应坐标，而不是继续提高位移。
- LivePage最后绘制 .6dp / alpha .28 全圈白描边；Android View 路径也有 .7dp、浅色0xB0FFFFFF的完整描边。浅色模式尤其容易看成白色边框，和方向局部高光叠加。
- 未按压时正文中心若采样纯色，任何折射都不显形。这不是把中心再画白/画灰的理由。需要来源是独立真实内容层，并让信息在控件背后实际滚过；均匀背景上的玻璃本来就应安静。

## 建议 root 首轮参数

| 项目 | 首轮建议 | 实际边界 |
| --- | --- | --- |
| 原生RenderEffect blur | 0.35–0.6dp，先0.45dp | 不是照抄0.25原生像素；低端/减少透明度走现有降级 |
| SDF边缘作用宽度 | 8–10dp，同时限制到控件最短边1/3 | 和中心向量的连续权重配合，避免整片都被挤压 |
| 常态位移上限 | 3–4dp，按压最多额外1–1.5dp | 与当前11+8dp相比显著收敛；原始page尺寸不受改变 |
| 轻微中心放大 | 中心向量采样收缩0.8–1.2%，按压不超过2% | 位移按控件大小限幅，不在中心制造非零方向；等色背景仍无细节 |
| dispersion | 先0；确实需要再 .003–.006，按压最多 .008 | 最后再加色散，先验证单次RGBA采样即可达成光学效果 |
| 主题tint | 普通透明控件 .03–.07；密集文字菜单保守 .10–.18 | 主色实体操作/设置实色组件独立，不能统一改成透明 |
| 局部高光 | 边带0.6–1dp，常态幅度 .035–.06，按压额外 <=.02 | 保留方向性；去掉全圈额外白描边，避免shader高光+白线双叠 |
| 色彩调整 | 默认contrast/brightness/saturation全1 | 如真机必要，最多1.03/1.01/1.03；不照抄CSS整体增强 |

建议向量场（独立数学推导，不是复制新项目代码）：保持像素尺寸下的圆角SDF。令内部距离 `u=max(-d,0)`，边缘权重 `e=1-smoothstep(0,bevel,u)`；中心向量 `v=center-p`。以 `v/max(length(v),1)` 为边缘主要方向、以 `v * zoom` 形成很小的内域放大，再乘平滑权重与最大偏移限幅。中心处 v为零，完整向量场连续。若保留数值法线，只给局部轮缘很小混合比例，别用高幅法线位移作整块透镜。

触摸响应可用高斯距离权重，但首轮额外位移应 <=1dp，服从同一个最大位移上限；光学变化与共享按钮几何按压一起释放。减少动态效果保留静态透镜或现有关闭位移策略，勿重新强制开启用户禁用的运动。

## 必须同步修复的采样坐标

独立page GraphicsLayer来源必须不含当前控件/材质自身。录制控件范围外扩，margin至少覆盖最大位移+3倍blur+抗锯齿余量。以上建议通常可先用10–12dp。record尺寸变为 w+2m、h+2m，平移加入m；LensShader用原控件size和margin，绘制输出再偏移-m。sampleLo/Hi应包含安全外扩录制范围且不越出实际page来源，不要仍夹在控件内部矩形。边界TileMode只是最后保护，不能替代外扩真实内容。

当前原生链 `createChainEffect(lens, blur)` 和QWEA0向内折射链一致，不需要为这次参考贸然倒置。先减小blur、收敛位移和夹紧，再看是否需要额外色彩效果。

## 验证与声明范围

root最终应真机比较：白/黑主题、同一蓝色按钮/多行正文在玻璃背后滚动、圆按钮与长胶囊、按下/拖动/松开、减少透明度/动态。先确认模糊来源包含实际内容，再看边界错位/拉伸/色条。不能用新增文档或AGSL“已编译”日志替代截图和交互验收。

Android33+才有AGSL折射；31/32只有RenderEffect路径；30降级采样/主题材料。原生AndroidView特殊合成面是否能进入Compose层录制，仍需设备确认。上述新参数是建议，最后采用值及真机结论由root更新到交接文档与构建记录。

## 对比 shuding/liquid-glass（用户追加参考）

固定提交：`a2d2e847f793430e3409a52927af815a23f4d372`，提交日期 2026-03-26。临时副本位于 `../Temp_Android/shuding-glass`，包括 commit.json、tree.json、README.md、LICENSE、liquid-glass.js、liquid-diamond.js 与 source-metadata.json；四个研究文件都有SHA256。LICENSE 是 MIT，版权行为 `Copyright (c) 2025 Shu Ding`。本节以这两个具体脚本为准，没有将SVG、CSS或JS复制进移动应用。

### `liquid-glass.js`：CPU像素位移贴图，再交给浏览器合成

- `liquid-glass.js:14–26` 是 smoothstep 和 rounded-rectangle SDF；`217–245` 对canvas每个像素运行 fragment，算dx/dy、编码为RGBA，再 `putImageData`、`canvas.toDataURL()`，最后设SVG `feDisplacementMap.scale`。
- `:70` 浏览器CSS链是 `url(#filter) blur(0.25px) contrast(1.2) brightness(1.05) saturate(1.1)`。这是网页CSS参数，不能等同dp/native px，也没有理由整体照搬调色增强。
- `:155–179` 拖动事件本身只移动DOM位置；脚本通过Proxy检测fragment是否读取mouse。`createLiquidGlass`默认fragment（`:264–278`）没有读取mouse，因此一般拖动不需要重建整张位移图，shader地图保持稳定。用户自定义fragment若读取mouse，mousemove才调用CPU全图`updateShader()`。
- 适合借鉴：静态、归一化的位移场；SDF边缘平滑；拖动容器几何与内部光学图相互独立；没有必要为平移容器重新生成材料贴图。
- 不适合直接移植Android：CPU全像素循环、双精度/数组暂存、Canvas ImageData、base64 `toDataURL()`、SVG滤镜组成一个高开销路径。在Android GPU上继续使用原生AGSL/RenderEffect更合适，避免JS/WebView与CPU read/writeback。

### `liquid-diamond.js`：静态光线追踪算法，但姿态动画每帧重建位移图

- `:13–23` IOR=1.18、最多8次内部反射、edge bulge及高于1的Canvas DPI；`:324–348` 实现反射/折射；`:609–658` 对菱形各面进行多次ray trace；`:843–874` 遍历全幅像素并重建 displacement map。
- `:1040–1126` 拖动姿态改变theta/phi，调用`setFragment()`替换光追函数；`:1160–1165` 惯性动画逐帧调用`updateTransform()`，而`:893–895` 的`setFragment()`又排程canvas全图重建。它比普通liquid-glass的稳定地图贵得多。适合展示三维棱镜/钻石材质，不适合小型按钮、菜单或每帧移动的手机控制。
- 该脚本的多个HDR样式渐变、bloom、rim和face overlay也一起构成其钻石演示；不能将最终观感归因于单独IOR值。

### 给当前 Android 路线的决定

继续保留 QWEA0/Liquid-Glass-Android 的原生AGSL/RenderEffect作为主线。shuding参考强化了三个可借的工程概念：

1. **位移场与容器移动分离**：纯布局/平移不重建材料采样图；只有形状、作用点、交互强度变化才变uniform。
2. **中心向量连续、边缘有SDF权重**：平移采样向量可归一化，并通过边界smoothstep逐渐起效；中心位移应平滑到零，设置独立的最大offset，减少整面统一移动造成的条纹。
3. **避免CPU map路径**：保留AGSL直接sample，不做逐像素RGBA位图、data URI或WebView桥接。若uniform静止，复用shader/effect；拖动控制容器时不必因此更新fragment map。

这与childrentime文档都谈SDF与位移场，但本文只确认共同的通用数学构件，**没有证据证明两个仓库之间存在fork或代码派生关系**。两者的最终Android集成均不替代QWEA0许可证/NOTICE基线。

研究范围仅源码静态检查，没有运行网页demo、基准、Android性能测试或视觉实机验证。

## 最终交付状态（覆盖中途研究建议）

用户要求回退后，最终0.1.9继续采用QWEA0法线折射/色散；没有部署childrentime/shuding JS或SVG方案。菜单取材已修为与外部一致的模态blur/dim层；最终profile模糊3.5dp、菜单tint.55、外层blur2.8dp/dim.12，中途18dp/.84~.86试验不保留。按钮轮廓采用QWEA0双向光瓣思路约1.2dp亮边、迎光侧向内柔光，浅背景有微弱方向明暗分离，低API约1.15dp渐变轮廓。细节和未验证边界以HANDOVER及frontend-0.1.9为准。