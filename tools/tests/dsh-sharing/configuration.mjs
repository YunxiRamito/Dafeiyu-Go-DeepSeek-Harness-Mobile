import { pathToFileURL } from 'node:url';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const [script, bundle] = process.argv.slice(2);
const { nativeToDsh, dshToNative } = await import(pathToFileURL(path.resolve(script)).href);
let passed = 0;
async function test(name, block) { await block(); passed++; console.log(`PASS ${name}`); }
const native = { providers: [
  { id: 'deepseek', name: 'DeepSeek official', builtinProvider: 'deepseek', enabled: true, models: [] },
  { id: 'openai-codex', name: 'Codex', builtinProvider: 'openai-codex', enabled: true, models: [] },
  { id: 'offline-custom', name: 'Offline gateway', api: 'openai-completions', baseUrl: 'https://example.invalid/v1',
    enabled: true, models: [{ id: 'fixture-model', reasoning: true, input: ['text'], contextWindow: 32768, maxTokens: 8192,
      thinkingLevelMap: { off: undefined, minimal: null, low: 'vendor_low', medium: null, high: 'vendor_high', xhigh: null, max: null } }] },
], credentials: { 'offline-custom': { type: 'api_key', key: 'OFFLINE_KEY' } } };
let dsh;
await test('fixed first-party Config and catalog admit real OAuth builtin and mapped custom efforts', async () => {
  dsh = await nativeToDsh(bundle, native);
  assert.deepEqual(dsh.order, native.providers.map(item => item.id));
  assert.equal(dsh.providers.deepseek, undefined);
  assert.equal(dsh.providers['openai-codex'].api, undefined);
  assert.deepEqual(dsh.providers['offline-custom'].models[0].reasoningEfforts,
    { off: null, low: 'vendor_low', high: 'vendor_high' });
  assert.equal(dsh.providers['offline-custom'].apiKeyEnv, 'DAFEIYU_PROVIDER_OFFLINE_CUSTOM');
  assert.equal(JSON.stringify(dsh).includes('OFFLINE_KEY'), false);
});
await test('reverse mapping preserves actual model capabilities without broadening unknown efforts', async () => {
  const value = await dshToNative(bundle, dsh.providers);
  assert.equal(value.providers.find(item => item.id === 'openai-codex').builtinProvider, 'openai-codex');
  const custom = value.providers.find(item => item.id === 'offline-custom');
  assert.deepEqual(custom.models[0].reasoningOptions, ['off', 'low', 'high']);
  assert.equal(custom.models[0].thinkingLevelMap.low, 'vendor_low');
  assert.equal(custom.models[0].thinkingLevelMap.medium, null);
});
await test('actual first-party strict validation rejects invalid route/protocol and empty reasoning maps', async () => {
  await assert.rejects(dshToNative(bundle, { fixture: { api: 'made-up', baseURL: 'https://example.invalid', models: [{ id: 'fixture' }] } }));
  await assert.rejects(dshToNative(bundle, { fixture: { api: 'openai-completions', baseURL: 'https://example.invalid', models: [{ id: 'fixture', reasoningEfforts: {} }] } }));
});
await test('DSH Pi-AI DeepSeek route remains distinct from protected Android direct channel', async () => {
  const value = await dshToNative(bundle, { deepseek: {} });
  assert.equal(value.providers[0].id, 'dsh-pi-deepseek');
  assert.equal(value.providers[0].builtinProvider, 'deepseek');
  assert.equal(value.providers[0].dshRoute, 'deepseek');
  const roundtrip = await nativeToDsh(bundle, { providers: value.providers, credentials: {} });
  assert.equal(roundtrip.providers.deepseek.displayName, value.providers[0].name);
  assert.equal(roundtrip.providers['dsh-pi-deepseek'], undefined);
});
await test('native aliases cannot claim the same DSH route or protected official route', async () => {
  await assert.rejects(nativeToDsh(bundle, { providers: [
    { id: 'a', dshRoute: 'openai', builtinProvider: 'openai', name: 'A' },
    { id: 'b', dshRoute: 'openai', builtinProvider: 'openai', name: 'B' },
  ] }));
  await assert.rejects(nativeToDsh(bundle, { providers: [{ id: 'a', dshRoute: 'deepseek-official', builtinProvider: 'openai', name: 'A' }] }));
});
await test('actual SettingsForms keeps exact stored models while first-party Config materializes defaults', async () => {
  const require = createRequire(path.join(path.resolve(bundle), 'package.json'));
  const { SettingsForms } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-settings')).href);
  const { Config } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-llm-pi-ai')).href);
  const models = [{ id: 'offline-runtime-model', name: 'offline-runtime-model', reasoningEfforts: false }];
  const raw = { providers: { fixture: { displayName: 'Offline', api: 'openai-completions',
    baseURL: 'https://example.invalid/v1', models } } };
  const entry = { id: 1, options: { id: 'llm-pi-ai', config: raw }, fiber: {
    uid: 1, state: 2, runtime: { Config }, config: Config(raw), ctx: {} } };
  const forms = Object.assign(Object.create(SettingsForms.prototype), {
    revisions: new Map(), presentations: new Map(), ownerContext: { emit() {},
      configEditor: { configuration() { return [{ entry, inherited: {}, override: raw }]; } } },
  });
  const result = forms.describe({ redactSecrets: true })[0];
  assert.deepEqual(result.user.providers.fixture.models, models);
  assert.notDeepEqual(result.value.providers.fixture.models, models);
  assert.deepEqual(result.value.providers.fixture.models[0].input, []);
  assert.deepEqual(result.value.providers.fixture.models[0].compat,
    { chatTemplateKwargs: {}, chatTemplateArgs: {} });
  const converted = await dshToNative(bundle, result.value.providers);
  assert.equal(converted.providers[0].models[0].id, 'offline-runtime-model');
});
console.log(`First-party configuration: ${passed} PASS; metadata only, no model/API/authentication requests.`);
