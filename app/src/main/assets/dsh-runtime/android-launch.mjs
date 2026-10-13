import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { installAndroidSandbox } from './android-sandbox.mjs';
import { installAndroidProviderOrder } from './android-provider-order.mjs';
import { installAndroidPersistence } from './android-persistence.mjs';
const [bundleRoot, nativeDirectory, ...args] = process.argv.slice(2);
if (bundleRoot !== '/dafeiyu-dsh') throw new Error('ANDROID_DSH_BUNDLE');
const anchor = '/dafeiyu-dsh/node_modules/@deepseek-ai/dsh/package.json';
const archiveDirectory = '/dafeiyu-dsh-home/dafeiyu-connection-plugin/workspace-lifecycle';
const preload = '/dafeiyu-connection-slot/package/scripts/archive-preload.mjs';
if (process.env.DAFEIYU_DSH_INSTALL_ANCHOR !== anchor || process.env.DAFEIYU_DSH_ARCHIVE_DIRECTORY !== archiveDirectory ||
    process.env.DSH_HOME !== '/dafeiyu-dsh-home' || !process.execArgv.some((value, index, values) =>
      value === '--import' && values[index + 1] === preload)) throw new Error('ANDROID_DSH_ARCHIVE_STARTUP');
const { assertFixedDshArchivePreload } = await import('/dafeiyu-connection-slot/package/lib/framework/remote-archive-dsh.js');
if (typeof assertFixedDshArchivePreload !== 'function') throw new Error('ANDROID_DSH_ARCHIVE_PRELOAD_INTERFACE');
// Refuse an old/mismatched preloader before any configured Agent or tool exists.
await assertFixedDshArchivePreload(anchor, archiveDirectory);
await installAndroidSandbox(bundleRoot, nativeDirectory, ['/dafeiyu-dsh', '/dafeiyu-dsh-home', '/dafeiyu-dsh-launch', '/dafeiyu-workspaces', '/dafeiyu-provider-order', '/dafeiyu-connection-slot']);
const removePersistence = await installAndroidPersistence(bundleRoot);
process.once('exit', removePersistence);
const removeOrder = await installAndroidProviderOrder(bundleRoot, '/dafeiyu-provider-order');
process.once('exit', removeOrder);
const entry = path.join(bundleRoot, 'node_modules/@deepseek-ai/dsh/lib/bin.js');
process.argv = [process.execPath, entry, ...args];
// The pinned CLI uses import.meta.main; dynamic import does not dispatch it.
// Call its actual exported entry rather than emulating command parsing.
const { runCli } = await import(pathToFileURL(entry).href);
if (typeof runCli !== 'function') throw new Error('ANDROID_DSH_CLI_INTERFACE');
await runCli();
