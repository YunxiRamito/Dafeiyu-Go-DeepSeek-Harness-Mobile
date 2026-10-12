# Pauli Test / Network Audit Handoff

Date: 2026-10-11, Asia/Shanghai.
Owner: this continuation agent only. Do not edit these tests from another agent.

## Main-thread report

The Session search -> browser -> subsequent model request is covered by the
existing 27-pass production-source fixture suite. This continuation reran it:
27 PASS, 0 FAIL. The model connection and public-web responses are injected
offline fixtures; actual protocol serialization, dispatcher, parser, Session
and reducer execute. This is not a live model or emulator result.

The original Session test did not directly rebuild history for the next user
message. An exclusive supplemental test now covers that boundary using the
same production Session, OfficialChatHistory, serialized model requests, and
UI summary helpers: 4 PASS, 0 FAIL. It checks exact IDs/arguments/results and
reasoning, redirect final URL provenance, the final answer, and exclusion of
nonfinal/incomplete/cancelled/missing-result history.

Android production search may fail on a route where direct HTTPS cannot reach
the search providers. OfficialWebHttpTransport explicitly sets Proxy.NO_PROXY.
A successful probe through 10.0.2.2:7890 does not prove this transport succeeds.
This does not establish failure on the newly repaired/current emulator.

Historical Android direct probe:
- Bing RSS: HTTP 302, rejected by the standalone probe.
- DDG HTML / Lite: connection timeouts.
- Kotlin documentation page: HTTP 200 and content check passed.

Historical explicit-proxy probe:
- Bing RSS, DDG HTML, DDG Lite and Kotlin documentation: all passed.
- An older Bing ParserConfigurationException was in the probe's JAXP parser;
  production parsing uses jsoup. It is not evidence of that production defect.

Important limits:
- The direct probe disabled redirect following. Production follows validated
  redirects up to four requests, so Bing 302 alone does not prove failure.
- The probe used HttpURLConnection, a different User-Agent and parser, and
  shell UID. Production uses OkHttp with checked DNS; DDG Lite is not a
  production fallback.
- Logs predate the user's reported absolute-image.sysdir repair, cold boot and
  DNS 223.5.5.5. No fresh DNS/route/production result was collected.
- Android production smoke subsequently recorded search success and browser
  assertion failures. The main thread confirmed the example.com failure used
  an invalid body expectation: its title contained Example Domain while the
  extracted main text did not. The replacement KotlinDocs attempt returned
  an empty body, per the main thread. A known static official specification
  with a real body assertion is the next main-thread rerun. Browser success
  is still pending; neither failure alone establishes a production defect.
- Host selfcheck and DEX preparation do not prove Android execution.
- Existing Windows production live success is host evidence, not emulator,
  final APK or application-UID evidence. Ranking/relevance and live model
  tool selection/citation behavior remain unverified.
- Compose compilation and pure helpers passed; rendered layout, tapping,
  disclosure state and external source navigation still need device UI QA.

## Evidence

Paths below are relative to the sibling Temp_Android/qa directory:

- Original 27-pass evidence:
  official-web-focused/run-e6c8151ce6ff451d878e916f5b16256c/fixture-results.log
- Continuation 27-pass rerun and session request JSON:
  official-web-focused/run-0fa326b39af041c9ac9cf3ffa2a286bb/
- UI Compose compile, reveal helpers, activity summaries and web receipts:
  chat-stream-ui/run-92b581705db3446d8e0e2c5d5a4b437e/
- Exclusive supplemental 4-pass results, next-user request and artifact hashes:
  pauli-handoff-exclusive-20261011/run-e45efb0faa8a4ee7a78ddcfbfcfa2c41/
- Historical direct Android probe:
  android-public-web/run-8b4d459e77194650a6a2f9489a8670e7/network-results.log
- Historical proxy Android probe:
  android-public-web/run-ce72af414fc24b2792293f1debe71627/network-results.log
- Prepared production smoke:
  emulator-0.1.18/production-web-d3de12dd1eef46ebad0e4ee37bb9487c/
- Recorded activity failure and verified restoration:
  emulator-0.1.18/activity-018-11b0f8b60fc84e3384dd5b23e1b4e6af/
- Browser smoke with the original assertion failure:
  emulator-0.1.18/production-web-382ef477f99b4852a0a81a15911cf5ed/

## Activity runner readiness

The user transferred exclusive ownership of verify-activity-receipts.ps1 and
activity-qa-common.ps1 in the sibling Temp_Android/qa/emulator-0.1.18 directory.
The navigation fix is ready for a main-thread device rerun. No device rerun
was performed by this agent.

