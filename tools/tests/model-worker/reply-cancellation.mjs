import fs from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const [worker] = process.argv.slice(2);
const source = await fs.readFile(worker, 'utf8');
const start = source.indexOf('function waitReply(');
const end = source.indexOf('\nasync function persistCredential', start);
assert(start > 0 && end > start);
const context = vm.createContext({ setTimeout, clearTimeout });
const waitReply = vm.runInContext(source.slice(start, end) + '\nwaitReply;', context);
const unhandled = [];
const record = failure => unhandled.push(failure);
process.on('unhandledRejection', record);
try {
  const replies = new Map();
  const cancellation = new AbortController();
  const waiting = waitReply(replies, 'blocked-send', cancellation.signal, 1000);
  cancellation.abort();
  // No await is attached until a pending synthetic send/backpressure resolves.
  await new Promise(resolve => setTimeout(resolve, 25));
  assert.equal(unhandled.length, 0);
  await assert.rejects(waiting, failure => failure.code === 'CANCELLED');
  assert.equal(replies.size, 0);
  console.log('PASS cancellation during blocked send has no unhandled rejection and preserves CANCELLED for original await');
  const already = new AbortController(); already.abort();
  const early = waitReply(replies, 'already-cancelled', already.signal, 1000);
  await new Promise(resolve => setTimeout(resolve, 10));
  await assert.rejects(early, failure => failure.code === 'CANCELLED');
  assert.equal(unhandled.length, 0); assert.equal(replies.size, 0);
  console.log('PASS pre-cancelled prompt is observed immediately and removed from active map');
  const success = waitReply(replies, 'success', undefined, 1000);
  replies.get('success').resolve('OFFLINE_REPLY');
  assert.equal(await success, 'OFFLINE_REPLY'); assert.equal(replies.size, 0);
  console.log('PASS successful reply still reaches original awaiting caller exactly once');
} finally { process.off('unhandledRejection', record); }
