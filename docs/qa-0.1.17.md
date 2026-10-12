# QA 0.1.17

Evidence recorded on 2026-10-11, Asia/Shanghai (UTC+08:00). Four scripts were rerun sequentially from 18:01:13 to 18:04:13 with `JAVA_HOME=C:\Program Files\Java\JDK21`, Zulu OpenJDK 21.0.7. All four exited 0. Dependencies were local/cached; no Gradle, Emulator, credentials or paid model calls were used. This consolidation changes documentation only.

| Script (`tools/`) | Observed result | Time on 2026-10-11 (UTC+08:00) / status | Evidence |
| --- | --- | --- | --- |
| `test-deepseek-chat.ps1` | 37/37 PASS; exit 0 | 18:01:13-18:01:57; rerun | [Log](../../Temp_Android/qa/qa-0.1.17-recheck-20261011-180112/01-test-deepseek-chat.log) |
| `test-assistant-stream.ps1` | 21 PASS; exit 0 | 18:01:57-18:02:39; rerun | [Log](../../Temp_Android/qa/qa-0.1.17-recheck-20261011-180112/02-test-assistant-stream.log) |
| `test-background-tasks.ps1` | 34 PASS; AndroidStore and Compose compilation PASS; exit 0 | 18:02:39-18:03:46; rerun (previous count was 30) | [Log](../../Temp_Android/qa/qa-0.1.17-recheck-20261011-180112/03-test-background-tasks.log) |
| `test-chat-stream-ui.ps1` | 7 source-sentence cases plus reveal/layout/selection/motion assertions PASS; Compose compilation PASS; exit 0 | 18:03:46-18:04:13; rerun | [Log](../../Temp_Android/qa/qa-0.1.17-recheck-20261011-180112/04-test-chat-stream-ui.log) |
| `test-deepseek-tools-vision.ps1` | Client/history/vision 25 PASS, 0 FAIL; real tool session 10 PASS, 0 FAIL; exit 0 | 17:58:30-17:59:31; earlier snapshot | [Log](../../Temp_Android/qa/deepseek-tools-vision-batch-20261011-175830/deepseek-tools-vision.log), [metadata](../../Temp_Android/qa/deepseek-tools-vision-batch-20261011-175830/results.json) |
| `test-aries-provider.ps1` | 64 PASS; production Android 35 API compilation PASS; exit 0 | 17:35:44-17:36:31; earlier snapshot | [Log](../../Temp_Android/qa/offline-regression-20261011-173514/03-test-aries-provider.log) |
| `test-connection-package.ps1` | 86 package checks + 7 preparation checks PASS; exit 0 | 17:36:31-17:36:54; earlier snapshot | [Log](../../Temp_Android/qa/offline-regression-20261011-173514/04-test-connection-package.log) |
| `test-local-plugin-bridge.ps1` | 25 PASS; Android 35 API compilation PASS with Provider fixture; exit 0 | 17:36:54-17:37:13; earlier snapshot | [Log](../../Temp_Android/qa/offline-regression-20261011-173514/05-test-local-plugin-bridge.log) |

Exact timestamps, durations and exit codes: [four-script batch metadata](../../Temp_Android/qa/qa-0.1.17-recheck-20261011-180112/results.json), [Java version](../../Temp_Android/qa/qa-0.1.17-recheck-20261011-180112/java-version.log), [earlier seven-script batch metadata](../../Temp_Android/qa/offline-regression-20261011-173514/results.json). Earlier snapshots establish results at their recorded times; they were not rerun after all subsequent integration edits.

The focused client/session snapshot covers preflight with zero transport/key reads, exact 4 MiB request bounds, image history mapping and malformed image rejection, tool/reasoning/result JSON, contiguous sequence across rounds, unadvertised tool refusal, dispatch limits and duplicate call IDs. Session tests use real dispatcher/output storage with offline model transport.

The earlier assistant-stream batch failed compilation because the JVM AtomicFile fixture lacked `delete()` ([failure log](../../Temp_Android/qa/offline-regression-20261011-173514/02-test-assistant-stream.log)). The current rerun compiles and passes all 21 checks. That fixture does not establish Android AtomicFile crash-recovery guarantees.

UI checks compile the real AssistantActivityFeed/StreamRevealText sources with glass signatures fixtures and execute pure helpers. No new fixture/dependency edits were needed for this rerun; the script does not compile ChatMarkdown or exercise Android rendering. Android/Compose compilation elsewhere likewise does not establish a full app build or runtime behavior.

Aries uses the frozen PC wire fixture identified in its log for signature/envelope/block interoperability; X25519/HKDF agreement uses synthetic identities, not an independently frozen PC pair envelope. Package/bridge checks do not establish runtime plugin loading, socket/TLS behavior or end-to-end connectivity.

Dedicated WorkspaceStore regression results remain with Helmholtz and are not claimed here. Final integrated Gradle/build/device verification remains separate.
