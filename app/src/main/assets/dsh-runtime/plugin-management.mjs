// Application-owned storage/package helper. It loads only the pinned DSH manager;
// installed plugin JavaScript and saved patch expressions are never evaluated here.
import fs from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import crypto from 'node:crypto';
import { createBuildAdapter } from './plugin-build-adapter.mjs';

const bundleRoot = path.resolve(process.argv[2]);
const dshHome = path.resolve(process.argv[3]);
const require = createRequire(path.join(bundleRoot, 'package.json'));
const check = (condition, code) => { if (!condition) throw new Error(code); };
const packageName = /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/;
const pinned = '0.2.1-alpha.1';
let manager;
let context;
let installId;
let aborted = false;
let lockYaml;
let transactionOpened = false;
const buildAbort = new AbortController();
const transactionDirectory = path.join(dshHome, '.mobile-plugin-transaction');
const profileFiles = ['package.json', 'pnpm-lock.yaml', 'cordis.patch.yml', 'pnpm-workspace.yaml', 'compatibility.json'];

async function safeProfilePath(target) {
  let current = path.resolve(target);
  check(current.startsWith(dshHome + path.sep), 'foreign-profile-path');
  while (current !== dshHome) {
    const stat = await fs.lstat(current).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
    check(!stat?.isSymbolicLink(), 'profile-link-refused');
    current = path.dirname(current);
  }
}

async function beginProfileTransaction(profileDir) {
  await safeProfilePath(transactionDirectory);
  check(!existsSync(transactionDirectory), 'profile-recovery-required');
  const stage = path.join(dshHome, `.mobile-plugin-stage-${crypto.randomUUID()}`);
  await fs.mkdir(stage, { mode: 0o700 });
  try {
    const records = {};
    const hashes = {};
    let total = 0;
    for (const name of profileFiles) {
      const file = path.join(profileDir, name);
      await safeProfilePath(file);
      const stat = await fs.lstat(file).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
      records[name] = stat !== null;
      if (stat === null) continue;
      check(stat.isFile() && stat.size <= 16 * 1024 * 1024, 'profile-snapshot-too-large');
      total += stat.size; check(total <= 24 * 1024 * 1024, 'profile-snapshot-too-large');
      const bytes = await fs.readFile(file);
      hashes[name] = crypto.createHash('sha256').update(bytes).digest('hex');
      const snapshot = await fs.open(path.join(stage, name), 'wx', 0o600);
      try { await snapshot.writeFile(bytes); await snapshot.sync(); } finally { bytes.fill(0); await snapshot.close(); }
    }
    const record = await fs.open(path.join(stage, 'journal.json'), 'wx', 0o600);
    try { await record.writeFile(JSON.stringify({ schema: 1, files: records, sha256: hashes })); await record.sync(); } finally { await record.close(); }
    await fs.rename(stage, transactionDirectory);
    transactionOpened = true;
  } finally { await fs.rm(stage, { recursive: true, force: true }); }
}

async function recoverProfileTransaction(profileDir) {
  await safeProfilePath(transactionDirectory);
  check(existsSync(transactionDirectory), 'no-pending-profile-transaction');
  const journal = path.join(transactionDirectory, 'journal.json');
  await safeProfilePath(journal);
  const stat = await fs.lstat(journal);
  check(stat.isFile() && stat.size <= 8192, 'invalid-profile-journal');
  const value = JSON.parse(await fs.readFile(journal, 'utf8'));
  check(value.schema === 1 && value.files && Object.keys(value.files).length === profileFiles.length && value.sha256 &&
    profileFiles.every(name => typeof value.files[name] === 'boolean') && Object.keys(value.sha256).every(name => profileFiles.includes(name) && value.files[name]), 'invalid-profile-journal');
  const snapshots = new Map();
  let total = 0;
  // Validate the complete snapshot before writing any original profile file.
  for (const name of profileFiles) if (value.files[name]) {
    const snapshot = path.join(transactionDirectory, name);
    await safeProfilePath(snapshot);
    const info = await fs.lstat(snapshot);
    check(info.isFile() && info.size <= 16 * 1024 * 1024, 'invalid-profile-snapshot');
    total += info.size; check(total <= 24 * 1024 * 1024, 'invalid-profile-snapshot');
    const bytes = await fs.readFile(snapshot);
    check(typeof value.sha256[name] === 'string' && crypto.createHash('sha256').update(bytes).digest('hex') === value.sha256[name], 'profile-snapshot-integrity');
    snapshots.set(name, bytes);
  }
  try {
    await safeProfilePath(profileDir);
    await fs.mkdir(profileDir, { recursive: true, mode: 0o700 });
    const { writeFileAtomic } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-atomic-write')).href);
    for (const name of profileFiles) {
      const file = path.join(profileDir, name);
      await safeProfilePath(file);
      if (value.files[name]) await writeFileAtomic(file, snapshots.get(name), { mode: 0o600 });
      else {
        const info = await fs.lstat(file).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
        check(info === null || info.isFile(), 'invalid-profile-recovery-target');
        if (info !== null) await fs.unlink(file);
      }
    }
    await fs.rm(transactionDirectory, { recursive: true });
    transactionOpened = false;
  } finally { for (const bytes of snapshots.values()) bytes.fill(0); }
}

