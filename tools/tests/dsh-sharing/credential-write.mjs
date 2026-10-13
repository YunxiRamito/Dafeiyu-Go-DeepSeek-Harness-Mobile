import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';
const [helper, bundle, evidence] = process.argv.slice(2);
const { readCredentials, writeCredentials } = await import(pathToFileURL(path.resolve(helper)).href);
let passed = 0;
async function test(name, fn) { await fn(); passed++; console.log(`PASS ${name}`); }
await fs.mkdir(evidence, { recursive: true });
const filename = path.join(evidence, '.credentials.yaml');
await fs.writeFile(filename, '# PRESERVE-COMMENT\nversion: 1\nrefs:\n  FOREIGN_KEY: KEEP_FOREIGN\nrecords: {}\n', { mode: 0o600 });
let revision;
await test('exact first-party writer persists API refs and OAuth records while preserving foreign entries/comments', async () => {
  revision = (await readCredentials(bundle, evidence)).revision;
  const result = await writeCredentials(bundle, evidence, { expectedRevision: revision,
    refs: [{ ref: 'DEEPSEEK_API_KEY', value: 'OFFLINE_NO_NETWORK' }], records: [{ provider: 'openai-codex', record: {
      kind: 'grant', payload: { type: 'oauth', access: 'OFFLINE_ACCESS', refresh: 'OFFLINE_REFRESH', expires: 3000 } } }] });
  const read = await readCredentials(bundle, evidence);
  assert.equal(read.refs.FOREIGN_KEY, 'KEEP_FOREIGN'); assert.equal(read.refs.DEEPSEEK_API_KEY, 'OFFLINE_NO_NETWORK');
  assert.equal(read.records['llm-pi-ai/openai-codex'].payload.refresh, 'OFFLINE_REFRESH');
  assert.equal(result.applied, 2); assert.equal(result.revision, read.revision);
  assert((await fs.readFile(filename, 'utf8')).includes('# PRESERVE-COMMENT'));
});
await test('stale document revision is refused before any credential modification', async () => {
  const before = await fs.readFile(filename, 'utf8');
  await assert.rejects(writeCredentials(bundle, evidence, { expectedRevision: revision,
    refs: [{ ref: 'FOREIGN_KEY', value: 'WRONG' }], records: [] }));
  assert.equal(await fs.readFile(filename, 'utf8'), before);
});
await test('cloud file content is materialized in controlled home and recovered as portable content', async () => {
  const before = await readCredentials(bundle, evidence);
  const adc = JSON.stringify({ type: 'authorized_user', client_id: 'OFFLINE', client_secret: 'OFFLINE', refresh_token: 'OFFLINE' });
  await writeCredentials(bundle, evidence, { expectedRevision: before.revision, refs: [], records: [{ provider: 'google-vertex',
    record: { kind: 'api-key', env: { GOOGLE_CLOUD_PROJECT: 'offline' } }, files: { google_adc: adc } }] });
  const read = await readCredentials(bundle, evidence);
  assert.equal(read.portableFiles['llm-pi-ai/google-vertex'].google_adc, adc);
  const env = read.records['llm-pi-ai/google-vertex'].env;
  assert.equal(env.GOOGLE_CLOUD_PROJECT, 'offline'); assert(env.GOOGLE_APPLICATION_CREDENTIALS.startsWith('/dafeiyu-dsh-home/credential-files/google-vertex/'));
  const file = path.join(evidence, env.GOOGLE_APPLICATION_CREDENTIALS.slice('/dafeiyu-dsh-home/'.length));
  assert.equal(await fs.readFile(file, 'utf8'), adc);
  if (process.platform !== 'win32') assert.equal((await fs.stat(file)).mode & 0o077, 0);
});
await test('explicit removal deletes only specified owned record and ref', async () => {
  const before = await readCredentials(bundle, evidence);
  await writeCredentials(bundle, evidence, { expectedRevision: before.revision,
    refs: [{ ref: 'DEEPSEEK_API_KEY', value: null }], records: [{ provider: 'openai-codex', record: null }] });
  const read = await readCredentials(bundle, evidence);
  assert.equal(read.refs.DEEPSEEK_API_KEY, undefined); assert.equal(read.records['llm-pi-ai/openai-codex'], undefined);
  assert.equal(read.refs.FOREIGN_KEY, 'KEEP_FOREIGN'); assert(read.records['llm-pi-ai/google-vertex']);
});
await test('duplicate refs, invalid cloud JSON and escaping route fail without touching credentials', async () => {
  const read = await readCredentials(bundle, evidence);
  const before = await fs.readFile(filename, 'utf8');
  for (const value of [
    { refs: [{ ref: 'X', value: 'A' }, { ref: 'X', value: 'B' }], records: [] },
    { refs: [], records: [{ provider: '../escape', record: { kind: 'api-key', key: 'X' } }] },
    { refs: [], records: [{ provider: 'google-vertex', record: { kind: 'api-key' }, files: { google_adc: '{' } }] },
  ]) await assert.rejects(writeCredentials(bundle, evidence, { expectedRevision: read.revision, ...value }));
  assert.equal(await fs.readFile(filename, 'utf8'), before);
});
await test('cloud path outside controlled home is refused instead of reading unrelated data', async () => {
  const before = await readCredentials(bundle, evidence);
  const text = await fs.readFile(filename, 'utf8');
  await assert.rejects(writeCredentials(bundle, evidence, { expectedRevision: before.revision, refs: [], records: [{ provider: 'amazon-bedrock',
    record: { kind: 'api-key', env: { AWS_SHARED_CREDENTIALS_FILE: path.join(path.dirname(evidence), 'unrelated.txt') } } }] }));
  assert.equal(await fs.readFile(filename, 'utf8'), text);
});
console.log(`Credential sharing: ${passed} PASS; real pinned storage only, no login/model/provider request.`);
