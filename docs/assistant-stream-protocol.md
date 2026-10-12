# Assistant Stream Protocol

2026-10-11. Frontend data contract; real model transport and backend execution are not connected.

## UI And Repository Contract

- `ChatMessage(id, role, text, streamSnapshot = null)` retains three-argument compatibility. Its declaration now lives in `data/ChatMessage.kt`.
- Render `message.streamSnapshot` when present. `activityOrder` is stable first-seen order; resolve TEXT/REASONING by `messageId`, `id` (blockId), and `reasoningKind`, and TOOL/SUBAGENT/SEARCH/CITATION by their IDs. `messages`, `toolCalls`, `subagents`, `searches`, `citations`, `usage`, `source`, and terminal statuses are immutable display snapshots.
- `officialEngineShowReasoning` and `officialEngineExpandActivities` both default to true, load from SharedPreferences, and persist through their `updateOfficialEngine...` methods. Expansion is a UI initial preference; public-thinking policy is separately configurable on provider adapters.
- `sendMessage(text): Boolean` returns false for every rejection and true after accepting the user message. The composer owner clears draft/attachment UI only on true.
- New conversation titles stay `新对话` until the demo `MessageStarted` receipt. Demo titles use the first input subject; a future backend can replace this with a real topic receipt.
- Demo replies use `source = DEMO` and explicitly say they are local demonstrations. They do not inject imaginary commands, searches, subagents, or thinking.

## Adapter Usage

Create one adapter and one reducer per provider response. Feed SSE envelopes in transport order; SSE decoding, JSON parsing, reconnect/replay, and HTTP errors belong to the future transport.

```kotlin
val adapter: AssistantStreamAdapter = OpenAiResponsesStreamAdapter()
val reducer = AssistantStreamReducer(source = adapter.source)
adapter.accept(eventType, jsonPayload).forEach { reducer.apply(it) }
val live = reducer.snapshot()
val closed = reducer.finish()
```

`AnthropicMessagesStreamAdapter(allowPublicThinking = true)` follows the same API. Adapters assign contiguous local sequence numbers; ignored events consume none. OpenAI provider sequence numbers deduplicate ordered replay. The reducer independently buffers out-of-order normalized events, ignores duplicates, and records skipped sequence gaps on finish.

Supported mappings: response/message start, text and public reasoning delta/done, function/custom/MCP arguments, command proposal and explicit stdout/stderr/exit outcome, provider server-tool status/result/error, MCP approval request, web/file search status/results, citation offsets, cumulative token usage, completion, failure, and token-limit incomplete termination. Normalized `AssistantStreamEvent` also supports backend approval decisions, cancellation, subagent lifecycle/output, and tool output/status. These are explicit backend events, never inferred from prose or a tool name.

`ToolArgumentsDone` means READY, not process success. A finished model turn with client tool calls can retain READY or AWAITING_APPROVAL. Explicit results/outcomes establish execution success/failure; an ended stream without terminal activity events becomes INCOMPLETE. Local stop uses `MessageCancelled`. Provider errors do not grant permission to execute.

Only explicit provider text/summary/thinking fields enter reasoning blocks. Signature, redacted thinking, encrypted content, and other opaque payloads never enter the UI snapshot or storage. Reasoning metadata stores only presence flags. Unsupported media/event families are safely ignored; this is not a complete media Responses client.

## Persistence And Compatibility

WorkspaceStore retains index version 2 and adds an optional message `streamSnapshot` object with schema 1. It writes all snapshot collections, text/reasoning, status, activity order, citations including file/page offsets, usage, source, and sequence diagnostics. Existing v1/v2 messages without the field restore with null. Unknown/malformed activity schemas fall back to ordinary message text. Existing archive purge and AtomicFile paths remain in place.

Loading a non-final snapshot marks the display stream final and interrupts active messages/tools/subagents/searches with INCOMPLETE (`process_restarted` where applicable). It preserves READY and AWAITING_APPROVAL as historical display states; it never resumes execution. Existing 8 MiB index and message/count limits still apply; oversized indexes fail atomically and surface the repository save notice. No opaque reasoning, API keys, attachment URIs, or composer drafts are introduced into this index.

## Official Sources And Research Limits

The OpenAI Docs skill was followed for official-documentation search and retrieval attempts. On this host the web tool returned no usable source body; direct OpenAI pages returned HTTP 403 and Anthropic documentation redirected to a region-unavailable page. Do not interpret those pages as successfully fetched evidence.

Documentation targets for the backend owner:

- [OpenAI Responses streaming events](https://developers.openai.com/api/reference/resources/responses/streaming-events)
- [OpenAI Streaming API Responses](https://developers.openai.com/api/docs/guides/streaming-responses)
- [Anthropic Streaming Messages](https://platform.claude.com/docs/en/build-with-claude/streaming)

Official SDK source was successfully retrieved through GitHub's Contents API as the accessible first-party schema evidence:

- [OpenAI event union, generated from OpenAPI](https://github.com/openai/openai-python/blob/b7a1990353baec896ee5e04b40b9f2dc2925a4f5/src/openai/types/responses/response_stream_event.py). Also inspected `response_reasoning_text_delta_event.py`, `response_shell_call_command_done_event.py`, and `response_shell_call_output_content_{delta,done}_event.py`. These define public protocol fields, command text, output, and exit/timeout outcomes.
- [Anthropic delta union](https://github.com/anthropics/anthropic-sdk-python/blob/b4b7916deaf4e5570cd627b1ae7ee4394a4de39f/src/anthropic/types/raw_content_block_delta.py). Also inspected `raw_content_block_start_event.py`, `raw_message_delta_event.py`, `thinking_delta.py`, `signature_delta.py`, `web_search_tool_result_block.py`, and `bash_code_execution_tool_result_block.py`. Signature is explicitly opaque; thinking is a separate incremental field.

The listed commits were resolved during this run; retrieval used the repositories' main branch. Backend integration must confirm protocol availability for its selected provider/model and retain any opaque continuation data privately outside the display contract.

## Verification

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\JDK21'
.\tools\test-assistant-stream.ps1
```

Pure JVM compilation uses cached Kotlin 2.1.20 and org.json, without Gradle or an emulator. Checks cover reducer sequencing, terminal activity behavior, adapter payload mapping, public/opaque reasoning separation, tool proposals versus execution outcomes, full JSON roundtrip, process restart interruption, and actual WorkspaceStore file save/load with legacy v1/v2 messages. Android Context/AtomicFile are minimal test fixtures; this does not validate Android framework atomic-write or real device behavior.

No real HTTP model request, operating-system command, approval action, subagent launch, or search request is implemented here. No ChatScreen/MainActivity/FishComponents or other handover documents were edited by this task. The main agent owns UI rendering and final APK rebuild.
