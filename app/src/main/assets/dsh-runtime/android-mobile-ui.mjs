// Installs the signed-APK, zero-dependency UI bundle through the reviewed
// DSH web-profile contract. It neither runs npm scripts nor restarts DSH.
import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';

const name = 'dafeiyu-mobile-ui';
const version = '0.1.5';
const pinnedDsh = '0.2.1-alpha.1';
const check = (value, code) => { if (!value) throw new Error(code); };
const missing = error => { if (error.code === 'ENOENT') return null; throw error; };

async function plainPath(root, target) {
  const boundary = path.resolve(root);
  let current = path.resolve(target);
  check(current === boundary || current.startsWith(boundary + path.sep), 'ANDROID_MOBILE_UI_PATH');
  while (true) {
    const info = await fs.lstat(current).catch(missing);
    check(!info?.isSymbolicLink(), 'ANDROID_MOBILE_UI_LINK');
    if (current === boundary) break;
    current = path.dirname(current);
  }
}

async function readJson(filename, limit) {
  const info = await fs.lstat(filename);
  check(info.isFile() && !info.isSymbolicLink() && info.size > 0 && info.size <= limit, 'ANDROID_MOBILE_UI_JSON');
  return JSON.parse(await fs.readFile(filename, 'utf8'));
}

export async function prepareAndroidMobileUi(bundleRoot, dshHome, packageDirectory,
  { sourceRoot = '/dafeiyu-extension-packages/mobile-ui' } = {}) {
  check(typeof packageDirectory === 'string' && path.isAbsolute(packageDirectory), 'ANDROID_MOBILE_UI_SOURCE');
  const sources = path.resolve(sourceRoot);
  const source = path.resolve(packageDirectory);
  const ownedSource = target => /^[a-f0-9]{64}\/package$/.test(path.relative(sources, target).split(path.sep).join('/'));
  check(ownedSource(source), 'ANDROID_MOBILE_UI_SOURCE');
  await plainPath(sources, source);
  const identity = await readJson(path.join(source, 'package.json'), 32 * 1024);
  check(identity.name === name && identity.version === version && identity.license === 'MIT' &&
    Object.keys(identity.dependencies ?? {}).length === 0 && identity.exports?.['./client'] === './client/client.js' &&
    identity.dsh?.client?.platform === 'web', 'ANDROID_MOBILE_UI_IDENTITY');
  const require = createRequire(path.join(path.resolve(bundleRoot), 'package.json'));
  check(require('@deepseek-ai/dsh/package.json').version === pinnedDsh &&
    require('@deepseek-ai/dsh-app-boot/package.json').version === pinnedDsh, 'ANDROID_MOBILE_UI_DSH_VERSION');
  const boot = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-app-boot')).href);
  const { withFileLock, writeFileAtomic } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-atomic-write')).href);
  const home = path.resolve(dshHome);
  const profile = path.join(home, 'profiles', 'web');
  await plainPath(home, profile);
  await fs.mkdir(profile, { recursive: true, mode: 0o700 });
  const manifestPath = path.join(profile, 'package.json');
  return withFileLock(manifestPath, async () => {
    const current = await fs.lstat(manifestPath).catch(missing);
    if (!current) await boot.initProfile(profile, boot.PROFILE_TEMPLATES.web?.bundles ?? boot.DEFAULT_PROFILE_BUNDLES);
    const before = await readJson(manifestPath, 1024 * 1024);
    check(before && typeof before === 'object' && !Array.isArray(before) &&
      before.dsh?.profile && typeof before.dsh.profile === 'object', 'ANDROID_MOBILE_UI_PROFILE');
    const bundles = before.dsh.profile.bundles ?? [];
    check(Array.isArray(bundles) && bundles.every(value => typeof value === 'string') && bundles.length <= 256 &&
      (!before.dependencies || typeof before.dependencies === 'object' && !Array.isArray(before.dependencies)),
    'ANDROID_MOBILE_UI_PROFILE');
    const previousSpec = before.dependencies?.[name];
    if (previousSpec !== undefined) {
      check(typeof previousSpec === 'string' && /^(file|link):/.test(previousSpec) &&
        ownedSource(path.resolve(profile, previousSpec.replace(/^(file|link):/, ''))), 'ANDROID_MOBILE_UI_FOREIGN_PACKAGE');
    }
    const modules = path.join(profile, 'node_modules');
    await plainPath(home, modules);
    await fs.mkdir(modules, { recursive: true, mode: 0o700 });
    const link = path.join(modules, name);
    const linkInfo = await fs.lstat(link).catch(missing);
    let previousLink = null;
    if (linkInfo) {
      check(linkInfo.isSymbolicLink(), 'ANDROID_MOBILE_UI_FOREIGN_INSTALL');
      previousLink = await fs.readlink(link);
      check(ownedSource(path.resolve(modules, previousLink)), 'ANDROID_MOBILE_UI_FOREIGN_INSTALL');
    }
    // link: retains the signed, verified source through the ordinary plugin
    // manager's hoisted and isolated pnpm install/repair paths.
    const spec = `link:${source.split(path.sep).join('/')}`;
    const alreadyLinked = previousLink !== null && path.resolve(modules, previousLink) === source;
    const alreadyDeclared = previousSpec === spec && bundles.filter(value => value === name).length === 1;
    if (alreadyLinked && alreadyDeclared) return { name, version, changed: false };
    const temporaryLink = path.join(modules, `.mobile-ui-${randomUUID()}`);
    let linkChanged = false;
    try {
      if (!alreadyLinked) {
        await fs.symlink(source, temporaryLink, process.platform === 'win32' ? 'junction' : 'dir');
        await fs.rename(temporaryLink, link);
        linkChanged = true;
      }
      const after = { ...before, dependencies: { ...before.dependencies, [name]: spec },
        dsh: { ...before.dsh, profile: { ...before.dsh.profile,
          bundles: [...bundles.filter(value => value !== name), name] } } };
      await writeFileAtomic(manifestPath, `${JSON.stringify(after, null, 2)}\n`, { mode: 0o600 });
      return { name, version, changed: true };
    } catch (error) {
      if (linkChanged) {
        if (previousLink === null) await fs.unlink(link);
        else {
          await fs.symlink(previousLink, temporaryLink, process.platform === 'win32' ? 'junction' : 'dir');
          await fs.rename(temporaryLink, link);
        }
      }
      throw error;
    } finally {
      const remaining = await fs.lstat(temporaryLink).catch(missing);
      if (remaining?.isSymbolicLink()) await fs.unlink(temporaryLink);
    }
  }, { waitMs: 10000 });
}
