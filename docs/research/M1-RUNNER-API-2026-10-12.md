# M1 Runner API: Read-Only Research

Research date: 2026-10-12, Asia/Shanghai.
Production status: 0.1.19 frozen. No production edits, JVM/Kotlin/Gradle,
ADB, dependency installation, external script execution, or model calls.
Only this report and seven inert upstream reference files were added.
This is an API recommendation, not an implemented feature or test PASS.

## Method and Network Evidence

All successful GitHub discovery and reference requests explicitly used
`http://127.0.0.1:7890`. The proxy port was reachable.

- GitHub repository search for `agent usage limits pydantic` returned HTTP
  200, with an unrelated wrapper as its single result. This wrapper was not
  selected merely because it matched the search.
- Existing local research identified Pydantic AI and LangGraph, but its
  branch snapshots lacked fixed commit and license verification.
- Further repository search/commit/ref API requests encountered TLS
  handshake errors and HTTP 403. The ref endpoint response explicitly said
  the unauthenticated API rate limit was exceeded.
- `git ls-remote` through the proxy failed with TLS unexpected EOF.
- Reading each GitHub repository HTML page through the same proxy succeeded.
  The page's `currentOid` supplied the commits below. Every retained source,
  README, and license was subsequently fetched from its full commit URL,
  with HTTP 200. These are observed commits, not a claim about stable releases.
- Upstream files are inert `.log` evidence, not imported or executed.
  SHA-256 below was calculated from the saved response bytes.

## Fixed Candidates and Licenses

| Candidate | Repository | Observed commit | License |
| --- | --- | --- | --- |
| Pydantic AI | https://github.com/pydantic/pydantic-ai | `69ea1e57201ed59e7d30fbe4a7590d193acecce0` | MIT; copyright Pydantic Services Inc. 2024 to present |
| LangGraph | https://github.com/langchain-ai/langgraph | `799c2f04fd3a56186e24e9fb8bbf3568e543cfbe` | MIT; copyright 2024 LangChain, Inc. |

The complete upstream licenses are retained below. No source was copied
into application code and no dependency was introduced. Any later source
reuse must retain the applicable license and source attribution.

Evidence directory: `m1-runner-api-20261012/`.
The exact retrieval URL for each row is
`https://raw.githubusercontent.com/{repository}/{commit}/{upstream path}`.

| Saved evidence | Upstream path | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| pydantic-readme.log | README.md | 24353 | `2C00DF9B5978BE8CFA6C0109DA1E2ED08A94C849B49261CD67618A8612F9576C` |
| pydantic-license.log | LICENSE | 1100 | `BC0E89738F36944C8106E1947C3D69631F9C33B8F1BCB60EC4F89D677D2DFCF4` |
| pydantic-usage.py.log | pydantic_ai_slim/pydantic_ai/usage.py | 30129 | `BC3C13B9B1186B4C714A0041DB35C51A5CAE2E3C155D7EE6F109A6F3CF8AB924` |
| pydantic-agent-graph.py.log | pydantic_ai_slim/pydantic_ai/_agent_graph.py | 162581 | `2903DA5D99D87B01CBC9441EEFFB5E93A0F606B9E86B6994B455DA3B47339A64` |
| langgraph-readme.log | README.md | 6395 | `3A13E257AF1216962D53D72414A7F44AC1FC5CEA34D994BDFF60EA2A3B045933` |
| langgraph-license.log | LICENSE | 1072 | `D9BB52F2E3540D60FF50D8F0F5B6BA649B7FD346948C4C3D086C8AFB120763C7` |
| langgraph-loop.py.log | libs/langgraph/langgraph/pregel/_loop.py | 89530 | `0887F03BB3316B2741D45ED55D616C50A91A4BD99610C66C85B01DCCB6567338` |

## Actual Source Findings and Selection

Pydantic AI `usage.py:566-570,628-634` checks projected usage before the next
request/tool execution. Borrow this admission pattern. Do not copy its
counters: `:496-506` describes accepted agent-loop responses rather than all
physical provider attempts, excludes several retry/continuation paths, and
limits successful tool calls. M1 must debit issued failures and physical
model attempts, with atomic sibling/root admission.

Pydantic AI `_agent_graph.py:2382-2444` distinguishes text from tool calls
and normally prioritizes processing the tools. `:2596-2604` explicitly
chooses a final result or the next model request. `:2706-2718` preserves
tool-response history before returning an explicit final result. Borrow
explicit outcome and faithful call/result pairing. Its optional early
output strategy, resumable graph, native/output tools, retries, and
interrupted-history synthesis do not fit this initial child policy.

LangGraph `_loop.py:638-640,1846,2105` provides bounded graph steps and
out-of-steps state; `:358-400` tracks checkpoint namespaces. Borrow bounded
execution and scoped identity. Graph steps are not physical HTTP attempts,
checkpoint namespaces are not Native authority, and recursion limits do not
enforce the child tool allowlist or prohibit grandchildren.

Both candidates are Python orchestration implementations. Installing them
does not fit the existing Android Kotlin/coroutine architecture and would
add another runtime, dependencies, lifecycle, and packaging work. No Android
API/ABI or heap compatibility was established. Retain the existing typed
client and executor/provider contracts; adapt the inspected patterns.
The repository pages, READMEs, and implementation files establish the
relevant mechanisms, not a maintenance SLA or mobile performance proof.
Neither candidate supplies the proposed M1 byte caps or an OOM guarantee.

