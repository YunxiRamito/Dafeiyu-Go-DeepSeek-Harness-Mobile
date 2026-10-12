# 模型能力、推理档位与模拟附件预览

这一版只验证手机前端交互。目录中的所有模型都有 `isExample = true`，名称包含“示例”，使用 `demo-` 标识；它们没有被远端发现，没有核验账号可用性，也没有调用模型。示例来自本机安装包的静态声明，不能当成当前供应商服务能力的完整目录。

## 每个模型独立声明推理选项

`DemoRepository.kt` 的 `ModelChoice` 包含 `id`、`provider`、`name`、`reasoningOptions`、`defaultReasoningIndex` 和 `isExample`。每个 `ReasoningOption` 包含稳定标识 `id` 和显示文字 `label`。显示顺序由该模型的列表决定，索引只用于界面选中状态；它不是可以跨供应商复用的数值强度。

| 示例模型 | 声明的选项 | 默认选项 | 界面行为 |
| --- | --- | --- | --- |
| DeepSeek | `disabled` / 不思考、`enabled` / 思考 | 思考 | 展示二档思考开关或二档离散控件 |
| GPT-5 | `minimal` / 最少、`low` / 低、`medium` / 中、`high` / 高 | 中（索引 2） | 展示四个离散档位，不能停在档位之间 |
| GPT-4o mini | 空列表 | 索引 `-1` | 隐藏推理控件 |

DeepSeek 示例只演示思考开关，不把 GPT-5 的 minimal/low/medium/high 套在 DeepSeek 上。这里的 `disabled` / `enabled` 是预览状态，未实现请求参数映射。GPT-5 完整展示本地目录中非 null 的 minimal/low/medium/high 四档映射，默认“中”是前端示例选择；off/xhigh/max 在该条目中为 null，因此不展示。

当模型有两个选项时，控件只能选择索引 0 或 1。模型有三个或更多选项时，离散滑块范围为 `0 .. options.lastIndex`，内部刻度数为 `options.size - 2`，拖动结果需要舍入为有效索引。模型选项为空时不创建滑块。

公开状态和操作如下：

```kotlin
repository.modelChoices
repository.selectedModel
repository.selectedReasoningIndex
repository.selectedReasoningOption // 无推理支持时为 null
repository.selectModel(modelId)
repository.setReasoningIndex(index)
```

切换模型会重置为新模型的示例默认档位，不沿用旧模型的索引或档位标识。无效模型标识不会修改选中状态，越界推理索引会被忽略。模拟回复在发送时捕获模型与档位，所以回复期间切换模型不会改写已经发送的预览配置。

## 静态依据与适用范围

研究日期为 2026-10-10。本机 DSH 适配器包版本为 `0.2.1-alpha.1`；这里只读取已安装文件，没有网络请求或服务探测。

- `G:\DeepSeek DSH\node_modules\@deepseek-ai\dsh-llm\lib\types\types.d.ts:340` 定义 `LlmReasoningEffortInfo`，其中 id 是适配器拥有的不透明标识；`LlmModelReasoningInfo.efforts` 遵循适配器偏好的顺序，`defaultEffort` 可选。模型 `reasoning` 元数据也可选。
- `G:\DeepSeek DSH\node_modules\@deepseek-ai\dsh-api-session-controller\lib\types\types.d.ts:111` 定义对客户端公开的模型推理元数据。正式接入应使用每个模型实际公布的目录能力。
- `G:\DeepSeek DSH\node_modules\@deepseek-ai\dsh-llm-deepseek\lib\index.js:312` 声明 `thinking` 的 enabled/disabled 开关；该文件 451 行起还定义适配器自己的 off/low/high/max。1707 行在原生请求中把 off 转成 disabled，其余转成 enabled。预览暂未实现这些原生 effort 档位或请求转换。
- `G:\DeepSeek DSH\node_modules\@deepseek-ai\dsh-llm-pi-ai\lib\index.js:755` 的 `getSupportedThinkingLevels` 根据单个模型的 `reasoning` 和 `thinkingLevelMap` 过滤能力，明确排除映射为 null 的档位；不能只根据供应商名称生成固定滑块。
- `G:\DeepSeek DSH\node_modules\@earendil-works\pi-ai\dist\providers\openai.models.js:3` 导入 `data/openai.json`。JSON 为单行文件，`openai-responses.gpt-5` 条目包含 `reasoning: true`，以及 minimal/low/medium/high 的对应映射，off/xhigh/max 为 null。`openai-responses.gpt-4o-mini` 条目的 `reasoning` 为 false。

这些本地声明说明“按模型能力生成控件”在代码结构上可实现。正式上线仍需由实际模型目录和对应适配器负责可用档位、默认值与请求映射；预览代码没有实现这条调用链。

## 附件仅验证输入区布局和状态

`AttachmentPreview(id, label, uri = null)` 保留标识、名称以及可选的系统选择结果 URI。`attachmentChoices` 提供“示例图片.png”“示例文档.pdf”“示例代码.kt”三个占位项；这些预置项的 URI 为 null，没有文件内容或真实缩略图。状态层本身不打开图库、相机或文件选择器，不读取 URI，也不申请权限。

系统照片选择器、文件选择器或外部相机返回的 URI 可由界面通过 `addAttachment` 加入当前模式，用于本次进程中的预览。URI 只保存在内存，不获取持久化授权，不保存到磁盘；系统选择及缩略图解码由界面负责，尚未实现模型附件上传。

```kotlin
repository.attachmentChoices
repository.attachmentPreviews
repository.toggleAttachment(attachmentId)
repository.addAttachment(id, label, uri = null)
repository.removeAttachment(attachmentId)
repository.clearAttachments()
```

添加与移除只更新内存预览列表，按 id 去重，最多六项；空标识或空名称不会加入。发送支持正文、预览附件或者两者同时存在；只有成功通过发送条件后才清空附件，名称会写入该条用户消息，URI 不进入消息或模拟模型请求。模拟回复明确说明附件内容未用于对话或上传。生成中的发送被拒绝时、DSH 演示环境未就绪时，预览附件仍保留。新建对话会清空当前模式的消息和附件。轻量与 DSH 模式各有自己的附件列表，切换模式不会混合草稿。

运行状态、发送、停止生成、新建对话及 `composerGeneration` 接口保持原有用途。真实附件发送以后需要另行定义 MIME 类型、模型输入能力、URI 授权、文件大小限制与上传生命周期；本次没有为这些功能建立可误认为已完成的实现。

## 显示与动画偏好

状态层另提供可写的 `reduceTransparency` 与 `reduceMotion` 两个 Compose 状态，默认均为 false，供设置页切换和根界面消费。它们只表示当前进程中的前端偏好，不访问系统无障碍设置，也不持久化；具体透明度和动画行为由界面应用。
