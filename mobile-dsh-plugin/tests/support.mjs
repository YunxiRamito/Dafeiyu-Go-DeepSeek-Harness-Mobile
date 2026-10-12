export function fixture() {
  const timers = new Map();
  const intervals = new Map();
  let counter = 0;
  let time = 1_000_000;
  let listener = null;
  let unsubscriptions = 0;
  let host = {
    protocol: "dafeiyu.mobile/1", enabled: true, origin: "https://dsh.offline.test",
    sessionId: "offline-session", revision: 1,
    capabilities: ["mobile-ui", "app-settings", "devices", "workspace"],
    safeArea: {top: 24, right: 0, bottom: 20, left: 0},
    keyboard: {visible: false, height: 0, mode: "resize"},
    reduceMotion: false, reduceTransparency: false, devicesObservedAt: time,
    workspaceId: "work-1",
    devices: [{id: "pc-1", name: "离线夹具电脑", online: true, keyVerified: false, member: true}]
  };
  const messages = [];
  const bridge = {
    getSnapshot: () => host,
    subscribe(callback) { listener = callback; return () => { listener = null; unsubscriptions++; }; },
    postMessage(message) { messages.push(JSON.parse(message)); }
  };
  const env = {
    location: {origin: host.origin}, Date: {now: () => time},
    crypto: {randomUUID: () => `offline-request-${++counter}`},
    __DAFEIYU_MOBILE_HOST__: bridge,
    setTimeout(callback) { const id = ++counter; timers.set(id, callback); return id; },
    clearTimeout(id) { timers.delete(id); },
    setInterval(callback) { const id = ++counter; intervals.set(id, callback); return id; },
    clearInterval(id) { intervals.delete(id); }
  };
  return {
    env, bridge, messages, timers, intervals,
    get host() { return host; },
    get unsubscriptions() { return unsubscriptions; },
    update(patch, notify = true) {
      host = {...host, revision: host.revision + 1, ...patch};
      if (notify) listener?.({kind: "snapshot", snapshot: host});
    },
    emit(event) { listener?.(event); },
    timeout() { [...timers.values()].forEach(callback => callback()); },
    advance(delta) { time += delta; [...intervals.values()].forEach(callback => callback()); },
    reply(patch = {}) {
      listener?.({...messages.at(-1), kind: "reply", ok: true, ...patch});
    }
  };
}

// A tiny DOM/effect harness for lifecycle assertions, not a DSH runtime.
export function fakeBrowser(base) {
  function eventTarget() {
    const events = new Map();
    return {
      events,
      addEventListener(name, fn) { if (!events.has(name)) events.set(name, new Set()); events.get(name).add(fn); },
      removeEventListener(name, fn) { events.get(name)?.delete(fn); },
      dispatch(name) { [...(events.get(name) ?? [])].forEach(fn => fn()); }
    };
  }
  const values = new Map([["height", ["100%", "important"]]]);
  const root = {style: {
    getPropertyValue: key => values.get(key)?.[0] ?? "",
    getPropertyPriority: key => values.get(key)?.[1] ?? "",
    setProperty: (key, value, priority = "") => values.set(key, [value, priority]),
    removeProperty: key => values.delete(key)
  }};
  const styles = new Set();
  const browser = {
    ...base, ...eventTarget(), innerHeight: 844,
    visualViewport: {...eventTarget(), width: 390, height: 844, offsetLeft: 0, offsetTop: 0},
    document: {
      documentElement: {clientWidth: 390},
      getElementById: id => id === "root" ? root : null,
      head: {appendChild: style => styles.add(style)},
      createElement: () => ({setAttribute() {}, remove() { styles.delete(this); }})
    }
  };
  return {browser, root, styles};
}

// The actual official SlotCore supplies registration/election/cascade semantics.
// This adapter models only the documented Cordis inject/effect lifetime.
export function slotAdapter(core) {
  const pending = new Set();
  return {
    register: (options, component) => core.register(options, component),
    inject(name, callback) {
      let entry = null;
      function refresh() {
        if (core.spec(name)) { if (!entry) entry = callback(); }
        else { entry?.(); entry = null; }
      }
      const off = core.subscribeDeclaration(name, refresh);
      pending.add(off);
      try { refresh(); } catch (error) { off(); pending.delete(off); throw error; }
      return () => { off(); pending.delete(off); entry?.(); entry = null; };
    },
    get pending() { return pending.size; }
  };
}