## Proposed API Boundary

Keep the model boundary typed and independently injectable:

```kotlin
typealias ChildGenerate = suspend (
    DeepSeekChatClient.Request,
    suspend (AssistantStreamEvent) -> Unit
) -> DeepSeekChatClient.Turn
```

This is a proposed signature only. The child runner receives this callback,
an executor that exposes only available web/weather tools, and a Native
execution context bound before queue publication. The callback adapter owns
an independent child client with the child Config. Do not share the active
parent client, receive the unrestricted parent dispatcher, or mint a root
budget when execution context is missing.

Use the existing BackgroundTaskRunner adapter for admission/progress and
BackgroundTaskResult(text) for a successful bounded answer. A bounded session
core should return an explicit Final/Failed outcome containing the current
turn's final text or a small safe error code. Ordinary coroutine cancellation
must propagate. A runner return is not itself durable task completion;
Engine publication and the pinned execution completion handle remain separate.
A typed failure mapping is needed because the current Engine collapses
ordinary runner exceptions into execution_failed.

Keep the parent's six-round compatibility adapter. Select the bounded runner
only for SUBAGENT; BACKGROUND retains its existing text behavior. Build each
next request with Request.copy and Turn.assistantHistory, preserving actual
call IDs, arguments, tool results, and public reasoning. Do not inherit parent
history, images, attachments, unsaved drafts, or credentials as prompt data.

## Success and Budget Rules

- MessageCompleted and Turn.completed mean a completed model turn, including
  tool-proposal turns. Success requires generate to return a completed current
  turn with no pending tools, finishReason stop, and nonblank final text, then
  a live epoch/deadline/budget check and successful durable publication.
- Earlier tool-round text and progress tails are not final answers. EOF or
  DONE without a finish reason, incomplete/error/cancelled turns, and limits
  must not become success. Tool failure can be followed by a truthful final
  explanation; it does not necessarily require overall task failure.
- Three attempts means at most three issued model requests, including failed
  ones. No new automatic retry feature. If attempt three proposes tools,
  reject before executing them because no final model request remains.
- Validate the whole batch before execution: batch <=2; issued tools <=4.
  Cache identical execution/call ID plus arguments without double execution;
  changed arguments fail. Cache hits still consume bounded model/proposal
  processing, and siblings share the Native root budget.
- Child Config: request 128 KiB; response 256 KiB; line 32 KiB; event 64 KiB;
  read timeout <=60 seconds. A read timeout is not a total execution deadline.
- Reserve 256 KiB per admitted model attempt without refunding failed issued
  attempts: at most three allowances gives a conservative 768 KiB model-body
  budget. Normalized event text cannot measure raw SSE framing bytes.
  Buffered read-ahead is distinct from logical reader consumption.
- Add an explicit normalized event-count cap before reducer accumulation;
  2048 per attempt and 4096 per child are discussion values, not approved
  constants. A separate cap for SSE frames/comments needs a reader hook.
- Bound reasoning, text, arguments, identifiers, tool envelopes, and retained
  history before growth/serialization. Model response bytes exclude tool HTTP
  responses and heap copies. Avoid validateRequest followed by another full
  encode. Do not silently drop required tool history to fit the request.
- Final <=16384 UTF-16 units. Conservative initial behavior is explicit failure
  on excess; truncation requires a truthful marker and surrogate-safe boundary.
  The roadmap's additional 32 KiB UTF-8 cap is a separate policy to confirm,
  not equivalent to the user's UTF-16 cap.
- Progress <=4096 UTF-16 units in an incremental bounded tail, reset by model
  turn, including correct surrogate handling across deltas. Emit only a small
  tail snapshot on the publication cadence. Do not build a second whole-session
  reducer or repeatedly join all accumulated text.

## Lifecycle Integration and Remaining Gates

Align with the runtime root/execution/deadline/epoch/completion contract in
`tools/tests/workspace-delete/M1-LIFECYCLE-NOTES.md`. Queue admission starts
the five-minute monotonic deadline. Expiration needs an Engine-owned wakeup
even when no worker/provider is available. A local deadline is a failed
timeout; user stop/config cancellation must not be relabeled timeout.

Pin parent waiting to one execution, not merely taskId or conversation.
Manual rerun cannot substitute its new result for an earlier parent request.
Keep accepted children independent of parent-wait cancellation, with their
original root budget and deadline. All model/web work descends from the
Engine execution job; no detached scope or additional child worker pool.

Newton's actual proposal could not be independently retrieved: the desktop
thread-list tool returned Invalid app tool request. The local lifecycle notes
have no verified Newton attribution. Therefore this report specifies the
interface alignment points, not a claim of completed Newton cross-review.

Future fake-transport acceptance must cover completed tool rounds, third-attempt
tools, malformed/incomplete EOF, pre-reducer limits, surrogate boundaries,
slow-stream deadline, failed-attempt charging, concurrent siblings, revocation,
manual rerun identity, storage failure, and cleanup. No M1 harness was created
or run. Device heap/OOM validation and any focused JVM command remain for the
main agent's separate scheduling. Parent usage completeness remains deferred.
