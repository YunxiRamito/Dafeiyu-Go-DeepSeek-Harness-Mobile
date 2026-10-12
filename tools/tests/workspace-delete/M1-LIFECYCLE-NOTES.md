# M1 child lifecycle review

Reviewed: 2026-10-11, Asia/Shanghai. This is a read-only review of the
shared 0.1.19 sources, not an M1 implementation or test PASS.
Only this report was added. No production changes, JVM/Kotlin/Gradle,
ADB, device operations, external network requests, or paid model calls.
The user reports whole-app lint passed and main-agent device QA is in
progress; neither establishes the proposed M1 lifecycle semantics.

## Findings, in priority order

1. **P1: A parent wait is bound to a task record, not one execution.**
   `OfficialToolDispatcher.kt:36,53-84` retains `(taskId, engine)` and
   validates task ID, conversation ID and SUBAGENT kind. The task-page
   play buttons (`BackgroundTasksScreen.kt:176,225`) call
   `engine.enqueue(task.id)` again for an inactive task. Enqueue
   (`BackgroundTaskEngine.kt:121-136`) clears the old result and
   reuses the same ID. A collector may miss the first terminal value
   when a manual rerun publishes QUEUED/RUNNING first; it can then
   return the second execution's result to the first parent call.
   All existing ownership checks still pass. The engine correctly
   prevents overlapping runners for one ID; that does not prevent
   result attribution across successive executions.

2. **P1: Current ownership is insufficient for M1 tree budgets.**
   `BackgroundTaskModels.kt:9-32` has no execution ID, root turn ID,
   parent call ID, origin or budget handle. `OfficialToolDispatcher.kt:
   19,37-40,111,149-179` has a per-turn ledger and task counter, but
   the child runner receives none of them. `parentConversationId`
   is a browsing/display association, not a unique turn or authority.
   Minting a child budget when the runner starts would let siblings,
   retries and manual reruns evade the parent's consumed budget.
   A root budget must exist before the initial parent model request
   and survive parent cancellation while accepted children run.

3. **P1: Configuration revocation currently covers Key mutations,
   not the full M1 configuration contract.**
   `DemoRepository.kt:1403-1456` increments credential generation,
   blocks admission, cancels registered model clients and requests
   engine stops before saving/clearing the Key. Provider enable/order
   changes (`:385-416`) only notify availability; engine-mode changes
   (`:349-352`) only update preferences. Engine configure/notify
   (`BackgroundTaskEngine.kt:87-101`) pump queued work; they do not
   revoke an admitted runner. The runner checks credential generation,
   but no provider/engine policy epoch. The milestone document's
   provider/model/engine stop semantics are a requirement to implement,
   not evidence that these paths already stop work.

