# 0.1.20 batch QA handoff

2026-10-12. This directory alone is owned by the QA worker. Existing runs, six
fixture files, production sources and historical freeze-manifest.json are retained.
No ADB/JVM/build is performed by offline preparation. Device/build ownership is
exclusive to the main agent. Source and APK identity must be checked after final build.

## Preparation and independent freeze

Run from the project root in PowerShell 7 with no profile. Offline emits a fresh
absolute result directory; use its result.json in the following freeze command.

```powershell
$qa = Join-Path (Get-Location) 'tools/tests/resume-qa-20261011'
pwsh -NoProfile -File "$qa/VerifyOffline.ps1"
$offline = '<absolute fresh passing offline directory>/result.json'
$freeze = "$qa/freeze-020-manifest.json"
pwsh -NoProfile -File "$qa/VerifyFreeze.ps1" -Freeze -OfflineResultPath $offline -ManifestPath $freeze -ExpectedVersion 0.1.20 -ExpectedVersionCode 21
pwsh -NoProfile -File "$qa/VerifyFreeze.ps1" -CheckSourceContracts -ManifestPath $freeze -ExpectedVersion 0.1.20 -ExpectedVersionCode 21
```

Changing QA inputs or relevant source after freezing requires fresh offline checks
and an independent 020 refreeze. Never replace the historical 019 freeze or weaken
the 019/code20 gate. Its historical inputs already predate inherited script changes;
it remains historical evidence and is not claimed to verify the current scripts.

## Main agent device commands

The main agent supplies its FINAL_READY APK and published SHA256. The example serial
must be replaced with the main agent's explicitly selected independent QA emulator.
0.1.20 requires explicit versionCode; production currently declares 21.

```powershell
$apk = '<absolute FINAL_READY 0.1.20 APK>'
$sha = '<main agent published 64 hex SHA256>'
$serial = 'emulator-5558'
pwsh -NoProfile -File "$qa/Invoke-QaBatch020.ps1" -ExpectedVersionCode 21 -FreezeManifestPath $freeze -Matrix Short -Run -FinalReady -MainAgentOwnsEmulator -OwnsDisposableClipboard -Serial $serial -ApkPath $apk -ExpectedApkSha256 $sha
pwsh -NoProfile -File "$qa/Invoke-QaBatch020.ps1" -ExpectedVersionCode 21 -FreezeManifestPath $freeze -Matrix LongChat -LongMaxSwipes 120 -LongMaxSeconds 600 -LongNoProgressSeconds 90 -Run -FinalReady -MainAgentOwnsEmulator -Serial $serial -ApkPath $apk -ExpectedApkSha256 $sha
```

Short runs receipts, exact copy/reference and 320dp run-info independently of the
old 48-swipe long search. Use `-ShortCases Receipts,RunInfo320` from an in-process
PowerShell invocation to exclude clipboard work. CopyReference requires explicitly
owned disposable emulator clipboard/history, as authorized by the user on this date.
It overwrites that clipboard and does not claim to restore it. It copies final response
and two generated files, pastes into the App composer, compares exact complete text,
checks references separately with Kotlin lineSequence quoting, clears synthetic draft,
and never sends. Failure to clear/paste/associate actions fails closed. File previews
default expanded; their actual header semantics determine toggles. Response and file
reference buttons are selected using unique matching vertical bounds beside the copy
action in the validated chat surface; saved UIAutomator XML flattens Compose Row nodes.

Receipts opens the three-agent, two-file and three-command batches and their eight
individual receipt IDs. Fresh terminal fixture disclosures default collapsed. On
Android versions omitting Compose stateDescription, the runner tracks only its own
toggles and verifies expanded receipt content; exported state-desc, when present,
must agree. This assumes a fresh fixture launch and exclusive device ownership.
The original semantic run-info fields remain mandatory; capsule shape, grouping,
spacing and 320dp wrapping are PENDING_REVIEW until PNG review.

LongChat records full-message semantic round IDs using raw text or the frozen plain
Markdown soft-line projection demonstrated by saved XML, directional frontier changes,
chat-interior pixel differences and hashed XML/PNG references in long-progress.jsonl.
Each first/last leg has separate swipe/time/no-progress budgets. It does not reverse
direction within a leg, increase caps to claim success, or treat identical XML as proof
of no scroll. No endpoint candidate or exhausted budget => INCOMPLETE. A short first
node wholly inside the list, or stable latest full-text semantics, merely nominates a
PNG for review => PENDING_REVIEW. Neither certifies actual visible sentinel lines.
Main agent must inspect sentinel pixels and record review separately; no automated
long PASS. One pair of legs is prepared; repeated cycles remain unvalidated.

Every batch retains complete private workspace/task/output/attachment/preferences
transaction backup, membership/SHA256 comparison and original absence restoration.
WM size is restored, App remains stopped, restoration failures retain the backup and
produce FAIL. All ADB calls are bounded at 30s, additionally limited by active long leg
deadline; cleanup clears that deadline. New run IDs are activity-020-<guid>; historical
activity-019-<guid> and their evidence remain untouched. Current-PID bounded logs and
meminfo do not establish peak memory, no leaks, old killed PIDs or real bitmap decode.
batch-result.json separates status, per-case evidence, restoration and cleanup errors.
