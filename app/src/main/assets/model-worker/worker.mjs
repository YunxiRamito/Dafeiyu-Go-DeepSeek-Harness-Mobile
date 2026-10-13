import { once } from 'node:events';
import fs from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const PROTOCOL = 'dafeiyu-go.model-worker.v1';
const VERSION = '0.87.1';
const APIS = ['openai-completions', 'mistral-conversations', 'openai-responses', 'azure-openai-responses',
  'openai-codex-responses', 'anthropic-messages', 'bedrock-converse-stream', 'google-generative-ai', 'google-vertex', 'pi-messages'];
const MAX_FRAME = 4 * 1024 * 1024;
const requests = new Map();
const prompts = new Map();
const receipts = new Map();
const credentials = new Map();
const credentialChains = new Map();
const temporaryCredentials = new Set();
const temporaryCredentialRoots = new Set();
let models;
let initialized = false;
let shuttingDown = false;
let outputChain = Promise.resolve();
let pi;
let builtins;
let piDirectory;
let configuring = false;

function requireValue(condition, code = 'INVALID_REQUEST') { if (!condition) throw Object.assign(new Error(code), { code }); }
function identifier(value) { return typeof value === 'string' && /^[a-z0-9][a-z0-9.-]{0,79}$/.test(value); }
function safeError(error) {
  return { code: ['INVALID_REQUEST', 'NOT_CONFIGURED', 'UNKNOWN_MODEL', 'UNSUPPORTED_REASONING', 'BUSY', 'CANCELLED',
    'CREDENTIAL_STORAGE', 'AUTH_FAILED', 'INVALID_COMPONENT', 'OUTPUT_LIMIT'].includes(error?.code) ? error.code : 'PROVIDER_FAILED',
    message: error?.name === 'AbortError' ? 'Model request cancelled' : 'Model provider request failed; check provider settings and credentials' };
}
function send(frame) {
  const encoded = JSON.stringify({ protocol: PROTOCOL, ...frame }) + '\n';
  requireValue(Buffer.byteLength(encoded) <= MAX_FRAME, 'OUTPUT_LIMIT');
  const next = outputChain.then(async () => { if (!process.stdout.write(encoded)) await once(process.stdout, 'drain'); });
  outputChain = next.catch(() => {});
  return next;
}
function waitReply(map, id, signal, timeout = 120000) {
  const waiting = new Promise((resolve, reject) => {
    const timer = setTimeout(() => finish(false, Object.assign(new Error('reply timeout'), { code: 'CREDENTIAL_STORAGE' })), timeout);
    const abort = () => finish(false, Object.assign(new Error('cancelled'), { code: 'CANCELLED' }));
    function finish(ok, value) {
      clearTimeout(timer); signal?.removeEventListener('abort', abort); map.delete(id);
      if (ok) resolve(value); else reject(value);
    }
    map.set(id, { resolve: value => finish(true, value), reject: value => finish(false, value) });
    if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
  });
  // Cancellation may arrive while send() is waiting for stdout backpressure.
  // Observe early rejection immediately, while returning the original promise
  // so the awaiting caller still receives its actual cancellation/failure.
  void waiting.catch(() => {});
  return waiting;
}
async function persistCredential(provider, value, signal) {
  const receiptId = randomUUID();
  const waiting = waitReply(receipts, receiptId, signal, 15000);
  const portable = value ? structuredClone(value) : null;
  if (portable?.files && portable.env) for (const key of ['GOOGLE_APPLICATION_CREDENTIALS', 'AWS_SHARED_CREDENTIALS_FILE', 'AWS_CONFIG_FILE']) {
    if (temporaryCredentials.has(portable.env[key])) delete portable.env[key];
  }
  try {
    await send({ type: 'credential_write', receiptId, provider, credential: portable });
    const receipt = await waiting;
    requireValue(receipt === true, 'CREDENTIAL_STORAGE');
  } finally { receipts.get(receiptId)?.reject(Object.assign(new Error('receipt released'), { code: 'CREDENTIAL_STORAGE' })); }
}
const credentialStore = {
  async read(id, options) { options?.signal?.throwIfAborted(); return credentials.get(id); },
  async list(options) { options?.signal?.throwIfAborted(); return [...credentials].map(([providerId, credential]) => ({ providerId, type: credential.type })); },
  async modify(id, fn, options) {
    const before = credentialChains.get(id) ?? Promise.resolve();
    const operation = before.catch(() => {}).then(async () => {
      options?.signal?.throwIfAborted();
      const current = credentials.get(id);
      const next = await fn(current);
      options?.signal?.throwIfAborted();
      if (next !== undefined) { await persistCredential(id, next, options?.signal); credentials.set(id, next); }
      return next ?? current;
    });
    const tail = operation.catch(() => {}); credentialChains.set(id, tail);
    void tail.finally(() => { if (credentialChains.get(id) === tail) credentialChains.delete(id); });
    return operation;
  },
  async delete(id, options) {
    return this.modify(id, async () => { await persistCredential(id, undefined, options?.signal); credentials.delete(id); }, options);
  },
};

