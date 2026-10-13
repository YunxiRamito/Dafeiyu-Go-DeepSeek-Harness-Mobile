// Application-owned adapter for fixed pnpm 10.34.5. No package lifecycle runs without per-call consent.
// Reviewed behavior: pnpm/pnpm 702ad5f860ffd50d64a3a711d9f8a3da16fc796e (MIT), rebuild + builder/policy.
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';

const check = (condition, code) => { if (!condition) throw new Error(code); };
const packageName = /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/;
const exactVersion = /^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?(?:\+[A-Za-z0-9.-]+)?$/;
const lifecycle = ['preinstall', 'install', 'postinstall', 'prepare'];

export async function createBuildAdapter(options) {
  const { require, profileDir, sourceRoot, registryUrl, command, commandPrefix = [], env, signal } = options;
  const { execa } = await import(pathToFileURL(require.resolve('execa')).href);
  const yaml = await import(pathToFileURL(require.resolve('yaml')).href);
  const { writeFileAtomic } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-atomic-write')).href);
  const receiptFile = path.join(profileDir, '.mobile-build-receipts.json');
  const inside = (root, candidate) => {
    const relative = path.relative(root, candidate);
    return relative !== '' && !relative.startsWith('..') && !path.isAbsolute(relative);
  };
  async function text(filename, maximum = 2 * 1024 * 1024) {
    const stat = await fs.lstat(filename); check(stat.isFile() && stat.size <= maximum, 'invalid-build-metadata');
    return fs.readFile(filename, 'utf8');
  }
  async function run(args, cwd, scripts = false) {
    const operation = execa(command, [...commandPrefix, ...args], {
      cwd, env: { ...env, npm_config_ignore_scripts: String(!scripts), npm_config_manage_package_manager_versions: 'false',
        npm_config_package_manager_strict: 'false', npm_config_enable_pre_post_scripts: 'false' },
      extendEnv: false, stdin: 'ignore', buffer: false, reject: false, killDescendants: true,
      cancelSignal: signal, timeout: 5 * 60 * 1000,
    });
    let output = Buffer.alloc(0);
    const collect = async stream => { for await (const chunk of stream) {
      output = Buffer.concat([output, Buffer.from(chunk)]); if (output.length > 64 * 1024) output = output.subarray(output.length - 64 * 1024);
    } };
    const result = await Promise.all([operation, collect(operation.stdout), collect(operation.stderr)]);
    check(result[0].exitCode === 0 && !result[0].timedOut && !result[0].isCanceled, `approved-build-failed: ${output.toString('utf8')}`);
    return output.toString('utf8').trim();
  }
  async function requireFixedPnpm() {
    const version = await run(['--version'], profileDir);
    check(version === '10.34.5', `build-adapter-requires-pnpm-10.34.5: actual ${version}`);
  }
  function scriptsOf(manifest, prepareOnly = false) {
    check(packageName.test(manifest.name) && exactVersion.test(manifest.version), 'invalid-build-package-identity');
    return Object.fromEntries((prepareOnly ? ['prepare'] : lifecycle).flatMap(key =>
      typeof manifest.scripts?.[key] === 'string' && manifest.scripts[key].trim() !== '' ? [[key, manifest.scripts[key]]] : []));
  }
  async function sourceDigest(folder) {
    const hash = crypto.createHash('sha256'); let files = 0; let bytes = 0;
    async function walk(directory) {
      const entries = (await fs.readdir(directory, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name));
      for (const entry of entries) {
        if (entry.name === 'node_modules') continue;
        const filename = path.join(directory, entry.name); const stat = await fs.lstat(filename);
        check(!stat.isSymbolicLink() && (stat.isDirectory() || stat.isFile()), 'build-source-link-or-special-file');
        if (stat.isDirectory()) await walk(filename);
        else {
          check(++files <= 5000 && (bytes += stat.size) <= 128 * 1024 * 1024, 'build-source-too-large');
          hash.update(path.relative(folder, filename) + '\0' + stat.size + '\0'); hash.update(await fs.readFile(filename));
        }
      }
    }
    await walk(folder); return hash.digest('hex');
  }
  async function completePatch(folder, manifest) {
    const patches = manifest.dsh?.bundle?.patch;
    const values = typeof patches === 'string' ? [patches] : patches;
    check(Array.isArray(values) && values.length > 0 && values.length <= 32, 'invalid-bundle-patch-path');
    let complete = true;
    for (const value of values) {
      check(typeof value === 'string' && value !== '' && !path.isAbsolute(value), 'invalid-bundle-patch-path');
      const filename = path.resolve(folder, value); check(inside(folder, filename), 'bundle-patch-path-escape');
      let parent = path.dirname(filename);
      while (parent !== folder) {
        const stat = await fs.lstat(parent).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
        check(!stat?.isSymbolicLink(), 'bundle-patch-path-link'); parent = path.dirname(parent);
      }
      const stat = await fs.lstat(filename).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
      if (stat === null) complete = false;
      else check(stat.isFile() && !stat.isSymbolicLink() && stat.size <= 1024 * 1024, 'invalid-bundle-patch-file');
    }
    return complete;
  }
  function consent(frame, candidates) {
    check(frame.acceptBuildRisk === true && Array.isArray(frame.approvedBuilds) && frame.approvedBuilds.length > 0 &&
      frame.approvedBuilds.length <= 64, 'build-consent-required');
    return frame.approvedBuilds.map(choice => {
      const candidate = candidates.find(value => choice.kind === value.kind && choice.key === value.key && choice.digest === value.digest);
      check(candidate !== undefined, 'stale-build-consent'); return candidate;
    });
  }
  async function prepare(frame, folder, manifest) {
    if (await completePatch(folder, manifest)) return null;
    const scripts = scriptsOf(manifest, true);
    check(typeof scripts.prepare === 'string', 'bundle-patch-missing-without-prepare');
    const candidate = { kind: 'prepare', key: `${manifest.name}@${manifest.version}`, digest: await sourceDigest(folder), scripts };
    if (frame.approvedBuilds === undefined) return { application: 'failed', changed: false, errorCode: 'build-consent-required', buildCandidates: [candidate] };
    consent(frame, [candidate]); await requireFixedPnpm();
    const workspace = await fs.lstat(path.join(folder, 'pnpm-workspace.yaml')).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
    check(workspace === null, 'source-build-monorepo-needs-adaptation');
    if (options.validateSource) await options.validateSource(folder);
    await run(['--ignore-scripts', '--ignore-pnpmfile', 'install', '--no-frozen-lockfile', `--registry=${registryUrl}`], folder);
    const after = JSON.parse(await text(path.join(folder, 'package.json')));
    check(after.name === manifest.name && after.version === manifest.version && after.scripts?.prepare === scripts.prepare, 'source-build-identity-changed');
    await run(['--config.ignore-pnpmfile=true', '--config.enable-pre-post-scripts=false', 'run', 'prepare'], folder, true);
    check(await completePatch(folder, after), 'prepare-did-not-produce-bundle-patch');
    return null;
  }
  async function receipts() {
    try {
      const value = JSON.parse(await text(receiptFile, 1024 * 1024));
      check(value.schema === 1 && value.completed && Object.keys(value.completed).length <= 5000, 'invalid-build-receipt'); return value.completed;
    } catch (error) { if (error.code === 'ENOENT') return {}; throw error; }
  }
  async function catalog() {
    const modules = path.join(profileDir, 'node_modules', '.modules.yaml');
    const raw = await text(modules).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
    if (raw === null) return [];
    const metadata = yaml.parse(raw, { maxAliasCount: 0, strict: true });
    if (metadata.packageManager !== 'pnpm@10.34.5') return [];
    const pending = [...new Set([...(metadata.pendingBuilds ?? []), ...Object.keys(metadata.ignoredBuilds ?? {})])];
    check(pending.length <= 5000 && pending.every(value => typeof value === 'string' && value.length <= 2048), 'invalid-pnpm-build-inventory');
    const lock = yaml.parse(await text(path.join(profileDir, 'pnpm-lock.yaml'), 16 * 1024 * 1024), { maxAliasCount: 0, strict: true });
    const completed = await receipts(); const candidates = [];
    const store = path.join(profileDir, 'node_modules', '.pnpm');
    const directories = await fs.readdir(store, { withFileTypes: true }); check(directories.length <= 10000, 'pnpm-store-too-large');
    for (const entry of directories) {
      if (!entry.isDirectory() || entry.name === 'node_modules') continue;
      const nodeModules = path.join(store, entry.name, 'node_modules');
      const children = await fs.readdir(nodeModules, { withFileTypes: true }).catch(error => { if (error.code === 'ENOENT') return []; throw error; });
      const packages = [];
      for (const child of children) {
        if (child.name.startsWith('@') && child.isDirectory()) {
          for (const scoped of await fs.readdir(path.join(nodeModules, child.name))) packages.push(path.join(nodeModules, child.name, scoped));
        } else if (!child.name.startsWith('.')) packages.push(path.join(nodeModules, child.name));
      }
      for (const folder of packages) {
        const real = await fs.realpath(folder).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
        if (real === null) continue;
        check(inside(profileDir, real) || inside(sourceRoot, real), 'foreign-build-package');
        const manifestText = await text(path.join(real, 'package.json')).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
        if (manifestText === null) continue;
        const manifest = JSON.parse(manifestText); const scripts = scriptsOf(manifest);
        if (Object.keys(scripts).length === 0) continue;
        const keys = pending.filter(key => Object.hasOwn(lock.packages ?? {}, key) &&
          (key === `${manifest.name}@${manifest.version}` || key.startsWith(`${manifest.name}@${manifest.version}(`) || key.startsWith(`${manifest.name}@file:`)));
        for (const depPath of keys) {
          const key = `${manifest.name}@${manifest.version}`;
          const digest = crypto.createHash('sha256').update(manifestText + '\0' + depPath + '\0' +
            JSON.stringify(lock.packages[depPath].resolution ?? {})).digest('hex');
          const receiptId = crypto.createHash('sha256').update(depPath).digest('hex');
          if (completed[receiptId] === digest || candidates.some(value => value.depPath === depPath && value.digest === digest)) continue;
          candidates.push({ kind: 'dependency', key, digest, scripts, depPath, receiptId });
        }
      }
    }
    check(candidates.length <= 256, 'too-many-pending-builds'); return candidates;
  }
  async function build(frame) {
    const selected = consent(frame, await catalog()); check(selected.every(value => value.kind === 'dependency'), 'invalid-dependency-build');
    await requireFixedPnpm();
    const workspaceFile = path.join(profileDir, 'pnpm-workspace.yaml');
    const original = await text(workspaceFile).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
    const workspace = original === null ? {} : yaml.parse(original, { maxAliasCount: 0, strict: true });
    check(workspace && typeof workspace === 'object' && !Array.isArray(workspace), 'invalid-build-workspace');
    const keys = selected.map(value => value.depPath.startsWith(`${value.key}(`) || value.depPath === value.key ? value.key : value.depPath);
    try {
      await writeFileAtomic(workspaceFile, yaml.stringify({ ...workspace, onlyBuiltDependencies: keys,
        onlyBuiltDependenciesFile: undefined, neverBuiltDependencies: [], dangerouslyAllowAllBuilds: false }), { mode: 0o600 });
      await run(['--config.ignore-pnpmfile=true', '--config.ignore-scripts=false', 'rebuild', ...selected.map(value => value.depPath)], profileDir, true);
      const completed = await receipts(); for (const item of selected) completed[item.receiptId] = item.digest;
      await writeFileAtomic(receiptFile, JSON.stringify({ schema: 1, completed }), { mode: 0o600 });
    } finally {
      if (original === null) await fs.rm(workspaceFile, { force: true });
      else await writeFileAtomic(workspaceFile, original, { mode: 0o600 });
    }
    return { application: 'restart-required', changed: true, warnings: ['已执行本次授权的精确包构建；后续安装仍不会自动运行脚本。'] };
  }
  return { prepare, catalog, build };
}
