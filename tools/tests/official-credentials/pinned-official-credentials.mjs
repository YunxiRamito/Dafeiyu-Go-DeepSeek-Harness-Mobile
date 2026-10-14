import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';

const [helper, bundle, frameFile, evidence] = process.argv.slice(2);
const { readCredentials, writeCredentials } = await import(pathToFileURL(path.resolve(helper)).href);
const require = createRequire(path.join(path.resolve(bundle), 'package.json'));
const packageJson = JSON.parse(await fs.readFile(require.resolve('@deepseek-ai/dsh-llm-deepseek-api-key/package.json'), 'utf8'));
assert.equal(packageJson.version, '0.2.1-alpha.1');
assert.equal(packageJson.license, 'MIT');
const { Config, resolveAdapterOptions } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-llm-deepseek-api-key')).href);
const { SettingsForms } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-settings')).href);
const frame = JSON.parse(await fs.readFile(frameFile, 'utf8'));
assert.equal(frame.records.length, 0);
assert.equal(frame.refs.length, 1);
await fs.mkdir(evidence, { recursive: true });
const filename = path.join(evidence, '.credentials.yaml');
await fs.writeFile(filename, '# KEEP-OFFICIAL-COMMENT\nversion: 1\nrefs:\n  DEEPSEEK_API_KEY: SYNTHETIC_OLD_KEY\n  FOREIGN_KEEP: SYNTHETIC_FOREIGN\nrecords:\n  llm-pi-ai/other:\n    kind: api-key\n    key: SYNTHETIC_UNRELATED\n', { mode: 0o600 });
let passed = 0;
const names = [];
async function test(name, block) { await block(); passed++; names.push(name); console.log(`PASS ${name}`); }
const before = await readCredentials(bundle, evidence);
await test('pinned credential helper stages candidate while preserving default ref and unrelated records/comments', async () => {
  const result = await writeCredentials(bundle, evidence, { ...frame, expectedRevision: before.revision });
  const staged = await readCredentials(bundle, evidence);
  assert.equal(result.applied, 1);
  assert.equal(staged.refs.DEEPSEEK_API_KEY, before.refs.DEEPSEEK_API_KEY);
  assert.equal(staged.refs.FOREIGN_KEEP, before.refs.FOREIGN_KEEP);
  assert.equal(staged.refs[frame.refs[0].ref], frame.refs[0].value);
  assert.deepEqual(staged.records, before.records);
  assert((await fs.readFile(filename, 'utf8')).includes('# KEEP-OFFICIAL-COMMENT'));
});
await test('stale writer revision rejects candidate replacement without damaging old or new key', async () => {
  const stagedBytes = await fs.readFile(filename);
  await assert.rejects(writeCredentials(bundle, evidence, { expectedRevision: before.revision,
    refs: [{ ref: frame.refs[0].ref, value: 'SYNTHETIC_THIRD_KEY' }], records: [] }));
  assert((await fs.readFile(filename)).equals(stagedBytes));
});
await test('actual DeepSeek provider snapshot accepts fresh ref and never aliases it to default', async () => {
  const options = resolveAdapterOptions({ apiKeyEnv: frame.refs[0].ref });
  assert.equal(options.apiKeyEnv, frame.refs[0].ref);
  const absent = 'DAFEIYU_PROVIDER_DEEPSEEK_22222222222222222222222222222222';
  assert.equal(resolveAdapterOptions({ apiKeyEnv: absent }).apiKeyEnv, absent);
  assert.equal((await readCredentials(bundle, evidence)).refs[absent], undefined);
});
await test('actual SettingsForms describes official raw durable apiKeyEnv with unrelated options retained', async () => {
  const raw = { apiKeyEnv: frame.refs[0].ref, thinking: 'enabled' };
  const entry = { id: 1, options: { id: 'llm-deepseek-api-key', config: raw }, fiber: {
    uid: 1, state: 2, runtime: { Config }, config: Config(raw), ctx: {} } };
  const forms = Object.assign(Object.create(SettingsForms.prototype), {
    revisions: new Map(), presentations: new Map(), ownerContext: { emit() {},
      configEditor: { configuration() { return [{ entry, inherited: {}, override: raw }]; } } },
  });
  const result = forms.describe({ redactSecrets: true })[0];
  assert.equal(result.ns, 'llm-deepseek-api-key');
  assert.equal(result.user.apiKeyEnv, frame.refs[0].ref);
  assert.equal(result.user.thinking, 'enabled');
  assert.equal(JSON.stringify(result).includes(frame.refs[0].value), false);
});
await fs.writeFile(path.join(evidence, 'report.json'), JSON.stringify({ passed, failures: 0, tests: names,
  pinnedCredentialWriterValidated: true, pinnedOfficialSettingsMetadataValidated: true,
  androidRuntimeValidated: false, realSettingsCasValidated: false, publicRequests: 0, paidRequests: 0 }, null, 2));
console.log(`Pinned official storage and metadata: ${passed} PASS; fixed SDK, synthetic values, no authentication or model request.`);
