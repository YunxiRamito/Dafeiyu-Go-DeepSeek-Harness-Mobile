# GitHub 只读参考核验：2026-10-11

本记录仅解释已取回的原始文本。请求显式使用 `http://127.0.0.1:7890`，不使用账号、模型或下载执行器；外部源码均保存为惰性 `.log`，没有安装、导入、source 或执行。下表行号为相应 `.log` 的一基行号。摘要、大小、请求时间、实际 URL 与 HTTP 状态见 [参考回执](references-20261011-232646/summary.json)。

## 搜索结果与证据强度

- [搜索回执](github-20261011-232509/summary.json)：四次 GitHub repositories 搜索均 HTTP 200，查询为 LangGraph、QuickJS Android、nodejs-mobile、WAMR；每次保留五条结果及搜索 URL。
- 同轮七次 repository metadata 请求均 HTTP 403，原始响应为未认证 API 配额耗尽；没有取得对应许可证详情、release 或当前提交证明。不能把这些失败写成核验成功。
- raw 参考读取 13/13 HTTP 200。DSHA 三份文件固定到提交 `18eaee90616808455abb225a8ae8ce5ab37c7f40`；其他为 main/master 的观测快照，以本地 SHA-256 复核，不能称为固定 release 或最新稳定版。
- 星数只作发现线索，不表示安全、维护承诺或 Android 可用性。Android QuickJS wrappers 只检索发现，尚未审计 JNI 生命周期、取消、内存限额或许可证，不直接选用。
- 初次 metadata 脚本包含旧 WAMR 仓库归属路径，失败原样保留；raw 读取和后续重跑目标已按搜索结果改为 `wasm-micro-runtime/wasm-micro-runtime`。

## 可复用的实际设计

| 参考 | 已读到的能力与行号 | 本项目采用范围与限制 |
| --- | --- | --- |
| Pydantic AI `usage.py` | 496–505 定义 request/tool limits；566–570 在下一步前检查；628–634 检查预计工具数量；533 定义计数预检开关 | 借鉴「预计消耗先准入」与运行级累计预算。其 requests 注释明确不包含全部 provider/transport 重试，tool_calls_limit 指成功调用；本项目须另计物理请求与失败执行，不能照搬其计数语义。 |
| LangGraph `_loop.py` | 637–640 超步骤终止；1846、2105 从 recursion_limit 推导 stop；checkpoint namespace 出现在子图执行路径 | 借鉴有界循环与作用域身份。图步骤限制不等于禁止创建孙任务；本项目仍需 engine 执行边界硬拒绝、深度 1 与白名单。 |
| QuickJS-NG `quickjs.h` | 515 `JS_SetMemoryLimit`，521 `JS_SetMaxStackSize`，525 自定义 allocator，1193 interrupt handler，1225 module loader | JS 数据变换候选。API 存在不证明 wrapper 已接线；需禁 std/os/FFI/文件/网络、异步 job 与 host callback 有界，独立进程或 Android 隔离服务另行论证。 |
| Wasmtime `config.rs` | 643 fuel，788 epoch interruption，857 stack；1973 memory reservation，2056 guard，2121 growth reservation | 对照 CPU/取消/栈/虚拟内存预算；这些配置不是线性内存硬上限的替代品。默认大虚拟预留不直接照搬移动端，本轮未确认 Android 构建/包体/ABI。 |
| WAMR README、`wasm_export.h` | README 49、80 列 Android；header 172 pool allocator，738–740 instantiate 栈/host heap，746–750 extended memory，1371 terminate | 更轻的 WASM 评估候选。host heap 不等于全部 native 或模块内存；需要同时限制模块线性内存、宿主调用、线程与终止后回收。 |
| nodejs-mobile README | 15–19 描述移动 Node 适配目标；35–41 版本非 SemVer；44 起指向专用 BUILDING 文档 | native 路线候选，不是本项目 Node 24.18.0 的即插即用替换。README 提醒通用 Node 文档可能只适用于上游；完整依赖与 DSH 兼容性另验。 |
| Termux nodejs-lts `build.sh` | 6 版本 24.18.0，15 依赖，170 prefix，172 Android，174–178 shared cares/openssl/sqlite/zlib/system ICU | 对照现有 ELF 固定 Termux prefix 与依赖来源。此脚本只读为 `.log`，未运行；不能证明换路径或只改 LD_LIBRARY_PATH 即可启动。 |
| DSHA `RuntimeLauncher.java` | 23–57 统一 argv/ProcessBuilder；61–91 HOME/PATH/临时目录与预加载环境；102–108 启动路径 | 借鉴启动参数集中构造与宿主生命周期分离；这里使用 rootfs/proot 方案，不作为 native Node 已成功的证据，不复制开放 shell 到轻引擎。 |
| DSHA `WebProcessManager.java` | 315–383 活体/出生身份核验；579–643 停止与所有权屏障；651–711 按记录与核实身份发信号 | 借鉴 PID 复用、未知退出、停止代次边界；不照搬大段实现，不能按端口/名字或裸 PID 杀进程。 |
| DSHA `security-model.md` | 信任边界表、终端与插件、应急与进程事务章节 | 明确同 UID 代码/proot 不是独立沙箱，loopback TCP 也不自动限制为本 App。DSHA 开放终端/第三方插件策略不继承到本项目受限子任务。 |

## URL 索引

1. https://github.com/pydantic/pydantic-ai/blob/main/pydantic_ai_slim/pydantic_ai/usage.py
2. https://github.com/langchain-ai/langgraph/blob/main/libs/langgraph/langgraph/pregel/_loop.py
3. https://github.com/quickjs-ng/quickjs/blob/master/quickjs.h
4. https://github.com/bytecodealliance/wasmtime/blob/main/crates/wasmtime/src/config.rs
5. https://github.com/wasm-micro-runtime/wasm-micro-runtime/blob/main/core/iwasm/include/wasm_export.h
6. https://github.com/wasm-micro-runtime/wasm-micro-runtime/blob/main/README.md
7. https://github.com/nodejs-mobile/nodejs-mobile/blob/main/README.md
8. https://github.com/termux/termux-packages/blob/master/packages/nodejs-lts/build.sh
9. https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.java
10. https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/app/src/main/java/com/deepseekharness/app/runtime/WebProcessManager.java
11. https://github.com/DSH-APP/DSHA/blob/18eaee90616808455abb225a8ae8ce5ab37c7f40/docs/security-model.md

候选许可证、固定版本、漏洞与维护状态在采用前再核验。本轮只读参考足以支持下一批 Kotlin 有界会话设计，尚不足以批准引入原生解释器、运行外部脚本或交付 DSH runtime。