async function input() {
  const chunks = [];
  let bytes = 0;
  for await (const chunk of process.stdin) {
    bytes += chunk.length; check(bytes <= 128 * 1024, 'request-too-large'); chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

function redact(value) {
  return String(value ?? '').replace(/(?:https?:\/\/)?127\.0\.0\.1:\d+\/[a-f0-9]{48}\//g, '[verified-registry]/')
    .replace(/(?:Bearer|api[_-]?key|token|password|secret)\s*[=: ]\s*[^\s,;]+/gi, '[credential removed]')
    .replace(/https?:\/\/[^\s/@]+:[^\s/@]+@/g, 'https://[credentials removed]@').slice(0, 8192);
}

function result(value) {
  return { application: value.application, changed: value.changed === true, bundle: value.bundle, version: value.version,
    errorCode: value.error?.code, packageKind: value.packageResult?.kind,
    exitCode: value.packageResult?.exitCode, output: redact(value.packageResult?.output),
    pendingBuilds: value.pendingBuilds ?? [], incompatible: value.error?.incompatible ?? value.packageResult?.incompatible ?? [],
    buildCandidates: value.buildCandidates ?? [],
    warnings: (value.warnings ?? []).map(redact) };
}

function registry(value) {
  const parsed = new URL(value);
  check(parsed.protocol === 'http:' && parsed.hostname === '127.0.0.1' && /^\/[a-f0-9]{48}\/$/.test(parsed.pathname)
    && !parsed.username && !parsed.password && !parsed.search && !parsed.hash, 'invalid-registry');
  return value;
}

// A package dependency must be an npm range or one of our application-owned local sources.
function profileDependencies(document, sources, includeDev = true, base = sources) {
  check(document && typeof document === 'object' && !Array.isArray(document), 'invalid-profile');
  for (const key of ['dependencies', 'optionalDependencies', ...includeDev ? ['devDependencies'] : []]) {
    for (const [name, value] of Object.entries(document[key] ?? {})) {
      check(packageName.test(name) && typeof value === 'string', 'invalid-dependency');
      const sourcePath = path.resolve(base, value.replace(/^(file|link):/, ''));
      if (value.startsWith('file:') || value.startsWith('link:')) {
        check(path.isAbsolute(sourcePath) && path.relative(sources, sourcePath) !== ''
          && !path.relative(sources, sourcePath).startsWith('..') && !path.isAbsolute(path.relative(sources, sourcePath)), 'foreign-local-source');
      } else check(/^[a-zA-Z0-9.*+~^<>=| _-]+$/.test(value), 'external-dependency-source');
    }
  }
  check(!document.pnpm?.patchedDependencies && !document.pnpm?.overrides && !document.pnpm?.packageExtensions, 'unsupported-profile-pnpm-overrides');
}

async function normalizeLock(profileDir, sourceRoot) {
  const filename = path.join(profileDir, 'pnpm-lock.yaml');
  if (!existsSync(filename)) return;
  const text = await fs.readFile(filename, 'utf8');
  check(Buffer.byteLength(text) <= 16 * 1024 * 1024, 'lockfile-too-large');
  const parsed = lockYaml.parse(text, { maxAliasCount: 0, strict: true });
  let nodes = 0;
  function visit(value, depth) {
    check(++nodes <= 250_000 && depth <= 32, 'lockfile-too-complex');
    if (!value || typeof value !== 'object') return;
    if (typeof value.tarball === 'string') {
      if (/^http:\/\/127\.0\.0\.1:\d+\/[a-f0-9]{48}\//.test(value.tarball)) delete value.tarball;
      else if (value.tarball.startsWith('file:')) {
        const relative = path.relative(sourceRoot, path.resolve(profileDir, value.tarball.slice(5)));
        check(relative !== '' && !relative.startsWith('..') && !path.isAbsolute(relative), 'foreign-lockfile-source');
      } else throw new Error('unsupported-lockfile-tarball-source');
    }
    check(!value.repo && !value.commit, 'unsupported-lockfile-git-source');
    for (const child of Object.values(value)) visit(child, depth + 1);
  }
  visit(parsed, 0);
  const { writeFileAtomic } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-atomic-write')).href);
  await writeFileAtomic(filename, lockYaml.stringify(parsed), { mode: 0o600 });
}

async function main() {
  const frame = await input();
  check(['list', 'enable', 'install', 'remove', 'repair', 'recover', 'exemption', 'build'].includes(frame.operation), 'unknown-operation');
  for (const dependency of ['@deepseek-ai/dsh-plugin-manager', '@deepseek-ai/dsh-app-boot']) {
    check(require(dependency + '/package.json').version === pinned, 'first-party-version-mismatch');
  }
  const boot = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-app-boot')).href);
  // The immutable dependency archive has no application package.json at its
  // root. The upstream manager expects the owning CLI package's real anchor.
  const installAnchor = require.resolve('@deepseek-ai/dsh/package.json');
  check(require('@deepseek-ai/dsh/package.json').version === pinned, 'first-party-version-mismatch');
  const { Context } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);
  const { PluginManager } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-plugin-manager')).href);
  lockYaml = await import(pathToFileURL(require.resolve('yaml')).href);
  const profileDir = path.join(dshHome, 'profiles', 'web');
  const sourceRoot = path.resolve(frame.sourceRoot);
  check(path.isAbsolute(frame.sourceRoot) && frame.sourceRoot.length < 512, 'invalid-source-root');
  if (frame.operation === 'recover') {
    await recoverProfileTransaction(profileDir);
    return { application: 'restart-required', changed: true, recovered: true,
      warnings: ['原插件配置已恢复；若依赖文件受中断影响，请继续修复对应插件。'] };
  }
  await safeProfilePath(transactionDirectory);
  check(!existsSync(transactionDirectory), 'profile-recovery-required');
  // Even a first catalog read can initialize a missing default profile, so Kotlin leases that case exclusively.
  if (frame.operation !== 'list' || !existsSync(path.join(profileDir, 'package.json'))) await beginProfileTransaction(profileDir);
  await fs.mkdir(profileDir, { recursive: true });
  if (!existsSync(path.join(profileDir, 'package.json'))) {
    boot.initProfile(profileDir, boot.PROFILE_TEMPLATES.web?.bundles ?? boot.DEFAULT_PROFILE_BUNDLES);
  }
  const manifest = boot.readProfileManifest('dsh', profileDir);
  const mobileUiName = 'dafeiyu-mobile-ui';
  const bundledMobileUi = /^link:\/dafeiyu-extension-packages\/mobile-ui\/[a-f0-9]{64}\/package$/
    .test(manifest.dependencies?.[mobileUiName] ?? '');
  // This source is verified and repaired by the signed APK launcher, not npm.
  // Keep generic install/repair from replacing it with a registry package.
  if (bundledMobileUi && frame.name === mobileUiName && ['enable', 'remove', 'repair'].includes(frame.operation))
    throw new Error('application-ui-bundle-readonly');
  if (!['list', 'enable', 'exemption'].includes(frame.operation)) {
    profileDependencies(manifest, sourceRoot, true, profileDir);
    for (const filename of ['.npmrc', '.pnpmfile.cjs', '.pnpmfile.mjs']) {
      if (existsSync(path.join(profileDir, filename))) {
        check((await fs.readFile(path.join(profileDir, filename), 'utf8')).trim() === '', 'unsupported-private-registry-or-pnpm-hook');
      }
    }
    await normalizeLock(profileDir, sourceRoot);
  }
  const registryUrl = ['list', 'enable', 'exemption'].includes(frame.operation) ? undefined : registry(frame.registry);
  const pmEnv = {
    PATH: process.env.PATH,
    DSH_HOME: dshHome,
    PNPM_HOME: path.join(dshHome, '.mobile-pnpm-home'),
    npm_config_registry: registryUrl,
    npm_config_ignore_scripts: 'true',
    npm_config_ignore_pnpmfile: 'true',
    npm_config_fetch_retries: '0',
    npm_config_audit: 'false',
    npm_config_verify_store_integrity: 'true',
    npm_config_manage_package_manager_versions: 'false',
    npm_config_package_manager_strict: 'false',
    npm_config_store_dir: path.join(dshHome, '.mobile-pnpm-store'),
    npm_config_cache: path.join(dshHome, '.mobile-pnpm-cache'),
    npm_config_userconfig: path.join(dshHome, '.mobile-empty-npmrc'),
    npm_config_globalconfig: path.join(dshHome, '.mobile-empty-npmrc'),
    TMPDIR: path.join(dshHome, '.mobile-tmp'),
    TMP: path.join(dshHome, '.mobile-tmp'),
    TEMP: path.join(dshHome, '.mobile-tmp'),
  };
  Object.keys(pmEnv).forEach(key => { if (pmEnv[key] === undefined) delete pmEnv[key]; });
  await fs.writeFile(path.join(dshHome, '.mobile-empty-npmrc'), '', { mode: 0o600 });
  await fs.mkdir(path.join(dshHome, '.mobile-tmp'), { recursive: true, mode: 0o700 });
  await fs.mkdir(path.join(dshHome, '.mobile-pnpm-home'), { recursive: true, mode: 0o700 });
  const pmCommand = process.argv[4] ?? 'pnpm';
  const pmArgs = [...process.argv[5] ? [process.argv[5]] : [], '--config.ignore-scripts=true', '--config.ignore-pnpmfile=true'];
  const profile = { name: 'web', dir: profileDir, patchPath: path.join(profileDir, 'cordis.patch.yml'),
    installAnchor, cwd: sourceRoot, home: dshHome, startedBundles: [], overlays: [],
    packageManager: { command: pmCommand, args: pmArgs, env: pmEnv } };
  context = new Context();
  context.provide('profileContext', profile);
  // There is no running Loader in this exclusive storage helper; all running entries are genuinely absent.
  // The real manager uses this empty inventory only for its stop/in-use protection, never as a service status.
  context.provide('loader', { entries: function* () {} });
  manager = new PluginManager(context, { outputBytes: 64 * 1024, lockWaitMs: 5000, inspectTimeoutMs: 30_000,
    githubConnectionTimeoutMs: 30_000, idleTimeoutMs: 120_000, pnpmCommand: pmCommand,
    registry: registryUrl, fallbackRegistries: [] });
  const builds = await createBuildAdapter({ require, profileDir, sourceRoot, registryUrl, command: pmCommand,
    commandPrefix: process.argv[5] ? [process.argv[5]] : [], env: pmEnv, signal: buildAbort.signal,
    validateSource: async folder => normalizeLock(folder, sourceRoot) });
  if (frame.operation === 'list') {
    const bundles = await manager.listBundles();
    const exemptions = manager.listVersionExemptions();
    return { bundles: bundles.map(item => ({
      name: item.name, version: item.version ?? '', description: item.description ?? '', enabled: item.enabled,
      installed: item.installed, optional: item.optional,
      removable: bundledMobileUi && item.name === mobileUiName ? false : item.removable,
      readOnlyReason: bundledMobileUi && item.name === mobileUiName ? '手机界面由应用更新维护' : item.readOnlyReason,
      errorCode: item.error?.code,
      rows: item.rows.map(row => ({ id: row.rowId, name: row.moduleName })), source: item.source ?? ''
    })), exemptions: exemptions.exemptions, buildCandidates: await builds.catalog(), warnings: exemptions.warnings };
  }
  if (frame.operation === 'build') {
    return builds.build(frame);
  }
  if (frame.operation === 'exemption') {
    const at = typeof frame.packageVersion === 'string' ? frame.packageVersion.lastIndexOf('@') : -1;
    check(at > 0 && packageName.test(frame.packageVersion.slice(0, at)) &&
      /^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?(?:\+[A-Za-z0-9.-]+)?$/.test(frame.packageVersion.slice(at + 1)), 'invalid-package-version');
    check(typeof frame.runtimeVersion === 'string' && frame.runtimeVersion.length <= 100 &&
      typeof frame.enabled === 'boolean' && (frame.enabled === false || frame.acceptRisk === true), 'compatibility-consent-required');
    return result(await manager.setVersionExemption(frame.packageVersion, frame.runtimeVersion, frame.enabled, frame.acceptRisk === true));
  }
  if (frame.operation === 'enable') {
    check(packageName.test(frame.name) && typeof frame.enabled === 'boolean', 'invalid-bundle');
    return result(await manager.setBundleEnabled(frame.name, frame.enabled));
  }
  if (frame.operation === 'remove') {
    check(packageName.test(frame.name), 'invalid-bundle');
    const value = await manager.removeBundle(frame.name);
    await normalizeLock(profileDir, sourceRoot);
    return result(value);
  }
  if (frame.operation === 'repair') {
    // Reinstall each explicit bundle through the actual manager; all selection states remain unchanged.
    const rows = (await manager.listBundles()).filter(item => item.installed && item.removable
      && !(bundledMobileUi && item.name === mobileUiName)
      && (!frame.name || frame.name === item.name));
    check(rows.length > 0, 'no-repairable-bundle');
    for (const row of rows) {
      if (!manifest.dependencies?.[row.name]) continue;
      const saved = manifest.dependencies[row.name];
      const source = saved.startsWith('file:') ? `file:${path.resolve(profileDir, saved.slice(5))}` : `${row.name}@${saved}`;
      // The pinned manager cannot disambiguate an unchanged local file reinstall. Remove through its real API first.
      const removed = await manager.removeBundle(row.name);
      if (removed.application === 'failed' || removed.application === 'cancelled') return result(removed);
      const value = await manager.installBundle(source,
        { enabled: row.enabled, saveExact: true, registry: registryUrl, requestId: crypto.randomUUID() });
      if (value.application === 'failed' || value.application === 'cancelled') return result(value);
    }
    await normalizeLock(profileDir, sourceRoot);
    return { application: 'restart-required', changed: true, warnings: [] };
  }
  check(typeof frame.spec === 'string' && frame.spec.length <= 512 && !frame.spec.startsWith('-'), 'invalid-spec');
  if (frame.spec.startsWith('file:')) {
    const source = frame.spec.slice(5);
    check(path.isAbsolute(source) && path.relative(sourceRoot, source) !== '' && !path.relative(sourceRoot, source).startsWith('..')
      && !path.isAbsolute(path.relative(sourceRoot, source)), 'foreign-install-source');
    const candidate = JSON.parse(await fs.readFile(path.join(source, 'package.json'), 'utf8'));
    check(!bundledMobileUi || candidate.name !== mobileUiName, 'application-ui-bundle-readonly');
    profileDependencies(candidate, sourceRoot, true, source);
    const preparation = await builds.prepare(frame, source, candidate);
    if (preparation !== null) return preparation;
  } else {
    check(/^(?:@[a-z0-9._-]+\/)?[a-z0-9][a-z0-9._-]*(?:@[a-zA-Z0-9][a-zA-Z0-9.+_-]*)?$/.test(frame.spec), 'external-install-source');
    check(!bundledMobileUi || !/^dafeiyu-mobile-ui(?:@|$)/.test(frame.spec), 'application-ui-bundle-readonly');
  }
  installId = crypto.randomUUID();
  const installed = await manager.installBundle(frame.spec, { enabled: frame.enabled !== false, saveExact: true,
    registry: registryUrl, requestId: installId });
  await normalizeLock(profileDir, sourceRoot);
  return result(installed);
}

for (const signal of ['SIGTERM', 'SIGINT', 'SIGQUIT']) process.on(signal, async () => {
  aborted = true;
  buildAbort.abort();
  if (manager && installId) await manager.cancelInstall(installId).catch(() => {});
});
try {
  const value = await main();
  check(!aborted, 'cancelled');
  if (transactionOpened) {
    if (value.application === 'failed' || value.application === 'cancelled') {
      await recoverProfileTransaction(path.join(dshHome, 'profiles', 'web'));
    } else {
      await fs.rm(transactionDirectory, { recursive: true });
      transactionOpened = false;
    }
  }
  process.stdout.write(JSON.stringify({ ok: true, value }));
} catch (failure) {
  let recovery = '';
  if (transactionOpened) {
    try { await recoverProfileTransaction(path.join(dshHome, 'profiles', 'web')); }
    catch { recovery = '; profile-recovery-required'; }
  }
  process.stdout.write(JSON.stringify({ ok: false, error: redact(failure.message) + recovery }));
  process.exitCode = 1;
} finally { if (context) await context.fiber.dispose().catch(() => {}); }
