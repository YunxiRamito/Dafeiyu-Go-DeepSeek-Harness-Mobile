# M1 Child Web Output and Activity Review

Date: 2026-10-11.
Status: read-only source review. No production edits, compilation, model calls,
Gradle or ADB. Android 0.1.19 production remains frozen.

## Decision

`BackgroundTaskResult(text)` and the existing parent await are sufficient for
the first delivery of a bounded child final answer followed by parent
continuation. They are NOT a child tool audit channel. They do not, by
themselves, establish that a child searched, which call succeeded, or where a
source came from.

The least UI work is to retain the existing task list, selectable result dialog,
collapsed agent receipt, and parent continuation. A result-only M1 can ship
without child tool rows if it clearly does not promise those rows. Final answers
should contain references from real tool results; model-authored URLs remain
answer text rather than verified tool provenance.

If M1 requires inspectable child web receipts, one bounded child-owned receipt
projection is necessary. Reuse current disclosure/source/copy presentation
later, with a small integration change; a nested timeline redesign is not
necessary. Frozen 0.1.19 production is not changed by this recommendation.

## Existing Source Evidence

Paths below are relative to the project root.

| Source | Observed behavior |
| --- | --- |
| `app/src/main/java/com/dafeiyu/mobile/data/BackgroundTaskModels.kt:33` | Result has text only; runner callback is also String only. Task stores result/progress and conversation ownership, not child call receipts or execution identity. |
| `app/src/main/java/com/dafeiyu/mobile/data/DemoRepository.kt:600` | Current independent runner calls the client directly without tools, declares no web capability, and rejects snapshots containing tool calls. Networking is not implemented by this runner. |
| `app/src/main/java/com/dafeiyu/mobile/data/BackgroundTaskEngine.kt:61` | awaitTerminal observes terminal state, checks task existence, forbids unfinished nested waits, and does not stop child work when the waiter is cancelled. |
| `app/src/main/java/com/dafeiyu/mobile/data/OfficialToolDispatcher.kt:53` | Parent await checks ledger, task kind, conversation and engine identity before/after waiting. Default wait is 15 minutes; result is capped at 16,384 characters with result_truncated. |
| `app/src/main/java/com/dafeiyu/mobile/data/OfficialToolSession.kt:100` | All sibling calls are executed/admitted before all waits are started. Results publish in original call order; parent history receives tool results before another model turn. |
| `app/src/main/java/com/dafeiyu/mobile/data/OfficialToolSession.kt:148` | Only awaited successful final text becomes SubagentTextDelta; no live child progress/web events are forwarded here. Completion notice is an application event. |
| `app/src/main/java/com/dafeiyu/mobile/data/AssistantStreamProtocol.kt:110` | ToolCallSnapshot has no owner task/run field; only SubagentSnapshot has parentCallId. Activity references have message identity, not child ownership. |
| `app/src/main/java/com/dafeiyu/mobile/ui/AssistantActivitySummary.kt:514` | Grouping uses parent snapshot round/reasoning boundaries. Linking an agent to its creation call does not link that agent's web calls. |
| `app/src/main/java/com/dafeiyu/mobile/ui/ActivityReceiptDetails.kt:140` | Valid actual web envelopes supply sources; prose does not. Agent details show public output and actual agent/parent IDs. |
| `app/src/main/java/com/dafeiyu/mobile/ui/BackgroundTasksScreen.kt:199` | Existing dialog shows prompt/progress/result in SelectionContainer. Result is plain Text; Markdown links are not rendered as interactive sources here. |
| `app/src/main/java/com/dafeiyu/mobile/data/BackgroundTaskJson.kt:10` | Every save serializes task result AND progress. No child tool schema is currently serialized. |
| `app/src/main/java/com/dafeiyu/mobile/data/AssistantStreamSnapshotJson.kt:48` | Parent snapshots serialize raw tool arguments/results and subagent output; importing child bodies here would persist them. |

This review does not rerun previously reported await/session/UI checks and
does not claim a new PASS result.

## Necessary for First Delivery

1. Keep independent child execution and existing parent await. Do not hand the
   child the unrestricted parent dispatcher/session: it advertises creation,
   file and task tools. Use a child web-only allowlist and actual bounded
   model/tool/model execution. Keep task-creation prohibition and existing
   credential/cancellation boundaries. A prompt instruction alone is not the
   execution boundary.
2. Continue returning one final answer String. Keep task success separate from
   individual web-call success: a child can explain a search failure in a
   successfully delivered answer. Conversely, a successful HTTP call does not
   make a missing final answer a successful task.
3. Keep ownership and execution identity for any child receipt channel:
   task ID, execution ID/attempt, parent conversation ID, original local call
   ID and child round/order. A task can be rerun with the same task ID, so task ID
   alone is insufficient. Ignore late events from old attempts, deleted tasks,
   replaced engines and obsolete credential generations.
4. Generate receipt metadata only from actual accepted tool execution/result:
   tool name, requested query/URL, real status/error code, validated returned
   source/final URLs, retrieval time and actual truncation flags. Publication
   time is optional. Do not derive events/sources from child answer text,
   thinking, progress labels, page instructions or mentioned URLs.
5. Preserve timeout/cancellation semantics. Await timeout is not task failure
   or task completion; parent cancellation is not proof the child stopped.
   Existing parent flow has no late-completion subscription after its await
   returns or is cancelled. The task screen can still show the later result;
   automatic late parent continuation is a separate feature.
6. Preserve final-answer truncation truth. Background result limit is 65,536
   characters, but parent await sends only 16,384. Its JSON marks truncation;
   agent public output receives the already clipped text without that flag.
   For M1, bound the child delivered answer to the parent relay limit using
   surrogate-safe handling, or propagate a small explicit truncation marker.
   Reference URLs should not be silently cut off at this boundary.

