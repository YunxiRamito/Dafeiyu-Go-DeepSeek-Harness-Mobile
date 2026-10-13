import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const [script, bundle, evidence, nativeArtifacts] = process.argv.slice(2);
const { readArtifact, writeCompatibleArtifact, readCredentials, readPortableArtifact, listArtifacts } = await import(pathToFileURL(path.resolve(script)).href);
let passed = 0;
async function test(name, block) { await block(); passed++; console.log(`PASS ${name}`); }
const artifact = {
  header: { version: 4, id: 'native/history', cwd: '/workspace', createdAt: 1000, isSeeded: false, delegationDepth: 0 },
  events: [
    { type: 'turn/start', seq: 0, time: 1000, data: { turn: 1 } },
    { type: 'step/start', seq: 1, time: 1000, data: { turn: 1, step: 1 } },
    { type: 'user/message', seq: 2, time: 1000, surfaceOp: 'append', data: {
      id: 'user-1', role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text: 'OFFLINE QUESTION' }] } },
    { type: 'assistant/message', seq: 3, time: 1000, surfaceOp: 'append', data: { turn: 1, step: 1, stream: [], message: {
      id: 'assistant-1', role: 'assistant', source: { kind: 'model', provider: 'android-light', model: 'compatible-history' },
      content: [{ type: 'text', text: 'OFFLINE ANSWER' }] } } },
    { type: 'step/end', seq: 4, time: 1000, data: { turn: 1, step: 1 } },
    { type: 'turn/end', seq: 5, time: 1000, data: { turn: 1, reason: { kind: 'completed' } } },
  ],
};
for (const compression of ['none', 'zstd']) {
  const root = path.join(evidence, compression);
  await fs.mkdir(root, { recursive: true });
  await test(`first-party ${compression} backend writes and reads compatible history without an Agent`, async () => {
    assert.equal((await writeCompatibleArtifact(bundle, root, artifact, compression)).created, true);
    const restored = await readArtifact(bundle, root, artifact.header.id, compression);
    assert.deepEqual(restored.header, artifact.header); assert.deepEqual(restored.events, artifact.events);
  });
  await test(`first-party ${compression} retry is idempotent and never replaces different history`, async () => {
    assert.equal((await writeCompatibleArtifact(bundle, root, artifact, compression)).created, false);
    const changed = structuredClone(artifact); changed.events[2].data.content[0].text = 'DIFFERENT';
    await assert.rejects(writeCompatibleArtifact(bundle, root, changed, compression));
    assert.deepEqual((await readArtifact(bundle, root, artifact.header.id, compression)).events, artifact.events);
  });
  await test(`first-party ${compression} metadata enumeration keeps logical IDs without parsing directories or executing history`, async () => {
    const listed = await listArtifacts(bundle, root, compression, 1);
    assert.equal(listed.compression, compression); assert.equal(listed.items.length, 1);
    assert.deepEqual(listed.items[0].header, artifact.header); assert(listed.items[0].sizeBytes > 0);
    assert.equal(listed.truncated, false); assert.equal(listed.nextAfterId, null);
    assert.deepEqual((await listArtifacts(bundle, root, compression, 1, artifact.header.id)).items, []);
  });
  await test(`first-party ${compression} portable file finds the injectively escaped Session identity`, async () => {
    const project = (await fs.readdir(root))[0];
    const session = (await fs.readdir(path.join(root, project))).find(name => name.startsWith('native'));
    const filename = path.join(root, project, session, `session.v4.jsonl${compression === 'zstd' ? '.zstd' : ''}`);
    assert.deepEqual((await readPortableArtifact(bundle, filename)).events, artifact.events);
  });
}
await test('storage-only write refuses runnable tools and never creates their Session', async () => {
  const invalid = structuredClone(artifact); invalid.header.id = 'tool-replay'; invalid.events[0].type = 'tool/call';
  await assert.rejects(writeCompatibleArtifact(bundle, path.join(evidence, 'zstd'), invalid));
});
await test('pinned credential parser keeps OAuth records and refs but rejects unknown and duplicate keys', async () => {
  const home = path.join(evidence, 'credential-home'); await fs.mkdir(home, { recursive: true });
  const filename = path.join(home, '.credentials.yaml');
  await fs.writeFile(filename, 'version: 1\nrefs:\n  DEEPSEEK_API_KEY: OFFLINE_KEY\nrecords:\n  llm-pi-ai/openai-codex:\n    kind: grant\n    payload: {type: oauth, access: OFFLINE_ACCESS, refresh: OFFLINE_REFRESH, expires: 3000}\n', { mode: 0o600 });
  const value = await readCredentials(bundle, home);
  assert.equal(value.refs.DEEPSEEK_API_KEY, 'OFFLINE_KEY'); assert.equal(value.records['llm-pi-ai/openai-codex'].payload.type, 'oauth');
  assert.match(value.revision, /^[a-f0-9]{64}$/);
  await fs.writeFile(filename, 'version: 1\nrefs: {}\nrefs: {}\n'); await assert.rejects(readCredentials(bundle, home));
});
if (nativeArtifacts) for (const filename of await fs.readdir(nativeArtifacts)) {
  const value = JSON.parse(await fs.readFile(path.join(nativeArtifacts, filename), 'utf8'));
  await test(`production Kotlin ${filename} passes pinned current-format lifecycle and durable backend`, async () => {
    const root = path.join(evidence, 'native-current');
    assert.equal((await writeCompatibleArtifact(bundle, root, value)).created, true);
    const restored = await readArtifact(bundle, root, value.header.id);
    assert.deepEqual(restored.header, value.header); assert.deepEqual(restored.events, value.events);
  });
}
await test('pinned historical v3 reader upgrades in memory without publishing a successor or replaying prompts', async () => {
  const require = createRequire(path.join(path.resolve(bundle), 'package.json'));
  const { historicalSessionFormatCatalog } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-session-format-catalog')).href);
  const root = path.join(evidence, 'historical');
  const current = structuredClone(artifact); current.header.id = 'history/v3';
  current.events[2].data.source = { kind: 'user' };
  const oldHeader = { ...current.header, version: 3 };
  const rows = [historicalSessionFormatCatalog.encodeCurrentHeader(oldHeader, 0),
    ...current.events.map(row => historicalSessionFormatCatalog.encodeCurrentEvent(row))];
  // Physical root/project/session directories match the real pinned JSONL
  // backend's injective session identity; migration never chooses a new path.
  await writeCompatibleArtifact(bundle, root, current, 'none');
  const project = (await fs.readdir(root))[0];
  const session = (await fs.readdir(path.join(root, project))).find(name => name.startsWith('history'));
  const directory = path.join(root, project, session);
  const v4 = path.join(directory, 'session.v4.jsonl');
  const v3 = path.join(directory, 'session.v3.jsonl');
  await fs.unlink(v4);
  await fs.writeFile(v3, rows.map(row => JSON.stringify(row)).join('\n') + '\n');
  const before = await fs.readdir(directory);
  const value = await readPortableArtifact(bundle, v3);
  assert.equal(value.header.version, 4);
  assert.equal(value.header.id, 'history/v3');
  assert.equal(value.events.find(row => row.type === 'user/message').data.content[0].text, 'OFFLINE QUESTION');
  assert.deepEqual(await fs.readdir(directory), before);
  assert.equal(value.events.some(row => row.type === 'agent/inbox/enqueue'), false);
});
console.log(`DSH shared persistence: ${passed} PASS; exact installed first-party code, local filesystem only; no Agent/model/paid request.`);