async function importPi() {
  const modules = process.argv[2] ?? path.dirname(new URL(import.meta.url).pathname);
  piDirectory = path.join(modules, 'node_modules/@earendil-works/pi-ai');
  const manifest = JSON.parse(await fs.readFile(path.join(piDirectory, 'package.json'), 'utf8'));
  requireValue(manifest.name === '@earendil-works/pi-ai' && manifest.version === VERSION, 'INVALID_COMPONENT');
  pi = await import(pathToFileURL(path.join(piDirectory, 'dist/index.js')).href);
  builtins = await import(pathToFileURL(path.join(piDirectory, 'dist/providers/all.js')).href);
}
function validateCredential(value) {
  requireValue(value && typeof value === 'object' && ['api_key', 'oauth'].includes(value.type));
  if (value.type === 'oauth') requireValue(typeof value.access === 'string' && typeof value.refresh === 'string' && Number.isFinite(value.expires));
  if (value.key !== undefined) requireValue(typeof value.key === 'string' && value.key.length <= 65536 && !/[\r\n\0]/.test(value.key));
  if (value.env !== undefined) requireValue(value.env && typeof value.env === 'object' && Object.entries(value.env).every(([key, value]) =>
    /^[A-Z][A-Z0-9_]{0,79}$/.test(key) && typeof value === 'string' && value.length <= 65536 && !/[\0]/.test(value)));
  return structuredClone(value);
}
async function materializeCredential(provider, raw, credentialsRoot = path.join(process.cwd(), '.credentials'), createdFiles = new Set()) {
  const credential = validateCredential(raw);
  if (!credential.files) return credential;
  const directory = path.join(credentialsRoot, provider);
  await fs.mkdir(directory, { recursive: true, mode: 0o700 });
  credential.env = { ...(credential.env ?? {}) };
  for (const [type, contents] of Object.entries(credential.files)) {
    const variable = { google_adc: 'GOOGLE_APPLICATION_CREDENTIALS', aws_credentials: 'AWS_SHARED_CREDENTIALS_FILE', aws_config: 'AWS_CONFIG_FILE' }[type];
    requireValue(variable && typeof contents === 'string' && Buffer.byteLength(contents) <= 512 * 1024);
    const file = path.join(directory, `${type}.secret`);
    await fs.writeFile(file, contents, { mode: 0o600 }); createdFiles.add(file); credential.env[variable] = file;
  }
  return credential;
}

function safeContent(content) {
  if (!content || typeof content !== 'object') return undefined;
  if (content.type === 'text' && typeof content.text === 'string') {
    return { type: 'text', text: content.text, ...(typeof content.textSignature === 'string' ? { textSignature: content.textSignature } : {}) };
  }
  if (content.type === 'thinking' && typeof content.thinking === 'string') {
    return { type: 'thinking', thinking: content.thinking,
      ...(typeof content.thinkingSignature === 'string' ? { thinkingSignature: content.thinkingSignature } : {}),
      ...(content.redacted === true ? { redacted: true } : {}) };
  }
  if (content.type === 'toolCall' && typeof content.id === 'string' && typeof content.name === 'string') {
    return { type: 'toolCall', id: content.id, name: content.name,
      arguments: content.arguments && typeof content.arguments === 'object' ? content.arguments : {},
      ...(typeof content.thoughtSignature === 'string' ? { thoughtSignature: content.thoughtSignature } : {}),
      ...(typeof content.namespace === 'string' ? { namespace: content.namespace } : {}) };
  }
  return undefined;
}

