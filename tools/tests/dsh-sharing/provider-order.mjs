import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const [script, bundleRoot, dataRoot] = process.argv.slice(2);
const require = createRequire(path.join(path.resolve(bundleRoot), 'package.json'));
const { Context } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);
const { LlmRuntime, LlmAdapter } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-llm')).href);
const { buildModelCatalog } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-api-session-controller')).href);
const { installAndroidProviderOrder } = await import(pathToFileURL(path.resolve(script)).href);
fs.mkdirSync(dataRoot, { recursive: true });
const projectionFile = path.join(dataRoot, 'order.json');
function publish(revision, routes, extra = {}) {
  fs.writeFileSync(projectionFile + '.new', JSON.stringify({ schema: 1, revision, routes, ...extra }));
  fs.renameSync(projectionFile + '.new', projectionFile);
}
const registration = ['foreign-a', 'fixture-b', 'deepseek-official', 'fixture-a', 'foreign-b'];
const ctx = new Context();
const llm = new LlmRuntime(ctx);
let notifications = 0;
let streamCalls = 0;
ctx.on('llm/adapters-updated', () => { notifications++; });
class OfflineAdapter extends LlmAdapter {
  async listModels(provider) { return [{ provider, id: 'offline-model', name: provider + ' model' }]; }
  async *stream() { streamCalls++; throw new Error('NO_MODEL_IO'); }
}
const providers = llm.registerAdapter(registration, new OfflineAdapter());
const directory = llm.registerConfigurableProviders(registration.map(provider => ({
  provider, displayName: provider, settingsNs: 'fixture', settingsPath: ['providers', provider],
})));
let remove;
let errors = 0;
let passed = 0;
async function test(name, block) { await block(); passed++; console.log('PASS ' + name); }
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
try {
  publish(1, ['fixture-a', 'deepseek-official']);
  remove = await installAndroidProviderOrder(bundleRoot, dataRoot, { intervalMs: 20, onError: () => { errors++; } });
  const first = ['fixture-a', 'deepseek-official', 'foreign-a', 'fixture-b', 'foreign-b'];
  await test('real first-party lists follow launcher routes and retain unknown registration order', async () => {
    assert.deepEqual(llm.listProviders().map(item => item.id), first);
    assert.deepEqual(llm.listConfigurableProviders().map(item => item.provider), first);
    assert.equal(llm.listProviders().find(item => item.id === 'foreign-a').name, 'foreign-a');
    const copy = llm.listConfigurableProviders(); copy[0].settingsPath.push('changed');
    assert.equal(llm.listConfigurableProviders()[0].settingsPath.includes('changed'), false);
  });
  await test('actual browser catalog projection preserves sorted routes and original models without provider I/O', async () => {
    const catalog = await buildModelCatalog({ llm }, { provider: 'fixture-a', model: 'offline-model' });
    assert.equal(catalog.failures.length, 0, JSON.stringify(catalog.failures));
    assert.deepEqual(catalog.groups.map(item => item.id), first);
    assert.deepEqual(catalog.routableProviders, first);
    assert.equal(catalog.groups[0].models[0].id, 'offline-model');
    assert.equal(streamCalls, 0);
  });
  await test('atomic hot order update emits the real existing topology notification', async () => {
    const before = notifications;
    publish(2, ['fixture-b', 'fixture-a', 'deepseek-official']);
    const deadline = Date.now() + 3000;
    while (notifications === before && Date.now() < deadline) await pause(10);
    assert.equal(notifications, before + 1);
    assert.deepEqual(llm.listProviders().map(item => item.id), ['fixture-b', 'fixture-a', 'deepseek-official', 'foreign-a', 'foreign-b']);
    assert.equal(llm.listConfigurableProviders()[0].provider, 'fixture-b');
  });
  await test('invalid extra fields and stale projection cannot overwrite verified order or announce success', async () => {
    const before = notifications;
    publish(3, ['foreign-b'], { credentials: 'DO_NOT_READ_OR_LOG' });
    await pause(70);
    assert.equal(errors, 1);
    assert.equal(llm.listProviders()[0].id, 'fixture-b');
    assert.equal(notifications, before);
    publish(1, ['foreign-b']);
    await pause(60);
    assert.equal(notifications, before);
    assert.equal(llm.listProviders()[0].id, 'fixture-b');
    publish(3, ['fixture-b', 'fixture-a', 'deepseek-official']);
    await pause(50);
    assert.equal(notifications, before);
  });
  await test('adapter refuses duplicate installation and restores original real methods on disposal', async () => {
    await assert.rejects(installAndroidProviderOrder(bundleRoot, dataRoot), /ANDROID_PROVIDER_ORDER_INTERFACE/);
    remove(); remove = undefined;
    assert.deepEqual(llm.listProviders().map(item => item.id), registration);
    assert.deepEqual(llm.listConfigurableProviders().map(item => item.provider), registration);
    const before = notifications;
    publish(4, ['foreign-b']); await pause(70);
    assert.equal(notifications, before);
  });
  await test('invalid initial projection is refused before altering the first-party class', async () => {
    publish(5, ['fixture-a', 'fixture-a']);
    await assert.rejects(installAndroidProviderOrder(bundleRoot, dataRoot), /ANDROID_PROVIDER_ORDER_INVALID/);
    assert.deepEqual(llm.listProviders().map(item => item.id), registration);
  });
} finally {
  remove?.(); directory(); providers();
}
console.log(`First-party provider order: ${passed} PASS; actual LlmRuntime and browser catalog, metadata only, ${streamCalls} model requests.`);
