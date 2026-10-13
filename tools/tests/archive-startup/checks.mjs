import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const [launcher, destination] = process.argv.slice(2);
await fs.mkdir(destination, { recursive: true });
const logModule = `import fs from 'node:fs'; export function record(value) { fs.appendFileSync(process.env.DAFEIYU_ARCHIVE_TEST_LOG, value + String.fromCharCode(10)); }`;
await fs.writeFile(path.join(destination, 'log.mjs'), logModule);
await fs.writeFile(path.join(destination, 'preload.mjs'), `import {record} from './log.mjs'; globalThis.__archiveFixturePrepared = true; record('preload');`);
await fs.writeFile(path.join(destination, 'archive.mjs'), `import {record} from './log.mjs';
export function assertFixedDshArchivePreload(anchor, directory) {
  if (!globalThis.__archiveFixturePrepared || process.env.DAFEIYU_ARCHIVE_TEST_MODE === 'refused') throw new Error('archive_preload_fixture_refused');
  if (anchor !== '/dafeiyu-dsh/node_modules/@deepseek-ai/dsh/package.json' || directory !== '/dafeiyu-dsh-home/dafeiyu-connection-plugin/workspace-lifecycle') throw new Error('archive_preload_fixture_scope');
  record('assert');
}`);
await fs.writeFile(path.join(destination, 'old-archive.mjs'), 'export const oldInterface = true;');
await fs.writeFile(path.join(destination, 'android-sandbox.mjs'), `import {record} from './log.mjs'; export function installAndroidSandbox(bundle, native, mounts) {
if (!mounts.includes('/dafeiyu-connection-slot')) throw new Error('fixture_missing_plugin_readonly_mount'); record('sandbox'); }`);
await fs.writeFile(path.join(destination, 'android-persistence.mjs'), `import {record} from './log.mjs'; export function installAndroidPersistence() {record('persistence'); return () => record('removePersistence');}`);
await fs.writeFile(path.join(destination, 'android-provider-order.mjs'), `import {record} from './log.mjs'; export function installAndroidProviderOrder() {record('providerOrder'); return () => record('removeOrder');}`);
await fs.writeFile(path.join(destination, 'cli.mjs'), `import {record} from './log.mjs'; export function runCli() {
if (process.argv[1].split(String.fromCharCode(92)).join('/') !== '/dafeiyu-dsh/node_modules/@deepseek-ai/dsh/lib/bin.js' || process.argv.slice(2).join(' ') !== 'web --host 127.0.0.1 --port 5432 --no-open') throw new Error('fixture_cli_argv'); record('runCli'); }`);
await fs.writeFile(path.join(destination, 'loader.mjs'), `import {registerHooks} from 'node:module'; import {pathToFileURL} from 'node:url';
const root = ${JSON.stringify(destination)}; const parent = ${JSON.stringify(pathToFileURL(launcher).href)};
registerHooks({ resolve(specifier, context, next) {
  let replacement;
  if (specifier.endsWith('/dafeiyu-connection-slot/package/scripts/archive-preload.mjs')) replacement = 'preload.mjs';
  if (specifier.endsWith('/dafeiyu-connection-slot/package/lib/framework/remote-archive-dsh.js')) replacement = process.env.DAFEIYU_ARCHIVE_TEST_MODE === 'old' ? 'old-archive.mjs' : 'archive.mjs';
  if (specifier.endsWith('/dafeiyu-dsh/node_modules/@deepseek-ai/dsh/lib/bin.js')) replacement = 'cli.mjs';
  if (context.parentURL === parent && ['android-sandbox.mjs','android-persistence.mjs','android-provider-order.mjs'].some(name => specifier === './'+name)) replacement = specifier.slice(2);
  return replacement ? {url: pathToFileURL(root + '/' + replacement).href, shortCircuit: true} : next(specifier, context);
}});`);

const preload = '/dafeiyu-connection-slot/package/scripts/archive-preload.mjs';
const launchArguments = ['/dafeiyu-dsh', '/data/app/fixed/lib/x86_64', 'web', '--host', '127.0.0.1', '--port', '5432', '--no-open'];
let passed = 0;
async function test(name, { missingPreload = false, envChange = {}, mode = 'normal', refused = null } = {}) {
  const log = path.join(destination, `run-${passed}.log`);
  const env = { ...process.env, DSH_HOME: '/dafeiyu-dsh-home',
    DAFEIYU_DSH_INSTALL_ANCHOR: '/dafeiyu-dsh/node_modules/@deepseek-ai/dsh/package.json',
    DAFEIYU_DSH_ARCHIVE_DIRECTORY: '/dafeiyu-dsh-home/dafeiyu-connection-plugin/workspace-lifecycle',
    DAFEIYU_ARCHIVE_TEST_LOG: log, DAFEIYU_ARCHIVE_TEST_MODE: mode, ...envChange };
  delete env.NODE_OPTIONS;
  const args = ['--import', pathToFileURL(path.join(destination, 'loader.mjs')).href];
  if (!missingPreload) args.push('--import', preload);
  args.push(launcher, ...launchArguments);
  const result = spawnSync(process.execPath, args, { env, timeout: 15000, encoding: 'utf8', windowsHide: true });
  assert.ifError(result.error);
  const events = (await fs.readFile(log, 'utf8').catch(() => '')).trim().split('\n').filter(Boolean);
  if (refused) {
    assert.notEqual(result.status, 0, name);
    assert(result.stderr.includes(refused), `${name}: ${result.stderr}`);
    assert(!events.includes('runCli') && !events.includes('sandbox') && !events.includes('persistence'), name);
  } else {
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(events, ['preload','assert','sandbox','persistence','providerOrder','runCli','removePersistence','removeOrder']);
  }
  passed++; console.log(`PASS ${name}`);
}
await test('production Android launcher checks preloader before adapters and preserves actual CLI dispatch order');
await test('missing actual --import refuses CLI creation', { missingPreload: true, refused: 'ANDROID_DSH_ARCHIVE_STARTUP' });
await test('another owning package anchor refuses CLI creation', { envChange: { DAFEIYU_DSH_INSTALL_ANCHOR: '/other/package.json' }, refused: 'ANDROID_DSH_ARCHIVE_STARTUP' });
await test('another archive journal refuses CLI creation', { envChange: { DAFEIYU_DSH_ARCHIVE_DIRECTORY: '/other/workspace-lifecycle' }, refused: 'ANDROID_DSH_ARCHIVE_STARTUP' });
await test('another DSH home refuses CLI creation', { envChange: { DSH_HOME: '/other-home' }, refused: 'ANDROID_DSH_ARCHIVE_STARTUP' });
await test('preloader core refusal prevents all adapters and CLI creation', { mode: 'refused', refused: 'archive_preload_fixture_refused' });
await test('old frozen archive interface fails closed', { mode: 'old', refused: 'ANDROID_DSH_ARCHIVE_PRELOAD_INTERFACE' });
console.log(`RESULT ${passed}/${passed} PASS actualNode=true productionAndroidLauncher=true syntheticPreloaderAdaptersCli=true actualAndroid=false paidRequests=0`);
