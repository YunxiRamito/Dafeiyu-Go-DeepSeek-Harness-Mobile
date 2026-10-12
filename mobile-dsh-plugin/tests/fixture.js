import {slotAdapter} from "./support.mjs";

const platform = await window.__offlinePlatformReady;
const React = platform.react;
const h = React.createElement;
const {SlotCore} = platform["@deepseek-ai/dsh-client-ui-slots"];
const core = new SlotCore();
const slots = slotAdapter(core);
const listeners = new Set();
const messages = [];
let host = {
  protocol: "dafeiyu.mobile/1", origin: location.origin, enabled: true,
  sessionId: "offline-browser-session", revision: 1,
  capabilities: ["mobile-ui", "app-settings", "devices", "workspace"],
  reduceMotion: false, reduceTransparency: false,
  safeArea: {top: 24, right: 0, bottom: 20, left: 0},
  keyboard: {visible: false, height: 0, mode: "resize"},
  devicesObservedAt: Date.now(), workspaceId: "offline-workspace",
  devices: [
    {id: "offline-pc", name: "离线夹具电脑", online: true, member: true, keyVerified: false},
    {id: "offline-tablet", name: "离线夹具平板", online: false, member: true, keyVerified: true}
  ]
};
let wide = innerWidth >= 768;
let openedOfficialSettings = 0;
const bridge = {
  getSnapshot: () => host,
  subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
  postMessage(message) { messages.push(JSON.parse(message)); }
};
if (!new URLSearchParams(location.search).has("dormant")) window.__DAFEIYU_MOBILE_HOST__ = bridge;
const workspaceSnapshot = {
  phase: "ready", state: "idle", error: null, archivedSessionIds: [], pinnedSessionIds: [],
  items: [{workspaceId: "offline-workspace", path: "G:/离线夹具/工作目录", title: "离线夹具"}]
};
const effects = [];
const ctx = {
  slots, workspaces: {list: {getSnapshot: () => workspaceSnapshot, subscribe: () => () => {}}},
  effect(fn) { const off = fn(); effects.push(off); return off; }
};
let client;
window.__ModuleLoader__.load = ({id, factory}) => {
  if (id !== "dafeiyu-go-mobile-dsh") throw new Error("Unexpected fixture plugin");
  client = factory(name => {
    if (!Object.hasOwn(platform, name)) throw new Error(`Missing official platform ${name}`);
    return platform[name];
  });
};
await new Promise((resolve, reject) => {
  const script = document.createElement("script");
  script.src = "/lib/client.js";
  script.onload = resolve;
  script.onerror = reject;
  document.head.appendChild(script);
});
const declare = () => core.register({
  name: "root", children: {
    "settings.launcher": {kind: "single", scope: "root"},
    "sidebar.footer.action": {kind: "list", scope: "root"},
    "shell.bottom": {kind: "single", scope: "root"},
    "shell.overlay": {kind: "list", scope: "root"}
  }
}, function FixtureOwner() {});
const undeclare = declare();
const account = () => h("span", {className: "fixture-badge"}, "原始设置夹具");
const offAccount = core.register({name: "settings.launcher"}, account);
client.apply(ctx);
function Seat({name, owner = {}}) {
  React.useSyncExternalStore(
    listener => core.subscribe(name, listener),
    () => core.getVersion(name), () => core.getVersion(name)
  );
  return core.entriesOfSlot(name).map((entry, index) => h(entry.component, {
    ...owner, key: entry.options.id ?? index
  }));
}
function Frame() {
  return h("div", {className: "fixture-frame", "data-wide": String(wide)},
    h("aside", {className: "fixture-sidebar"},
      h("span", {className: "fixture-badge"}, wide ? "离线 UI 夹具" : "夹具"),
      h("div", {className: "fixture-spacer"}),
      h(Seat, {name: "sidebar.footer.action", owner: {wide}}),
      h(Seat, {name: "settings.launcher", owner: {
        wide, settingsOpen: false,
        openSettings: () => { openedOfficialSettings++; },
        openOnboarding() {}
      }})),
    h("main", {className: "fixture-main"},
      h("h1", null, "移动插件离线验证"),
      h("p", null, "使用已安装官方 React、图标与 SlotCore。此页面是契约夹具，没有 DSH 服务或账户连接。"),
      h("input", {"aria-label": "键盘夹具", placeholder: "键盘夹具"})),
    h("div", {className: "fixture-bottom"}, h(Seat, {name: "shell.bottom"})),
    h(Seat, {name: "shell.overlay"}));
}
document.getElementById("root").replaceChildren();
const root = platform["react-dom/client"].createRoot(document.getElementById("root"));
root.render(h(Frame));
window.offlineFixture = {
  ready: true, messages,
  get openedOfficialSettings() { return openedOfficialSettings; },
  get host() { return host; },
  setWide(value) { wide = value; root.render(h(Frame)); },
  update(patch) {
    host = {...host, ...patch, revision: host.revision + 1};
    for (const listener of [...listeners]) listener({kind: "snapshot", snapshot: host});
  },
  reply(patch = {}) {
    for (const listener of [...listeners]) listener({...messages.at(-1), kind: "reply", ok: true, ...patch});
  },
  activate() {
    window.__DAFEIYU_MOBILE_HOST__ = bridge;
    window.dispatchEvent(new Event("dafeiyu-mobile-host-ready"));
  },
  unload() { effects.reverse().forEach(off => off()); },
  dispose() { effects.reverse().forEach(off => off()); offAccount(); undeclare(); root.unmount(); }
};
