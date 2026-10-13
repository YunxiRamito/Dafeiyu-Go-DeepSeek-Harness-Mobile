// Storage-only permanent deletion for fixed DSH 0.2.1-alpha.1 (MIT).
// The Android bridge owns real stopped-service/exclusive-process admission.
// No profile, user plugin, Agent, prompt, tool or model request is executed.
import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { createHash, randomUUID } from 'node:crypto';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { installAndroidPersistence } from './android-persistence.mjs';

const VERSION = '0.2.1-alpha.1';
const LIMIT = 4 * 1024 * 1024;
const SCOPE = Object.freeze({ group: 'local-shared-history', peer: 'android-local-user' });
const id = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(value) &&
  !['__proto__', 'constructor', 'prototype'].includes(value);
const hash = value => createHash('sha256').update(value).digest('hex');
const object = value => value && typeof value === 'object' && !Array.isArray(value);
function check(value) { if (!value) throw new Error('LOCAL_HISTORY_DELETION_INVALID'); }
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (object(value)) return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  return value;
}
const digest = value => hash(JSON.stringify(canonical(value)));
async function directory(target) {
  await fs.mkdir(target, { recursive: true, mode: 0o700 });
  const info = await fs.lstat(target);
  check(info.isDirectory() && !info.isSymbolicLink() && await fs.realpath(target) === path.resolve(target));
}
async function document(filename) {
  const info = await fs.lstat(filename);
  check(info.isFile() && !info.isSymbolicLink() && info.size <= LIMIT);
  return JSON.parse(await fs.readFile(filename, 'utf8'));
}
function validateRequest(request) {
  check(object(request) && Object.keys(request).every(key => ['operation', 'requestId', 'nativeId', 'sessionIds',
    'mappingRevision', 'mappingSha256', 'confirmDelete'].includes(key)));
  check(request.operation === 'delete-history' && request.confirmDelete === true && id(request.requestId) && id(request.nativeId) &&
    Number.isSafeInteger(request.mappingRevision) && request.mappingRevision >= 0 && /^[0-9a-f]{64}$/.test(request.mappingSha256) &&
    Array.isArray(request.sessionIds) && request.sessionIds.length <= 256 && request.sessionIds.every(id) &&
    new Set(request.sessionIds).size === request.sessionIds.length && request.sessionIds.every((value, index) => index === 0 || value > request.sessionIds[index - 1]));
}
async function mappingWitness(filename, request) {
  const value = await document(filename);
  check(value.schema === 1 && value.revision === request.mappingRevision && digest(value) === request.mappingSha256 &&
    object(value.links) && Object.keys(value.links).length <= 10000);
  const entries = Object.entries(value.links).filter(([, link]) => link?.nativeId === request.nativeId)
    .sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([sessionId, link]) => {
      check(id(sessionId) && object(link) && id(link.nativeId) && id(link.workspaceId) &&
        Number.isSafeInteger(link.createdAt) && link.createdAt >= 0 && /^[0-9a-f]{64}$/.test(link.nativeFingerprint) &&
        (link.published === undefined || typeof link.published === 'boolean'));
      return { sessionId, published: link.published === true, linkDigest: digest(link) };
    });
  check(JSON.stringify(entries.map(item => item.sessionId)) === JSON.stringify(request.sessionIds));
  return entries;
}
function validateLedger(value) {
  check(object(value) && value.version === 1 && Array.isArray(value.intents) && value.intents.length <= 1000);
  const requests = new Set();
  for (const intent of value.intents) {
    validateRequest(intent.request);
    check(!requests.has(intent.request.requestId) && Number.isSafeInteger(intent.createdAt) && intent.createdAt >= 0 &&
      ['deleting', 'complete'].includes(intent.phase) && Array.isArray(intent.entries) && Array.isArray(intent.items) &&
      intent.entries.length === intent.request.sessionIds.length && intent.items.length === intent.entries.length);
    requests.add(intent.request.requestId);
    for (let index = 0; index < intent.items.length; index++) {
      const entry = intent.entries[index], item = intent.items[index];
      check(object(entry) && entry.sessionId === intent.request.sessionIds[index] && typeof entry.published === 'boolean' &&
        /^[0-9a-f]{64}$/.test(entry.linkDigest) && object(item) && item.sessionId === entry.sessionId &&
        ['pending', 'archived', 'prepared', 'deleted', 'not_materialized'].includes(item.phase) &&
        id(item.archiveRequestId) && id(item.prepareRequestId) && id(item.deleteRequestId));
      if (['prepared', 'deleted'].includes(item.phase)) check(object(item.prepared) && id(item.prepared.ticket) &&
        item.prepared.sessionId === item.sessionId && item.prepared.status === 'prepared');
      if (item.phase === 'deleted') check(object(item.deleted) && item.deleted.ticket === item.prepared.ticket &&
        item.deleted.sessionId === item.sessionId && item.deleted.deleted === true && item.deleted.status === 'deleted');
      if (item.phase === 'not_materialized') check(entry.published === false);
    }
    if (intent.phase === 'complete') check(intent.items.every(item => ['deleted', 'not_materialized'].includes(item.phase)));
  }
  return value;
}
async function compose(paths) {
  const anchor = path.join(paths.bundleRoot, 'node_modules/@deepseek-ai/dsh/package.json');
  const require = createRequire(path.join(paths.bundleRoot, 'package.json'));
  const load = async name => {
    const metadata = require(`@deepseek-ai/${name}/package.json`);
    check(metadata.license === 'MIT' && (name === 'cordis' || metadata.version === VERSION));
    return import(pathToFileURL(require.resolve(`@deepseek-ai/${name}`)).href);
  };
  const archiveDirectory = path.join(paths.home, 'dafeiyu-connection-plugin/workspace-lifecycle');
  const archive = await import(pathToFileURL(path.join(paths.installedPackage, 'lib/framework/remote-archive-dsh.js')).href);
  await archive.assertFixedDshArchivePreload(anchor, archiveDirectory);
  if (paths.bundleRoot === '/dafeiyu-dsh') await installAndroidPersistence(paths.bundleRoot);
  const [{ Context, Service }, { SessionStore }, { default: Persistence }] =
    await Promise.all(['cordis', 'dsh-session', 'dsh-session-persistence-jsonl'].map(load));
  const ctx = new Context();
  let lease, lifecycle;
  try {
    new SessionStore(ctx);
    const persistence = new Persistence(ctx, { root: path.join(paths.home, 'sessions'), compression: 'zstd' });
    const leaseDirectory = path.join(paths.home, 'dafeiyu-local-history-runtime');
    await directory(leaseDirectory);
    lease = await persistence.acquireLease('dafeiyu-local-shared-runtime', undefined, leaseDirectory);
    const [{ default: Storage }, JsonStorage, Domains, { WorkspaceRegistry }, { SessionProjectionRegistry },
      { AgentRegistry }, { AgentLoop }, { default: Tools }, { default: SystemPrompt }, { default: Llm },
      { SqliteSessionQueryEngine }, { TypertRegistry }, { SessionProjectionCache }, { SessionController },
      { LocalAttachmentStore }, { CommandRuntime }, { HostConnectionService }, { FileUploads }] =
      await Promise.all(['dsh-storage', 'dsh-storage-json', 'dsh-storage-domain', 'dsh-workspace', 'dsh-session-projection',
        'dsh-agent', 'dsh-agent-loop', 'dsh-tools', 'dsh-system-prompt', 'dsh-llm', 'dsh-session-query-sqlite',
        'dsh-typert-registry', 'dsh-session-projection-cache', 'dsh-api-session-controller', 'dsh-attachment-local',
        'dsh-commands', 'dsh-client-connection', 'dsh-client-file-upload'].map(load));
    new Storage(ctx); JsonStorage.apply(ctx, { root: path.join(paths.home, 'storages') });
    await Domains.apply(ctx, { backend: 'json' });
    const registry = new WorkspaceRegistry(ctx); await registry[Service.init]();
    new SessionProjectionRegistry(ctx); new AgentRegistry(ctx); new SystemPrompt(ctx, {}); new Tools(ctx); new Llm(ctx);
    const cache = new SessionProjectionCache(ctx, { writeEveryEvents: 200, writeIntervalMs: 5000 }); await cache[Service.init]();
    new AgentLoop(ctx, { agents: [], maxParallelToolCalls: 10 });
    // Fixed mobile Web profile uses :memory:. Persistent first-party cache and
    // native registry share the owning home's actual storages directory.
    const query = new SqliteSessionQueryEngine(ctx, { path: ':memory:', openAt: 'startup' }); await query[Service.init]();
    new TypertRegistry(ctx); new LocalAttachmentStore(ctx, { dshHome: paths.home }); new CommandRuntime(ctx);
    new HostConnectionService(ctx, [], undefined); new FileUploads(ctx); new SessionController(ctx, {});
    const driver = new archive.FixedDshArchiveDriver(ctx);
    check(driver.available());
    const { RemoteWorkspaceLifecycle } = await import(pathToFileURL(path.join(paths.installedPackage, 'lib/framework/remote-workspaces.js')).href);
    lifecycle = new RemoteWorkspaceLifecycle(ctx, { directory: archiveDirectory, archiveDeletion: true });
    await lifecycle.ready; check(await lifecycle.ensureArchiveDeletion());
    return { ctx, persistence, driver, lifecycle,
      async dispose() { try { await lifecycle.dispose(); } finally { try { await lease.release(); } finally { await ctx.fiber.dispose(); } } } };
  } catch (error) {
    try { await lifecycle?.dispose(); } finally { try { await lease?.release(); } finally { await ctx.fiber.dispose(); } }
    throw error;
  }
}

