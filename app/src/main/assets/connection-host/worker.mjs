// Application-owned supervisor for the frozen shared Connect framework.
// Launch credentials arrive through private stdin only. stdout is a framed API.
import { AsyncLocalStorage } from 'node:async_hooks'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { createInterface } from 'node:readline'
import { pathToFileURL } from 'node:url'

const packageRoot = process.argv[2]
const stateRoot = process.argv[3]
if (!packageRoot || !stateRoot || process.argv.length !== 4) process.exit(2)
const scopes = new AsyncLocalStorage()
const pending = new Map()
const methods = new Set(['get_connection_state', 'send_encrypted_message', 'list_incoming_messages', 'acknowledge_message',
  'create_file_transfer', 'upload_file_chunk', 'complete_file_transfer', 'list_incoming_file_transfers',
  'download_file_chunk', 'acknowledge_file_transfer', 'get_group_file_cache_stats', 'cleanup_group_file_cache'])
let plugin, transport, configured = false, disposing = false, configureStage = 'identity'
const write = value => {
  const encoded = JSON.stringify(value, (_key, item) => item instanceof Uint8Array ? Buffer.from(item).toString('base64') : item) + '\n'
  if (Buffer.byteLength(encoded) > 2 * 1024 * 1024) throw new Error('protocol_error')
  process.stdout.write(encoded)
}
const code = error => typeof error?.code === 'string' && /^[a-z_]{1,80}$/.test(error.code) ? error.code : 'protocol_error'
const diagnostics = new Map([
  ['Invalid Android bridge launch parameters.', 'bridge_credentials'],
  ['Invalid IPC JSON or UTF-8.', 'ipc_encoding'],
  ['IPC response identity mismatch.', 'ipc_identity'],
  ['Invalid IPC error envelope.', 'ipc_error_envelope'],
  ['Missing IPC data.', 'ipc_data'],
  ['Invalid connection snapshot.', 'connection_snapshot'],
  ['Connection request failed (protocol_error).', 'native_protocol'],
])
const diagnostic = error => diagnostics.get(error?.message) ?? 'unknown'
write({ kind: 'hello', pid: process.pid, node: process.version, architecture: process.arch, platform: process.platform })

async function configure(value) {
  if (configured || value.protocol !== 'dafeiyu-go.connection.v1' || value.version !== '0.3.0') throw new Error('protocol_error')
  configured = true
  configureStage = 'package'
  const manifest = JSON.parse(await readFile(packageRoot + '/package.json', 'utf8'))
  if (manifest.name !== 'dafeiyu-go-connect' || manifest.version !== value.version || manifest.dsh?.pluginApi !== value.protocol)
    throw new Error('protocol_error')
  configureStage = 'archive'
  const archive = await readFile(packageRoot + '/../archive.tgz')
  const sha256 = createHash('sha256').update(archive).digest('hex')
  if (archive.length > 16 * 1024 * 1024 || archive.length !== value.size_bytes || sha256 !== value.sha256) throw new Error('protocol_error')
  configureStage = 'import'
  const framework = await import(pathToFileURL(packageRoot + '/lib/framework/index.js'))
  class ScopedTransport extends framework.AndroidBridgeTransport {
    wireEnvelope(envelope) {
      const result = super.wireEnvelope(envelope)
      const scope = scopes.getStore()
      return scope ? { ...result, remote_scope: scope } : result
    }
  }
  configureStage = 'framework'
  transport = new ScopedTransport({ env: value.bridge })
  plugin = framework.createPlugin({ ipcTransport: transport, stateDirectory: stateRoot,
    // Online workspaces are computer metadata; this host must not auto-fetch files.
    config: { workspaceSync: { enabled: false, root: null }, broadcast: { enabled: false } }, pollMs: 30_000 })
  await plugin.ready
  configureStage = 'provider'
  const state = (await plugin.provider.get_connection_state({ timeoutMs: 15_000 })).data
  write({ kind: 'ready', version: manifest.version, protocol: plugin.provider.protocol, pid: process.pid,
    sha256, size_bytes: archive.length, state })
}

async function request(value) {
  if (!plugin || disposing || !methods.has(value.method) || typeof value.id !== 'string' ||
      !/^[a-f0-9-]{36}$/.test(value.id) || pending.has(value.id) || pending.size >= 16 ||
      !value.params || Array.isArray(value.params) || typeof value.params !== 'object') throw new Error('protocol_error')
  const controller = new AbortController()
  pending.set(value.id, controller)
  try {
    const params = { ...value.params }
    if (value.method === 'upload_file_chunk') {
      if (typeof params.bytes !== 'string' || Buffer.from(params.bytes, 'base64').toString('base64') !== params.bytes) throw new Error('protocol_error')
      params.bytes = new Uint8Array(Buffer.from(params.bytes, 'base64'))
    }
    const options = { signal: controller.signal, timeoutMs: 55_000 }
    const response = await scopes.run(value.remote_scope, () => value.method === 'get_connection_state'
      ? plugin.provider.get_connection_state(options) : plugin.provider[value.method](params, options))
    write({ kind: 'response', id: value.id, success: true, data: response.data })
  } catch (error) {
    write({ kind: 'response', id: value.id, success: false, error_code: code(error) })
  } finally { pending.delete(value.id) }
}

async function dispose() {
  if (disposing) return
  disposing = true
  for (const controller of pending.values()) controller.abort()
  transport?.dispose()
  await plugin?.dispose()
}

const input = createInterface({ input: process.stdin, crlfDelay: Infinity, terminal: false })
input.on('line', line => {
  if (Buffer.byteLength(line) > 1024 * 1024) { void dispose().finally(() => process.exit(2)); return }
  let value
  try { value = JSON.parse(line) } catch { void dispose().finally(() => process.exit(2)); return }
  if (value.kind === 'configure') {
    // Report only a fixed stage; never send upstream error text or launch credentials.
    void configure(value).catch(error => { write({ kind: 'failed', error_code: code(error), stage: configureStage, diagnostic: diagnostic(error) }); void dispose().finally(() => process.exit(2)) })
  } else if (value.kind === 'call') {
    void request(value).catch(error => { write({ kind: 'response', id: value.id ?? '', success: false, error_code: code(error) }) })
  } else if (value.kind === 'cancel') pending.get(value.id)?.abort()
  else if (value.kind === 'dispose') void dispose().finally(() => process.exit(0))
  else { void dispose().finally(() => process.exit(2)) }
})
input.on('close', () => { void dispose().finally(() => process.exit(0)) })
