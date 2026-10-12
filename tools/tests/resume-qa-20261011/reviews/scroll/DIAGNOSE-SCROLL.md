# 0.1.19 scroll QA diagnosis

Updated: 2026-10-12, Asia/Shanghai. Scope: exclusive QA only; no production edits, ADB, JVM, network, model calls or device writes by this worker.

## Evidence and expression review

Failed run: `runs/activity-019-b2e24879535048608476476508124a89`.
`result.txt` records chat-navigation scene rejection and verified complete restoration.

Expanded XML 002 through 019 has the same actual list bounds `[0,231][1080,1854]`. All 45 list descendants retain the same semantic attributes and positions. The list subtree in 019 is identical to 002, while the full 019 dump loses navigation, run info and composer siblings. PNG 018/019 show those controls and the same chat positions, with external clock differences. XML and PNG are sequential, not atomic. This supports a partial-dump hypothesis but does not establish absence of real scene drift.

The QA expressions select the largest vertical scroll surface above the editable composer; horizontal metrics and metric-bearing ancestors are excluded. Direction remains consistent with the production `LazyColumn(reverseLayout = true)`: earlier is a downward gesture. Unchanging list bounds alone would not prove no movement; unchanging descendant coordinates/content across 17 gestures provides the stronger evidence here.

The center start is `(540,556)` at 20% of the list height. It falls on `[63,556][1017,685]`, a clickable file-header parent with a separate description child `收起 qa-output-a.txt`. The description child itself is not clickable. Logs retain both nodes rather than conflating their attributes.

Read-only production references: `ChatScreen.kt` uses reverseLayout and horizontal 24dp list padding; density420 gives 63px, matching the occupied right edge1017 on width1080. `GeneratedFiles.kt` has a clickable file header and nested selectable/scrollable text. This identifies a possible child interaction lane; it does not establish that a specific production modifier consumes this gesture. No production workaround is proposed without the Einstein/Euler root review.

## Implemented QA behavior

`QaCaptureChat`: at most three acquisitions per scene, including an optional existing first XML. Raw Capture obtains one fresh XML and PNG without nested retry. Each candidate must pass unchanged navigation/title/composer/current-metrics assertions. Rejected frames are retained and logged. Sidebar or generation evidence fails immediately. No input occurs during retries. Three rejected frames stop the transaction and invoke restoration.

`Swipe`: default center geometry and 400ms duration preserved. JSONL logs actual surface bounds, from/to, lane, start-hit descendants, pre-gesture surface SHA256 and command success.

`-DiagnoseScroll`: validate collapsed state; expand and require the earlier-round sentinel; center swipe and recapture; derive a distinct right padding candidate from the newest validated XML; padding swipe and recapture; sample memory; return through the existing full restoration finally. Exactly two swipe commands, no Find or extended search. Exceptions also invoke finally. No full activity PASS is emitted.

The right candidate needs an observed blank strip at least8px wide between semantic descendant right bounds and list right edge. The saved failing frames yield x1048 versus center540. Unknown or occupied corridor rejects, with no hard coordinate fallback. Accessibility does not expose every gesture consumer; this is an experiment, not proof of an inert hit lane.

Default complete runner retains 24 frames per direction and both directions. Fixture size and XML cap remain unchanged. Screenshots are not batch decoded.

## Actual main-agent diagnostic result

Run: `runs/activity-019-f97afe6f64654eaab269c7b3f3e2c8e2`, started 2026-10-12T00:18:22.6722273+08:00, serial `emulator-5558`. Main reports exit0; the actual result log records both gestures, diagnostic completion and restoration without a FAIL entry. `gate.json` records version0.1.19/code20 and matching host/installed SHA256 `8830c7789bcee3275d952197ebb3c619a8499535e18678e9ac697f4dfbb7e6d7`. This identifies the diagnosed APK, not a subsequently repaired APK.

Both gesture records have `command_succeeded=true`, bounds `[0,231][1080,1854]`, y556 to1529, earlier=true and duration400ms.

| Gesture | Actual from/to | Start-hit evidence | Chat subtree result |
| --- | --- | --- | --- |
| Center, 00:18:38.1103667 +08:00 | `(540,556)` to `(540,1529)` | Clickable parent and separate description child `收起 qa-output-a.txt`, both `[63,556][1017,685]` | Unchanged |
| Right padding, 00:18:42.2870917 +08:00 | `(1048,556)` to `(1048,1529)` | Empty start_hits | Changed |

Center before/after and right-padding before SHA256: `cbd1c02e25b7e3e2694e86468f0a3da522ded72d273e98abb25accb70cdc2154`.
Right-padding after SHA256: `6a048a648f7fca2af8bcf2a3a147dfda0710849991f1a13bb8a037689e6ce1b3`.

Actual XML evidence: `002-diagnose-expanded-scene-0.xml` and `003-diagnose-after-center-scene-0.xml` both place the earlier-round sentinel at `[95,346][1017,383]` and final reply at `[63,420][1017,535]`. In `004-diagnose-after-right-padding-scene-0.xml`, the earlier-round sentinel is at `[95,1742][912,1779]` and final reply at `[63,1816][1017,1854]`. This verifies content movement after the padding gesture rather than a clock-only hash change. All four scene captures are attempt0; no scene recapture occurred in this run, so it does not prove the intermittent incomplete dump is resolved. Matching PNG files are preserved for visual review.

