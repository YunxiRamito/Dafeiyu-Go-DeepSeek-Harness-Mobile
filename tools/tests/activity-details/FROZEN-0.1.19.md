# Activity Receipts 0.1.19 Freeze

Date: 2026-10-11. Status: frozen after one focused Compose compilation and all
six JVM check entry points passed. No Gradle, ADB, paid model or full app build
was run by this worker. Shared files were not reverted.

## Production Scope

Only these production files were edited:

- `app/src/main/java/com/dafeiyu/mobile/ui/AssistantActivityFeed.kt`
- `app/src/main/java/com/dafeiyu/mobile/ui/AssistantActivitySummary.kt`
- `app/src/main/java/com/dafeiyu/mobile/ui/ActivityReceiptDetails.kt`

The feed consumes one interleaved timeline using real `activityOrder` and model
round references. Matching kinds still merge within a round and reasoning span;
every real reasoning block, including a blank public block, stops merging.
Unavailable stale references cannot invent reasoning boundaries. Legacy missing
content references are restored using known message/block order; missing event
timestamps are not reconstructed from model prose.

Linked children retain their parent creation position and parent receipt without
double counting. Existing call IDs, reasoning disclosure IDs, default collapsed
reasoning/rows, terminal autofold and manual reopen behavior remain represented.
Headlines use the latest public sentence without rewriting internal whitespace,
with a 512-character display bound and surrogate-safe clipping.

All activity headers share a 44dp leading slot, 17dp glyph, vertically centered
single-line text, 48dp minimum click height and 4dp vertical padding. Process
children use a common 2dp gap. File names and notices moved into the disclosure,
so they do not stretch one type of collapsed header more than another. Existing
glass animation and reduce-motion handling remain in place.

Multi-receipt rows render child labels without parsing result payloads. Each
child is parsed only after its own explicit disclosure. Full copy text is built
only when clicked. A tool envelope is reused for status, headline and detail
projection. Details retain actual IDs, parameters, results, partial/error
status, safe source URLs, dates, source/link separation and redaction.

## Memory Boundaries

- Timeline reasoning entries point to original blocks; they do not clone text.
- No accumulating snapshot/text cache was introduced.
- Public title: at most 512 characters plus an ellipsis.
- Display/copy text section: at most 16,384 UTF-16 code units.
- Web provenance envelope: at most 131,072 characters; depth at most 16 and
  256 structural containers.
- Generic detail JSON: at most 327,680 characters; depth at most 16 and
  4,096 structural nodes. JSON strings containing braces do not count as nodes.
- Tool search: at most 8 sources; browser: at most 24 page links.
- Search-event detail: at most 24 sources.
- Raw stream/history storage is owned outside these files and has not been
  changed. This focused result is not a guarantee that the whole app cannot OOM.

## Evidence

Output directory:

`G:/DeepSeek DSH/DSH Works/Project/Dafeiyu-Go/Temp_Android/qa/chat-stream-ui/run-cde7720e4a7b4f2a95d64b4a79e4359b`

One cached Kotlin 2.1.20 Compose compilation with `-Werror`, followed by
sequential JVM executions, all with `-Xmx512m`. TEMP/TMP:

`G:/DeepSeek DSH/DSH Works/Project/Dafeiyu-Go/Temp_Android/qa/activity-receipt-jvm-temp`

`java.io.tmpdir` pointed at the focused output directory. No test JVM remains.

- `fixture-results.log`: existing thinking/reveal/Unicode/motion checks PASS.
- `AssistantActivitySummaryChecks-results.log`: existing aggregation, real query,
  agent, status, history and lifecycle checks PASS.
- `AssistantWebReceiptChecks-results.log`: web status/provenance checks PASS.
- `AssistantActivityTimelineChecks-results.log`: 15 explicit cases PASS, including
  interleaving, hidden reasoning, reducer rounds, same-span merging, repeated
  block IDs/kinds, duplicate calls, legacy references, stale/blank blocks,
  late children, separated parent batches, shared text and terminal reopen.
- `ThinkingReceiptChecks-results.log`: 9 cases PASS, including source whitespace,
  quotes, partial text, versions, title limit and surrogate clipping.
- `ActivityReceiptDetailsChecks-results.log`: existing details/copy/security
  checks and added collapsed-title/missing-reference/oversized-web-redaction
  assertions PASS.
- Three focused PowerShell runner files passed PowerShell AST syntax parsing.

These compile against real feed/summary/details/protocol/reveal/motion source.
External glass glyph/deformation and Markdown signatures use compile fixtures.
No Android UI, pixel/baseline, clipboard service, device memory or physical
interaction claim is implied.

Frozen production SHA-256:

| File | SHA-256 |
| --- | --- |
| AssistantActivityFeed.kt | 60860840E57B659B6E8E8D69FDC57F5DD82EF298D68185DCC64D69F72D8F2C93 |
| AssistantActivitySummary.kt | 7377E422369A93138E0C293C4926A697E9C15EB325E667052391B0274E523763 |
| ActivityReceiptDetails.kt | ACD1E625CB8EF7763841E17DC04EE7507022025B91AA73E300F620D0140CDC2D |

## Commands

The executed entry point:

```powershell
$receiptTemp = [IO.Path]::GetFullPath('../Temp_Android/qa/activity-receipt-jvm-temp')
$env:TEMP = $receiptTemp
$env:TMP = $receiptTemp
./tools/tests/activity-details/VerifyActivityReceiptDetails.ps1 -TempRoot '../Temp_Android' -HeapMiB 512
```

An additional pure-model runner is prepared and syntax checked, but not executed
in this batch:

```powershell
./tools/tests/activity-details/VerifyActivityReceiptModel.ps1 -TempRoot '../Temp_Android' -HeapMiB 512
```

Both runners explicitly include production `ActivityReceiptDetails.kt`.
Existing `VerifyActivitySummary.ps1` was also fixed to include that dependency
and use the same maximum heap. These runners should be run serially.
