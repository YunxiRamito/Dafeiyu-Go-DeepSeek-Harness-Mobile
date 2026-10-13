// Fixed adapter for DSH 0.2.1-alpha.1 / node-addon-system 0.1.2.
// Keeps the first-party policy, backend selection, approval and failure rules.
// PRoot-specific external read/exec roots are added only to Landlock grants.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const installed = Symbol.for('dafeiyu.android.sandbox.0.2.1-alpha.1');
export async function installAndroidSandbox(bundleRoot, nativeDirectory, privateMounts = []) {
  if (process.platform !== 'linux') throw new Error('ANDROID_SANDBOX_PLATFORM');
  if (!nativeDirectory?.startsWith('/data/app/') || nativeDirectory.split('/').includes('..') || nativeDirectory.includes('\0'))
    throw new Error('ANDROID_SANDBOX_LOADER');
  if (!Array.isArray(privateMounts) || privateMounts.length > 12 || !privateMounts.every(value =>
    typeof value === 'string' && /^\/dafeiyu-[A-Za-z0-9._/-]+$/.test(value) && !value.split('/').includes('..')))
    throw new Error('ANDROID_SANDBOX_MOUNTS');
  const require = createRequire(path.join(path.resolve(bundleRoot), 'package.json'));
  if (require('@deepseek-ai/dsh-sandbox-local/package.json').version !== '0.2.1-alpha.1' ||
      require('@deepseek-ai/node-addon-system/package.json').version !== '0.1.2') throw new Error('ANDROID_SANDBOX_VERSION');
  const { LocalSandboxProvider } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-sandbox-local')).href);
  const { SandboxUnavailableError } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-sandbox')).href);
  const api = await import(pathToFileURL(require.resolve('@deepseek-ai/node-addon-system/landlock-run')).href);
  const prototype = LocalSandboxProvider.prototype;
  if (prototype[installed]) throw new Error('ANDROID_SANDBOX_ALREADY_INSTALLED');
  const original = prototype.runnerArgv;
  if (typeof original !== 'function') throw new Error('ANDROID_SANDBOX_INTERFACE');
  const readOnly = [...new Set([nativeDirectory, '/system', '/apex', '/proc', '/sys', '/dev', ...privateMounts])];
  if (!readOnly.every(value => fs.existsSync(value))) throw new Error('ANDROID_SANDBOX_MOUNT_UNAVAILABLE');
  prototype.runnerArgv = function (runner, policy) {
    const base = original.call(this, runner, policy);
    if (runner !== 'landlock') return base;
    if (api.probe(base[0], { timeoutMs: 5000 }) !== 'full')
      throw new SandboxUnavailableError(policy.mode, 'Android kernel has incomplete Landlock enforcement');
    if (!base.includes('--ro') || !base.includes('/') || !path.isAbsolute(policy.workspaceRoot))
      throw new SandboxUnavailableError(policy.mode, 'Pinned runner profile changed');
    return base.concat(api.grantArgs({ readOnly: [...readOnly, policy.workspaceRoot] }));
  };
  prototype[installed] = true;
  return () => { prototype.runnerArgv = original; delete prototype[installed]; };
}