`result.txt` records diagnostic-only completion, full workspace/task/output root restoration with complete membership/SHA256/original absence, and the restored App stopped. The App-only credential-family absence gate passed; no whole-device credential assertion is made.

Primary actual logs: `runs/activity-019-f97afe6f64654eaab269c7b3f3e2c8e2/result.txt`, `swipe-log.jsonl`, and `gate.json`.
Result log SHA256: `1fe028535cf78da165f5123afd48eb2719ffed56e94a4a73d58ece3f02ae2b60`.
Swipe log SHA256: `d71dbfe1e301012279969f57482e2c81bda1f75b3bbeaf10159a49d9f1955c87`.

The controlled x change supports the file-header gesture-lane hypothesis and shows the list can move with the same direction/y/duration. It does not independently identify the consuming modifier or certify the full activity matrix. Main has now authorized Einstein's local production fix exposing FishButton `consumeDrag` and passing false for GeneratedFiles headers. That authorization is reported by main; this worker has not implemented or verified the production fix.

The next repaired APK must use its new verified hash and reproduce the default center gesture on the same fixture before full activity QA. Require changed chat content coordinates after center and the complete restoration evidence. A further `-DiagnoseScroll` run still begins with default center for comparison; full QA should then use the normal runner without that switch. The diagnostic padding lane must not become the default path or substitute for center-regression proof.

Historical note: the first diagnostic-result update changed only README and this review, leaving the then-old freeze unmatched. Main subsequently authorized offline/refreeze. The latest empty-bounds repair below has its own offline result and freeze; consult the current manifest.

## New APK center success and minimal empty-bounds repair

New APK SHA256 `c7e3320b4c483c05be842caa62f8f1158b9502ad6870a8ab14a5e5c7cf8a41f3`, size91,404,408. Main's `runs/activity-019-67829cab2b7744e79e4c4f273eaa5867` binds this artifact. The unchanged center gesture now changes the surface hash from `cbd1...` to `ebc547...`. XML002/003 places the earlier text, final reply and expanded file A header923px lower. The header remains `收起 qa-output-a.txt` and exact preview remains present; PNG corroborates actual scrolling without a title toggle. The later padding scan fails on an empty descendant, so that run is not a full diagnostic PASS.

The default-runner evidence `runs/activity-019-ce85fece8aba414faf508540b4b2b60d/result.txt` records the center gesture and partial activity/image-receipt PASS, followed by `Empty UI bounds`, then verified complete restoration and stopped App. The corresponding `009-collapse-True-0-scene-0.xml` has no completion-time label. Its only empty node is a non-scrollable Button under an offscreen clickable parent. The actual list root is `[0,231][1080,1854]`; file A header is valid at `[63,1484][1017,1613]`. Replaying the original expressions identifies Swipe start-hit logging, after Find misses the label, as the failing bounds call. The raw frame does not support an empty completion-label match or an empty nested scroll candidate as this particular cause.

QA-only repair skips exact `[0,0][0,0]` descendants during hit logging and padding inference. Find iterates list-local matches and requires a tap midpoint inside the list/ancestor intersection, preserving strict Tap checks. Missing visible matches continue the same real center scrolling,24 frames per direction and two directions. Empty/offscreen nested scroll candidates are excluded only under a validated scroll ancestor; actual root, composer, run-info and malformed bounds stay strict. Five focused offline cases replay009 and derived same-name ordering/offscreen/nested candidates, including48-frame exhaustion without taps. Fixture, device transaction and cleanup boundaries are unchanged.

The optional file-title toggle regression was deferred under main's latest scope/time instruction. No title interaction was added and the first center gesture is unchanged. The actual collapsed label is `预览 qa-output-a.txt`. Full default-device QA on the frozen repair remains pending main execution; the new center success is already supported by device evidence and needs no padding workaround.

## Main-agent execution and interpretation

Run from project root with final verified APK path/hash and the actually owned emulator serial:

```powershell
pwsh -NoProfile -File ./tools/tests/resume-qa-20261011/VerifyFreeze.ps1 -CheckSourceContracts
pwsh -NoProfile -File ./tools/tests/resume-qa-20261011/VerifyActivity019.ps1 -Run -DiagnoseScroll -FinalReady -MainAgentOwnsEmulator -Serial emulator-5558 -ApkPath $apk -ExpectedApkSha256 $sha
```

Review gate.json, every rejected and accepted XML/PNG, swipe-log.jsonl, surface-before/after entries, meminfo and result.txt restoration evidence. Different child positions after only padding would implicate the original gesture lane; unchanged positions after both requires further hit-test/scroll-range review. Partial dump recovering within three attempts does not convert failed historical QA into PASS. If the new layout exposes no safe padding, retain the rejection and inspect evidence; do not weaken the selector.

At preparation time Einstein/Euler conclusions were unavailable to this worker; app thread listing returned an invalid-request error and no corresponding local review artifact was found. Main has since reported the production fix authorization above. Detailed root-review artifacts and the new APK center-regression result remain to be linked before certifying the fix. M1/console encoding review remains paused.
