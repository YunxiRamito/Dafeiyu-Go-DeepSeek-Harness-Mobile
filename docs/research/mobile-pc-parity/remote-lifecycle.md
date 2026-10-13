# 远程工作区、附件与用户下载：固定 DSH 源码审查

2026-10-13。经本机 `http://127.0.0.1:7890` 查询并读取 DeepSeek 官方 GitHub；没有运行仓库脚本。实际标签为 `dsh-v0.2.1-alpha.1`，固定 commit `5badb15009ae1756c3afe0ae0cef1faafc290ccc`，与本机已安装 npm `@deepseek-ai/dsh-*` 0.2.1-alpha.1 一致。README 和 LICENSE 已读，许可证 MIT，Copyright (c) 2026 DeepSeek。保留已有运行组件的第三方声明；本适配调用安装包服务，不复制上游源码。

原始 README、LICENSE、源码和请求错误保存在 `../Temp_Android/qa/remote-lifecycle-research/`。最初猜测 `v0.2.1-alpha.1`、`0.2.1-alpha.1` 的 ref 均返回真实 HTTP 404；查询 tags API 得到正确标签与 commit。猜测 `src/registry.ts` 同样 404，contents API 确认 registry 实现在 `packages/workspace/workspace/src/index.ts`。没有把路径错误记录为“没有成熟实现”。

审查文件：

| 固定 commit 内路径 | 采用范围与实际边界 |
| --- | --- |
| `packages/api/workspace-controller/src/index.ts`、`src/commands.ts` | 实际 `workspace.delete`、`archiveSession`、`unarchiveSession` 和序列化命令；删除登记不删目录或会话。 |
| `packages/workspace/workspace/src/index.ts` | 真实 archive 集合、运行活动 waterfall、stop 请求和登记持久化。archive 先持久保存，再发停止请求；返回不代表所有执行已经退出。 |
| `packages/api/workspace-files/src/index.ts` | 实际 stat、版本、分页二进制读取；`readBytes` 允许权限范围内工作区外文件，所以远程产物必须另加会话真实 cwd 的 canonical 范围验证。 |
| `packages/client/file-upload/src/index.ts` | 实际 streaming upload、Agent scope、Host-minted receipt 和 prompt 绑定；禁止手机伪造 attachmentId 或把另一会话的 receipt 带入当前任务。 |

远程适配须在删除登记前持久保存名称、实际电脑路径、工作区顺序和会话归属；文件删除使用独立的明确确认，默认保留。运行中移除先展示停止／取消选择，停止后复读真实活动与执行状态，不以 `session.cancel.accepted` 或 archive 回执判断完成。恢复需重新验证目录并恢复持久归属；旧记录无可靠 archive 时间时不推断期限。

电脑生成产物只同步目录信息。用户点击下载后才准备加密文件传输，以真实文件大小、版本、分片和最终摘要验证；路径、设备身份、配对权限在每次分片前复核。超过 1 MiB 的下载沿统一四／八并发规则；不能把本机假 HTTP URL、普通网页广播或单流循环称为多线程下载。

本文件记录源码研究和设计边界，不是实现完成、Android 运行或公网验收证据。

## 固定原生归档删除及启动前保护

同一固定 DSH 的公开 workspace/session API 没有完整永久删除方法。后续适配核对安装包的真实 AgentHandle disposer、SessionStore、JSONL/SQLite 租约与 registry 事务，在保存 tombstone、停止/排空活动、更新索引及删除受控日志后才提交精确终态。不能只删除磁盘日志冒充生命周期完成。旧归档未观测到可靠日期时保持未知；新的真实 archive epoch 才开始30天保留期。手机只取得 scoped prepare ticket并独立确认，不传入任意本机路径或归档日期。

保护必须在 owning DSH CLI、插件 manager和原生服务构造之前装载，采用固定包的 `scripts/archive-preload.mjs` 与 `assertFixedDshArchivePreload`。readiness检查同一个模块实例、固定 DSH package anchor和固定 private lifecycle目录，不导入新的服务、不改 prototype、不写journal。只要 `archive-deletion.json` 存在，包括仅epoch/receipt的记录，就不能回退为无保护启动。损坏/未知日志、不同目录或旧包需保留原数据并拒绝；卸载驱动仍保留当前服务的 tombstone gate，重新绑定必须在任何save前核验同一日志。

Android 将经完整安装树验证的固定 Connect slot纳入受管进程租约，只接受固定 DSH_HOME、install anchor和archive directory；独立模型 worker不受此启动器影响。PC 启动器同样核对冻结tgz与实际安装树，并将Windows绝对路径转为file URL供Node `--import`。源码专项已证明控制顺序和固定 first-party类的文件事务，但真实Android预加载、PC生产安装、Host capability与公网删除仍需分别验证；不按源码文件或单测存在判断已交付。
