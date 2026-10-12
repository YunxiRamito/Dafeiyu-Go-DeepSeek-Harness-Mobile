import test from "node:test";
import assert from "node:assert/strict";
import {createCore} from "../src/core.js";
import {fixture} from "./support.mjs";

test("requires a complete explicit host capability; ordinary browsers remain dormant", () => {
  const f = fixture();
  const core = createCore(f.env);
  assert.equal(core.createHostController(undefined), null);
  for (const patch of [
    {enabled: false}, {origin: "https://wrong.test"}, {capabilities: []},
    {sessionId: ""}, {revision: -1}, {reduceMotion: undefined},
    {capabilities: ["mobile-ui", "execute-script"]},
    {safeArea: {top: -1}}, {devices: [{id: "bad"}]},
    {devicesObservedAt: 2_000_000}, {workspaceId: undefined}
  ]) assert.equal(core.normalizeSnapshot({...f.host, ...patch}, f.env.location.origin), null);
  assert.equal(core.normalizeSnapshot({...f.host, origin: "null"}, "null"), null);
  assert.ok(core.normalizeSnapshot(f.host, f.env.location.origin));
});

test("native messages have a fixed action and only allowlisted fields", () => {
  const f = fixture();
  const controller = createCore(f.env).createHostController(f.bridge);
  controller.openAppSettings("javascript:ignored");
  controller.openAppSettings();
  assert.equal(f.messages.length, 1);
  assert.deepEqual(Object.keys(f.messages[0]).sort(),
    ["action", "kind", "origin", "protocol", "requestId", "sessionId"].sort());
  assert.equal(f.messages[0].action, "open_app_settings");
  assert.equal(controller.getSnapshot().request.phase, "loading");
  for (const patch of [
    {origin: "https://wrong.test"}, {sessionId: "old"},
    {requestId: "old"}, {action: "execute-script"}, {protocol: "bad"}, {ok: "yes"}
  ]) {
    f.reply(patch);
    assert.equal(controller.getSnapshot().request.phase, "loading");
  }
  f.reply();
  assert.equal(controller.getSnapshot().request.phase, "success");
  assert.equal(f.timers.size, 0);
  controller.dispose();
});

test("cancel ignores delayed replies and retry uses a new request", () => {
  const f = fixture();
  const controller = createCore(f.env).createHostController(f.bridge);
  controller.openAppSettings();
  const first = f.messages[0];
  controller.setPanel(null);
  f.reply();
  assert.equal(controller.getSnapshot().panel, null);
  assert.equal(controller.getSnapshot().request.phase, "idle");
  controller.openAppSettings();
  assert.notEqual(f.messages[1].requestId, first.requestId);
  f.emit({...first, kind: "reply", ok: true});
  assert.equal(controller.getSnapshot().request.phase, "loading");
  f.timeout();
  assert.equal(controller.getSnapshot().request.phase, "error");
  assert.match(controller.getSnapshot().request.message, /超时/);
  controller.openAppSettings();
  f.reply({ok: false, code: "denied", message: "<script>ignored</script>"});
  assert.equal(controller.getSnapshot().request.message, "App 拒绝了此次请求");
  controller.dispose();
  assert.equal(f.timers.size, 0);
  assert.equal(f.intervals.size, 0);
  assert.equal(f.unsubscriptions, 1);
});

test("transport and secure random failures display bounded errors", () => {
  const f = fixture();
  f.env.crypto.randomUUID = () => { throw new Error("failure"); };
  const controller = createCore(f.env).createHostController(f.bridge);
  controller.openAppSettings();
  assert.equal(f.messages.length, 0);
  assert.equal(controller.getSnapshot().request.phase, "error");
  f.env.crypto.randomUUID = () => "new-id";
  f.bridge.postMessage = () => { throw new Error("native bridge gone"); };
  controller.openAppSettings();
  assert.equal(controller.getSnapshot().request.phase, "error");
  assert.equal(f.timers.size, 0);
  controller.dispose();
});

test("scope changes revoke and remove pending work", () => {
  for (const patch of [{enabled: false}, {sessionId: "next"}, {origin: "https://next.test"}]) {
    const f = fixture();
    let revoked = 0;
    const controller = createCore(f.env).createHostController(f.bridge, () => revoked++);
    controller.openAppSettings();
    f.update(patch);
    assert.equal(revoked, 1);
    assert.equal(f.intervals.size + f.timers.size, 0);
    controller.openAppSettings();
    assert.equal(f.messages.length, 1);
  }
});

