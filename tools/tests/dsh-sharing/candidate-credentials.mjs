import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const [helper, bundle, frameFile, evidence] = process.argv.slice(2);
const { readCredentials, writeCredentials } = await import(pathToFileURL(path.resolve(helper)).href);
const frame = JSON.parse(await fs.readFile(frameFile, 'utf8'));
assert.equal(frame.records.length, 0);
assert.equal(frame.refs.length, 1);
await fs.mkdir(evidence, { recursive: true });
const filename = path.join(evidence, '.credentials.yaml');
await fs.writeFile(filename, '# KEEP-CANDIDATE-COMMENT\nversion: 1\nrefs:\n  DAFEIYU_PROVIDER_FIXTURE_OLD: SYNTHETIC_OLD\n  FOREIGN_KEEP: SYNTHETIC_FOREIGN\nrecords:\n  llm-pi-ai/fixture-route:\n    kind: api-key\n    key: SYNTHETIC_OLD\n  llm-pi-ai/openai-codex:\n    kind: grant\n    payload:\n      type: oauth\n      access: SYNTHETIC_ACCESS\n      refresh: SYNTHETIC_REFRESH\n      expires: 5000\n', { mode: 0o600 });
let passed = 0;
const names = [];
async function test(name, block) { await block(); passed++; names.push(name); console.log(`PASS ${name}`); }
const before = await readCredentials(bundle, evidence);
await test('production-generated staging frame persists fresh reference while preserving active refs records and comments', async () => {
  const result = await writeCredentials(bundle, evidence, { ...frame, expectedRevision: before.revision });
  const staged = await readCredentials(bundle, evidence);
  assert.equal(result.applied, 1);
  assert.equal(staged.refs.DAFEIYU_PROVIDER_FIXTURE_OLD, before.refs.DAFEIYU_PROVIDER_FIXTURE_OLD);
  assert.equal(staged.refs.FOREIGN_KEEP, before.refs.FOREIGN_KEEP);
  assert.equal(staged.refs[frame.refs[0].ref], frame.refs[0].value);
  assert.deepEqual(staged.records, before.records);
  assert((await fs.readFile(filename, 'utf8')).includes('# KEEP-CANDIDATE-COMMENT'));
});
await test('stale credential revision refuses cleanup without touching old or staged credentials', async () => {
  const stagedBytes = await fs.readFile(filename, 'utf8');
  await assert.rejects(writeCredentials(bundle, evidence, { expectedRevision: before.revision,
    refs: [{ ref: 'DAFEIYU_PROVIDER_FIXTURE_OLD', value: null }], records: [] }));
  assert.equal(await fs.readFile(filename, 'utf8'), stagedBytes);
});
await test('separate post-confirmation writer removes only retired owned key and simple route record', async () => {
  const staged = await readCredentials(bundle, evidence);
  await writeCredentials(bundle, evidence, { expectedRevision: staged.revision,
    refs: [{ ref: 'DAFEIYU_PROVIDER_FIXTURE_OLD', value: null }], records: [{ provider: 'fixture-route', record: null }] });
  const final = await readCredentials(bundle, evidence);
  assert.equal(final.refs.DAFEIYU_PROVIDER_FIXTURE_OLD, undefined);
  assert.equal(final.refs[frame.refs[0].ref], frame.refs[0].value);
  assert.equal(final.refs.FOREIGN_KEEP, before.refs.FOREIGN_KEEP);
  assert.equal(final.records['llm-pi-ai/fixture-route'], undefined);
  assert.deepEqual(final.records['llm-pi-ai/openai-codex'], before.records['llm-pi-ai/openai-codex']);
  assert((await fs.readFile(filename, 'utf8')).includes('# KEEP-CANDIDATE-COMMENT'));
});
await fs.writeFile(path.join(evidence, 'report.json'), JSON.stringify({ passed, failures: 0, tests: names,
  pinnedCredentialWriterValidated: true, androidRuntimeValidated: false, realSettingsCasValidated: false,
  publicRequests: 0, paidRequests: 0 }, null, 2));
console.log(`Pinned candidate credential storage: ${passed} PASS; real storage, synthetic values, no provider/auth/model network request.`);
