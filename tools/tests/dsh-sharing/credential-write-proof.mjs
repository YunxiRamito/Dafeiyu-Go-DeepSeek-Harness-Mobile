import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';
const [bundle, evidence] = process.argv.slice(2);
const require = createRequire(path.join(path.resolve(bundle), 'package.json'));
const { Context, Service } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);
const { LocalCredentialProvider, parseCredentialsDocument } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-credentials-local')).href);
const { credentialRef, credentialKey } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-credentials')).href);
const { createLaunchEnvironmentSnapshot } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-launch-environment')).href);
await fs.mkdir(evidence, { recursive: true });
const file = path.join(evidence, '.credentials.yaml');
await fs.writeFile(file, '# KEEP THIS COMMENT\nversion: 1\nrefs:\n  OTHER_KEY: KEEP_OTHER\nrecords: {}\n', { mode: 0o600 });
const ctx = new Context();
ctx.reflect.provide('launchEnvironment', createLaunchEnvironmentSnapshot([{ source: 'process', values: {} }]));
const local = new LocalCredentialProvider(ctx, { dshHome: evidence, watch: false });
const release = [];
for await (const dispose of local[Service.init]()) if (typeof dispose === 'function') release.push(dispose);
try {
  await local.set(credentialRef('DEEPSEEK_API_KEY'), 'OFFLINE_KEY');
  await local.modifyRecord(credentialKey('llm-pi-ai', 'openai-codex'), async () => ({ kind: 'grant', payload: {
    type: 'oauth', access: 'OFFLINE_ACCESS', refresh: 'OFFLINE_REFRESH', expires: 3000 } }));
  await local.modifyRecord(credentialKey('llm-pi-ai', 'amazon-bedrock'), async () => ({ kind: 'api-key', env: { AWS_PROFILE: 'offline' } }));
  const text = await fs.readFile(file, 'utf8');
  const value = parseCredentialsDocument(text, file);
  assert.equal(value.refs.get('DEEPSEEK_API_KEY'), 'OFFLINE_KEY');
  assert.equal(value.refs.get('OTHER_KEY'), 'KEEP_OTHER');
  assert.equal(value.records.get('llm-pi-ai/openai-codex').payload.type, 'oauth');
  assert.equal(value.records.get('llm-pi-ai/amazon-bedrock').env.AWS_PROFILE, 'offline');
  assert(text.includes('# KEEP THIS COMMENT'));
  await local.deleteRecord(credentialKey('llm-pi-ai', 'openai-codex'));
  await local.unset(credentialRef('DEEPSEEK_API_KEY'));
  const final = parseCredentialsDocument(await fs.readFile(file, 'utf8'), file);
  assert.equal(final.refs.get('OTHER_KEY'), 'KEEP_OTHER');
  assert.equal(final.refs.has('DEEPSEEK_API_KEY'), false);
  assert.equal(final.records.has('llm-pi-ai/openai-codex'), false);
  console.log('PASS exact first-party lock-safe credential writer preserves unrelated keys/comments, OAuth/cloud records, removes only explicit keys; no login/provider call');
} finally {
  for (const dispose of release.reverse()) await dispose();
  await ctx.fiber.dispose();
}
