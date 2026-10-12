# 0.1.19 Device QA Preparation

Date: 2026-10-11, Asia/Shanghai.
Owner: Pauli continuation, exclusive preparation directory.
Status: plan prepared; device execution pending main-thread ownership.

## Entry Gates

- Main thread owns emulator-5554 and all ADB/display changes. This preparation
  does not run ADB, Gradle, install, networking or model requests.
- Bind the installed final APK versionName, versionCode and SHA256 to the
  main-thread final artifact. Do not infer readiness from a build directory.
- The current activity runner requires 0.1.18. Prepare a separate 0.1.19 runner
  and fixture expectations under exclusive ownership before using this plan;
  do not silently relax the 0.1.18 version guard.
- Use only synthetic completed/interrupted conversations, inert workspace
  entries and an empty task queue. Do not send, resume, retry or stop a model
  run. Do not inspect credentials or record settings containing secrets.
- Before staging, force-stop, save and verify every mutated path including
  AtomicFile .bak/.new siblings. Record original absence and display overrides.
  The backup/restore allowlist must include every path a new fixture action
  changes. Stop preparation if that scope has not been established.

## Focused Matrix

| Area | Fixture/action | Pass condition |
| --- | --- | --- |
| Long chat | Mixed short/long final replies, process groups and generated text; scroll toward earlier and later messages | Both ends reachable, correct reverse-layout direction, selected conversation unchanged, no header/metric/composer gestures |
| Process expansion | Completed and interrupted receipt groups; expand, scroll, collapse | Expected counts and terminal labels, no earlier-round text in final body, no resumed operation |
| Scroll anchoring | Expand/collapse a group near the viewport edge; focus composer and dismiss IME | No lost content or unexplained jump; final reply and selected group remain reachable |
| Horizontal metrics | Narrow display with long metric labels; swipe metric strip separately | Metrics remain truthful, horizontal movement does not open Sidebar or move chat |
| Sidebar | Open/close with explicit controls; select synthetic workspace and conversation; repeat | Selection and title match fixture, drawer closes, composer/chat are restored, no real workspace edited |
| Sidebar overflow | Long workspace/chat names and enough synthetic rows to overflow | Lists scroll independently, text fits, selected entry and footer remain reachable |
| Archive | Archive one inert synthetic conversation through the expected gesture/control | Only intended synthetic entry changes; selection and empty-state behavior are coherent; transaction covers persistence |
| Footer | Open inert task view and return; settings only if secret-free synthetic state is available | No queued task resumes; back navigation returns to expected fixture; otherwise mark settings deferred |
| Composer tools | Menu, attachment and sandbox affordances with no grant/upload; dismiss each view | Correct panel opens/closes, no message sent, no user attachment or storage altered |
| Model selector | Open selector only if guaranteed to use cached data, then dismiss | Current selection preserved; no credential access or external request; otherwise defer |
| Disabled buttons | Empty composer and offline synthetic conversation | Send disabled; runner refuses both enabled send and stop actions, including descendant nodes |
| Copy/reference | Synthetic final reply and generated text | Exact full synthetic text copied/referenced, correct output association, composer can be cleared without sending |
| Narrow/large font | 320dp width, normal and large font, IME shown/hidden, long URL/name | Buttons and text fit, no incoherent overlap, chat has usable bounds above composer |
| Reopen | Force-stop/reopen staged fixture with empty queue | Selection/disclosure behavior recorded, no pending operation resumes |

## Scene And Action Guards

For every chat capture, require the expected conversation title, closed Sidebar,
editable composer and expected fixture state. Validate metric sentinels when
the fixture includes completed metrics. Treat unexpected generation or changed
metrics as scene drift and stop input.

Derive gesture bounds from the vertical chat node above the composer. Exclude
HorizontalScrollView and Tokens descendants. A temporarily missing surface
allows bounded recapture; never substitute screen coordinates or a header.

For Sidebar actions, use a separate scene assertion and actual Sidebar nodes.
The chat assertion is intentionally invalid while Sidebar is open. After
selection, wait for the expected title, closed Sidebar and chat composer before
returning to chat scrolling. Do not use chat FindVisible to scroll the drawer.

Record XML and PNG in each state, with identical action names and sequence
numbers. They are sequential captures, so an apparent mismatch needs recapture
and explicit reporting rather than an assumed atomic screenshot/hierarchy.

## Evidence And Exit

Use a unique qa019 run directory. Record installed version/code/hash, fixture
hashes, display configuration, each action/scene, assertions and restore result.
Keep device results separate from offline XML helper results and shell web smoke.

Each matrix row is PASS, FAIL or DEFERRED with an evidence path and reason.
Do not count a screenshot alone as proof of copy correctness, scrolling to both
ends, restored persistence or application-UID network behavior.

In finally, force-stop before restoring; restore each backed-up path even if
another fails, verify exact file/tree membership and bytes plus original absence,
then restore display overrides. Keep backup on any failure. Leave the restored
app stopped so original queued work cannot invoke a paid provider.

## Available Offline Regression

From the workspace root:

```powershell
pwsh -NoProfile -File ./tools/tests/pauli-handoff-exclusive-20261011/VerifyActivityRunnerOffline.ps1
```

Observed result: 11 PASS / 0 FAIL on the saved 0.1.18 XML, pure selectors and
mocked input. This verifies the navigation guards; it is not 0.1.19 device QA.
