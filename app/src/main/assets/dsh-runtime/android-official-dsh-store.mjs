// Dafeiyu-GO (MIT). Isolated Android controlled-storage candidate.
// Fixed DSH 0.2.1-alpha.1 storage APIs only; no configured plugin is mounted,
// no service is started and no key, parser error or raw launch input is logged.
import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { isDeepStrictEqual, parseEnv } from 'node:util';
import { readCredentials, writeCredentials } from './shared-persistence.mjs';

const VERSION = '0.2.1-alpha.1';
const REALM = 'android-dsh-storage-v1';
const MAX_FILE = 8 * 1024 * 1024;
const HEX = /^[a-f0-9]{64}$/;
const ID = /^[a-f0-9]{32}$/;
const canonical = value => Array.isArray(value) ? value.map(canonical)
  : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value;
export const digest = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : JSON.stringify(canonical(value))).digest('hex');
const check = (value, code = 'DshConflict') => { if (!value) { const error = new Error(code); error.safeCode = code; throw error; } };
const same = (left, right) => check(isDeepStrictEqual(left, right));
const reference = value => typeof value === 'string' && /^[A-Za-z_][A-Za-z0-9_]*$/.test(value);
const secret = value => value === null || typeof value === 'string' && value.length > 0 && value.length <= 512 && !/[\s\p{Cc}]/u.test(value);
const plain = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const within = (root, file) => file.startsWith(root + path.sep);
const flatten = rows => rows.flatMap(row => [row, ...(row.group && Array.isArray(row.config) ? flatten(row.config) : [])]);

