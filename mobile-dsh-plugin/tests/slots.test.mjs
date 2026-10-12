import test from "node:test";
import assert from "node:assert/strict";
import {readFile, access} from "node:fs/promises";
import {resolve} from "node:path";
import {pathToFileURL} from "node:url";
import vm from "node:vm";
import {createCore} from "../src/core.js";
import {createClient} from "../src/client.js";
import {fixture, fakeBrowser, slotAdapter} from "./support.mjs";

const packageRoot = resolve(process.env.DSH_NODE_MODULES ?? "G:/DeepSeek DSH/node_modules",
  "@deepseek-ai/dsh-client-ui-slots");
let SlotCore = null;
try {
  await access(resolve(packageRoot, "lib/index.js"));
  ({SlotCore} = await import(pathToFileURL(resolve(packageRoot, "lib/index.js"))));
} catch {}
const officialTest = (name, fn) => test(name, {skip: !SlotCore && "Set DSH_NODE_MODULES to run against official SlotCore"}, fn);
const css = await readFile(new URL("../src/mobile.css", import.meta.url), "utf8");
function setup(enabled = true) {
  const f = fixture();
  const {browser, root, styles} = fakeBrowser(f.env);
  if (!enabled) delete browser.__DAFEIYU_MOBILE_HOST__;
  const core = new SlotCore();
  const slots = slotAdapter(core);
  const effects = [];
  const ctx = {slots, workspaces: {list: {}}, effect(fn) { const dispose = fn(); effects.push(dispose); return dispose; }};
  createClient({createElement() {}}, {}, createCore(browser), css, browser).apply(ctx);
  const children = {
    "settings.launcher": {kind: "single", scope: "root"},
    "sidebar.footer.action": {kind: "list", scope: "root"},
    "shell.bottom": {kind: "single", scope: "root"},
    "shell.overlay": {kind: "list", scope: "root"}
  };
  return {f, browser, root, styles, core, slots, children,
    declare: () => core.register({name: "root", children}, function OfflineOwner() {}),
    unload: () => effects.reverse().forEach(dispose => dispose())};
}

officialTest("official SlotCore: wait-before-declare, account shadow, cascade and re-declare", () => {
  const s = setup();
  assert.equal(s.slots.pending, 4);
  assert.equal(s.core.entries("settings.launcher").length, 0);
  let parent = s.declare();
  const account = function OfficialAccountFixture() {};
  const offAccount = s.core.register({name: "settings.launcher"}, account);
  assert.equal(s.core.entries("settings.launcher").length, 2);
  assert.notEqual(s.core.entriesOfSlot("settings.launcher")[0].component, account);
  assert.equal(s.core.entries("sidebar.footer.action")[0].options.id, "dafeiyu-mobile-devices");
  parent();
  assert.equal(s.core.entries("settings.launcher").length, 0);
  parent = s.declare();
  assert.equal(s.core.entries("settings.launcher").length, 1);
  assert.equal(s.styles.size, 1);
  s.unload();
  assert.equal(s.slots.pending, 0);
  assert.equal(s.styles.size, 0);
  assert.equal(s.core.entries("shell.bottom").length, 0);
  assert.equal(s.root.style.getPropertyValue("height"), "100%");
  assert.equal(s.root.style.getPropertyPriority("height"), "important");
  assert.equal(s.root.style.getPropertyValue("position"), "");
  offAccount();
  parent();
});

officialTest("no bridge produces no DOM or slot mutations; explicit injection then revocation cleans up", () => {
  const s = setup(false);
  const parent = s.declare();
  assert.equal(s.styles.size, 0);
  assert.equal(s.slots.pending, 0);
  assert.equal(s.root.style.getPropertyValue("height"), "100%");
  s.browser.__DAFEIYU_MOBILE_HOST__ = s.f.bridge;
  s.browser.dispatch("dafeiyu-mobile-host-ready");
  assert.equal(s.styles.size, 1);
  assert.equal(s.core.entries("shell.overlay").length, 1);
  assert.equal(s.root.style.getPropertyValue("height"), "800px");
  s.f.update({enabled: false});
  assert.equal(s.styles.size, 0);
  assert.equal(s.slots.pending, 0);
  assert.equal(s.core.entries("shell.overlay").length, 0);
  assert.equal(s.root.style.getPropertyValue("height"), "100%");
  s.unload();
  parent();
});

officialTest("plugin unload restores the existing settings launcher and preserves unrelated host style changes", () => {
  const s = setup();
  const parent = s.declare();
  const account = function Account() {};
  const offAccount = s.core.register({name: "settings.launcher"}, account);
  s.root.style.setProperty("width", "360px");
  s.unload();
  assert.equal(s.core.entriesOfSlot("settings.launcher")[0].component, account);
  assert.equal(s.root.style.getPropertyValue("width"), "360px");
  assert.equal(s.f.intervals.size, 0);
  assert.equal(s.browser.visualViewport.events.get("resize").size, 0);
  assert.equal(s.browser.events.get("dafeiyu-mobile-host-ready").size, 0);
  offAccount();
  parent();
});

officialTest("explicit ready on bridge replacement removes old ownership and attaches the new session", () => {
  const s = setup();
  const parent = s.declare();
  const next = fixture();
  s.browser.__DAFEIYU_MOBILE_HOST__ = next.bridge;
  s.browser.dispatch("dafeiyu-mobile-host-ready");
  assert.equal(s.styles.size, 1);
  assert.equal(s.slots.pending, 4);
  assert.equal(s.core.entries("settings.launcher").length, 1);
  assert.equal(s.f.intervals.size, 1);
  assert.equal(s.f.unsubscriptions, 1);
  next.update({enabled: false});
  assert.equal(s.styles.size, 0);
  assert.equal(s.slots.pending, 0);
  assert.equal(s.f.intervals.size, 0);
  s.unload();
  parent();
});

test("built entry uses the actual lazy module-loader protocol and only baseline imports", async () => {
  const bundle = await readFile(new URL("../lib/client.js", import.meta.url), "utf8");
  let registered;
  const context = {window: {__ModuleLoader__: {load(value) { registered = value; }}}};
  vm.runInNewContext(bundle, context);
  assert.equal(registered.id, "dafeiyu-go-mobile-dsh");
  const imports = [];
  const exported = registered.factory(name => {
    imports.push(name);
    return name === "react" ? {createElement() {}} : {};
  });
  assert.deepEqual(imports, ["react", "@deepseek-ai/dsh-client-ui-primitives"]);
  assert.equal(typeof exported.apply, "function");
  assert.deepEqual([...exported.inject], ["slots", "workspaces"]);
});
