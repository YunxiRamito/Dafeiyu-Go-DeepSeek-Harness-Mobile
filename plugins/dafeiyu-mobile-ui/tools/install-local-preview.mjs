// Development-only installation: one zero-dependency bundle, no package-manager
// reinstall and no DSH process restart. All filesystem changes stay on G:.
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const argument = name => {
  const index = args.indexOf(name);
  if (index < 0 || !args[index + 1]) throw new Error(`Required option: ${name}`);
  return path.resolve(args[index + 1]);
};
const profile = argument('--profile');
const sdkRoot = argument('--sdk');
const evidence = argument('--evidence');
for (const target of [packageRoot, profile, sdkRoot, evidence]) {
  if (!/^G:[\\/]/i.test(target)) throw new Error('Preview installation and evidence must stay on G:');
}
const identity = JSON.parse(await fs.readFile(path.join(packageRoot, 'package.json'), 'utf8'));
if (identity.name !== 'dafeiyu-mobile-ui' || Object.keys(identity.dependencies ?? {}).length) {
  throw new Error('This helper only installs the reviewed zero-dependency mobile UI package');
}
await fs.access(path.join(packageRoot, 'client/client.js'));
const { withFileLock, writeFileAtomic } = await import(pathToFileURL(path.join(sdkRoot, '@deepseek-ai/dsh-atomic-write/lib/index.js')).href);
const manifestPath = path.join(profile, 'package.json');
await fs.mkdir(evidence, { recursive: true });
const checksum = bytes => createHash('sha256').update(bytes).digest('hex');
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const receipt = await withFileLock(manifestPath, async () => {
  const beforeBytes = await fs.readFile(manifestPath);
  const before = JSON.parse(beforeBytes);
  if (before.dsh?.profile?.patchReload !== 'live') {
    throw new Error('The selected profile does not declare live reload; this helper will not restart DSH');
  }
  const link = path.join(profile, 'node_modules', identity.name);
  const source = await fs.realpath(packageRoot);
  const spec = `link:${source.replaceAll('\\', '/')}`;
  if (before.dependencies?.[identity.name] && before.dependencies[identity.name] !== spec) {
    const declared = before.dependencies[identity.name];
    // A reviewed source migration may leave a compatibility junction. Accept
    // only a link resolving to this exact canonical package; never replace a
    // different installation or a registry package.
    if (!declared.startsWith('link:') || (await fs.realpath(path.resolve(profile, declared.slice(5)))).toLowerCase() !== source.toLowerCase()) {
      throw new Error('The profile already declares a different mobile UI package');
    }
  }
  const bundles = before.dsh.profile.bundles ?? [];
  if (!Array.isArray(bundles) || bundles.some(name => typeof name !== 'string')) {
    throw new Error('The profile has an unexpected bundle list; preserve it');
  }
  const backup = path.join(evidence, `package-before-${stamp}.json`);
  await fs.writeFile(backup, beforeBytes, { flag: 'wx' });
  let createdLink = false;
  try {
    const installedSource = await fs.realpath(link);
    if (installedSource.toLowerCase() !== source.toLowerCase()) {
      throw new Error('An existing plugin installation has a different source; preserve it');
    }
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    await fs.symlink(source, link, 'junction');
    createdLink = true;
  }
  const updated = {
    ...before,
    dependencies: { ...before.dependencies, [identity.name]: spec },
    dsh: { ...before.dsh, profile: { ...before.dsh.profile, bundles: [...bundles.filter(name => name !== identity.name), identity.name] } },
  };
  const afterBytes = Buffer.from(`${JSON.stringify(updated, null, 2)}\n`);
  const manifestStat = await fs.stat(manifestPath);
  await writeFileAtomic(manifestPath, afterBytes, { mode: manifestStat.mode & 0o777 });
  return {
    package: identity.name,
    version: identity.version,
    profile,
    source,
    backup,
    createdLink,
    profileBeforeSha256: checksum(beforeBytes),
    profileAfterSha256: checksum(afterBytes),
    clientSha256: checksum(await fs.readFile(path.join(packageRoot, 'client/client.js'))),
    processRestarted: false,
    clientLoadingVerified: false,
    note: 'Manifest committed. Confirm the actual DSH boot graph and browser load before marking verification complete.',
  };
}, { waitMs: 10000 });
await fs.writeFile(path.join(evidence, `installation-${stamp}.json`), `${JSON.stringify(receipt, null, 2)}\n`);
console.log(JSON.stringify({ package: receipt.package, version: receipt.version, createdLink: receipt.createdLink, processRestarted: false, clientLoadingVerified: false }));