The failed grouped-expanded-True-2.xml shows the sidebar (close-sidebar label,
no composer/chat surface). FindVisible previously passed that frame directly
to SwipeList and reported "No chat scroll surface". This proves a missing
runner scene guard; it does not identify why the device entered that scene.
XML and PNG are captured separately and cannot be treated as an atomic frame.
Other saved XML shows unexpected generation and Tokens 2822 instead of the
synthetic Tokens 777777. The fix reports these drifts before further input.

Minimal runner/common changes:
- Validate conversation title, closed sidebar, editable composer, no generation
  and completed fixture metric sentinels before FindVisible or chat gestures.
- Select a vertical scroll surface above the composer; exclude horizontal
  metrics. Do not use header/composer/coordinate fallback.
- Allow up to three captures for a temporarily absent vertical surface.
- Resolve the composer to its actual EditText ancestor.
- Reject taps on disabled controls and send/stop controls or their descendants.

VerifyActivityRunnerOffline.ps1: 11 PASS / 0 FAIL on 2026-10-11.
It parses runner/common/prepare, replays saved XML, mocks every interaction,
tests bounded capture recovery and gesture bounds, checks forbidden buttons,
validates fixtures and tests installed APK path regexes. No ADB, Gradle,
network, model request or credentials were used.

Both installed APK regexes already accept "~". No change to
prepare-production-web-smoke.ps1 is necessary for that issue.
The smoke script does not stage application data: it uploads an independently
compiled DEX to a unique allowlisted /data/local/tmp path, runs shell
app_process and deletes that temporary DEX in finally. It therefore has no
application-data restore transaction; its result must retain the shell-UID
qualification even when installed APK version/hash are checked.
The activity runner still intentionally requires version 0.1.18. Do not use it
unchanged against 0.1.19; see QA-0.1.19-PLAN.md for the separate readiness gate.

## Restore audit

The failed activity result.txt binds the installed APK to SHA256
98D38E61F5ADF8A9785EE5CAF3CE4F29C65A8E46E1DECF01FC94173AA45D9B73.
It reports original index/recovery/task AtomicFile families and the output tree
restored, byte comparisons verified, originally absent paths still absent,
and the app left stopped deliberately.

The runner backs up and compares before mutation, restores items independently,
retains backups on restore failure, and refuses overwrite if final force-stop
fails. Restarting restored original queued tasks is deliberately avoided.
This is evidence for the listed transaction paths, not every app setting or
clipboard state. No new confirmed restore defect was found.

Remaining limits: directory comparison assumes the current relative layout,
checks ordinary directories/files but not symlinks/special files, and uses
find | sort without pipefail. These are robustness gaps; the recorded restore
does not establish data loss. They are outside the navigation fix.

## UI-stage focused preparation

Local commands from the project root; no Gradle, ADB, dependency download,
model request or credential read:

```powershell
& './tools/test-official-web-focused.ps1'
& './tools/tests/chat-stream-ui/VerifyActivitySummary.ps1'
& './tools/tests/pauli-handoff-exclusive-20261011/VerifySessionWebNextUser.ps1' -WebRunName 'run-0fa326b39af041c9ac9cf3ffa2a286bb'
& './tools/tests/pauli-handoff-exclusive-20261011/VerifyActivityRunnerOffline.ps1'
```

The supplemental runner reuses the selected successful web fixture JAR.
Pass the new run name after another web rerun; it does not rebuild that JAR.

Device-stage checklist for the main thread after final APK readiness:

1. Bind final installed APK/version/hash and exact production source evidence.
2. Run the already prepared production web smoke under main-thread device
   ownership, recording per-provider failures and browser outcome separately.
   Shell execution must remain labeled separately from application-UID QA.
3. Stage UI fixtures only through the existing main-thread backup/restore
   workflow. Do not use a live model or read preferences/credentials.
4. On search/browser success, manually expand the process disclosure; verify
   query, redirect final URL, provider, retrieval time and truncation labels.
   Source receipts render only after the user opens that disclosure.
5. Check failed, cancelled, incomplete and malformed results: truthful status
   and error text, no success wording or invented source receipt.
6. Check 320dp width, large font, long query/URL, wrapping and source tapping;
   final answer should not be duplicated in process history.
7. Check same-round grouping, cross-round separation and restore/reopen state.

No ADB was executed in this continuation. Production source, dependencies,
other workers' files and Gradle outputs were not edited. Changes are confined
to this exclusive directory and the two activity QA scripts explicitly
transferred by the user. All requests in the new tests are in-memory fixtures.
