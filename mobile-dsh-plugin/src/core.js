/**
 * Self-contained host contract. The deterministic build serializes this factory
 * into DSH's lazy client module; keep helpers inside the factory.
 */
export function createCore(env) {
  const protocol = "dafeiyu.mobile/1";
  const capabilityNames = new Set(["mobile-ui", "app-settings", "devices", "workspace"]);
  const text = (value, max = 256) =>
    typeof value === "string" && value.length > 0 && value.length <= max &&
    !/[\u0000-\u001f\u007f]/u.test(value);
  const number = (value, max) =>
    Number.isFinite(value) && value >= 0 && value <= max;
  const now = () => env.Date.now();

  function normalizeSnapshot(raw, origin) {
    if (!raw || raw.protocol !== protocol || raw.enabled !== true ||
        origin === "null" || raw.origin !== origin || !text(raw.sessionId, 128) ||
        !Number.isSafeInteger(raw.revision) || raw.revision < 0 ||
        !Array.isArray(raw.capabilities) ||
        !raw.capabilities.includes("mobile-ui") ||
        raw.capabilities.some(value => !capabilityNames.has(value)) ||
        typeof raw.reduceMotion !== "boolean" ||
        typeof raw.reduceTransparency !== "boolean") return null;
    const area = raw.safeArea;
    const keyboard = raw.keyboard;
    if (!area || !["top", "right", "bottom", "left"].every(key => number(area[key], 256)) ||
        !keyboard || typeof keyboard.visible !== "boolean" ||
        !number(keyboard.height, 4096) ||
        !["resize", "overlay"].includes(keyboard.mode)) return null;
    if (!Array.isArray(raw.devices) || raw.devices.length > 64 ||
        !number(raw.devicesObservedAt, Number.MAX_SAFE_INTEGER) ||
        raw.devicesObservedAt > now() + 5000) return null;
    const ids = new Set();
    const devices = [];
    for (const device of raw.devices) {
      if (!device || !text(device.id, 128) || ids.has(device.id) ||
          !text(device.name, 128) || typeof device.online !== "boolean" ||
          typeof device.keyVerified !== "boolean" || typeof device.member !== "boolean") return null;
      ids.add(device.id);
      devices.push(Object.freeze({
        id: device.id, name: device.name, online: device.online,
        keyVerified: device.keyVerified, member: device.member
      }));
    }
    if (raw.workspaceId !== null && !text(raw.workspaceId, 128)) return null;
    return Object.freeze({
      protocol, enabled: true, origin, sessionId: raw.sessionId, revision: raw.revision,
      capabilities: Object.freeze([...new Set(raw.capabilities)]),
      safeArea: Object.freeze({top: area.top, right: area.right, bottom: area.bottom, left: area.left}),
      keyboard: Object.freeze({visible: keyboard.visible, height: keyboard.height, mode: keyboard.mode}),
      reduceMotion: raw.reduceMotion, reduceTransparency: raw.reduceTransparency,
      devices: Object.freeze(devices), devicesObservedAt: raw.devicesObservedAt,
      workspaceId: raw.workspaceId
    });
  }

  function viewport(host, layout, visual) {
    const width = Math.max(0, Math.min(layout.width, visual?.width ?? layout.width));
    const visualHeight = Math.max(0, Math.min(layout.height, visual?.height ?? layout.height));
    // adjustResize already changes layout.height. VisualViewport can also account
    // for an overlay keyboard; subtract only its still-unaccounted occlusion.
    const accounted = Math.max(0, layout.height - visualHeight);
    const extra = host.keyboard.visible && host.keyboard.mode === "overlay"
      ? Math.max(0, host.keyboard.height - accounted) : 0;
    const bottom = host.keyboard.visible ? 0 : host.safeArea.bottom;
    return Object.freeze({
      left: (visual?.offsetLeft ?? 0) + host.safeArea.left,
      top: (visual?.offsetTop ?? 0) + host.safeArea.top,
      width: Math.max(0, width - host.safeArea.left - host.safeArea.right),
      height: Math.max(0, visualHeight - extra - host.safeArea.top - bottom)
    });
  }

  function workspace(host, list) {
    if (!host.capabilities.includes("workspace") || !host.workspaceId) {
      return Object.freeze({phase: "unselected", path: null});
    }
    if (!list || list.phase !== "ready" || list.state === "loading") {
      return Object.freeze({phase: "loading", path: null});
    }
    if (list.state === "error") return Object.freeze({phase: "error", path: null});
    const item = list.items?.find(value => value.workspaceId === host.workspaceId);
    return item && text(item.path, 4096)
      ? Object.freeze({phase: "ready", path: item.path})
      : Object.freeze({phase: "missing", path: null});
  }

  function deviceSummary(host, timestamp = now()) {
    if (!host.capabilities.includes("devices")) return {phase: "unavailable", online: 0};
    if (timestamp - host.devicesObservedAt > 60000) return {phase: "stale", online: 0};
    return {phase: "ready", online: host.devices.filter(device => device.online).length};
  }

  function createHostController(bridge, onRevoke = () => {}) {
    if (!bridge || typeof bridge.getSnapshot !== "function" ||
        typeof bridge.subscribe !== "function" || typeof bridge.postMessage !== "function") return null;
    let host;
    try { host = normalizeSnapshot(bridge.getSnapshot(), env.location.origin); } catch { return null; }
    if (!host) return null;
    let alive = true;
    let unsubscribe = null;
    let pending = null;
    const listeners = new Set();
    let state = Object.freeze({host, panel: null, request: {phase: "idle"}, tick: now()});
    function publish(patch) {
      state = Object.freeze({...state, ...patch});
      for (const listener of [...listeners]) listener();
    }
    function clearPending() {
      if (pending) env.clearTimeout(pending.timer);
      pending = null;
    }
    function dispose() {
      if (!alive) return;
      alive = false;
      clearPending();
      env.clearInterval(staleTimer);
      if (typeof unsubscribe === "function") unsubscribe();
      unsubscribe = null;
      listeners.clear();
    }
    function revoke() {
      if (!alive) return;
      dispose();
      onRevoke();
    }
    function current() {
      if (!alive || env.__DAFEIYU_MOBILE_HOST__ !== bridge) return false;
      try {
        const next = normalizeSnapshot(bridge.getSnapshot(), env.location.origin);
        if (!next || next.sessionId !== host.sessionId || next.revision < host.revision ||
            (next.revision === host.revision && JSON.stringify(next) !== JSON.stringify(host))) return false;
        // Every action rechecks the latest injected permissions, even before a
        // subscription notification has reached the UI.
        if (next.revision > host.revision) {
          host = next;
          const denied = !host.capabilities.includes("app-settings");
          if (denied) clearPending();
          publish({host, tick: now(), ...(denied
            ? {request: {phase: "error", message: "App 设置入口已停用"}} : {})});
        }
        return true;
      } catch { return false; }
    }
    function receive(event) {
      if (!alive) return;
      if (!current()) return revoke();
      if (event?.kind === "snapshot") {
        const next = normalizeSnapshot(event.snapshot, env.location.origin);
        if (!next || next.sessionId !== host.sessionId) return revoke();
        // The event is an invalidation notice, never an alternate authority.
        // Old queued notices cannot restore a withdrawn capability.
        if (next.revision < host.revision) return;
        if (JSON.stringify(next) !== JSON.stringify(host)) return revoke();
      } else if (event?.kind === "reply" && pending) {
        if (event.protocol !== protocol || event.origin !== host.origin ||
            event.sessionId !== host.sessionId || event.requestId !== pending.id ||
            event.action !== "open_app_settings" || typeof event.ok !== "boolean") return;
        clearPending();
        publish({request: event.ok ? {phase: "success"} : {
          phase: "error",
          message: event.code === "denied" ? "App 拒绝了此次请求" :
            event.code === "unavailable" ? "App 设置暂不可用" : "未能打开 App 设置"
        }});
      }
    }
    const staleTimer = env.setInterval(() => {
      if (!current()) return revoke();
      publish({host, tick: now()});
    }, 15000);
    const controller = {
      getSnapshot: () => state,
      subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
      setPanel(panel) {
        if (!current()) return revoke();
        if (![null, "devices", "app", "settings"].includes(panel)) return;
        if (panel !== "app") clearPending();
        publish({panel, ...(panel !== "app" ? {request: {phase: "idle"}} : {})});
      },
      openAppSettings() {
        if (!current()) return revoke();
        if (pending) return;
        if (!host.capabilities.includes("app-settings")) {
          publish({panel: "app", request: {phase: "error", message: "App 设置入口未启用"}});
          return;
        }
        let id;
        try { id = env.crypto.randomUUID(); } catch {
          publish({panel: "app", request: {phase: "error", message: "无法建立安全的设置请求"}});
          return;
        }
        const timer = env.setTimeout(() => {
          if (!alive || pending?.id !== id) return;
          clearPending();
          publish({request: {phase: "error", message: "App 响应超时，请重试"}});
        }, 12000);
        pending = {id, timer};
        publish({panel: "app", request: {phase: "loading"}});
        try {
          bridge.postMessage(JSON.stringify({
            protocol, kind: "request", action: "open_app_settings",
            origin: host.origin, sessionId: host.sessionId, requestId: id
          }));
        } catch {
          clearPending();
          publish({request: {phase: "error", message: "无法连接 App 设置"}});
        }
      },
      dispose
    };
    try {
      unsubscribe = bridge.subscribe(receive);
      if (typeof unsubscribe !== "function") { revoke(); return null; }
      // A synchronous revocation during subscription still releases its disposer.
      if (!alive) { unsubscribe(); unsubscribe = null; return null; }
    } catch { revoke(); return null; }
    return controller;
  }
  return {protocol, normalizeSnapshot, viewport, workspace, deviceSummary, createHostController};
}