/** Direct calls are for reviewed isolated evidence. The CLI supplies only
 * fixed private mounts; peers/paths/service claims cannot enter its request. */
export async function executeSharedHistoryDeletion(paths, request, hooks = {}) {
  validateRequest(request);
  for (const value of Object.values(paths)) check(typeof value === 'string' && path.isAbsolute(value));
  const native = await compose(paths);
  try {
    const stateDirectory = path.join(paths.home, 'dafeiyu-local-history-deletion');
    await directory(stateDirectory);
    const file = path.join(stateDirectory, 'deletion-intents.json');
    let state = { version: 1, intents: [] };
    try { state = validateLedger(await document(file)); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    const save = async () => {
      validateLedger(state);
      const bytes = Buffer.from(JSON.stringify(state)); check(bytes.length <= LIMIT);
      const temp = path.join(stateDirectory, `.intent-${randomUUID()}.tmp`), handle = await fs.open(temp, 'wx', 0o600);
      try { await handle.writeFile(bytes); await handle.sync(); } finally { await handle.close(); }
      await native.driver.durableMove(temp, file, true);
      if (process.platform !== 'win32') { const parent = await fs.open(stateDirectory, 'r'); try { await parent.sync(); } finally { await parent.close(); } }
    };
    let intent = state.intents.find(item => item.request.requestId === request.requestId);
    if (intent) check(digest(intent.request) === digest(request));
    const mapping = () => mappingWitness(path.join(paths.mappingRoot, 'links.json'), request);
    if (!intent) {
      check(state.intents.length < 1000 && !state.intents.some(item => item.request.nativeId === request.nativeId && item.phase !== 'complete'));
      const entries = await mapping();
      intent = { request: structuredClone(request), createdAt: Date.now(), entries, phase: 'deleting',
        items: entries.map(entry => ({ sessionId: entry.sessionId, phase: 'pending', archiveRequestId: randomUUID(),
          prepareRequestId: randomUUID(), deleteRequestId: randomUUID() })) };
      state.intents.push(intent); await save();
    }
    const signal = AbortSignal.timeout(75_000);
    const current = async () => { signal.throwIfAborted(); check(digest(await mapping()) === digest(intent.entries)); };
    if (intent.phase !== 'complete') {
      await current();
      for (let index = 0; index < intent.items.length; index++) {
        const item = intent.items[index];
        if (['deleted', 'not_materialized'].includes(item.phase)) continue;
        await current();
        if (item.phase === 'pending') {
          const stored = await native.persistence.stat(item.sessionId);
          if (!stored) {
            // Only a not-acknowledged write with no physical artifact may be
            // absent. A published log missing without our receipt is unsafe.
            check(intent.entries[index].published === false);
            item.phase = 'not_materialized'; await save(); continue;
          }
          const reply = await native.lifecycle.execute('session.archive', { sessionId: item.sessionId }, item.archiveRequestId, signal, SCOPE);
          check(reply.sessionId === item.sessionId && reply.archived === true && reply.tasksStopped === true);
          item.phase = 'archived'; await save();
        }
        // Keep one stable local intent for all versions. Native tickets are
        // prepared immediately before each delete, as deleting a sibling
        // legitimately updates the shared workspace snapshot.
        if (item.phase === 'archived') {
          const reply = await native.lifecycle.execute('session.archived.delete.prepare', { sessionId: item.sessionId }, item.prepareRequestId, signal, SCOPE);
          check(reply.sessionId === item.sessionId && reply.status === 'prepared' && id(reply.ticket));
          item.prepared = reply; item.phase = 'prepared'; await save();
        }
        const reply = await native.lifecycle.execute('session.archived.delete', { ticket: item.prepared.ticket, confirmDelete: true }, item.deleteRequestId, signal, SCOPE);
        check(reply.ticket === item.prepared.ticket && reply.sessionId === item.sessionId && reply.deleted === true && reply.status === 'deleted');
        await hooks.afterNativeDelete?.(item.sessionId);
        item.deleted = reply; item.phase = 'deleted'; await save();
      }
      await current(); intent.phase = 'complete'; intent.completedAt = Date.now(); await save();
    }
    return { requestId: request.requestId, nativeId: request.nativeId, deleted: true, status: 'complete', executionDevice: 'local_phone',
      mappingRevision: request.mappingRevision, mappingSha256: request.mappingSha256,
      items: intent.items.map(item => ({ sessionId: item.sessionId, status: item.phase,
        ...(item.deleted ? { ticket: item.deleted.ticket, deletedAt: item.deleted.deletedAt } : {}) })) };
  } finally { await native.dispose(); }
}

async function main() {
  check(process.argv[2] === '/dafeiyu-dsh' && process.env.DSH_HOME === '/dafeiyu-dsh-home');
  let bytes = Buffer.alloc(0);
  for await (const part of process.stdin) { check(bytes.length + part.length <= LIMIT); bytes = Buffer.concat([bytes, part]); }
  const value = await executeSharedHistoryDeletion({ bundleRoot: '/dafeiyu-dsh', home: '/dafeiyu-dsh-home',
    mappingRoot: '/dafeiyu-shared-data', installedPackage: '/dafeiyu-connection-slot/package' }, JSON.parse(bytes.toString('utf8')));
  process.stdout.write(JSON.stringify({ ok: true, value }) + '\n');
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(error => {
  const reasons = ['ENOENT', 'EACCES', 'EPERM', 'EINVAL', 'EBUSY', 'EAGAIN', 'ERR_DLOPEN_FAILED', 'MODULE_NOT_FOUND',
    'ERR_MODULE_NOT_FOUND', 'ERR_INVALID_ARG_VALUE', 'ERR_FLOCK_UNSUPPORTED_PLATFORM'];
  process.stdout.write(JSON.stringify({ ok: false, code: 'SHARED_HISTORY_DELETION_FAILED',
    reason: reasons.includes(error?.code) ? error.code : 'INVALID' }) + '\n');
  process.exitCode = 1;
});