4. **P1: No engine wall deadline, including queue time, exists.**
   Parent waiting uses 15 minutes by default (`OfficialToolDispatcher.kt:
   29,71`); it neither limits nor stops child execution. The model
   client's default read timeout is 660,000 ms (`DeepSeekChatClient.kt:
   85-98`), an I/O read timeout rather than a total job lifetime.
   Queue admission can remain blocked indefinitely. Adding only
   `withTimeout` around the runner also mislabels its timeout as
   STOPPED because the engine catches all CancellationException
   at `BackgroundTaskEngine.kt:231-236`.

5. **P1: A child web session needs cancellation and bounded accumulation
   beyond the existing text-runner cleanup.**
   The current runner is text-only for both kinds
   (`DemoRepository.kt:596-648`), with no tools in its request.
   `taskClients` tracks model clients, not web calls. Web cancellation
   follows the caller coroutine (`OfficialWebClient.kt:306-353`), so
   M1 must make every child model/web round descend from the engine
   execution job. Final result checks occur after construction
   (`BackgroundTaskEngine.kt:225-228`); progress joins all accumulated
   text before taking the last 4,096 characters (`DemoRepository.kt:
   624-626`). These are not memory bounds for a multi-round child.

6. **P2: Boundary success means stop requests were committed, not
   that old transport work has drained.**
   `BackgroundTaskCredentialBoundary.kt:10-34` cancels clients first,
   attempts all stops after ordinary failures, and stays blocked on
   failure/cancellation. On success it clears blocked without joining
   workers. Engine stop/delete (`:139-162`) commit before canceling the
   job, so a save failure prevents that method's cancellation step.
   The existing credential wrapper compensates by canceling clients
   first. The boundary has no internal serialization; retain its
   current single-owner/busy orchestration when extending it.
   Do not interpret client.cancel(), boundary success, or a canceled
   waiter as proof that all callbacks and buffers have been released.

## What can be reused

| Existing behavior | Evidence | M1 implication |
| --- | --- | --- |
| At most two reserved/running engine jobs across both kinds | Engine `:180-190`; Models `:47-48` | Keep this scheduler; no additional child worker pool |
| Same-task rerun waits until old reservation is released | Engine `:126-127,257-259` | Keep serialization; add per-execution identity separately |
| Runner selected at RUNNING admission, not earlier reservation | Engine `:198-206` | A queued task can use updated runner configuration; revalidate ownership/policy at admission too |
| Screen/waiter cancellation does not stop engine work | Engine `:37,56-74`; Repository `:2365-2369` | Preserve accepted-child independence and its budget ownership |
| Recursive create/enqueue/unfinished await rejected inside runner | Engine `:43-45,110,123,63-73` | Keep marker and pass no task engine to restricted child executor |
| Durable queue/publication and small commits resist caller cancellation | Engine `:121-136,168-177` | Bind runtime authority within the same admission transaction; do not wrap network I/O in NonCancellable |
| Late ordinary success cannot replace STOPPING/closed status | Engine `:238-255` | Retain this precedence; validate execution and revocation epoch as well |
| Storage failure interrupts ended active work and pauses pump | Engine `:183,267-275` | Do not publish a successful completion handle when terminal save failed |
| Restore active tasks as INTERRUPTED; no auto continuation | Engine `:312-332` | Do not reconstruct lost M1 authority or replay paid work on restart |
| Close cancels jobs and schedules interruption asynchronously | Engine `:284-304`; Repository `:2488-2493` | Close is not a synchronous drain barrier or an OS background-service guarantee |

The private RunnerExecution marker follows ordinary withContext switches.
An independently created scope does not inherit it automatically. It is
defense in depth, not the child capability boundary. Restricted execution
must have only explicitly allowed providers, with execution-time rejection
of undeclared tool names; no detached runner or recursive engine access.

## Conservative minimum ownership contract

Keep store schema version 1, existing BACKGROUND text behavior, UI behavior,
engine concurrency and restore policy. Add a small runtime-only ownership
handle and registry; do not put credentials, model clients, Activities,
full histories or output bodies in that context.

Recommended Native-owned fields:

| Field | Purpose |
| --- | --- |
| rootTurnId and shared rootBudget reference | One identity/budget per authorized parent user turn; one distinct identity for an explicit manual standalone execution |
| origin, originatingConversationId, parentCallId | Distinguish PARENT_TOOL and MANUAL_UI; conversation is association only; manual has no owning parent call |
| taskId, executionId, engine instance | Uniquely bind one attempt, independent of the persistent record |
| depth, policyVersion, credential/config epoch | Native depth 1 and permission snapshot; never accept these from model arguments |
| monotonic admission time/deadline, revocation state | Queue time counts; wall-clock timestamps in the store are not live deadlines |
| local caps and bounded per-execution completion | Child caps subordinate to the shared root; completion represents that exact attempt |

Create the parent root before its first model round and inject the same
handle into its session/dispatcher and every accepted child. Budget
operations must be atomic and short; do not hold the budget mutex while
performing network I/O, saving a store, or awaiting children.

For parent-created `run=true`, bind the execution context and result handle
under the engine admission mutex before publishing QUEUED. Roll back a
binding if durable enqueue fails. Once queue commit succeeds, the engine
owns that execution even if the caller disappears before receiving the
enqueue response. This ownership must not depend on whether a parent
waiter registered in time. Revalidate at RUNNING, before each model/web
attempt, before accepting events, and before terminal publication.

Expose an explicit parent-owned submission path that returns an execution
handle. Preserve ordinary `enqueue(id)` as the Native manual path for
existing UI callers. A rejected parent-owned submission must not silently
fall back to manual/root allocation. A runner must not mint a budget
merely because an ownership lookup failed.

Keep the root handle alive while its parent or any accepted queued/running
child owns it. Canceling a parent wait, a parent finishing, or the parent
15-minute wait timing out does not refund/reset budget or detach live
children from the root. New user turns in the same conversation have new
roots; a canceled/completed parent cannot reacquire a free budget by
retrying the same execution under a new call ID.

Use the existing M1 planned limits as starting values, not implemented
guarantees: root tasks <=4; child rounds <=3, batch <=2, calls <=4; tree
tools <=16 and model physical attempts <=12; engine workers <=2.
Count failed and canceled attempts once they have actually been issued.
Count redirects/provider fallback as physical network attempts separately
from logical tool calls. Budget exhaustion must fail before the next
request. Cached identical call IDs do not execute or debit again;
namespaces include root/execution/call ID, and changed arguments fail.
Attempt/byte bounds are not a guaranteed monetary cost cap.

## Manual rerun and parent result rules

| Entry | Root and execution rule | Who may consume the result |
| --- | --- | --- |
| Parent create_subagent with run=true | Existing parent root; new child execution ID | That root/parent call's pinned execution handle |
| Same parent retries an execution, if later enabled | Same root and consumed budget; new execution ID; explicit retry/deadline policy | Explicitly authorized replacement handle; never automatic live-record substitution |
| User presses task-page play on a terminal/interrupted child | Fresh MANUAL_UI standalone root, new execution ID and bounded child policy | Task UI; no automatic result injection into the original parent |
| Parent creates run=false draft, then user presses play | Draft creation counts against parent creation quota; later manual run gets fresh standalone root | Task UI; an old draft owner does not grant runtime parent authority |
| Recovered/legacy task has no runtime context | No automatic network run; explicit manual execution gets fresh Native authority | Task UI; no reconstructed old parent waiter |
| Default model selection changes | Does not retarget an already pinned task.model by inference | Existing task/model association stays explicit |

The fresh manual root is justified only by an explicit trusted Native UI
action. A model-requested retry/new ID remains inside its original root.
Use the same global model/web/byte admission for manual and parent-owned
work; fresh manual ownership does not bypass global resource limits.
If manual network-child policy is not approved for the M1 implementation,
keep that path text-only or return an explicit unavailable error. Do not
infer network authority from SUBAGENT kind or parentConversationId.

Pin a parent waiter to
`(engine, taskId, executionId, rootTurnId, parentCallId)`. Prefer one
bounded one-shot completion per accepted attempt over collecting the live
task StateFlow. Complete it only after that attempt's durable terminal
publication; propagate a truthful storage failure if publication fails.
It must be registered before the child can complete, since submission and
later waiting are separate. Manual rerun may update the task's current UI
record, but cannot replace an old handle's result.

If retaining prior completion is impractical, return an explicit
`execution_replaced`/unavailable outcome when the pinned execution is
superseded. Never wait for the later execution and attribute its answer
to the original parent. Define delete/close as unavailable/interrupted
outcomes for outstanding handles. Bound pending handles and retained
summaries; release them when observers and engine ownership end, or after
the documented wait/deadline window. Reject admission at the cap rather
than silently evicting a still-valid waiter. Do not retain a full task
history or clone all stored results per rerun.

## Timeout, stop and configuration

- Start a child deadline at durable queue admission, with a monotonic
  clock; the proposed total is <=5 minutes including queueing.
  Queue expiration needs an engine-owned timer/wakeup even when no slot
  or provider is available. A timeout wrapper entered only at RUNNING
  misses this case. Runtime deadline registrations must also be bounded.
- Distinguish actual child deadline expiration from ordinary cancellation.
  A local child deadline maps to FAILED/task_timeout, not STOPPED.
  Catch its timeout before generic CancellationException and do not turn
  an outer user/config cancellation into a timeout. Serialize deadline,
  stop, delete and final commits; retain existing STOPPING/closed
  precedence, with a documented winner for simultaneous requests.
- Cancel all model/web descendants through the execution job and revoke
  authority before accepting any subsequent round. Keep explicit client
  cancellation as a prompt transport stop, not as permanent revocation:
  DeepSeekChatClient.cancel() (`:121`) cancels the active call and does
  not prohibit a future generate() call on that instance.
- Extend the current credential orchestration to actual capability
  changes: Key replacement/clear, disabling the bound provider,
  invalidating the bound model or switching the engine policy.
  Distinguish those from harmless provider order/default selection edits.
  A conservative first implementation can stop all child executions on
  a relevant capability change, without inventing per-provider migration.
- First close admission and advance/revoke the relevant epoch; then
  cancel transports/jobs and commit stop requests; then change config.
  A failed stop/save keeps admission blocked for explicit recovery.
  Existing Boundary.stop's blocked flag and cancel-before-save ordering
  can be reused. Keep one serialized mutation owner; no long joins while
  holding the engine mutex.
- Boundary unblock may allow new independent admissions once policy is
  safe, but old executions remain revoked and their permits remain held
  until actual cleanup. Never use blanket shared OkHttp cancelAll();
  cancel only the affected child calls. Coroutine cancellation can resume
  a waiter before an OkHttp callback fully unwinds; release callback/parse
  resources in their own cleanup path.

Parent cancelResponse currently cancels only the parent response job/client.
Preserve that behavior for M1 as planned: stopping a parent wait does not
stop accepted children. Explicit stop-child, its own deadline/limits, Key
revocation, and engine close remain separate reasons to end child work.

## Memory and session constraints

The minimum child runner should use the existing small configurable
DeepSeekChatClient limits: read <=60 seconds, request <=128 KiB,
response <=256 KiB, line <=32 KiB and event <=64 KiB, with cumulative
tree/model/history bounds enforced before reducer accumulation or
serialization. Smaller transport limits alone do not bound copies
retained across multiple rounds. Keep bounded progress tails rather than
joining full text every 100 ms. Pass only the explicit prompt; no parent
history, attachments, images or unsaved drafts.

Reuse a bounded session core with an executor allowlist limited to
web_search/browser/get_weather. Keep the parent rounds=6 default.
OfficialToolSession currently returns Unit and tracks all emitted tool
events (`:72-151`); the child needs an explicit final outcome and current
round body. Do not transplant the text-runner condition that the aggregate
snapshot must have no tool calls (`DemoRepository.kt:635-638`) into a
successful multi-round session. Unfinished last-round tool proposals,
budget errors and missing final text must not become task SUCCEEDED.

Engine's two jobs do not limit concurrent parent model I/O or the shared
web client's callback/parse work. Add shared global model <=2 and web
response/parse <=2 admission across parent and children; release parent
model I/O permits and actually discarded transient buffers before awaiting
children. Retained history and callback buffers keep their byte reservations
until their references/work have actually ended; waiting alone does not
make those bytes free. The current dispatcher
mutex includes network waits (`OfficialToolDispatcher.kt:96-138`); do not
reuse that lock as a tree budget or global permit lock.

The web 1 MiB bound (`OfficialWebClient.kt:330-346`) still allows buffer,
byte-array, decoded-text and parser copies. The task store encodes/loads
the complete bounded JSON (`BackgroundTaskJson.kt:13-35`,
`BackgroundTaskAndroidStore.kt:17-32`). Neither 24 MiB store capacity nor
the proposed 32 MiB temporary-byte reservation proves heap/OOM safety.
Do not add full-store output scans or content copies to owner lookup.
Use small metadata contexts and bounded completion summaries; source
inspection without heap/device evidence cannot certify an OOM ceiling.

## Focused acceptance to schedule later

No tests were executed during this review. Existing test source was read:
`BackgroundTaskChecks.kt:126-218,260-289,395-456,677-801` and
`BackgroundTaskAwaitChecks.kt:40-204`. It covers concurrency, ordinary
stop/delete, admission config refresh, immediate retry, storage failures,
credential stop ordering/gating, waiter independence and recursion.
That coverage must be retained; it does not supply M1 budget or
per-execution ownership evidence.

Use actual production sources with fake model/web/store, no paid network.
Add these deterministic scenarios to a future M1 exclusive harness:

| Scenario | Required observation |
| --- | --- |
| First attempt finishes; manual rerun publishes before parent collector resumes | Old parent gets first pinned outcome or explicit replacement error, never second answer |
| Same conversation, two user turns, same tool call ID | Separate roots/ledgers; no result or budget crossover |
| Two siblings race for last tree quota; failed attempts then new-ID retry | Exactly one admitted; issued failures charged; quota never reminted |
| Parent waiter canceled/times out while queued/running child survives | Child keeps original budget/deadline; parent releases only its waiter/resources |
| run=false draft played later; interrupted/legacy task manually rerun | Fresh explicit manual execution, no old-parent injection or automatic network continuation |
| All slots busy or provider disabled until deadline | Queued child expires without invoking runner; no leak/revival on later config refresh |
| Timeout versus explicit stop/delete/close races | Truthful terminal reason, no late success, exactly-once cleanup/completion |
| Key/provider/engine revoked before admission, between rounds, during web/model, before final commit | Old epoch cannot issue new request or publish success; unrelated roots/calls unaffected by targeted cancellation |
| Stop save fails or boundary callback/caller cancels | All possible stop requests attempted; admission stays blocked; revoked transports cannot restart |
| Runner config replaced while reserved, and after admission | Current admission behavior retained; capability revocation explicitly enforced for admitted M1 work |
| Detached scope attempts recursive task operations | Restricted executor has no engine capability; normal withContext marker rejection remains |
| Long reasoning/history, near-limit UTF-8/escaping, fallback/redirect attempts | Reject before oversized accumulation; actual attempts charged; progress and summaries bounded |
| Completion followed by rerun/delete/close; repeated canceled parents | Completion ownership remains correct; registries/permits drain within explicit bounded policy |
| Terminal save fails after model finishes | Parent sees storage/unavailable failure, never a falsely durable success |

There is no M1 harness or new runnable test command created by this report.
Any JVM/focused compilation or device verification must be scheduled by
the main agent separately. Pure file reads and SHA-256 checks below were
the only verification operations.

## Scope and input snapshot

Conservative production scope for a later authorized implementation:
runtime policy/budget/context + restricted executor + bounded child runner;
small engine admission/completion changes; session outcome/handle injection;
main-agent-only repository runner/config wiring. Keep store version 1 and
existing BACKGROUND behavior. No UI, workspace deletion, DSH/runtime,
shared glass bridge, serializer/parser overhaul or retry feature in this
review. If safe pre-accumulation limits cannot be met within these existing
boundaries, treat that as an M1 blocker instead of quietly enlarging scope.

Local references, relative to this report:

- [Engine](../../../app/src/main/java/com/dafeiyu/mobile/data/BackgroundTaskEngine.kt)
- [Credential boundary](../../../app/src/main/java/com/dafeiyu/mobile/data/BackgroundTaskCredentialBoundary.kt)
- [Task models](../../../app/src/main/java/com/dafeiyu/mobile/data/BackgroundTaskModels.kt)
- [Parent dispatcher](../../../app/src/main/java/com/dafeiyu/mobile/data/OfficialToolDispatcher.kt)
- [Session](../../../app/src/main/java/com/dafeiyu/mobile/data/OfficialToolSession.kt)
- [Repository](../../../app/src/main/java/com/dafeiyu/mobile/data/DemoRepository.kt)
- [Model client](../../../app/src/main/java/com/dafeiyu/mobile/data/DeepSeekChatClient.kt)
- [Web client](../../../app/src/main/java/com/dafeiyu/mobile/data/OfficialWebClient.kt)
- [M1 planned constraints, sections 4-7](../../../docs/ENGINE-NEXT-MILESTONES-2026-10-11.md)

SHA-256 of reviewed core inputs, taken before writing this report:

| File | SHA-256 |
| --- | --- |
| BackgroundTaskEngine.kt | `4451B3587DA1D51BC9B2EC39F14374C79201C0BBE013D7664780477CAB6A971F` |
| BackgroundTaskCredentialBoundary.kt | `AEC53C73CED95CA43172DDCC5116762BDABA6DCB689D7A299222AA82828220D6` |
| OfficialToolDispatcher.kt | `BE68DAFA0DFEBC73D8DBA8537331F9CA381F358E1675AEE6F0A0C00CC75C974E` |

Shared-workspace line numbers/hashes describe this review snapshot.
If another owner changes these inputs before M1 work, review their new
snapshot rather than treating these notes as implementation validation.