test("withdrawn capability is rechecked on action and cannot be restored by an old notice", () => {
  const f = fixture();
  const controller = createCore(f.env).createHostController(f.bridge);
  const old = structuredClone(f.host);
  controller.openAppSettings();
  f.update({capabilities: ["mobile-ui"]}, false);
  f.reply();
  assert.equal(controller.getSnapshot().request.phase, "error");
  f.emit({kind: "snapshot", snapshot: old});
  controller.openAppSettings();
  assert.equal(f.messages.length, 1);
  assert.deepEqual(controller.getSnapshot().host.capabilities, ["mobile-ui"]);
  controller.dispose();
});

test("bridge replacement and nonmonotone snapshots terminate the old session", () => {
  for (const change of [
    f => { f.env.__DAFEIYU_MOBILE_HOST__ = {...f.bridge}; },
    f => f.update({reduceMotion: true, revision: 0}, false)
  ]) {
    const f = fixture();
    let revoked = false;
    const controller = createCore(f.env).createHostController(f.bridge, () => { revoked = true; });
    change(f);
    controller.openAppSettings();
    assert.equal(revoked, true);
    assert.equal(f.messages.length, 0);
    controller.dispose();
  }
});

test("synchronous subscription revocation releases returned unsubscribe", () => {
  const f = fixture();
  let cleanup = 0;
  f.bridge.subscribe = callback => {
    f.update({enabled: false}, false);
    callback({kind: "snapshot", snapshot: f.host});
    return () => cleanup++;
  };
  assert.equal(createCore(f.env).createHostController(f.bridge), null);
  assert.equal(cleanup, 1);
  assert.equal(f.intervals.size, 0);
});

test("safe area and keyboard resize/overlay do not double-subtract occlusion", () => {
  const f = fixture();
  const core = createCore(f.env);
  const layout = {width: 390, height: 844};
  const visual = {width: 390, height: 844, offsetLeft: 0, offsetTop: 0};
  assert.equal(core.viewport(f.host, layout, visual).height, 800);
  const resize = {...f.host, keyboard: {visible: true, height: 300, mode: "resize"}};
  assert.equal(core.viewport(resize, {...layout, height: 544}, {...visual, height: 544}).height, 520);
  const overlay = {...resize, keyboard: {...resize.keyboard, mode: "overlay"}};
  assert.equal(core.viewport(overlay, layout, visual).height, 520);
  assert.equal(core.viewport(overlay, layout, {...visual, height: 544}).height, 520);
  assert.equal(core.viewport(overlay, layout, {...visual, height: 644}).height, 520);
  assert.deepEqual(core.viewport(f.host, layout, {...visual, width: 300, offsetLeft: 10, offsetTop: 5}),
    {left: 10, top: 29, width: 300, height: 800});
});

test("workspace selection uses the official authoritative list; members alone never imply verified keys", () => {
  const f = fixture();
  const core = createCore(f.env);
  assert.deepEqual(core.workspace(f.host, {
    phase: "ready", state: "idle",
    items: [{workspaceId: "other", path: "G:/wrong"}, {workspaceId: "work-1", path: "G:/actual"}]
  }), {phase: "ready", path: "G:/actual"});
  assert.equal(core.workspace(f.host, {phase: "ready", state: "idle", items: []}).phase, "missing");
  assert.equal(core.workspace(f.host, {phase: "pending", state: "loading"}).phase, "loading");
  assert.equal(core.workspace(f.host, {phase: "ready", state: "error"}).phase, "error");
  assert.equal(core.workspace({...f.host, workspaceId: null}, {}).phase, "unselected");
  assert.equal(core.deviceSummary(f.host).online, 1);
  assert.equal(core.deviceSummary(f.host, f.host.devicesObservedAt + 60001).phase, "stale");
  assert.equal(core.deviceSummary({...f.host, capabilities: ["mobile-ui"]}).phase, "unavailable");
  assert.equal(core.normalizeSnapshot(f.host, f.host.origin).devices[0].keyVerified, false);
});
