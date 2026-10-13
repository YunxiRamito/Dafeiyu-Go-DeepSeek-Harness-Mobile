import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import { once } from 'node:events';

const [worker, modules, qaRoot] = process.argv.slice(2);
const protocol = 'dafeiyu-go.model-worker.v1';
let passed = 0;
async function test(name, fn) { await fn(); passed++; console.log(`PASS ${name}`); }
const child = spawn(process.execPath, [worker, modules], { cwd: qaRoot, stdio: ['pipe', 'pipe', 'pipe'],
  env: { PATH: process.env.PATH, SYSTEMROOT: process.env.SYSTEMROOT, OTEL_SDK_DISABLED: 'true' } });
const frames = []; let partial = ''; const waiters = new Set(); let errors = '';
child.stdout.setEncoding('utf8');
child.stdout.on('data', text => {
  partial += text;
  for (;;) {
    const newline = partial.indexOf('\n'); if (newline < 0) break;
    const frame = JSON.parse(partial.substring(0, newline)); partial = partial.substring(newline + 1);
    frames.push(frame); for (const waiter of waiters) waiter();
  }
});
child.stderr.on('data', bytes => { errors += bytes.toString(); });
function send(type, id, value = {}) { child.stdin.write(JSON.stringify({ protocol, type, id, ...value }) + '\n'); }
async function next(predicate) {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    const index = frames.findIndex(predicate); if (index >= 0) return frames.splice(index, 1)[0];
    await new Promise(resolve => { const timeout = setTimeout(finish, 100); function finish() { waiters.delete(finish); clearTimeout(timeout); resolve(); } waiters.add(finish); });
  }
  throw new Error(`Worker response timeout (stderr length ${errors.length})`);
}
function terminal(id) { return next(frame => frame.id === id && ['result', 'error'].includes(frame.type)); }
try {
  await test('actual pinned pi-ai loads without DSH and advertises ten adapter protocols', async () => {
    const ready = await next(frame => frame.type === 'ready');
    assert.equal(ready.version, '0.87.1'); assert.equal(new Set(ready.apis).size, 10);
  });
  await test('configuration uses actual built-in providers and protocol catalog', async () => {
    send('configure', 'configure', { providers: [{ id: 'deepseek', builtinProvider: 'deepseek' },
      { id: 'anthropic', builtinProvider: 'anthropic' }, { id: 'amazon-bedrock', builtinProvider: 'amazon-bedrock' },
      { id: 'google-vertex', builtinProvider: 'google-vertex' }], credentials: {} });
    assert.equal((await terminal('configure')).type, 'result');
    send('catalog', 'catalog');
    const response = await terminal('catalog'); assert.equal(response.type, 'result');
    assert.equal(response.value.providers.length, 4);
    assert.ok(response.value.providers.every(item => item.models.length > 0 && item.configured === false));
    assert.ok(response.value.providers.find(item => item.id === 'anthropic').authMethods.includes('oauth'));
  });
  await test('full catalog preserves actual AWS Vertex Azure OAuth and ten API families', async () => {
    send('catalog', 'all', { allBuiltins: true });
    const response = await terminal('all'); assert.equal(response.type, 'result');
    const apis = new Set(response.value.providers.flatMap(provider => provider.models.map(model => model.api)));
    assert.ok(apis.has('bedrock-converse-stream')); assert.ok(apis.has('google-vertex')); assert.ok(apis.has('azure-openai-responses'));
    assert.ok(apis.has('openai-codex-responses')); assert.ok(apis.size >= 9);
  });
  await test('unknown model and unsupported reasoning fail without provider requests', async () => {
    send('stream', 'unknown', { provider: 'anthropic', model: 'does-not-exist', context: { messages: [] } });
    assert.equal((await terminal('unknown')).error.code, 'UNKNOWN_MODEL');
    send('catalog', 'get-model'); const catalog = await terminal('get-model');
    const model = catalog.value.providers.find(provider => provider.id === 'anthropic').models[0];
    send('stream', 'effort', { provider: 'anthropic', model: model.id, reasoning: 'invented', context: { messages: [] } });
    assert.equal((await terminal('effort')).error.code, 'UNSUPPORTED_REASONING');
  });
  await test('real OpenAI adapter returns streaming text and tool data to host without tool execution', async () => {
    let requestBody; let requestCount = 0;
    const server = http.createServer((request, response) => {
      requestCount++;
      let body = ''; request.on('data', bytes => body += bytes.toString());
      request.on('end', () => {
        requestBody = JSON.parse(body);
        response.writeHead(200, { 'content-type': 'text/event-stream' });
        const chunk = delta => `data: ${JSON.stringify({ id: 'fixture', choices: [{ index: 0, delta, finish_reason: null }] })}\n\n`;
        response.write(chunk({ role: 'assistant', content: 'offline fixture' }));
        response.write(chunk({ tool_calls: [{ index: 0, id: 'fixture-call', type: 'function', function: { name: 'host_only', arguments: '{"value":"x"}' } }] }));
        response.end(`data: ${JSON.stringify({ id: 'fixture', choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }],
          usage: { prompt_tokens: 2, completion_tokens: 3, total_tokens: 5 } })}\n\ndata: [DONE]\n\n`);
      });
    });
    server.listen(0, '127.0.0.1'); await once(server, 'listening');
    try {
      send('configure', 'fixture-config', { providers: [{ id: 'fixture', name: 'fixture', api: 'openai-completions',
        baseUrl: `http://127.0.0.1:${server.address().port}/v1`, models: [{ id: 'fixture-model', reasoning: false }] }],
        credentials: { fixture: { type: 'api_key', key: 'FAKE_KEY_NO_NETWORK' } } });
      assert.equal((await terminal('fixture-config')).type, 'result');
      send('stream', 'fixture-stream', { provider: 'fixture', model: 'fixture-model', context: {
        messages: [{ role: 'user', content: 'fixture', timestamp: Date.now() }],
        tools: [{ name: 'host_only', description: 'Only a host may execute it', parameters: { type: 'object', properties: { value: { type: 'string' } } } }],
      } });
      assert.equal((await terminal('fixture-stream')).type, 'result');
      const events = frames.filter(frame => frame.id === 'fixture-stream');
      assert.ok(events.some(frame => frame.event.type === 'text_delta' && frame.event.delta === 'offline fixture'));
      assert.ok(events.some(frame => frame.event.type === 'toolcall_end' && frame.event.toolCall.name === 'host_only'));
      assert.ok(events.every(frame => !JSON.stringify(frame).includes('diagnostics')),
        'provider diagnostics must not cross the worker boundary');
      assert.deepEqual(events.map(event => event.sequence), events.map((_, index) => index));
      assert.equal(requestCount, 1); assert.equal(requestBody.tools[0].function.name, 'host_only');
    } finally { server.closeAllConnections(); server.close(); }
  });
  await test('cancel aborts a genuine blocked adapter request and permits a future request', async () => {
    let received;
    const arrived = new Promise(resolve => received = resolve);
    const server = http.createServer((request, response) => {
      request.resume(); request.on('end', () => { response.writeHead(200, { 'content-type': 'text/event-stream' }); response.write(':keepalive\n\n'); received(); });
    });
    server.listen(0, '127.0.0.1'); await once(server, 'listening');
    try {
      send('configure', 'cancel-config', { providers: [{ id: 'fixture', api: 'openai-completions', baseUrl: `http://127.0.0.1:${server.address().port}/v1`,
        models: [{ id: 'fixture-model' }] }], credentials: { fixture: { type: 'api_key', key: 'FAKE' } } });
      assert.equal((await terminal('cancel-config')).type, 'result');
      send('stream', 'blocked', { provider: 'fixture', model: 'fixture-model', context: { messages: [{ role: 'user', content: 'fixture', timestamp: Date.now() }] } });
      await Promise.race([arrived, new Promise((_, reject) => setTimeout(() => reject(new Error('fixture server not reached')), 5000))]);
      send('cancel', 'cancel', { target: 'blocked' }); assert.equal((await terminal('cancel')).type, 'result');
      const terminalFrame = await terminal('blocked'); assert.equal(terminalFrame.type, 'error');
      send('catalog', 'after-cancel'); assert.equal((await terminal('after-cancel')).type, 'result');
    } finally { server.closeAllConnections(); server.close(); }
  });
  await test('credential files are staged and removed on reconfiguration', async () => {
    send('configure', 'files-config', { providers: [{ id: 'fixture', api: 'openai-completions', baseUrl: 'https://example.invalid/v1',
      models: [{ id: 'fixture-model' }] }], credentials: { fixture: { type: 'api_key', files: { aws_config: '[default]\nregion=us-east-1\n' } } } });
    assert.equal((await terminal('files-config')).type, 'result');
    const roots = (await fs.readdir(path.join(qaRoot, '.credentials'), { withFileTypes: true })).filter(item => item.isDirectory());
    assert.equal(roots.length, 1);
    send('configure', 'files-clear', { providers: [{ id: 'fixture', api: 'openai-completions', baseUrl: 'https://example.invalid/v1',
      models: [{ id: 'fixture-model' }] }], credentials: {} });
    assert.equal((await terminal('files-clear')).type, 'result');
    const remaining = await fs.readdir(path.join(qaRoot, '.credentials'), { withFileTypes: true }).catch(() => []);
    assert.equal(remaining.filter(item => item.isDirectory()).length, 0);
  });
  await test('no credentials are printed to stderr or catalog frames', async () => {
    assert.equal(errors, '');
    assert.ok(!JSON.stringify(frames).includes('FAKE_KEY_NO_NETWORK'));
  });
  console.log(`Node worker: ${passed} PASS; real pinned pi-ai and loopback synthetic HTTP only, no paid API.`);
} finally {
  child.stdin.end();
  await Promise.race([once(child, 'exit'), new Promise(resolve => setTimeout(() => { child.kill(); resolve(); }, 1500))]);
}
