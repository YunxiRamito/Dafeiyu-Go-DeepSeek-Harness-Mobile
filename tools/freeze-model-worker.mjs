import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';

// Reads an already reviewed npm v3 lock. It never installs or executes package lifecycle scripts.
const [installedRoot, destination] = process.argv.slice(2);
if (!installedRoot || !destination) throw new Error('Usage: freeze-model-worker.mjs installedRoot outputLock');
const source = await fs.readFile(path.join(installedRoot, 'node_modules/.package-lock.json'));
const lock = JSON.parse(source);
if (lock.lockfileVersion !== 3) throw new Error('A v3 lock is required');
const packages = lock.packages;
const rootKey = 'node_modules/@earendil-works/pi-ai';
if (packages[rootKey]?.version !== '0.87.1') throw new Error('pi-ai must be exactly 0.87.1');
function resolve(from, dependency) {
  let current = from;
  while (true) {
    const candidate = `${current}/node_modules/${dependency}`;
    if (packages[candidate]) return candidate;
    const index = current.lastIndexOf('/node_modules/');
    if (index < 0) break;
    current = current.substring(0, index);
  }
  const root = `node_modules/${dependency}`;
  if (packages[root]) return root;
  throw new Error(`Unresolved frozen dependency ${dependency} for ${from}`);
}
const chosen = new Set();
function visit(key) {
  if (chosen.has(key)) return;
  const entry = packages[key];
  if (!entry?.integrity?.startsWith('sha512-') || !entry.resolved || !entry.version) throw new Error(`Incomplete lock ${key}`);
  // Reviewed: genai preinstall prints a no-op, protobuf postinstall prints a
  // version-scheme warning only. Neither is required and neither is executed.
  if (entry.hasInstallScript && !['node_modules/@google/genai', 'node_modules/protobufjs'].includes(key)) {
    throw new Error(`Lifecycle script needs separate audit: ${key}`);
  }
  chosen.add(key);
  for (const dependency of Object.keys(entry.dependencies ?? {})) visit(resolve(key, dependency));
  for (const dependency of Object.keys(entry.optionalDependencies ?? {})) {
    const resolved = resolve(key, dependency);
    const item = packages[resolved];
    if (item.os && !item.os.includes('linux')) continue;
    if (item.cpu && !item.cpu.includes('arm64') && !item.cpu.includes('x64')) continue;
    visit(resolved);
  }
}
visit(rootKey);
const artifacts = [...chosen].sort().map(key => {
  const entry = packages[key];
  const name = key.substring(key.lastIndexOf('node_modules/') + 'node_modules/'.length);
  const base = name.substring(name.lastIndexOf('/') + 1);
  return { path: key, name, version: entry.version, url: `https://registry.npmjs.org/${name}/-/${base}-${entry.version}.tgz`,
    integrity: entry.integrity, license: entry.license ?? 'SEE_PACKAGE_LICENSE',
    maximumExpandedBytes: 96 * 1024 * 1024 };
});
const output = { schema: 1, component: 'dafeiyu-go-model-worker', version: '0.87.1',
  upstreamCommit: 'f07218c4d4bbc12bef056a7058c3dd49dfe41abe',
  sourceLockSha256: crypto.createHash('sha256').update(source).digest('hex'),
  nodeMinimum: '22.19.0', artifacts };
await fs.mkdir(path.dirname(destination), { recursive: true });
await fs.writeFile(destination, JSON.stringify(output, null, 2) + '\n');
console.log(`Pinned ${artifacts.length} npm artifacts; lifecycle scripts are never run.`);
