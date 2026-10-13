import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { readFile, mkdir } from 'node:fs/promises'
import path from 'node:path'
import { createInterface } from 'node:readline'
import { fileURLToPath, pathToFileURL } from 'node:url'

const [slot, runRoot] = process.argv.slice(2)
if (!slot || !runRoot) throw Error('Expected reviewed package slot and G-drive QA directory')
await mkdir(runRoot, { recursive: true })
const sourceRoot = path.dirname(fileURLToPath(import.meta.url))
const worker = path.resolve(sourceRoot, '../../../app/src/main/assets/connection-host/worker.mjs')
const archive = await readFile(path.resolve(slot, '../archive.tgz'))
const sha256 = createHash('sha256').update(archive).digest('hex')
const child = spawn(process.execPath, ['--import', pathToFileURL(path.join(sourceRoot, 'native-fixture.mjs')).href,
  worker, slot, runRoot], { cwd: runRoot, stdio: ['pipe', 'pipe', 'pipe'] })
const buffered = [], waiting = []
let stderr = '', passes = 0
child.stderr.on('data', bytes => { stderr += bytes.toString() })
const lines = createInterface({ input: child.stdout })
lines.on('line', line => { const value = JSON.parse(line), waiter = waiting.shift(); if (waiter) waiter(value); else buffered.push(value) })
const exited = new Promise(resolve => child.on('exit', code => resolve(code)))
const next = () => Promise.race([buffered.length ? Promise.resolve(buffered.shift()) : new Promise(resolve => waiting.push(resolve)),
  exited.then(code => { throw Error(`Worker exited ${code}`) }),
  new Promise((_, reject) => { const timer = setTimeout(() => reject(Error('Worker fixture timeout')), 10_000); timer.unref() })])
const write = value => child.stdin.write(JSON.stringify(value) + '\n')
try {
  assert.equal((await next()).kind, 'hello'); passes++
  write({ kind: 'configure', protocol: 'dafeiyu-go.connection.v1', version: '0.3.0', sha256, size_bytes: archive.length,
    bridge: { DAFEIYU_CONNECTION_SOCKET: 'dafeiyu-plugin-fixture', DAFEIYU_CONNECTION_CAPABILITY: 'A'.repeat(43),
      DAFEIYU_CONNECTION_GENERATION: '12345678-1234-1234-1234-123456789012' } })
  const ready = await next()
  assert.equal(ready.kind, 'ready'); assert.equal(ready.sha256, sha256); passes++
  const ids = new Set()
  for (let index = 0; index < 8; index++) {
    const id = randomUUID(), group = index % 2 ? 'fixture-group-a' : 'fixture-group-b'
    ids.add(id)
    write({ kind: 'call', id, method: 'send_encrypted_message', remote_scope: { node_id: 'fixture-node', group_id: group,
      local_device_id: 'fixture-phone', remote_device_id: 'fixture-computer' }, params: { group_id: group,
      kind: 'dsh_remote_request', idempotency_key: `dsh-remote-chunk-${index}-fixture`,
      body: { protocol: 'dafeiyu-go.remote-dsh.v1', request_id: `chunk-${index}-fixture`, target_device_id: 'fixture-computer',
        operation: 'artifact.read', payload: { offset: index * 48 * 1024, length: 48 * 1024 } } } })
  }
  const controlId = randomUUID()
  write({ kind: 'call', id: controlId, method: 'get_connection_state', params: {} })
  const control = await next()
  assert.equal(control.id, controlId); assert.equal(control.success, true); passes++
  for (let index = 0; index < 8; index++) {
    const response = await next()
    assert.equal(response.success, true); assert.equal(ids.delete(response.id), true)
    assert.equal(response.data.boundary_peak, 8)
  }
  assert.equal(ids.size, 0); passes++
  const invalidId = randomUUID()
  write({ kind: 'call', id: invalidId, method: 'retire_transfer_receipt', params: {} })
  const invalid = await next()
  assert.equal(invalid.id, invalidId); assert.equal(invalid.success, false); assert.equal(invalid.error_code, 'protocol_error'); passes++
  write({ kind: 'dispose' })
  assert.equal(await exited, 0); assert.equal(stderr, ''); passes++
  console.log(`${passes}/${passes} PASS: real worker + frozen package; eight concurrent synthetic socket requests, isolated scopes, control capacity, no retirement IPC. Android/network concurrency untested.`)
} finally { child.kill(); lines.close() }