function safeUsage(usage) {
  if (!usage || typeof usage !== 'object') return undefined;
  const number = key => Number.isFinite(usage[key]) ? usage[key] : 0;
  const cost = usage.cost && typeof usage.cost === 'object' ? {
    input: numberFrom(usage.cost.input), output: numberFrom(usage.cost.output),
    cacheRead: numberFrom(usage.cost.cacheRead), cacheWrite: numberFrom(usage.cost.cacheWrite),
    total: numberFrom(usage.cost.total),
  } : undefined;
  return { input: number('input'), output: number('output'), cacheRead: number('cacheRead'),
    cacheWrite: number('cacheWrite'), ...(Number.isFinite(usage.cacheWrite1h) ? { cacheWrite1h: usage.cacheWrite1h } : {}),
    ...(Number.isFinite(usage.reasoning) ? { reasoning: usage.reasoning } : {}), totalTokens: number('totalTokens'),
    ...(cost ? { cost } : {}) };
}

function numberFrom(value) { return Number.isFinite(value) ? value : 0; }

// pi-ai AssistantMessage contains provider diagnostics and raw error payloads. Only
// expose the stable transcript fields needed for history/tool routing.
function safeAssistantMessage(message) {
  if (!message || typeof message !== 'object') return undefined;
  const content = Array.isArray(message.content) ? message.content.map(safeContent).filter(Boolean) : [];
  return { role: 'assistant', content,
    ...(typeof message.api === 'string' ? { api: message.api } : {}),
    ...(typeof message.provider === 'string' ? { provider: message.provider } : {}),
    ...(typeof message.model === 'string' ? { model: message.model } : {}),
    ...(typeof message.responseModel === 'string' ? { responseModel: message.responseModel } : {}),
    ...(typeof message.responseId === 'string' ? { responseId: message.responseId } : {}),
    ...(typeof message.providerThinkingLevel === 'string' ? { providerThinkingLevel: message.providerThinkingLevel } : {}),
    ...(safeUsage(message.usage) ? { usage: safeUsage(message.usage) } : {}),
    ...(typeof message.stopReason === 'string' ? { stopReason: message.stopReason } : {}),
    ...(typeof message.rawStopReason === 'string' ? { rawStopReason: message.rawStopReason } : {}),
    ...(Number.isFinite(message.timestamp) ? { timestamp: message.timestamp } : {}) };
}
async function configure(message) {
  requireValue(requests.size === 0 && !configuring, 'BUSY');
  configuring = true;
  const stagingRoot = path.join(process.cwd(), '.credentials', `staging-${randomUUID()}`);
  const stagedFiles = new Set();
  try {
  requireValue(Array.isArray(message.providers) && message.providers.length <= 128);
  const candidateCredentials = new Map();
  for (const [provider, credential] of Object.entries(message.credentials ?? {})) {
    requireValue(identifier(provider)); candidateCredentials.set(provider,
      await materializeCredential(provider, credential, stagingRoot, stagedFiles));
  }
  const candidate = builtins.builtinModels({ credentials: credentialStore, authContext: {
    async env() { return undefined; },
    async fileExists(file) { return temporaryCredentials.has(file) && await fs.stat(file).then(item => item.isFile()).catch(() => false); },
  } });
  const native = new Map(candidate.getProviders().map(provider => [provider.id, provider]));
  candidate.clearProviders();
  const ids = new Set();
  for (const profile of message.providers) {
    requireValue(identifier(profile.id) && !ids.has(profile.id)); ids.add(profile.id);
    if (profile.enabled === false) continue;
    const original = native.get(profile.builtinProvider ?? profile.id);
    if (original && !profile.api && !(profile.models?.length)) {
      candidate.setProvider({ ...original, id: profile.id, name: profile.name ?? original.name,
        getModels: () => original.getModels().map(model => ({ ...model, provider: profile.id,
          ...(profile.baseUrl ? { baseUrl: profile.baseUrl } : {}) })) });
    } else {
      requireValue(APIS.includes(profile.api) && Array.isArray(profile.models) && profile.models.length > 0);
      const streams = pi.lazyApi(() => import(pathToFileURL(path.join(piDirectory, `dist/api/${profile.api}.js`)).href));
      candidate.setProvider(pi.createProvider({ id: profile.id, name: profile.name ?? profile.id, baseUrl: profile.baseUrl,
        auth: original?.auth ?? { apiKey: { async resolve({ credential }) {
          if (!credential || credential.type !== 'api_key') return undefined;
          return { auth: { ...(credential.key !== undefined ? { apiKey: credential.key } : {}) }, env: credential.env, source: 'stored credential' };
        } } },
        models: profile.models.map(model => ({ cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
          contextWindow: 32768, maxTokens: 8192, reasoning: false, input: ['text'], ...model,
          id: model.id, name: model.name ?? model.id, provider: profile.id, api: profile.api, baseUrl: profile.baseUrl })),
        api: streams,
      }));
    }
  }
  const oldFiles = [...temporaryCredentials];
  const oldRoots = [...temporaryCredentialRoots];
  credentials.clear(); for (const [key, value] of candidateCredentials) credentials.set(key, value);
  models = candidate; initialized = true;
  temporaryCredentials.clear(); stagedFiles.forEach(file => temporaryCredentials.add(file));
  temporaryCredentialRoots.clear(); temporaryCredentialRoots.add(stagingRoot);
  await Promise.allSettled(oldFiles.map(file => fs.rm(file, { force: true })));
  await Promise.allSettled(oldRoots.map(directory => fs.rm(directory, { recursive: true, force: true })));
  await send({ type: 'result', id: message.id, value: { configured: true } });
  } catch (failure) {
    await Promise.allSettled(stagedFiles.values().map(file => fs.rm(file, { force: true })));
    await fs.rm(stagingRoot, { recursive: true, force: true }).catch(() => {});
    throw failure;
  } finally { configuring = false; }
}
async function catalog(message, signal) {
  const providers = [];
  const all = message.allBuiltins === true ? builtins.builtinModels({ authContext: { async env() {}, async fileExists() { return false; } } }) : models;
  for (const provider of all.getProviders()) {
    const auth = await all.checkAuth(provider.id, { signal });
    providers.push({ id: provider.id, name: provider.name, configured: Boolean(auth),
      authMethods: ['api_key', 'oauth'].filter(type => type === 'api_key' ? provider.auth?.apiKey : provider.auth?.oauth),
      models: all.getModels(provider.id).map(model => ({ ...model, reasoningOptions: pi.getSupportedThinkingLevels(model) })) });
  }
  await send({ type: 'result', id: message.id, value: { providers, apis: APIS, version: VERSION } });
}
async function stream(message, signal) {
  const model = models.getModel(message.provider, message.model);
  requireValue(model, 'UNKNOWN_MODEL');
  const levels = pi.getSupportedThinkingLevels(model);
  requireValue(message.reasoning == null || levels.includes(message.reasoning), 'UNSUPPORTED_REASONING');
  requireValue(message.context && Array.isArray(message.context.messages) && message.context.messages.length <= 4096);
  const options = { signal, timeoutMs: 660000, maxRetries: 0, transport: 'sse',
    ...(message.reasoning && message.reasoning !== 'off' ? { reasoning: message.reasoning } : {}),
    ...(message.maxTokens ? { maxTokens: message.maxTokens } : {}) };
  const events = models.streamSimple(model, message.context, options);
  let sequence = 0;
  for await (const event of events) {
    signal.throwIfAborted();
    if (event.type === 'error') {
      await send({ type: 'event', id: message.id, sequence: sequence++, event: { type: 'error', reason: event.reason,
        error: { ...safeAssistantMessage(event.error), errorMessage: 'Provider request failed; check authentication, endpoint and provider availability' } } });
    } else if (event.type === 'done') {
      await send({ type: 'event', id: message.id, sequence: sequence++, event: { type: 'done', reason: event.reason,
        message: safeAssistantMessage(event.message) } });
    } else {
      const { partial, ...compact } = event;
      if (event.type === 'start') compact.partial = { provider: partial.provider, model: partial.model, api: partial.api };
      if (event.type.startsWith('toolcall_')) compact.toolCall = safeContent(partial?.content?.[event.contentIndex]);
      await send({ type: 'event', id: message.id, sequence: sequence++, event: compact });
    }
  }
  await send({ type: 'result', id: message.id, value: { finished: true } });
}
async function login(message, signal) {
  const credential = await models.login(message.provider, message.authType ?? 'oauth', { signal,
    async prompt(prompt) {
      const promptId = randomUUID();
      const waiting = waitReply(prompts, promptId, prompt.signal ?? signal);
      const { signal: _, ...display } = prompt;
      try {
        await send({ type: 'auth_prompt', id: message.id, promptId, prompt: display });
        return await waiting;
      } finally {
        prompts.get(promptId)?.reject(Object.assign(new Error('prompt released'), { code: 'CANCELLED' }));
        await send({ type: 'auth_prompt_cancel', id: message.id, promptId });
      }
    },
    notify(event) { void send({ type: 'auth_event', id: message.id, event }); },
  });
  await send({ type: 'result', id: message.id, value: { authenticated: true, credentialType: credential.type } });
}
async function dispatch(message) {
  requireValue(message.protocol === PROTOCOL && typeof message.id === 'string' && message.id.length <= 120);
  if (message.type === 'cancel') { requests.get(message.target)?.abort(); return send({ type: 'result', id: message.id, value: { requested: true } }); }
  if (message.type === 'auth_reply') { prompts.get(message.promptId)?.resolve(message.value); return; }
  if (message.type === 'credential_ack') { receipts.get(message.receiptId)?.resolve(message.ok === true); return; }
  if (message.type === 'shutdown') {
    shuttingDown = true; for (const controller of requests.values()) controller.abort();
    await send({ type: 'result', id: message.id, value: { stopped: true } }); return;
  }
  requireValue(!shuttingDown && !requests.has(message.id) && requests.size < 4, 'BUSY');
  if (message.type === 'configure') return configure(message);
  requireValue(initialized && !configuring, 'NOT_CONFIGURED');
  const controller = new AbortController(); requests.set(message.id, controller);
  try {
    if (message.type === 'catalog') await catalog(message, controller.signal);
    else if (message.type === 'stream') await stream(message, controller.signal);
    else if (message.type === 'login') await login(message, controller.signal);
    else if (message.type === 'logout') {
      await models.logout(message.provider, { signal: controller.signal });
      await send({ type: 'result', id: message.id, value: { loggedOut: true } });
    } else throw Object.assign(new Error('unknown command'), { code: 'INVALID_REQUEST' });
  } finally { requests.delete(message.id); }
}
async function* boundedLines(input) {
  let parts = []; let size = 0;
  for await (const chunk of input) {
    let offset = 0;
    while (offset < chunk.length) {
      const newline = chunk.indexOf(10, offset);
      const end = newline < 0 ? chunk.length : newline;
      const part = chunk.subarray(offset, end);
      size += part.length; requireValue(size <= MAX_FRAME, 'INPUT_LIMIT');
      parts.push(part);
      if (newline >= 0) { yield Buffer.concat(parts, size).toString('utf8'); parts = []; size = 0; }
      offset = end + (newline >= 0 ? 1 : 0);
    }
  }
  requireValue(size === 0, 'INVALID_JSON');
}
async function main() {
  await importPi();
  await send({ type: 'ready', version: VERSION, apis: APIS });
  for await (const line of boundedLines(process.stdin)) {
    let message;
    try { message = JSON.parse(line); }
    catch { await send({ type: 'fatal', error: { code: 'INVALID_JSON' } }); break; }
    void dispatch(message).catch(error => send({ type: 'error', id: message.id, error: safeError(error) }).catch(() => process.exit(2)));
    if (shuttingDown) break;
  }
}
try { await main(); }
catch { await send({ type: 'fatal', error: { code: 'INVALID_COMPONENT', message: 'Model runtime could not load its pinned dependencies' } }).catch(() => {}); }
finally {
  for (const controller of requests.values()) controller.abort();
  await Promise.allSettled([...temporaryCredentials].map(file => fs.rm(file, { force: true })));
  await Promise.allSettled([...temporaryCredentialRoots].map(directory => fs.rm(directory, { recursive: true, force: true })));
  await outputChain;
  process.exit(0);
}
