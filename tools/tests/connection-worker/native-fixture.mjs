// Synthetic socket only. The worker and frozen Connect package remain real.
import net from 'node:net'
import { EventEmitter } from 'node:events'
import assert from 'node:assert/strict'

const state = {
  protocol: 'dafeiyu-go.connection.v1', server_connected: false, node_id: null, group_id: null,
  pairing_status: 'unknown', relationship_confirmed: false, terminal_receipt: null,
  local_device: { id: '', name: null, public_key: null }, remote_device: null, members: [],
  capabilities: { workspace_sync: false, web_broadcast: false, encrypted_messages: false,
    encrypted_files: false, presence: false, file_cache_cleanup: false,
    remote_task: false, remote_directory: false, remote_settings: false, remote_web_tunnel: false,
    remote_artifacts: false, remote_attachments: false },
}
let active = 0, maximum = 0
net.createConnection = function (endpoint) {
  assert.equal(endpoint, '\0dafeiyu-plugin-fixture')
  const socket = new EventEmitter()
  let counted = false
  socket.destroy = () => { socket.destroyed = true; if (counted) { counted = false; active-- } }
  socket.write = bytes => {
    const request = JSON.parse(bytes.toString())
    assert.equal(request.protocol, 'dafeiyu-go.connection.v1')
    assert.equal(request.capability, 'A'.repeat(43))
    assert.equal(request.generation, '12345678-1234-1234-1234-123456789012')
    assert.ok(request.nonce && request.request_id)
    if (request.method === 'get_connection_state') {
      queueMicrotask(() => socket.emit('data', Buffer.from(JSON.stringify({ request_id: request.request_id, success: true, data: state }) + '\n')))
    } else {
      assert.equal(request.method, 'send_encrypted_message')
      assert.equal(request.params.kind, 'dsh_remote_request')
      const scope = request.remote_scope
      assert.deepEqual(scope, { node_id: 'fixture-node', group_id: request.params.group_id,
        local_device_id: 'fixture-phone', remote_device_id: 'fixture-computer' })
      assert.equal(request.params.body.target_device_id, scope.remote_device_id)
      assert.equal(request.params.body.operation, 'artifact.read')
      active++; counted = true; maximum = Math.max(maximum, active)
      setTimeout(() => {
        if (!socket.destroyed) socket.emit('data', Buffer.from(JSON.stringify({ request_id: request.request_id, success: true,
          data: { message_id: request.params.body.request_id, boundary_peak: maximum } }) + '\n'))
      }, 250)
    }
  }
  queueMicrotask(() => socket.emit('connect'))
  return socket
}
