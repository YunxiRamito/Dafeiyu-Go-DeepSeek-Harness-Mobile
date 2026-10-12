import assert from "node:assert/strict";
import {createServer} from "node:http";
import {readFile, mkdir} from "node:fs/promises";
import {resolve, dirname} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";

const project = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const dependencies = process.env.DSH_NODE_MODULES ?? "G:/DeepSeek DSH/node_modules";
const playwrightPath = process.env.PLAYWRIGHT_MODULE ??
  resolve(project, "../../Dafeiyu-Go-DeepSeek-Harness-HTML/interactive-preview/node_modules/playwright/index.mjs");
const {chromium} = await import(pathToFileURL(playwrightPath));
const frontend = resolve(dependencies, "@deepseek-ai/dsh-web-frontend/dist/assets");
const metadata = JSON.parse(await readFile(resolve(dependencies, "@deepseek-ai/dsh-web-frontend/package.json"), "utf8"));
assert.equal(metadata.version, "0.2.1-alpha.1", "Reverify published platform assets before testing a new DSH version");
const routes = new Map([
  ["/", resolve(project, "tests/fixture.html")],
  ["/official/index.js", resolve(frontend, "index-DjTxlw_T.js")],
  ["/official/vendor-CCJJTK99.js", resolve(frontend, "vendor-CCJJTK99.js")],
  ["/tests/fixture.js", resolve(project, "tests/fixture.js")],
  ["/tests/support.mjs", resolve(project, "tests/support.mjs")],
  ["/lib/client.js", resolve(project, "lib/client.js")]
]);
const server = createServer(async (request, response) => {
  const pathname = new URL(request.url, "http://localhost").pathname;
  const file = routes.get(pathname);
  if (!file) { response.writeHead(404); response.end(); return; }
  try {
    response.writeHead(200, {
      "Content-Type": pathname === "/" ? "text/html; charset=utf-8" : "text/javascript; charset=utf-8",
      "Cache-Control": "no-store"
    });
    response.end(await readFile(file));
  } catch (error) { response.writeHead(500); response.end(String(error)); }
});
await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const output = resolve(project, "../../Temp_Android/mobile-dsh-plugin");
await mkdir(output, {recursive: true});
let browser;
const results = [];
async function stableDialog(page) {
  await page.waitForFunction(() =>
    [...document.querySelector("dialog").getAnimations()].every(animation =>
      animation.playState === "finished"));
}
try {
  browser = await chromium.launch({
    headless: true,
    ...(process.env.BROWSER_EXECUTABLE ? {executablePath: process.env.BROWSER_EXECUTABLE} :
      process.env.BROWSER_CHANNEL ? {channel: process.env.BROWSER_CHANNEL} : {})
  });
  for (const viewport of [{width: 390, height: 844}, {width: 320, height: 640}, {width: 1280, height: 800}]) {
    const page = await browser.newPage({viewport});
    const errors = [];
    await page.route("**/*", route => {
      if (!route.request().url().startsWith(`${origin}/`)) {
        errors.push(`Blocked external request ${route.request().url()}`);
        return route.abort();
      }
      return route.continue();
    });
    page.on("pageerror", error => errors.push(error.message));
    page.on("console", message => {
      if (message.type() === "error" && !message.text().includes("OFFLINE_FIXTURE_BOOT_STOP") &&
          !message.text().includes("404")) errors.push(message.text());
    });
    await page.goto(origin);
    await page.waitForFunction(() => window.offlineFixture?.ready);
    await page.getByRole("button", {name: "在线设备 1", exact: true}).click();
    await page.getByRole("dialog").waitFor({state: "visible"});
    assert.equal(await page.getByText("密钥尚未核验", {exact: true}).count(), 1);
    assert.equal(await page.getByText("密钥已核验", {exact: true}).count(), 1);
    await stableDialog(page);
    await page.screenshot({path: resolve(output, `devices-${viewport.width}.png`), fullPage: true});
    await page.getByRole("button", {name: "关闭", exact: true}).click();
    if (viewport.width < 768) {
      await page.getByRole("button", {name: "设置", exact: true}).click();
      const settings = await page.getByRole("dialog").evaluate(dialog => {
        const buttons = [...dialog.querySelectorAll(".dm-settings button")].map(button => button.getBoundingClientRect());
        return buttons.map(rect => ({x: rect.x, y: rect.y, width: rect.width, height: rect.height}));
      });
      assert.equal(settings.length, 2);
      assert.equal(settings[0].y, settings[1].y);
      assert.ok(settings[0].width >= 44 && settings[1].width >= 44);
    }
    await page.getByRole("button", {name: "DSH 设置", exact: true}).click();
    assert.equal(await page.evaluate(() => offlineFixture.openedOfficialSettings), 1);
    await page.getByRole("dialog").waitFor({state: "hidden"});
    if (viewport.width < 768) await page.getByRole("button", {name: "设置", exact: true}).click();
    await page.getByRole("button", {name: "App 设置", exact: true}).click();
    await page.getByText("正在请求打开 App 设置", {exact: true}).waitFor();
    const request = await page.evaluate(() => offlineFixture.messages.at(-1));
    assert.deepEqual(Object.keys(request).sort(),
      ["action", "kind", "origin", "protocol", "requestId", "sessionId"].sort());
    assert.equal(request.action, "open_app_settings");
    await page.getByRole("button", {name: "取消等待"}).click();
    await page.evaluate(() => offlineFixture.reply());
    await page.getByRole("dialog").waitFor({state: "hidden"});
    if (viewport.width < 768) await page.getByRole("button", {name: "设置", exact: true}).click();
    await page.getByRole("button", {name: "App 设置", exact: true}).click();
    await page.evaluate(() => offlineFixture.reply({ok: false, code: "unavailable"}));
    await page.getByRole("alert").getByText("App 设置暂不可用").waitFor();
    await page.getByRole("button", {name: "重试", exact: true}).click();
    await page.evaluate(() => offlineFixture.reply());
    await page.getByText("请求已交给 App", {exact: true}).waitFor();
    await stableDialog(page);
    await page.screenshot({path: resolve(output, `settings-${viewport.width}.png`), fullPage: true});
    await page.getByRole("button", {name: "关闭", exact: true}).click();

    await page.evaluate(() => offlineFixture.update({reduceMotion: true, reduceTransparency: true}));
    const reduced = await page.getByRole("button", {name: "在线设备 1", exact: true}).evaluate(button => {
      button.dataset.pressed = "";
      const style = getComputedStyle(button);
      const result = {transform: style.transform, duration: style.transitionDuration, backdrop: style.backdropFilter};
      delete button.dataset.pressed;
      return result;
    });
    assert.deepEqual(reduced, {transform: "none", duration: "0s", backdrop: "none"});
    await page.evaluate(() => offlineFixture.update({reduceMotion: false, reduceTransparency: false}));
    const devicesButton = page.getByRole("button", {name: "在线设备 1", exact: true});
    const bounds = await devicesButton.boundingBox();
    await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    await page.mouse.down();
    await page.waitForFunction(() => {
      const button = document.querySelector(".dm-devices button");
      return button.hasAttribute("data-pressed") && getComputedStyle(button).transform !== "none";
    });
    await page.mouse.up();
    await page.getByRole("dialog").waitFor({state: "visible"});
    await page.getByRole("button", {name: "关闭", exact: true}).click();

    await page.evaluate(() => offlineFixture.update({keyboard: {visible: true, height: 240, mode: "overlay"}}));
    const rootBox = await page.locator("#root").boundingBox();
    assert.equal(Math.round(rootBox.height), viewport.height - 240 - 24);
    assert.equal(Math.round(rootBox.y), 24);
    await devicesButton.click();
    const dialogBox = await page.getByRole("dialog").boundingBox();
    assert.ok(dialogBox.x >= 0 && dialogBox.x + dialogBox.width <= viewport.width);
    assert.ok(dialogBox.y >= 24 && dialogBox.y + dialogBox.height <= viewport.height - 240);
    const overflow = await page.evaluate(() => ({
      page: document.documentElement.scrollWidth > innerWidth,
      dialog: document.querySelector("dialog").scrollWidth > document.querySelector("dialog").clientWidth
    }));
    assert.deepEqual(overflow, {page: false, dialog: false});
    await page.evaluate(() => offlineFixture.update({enabled: false}));
    assert.equal(await page.locator("style[data-dafeiyu-mobile-style]").count(), 0);
    await page.getByText("原始设置夹具", {exact: true}).waitFor();
    assert.equal(await page.getByRole("dialog").count(), 0);
    assert.equal(await page.locator("#root").evaluate(root => root.style.position), "");
    assert.deepEqual(errors, []);
    results.push({viewport, result: "passed"});
    await page.close();
  }
  const dormant = await browser.newPage({viewport: {width: 390, height: 844}});
  await dormant.goto(`${origin}/?dormant`);
  await dormant.waitForFunction(() => window.offlineFixture?.ready);
  assert.equal(await dormant.locator("style[data-dafeiyu-mobile-style]").count(), 0);
  await dormant.evaluate(() => offlineFixture.activate());
  await dormant.getByRole("button", {name: "在线设备 1", exact: true}).waitFor();
  await dormant.evaluate(() => offlineFixture.unload());
  assert.equal(await dormant.locator("style[data-dafeiyu-mobile-style]").count(), 0);
  await dormant.close();
  console.log(JSON.stringify({
    kind: "offline-plugin-contract-and-visual-test",
    officialPlatform: metadata.version, runtimeConnection: false,
    results, dormantActivationAndUnload: "passed", screenshots: output
  }, null, 2));
} finally {
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
}
