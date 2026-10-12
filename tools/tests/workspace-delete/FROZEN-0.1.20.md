# Workspace 0.1.20 freeze

Date: 2026-10-12. Production and test edits frozen for main-agent scheduling.
This worker ran no JVM, Gradle build or ADB. Earlier main-agent Kotlin success
does not validate the production edits in this snapshot.

## Delivered files

- `app/src/main/java/com/dafeiyu/mobile/ui/WorkspaceSidebar.kt`
- `tools/tests/workspace-delete/WorkspaceSwipeChecks.kt`
- `tools/tests/workspace-delete/VerifyWorkspaceSwipe.ps1`

Preserved existing task/storage StateFlow subscriptions, repository deletion
rechecks, opaque theme-colored selected ellipse, white foreground and shared
reduced-motion color transitions. No data, MainActivity, ChatScreen or shared
glass file edited. No new feature, dependency or upstream code introduced.

## Necessary fixes

- Record Drag Start/Stop/Cancel at interaction emission, rather than in a delayed
  collector. Associate each drag token with its DOWN generation. Old Stop/Cancel
  and old UP cannot complete or revoke the next gesture's deletion permission.
- New DOWN cancels the previous rebound job. Rebound checks generation and
  pointer state before entering the animation. Cancel restores the starting
  stage; a canceled first swipe cannot reveal Archive from a late settle.
- Capture the deletion distance on UP in Initial pass, before Main pass can fling
  past the threshold. Permission requires a settled Archive at DOWN, revealed
  row identity, distance above 1.5 archive widths at UP, matching Stop and a
  settled Delete. First DOWN from Closed never installs a Delete anchor.
- Adapt all legacy `release()` test calls; add ordering, token/generation,
  cancellation and below-threshold velocity cases. Fourteen prepared Kotlin
  tests include the existing white-text theme contrast checks.
- Cap the focused runner at 512 MiB and point both Java processes' temporary
  directory to their G-drive QA output directory.

## Actual static evidence

Executed PowerShell source checks: **11 PASS**. Checked interaction collector
removal, emission-time Start binding, DOWN rebound cancellation, UP threshold
before Final, conditional Delete anchor, generation-matched completion,
canceled-settle restoration, task/storage subscriptions, repository guard,
selected ellipse/white body and reduced-motion color transition.

PowerShell AST parse of `VerifyWorkspaceSwipe.ps1`: **PASS**. Test source contains
14 test cases and no legacy parameterless `gate.release()` calls. These are
static results, not Kotlin execution, Compose compilation or rendered evidence.

SHA-256 snapshot:

| File | SHA-256 |
| --- | --- |
| WorkspaceSidebar.kt | `2021E0B500F3E6E99D0C20661730B9745D150C06B45A044EC77D1CF54839ECE0` |
| WorkspaceSwipeChecks.kt | `94B08A32D32176A264F6856715B3469AD9802D3D6F05E3D9CB9818399CEF0DDE` |
| VerifyWorkspaceSwipe.ps1 | `800EA32614B9E3ADFB4C34E008B1AB839EB0A9D6D32B7AB0FC6184301AFEB3F0` |

## Main-agent serial commands

Run from the project root, one command at a time:

```powershell
& './tools/tests/workspace-delete/VerifyWorkspaceSwipe.ps1' -MaxHeapMiB 512
& './tools/tests/workspace-delete/VerifyWorkspaceDelete.ps1' -Stage Delete -MaxHeapMiB 512
```

The swipe runner extracts the real production gate/color helpers; it does not
compile the complete Compose row. Main-agent integrated Kotlin/build remains
required to validate the interaction-source wrapper and Compose wiring.

Device acceptance remains pending: first long swipe/first canceled swipe cannot
delete; second swipe below threshold, cancel, rapid re-DOWN during settle,
multi-pointer and row/workspace switching cannot delete; deliberate second swipe
can delete once. Confirm draft/attachment/task protection, task-removal refresh,
last empty workspace replacement and restart persistence, light/dark selected
ellipse pixels and reduced-motion behavior with the final APK identity.

## Final integration handoff

Main agent reports unified assemble/lint started. This worker has not inspected
its outcome and will not change production during the build. Rechecked all three
SHA-256 values above after that notification; each still matches the freeze.
No focused command has been executed by this worker. Run swipe first after the
unified build, then the existing Delete fixture if a fresh protection/persistence
regression result is needed for this iteration. Both commands above are ready.

No known remaining production blocker was identified at freeze. Acceptance
boundaries for the main agent:

- The 14 swipe cases exercise extracted production gate/color helpers. They
  do not simulate Compose pointer passes, velocity/fling or MutatorMutex
  scheduling. Names mentioning settle describe the permission scenario;
  actual rebound position and visual behavior remain device checks.
- Build validates the complete row's interaction-source delegation and Compose
  API usage. Successful compilation alone does not prove correct Start/Stop/
  Cancel emission timing or cancellation of an animation interrupted by DOWN.
- The Delete fixture uses extracted repository code and offline task/store
  fixtures. It covers protection and persistence logic; lifecycle subscription
  refresh of a rendered delete button still needs the final APK.
- Theme contrast checks cover opaque endpoint colors. Animated intermediate
  colors, glass rendering, hit targets and light/dark pixels remain unverified.
- Workspace deletion's Boolean reports request acceptance; the repository's
  later notice/state reports whether IO rechecks actually allowed removal.

Do not label static or helper results as device acceptance. Bind final device
results to the unified APK rather than an earlier package.