async function noLinks(file) {
  let cursor = path.resolve(file);
  while (cursor !== path.dirname(cursor)) {
    try { check(!(await fs.lstat(cursor)).isSymbolicLink(), 'StoragePending'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    cursor = path.dirname(cursor);
  }
}
async function bytes(file, optional = false) {
  await noLinks(file);
  try {
    const stat = await fs.lstat(file);
    check(stat.isFile() && stat.size <= MAX_FILE, 'StoragePending');
    const value = await fs.readFile(file);
    check(value.length <= MAX_FILE, 'StoragePending');
    return value;
  } catch (error) { if (optional && error.code === 'ENOENT') return null; throw error; }
}
async function executableDigest(file) {
  await noLinks(file);
  const stat = await fs.lstat(file);
  check(stat.isFile() && stat.size > 0 && stat.size <= 192 * 1024 * 1024, 'HostUnavailable');
  const hash = createHash('sha256');
  const handle = await fs.open(file, 'r');
  try { for await (const data of handle.readableWebStream()) hash.update(Buffer.from(data)); }
  finally { await handle.close(); }
  return hash.digest('hex');
}

export async function createControlledDshStore(rootArgument, homeArgument, init, control) {
  const root = path.resolve(rootArgument), home = path.resolve(homeArgument), dir = path.join(home, 'profiles/web');
  check(path.isAbsolute(rootArgument) && path.isAbsolute(homeArgument) && typeof control === 'function', 'HostUnavailable');
  await noLinks(root); await noLinks(home);
  const binding = structuredClone(init.binding);
  check(plain(binding) && binding.schema === 1 && binding.realm === REALM && HEX.test(init.scopeId), 'HostUnavailable');
  const fixture = binding.mode === 'offline-fixture';
  if (fixture) {
    // A Windows run is explicitly a storage fixture. It can never claim an
    // Android process, AndroidKeyStore, PRoot or platform-launch PASS.
    const qa = path.resolve(process.env.DAFEIYU_ANDROID_OFFICIAL_QA_RUN ?? '');
    check(process.platform === 'win32' && process.version === 'v24.21.0' && /^G:[\\/]/i.test(qa)
      && qa.includes('official-dsh-cold-recovery-next') && within(qa, home), 'HostUnavailable');
  } else {
    check(binding.mode === 'android' && process.platform === 'linux' && process.version === 'v24.19.0'
      && ['x64', 'arm64'].includes(process.arch) && binding.architecture === process.arch
      && root === '/dafeiyu-dsh' && home === '/dafeiyu-dsh-home', 'HostUnavailable');
  }
  check(HEX.test(binding.nodeSha256) && HEX.test(binding.slotManifestDigest) && HEX.test(binding.archiveSha256), 'HostUnavailable');
  check(await executableDigest(process.execPath) === binding.nodeSha256, 'HostUnavailable');
  const require = createRequire(path.join(root, 'package.json'));
  for (const name of ['dsh', 'dsh-app-boot', 'dsh-atomic-write', 'dsh-credentials-local', 'dsh-llm-deepseek-api-key', 'dsh-launch-environment', 'dsh-credentials'])
    check(require(`@deepseek-ai/${name}/package.json`).version === VERSION, 'HostUnavailable');
  check(Array.isArray(init.runtimePins) && init.runtimePins.length >= 19 && init.runtimePins.length <= 512, 'HostUnavailable');
  const pins = new Map();
  for (const pin of init.runtimePins) {
    check(plain(pin) && typeof pin.relative === 'string' && HEX.test(pin.sha256), 'HostUnavailable');
    const file = path.resolve(root, pin.relative);
    check(within(root, file) && !pins.has(file), 'HostUnavailable'); pins.set(file, pin.sha256);
  }
  const verifyPins = async () => {
    for (const [file, hash] of pins) check(digest(await bytes(file)) === hash, 'HostUnavailable');
  };
  await verifyPins();
  const load = name => import(pathToFileURL(require.resolve(name)).href);
  const [boot, atomic, official, yaml, include, cordis, local, credentialsApi] = await Promise.all([
    load('@deepseek-ai/dsh-app-boot'), load('@deepseek-ai/dsh-atomic-write'), load('@deepseek-ai/dsh-llm-deepseek-api-key'),
    load('yaml'), load('@deepseek-ai/cordis-plugin-include'), load('@deepseek-ai/cordis'),
    load('@deepseek-ai/dsh-credentials-local'), load('@deepseek-ai/dsh-credentials'),
  ]);
  const jsYaml = require('js-yaml');
  const manifest = path.join(dir, 'package.json'), patchPath = path.join(dir, 'cordis.patch.yml');
  const homePatch = path.join(home, 'cordis.patch.yml'), credentialPath = path.join(home, '.credentials.yaml');
  const installAnchor = require.resolve('@deepseek-ai/dsh/package.json');
  const future = binding.futureLaunch;
  check(plain(future) && typeof future.cwd === 'string' && path.isAbsolute(future.cwd) && Array.isArray(future.argv)
    && future.argv.length >= 9 && future.argv.length <= 32 && future.argv.every(value => typeof value === 'string' && !value.includes('\0'))
    && plain(future.environment) && future.environment.DSH_HOME === home && Array.isArray(future.files), 'HostUnavailable');
  for (const [name, value] of Object.entries(future.environment)) check(reference(name) && typeof value === 'string' && !value.includes('\0'), 'HostUnavailable');
  check(future.environment.NODE_OPTIONS === undefined && future.environment.NODE_PATH === undefined
    && future.environment.DAFEIYU_DSH_INSTALL_ANCHOR === installAnchor
    && future.environment.DAFEIYU_DSH_ARCHIVE_DIRECTORY === path.join(home, 'dafeiyu-connection-plugin/workspace-lifecycle')
    && future.environment.OTEL_SDK_DISABLED === 'true', 'HostUnavailable');
  const requiredRoles = new Set(['android-launch', 'android-sandbox', 'android-provider-order', 'android-persistence',
    'android-mobile-ui', 'archive-preload', 'remote-archive-dsh', 'remote-archive']);
  const roleFiles = new Map();
  check(future.files.length >= requiredRoles.size && future.files.length <= 96, 'HostUnavailable');
  for (const file of future.files) {
    check(plain(file) && typeof file.role === 'string' && !roleFiles.has(file.role) && path.isAbsolute(file.file)
      && HEX.test(file.sha256) && Number.isSafeInteger(file.bytes) && file.bytes > 0 && file.bytes <= MAX_FILE, 'HostUnavailable');
    roleFiles.set(file.role, file);
  }
  check([...requiredRoles].every(role => roleFiles.has(role)), 'HostUnavailable');
  if (!fixture) {
    const launchRoot = '/dafeiyu-dsh-launch';
    for (const role of [...requiredRoles].filter(role => role.startsWith('android-')))
      check(roleFiles.get(role).file === `${launchRoot}/${role}.mjs`, 'HostUnavailable');
    check(roleFiles.get('archive-preload').file === '/dafeiyu-connection-slot/package/scripts/archive-preload.mjs'
      && roleFiles.get('remote-archive-dsh').file === '/dafeiyu-connection-slot/package/lib/framework/remote-archive-dsh.js'
      && roleFiles.get('remote-archive').file === '/dafeiyu-connection-slot/package/lib/framework/remote-archive.js', 'HostUnavailable');
    check(future.argv[0] === '/usr/local/bin/node' && future.argv[1] === '--import'
      && future.argv[2] === roleFiles.get('archive-preload').file && future.argv[3] === roleFiles.get('android-launch').file
      && future.argv[4] === root && future.argv.slice(-6, -2).join('\0') === ['web', '--host', '127.0.0.1', '--port'].join('\0')
      && /^\d+$/.test(future.argv.at(-2)) && Number(future.argv.at(-2)) >= 1024 && Number(future.argv.at(-2)) <= 65535
      && future.argv.at(-1) === '--no-open', 'HostUnavailable');
  }
  const bindingDigest = digest({ binding, runtimePins: init.runtimePins, actualNode: { version: process.version, platform: process.platform, arch: process.arch } });
  const pinsDigest = digest(init.runtimePins);
  const capture = async (inputs, file, optional = false, kind = 'file') => {
    const value = await bytes(file, optional);
    inputs.push({ kind, file: path.resolve(file), bytes: value?.length ?? null, sha256: value === null ? null : digest(value) });
    return value;
  };
  const verifyCaptured = async inputs => {
    for (const input of inputs) {
      if (input.kind === 'link') {
        check((await fs.lstat(input.file)).isSymbolicLink() && digest(await fs.readlink(input.file)) === input.sha256);
      } else {
        const value = await bytes(input.file, input.bytes === null);
        check((value === null ? null : digest(value)) === input.sha256 && (value?.length ?? null) === input.bytes);
      }
    }
  };
  const locked = async (credential, action) => {
    // Includes the actual profile patch writer lock. Service/task stop and the
    // exclusive managed helper lease are required in addition to these locks.
    const files = [manifest, patchPath, homePatch, ...(credential ? [credentialPath] : [])];
    for (const file of files) await noLinks(file);
    const next = index => index === files.length ? action()
      : atomic.withFileLock(files[index], () => next(index + 1), { waitMs: 3000 });
    return next(0);
  };
  const safePatch = value => {
    if (value === null) return;
    const document = yaml.parseDocument(value.toString('utf8'), { customTags: [{ tag: 'tag:yaml.org,2002:js', resolve: value => value }] });
    check(document.errors.length === 0 && document.warnings.length === 0 && yaml.isSeq(document.contents), 'UnsupportedProfile');
    yaml.visit(document, { Node(_, node) { check(node.tag !== 'tag:yaml.org,2002:js', 'UnsupportedProfile'); } });
  };

  async function mobileUi(inputs, packageDirectory) {
    const ui = binding.mobileUi;
    check(plain(ui) && ui.directory === packageDirectory && path.isAbsolute(ui.sourceRoot)
      && plain(ui.manifest) && ui.manifest.schema === 1 && ui.manifest.name === 'dafeiyu-mobile-ui'
      && ui.manifest.version === '0.1.6' && HEX.test(ui.manifest.sha256), 'UnsupportedProfile');
    check(path.resolve(ui.directory) === path.join(path.resolve(ui.sourceRoot), ui.manifest.sha256, 'package'), 'UnsupportedProfile');
    if (!fixture) check(ui.sourceRoot === '/dafeiyu-extension-packages/mobile-ui'
      && future.argv[6] === '--dafeiyu-mobile-ui' && future.argv[7] === ui.directory, 'HostUnavailable');
    const slot = path.dirname(ui.directory);
    const expected = new Map();
    check(Array.isArray(ui.manifest.files) && ui.manifest.files.length >= 3 && ui.manifest.files.length <= 32, 'HostUnavailable');
    for (const item of ui.manifest.files) {
      check(/^package\/(?:[A-Za-z0-9._-]+\/)*[A-Za-z0-9._-]+$/.test(item.path) && !item.path.split('/').includes('..')
        && HEX.test(item.sha256) && item.size_bytes > 0 && item.size_bytes <= 2 * 1024 * 1024 && !expected.has(item.path), 'HostUnavailable');
      expected.set(item.path, item);
    }
    const actual = new Set();
    const walk = async directory => {
      await noLinks(directory);
      for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
        const file = path.join(directory, entry.name);
        check(!entry.isSymbolicLink(), 'HostUnavailable');
        if (entry.isDirectory()) await walk(file);
        else {
          const relative = path.relative(slot, file).split(path.sep).join('/');
          const member = expected.get(relative); check(entry.isFile() && member !== undefined, 'HostUnavailable');
          const value = await capture(inputs, file, false, 'mobile-ui');
          check(value.length === member.size_bytes && digest(value) === member.sha256, 'HostUnavailable'); actual.add(relative);
        }
      }
    };
    await walk(slot); same([...actual].sort(), [...expected.keys()].sort());
    const identity = JSON.parse((await bytes(path.join(ui.directory, 'package.json'))).toString('utf8'));
    check(identity.name === 'dafeiyu-mobile-ui' && identity.version === '0.1.6' && identity.license === 'MIT'
      && Object.keys(identity.dependencies ?? {}).length === 0 && identity.exports?.['./client'] === './client/client.js'
      && identity.dsh?.client?.platform === 'web', 'HostUnavailable');
  }

  async function launchSnapshot() {
    const inputs = [];
    check(await executableDigest(process.execPath) === binding.nodeSha256, 'HostUnavailable');
    await noLinks(future.cwd);
    for (const item of roleFiles.values()) {
      const value = await capture(inputs, item.file, false, 'launch-code');
      check(value.length === item.bytes && digest(value) === item.sha256, 'HostUnavailable');
    }
    if (binding.dependencyLock !== undefined) {
      const lock = binding.dependencyLock;
      check(plain(lock) && path.isAbsolute(lock.file) && HEX.test(lock.sha256)
        && (fixture || within(root, lock.file)), 'HostUnavailable');
      const value = await capture(inputs, lock.file, false, 'dependency-lock');
      check(digest(value) === lock.sha256, 'HostUnavailable');
    } else check(fixture, 'HostUnavailable');
    if (binding.providerOrderFile !== undefined) await capture(inputs, binding.providerOrderFile, true, 'provider-order');
    else check(fixture, 'HostUnavailable');
    const projectPath = path.join(future.cwd, '.env'), userPath = path.join(home, '.env');
    const project = await capture(inputs, projectPath, true, 'project-env');
    const user = future.cwd === home ? null : await capture(inputs, userPath, true, 'user-env');
    // Call the REAL fixed CLI validator and source-precedence builder. Its
    // synchronous environment materialization is confined to this serialized
    // storage helper and completely restored before any storage action.
    const inherited = { ...process.env };
    let environment;
    try {
      for (const name of Object.keys(process.env)) delete process.env[name];
      for (const [name, value] of Object.entries(future.environment)) process.env[name] = value;
      environment = boot.loadLayeredEnv('dsh', future.cwd, () => { check(false, 'UnsupportedLaunch'); });
    } catch (error) { check(false, error.safeCode ?? 'UnsupportedLaunch'); }
    finally {
      for (const name of Object.keys(process.env)) delete process.env[name];
      for (const [name, value] of Object.entries(inherited)) process.env[name] = value;
    }
    const layers = [{ source: 'process', values: future.environment },
      ...(project === null ? [] : [{ source: 'project-env', path: projectPath, values: parseEnv(project.toString('utf8')) }]),
      ...(user === null ? [] : [{ source: 'user-env', path: userPath, values: parseEnv(user.toString('utf8')) }])];
    await verifyCaptured(inputs);
    return { inputs, environment, environmentDigest: digest(layers), launchDigest: digest({ bindingDigest, inputs, layers }) };
  }

  async function rawProfile(candidate) {
    check(ID.test(candidate?.slice('DAFEIYU_PROVIDER_DEEPSEEK_'.length))
      && candidate === 'DAFEIYU_PROVIDER_DEEPSEEK_' + candidate.slice('DAFEIYU_PROVIDER_DEEPSEEK_'.length), 'InvalidIntent');
    await verifyPins();
    const launchState = await launchSnapshot();
    const inputs = [];
    const identityBytes = await capture(inputs, manifest);
    const identity = JSON.parse(identityBytes.toString('utf8'));
    check(plain(identity.dsh?.profile) && Array.isArray(identity.dsh.profile.bundles) && identity.dsh.profile.bundles.length > 0
      && identity.dsh.profile.bundles.length <= 128 && identity.dsh.profile.bundles.every(value => typeof value === 'string')
      && new Set(identity.dsh.profile.bundles).size === identity.dsh.profile.bundles.length
      && !identity.dsh.profile.bundles.includes('@deepseek-ai/dsh-experimental-schedule-bundle'), 'UnsupportedProfile');
    if (binding.mobileUi !== undefined) {
      // The actual Android launcher prepares this link before starting DSH.
      // A missing/old/foreign declaration would cause startup to mutate the
      // captured manifest; refuse it rather than proving a different launch.
      check(identity.dsh.profile.bundles.includes('dafeiyu-mobile-ui') &&
        identity.dependencies?.['dafeiyu-mobile-ui'] === `link:${binding.mobileUi.directory.split(path.sep).join('/')}`,
      'UnsupportedProfile');
    }
    const patchBytes = await capture(inputs, patchPath, true), homeBytes = await capture(inputs, homePatch, true);
    safePatch(patchBytes); safePatch(homeBytes);
    await capture(inputs, path.join(dir, boot.PROFILE_COMPATIBILITY_FILENAME), true);
    const admitted = new Map();
    // Preflight precedes loadProfileDirectory: the actual SDK drops retired
    // bundles by writing the manifest even when called by a reader.
    for (const name of identity.dsh.profile.bundles) {
      const declared = path.resolve(boot.resolveBundleDir('dsh', name, installAnchor, dir));
      const resolved = await fs.realpath(declared);
      if (name === 'dafeiyu-mobile-ui') {
        const alias = path.join(dir, 'node_modules', name);
        check(declared === alias && (await fs.lstat(alias)).isSymbolicLink(), 'UnsupportedProfile');
        await noLinks(path.dirname(alias));
        const target = await fs.readlink(alias);
        inputs.push({ kind: 'link', file: alias, bytes: Buffer.byteLength(target), sha256: digest(target) });
        await mobileUi(inputs, resolved);
      } else {
        check(within(root, resolved) && declared === resolved, 'UnsupportedProfile');
        const bundleIdentityPath = path.join(resolved, 'package.json');
        check(pins.has(bundleIdentityPath), 'UnsupportedProfile');
      }
      const bundleBytes = await capture(inputs, path.join(resolved, 'package.json'));
      const bundleIdentity = JSON.parse(bundleBytes.toString('utf8'));
      check(bundleIdentity.name === name && plain(bundleIdentity.dsh?.bundle), 'UnsupportedProfile');
      const patches = boot.bundlePatchPaths(resolved, bundleIdentity.dsh.bundle);
      check(patches.length > 0 && patches.length <= 32 && patches.every(file => within(resolved, path.resolve(file))), 'UnsupportedProfile');
      for (const file of patches) {
        check(name === 'dafeiyu-mobile-ui' || pins.has(path.resolve(file)), 'UnsupportedProfile');
        const value = await capture(inputs, file);
        if (name === 'dafeiyu-mobile-ui') safePatch(value);
      }
      admitted.set(name, { declared, resolved, patches });
    }
    const loaded = boot.loadProfileDirectory('dsh', dir, installAnchor);
    check(loaded.skippedBundles.length === 0 && loaded.layers.length === admitted.size, 'UnsupportedProfile');
    for (const layer of loaded.layers) {
      const source = admitted.get(layer.packageName);
      check(source?.declared === path.resolve(layer.packageDir), 'UnsupportedProfile');
      same(layer.patchPaths.map(file => path.join(source.resolved, path.relative(source.declared, file))), source.patches);
    }
    const context = { dir, home, patchPath, installAnchor, overlays: [],
      telemetryDisabledEnv: launchState.environment.get('DSH_TELEMETRY_DISABLED')?.value };
    const rows = boot.composeEntries([boot.readProfilePatches('dsh', context, loaded)]);
    const flat = flatten(rows);
    const shippedNames = new Set(flatten(boot.composeEntries([loaded.layers.flatMap(layer => layer.patches)]))
      .map(row => row.name).filter(name => typeof name === 'string'));
    // A static user insertion can mount an arbitrary package even without a
    // dynamic Include. This storage-only resolver only supports the complete
    // fixed bundle graph (and the explicitly verified signed UI bundle).
    check(flat.every(row => typeof row.name !== 'string' || shippedNames.has(row.name)), 'UnsupportedProfile');
    check(!flat.some(row => ['cordis:include', '@deepseek-ai/cordis-plugin-include'].includes(row.name)), 'UnsupportedProfile');
    const officialRows = flat.filter(row => row.name === '@deepseek-ai/dsh-llm-deepseek-api-key'
      && ['llm-deepseek', 'llm-deepseek-api-key'].includes(row.id));
    check(officialRows.length === 1 && flat.filter(row => row.id === officialRows[0].id).length === 1, 'UnsupportedProfile');
    const officialRow = officialRows[0];
    const effectiveReference = officialRow.config?.apiKeyEnv ?? 'DEEPSEEK_API_KEY';
    check(reference(effectiveReference) && officialRow.disabled !== true, 'UnsupportedProfile');
    const localRows = flat.filter(row => row.name === '@deepseek-ai/dsh-credentials-local');
    check(localRows.length === 1 && !localRows[0].disabled && !localRows[0].config?.path
      && (!localRows[0].config?.dshHome || localRows[0].config.dshHome === home), 'UnsupportedProfile');
    const document = yaml.parseDocument(patchBytes === null ? '[]\n' : patchBytes.toString('utf8'));
    check(document.errors.length === 0 && document.warnings.length === 0 && yaml.isSeq(document.contents), 'UnsupportedProfile');
    const index = document.contents.items.findLastIndex(item => yaml.isMap(item) && item.get('id') === officialRow.id && !item.has('insert')
      && (!item.has('name') || item.get('name') === officialRow.name));
    const rawSelector = index < 0 ? null : document.getIn([index, 'config', 'apiKeyEnv']) ?? null;
    check(rawSelector === null || reference(rawSelector), 'UnsupportedProfile');
    const nextConfig = { ...(officialRow.config ?? {}), apiKeyEnv: candidate };
    check(official.Config(nextConfig).apiKeyEnv.get() === candidate
      && official.resolveAdapterOptions(nextConfig, launchState.environment).apiKeyEnv === candidate, 'UnsupportedProfile');
    if (index < 0) document.add(document.createNode({ id: officialRow.id, name: officialRow.name, config: nextConfig }));
    else document.setIn([index, 'config'], document.createNode(nextConfig));
    const plannedPatch = String(document);
    const plannedRows = boot.composeEntries([boot.readProfilePatches('dsh', context, {
      ...loaded, patches: jsYaml.load(plannedPatch, { schema: include.entryListSchema }),
    })]);
    const replace = entries => entries.map(row => row.id === officialRow.id ? { ...row, config: nextConfig }
      : row.group && Array.isArray(row.config) ? { ...row, config: replace(row.config) } : row);
    same(plannedRows, replace(rows));
    const compositionDigest = digest(rows), plannedCompositionDigest = digest(plannedRows);
    const revisionOf = (profileInputs, composition) => digest({ profileInputs, compositionDigest: composition,
      launchDigest: launchState.launchDigest, runtimePinsDigest: pinsDigest, bindingDigest });
    const plannedInputs = inputs.map(input => input.file === patchPath
      ? { ...input, bytes: Buffer.byteLength(plannedPatch), sha256: digest(plannedPatch) } : input);
    await verifyCaptured(inputs); await verifyCaptured(launchState.inputs);
    let candidateMentioned = false;
    for (const input of inputs.filter(input => input.bytes !== null && input.kind !== 'link'))
      if ((await bytes(input.file)).toString('utf8').includes(candidate)) candidateMentioned = true;
    return { revision: revisionOf(inputs, compositionDigest), plannedRevision: revisionOf(plannedInputs, plannedCompositionDigest),
      plannedPatch, inputs, plannedInputs, compositionDigest, plannedCompositionDigest, launchState,
      effectiveReference, rawSelector, candidateMentioned, namespace: officialRow.id };
  }

  async function credentialState(candidate, selected, environment) {
    const raw = await bytes(credentialPath, true);
    const portable = await readCredentials(root, home);
    check(portable.revision === digest(raw === null ? '' : raw));
    const document = yaml.parseDocument(raw === null ? '' : raw.toString('utf8'));
    check(document.errors.length === 0 && document.warnings.length === 0, 'StoragePending');
    if (!yaml.isMap(document.contents)) document.contents = document.createNode({ version: 1, refs: {}, records: {} });
    if (!document.has('version')) document.set('version', 1);
    const normalizeEmptySection = name => {
      if (!document.has(name)) { document.set(name, document.createNode({})); return; }
      const node = document.get(name, true);
      if (yaml.isMap(node)) return;
      // readCredentials has already used the fixed strict SDK parser. Only
      // its permitted explicit-null sections become empty proof maps; never
      // overwrite a scalar, sequence or unsupported alias with empty data.
      check(yaml.isScalar(node) && node.value === null, 'StoragePending');
      const empty = document.createNode({});
      for (const metadata of ['comment', 'commentBefore', 'spaceBefore', 'anchor'])
        if (Object.hasOwn(node, metadata)) empty[metadata] = node[metadata];
      // Scalar tags/source tokens cannot describe the replacement map. The
      // original raw revision still binds their exact on-disk spelling.
      document.set(name, empty);
    };
    normalizeEmptySection('refs'); normalizeEmptySection('records');
    document.deleteIn(['refs', candidate]);
    const rest = structuredClone(portable); delete rest.refs[candidate]; delete rest.revision;
    const noncandidate = digest({ document: String(document), portable: rest });
    const ctx = new cordis.Context(); ctx.reflect.provide('launchEnvironment', environment);
    const provider = new local.LocalCredentialProvider(ctx, { dshHome: home, watch: false });
    const disposers = [];
    try {
      // The strict fixed reader above rejects legacy documents before SDK init
      // could perform its optional automatic migration.
      for await (const dispose of provider[cordis.Service.init]()) if (typeof dispose === 'function') disposers.push(dispose);
      const hit = await provider.resolve(credentialsApi.credentialRef(selected));
      check(hit === undefined || secret(hit.value), 'StoragePending');
      const actual = await bytes(credentialPath, true);
      check(digest(actual === null ? '' : actual) === portable.revision);
      return { credentialRevision: portable.revision, credentialsExceptCandidateRevision: noncandidate,
        selectedKey: hit?.value ?? null, candidatePresent: Object.hasOwn(portable.refs, candidate), candidateKey: portable.refs[candidate] ?? null };
    } finally {
      for (const dispose of disposers.reverse()) await dispose(); await ctx.fiber.dispose();
    }
  }

  async function snapshot(candidate) {
    const profile = await rawProfile(candidate);
    const credentials = await credentialState(candidate, profile.effectiveReference, profile.launchState.environment);
    return { schema: 1, realm: REALM, bindingDigest, namespace: profile.namespace,
      profileRevision: profile.revision, plannedSelectedProfileRevision: profile.plannedRevision,
      rawProfileInputs: profile.inputs, plannedProfileInputs: profile.plannedInputs,
      compositionDigest: profile.compositionDigest, plannedCompositionDigest: profile.plannedCompositionDigest,
      launchInputs: profile.launchState.inputs, launchDigest: profile.launchState.launchDigest,
      environmentDigest: profile.launchState.environmentDigest, runtimePinsDigest: pinsDigest,
      ...credentials, effectiveReference: profile.effectiveReference, userReference: profile.rawSelector,
      candidateReference: candidate, candidateMentioned: profile.candidateMentioned };
  }
  const unbound = async (candidate, state) => {
    check(state.environment.get(candidate) === undefined
      && !Object.keys(process.env).some(name => name.toUpperCase() === candidate.toUpperCase()), 'ReferenceBound');
  };
  const authority = (body, step) => {
    const projection = body.authorization;
    check(plain(projection) && projection.kind === 'android-local-journal-projection' && projection.schema === 1
      && projection.scopeId === init.scopeId && Number.isSafeInteger(projection.revision) && projection.revision > 0
      && HEX.test(projection.journalDigest), 'HostUnavailable');
    const pending = projection.pending;
    check(plain(pending) && pending.step === step && ID.test(pending.id) && pending.candidate === 'DAFEIYU_PROVIDER_DEEPSEEK_' + pending.id
      && secret(pending.desired) && pending.candidate === body.expected?.candidateReference
      && pending.dshStorage?.schema === 1 && pending.dshStorage.realm === REALM
      && pending.dshStorage.bindingDigest === bindingDigest && pending.dshStorage.before?.bindingDigest === bindingDigest,
    'HostUnavailable');
    return pending;
  };
  const profileSame = (left, right) => {
    for (const name of ['bindingDigest', 'namespace', 'profileRevision', 'plannedSelectedProfileRevision', 'rawProfileInputs',
      'plannedProfileInputs', 'compositionDigest', 'plannedCompositionDigest', 'launchInputs', 'launchDigest', 'environmentDigest',
      'runtimePinsDigest', 'effectiveReference', 'userReference', 'candidateMentioned']) same(left[name], right[name]);
  };

  return {
    bindingDigest,
    async read(candidate) {
      return locked(true, async () => { await control('read-locked', candidate); return snapshot(candidate); });
    },
    async stage(body) {
      const pending = authority(body, 'STAGING');
      same(body.expected, pending.dshStorage.before);
      return locked(false, async () => {
        const before = await atomic.withFileLock(credentialPath, async () => {
          await control('stage-before', pending.candidate);
          const actual = await snapshot(pending.candidate); same(actual, body.expected);
          check(!actual.candidatePresent && !actual.candidateMentioned, 'ReferenceBound'); return actual;
        }, { waitMs: 3000 });
        if (pending.desired !== null) await writeCredentials(root, home, {
          expectedRevision: before.credentialRevision, refs: [{ ref: pending.candidate, value: pending.desired }], records: [],
        }, '/dafeiyu-dsh-home', async () => {
          same(await snapshot(pending.candidate), body.expected);
          await unbound(pending.candidate, await launchSnapshot());
          await control('stage-before-write', pending.candidate);
          // Re-read after the lock-held host challenge, not only before it.
          same(await snapshot(pending.candidate), body.expected);
        });
        return atomic.withFileLock(credentialPath, async () => {
          await control('stage-after-write', pending.candidate);
          const after = await snapshot(pending.candidate); profileSame(before, after);
          check(after.credentialsExceptCandidateRevision === before.credentialsExceptCandidateRevision && after.selectedKey === before.selectedKey
            && after.candidatePresent === (pending.desired !== null) && after.candidateKey === pending.desired, 'NotConfirmed');
          return after;
        }, { waitMs: 3000 });
      });
    },
    async select(body) {
      const pending = authority(body, 'SETTINGS_SUBMITTING');
      const before = pending.dshStorage.before, staged = pending.dshStorage.staged;
      check(plain(staged) && pending.stagedRevision === staged.credentialRevision, 'HostUnavailable');
      same(body.expected, staged); profileSame(before, staged);
      check(staged.credentialsExceptCandidateRevision === before.credentialsExceptCandidateRevision && staged.selectedKey === before.selectedKey
        && staged.candidatePresent === (pending.desired !== null) && staged.candidateKey === pending.desired && !staged.candidateMentioned);
      return locked(true, async () => {
        same(await snapshot(pending.candidate), staged);
        const planned = await rawProfile(pending.candidate);
        await unbound(pending.candidate, planned.launchState);
        await control('select-before-write', pending.candidate);
        same(await snapshot(pending.candidate), staged);
        await atomic.writeFileAtomic(patchPath, planned.plannedPatch, { mode: 0o600 });
        await control('select-after-write', pending.candidate);
        const after = await snapshot(pending.candidate);
        check(classifyRecovery(pending, after) === 'selected', 'NotConfirmed'); return after;
      });
    },
  };
}

/** Pure recovery classifier. It requires a persisted complete before/planned
 * proof. Equal selector or a process-local namespace revision is never enough. */
export function classifyRecovery(pending, live) {
  const proof = pending?.dshStorage, before = proof?.before;
  check(proof?.schema === 1 && proof.realm === REALM && plain(before) && plain(live)
    && before.realm === REALM && live.realm === REALM && before.bindingDigest === proof.bindingDigest
    && live.bindingDigest === proof.bindingDigest && live.candidateReference === pending.candidate
    && pending.candidate === 'DAFEIYU_PROVIDER_DEEPSEEK_' + pending.id && ID.test(pending.id) && secret(pending.desired), 'InvalidJournal');
  if (isDeepStrictEqual(live, before)) return 'original';
  const shared = ['bindingDigest', 'namespace', 'launchInputs', 'launchDigest', 'environmentDigest', 'runtimePinsDigest', 'credentialsExceptCandidateRevision'];
  if (!shared.every(name => isDeepStrictEqual(live[name], before[name])) || live.candidatePresent !== (pending.desired !== null)
    || live.candidateKey !== pending.desired) return 'pending';
  const stageFields = ['profileRevision', 'plannedSelectedProfileRevision', 'rawProfileInputs', 'plannedProfileInputs',
    'compositionDigest', 'plannedCompositionDigest', 'effectiveReference', 'userReference', 'candidateMentioned', 'selectedKey'];
  if (stageFields.every(name => isDeepStrictEqual(live[name], before[name]))) {
    if (pending.step === 'STAGING' || proof.staged && isDeepStrictEqual(live, proof.staged)) return 'staged';
    return 'pending';
  }
  if (!proof.staged || !['SETTINGS_SUBMITTING', 'DSH_CONFIRMED', 'PROVIDER_SUBMITTING', 'PROVIDER_CONFIRMED',
    'BALANCE_SUBMITTING', 'BALANCE_CONFIRMED', 'COMMITTING'].includes(pending.step)) return 'pending';
  return live.profileRevision === before.plannedSelectedProfileRevision
    && isDeepStrictEqual(live.rawProfileInputs, before.plannedProfileInputs) && live.compositionDigest === before.plannedCompositionDigest
    && live.credentialRevision === proof.staged.credentialRevision && pending.stagedRevision === live.credentialRevision
    && live.effectiveReference === pending.candidate && live.userReference === pending.candidate && live.selectedKey === pending.desired
    ? 'selected' : 'pending';
}
