import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
const [testRoot, bundle, adapter] = process.argv.slice(2);
const { restore } = await import(pathToFileURL(adapter).href);
const { readCredentials } = await import(pathToFileURL(path.join(path.dirname(adapter), 'dym-persistence.mjs')).href);
const require = createRequire(path.join(bundle, 'package.json'));
const { initProfile } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-app-boot')).href);
const sample = JSON.parse(await fs.readFile(path.join(testRoot, 'owned-data/.dafeiyu/config/providers.json'), 'utf8'));
const base = path.join(testRoot, 'credential-cases');
await fs.mkdir(base, { recursive: true });
let passed = 0;
function check(value, label) { if (!value) throw new Error(label); passed++; process.stdout.write(`PASS ${label}\n`); }
async function run(name, credential, extra = null) {
  const home = path.join(base, name, 'home'), staging = path.join(base, name, 'staging');
  await fs.mkdir(path.join(home, 'profiles'), { recursive: true });
  await initProfile(path.join(home, 'profiles/web'), ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app']);
  await fs.mkdir(path.join(staging, '.dafeiyu/config'), { recursive: true });
  const native = structuredClone(sample); native.credentials['om-fixture'] = credential;
  await fs.writeFile(path.join(staging, '.dafeiyu/config/providers.json'), JSON.stringify(native));
  if (extra) await fs.writeFile(path.join(staging, '.dafeiyu/config/credentials.json'), JSON.stringify(extra));
  const result = await restore(bundle, home, staging);
  return { home, result, credentials: await readCredentials(bundle, home) };
}
const injection = await run('forbidden-env', { type: 'api_key', key: 'fixture-key', env: { NODE_OPTIONS: '--eval=unsafe-fixture' } });
check(injection.result.warnings.length > 0 && !injection.result.providerOrder, 'runtime control environment rejected before provider mutation');
check(Object.keys(injection.credentials.refs).length === 0 && Object.keys(injection.credentials.records).length === 0, 'rejected injected credential leaves real store unchanged');
const oauth = await run('oauth', { type: 'oauth', refresh: 'fixture-refresh', access: 'fixture-access', expires: 1900000000000 });
check(oauth.result.warnings.length === 0 && oauth.credentials.records['llm-pi-ai/om-fixture'].kind === 'grant'
  && oauth.credentials.records['llm-pi-ai/om-fixture'].payload.refresh === 'fixture-refresh', 'OAuth grant restored through fixed first-party record writer');
const cloudJson = JSON.stringify({ type: 'service_account', project_id: 'fixture-project', private_key: 'fixture-only' });
const cloud = await run('cloud-file', { type: 'api_key', env: { GOOGLE_PROJECT_ID: 'fixture-project' }, files: { google_adc: cloudJson } });
const record = cloud.credentials.records['llm-pi-ai/om-fixture'];
check(cloud.result.warnings.length === 0 && record.env.GOOGLE_APPLICATION_CREDENTIALS.startsWith(cloud.home + path.sep), 'cloud credential file rebound inside actual target home');
check(cloud.credentials.portableFiles['llm-pi-ai/om-fixture'].google_adc === cloudJson, 'cloud credential bytes preserved by controlled first-party readback');
const unknown = await run('unknown-ref', { type: 'api_key', key: 'fixture-key' }, { version: 1, officialRef: 'DEEPSEEK_API_KEY',
  refs: { UNRELATED_IDENTITY: 'must-stay-opaque' }, records: {}, portableFiles: {} });
check(unknown.result.warnings.length === 0 && !Object.hasOwn(unknown.credentials.refs, 'UNRELATED_IDENTITY'), 'unrelated reference remains retained metadata and cannot modify real credential namespace');
await fs.writeFile(path.join(base, 'result.json'), JSON.stringify({ passed, paidModelRequests: 0, firstPartyCredentials: true, cloudsRebound: true }));
