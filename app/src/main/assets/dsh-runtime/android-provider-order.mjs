// Narrow metadata adapter for fixed DSH 0.2.1-alpha.1 (upstream MIT).
// The launcher owns route ordering; no DSH Config key or provider fact is invented.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const installed = Symbol.for('dafeiyu.android.provider-order.0.2.1-alpha.1');
const invalid = () => new Error('ANDROID_PROVIDER_ORDER_INVALID');
function readProjection(directory) {
  const file = path.join(directory, 'order.json');
  if (fs.lstatSync(file).isSymbolicLink()) throw invalid();
  const fd = fs.openSync(file, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW ?? 0));
  try {
    const stat = fs.fstatSync(fd);
    if (!stat.isFile() || stat.size < 1 || stat.size > 32 * 1024) throw invalid();
    const bytes = Buffer.alloc(stat.size + 1);
    let received = 0;
    while (received < bytes.length) {
      const count = fs.readSync(fd, bytes, received, bytes.length - received, null);
      if (count === 0) break;
      received += count;
    }
    if (received !== stat.size) throw invalid();
    const value = JSON.parse(bytes.subarray(0, received).toString('utf8'));
    if (Object.keys(value).sort().join(',') !== 'revision,routes,schema' || value.schema !== 1 ||
        !Number.isSafeInteger(value.revision) || value.revision < 0 || !Array.isArray(value.routes) ||
        value.routes.length < 1 || value.routes.length > 128 || new Set(value.routes).size !== value.routes.length ||
        !value.routes.every(route => typeof route === 'string' && route.length <= 512 && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(route))) throw invalid();
    return value;
  } finally { fs.closeSync(fd); }
}

export async function installAndroidProviderOrder(bundleRoot, directory, { intervalMs = 1000, onError = () =>
  console.error('dsh: provider order projection unavailable; retaining the last verified order') } = {}) {
  if (!Number.isInteger(intervalMs) || intervalMs < 20 || intervalMs > 60_000) throw invalid();
  const require = createRequire(path.join(path.resolve(bundleRoot), 'package.json'));
  if (require('@deepseek-ai/dsh-llm/package.json').version !== '0.2.1-alpha.1') throw new Error('ANDROID_PROVIDER_ORDER_VERSION');
  const { LlmRuntime } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-llm')).href);
  const prototype = LlmRuntime.prototype;
  const originalProviders = prototype.listProviders;
  const originalDirectory = prototype.listConfigurableProviders;
  const originalEmit = prototype.emitAdaptersUpdated;
  if (prototype[installed] || [originalProviders, originalDirectory, originalEmit].some(value => typeof value !== 'function'))
    throw new Error('ANDROID_PROVIDER_ORDER_INTERFACE');
  let projection = readProjection(directory);
  let signature = JSON.stringify(projection.routes);
  let reported = false;
  const runtimes = new Set();
  function refresh() {
    try {
      const next = readProjection(directory);
      if (next.revision < projection.revision) throw invalid();
      const changed = JSON.stringify(next.routes) !== signature;
      projection = next;
      signature = JSON.stringify(next.routes);
      reported = false;
      if (changed) for (const runtime of runtimes) originalEmit.call(runtime);
    } catch {
      if (!reported) { reported = true; onError(); }
    }
  }
  function ordered(rows, key) {
    const positions = new Map(projection.routes.map((route, index) => [route, index]));
    return rows.map((row, index) => ({ row, index })).sort((a, b) =>
      (positions.get(a.row[key]) ?? Number.MAX_SAFE_INTEGER) - (positions.get(b.row[key]) ?? Number.MAX_SAFE_INTEGER) ||
      a.index - b.index).map(item => item.row);
  }
  prototype.listProviders = function (...args) {
    runtimes.add(this); refresh();
    return ordered(originalProviders.apply(this, args), 'id');
  };
  prototype.listConfigurableProviders = function (...args) {
    runtimes.add(this); refresh();
    return ordered(originalDirectory.apply(this, args), 'provider');
  };
  prototype[installed] = true;
  const timer = setInterval(refresh, intervalMs);
  timer.unref();
  return () => {
    clearInterval(timer);
    prototype.listProviders = originalProviders;
    prototype.listConfigurableProviders = originalDirectory;
    delete prototype[installed];
    runtimes.clear();
  };
}
