// Android adapter for fixed dsh-session-persistence-jsonl 0.2.1-alpha.1 (MIT).
// Its POSIX publisher uses link(2), which Android's app SELinux domain denies.
// Keep the real first-party flock lease, serialization and fsync protocol;
// publish the already synced sibling with kernel RENAME_NOREPLACE instead.
import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { getSystemErrorName } from 'node:util';
import { pathToFileURL } from 'node:url';

const installed = Symbol.for('dafeiyu.android.persistence.0.2.1-alpha.1');
export async function installAndroidPersistence(bundleRoot) {
  if (process.platform !== 'linux' || bundleRoot !== '/dafeiyu-dsh') throw new Error('ANDROID_PERSISTENCE_PLATFORM');
  const require = createRequire(path.join(bundleRoot, 'package.json'));
  // Koffi intentionally does not export its manifest subpath. Resolve its
  // pinned actual entry rather than asking CJS to bypass its exports map.
  const koffiManifest = JSON.parse(await fs.readFile(path.join(path.dirname(require.resolve('koffi')), 'package.json'), 'utf8'));
  if (require('@deepseek-ai/dsh-session-persistence-jsonl/package.json').version !== '0.2.1-alpha.1' ||
      koffiManifest.name !== 'koffi' || koffiManifest.version !== '3.1.1') throw new Error('ANDROID_PERSISTENCE_VERSION');
  const { default: Persistence } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-session-persistence-jsonl')).href);
  const prototype = Persistence.prototype;
  if (prototype[installed]) return prototype[installed];
  const koffi = require('koffi');
  const libc = koffi.load('libc.so.6');
  const renameat2 = libc.func('int renameat2(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, unsigned int flags)');
  const original = prototype.materializePosix;
  if (typeof original !== 'function' || !['syncDirPosix', 'rejectExistingLog', 'writeSyncedTempFile'].every(name =>
    typeof prototype[name] === 'function')) throw new Error('ANDROID_PERSISTENCE_INTERFACE');
  prototype.materializePosix = async function (project, directory, finalPath, id, content) {
    if (!path.isAbsolute(this.root) || path.dirname(project) !== this.root || path.dirname(directory) !== project ||
        path.dirname(finalPath) !== directory) throw new Error('ANDROID_PERSISTENCE_BOUNDARY');
    // This is the fixed upstream POSIX sequence through its existing methods.
    // JsonlSessionHandle still obtains and retains its actual kernel write
    // lease before it can call materialize; no process ownership is fabricated.
    await fs.mkdir(this.root, { recursive: true, mode: 0o700 });
    await this.syncDirPosix(path.dirname(this.root));
    await fs.mkdir(project, { recursive: true, mode: 0o700 });
    await this.syncDirPosix(this.root);
    await fs.mkdir(directory, { recursive: true, mode: 0o700 });
    await this.syncDirPosix(project);
    await this.rejectExistingLog(finalPath, id);
    const temporary = await this.writeSyncedTempFile(finalPath, content);
    let published = false;
    try {
      if (path.dirname(temporary) !== directory) throw new Error('ANDROID_PERSISTENCE_TEMP_BOUNDARY');
      // AT_FDCWD = -100; RENAME_NOREPLACE = 1. Unlike ordinary rename,
      // this kernel operation refuses an existing destination atomically.
      if (renameat2(-100, temporary, -100, finalPath, 1) !== 0) {
        const errno = koffi.errno();
        const code = Number.isInteger(errno) && errno > 0 ? getSystemErrorName(-errno) : 'EPUBLISH';
        throw Object.assign(new Error('ANDROID_PERSISTENCE_PUBLISH'), { code, syscall: 'renameat2' });
      }
      published = true;
      await this.syncDirPosix(directory);
    } finally {
      if (!published) await fs.rm(temporary, { force: true });
    }
  };
  const dispose = () => { prototype.materializePosix = original; delete prototype[installed]; };
  prototype[installed] = dispose;
  return dispose;
}