## Smallest Honest Receipt Integration

Preferred if inspectable child web calls are in the first acceptance criteria:

- Hold one bounded receipt collection per child execution, alongside text.
  Keep each call's original ID/order and actual lifecycle. Emit typed receipt
  updates separately from String progress. Extending BackgroundTaskResult alone
  is insufficient: the current engine reads/stores only result.text.
- Associate the collection with the existing agent/task disclosure. Child
  receipts remain child-owned; reuse current receipt formatting with explicit
  ownership visible. Avoid placing them in the parent top-level timeline.
- Reuse a single result projection. Do not emit BOTH synthetic Search events
  and Tool events for the same tool call just to obtain a search icon.
- If adapting through existing ToolCallSnapshot types, retain original local
  call ID separately from a collision-safe UI key. ID namespacing alone does not
  add ownership or child reasoning boundaries. Do not forward child sequence
  numbers, MessageCompleted, UsageReported or reasoning directly into the
  parent reducer. Its sequence and lifecycle belong to the parent run.
- A metadata-only receipt must explicitly say the body was not retained.
  Do not forge an official full-result envelope to pass compactWebData checks.
  When reusing the existing web renderer unchanged, provide the real validated,
  bounded web envelope. Any compact metadata format needs an explicit adapter
  and matching validation, not a made-up successful response.

Conservative fallback for result-only M1:

- Show the existing collapsed agent/result details and task screen.
- Include a small source index selected from actual tool results in the child
  answer/relay, with a clear distinction between answer text and tool metadata.
  If assembled by application code, keep that origin explicit.
- Do not advertise per-call child tool inspection until its data path exists.
  A truthful missing-receipt state is preferable to invented search rows.

## Memory and Persistence

Current bounds are not a full streaming memory guarantee:

- Tasks: 50 retained, 2 concurrent; input 32,768, result 65,536 and progress
  4,096 characters; task-store ceiling 24 MiB.
- Result length is checked AFTER runner completion. The reducer appends text
  without a text-size cap. Current progress code joins all text and then takes
  its last 4,096 characters approximately every 100 ms. This allocates full
  growing text despite the small published progress field.
- DeepSeekChatClient already has transport limits: default 16 MiB response,
  1 MiB event and 4 MiB request. The issue is not unlimited network input;
  those limits still allow much larger transient text than the 65,536-character
  task result cap, and repeated child rounds need an aggregate budget.
- Progress updates themselves do not call store.save, but later task commits
  serialize the latest progress for all tasks. Final progress is retained with
  result today, so do not use progress as a receipt JSON/event log.
- Final answer is also relayed into parent creation-tool result and agent
  output; parent snapshots serialize both. Do not add entire child snapshots,
  child history and per-delta event archives to that duplication.
- Existing official web bodies are bounded (browser text 12,000 characters,
  20 extracted links, search limit at most 8, reply at most 128 KiB). Repeated
  calls/rounds still need an aggregate child budget; individual limits alone
  are insufficient.
- Workspace index ceiling is 8 MiB with a recovery path capped at 64 MiB.
  These disk limits are checked after JSON/byte construction and do not bound
  transient encoding allocations.

Suggested M1 policy, NOT existing constants or committed implementation:

- At most 4 child model rounds and 8 actual web calls per execution; allow a
  tighter policy chosen by the data owner.
- Final delivered answer at most 16,384 UTF-16 units, with boundary-safe clipping
  and a truthful marker; retain only a bounded rolling progress preview.
- At most 8 receipt records and 64 KiB aggregate serialized receipt projection
  per active execution. Enforce limits while collecting, not only when saving.
  Cap strings/source counts individually; do not stringify giant payloads to
  measure after allocation.
- Persist only final answer, existing task status, and at most a 16 KiB compact
  receipt/source index per retained execution if history receipts are required.
  Body retention may be memory-only and cleared at terminal cleanup; reopening
  must then say body not retained. Keep no durable raw webpage/body/history log.
- Bound child model text/reasoning and aggregate tool-history bytes before
  reducer growth. Preserve tool-call/result pairing if history is reduced;
  never silently drop required protocol messages or fabricate continuations.

## Can Wait

Live child thinking/tool timelines; nested panels; per-call animation changes;
Markdown rendering in the task dialog; new copy buttons there; full raw webpage
archive/export; dedicated source-management screen; automatic continuation
after a timed-out/cancelled parent run; retry UI; aggregation of child token
usage into parent metrics. Do not count child usage as parent usage by forwarding
events. These can be designed after the bounded data channel is proven.

## Next-Stage Acceptance Checks

Not executed in this read-only review:

1. Two siblings reuse the same local web-call ID without collision/ownership
   loss; rerun/late old-attempt events cannot overwrite current result.
2. Real child search -> browser -> final answer reaches parent before its next
   turn; snippets, page body, final URL and retrieval time keep their identities.
3. Search failure followed by an explanatory final answer does not show a
   successful search. Mentioned URLs without execution create no receipts.
4. Failed/cancelled calls, child stop, parent wait cancellation, timeout,
   credential replacement and task deletion preserve their actual states.
5. Large streaming text/reasoning and repeated web calls hit collection budgets
   without constructing unbounded progress copies; truncation is visible.
6. Restart keeps small final metadata; discarded bodies are labelled unavailable,
   without synthesized IDs, timestamps, sources or success.
7. Existing parent ordering, default fold, final fold/reopen and receipt copy
   remain stable. Existing await/session suites remain regression gates.
