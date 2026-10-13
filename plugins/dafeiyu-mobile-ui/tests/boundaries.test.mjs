import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { Script, createContext } from 'node:vm';
import test from 'node:test';
import { createDafeiyuMobileContext } from '../client/context.js';
import { createDafeiyuMobileUiPlugin } from '../client/index.js';
import { createDafeiyuMobileFooter } from '../client/host-footer.js';
import { createDafeiyuStartup } from '../client/startup.js';

const packageRoot = fileURLToPath(new URL('../', import.meta.url));
const cssText = 'html[data-dafeiyu-surface="mobile"] { --boundary-fixture: 1; }';
const validHost = (overrides = {}) => ({
  version: 1, platform: 'android', surface: 'mobile', mode: 'local',
  theme: 'system', material: true, motion: true, ...overrides,
});

// A small browser-API fixture, not an implementation of the activation policy.
// The tests execute the real ESM exports and the actual built ModuleLoader bundle.
// Rendering, Cordis injection and the production browser remain separate checks.
class Events {
  listeners = new Map();
  registrations = new Map();
  addEventListener(type, callback, options = false) {
    const capture = typeof options === 'boolean' ? options : Boolean(options.capture);
    if (!this.registrations.has(type)) this.registrations.set(type, []);
    const entries = this.registrations.get(type);
    if (entries.some(entry => entry.callback === callback && entry.capture === capture)) return;
    entries.push({ callback, capture, once: typeof options === 'object' && Boolean(options.once) });
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(callback);
  }
  removeEventListener(type, callback, options = false) {
    const capture = typeof options === 'boolean' ? options : Boolean(options.capture);
    const entries = (this.registrations.get(type) || []).filter(entry => entry.callback !== callback || entry.capture !== capture);
    this.registrations.set(type, entries);
    if (!entries.some(entry => entry.callback === callback)) this.listeners.get(type)?.delete(callback);
  }
  dispatchEvent(event) {
    event.target ??= this;
    event.preventDefault ??= () => { event.defaultPrevented = true; };
    event.stopPropagation ??= () => { event.cancelBubble = true; };
    event.stopImmediatePropagation ??= () => { event.cancelBubble = true; event.immediateStopped = true; };
    const path = [this];
    for (let ancestor = this.eventParent; ancestor; ancestor = ancestor.eventParent) path.push(ancestor);
    const invoke = (target, capture, phase) => {
      event.currentTarget = target;
      event.eventPhase = phase;
      for (const entry of [...(target.registrations.get(event.type) || [])]) {
        if (entry.capture !== capture || event.immediateStopped) continue;
        if (entry.once) target.removeEventListener(event.type, entry.callback, capture);
        entry.callback.call(target, event);
      }
    };
    for (const ancestor of path.slice(1).reverse()) {
      invoke(ancestor, true, 1);
      if (event.cancelBubble) break;
    }
    if (!event.cancelBubble) {
      invoke(this, true, 2);
      invoke(this, false, 2);
    }
    if (event.bubbles && !event.cancelBubble) for (const ancestor of path.slice(1)) {
      invoke(ancestor, false, 3);
      if (event.cancelBubble) break;
    }
    event.currentTarget = null;
    event.eventPhase = 0;
    return !event.defaultPrevented;
  }
  listenerCount() { return [...this.registrations.values()].reduce((sum, rows) => sum + rows.length, 0); }
}

class BrowserEvent {
  constructor(type, options = {}) {
    Object.assign(this, { type, bubbles: false, cancelable: false, defaultPrevented: false, cancelBubble: false }, options);
  }
  preventDefault() { if (this.cancelable) this.defaultPrevented = true; }
  stopPropagation() { this.cancelBubble = true; }
  stopImmediatePropagation() { this.cancelBubble = true; this.immediateStopped = true; }
}

function selectorParts(selector) {
  // Whitespace inside an attribute selector is not a descendant combinator.
  return selector.match(/(?:\[[^\]]+\]|[^\s\[>])+|>/g) || [];
}

function matchesSimple(element, selector) {
  const tag = selector.match(/^[a-z][\w-]*/i)?.[0];
  if (tag && element.tagName !== tag.toUpperCase()) return false;
  const outsideAttributes = selector.replace(/\[[^\]]+\]/g, '');
  for (const token of outsideAttributes.match(/\.[\w-]+|#[\w-]+/g) || []) {
    if (token[0] === '.' && !element.classList.contains(token.slice(1))) return false;
    if (token[0] === '#' && element.id !== token.slice(1)) return false;
  }
  for (const attribute of selector.matchAll(/\[([^\]]+)\]/g)) {
    const match = attribute[1].match(/^([\w:-]+)(?:\s*(\*=|\^=|=)\s*["']([^"']*)["']\s*(i)?)?$/);
    assert.ok(match, `Unsupported fixture attribute selector: ${attribute[0]}`);
    const [, name, operator, expected, insensitive] = match;
    let actual = element.getAttribute(name);
    if (actual === null) return false;
    if (!operator) continue;
    const value = insensitive ? expected.toLowerCase() : expected;
    if (insensitive) actual = actual.toLowerCase();
    const accepted = operator === '=' ? actual === value :
      operator === '^=' ? actual.startsWith(value) : actual.includes(value);
    if (!accepted) return false;
  }
  return true;
}

function matches(element, selector) {
  return selector.split(',').some((group) => {
    const parts = selectorParts(group.trim());
    let current = element;
    if (!parts.length || !matchesSimple(current, parts.pop())) return false;
    while (parts.length) {
      const part = parts.pop();
      current = current.parentElement;
      if (part === '>') {
        const parentSelector = parts.pop();
        if (!parentSelector || !current || !matchesSimple(current, parentSelector)) return false;
      } else while (current && !matchesSimple(current, part)) current = current.parentElement;
      if (!current) return false;
    }
    return true;
  });
}

class TextNode extends Events {
  nodeType = 3;
  parentElement = null;
  constructor(value, document) { super(); this.data = String(value); this.ownerDocument = document; }
  get parentNode() { return this.parentElement; }
  get eventParent() { return this.parentElement; }
  get isConnected() { return Boolean(this.parentElement?.isConnected); }
  get textContent() { return this.data; }
  set textContent(value) {
    this.data = value === null || value === undefined ? '' : String(value);
    this.ownerDocument.recordMutation?.({ type: 'characterData', target: this });
  }
  contains(node) { return this === node; }
  cloneNode() { return this.ownerDocument.createTextNode(this.data); }
  remove() {
    const previous = this.parentElement;
    if (previous) previous.childNodes = previous.childNodes.filter(child => child !== this);
    this.parentElement = null;
    if (previous) this.ownerDocument.recordMutation?.({ type: 'childList', target: previous });
  }
}

class Element extends Events {
  nodeType = 1;
  attributesMap = new Map();
  childNodes = [];
  parentElement = null;
  innerHTML = '';
  rectangle = { x: 0, y: 0, width: 0, height: 0 };
  constructor(tag, document) {
    super();
    this.tagName = tag.toUpperCase();
    this.ownerDocument = document;
    this.classList = {
      contains: (name) => this.className.split(/\s+/).includes(name),
      add: (...names) => { this.className = [...new Set([...this.className.split(/\s+/).filter(Boolean), ...names])].join(' '); },
      remove: (...names) => { this.className = this.className.split(/\s+/).filter((name) => !names.includes(name)).join(' '); },
      toggle: (name, force) => {
        const add = force ?? !this.classList.contains(name);
        if (add) this.classList.add(name); else this.classList.remove(name);
        return add;
      },
    };
    const properties = new Map();
    this.style = { cssText: '', setProperty(name, value) { properties.set(name, String(value)); },
      getPropertyValue(name) { return properties.get(name) || ''; },
      getPropertyPriority() { return ''; },
      removeProperty(name) { const previous = properties.get(name) || ''; properties.delete(name); return previous; } };
  }
  get parentNode() { return this.parentElement; }
  get children() { return this.childNodes.filter(node => node.nodeType === 1); }
  get eventParent() { return this.parentElement || (this === this.ownerDocument.documentElement ? this.ownerDocument : null); }
  get firstElementChild() { return this.children[0] || null; }
  get previousElementSibling() {
    const siblings = this.parentElement?.children || [];
    return siblings[siblings.indexOf(this) - 1] || null;
  }
  get nextElementSibling() {
    const siblings = this.parentElement?.children || [];
    return siblings[siblings.indexOf(this) + 1] || null;
  }
  get isConnected() { return this === this.ownerDocument.documentElement || Boolean(this.parentElement?.isConnected); }
  get attributes() { return [...this.attributesMap].map(([name, value]) => ({ name, value })); }
  get textContent() { return this.childNodes.map(node => node.textContent).join(''); }
  set textContent(value) {
    const hadChildren = this.childNodes.length > 0;
    for (const child of this.childNodes) child.parentElement = null;
    this.childNodes = [];
    const text = value === null || value === undefined ? '' : String(value);
    if (text) {
      const node = this.ownerDocument.createTextNode(text);
      node.parentElement = this;
      this.childNodes.push(node);
    }
    // Element.textContent replaces its text nodes even when nonempty data is
    // unchanged. Modeling that childList write exposes observer feedback loops.
    if (text || hadChildren) this.ownerDocument.recordMutation?.({ type: 'childList', target: this });
  }
  get className() { return this.getAttribute('class') || ''; }
  set className(value) { this.setAttribute('class', value); }
  get id() { return this.getAttribute('id') || ''; }
  set id(value) { this.setAttribute('id', value); }
  get type() { return this.getAttribute('type') || ''; }
  set type(value) { this.setAttribute('type', value); }
  get disabled() { return this.hasAttribute('disabled'); }
  set disabled(value) { if (value) this.setAttribute('disabled', ''); else this.removeAttribute('disabled'); }
  get hidden() { return this.hasAttribute('hidden'); }
  set hidden(value) { if (value) this.setAttribute('hidden', ''); else this.removeAttribute('hidden'); }
  get outerHTML() {
    const attributes = [...this.attributesMap].map(([name, value]) => ` ${name}="${value}"`).join('');
    return `<${this.tagName.toLowerCase()}${attributes}>${this.innerHTML}${this.childNodes.map(child => child.nodeType === 3 ? child.textContent : child.outerHTML).join('')}</${this.tagName.toLowerCase()}>`;
  }
  getAttribute(name) { return this.attributesMap.get(name) ?? null; }
  hasAttribute(name) { return this.attributesMap.has(name); }
  setAttribute(name, value) {
    this.attributesMap.set(name, String(value));
    this.ownerDocument.recordMutation?.({ type: 'attributes', target: this, attributeName: name });
  }
  removeAttribute(name) {
    if (!this.attributesMap.delete(name)) return;
    this.ownerDocument.recordMutation?.({ type: 'attributes', target: this, attributeName: name });
  }
  appendChild(child) {
    child.remove();
    child.parentElement = this;
    this.childNodes.push(child);
    this.ownerDocument.recordMutation?.({ type: 'childList', target: this });
    return child;
  }
  append(...children) {
    for (const child of children) this.appendChild(typeof child === 'string' ? this.ownerDocument.createTextNode(child) : child);
  }
  replaceChildren(...children) {
    this.textContent = '';
    this.append(...children);
  }
  remove() {
    const previous = this.parentElement;
    if (previous) previous.childNodes = previous.childNodes.filter((child) => child !== this);
    this.parentElement = null;
    if (previous) this.ownerDocument.recordMutation?.({ type: 'childList', target: previous });
  }
  contains(element) { return this === element || this.childNodes.some((child) => child.contains(element)); }
  matches(selector) { return matches(this, selector); }
  closest(selector) { return this.matches(selector) ? this : this.parentElement?.closest(selector) || null; }
  querySelectorAll(selector) {
    return this.children.flatMap((child) => [...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector)]);
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  cloneNode(deep = false) {
    const clone = this.ownerDocument.createElement(this.tagName);
    for (const [name, value] of this.attributesMap) clone.setAttribute(name, value);
    clone.innerHTML = this.innerHTML;
    if (deep) for (const child of this.childNodes) clone.appendChild(child.cloneNode(true));
    return clone;
  }
  click() { if (!this.disabled) this.dispatchEvent(new BrowserEvent('click', { bubbles: true, cancelable: true,
    isTrusted: false, detail: 0 })); }
  focus() { this.ownerDocument.activeElement = this; }
  hasPointerCapture(pointerId) { return this.ownerDocument.pointerCaptures.get(pointerId) === this; }
  setPointerCapture(pointerId) {
    const captures = this.ownerDocument.pointerCaptures;
    const previous = captures.get(pointerId);
    if (previous === this) return;
    captures.set(pointerId, this);
    if (previous) previous.dispatchEvent(new BrowserEvent('lostpointercapture', { bubbles: true, pointerId, isTrusted: true }));
    this.dispatchEvent(new BrowserEvent('gotpointercapture', { bubbles: true, pointerId, isTrusted: true }));
  }
  releasePointerCapture(pointerId) {
    if (!this.hasPointerCapture(pointerId)) return;
    this.ownerDocument.pointerCaptures.delete(pointerId);
    this.dispatchEvent(new BrowserEvent('lostpointercapture', { bubbles: true, pointerId, isTrusted: true }));
  }
  getBoundingClientRect() {
    const { x, y, width, height } = this.rectangle;
    return { x, y, width, height, left: x, top: y, right: x + width, bottom: y + height };
  }
}

function browser({ href = 'http://127.0.0.1:8787/', width = 1366,
  userAgent = 'Mozilla/5.0 Windows NT 10.0', host, autoMutations = false,
  virtualTime = false, reducedMotion = false } = {}) {
  const env = new Events();
  const observers = [];
  const microtasks = [];
  const timers = new Map();
  const mutationStats = { records: 0, deliveries: 0 };
  let nextTimer = 0;
  let now = 0;
  const mediaQueries = [];
  const document = new Events();
  document.nodeType = 9;
  document.pointerCaptures = new Map();
  document.eventParent = env;
  document.defaultView = env;
  const acceptsRecord = (observer, record) => {
    const options = observer.options;
    if (record.target !== observer.target && (!options.subtree || !observer.target.contains(record.target))) return false;
    if (record.type === 'attributes') return options.attributes &&
      (!options.attributeFilter || options.attributeFilter.includes(record.attributeName));
    if (record.type === 'childList') return options.childList;
    if (record.type === 'characterData') return options.characterData;
    return false;
  };
  document.recordMutation = record => {
    if (!autoMutations) return;
    for (const observer of observers) {
      if (!observer.active || !acceptsRecord(observer, record)) continue;
      observer.records.push(record);
      mutationStats.records++;
      if (observer.queued) continue;
      observer.queued = true;
      microtasks.push(() => {
        observer.queued = false;
        if (!observer.active) { observer.records.length = 0; return; }
        const records = observer.records.splice(0);
        if (!records.length) return;
        mutationStats.deliveries++;
        observer.callback(records);
      });
    }
  };
  const elements = [];
  document.createElement = (tag) => {
    const element = new Element(tag, document);
    elements.push(element);
    return element;
  };
  document.createElementNS = (_namespace, tag) => document.createElement(tag);
  document.createTextNode = value => new TextNode(value, document);
  document.documentElement = document.createElement('html');
  document.head = document.documentElement.appendChild(document.createElement('head'));
  document.body = document.documentElement.appendChild(document.createElement('body'));
  document.querySelectorAll = (selector) => [document.documentElement, ...document.documentElement.querySelectorAll(selector)]
    .filter((element) => element.matches(selector));
  document.querySelector = (selector) => document.querySelectorAll(selector)[0] || null;
  document.getElementById = (id) => document.querySelectorAll('[id]').find((element) => element.id === id) || null;
  Object.assign(env, {
    document, location: { href }, innerWidth: width, innerHeight: 900,
    navigator: { userAgent, platform: 'Win32' }, URL,
    Event: BrowserEvent, KeyboardEvent: BrowserEvent, MouseEvent: BrowserEvent, PointerEvent: BrowserEvent,
    __DAFEIYU_DSH_CLIENT__: host,
    queueMicrotask: (callback) => { microtasks.push(callback); },
    setTimeout: (callback, delay = 0) => { timers.set(++nextTimer, { callback, at: now + Number(delay) }); return nextTimer; },
    clearTimeout: (id) => { timers.delete(id); },
    matchMedia: query => {
      const media = new Events(); media.media = query; media.matches = reducedMotion && query === '(prefers-reduced-motion: reduce)';
      mediaQueries.push(media); return media;
    },
    MutationObserver: class {
      constructor(callback) {
        this.callback = callback; this.active = false; this.records = []; this.queued = false; observers.push(this);
      }
      observe(target, options) { this.target = target; this.options = options; this.active = true; }
      disconnect() { this.active = false; this.records.length = 0; }
    },
  });
  env.window = env;
  env.globalThis = env;
  const create = (tag, attributes = {}, parent = document.body) => {
    const element = document.createElement(tag);
    for (const [name, value] of Object.entries(attributes)) element.setAttribute(name, value);
    parent.appendChild(element);
    return element;
  };
  const flush = () => {
    let count = 0;
    const dueTimers = () => [...timers].filter(([, timer]) => !virtualTime || timer.at <= now);
    while (microtasks.length || dueTimers().length) {
      while (microtasks.length) {
        assert.ok(count++ < 50, 'Unbounded fixture microtask loop');
        microtasks.shift()();
      }
      for (const [id, timer] of dueTimers()) {
        assert.ok(count++ < 50, 'Unbounded fixture timer loop');
        timers.delete(id); timer.callback();
      }
    }
  };
  const mutation = (flushTasks = true, records = []) => {
    for (const observer of observers) {
      if (!observer.active) continue;
      const accepted = records.filter(record => acceptsRecord(observer, record));
      if (!records.length || accepted.length) observer.callback(accepted);
    }
    if (flushTasks) flush();
  };
  return { env, document, observers, create, flush, mutation, mediaQueries,
    advance: milliseconds => { now += milliseconds; flush(); },
    pendingTimers: () => timers.size,
    mutationStats: () => ({ ...mutationStats, queuedTasks: microtasks.length,
      pendingRecords: observers.reduce((sum, observer) => sum + observer.records.length, 0) }),
    activeObservers: () => observers.filter((observer) => observer.active).length,
    listenerCount: () => env.listenerCount() + document.listenerCount() + mediaQueries.reduce((sum, media) => sum + media.listenerCount(), 0) +
      elements.reduce((sum, element) => sum + element.listenerCount(), 0),
  };
}

function attrs(element) { return Object.fromEntries(element.attributesMap); }
function nativeShell(fixture, { rootSlot = fixture.create('section', { 'data-slot': 'root' }) } = {}) {
  const frame = fixture.create('div', { 'data-sidebar-collapsed': 'false' }, rootSlot);
  frame.rectangle = { x: 0, y: 0, width: fixture.env.innerWidth, height: fixture.env.innerHeight };
  const sidebarColumn = fixture.create('aside', {}, frame);
  const sidebar = fixture.create('section', { 'data-slot': 'sidebar' }, sidebarColumn);
  const sidebarRoot = fixture.create('div', {}, sidebar);
  const toggle = fixture.create('button', { 'aria-label': 'Collapse sidebar' }, sidebarRoot);
  fixture.create('svg', { 'data-fixture-icon': 'native-sidebar' }, toggle);
  const newSession = fixture.create('button', { class: 'hHd-Xa_newSession', 'aria-label': 'New Session' }, sidebarRoot);
  fixture.create('svg', { 'data-fixture-icon': 'native-new-session' }, newSession);
  const settings = fixture.create('button', { 'aria-label': 'Settings' }, sidebarRoot);
  fixture.create('span', { 'data-slot': 'sidebar.settings' }, settings);
  const center = fixture.create('main', {}, frame);
  const main = fixture.create('section', { 'data-slot': 'main' }, center);
  const conversationSlot = fixture.create('section', { 'data-slot': 'main.conversation' }, main);
  const conversation = fixture.create('div', { 'data-phase': 'active' }, conversationSlot);
  const header = fixture.create('section', { 'data-slot': 'conversation.session.header' }, conversation);
  const title = fixture.create('span', {}, header);
  title.textContent = 'Offline activity session';
  const lineage = fixture.create('section', { 'data-slot': 'conversation.session.header.lineage' }, header);
  const actions = fixture.create('section', { 'data-slot': 'conversation.session.header.actions' }, header);
  const activity = fixture.create('section', { 'data-slot': 'conversation.input.activity' }, conversation);
  const rightbarColumn = fixture.create('aside', {}, frame);
  fixture.create('section', { 'data-slot': 'rightbar' }, rightbarColumn);
  return { rootSlot, frame, sidebarColumn, sidebar, sidebarRoot, toggle, newSession, settings,
    center, main, conversationSlot, conversation, header, title, lineage, actions, activity, rightbarColumn };
}

function nativeComposer(fixture, shell) {
  const bar = fixture.create('section', { 'data-slot': 'conversation.composer.bar' }, shell.conversation);
  bar.rectangle = { x: 12, y: fixture.env.innerHeight - 120, width: fixture.env.innerWidth - 24, height: 100 };
  return bar;
}

// These count labels are the fixed DSH 0.2.1-alpha.1 public DOM projection of
// subagentCatalog directCount and the live + retained-settled job roster.
// A trigger without the count is deliberately not evidence of an actual record.
function nativeActivity(fixture, shell, { agents = true, jobs = true, team = true,
  agentLabel = '1 subagent', jobLabel = '1 background job' } = {}) {
  let agentMenu = null;
  let jobsMenu = null;
  const calls = { agents: 0, agentEscapes: 0, jobs: 0 };
  let agentSource = null;
  let jobsSource = null;
  let jobsRoot = null;
  let teamRoot = null;
  if (team) {
    teamRoot = fixture.create('div', { 'data-team-action': '' }, shell.actions);
    const teamSource = fixture.create('button', { class: 'VoX2oq_trigger', 'aria-label': 'Team', 'aria-haspopup': 'dialog' }, teamRoot);
    fixture.create('svg', { 'data-fixture-icon': 'native-team' }, teamSource);
  }
  const escapeAgents = event => {
    if (event.key !== 'Escape') return;
    calls.agentEscapes++;
    agentSource.setAttribute('aria-expanded', 'false');
    agentMenu?.removeEventListener('keydown', escapeAgents);
    agentMenu?.remove();
    agentMenu = null;
  };
  if (agents) {
    agentSource = fixture.create('button', { class: 'ZKlsPq_trigger', 'aria-haspopup': 'tree',
      'aria-expanded': 'false', 'aria-label': agentLabel }, shell.actions);
    agentSource.addEventListener('keydown', escapeAgents);
    agentSource.addEventListener('click', () => {
      calls.agents++;
      if (agentMenu) return;
      agentSource.setAttribute('aria-expanded', 'true');
      agentMenu = fixture.create('div', { class: 'ZKlsPq_menu' });
      fixture.create('div', { role: 'tree', 'aria-label': 'Subagent sessions' }, agentMenu);
      fixture.create('button', { 'aria-label': 'Select fixture agent' }, agentMenu);
      agentMenu.addEventListener('keydown', escapeAgents);
    });
  }
  const escapeJobs = event => {
    if (event.key !== 'Escape') return;
    jobsSource.setAttribute('aria-expanded', 'false');
    jobsMenu?.removeEventListener('keydown', escapeJobs);
    jobsMenu?.remove();
    jobsMenu = null;
  };
  if (jobs) {
    jobsRoot = fixture.create('div', { class: 'QsffPG_root' }, shell.actions);
    jobsSource = fixture.create('button', { class: 'QsffPG_trigger', 'aria-expanded': 'false',
      'aria-label': jobLabel }, jobsRoot);
    jobsSource.addEventListener('keydown', escapeJobs);
    jobsSource.addEventListener('click', () => {
      calls.jobs++;
      const open = jobsSource.getAttribute('aria-expanded') !== 'true';
      jobsSource.setAttribute('aria-expanded', String(open));
      if (open) {
        jobsMenu = fixture.create('ul', { class: 'QsffPG_menu', 'aria-label': 'Background jobs' }, jobsRoot);
        fixture.create('button', { 'aria-label': 'Stop fixture job' }, jobsMenu);
        jobsMenu.addEventListener('keydown', escapeJobs);
      } else {
        jobsMenu?.removeEventListener('keydown', escapeJobs);
        jobsMenu?.remove(); jobsMenu = null;
      }
    });
  }
  return { agentSource, jobsSource, jobsRoot, teamRoot, calls,
    get agentMenu() { return agentMenu; }, get jobsMenu() { return jobsMenu; } };
}

function nativeConversationHeader(fixture, shell) {
  shell.header.remove();
  const slot = fixture.create('section', { 'data-slot': 'conversation.header' }, shell.conversation);
  const header = fixture.create('header', { class: 'wSkVaW_header' }, slot);
  const leading = fixture.create('div', { class: 'wSkVaW_headerLeading' }, header);
  const session = fixture.create('section', { 'data-slot': 'conversation.session.header' }, leading);
  const row = fixture.create('div', { class: 'wSkVaW_titleRow' }, session);
  const cluster = fixture.create('div', { class: 'wSkVaW_titleCluster' }, row);
  const crumbs = fixture.create('nav', { class: 'wSkVaW_crumbs', 'aria-label': 'Session hierarchy' }, cluster);
  const segment = fixture.create('span', { class: 'wSkVaW_crumbSeg' }, crumbs);
  const title = fixture.create('span', { class: 'wSkVaW_crumb wSkVaW_crumbCurrent' }, segment);
  title.textContent = 'Native static session';
  const lineage = fixture.create('section', { 'data-slot': 'conversation.session.header.lineage' }, cluster);
  const actions = fixture.create('section', { 'data-slot': 'conversation.session.header.actions' }, row);
  const utilities = fixture.create('section', { 'data-slot': 'conversation.session.header.utilities' }, row);
  const preset = fixture.create('span', { class: 'SVAs4q_label' }, actions);
  preset.textContent = 'Workspace write';
  return { slot, header, leading, session, row, cluster, crumbs, segment, title, lineage, actions, utilities, preset };
}

// Minimal fixed-SDK row/portal structure. Receivers represent native actions;
// they never access real workspaces, sessions, files or a model API.
function nativeSidebarRows(fixture, shell, { suffix = 'one', archived = false, deferWorkspaceMenu = false } = {}) {
  const slot = fixture.create('section', { 'data-slot': 'sidebar.workspaces' }, shell.sidebarRoot);
  const region = fixture.create('div', { class: 'bhn1Oq_root' }, slot);
  const tree = fixture.create('div', { role: 'tree', 'aria-label': 'Workspaces and sessions' }, region);
  const workRow = fixture.create('div', { class: 'YDXeBa_projectRow', 'data-row-key': `workspace:ws-${suffix}`,
    role: 'treeitem', 'aria-expanded': 'true' }, tree);
  const workText = fixture.create('span', { class: 'YDXeBa_projectText' }, workRow);
  fixture.create('span', { class: 'YDXeBa_title' }, workText).textContent = `Workspace ${suffix}`;
  const workActions = fixture.create('span', { class: 'YDXeBa_rowActions' }, workRow);
  const workMore = fixture.create('button', { type: 'button', class: 'YDXeBa_iconButton',
    'aria-label': `Workspace actions for Workspace ${suffix}` }, workActions);
  fixture.create('svg', {}, workMore);
  const sessionRow = fixture.create('div', { class: `YDXeBa_sessionRow${archived ? ' YDXeBa_archived' : ''}`,
    'data-row-key': `session:session-${suffix}`, role: 'treeitem', 'aria-selected': 'false' }, tree);
  fixture.create('span', { class: 'YDXeBa_slot' }, sessionRow);
  const sessionTitle = fixture.create('span', { class: 'YDXeBa_title' }, sessionRow);
  sessionTitle.textContent = `Session ${suffix}`;
  const sessionTime = fixture.create('span', { class: 'YDXeBa_time' }, sessionRow);
  sessionTime.textContent = '5min';
  const sessionActions = fixture.create('span', { class: 'YDXeBa_rowActions' }, sessionRow);
  const sessionMore = fixture.create('button', { type: 'button', class: 'YDXeBa_iconButton',
    'aria-label': `Session actions for Session ${suffix}` }, sessionActions);
  fixture.create('svg', {}, sessionMore);
  const archive = fixture.create('button', { type: 'button', class: 'YDXeBa_iconButton',
    'aria-label': archived ? 'Unarchive session' : 'Archive session' }, sessionActions);
  fixture.create('svg', {}, archive);
  const pin = fixture.create('button', { type: 'button', class: 'YDXeBa_iconButton', 'aria-label': 'Pin session' }, sessionActions);
  fixture.create('svg', {}, pin);
  workRow.rectangle = { x: 10, y: 130, width: 280, height: 34 };
  sessionRow.rectangle = { x: 10, y: 170, width: 280, height: 32 };
  const calls = { workMore: 0, sessionMore: 0, workToggle: 0, sessionOpen: 0,
    archive: 0, restore: 0, pin: 0, renameWork: 0, renameSession: 0, deleteConfirmation: 0, fileDelete: 0 };
  let menu = null;
  let workspaceMenuCommit = false;
  const menuReceivers = [];
  const closeMenu = () => {
    for (const [element, type, callback] of menuReceivers.splice(0)) element.removeEventListener(type, callback);
    workRow.classList.remove('YDXeBa_menuOpen'); sessionRow.classList.remove('YDXeBa_menuOpen');
    menu?.remove(); menu = null;
  };
  const makeMenu = kind => {
    closeMenu();
    (kind === 'workspace' ? workRow : sessionRow).classList.add('YDXeBa_menuOpen');
    menu = fixture.create('div', { role: 'menu' });
    const rows = kind === 'workspace' ? [['Rename', 'renameWork'], ['Delete workspace', 'deleteConfirmation']] :
      [['Pin session', 'pin'], ['Rename', 'renameSession'], ['Archive session', 'archive']];
    for (const [label, action] of rows) {
      const item = fixture.create('button', { type: 'button', role: 'menuitem' }, menu);
      fixture.create('span', {}, item).textContent = label;
      const choose = () => { calls[action]++; closeMenu(); };
      item.addEventListener('click', choose); menuReceivers.push([item, 'click', choose]);
    }
    const escape = event => { if (event.key === 'Escape') { event.preventDefault(); closeMenu(); } };
    menu.addEventListener('keydown', escape); menuReceivers.push([menu, 'keydown', escape]);
  };
  workRow.addEventListener('click', () => { calls.workToggle++; });
  sessionRow.addEventListener('click', () => { calls.sessionOpen++; });
  for (const strip of [workActions, sessionActions]) strip.addEventListener('click', event => { event.stopPropagation(); });
  workMore.addEventListener('click', () => {
    calls.workMore++;
    if (deferWorkspaceMenu) workspaceMenuCommit = true; else makeMenu('workspace');
  });
  sessionMore.addEventListener('click', () => { calls.sessionMore++; makeMenu('session'); });
  archive.addEventListener('click', () => { calls[archived ? 'restore' : 'archive']++; });
  pin.addEventListener('click', () => { calls.pin++; });
  return { slot, region, tree, workRow, workActions, workMore, sessionRow, sessionActions, sessionMore,
    sessionTitle, sessionTime, archive, pin, calls, closeMenu,
    commitWorkspaceMenu() { if (workspaceMenuCommit) { workspaceMenuCommit = false; makeMenu('workspace'); } },
    get menu() { return menu; } };
}

function touchPointer(fixture, target, type, options = {}) {
  const event = new fixture.env.PointerEvent(type, { bubbles: true, cancelable: true,
    pointerType: 'touch', pointerId: 1, isPrimary: true, button: 0,
    buttons: type === 'pointerup' || type === 'pointercancel' ? 0 : 1,
    clientX: 240, clientY: 185, ...options });
  // Touch browsers capture the initial descendant implicitly; the row's
  // explicit capture during a horizontal drag transfers from that descendant.
  // These are generic browser events, with no mobile gesture policy in them.
  const receiver = ['pointermove', 'pointerup', 'pointercancel'].includes(type) ?
    fixture.document.pointerCaptures.get(event.pointerId) || target : target;
  receiver.dispatchEvent(event);
  if (type === 'pointerdown' && event.pointerType === 'touch') target.setPointerCapture(event.pointerId);
  if (type === 'pointerup' || type === 'pointercancel') fixture.document.pointerCaptures.get(event.pointerId)?.releasePointerCapture(event.pointerId);
  fixture.flush();
  return event;
}

function userClick(fixture, target) {
  const event = new fixture.env.MouseEvent('click', { bubbles: true, cancelable: true, isTrusted: true, detail: 1 });
  target.dispatchEvent(event); fixture.flush();
  return event;
}

function swipeLeft(fixture, row, { pointerId = 1, distance = 100, y = 185 } = {}) {
  touchPointer(fixture, row, 'pointerdown', { pointerId, clientX: 240, clientY: y });
  const move = touchPointer(fixture, row, 'pointermove', { pointerId, clientX: 240 - distance, clientY: y });
  touchPointer(fixture, row, 'pointerup', { pointerId, clientX: 240 - distance, clientY: y });
  return move;
}

function sidebarTouchFixture({ deferWorkspaceMenu = false, ...options } = {}) {
  const fixture = browser({ host: validHost(), autoMutations: true, virtualTime: true, ...options });
  const shell = nativeShell(fixture);
  shell.sidebarColumn.rectangle = { x: 0, y: 0, width: 310, height: 900 };
  const rows = nativeSidebarRows(fixture, shell, { deferWorkspaceMenu });
  const nativeListeners = fixture.listenerCount();
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  fixture.flush();
  const toggle = fixture.document.querySelector('[data-dafeiyu-mobile-control="sidebar-toggle"]');
  toggle?.click(); fixture.flush();
  return { ...fixture, shell, rows, dispose, nativeListeners, toggle };
}

function connectionFixture(initial = 'disconnected') {
  let snapshot = initial;
  const subscribers = new Set();
  let cleanups = 0;
  let reconnects = 0;
  return {
    service: { state: {
      getSnapshot() { return snapshot; },
      subscribe(callback) { subscribers.add(callback); return () => { if (subscribers.delete(callback)) cleanups++; }; },
    }, reconnect() { reconnects++; } },
    publish(value) { snapshot = value; for (const callback of [...subscribers]) callback(); },
    get cleanups() { return cleanups; }, get reconnects() { return reconnects; },
  };
}

function startupPreviewFixture(options = {}) {
  const fixture = browser({ href: 'http://127.0.0.1:8787/?dafeiyu-preview=mobile',
    autoMutations: true, virtualTime: true, ...options });
  const shell = nativeShell(fixture);
  nativeComposer(fixture, shell);
  const notifications = [];
  const previewParent = { postMessage(data, origin) { notifications.push({ data, origin }); } };
  fixture.env.parent = previewParent;
  const context = createDafeiyuMobileContext(fixture.env);
  const removeSurface = context.install(cssText);
  fixture.flush();
  const sidebar = fixture.document.querySelector('[data-dafeiyu-mobile-control="sidebar-toggle"]');
  if (sidebar) sidebar.rectangle = { x: 18, y: 12, width: 36, height: 36 };
  const connection = connectionFixture('connected');
  const removeStartup = createDafeiyuStartup(fixture.env, { readContext: context.readContext, connection: connection.service });
  fixture.flush();
  const overlay = fixture.document.querySelector('[data-dafeiyu-mobile-layout="startup-overlay"]');
  const focus = overlay?.querySelector('[data-dafeiyu-mobile-layout="startup-focus"]');
  if (focus) focus.rectangle = { x: 160, y: 260, width: 64, height: 64 };
  fixture.advance(740);
  const replay = (requestId = 1, overrides = {}) => {
    fixture.env.dispatchEvent({ type: 'message', source: previewParent, origin: 'http://127.0.0.1:8791',
      data: { type: 'dafeiyu-startup-preview', version: 1, action: 'replay', requestId }, ...overrides });
    fixture.flush();
  };
  const dispose = () => { removeStartup(); removeSurface(); fixture.flush(); };
  return { ...fixture, context, connection, overlay, focus, notifications, previewParent, replay, dispose,
    status: () => overlay?.getAttribute('data-dafeiyu-startup-state') };
}

function startupNativeFixture({ mode = 'local', bound = true, connected = false } = {}) {
  const fixture = browser({ host: validHost({ mode }), autoMutations: true, virtualTime: true });
  const shell = nativeShell(fixture);
  const notifications = [];
  const bridge = {
    version: 1, platform: 'android', document_bound: bound, capabilities: ['app.web.presentation'],
    notifyPresentationReady() { notifications.push(true); return Promise.resolve({ accepted: true }); },
    notifyPresentationUnavailable() { notifications.push(false); return Promise.resolve({ accepted: true }); },
  };
  fixture.env.__DAFEIYU_DSH_HOST__ = bridge;
  const context = createDafeiyuMobileContext(fixture.env);
  const removeSurface = context.install(cssText);
  fixture.flush();
  const target = fixture.document.querySelector('[data-dafeiyu-mobile-control="sidebar-toggle"]');
  const connection = connectionFixture(connected ? 'connected' : 'disconnected');
  const removeStartup = createDafeiyuStartup(fixture.env, { readContext: context.readContext, connection: connection.service });
  fixture.flush();
  const overlay = fixture.document.querySelector('[data-dafeiyu-mobile-layout="startup-overlay"]');
  const focus = overlay.querySelector('[data-dafeiyu-mobile-layout="startup-focus"]');
  focus.rectangle = { x: 160, y: 260, width: 64, height: 64 };
  const addComposer = () => nativeComposer(fixture, shell);
  const showTarget = () => {
    target.rectangle = { x: 18, y: 12, width: 36, height: 36 };
    fixture.env.dispatchEvent({ type: 'resize' }); fixture.flush();
  };
  const dispose = () => { removeStartup(); removeSurface(); fixture.flush(); };
  return { ...fixture, shell, bridge, notifications, context, target, connection, overlay, addComposer, showTarget,
    removeStartup, removeSurface, dispose, status: () => overlay.getAttribute('data-dafeiyu-startup-state') };
}

function pluginStyles(fixture) { return fixture.document.querySelectorAll('style[data-plugin="dafeiyu-mobile-ui"]'); }
function ownMarkers(fixture) { return fixture.document.querySelectorAll('[data-dafeiyu-mobile-control]'); }
function assertInactive(fixture) {
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-surface'), null);
  assert.equal(pluginStyles(fixture).length, 0);
  assert.equal(fixture.activeObservers(), 0);
}

test('ordinary PC pages stay inert at desktop/mobile widths and with a forged Android UA', () => {
  for (const options of [{}, { width: 390 }, { width: 390, userAgent: 'Mozilla/5.0 (Linux; Android 16) Mobile' }]) {
    const fixture = browser(options);
    const context = createDafeiyuMobileContext(fixture.env);
    assert.equal(context.readContext(), null);
    const rootBefore = attrs(fixture.document.documentElement);
    const bodyBefore = attrs(fixture.document.body);
    const dispose = context.install(cssText);
    assertInactive(fixture);
    assert.deepEqual(attrs(fixture.document.documentElement), rootBefore);
    assert.deepEqual(attrs(fixture.document.body), bodyBefore);
    dispose();
    assert.equal(fixture.listenerCount(), 0);
  }
});

test('mobile viewport follows window and keyboard resize, restores its prior value and releases listeners', () => {
  const fixture = browser({ host: validHost() });
  const root = fixture.document.documentElement;
  root.style.setProperty('--df-viewport-height', '700px');
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  assert.equal(root.style.getPropertyValue('--df-viewport-height'), '900px');
  fixture.env.innerHeight = 410;
  fixture.env.dispatchEvent({ type: 'resize' });
  assert.equal(root.style.getPropertyValue('--df-viewport-height'), '410px');
  fixture.env.innerHeight = 0;
  fixture.env.dispatchEvent({ type: 'resize' });
  assert.equal(root.style.getPropertyValue('--df-viewport-height'), '410px');
  dispose();
  assert.equal(root.style.getPropertyValue('--df-viewport-height'), '700px');
  assert.equal(fixture.listenerCount(), 0);
});

test('viewport ownership preserves a later writer and never applies to ordinary PC pages', () => {
  const fixture = browser({ host: validHost() });
  const root = fixture.document.documentElement;
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  root.style.setProperty('--df-viewport-height', '555px');
  fixture.env.innerHeight = 600;
  fixture.env.dispatchEvent({ type: 'resize' });
  assert.equal(root.style.getPropertyValue('--df-viewport-height'), '555px');
  dispose();
  assert.equal(root.style.getPropertyValue('--df-viewport-height'), '555px');
  const desktop = browser();
  const stop = createDafeiyuMobileContext(desktop.env).install(cssText);
  assert.equal(desktop.document.documentElement.style.getPropertyValue('--df-viewport-height'), '');
  stop();
});

test('complete version-1 Android local and online contexts preserve explicit appearance values', () => {
  for (const mode of ['local', 'online']) {
    const fixture = browser({ host: validHost({ mode, theme: 'dark', material: false, motion: false }) });
    const context = createDafeiyuMobileContext(fixture.env);
    const value = context.readContext();
    assert.equal(value.mode, mode);
    assert.equal(value.preview, false);
    assert.equal(value.theme, 'dark');
    assert.equal(value.material, false);
    assert.equal(value.motion, false);
    const dispose = context.install(cssText);
    assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-surface'), 'mobile');
    assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-mode'), mode);
    assert.equal(pluginStyles(fixture).length, 1);
    dispose();
    assertInactive(fixture);
  }
});

test('missing or malformed host fields cannot activate a page', () => {
  const candidates = [null, [], 'android', validHost({ version: 2 }), validHost({ version: '1' }),
    validHost({ platform: 'windows' }), validHost({ surface: 'desktop' }),
    validHost({ mode: 'remote' }), validHost({ theme: 'auto' }),
    validHost({ material: 'true' }), validHost({ motion: 1 })];
  for (const field of Object.keys(validHost())) {
    const incomplete = validHost();
    delete incomplete[field];
    candidates.push(incomplete);
  }
  for (const host of candidates) {
    const fixture = browser({ host });
    const context = createDafeiyuMobileContext(fixture.env);
    assert.equal(context.readContext(), null, `Rejected context: ${JSON.stringify(host)}`);
    const dispose = context.install(cssText);
    assertInactive(fixture);
    dispose();
  }
});

test('explicit preview enables the page, URL removal disables it, and valid host values take precedence', () => {
  const fixture = browser({ href: 'http://127.0.0.1:8787/?dafeiyu-preview=mobile' });
  const context = createDafeiyuMobileContext(fixture.env);
  assert.equal(context.readContext().preview, true);
  const dispose = context.install(cssText);
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-preview'), 'mobile');
  fixture.env.location.href = 'http://127.0.0.1:8787/?dafeiyu-preview=off';
  fixture.env.dispatchEvent({ type: 'popstate' });
  assert.equal(context.readContext(), null);
  assertInactive(fixture);
  fixture.env.location.href = 'http://127.0.0.1:8787/?dafeiyu-preview=mobile';
  fixture.env.dispatchEvent({ type: 'pageshow' });
  assert.equal(pluginStyles(fixture).length, 1);
  fixture.env.__DAFEIYU_DSH_CLIENT__ = validHost({ mode: 'local', theme: 'light', material: false });
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-client-change' });
  assert.equal(context.readContext().preview, false);
  assert.equal(context.readContext().theme, 'light');
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-preview'), null);
  dispose();
  assertInactive(fixture);
  assert.equal(fixture.listenerCount(), 0);
});

test('appearance query parameters require preview, validate their values, and cannot override a real host', () => {
  for (const theme of ['system', 'light', 'dark']) {
    for (const material of [true, false]) for (const motion of [true, false]) {
      const href = `http://127.0.0.1:8787/?dafeiyu-preview=mobile&dafeiyu-theme=${theme}&dafeiyu-material=${material}&dafeiyu-motion=${motion}`;
      const fixture = browser({ href });
      const context = createDafeiyuMobileContext(fixture.env);
      const value = context.readContext();
      assert.equal(value.preview, true);
      assert.equal(value.theme, theme);
      assert.equal(value.material, material);
      assert.equal(value.motion, motion);
      const dispose = context.install(cssText);
      assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-theme'), theme);
      assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-material'), String(material));
      assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-motion'), String(motion));
      dispose();
      assertInactive(fixture);
    }
  }
  const invalid = browser({ href: 'http://127.0.0.1:8787/?dafeiyu-preview=mobile&dafeiyu-theme=sepia&dafeiyu-material=0&dafeiyu-motion=off' });
  const fallback = createDafeiyuMobileContext(invalid.env).readContext();
  assert.equal(fallback.theme, 'system');
  assert.equal(fallback.material, true);
  assert.equal(fallback.motion, true);
  const noPreview = browser({ href: 'http://127.0.0.1:8787/?dafeiyu-theme=dark&dafeiyu-material=false&dafeiyu-motion=false' });
  const noPreviewContext = createDafeiyuMobileContext(noPreview.env);
  assert.equal(noPreviewContext.readContext(), null);
  const noPreviewDispose = noPreviewContext.install(cssText);
  assertInactive(noPreview);
  noPreviewDispose();
  for (const preview of ['', '&dafeiyu-preview=mobile']) {
    const fixture = browser({
      href: `http://127.0.0.1:8787/?dafeiyu-theme=dark&dafeiyu-material=false&dafeiyu-motion=true${preview}`,
      host: validHost({ theme: 'light', material: true, motion: false }),
    });
    const context = createDafeiyuMobileContext(fixture.env);
    const value = context.readContext();
    assert.equal(value.preview, false);
    assert.equal(value.theme, 'light');
    assert.equal(value.material, true);
    assert.equal(value.motion, false);
    const dispose = context.install(cssText);
    assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-theme'), 'light');
    assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-material'), 'true');
    assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-motion'), 'false');
    dispose();
  }
});

test('disposal restores every owned control role without changing the original theme or layout', () => {
  const fixture = browser({ host: validHost() });
  fixture.document.documentElement.setAttribute('data-dafeiyu-mode', 'previous-host-mode');
  fixture.document.body.setAttribute('data-ds-dark-theme', 'true');
  fixture.document.body.setAttribute('class', 'desktop-layout host-theme');
  fixture.document.body.setAttribute('style', '--dsw-font-size: 15px');
  fixture.document.body.style.cssText = '--dsw-font-size: 15px';
  const rootBefore = attrs(fixture.document.documentElement);
  const bodyBefore = attrs(fixture.document.body);
  const bodyStyleBefore = { ...fixture.document.body.style };
  const controls = [];
  for (const slot of ['conversation.header.leading', 'conversation.session.header.actions',
    'conversation.input.model', 'conversation.input.permission', 'conversation.input.activity',
    'conversation.composer.bar']) {
    const section = fixture.create('section', { 'data-slot': slot });
    controls.push(fixture.create('button', slot === 'conversation.composer.bar' ? { type: 'submit' } : {}, section));
  }
  const dialog = fixture.create('div', { role: 'dialog' });
  controls.push(fixture.create('button', { 'aria-label': 'Close' }, dialog));
  const protectedButton = fixture.create('button', { 'aria-label': 'Close', 'data-dafeiyu-mobile-control': 'host-owned' });
  const layout = { toggleSidebar() { assert.fail('Activation must not change the desktop sidebar preference'); } };
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText, layout);
  assert.equal(ownMarkers(fixture).length, controls.length + 1);
  for (const control of controls) assert.ok(control.hasAttribute('data-dafeiyu-mobile-control'));
  assert.deepEqual(attrs(fixture.document.body), bodyBefore);
  assert.deepEqual({ ...fixture.document.body.style }, bodyStyleBefore);
  fixture.mutation(false);
  dispose();
  dispose();
  fixture.flush();
  for (const control of controls) assert.equal(control.getAttribute('data-dafeiyu-mobile-control'), null);
  assert.equal(protectedButton.getAttribute('data-dafeiyu-mobile-control'), 'host-owned');
  assert.deepEqual(attrs(fixture.document.documentElement), rootBefore);
  assert.deepEqual(attrs(fixture.document.body), bodyBefore);
  assert.deepEqual({ ...fixture.document.body.style }, bodyStyleBefore);
  assert.equal(pluginStyles(fixture).length, 0);
  assert.equal(fixture.activeObservers(), 0);
  assert.equal(fixture.listenerCount(), 0);
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-client-change' });
  assert.equal(pluginStyles(fixture).length, 0);
});

test('host removal clears markers and style, with later host-owned changes retained', () => {
  const fixture = browser({ host: validHost() });
  const section = fixture.create('section', { 'data-slot': 'conversation.input.model' });
  const control = fixture.create('button', {}, section);
  const context = createDafeiyuMobileContext(fixture.env);
  const dispose = context.install(cssText);
  control.setAttribute('data-dafeiyu-mobile-control', 'new-host-value');
  fixture.document.documentElement.setAttribute('data-dafeiyu-theme', 'new-host-theme');
  fixture.env.__DAFEIYU_DSH_CLIENT__ = undefined;
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-client-change' });
  assertInactive(fixture);
  assert.equal(control.getAttribute('data-dafeiyu-mobile-control'), 'new-host-value');
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-theme'), 'new-host-theme');
  dispose();
  assert.equal(fixture.listenerCount(), 0);
});

test('host exit restores detached controls and keeps attributes changed later by the host', () => {
  const fixture = browser({ host: validHost() });
  const originalRoot = attrs(fixture.document.documentElement);
  const detachedSection = fixture.create('section', { 'data-slot': 'conversation.input.model' });
  const restored = fixture.create('button', {}, detachedSection);
  const changed = fixture.create('button', {}, detachedSection);
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  assert.equal(restored.getAttribute('data-dafeiyu-mobile-control'), 'model');
  assert.equal(changed.getAttribute('data-dafeiyu-mobile-control'), 'model');
  changed.setAttribute('data-dafeiyu-mobile-control', 'later-host-control');
  fixture.mutation();
  assert.equal(changed.getAttribute('data-dafeiyu-mobile-control'), 'later-host-control');
  detachedSection.remove();
  assert.equal(restored.isConnected, false);
  assert.equal(changed.isConnected, false);
  // No mutation delivery: explicit host exit must still release detached nodes.
  fixture.env.__DAFEIYU_DSH_CLIENT__ = undefined;
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-client-change' });
  assertInactive(fixture);
  assert.equal(restored.getAttribute('data-dafeiyu-mobile-control'), null);
  assert.equal(changed.getAttribute('data-dafeiyu-mobile-control'), 'later-host-control');
  assert.deepEqual(attrs(fixture.document.documentElement), originalRoot);
  dispose();
  assert.equal(fixture.listenerCount(), 0);
});

test('rightbar and microphone roles stay distinct from neighbouring header and activity actions', () => {
  const fixture = browser({ host: validHost() });
  const leading = fixture.create('section', { 'data-slot': 'conversation.header.leading' });
  const ordinaryLeading = fixture.create('button', { 'aria-label': 'Refresh workspace' }, leading);
  const header = fixture.create('section', { 'data-slot': 'conversation.session.header' });
  const corner = fixture.create('section', { 'data-slot': 'conversation.session.header.corner' }, header);
  const rightbar = fixture.create('button', { 'data-sidebar-right-expand': '', 'aria-label': '打开右侧边栏' }, corner);
  const labelRightbar = fixture.create('button', { 'aria-label': 'Open right sidebar' }, corner);
  const ordinaryCorner = fixture.create('button', { 'aria-label': 'Open right sidebar documentation' }, corner);
  const actions = fixture.create('section', { 'data-slot': 'conversation.session.header.actions' }, header);
  const ordinaryAction = fixture.create('button', { 'aria-label': 'Copy conversation link' }, actions);
  const semanticTab = fixture.create('button', { role: 'tab', 'aria-label': 'Conversation tab' }, actions);
  const tabs = fixture.create('section', { 'data-conversation-tabs': '' }, header);
  const nativeTab = fixture.create('button', { role: 'tab', 'aria-label': 'Current session' }, tabs);
  const nativeTabAuxiliary = fixture.create('button', { 'aria-label': 'More sessions' }, tabs);
  const activity = fixture.create('section', { 'data-slot': 'conversation.input.activity' });
  const microphone = fixture.create('button', { 'aria-label': '开始录音' }, activity);
  const ordinaryActivity = fixture.create('button', { 'aria-label': 'Token usage' }, activity);
  const similarActivity = fixture.create('button', { 'aria-label': 'Start recording trace' }, activity);
  const model = fixture.create('section', { 'data-slot': 'conversation.input.model' });
  const sameLabelOutsideActivity = fixture.create('button', { 'aria-label': 'Start recording' }, model);
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  const expectations = new Map([
    [ordinaryLeading, 'header'], [rightbar, 'rightbar-toggle'], [labelRightbar, 'rightbar-toggle'],
    [ordinaryCorner, 'session-action'], [ordinaryAction, 'session-action'],
    [semanticTab, null], [nativeTab, null], [nativeTabAuxiliary, null],
    [microphone, 'microphone'], [ordinaryActivity, 'activity'], [similarActivity, 'activity'],
    [sameLabelOutsideActivity, 'model'],
  ]);
  for (const [button, role] of expectations) assert.equal(button.getAttribute('data-dafeiyu-mobile-control'), role);
  ordinaryAction.setAttribute('role', 'tab');
  fixture.mutation(true, [{ type: 'attributes', target: ordinaryAction, attributeName: 'role' }]);
  assert.equal(ordinaryAction.getAttribute('data-dafeiyu-mobile-control'), null);
  ordinaryAction.removeAttribute('role');
  fixture.mutation(true, [{ type: 'attributes', target: ordinaryAction, attributeName: 'role' }]);
  assert.equal(ordinaryAction.getAttribute('data-dafeiyu-mobile-control'), 'session-action');
  microphone.setAttribute('aria-label', 'Task progress');
  fixture.mutation(true, [{ type: 'attributes', target: microphone, attributeName: 'aria-label' }]);
  assert.equal(microphone.getAttribute('data-dafeiyu-mobile-control'), 'activity');
  const newMic = fixture.create('button', { 'aria-label': 'Start recording' });
  activity.replaceChildren(newMic, ordinaryActivity);
  fixture.mutation(true, [{ type: 'childList', target: activity }]);
  assert.equal(newMic.getAttribute('data-dafeiyu-mobile-control'), 'microphone');
  assert.equal(microphone.getAttribute('data-dafeiyu-mobile-control'), null);
  assert.equal(similarActivity.getAttribute('data-dafeiyu-mobile-control'), null);
  assert.equal(ordinaryActivity.getAttribute('data-dafeiyu-mobile-control'), 'activity');
  dispose();
  for (const button of [...expectations.keys(), newMic]) assert.equal(button.getAttribute('data-dafeiyu-mobile-control'), null);
  assert.equal(fixture.listenerCount(), 0);
});

test('native phase transitions and whole slot replacement reconcile layout markers on detached trees', () => {
  const fixture = browser({ host: validHost() });
  const rootBefore = attrs(fixture.document.documentElement);
  const bodyBefore = attrs(fixture.document.body);
  const rootSlot = fixture.create('section', { 'data-slot': 'root' });
  function renderTree(phase) {
    const frame = fixture.create('div', { 'data-sidebar-collapsed': 'true' }, rootSlot);
    const sidebarColumn = fixture.create('aside', {}, frame);
    const sidebar = fixture.create('section', { 'data-slot': 'sidebar' }, sidebarColumn);
    const sidebarRoot = fixture.create('div', {}, sidebar);
    const sidebarNavigation = fixture.create('nav', {}, sidebarRoot);
    const toggle = fixture.create('button', { 'aria-label': '打开侧边栏' }, sidebarRoot);
    fixture.create('span', { 'data-slot': 'sidebar.toggle.badge' }, toggle);
    fixture.create('svg', {}, toggle);
    const brandButton = fixture.create('button', {}, sidebarRoot);
    fixture.create('span', { 'data-slot': 'sidebar.brand.mark' }, brandButton);
    const workspaceSlot = fixture.create('section', { 'data-slot': 'sidebar.workspaces' }, sidebarRoot);
    const workspaces = fixture.create('div', {}, workspaceSlot);
    const footer = fixture.create('footer', {}, sidebarRoot);
    const settingsArea = fixture.create('div', {}, footer);
    fixture.create('section', { 'data-slot': 'sidebar.settings' }, settingsArea);
    const footerActions = fixture.create('div', {}, footer);
    fixture.create('section', { 'data-slot': 'sidebar.footer.action' }, footerActions);
    const center = fixture.create('main', {}, frame);
    const main = fixture.create('section', { 'data-slot': 'main' }, center);
    const conversationSlot = fixture.create('section', { 'data-slot': 'main.conversation' }, main);
    const conversationRoot = fixture.create('div', { 'data-phase': phase }, conversationSlot);
    const seat = fixture.create('div', { 'data-composer-seat': '' }, conversationRoot);
    const stack = fixture.create('div', {}, seat);
    const hero = fixture.create('section', {}, stack);
    fixture.create('span', { 'data-slot': 'conversation.hero.brand.mark' }, hero);
    const bar = fixture.create('section', { 'data-slot': 'conversation.composer.bar' }, stack);
    const modelSlot = fixture.create('section', { 'data-slot': 'conversation.input.model' }, bar);
    const model = fixture.create('button', {}, modelSlot);
    return { frame, conversationRoot, seat, stack, hero, model, sidebarNavigation, brandButton, workspaces, footer };
  }
  const first = renderTree('hero');
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  assert.equal(first.conversationRoot.getAttribute('data-dafeiyu-mobile-layout'), 'conversation-root');
  assert.equal(first.conversationRoot.getAttribute('data-phase'), 'hero');
  assert.equal(first.seat.getAttribute('data-dafeiyu-mobile-layout'), 'composer-seat');
  assert.equal(first.stack.getAttribute('data-dafeiyu-mobile-layout'), 'composer-stack');
  assert.equal(first.hero.getAttribute('data-dafeiyu-mobile-layout'), 'hero');
  assert.equal(first.brandButton.getAttribute('data-dafeiyu-mobile-layout'), 'sidebar-brand');
  assert.equal(first.sidebarNavigation.getAttribute('data-dafeiyu-mobile-section'), 'navigation');
  assert.equal(first.workspaces.getAttribute('data-dafeiyu-mobile-section'), 'workspaces');
  assert.equal(first.footer.getAttribute('data-dafeiyu-mobile-section'), 'footer');
  first.conversationRoot.setAttribute('data-phase', 'active');
  fixture.mutation(true, [{ type: 'attributes', target: first.conversationRoot, attributeName: 'data-phase' }]);
  assert.equal(first.conversationRoot.getAttribute('data-phase'), 'active');
  assert.equal(first.hero.getAttribute('data-dafeiyu-mobile-layout'), null);
  first.conversationRoot.setAttribute('data-phase', 'hero');
  fixture.mutation(true, [{ type: 'attributes', target: first.conversationRoot, attributeName: 'data-phase' }]);
  assert.equal(first.hero.getAttribute('data-dafeiyu-mobile-layout'), 'hero');
  first.stack.setAttribute('data-dafeiyu-mobile-layout', 'later-host-stack');
  const second = renderTree('settling');
  rootSlot.replaceChildren(second.frame);
  fixture.mutation(true, [{ type: 'childList', target: rootSlot }]);
  assert.equal(first.frame.isConnected, false);
  for (const element of [first.conversationRoot, first.seat, first.hero, first.brandButton]) {
    assert.equal(element.getAttribute('data-dafeiyu-mobile-layout'), null);
  }
  for (const element of [first.sidebarNavigation, first.workspaces, first.footer]) {
    assert.equal(element.getAttribute('data-dafeiyu-mobile-section'), null);
  }
  assert.equal(first.stack.getAttribute('data-dafeiyu-mobile-layout'), 'later-host-stack');
  assert.equal(first.model.getAttribute('data-dafeiyu-mobile-control'), null);
  assert.equal(second.conversationRoot.getAttribute('data-dafeiyu-mobile-layout'), 'conversation-root');
  assert.equal(second.conversationRoot.getAttribute('data-phase'), 'settling');
  assert.equal(second.hero.getAttribute('data-dafeiyu-mobile-layout'), null);
  assert.equal(second.model.getAttribute('data-dafeiyu-mobile-control'), 'model');
  assert.equal(pluginStyles(fixture).length, 1);
  assert.equal(fixture.activeObservers(), 1);
  assert.deepEqual(attrs(fixture.document.body), bodyBefore);
  second.frame.remove();
  fixture.env.__DAFEIYU_DSH_CLIENT__ = undefined;
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-client-change' });
  assertInactive(fixture);
  assert.equal(second.model.getAttribute('data-dafeiyu-mobile-control'), null);
  assert.equal(second.conversationRoot.getAttribute('data-dafeiyu-mobile-layout'), null);
  assert.equal(second.stack.getAttribute('data-dafeiyu-mobile-layout'), null);
  assert.deepEqual(attrs(fixture.document.documentElement), rootBefore);
  assert.deepEqual(attrs(fixture.document.body), bodyBefore);
  dispose();
  assert.equal(fixture.listenerCount(), 0);
});

test('real root/sidebar anchors create disposable navigation without changing desktop layout defaults', () => {
  const fixture = browser({ host: validHost({ mode: 'online' }) });
  Object.defineProperty(fixture.env, '__DSH_MOBILE_SHELL__', {
    value: false, writable: true, configurable: true, enumerable: false,
  });
  const yieldBefore = Object.getOwnPropertyDescriptor(fixture.env, '__DSH_MOBILE_SHELL__');
  const rootBefore = attrs(fixture.document.documentElement);
  const bodyBefore = attrs(fixture.document.body);
  const rootSlot = fixture.create('section', { 'data-slot': 'root' });
  const frame = fixture.create('div', { 'data-sidebar-collapsed': 'true' }, rootSlot);
  const frameBefore = attrs(frame);
  const sidebarColumn = fixture.create('aside', {}, frame);
  const sidebar = fixture.create('section', { 'data-slot': 'sidebar' }, sidebarColumn);
  const toggle = fixture.create('button', { 'aria-label': 'Toggle sidebar' }, sidebar);
  fixture.create('span', { 'data-slot': 'sidebar.toggle.badge' }, toggle);
  fixture.create('svg', { 'aria-hidden': 'true' }, toggle);
  const newSession = fixture.create('button', { 'aria-label': 'New Session' }, sidebar);
  fixture.create('svg', { id: 'original-new-session-icon' }, newSession);
  const devices = fixture.create('section', { 'data-slot': 'sidebar.devices', 'data-host-device': 'unchanged' }, sidebar);
  const center = fixture.create('main', {}, frame);
  const main = fixture.create('section', { 'data-slot': 'main' }, center);
  fixture.create('h1', {}, main).textContent = 'Offline navigation fixture';
  const existingChildren = [...frame.children];
  const originalListener = () => {};
  toggle.addEventListener('click', originalListener);
  const listenersBefore = fixture.listenerCount();
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText, {
    toggleSidebar() { assert.fail('Installation must not mutate persisted sidebar state'); },
  });
  assert.equal(fixture.document.querySelectorAll('nav[data-dafeiyu-mobile-layout="navigation"]').length, 1);
  assert.equal(fixture.document.querySelectorAll('button[data-dafeiyu-mobile-layout="backdrop"]').length, 1);
  assert.equal(frame.getAttribute('data-sidebar-collapsed'), 'true');
  assert.equal(fixture.env.__DSH_MOBILE_SHELL__, true);
  assert.equal(devices.getAttribute('data-host-device'), 'unchanged');
  assert.deepEqual(attrs(fixture.document.body), bodyBefore);
  dispose();
  assert.deepEqual(frame.children, existingChildren);
  assert.deepEqual(attrs(frame), frameBefore);
  assert.deepEqual(attrs(fixture.document.documentElement), rootBefore);
  assert.deepEqual(attrs(fixture.document.body), bodyBefore);
  assert.deepEqual(Object.getOwnPropertyDescriptor(fixture.env, '__DSH_MOBILE_SHELL__'), yieldBefore);
  assert.equal(fixture.listenerCount(), listenersBefore, 'Original listener survives; plugin listeners do not');
  assert.equal(fixture.activeObservers(), 0);
  assert.equal(pluginStyles(fixture).length, 0);
  assert.ok(toggle.listeners.get('click').has(originalListener));
  assert.equal(newSession.querySelector('svg').id, 'original-new-session-icon');
});

test('navigation survives the official collapsed badge being replaced by the expanded sidebar source', () => {
  const fixture = browser({ host: validHost({ mode: 'online' }) });
  const rootSlot = fixture.create('section', { 'data-slot': 'root' });
  const frame = fixture.create('div', { 'data-sidebar-collapsed': 'true' }, rootSlot);
  const column = fixture.create('aside', {}, frame);
  const sidebar = fixture.create('section', { 'data-slot': 'sidebar' }, column);
  const collapsedToggle = fixture.create('button', { 'aria-label': '打开侧边栏' }, sidebar);
  const badge = fixture.create('span', { 'data-slot': 'sidebar.toggle.badge' }, collapsedToggle);
  const brandMark = fixture.create('span', { 'data-slot': 'sidebar.brand.mark' }, collapsedToggle);
  fixture.create('svg', { 'data-fixture-icon': 'brand-fish' }, brandMark);
  fixture.create('svg', { 'data-fixture-icon': 'panel-left-collapsed' }, collapsedToggle);
  const brandNewSession = fixture.create('button', { 'aria-label': 'New Session' }, sidebar);
  const brandNewMark = fixture.create('span', { 'data-slot': 'sidebar.brand.mark' }, brandNewSession);
  fixture.create('svg', { 'data-fixture-icon': 'brand-new-session' }, brandNewMark);
  brandNewSession.addEventListener('click', () => { assert.fail('Brand action must not be the new-session source'); });
  let newSessions = 0;
  const actualNewSession = fixture.create('button', { 'aria-label': 'New Session' }, sidebar);
  fixture.create('svg', { 'data-fixture-icon': 'real-new-session' }, actualNewSession);
  actualNewSession.addEventListener('click', () => { newSessions++; });
  let layoutChanges = 0;
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText, {
    toggleSidebar() {
      layoutChanges++;
      frame.removeAttribute('data-sidebar-collapsed');
      badge.remove();
      collapsedToggle.remove();
      const expandedToggle = fixture.create('button', { 'aria-label': '收起侧边栏' }, sidebar);
      fixture.create('svg', { 'data-fixture-icon': 'panel-left-expanded' }, expandedToggle);
      fixture.mutation(false);
    },
  });
  const navigation = fixture.document.querySelector('nav[data-dafeiyu-mobile-layout="navigation"]');
  const navToggle = navigation.querySelector('button[data-dafeiyu-mobile-control="sidebar-toggle"]');
  assert.equal(layoutChanges, 0);
  assert.equal(navToggle.querySelector('svg').getAttribute('data-fixture-icon'), 'panel-left-collapsed');
  navToggle.click();
  fixture.flush();
  assert.equal(layoutChanges, 1);
  assert.equal(frame.getAttribute('data-sidebar-collapsed'), null);
  assert.equal(fixture.document.querySelector('[data-slot="sidebar.toggle.badge"]'), null);
  assert.equal(fixture.document.querySelector('nav[data-dafeiyu-mobile-layout="navigation"]'), navigation);
  assert.equal(navToggle.querySelector('svg').getAttribute('data-fixture-icon'), 'panel-left-expanded');
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-drawer'), 'open');
  const backdrop = fixture.document.querySelector('button[data-dafeiyu-mobile-layout="backdrop"]');
  backdrop.click();
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-drawer'), 'closed');
  assert.equal(layoutChanges, 1, 'Closing the local drawer must not collapse the official desktop layout');
  assert.equal(frame.getAttribute('data-sidebar-collapsed'), null);
  navToggle.click();
  assert.equal(layoutChanges, 1, 'Reopening an already expanded source must not toggle the desktop layout');
  const navNewSession = navigation.querySelector('button[data-dafeiyu-mobile-control="new-session"]');
  assert.equal(navNewSession.querySelector('svg').getAttribute('data-fixture-icon'), 'real-new-session');
  navNewSession.click();
  assert.equal(newSessions, 0, 'The top action opens the menu before forwarding a business action');
  const quick = fixture.document.querySelector('[data-dafeiyu-mobile-layout="quick-actions"]');
  assert.equal(quick.getAttribute('aria-hidden'), 'false');
  quick.querySelector('[data-dafeiyu-mobile-control="quick-new-session"]').click();
  assert.equal(newSessions, 1);
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-drawer'), 'closed');
  assert.equal(layoutChanges, 1);
  dispose();
  assertInactive(fixture);
  assert.equal(fixture.listenerCount(), 2, 'Only the two original sidebar action listeners survive');
});

test('optional footer factories stay inert on PC and receive only active layout lifecycle updates', () => {
  for (const options of [{}, { width: 390 }, { width: 390, userAgent: 'Mozilla/5.0 (Linux; Android 16) Mobile' },
    { host: validHost({ version: 2 }) }]) {
    const fixture = browser(options);
    nativeShell(fixture);
    const dispose = createDafeiyuMobileContext(fixture.env).install(cssText, undefined, {
      createFooter() { assert.fail('An unvalidated page must never invoke the footer factory'); },
    });
    fixture.mutation();
    assertInactive(fixture);
    dispose();
    assert.equal(fixture.listenerCount(), 0);
  }

  const fixture = browser({ host: validHost() });
  const shell = nativeShell(fixture);
  const instances = [];
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText, undefined, {
    createFooter(env, options) {
      assert.equal(env, fixture.env);
      assert.equal(typeof options.closeDrawer, 'function');
      const instance = { updates: [], disposals: 0 };
      instances.push(instance);
      return { update(parts) { instance.updates.push({ ...parts }); }, dispose() { instance.disposals++; } };
    },
  });
  assert.equal(instances.length, 1);
  assert.deepEqual(instances[0].updates.at(-1), {
    frame: shell.frame, sidebar: shell.sidebarColumn, center: shell.center, rightbar: shell.rightbarColumn,
  });
  fixture.env.__DAFEIYU_DSH_CLIENT__ = validHost({ mode: 'online', theme: 'dark' });
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-client-change' });
  assert.equal(instances.length, 1, 'Appearance changes update the existing instance');
  const updatesBeforeExit = instances[0].updates.length;
  fixture.mutation(false);
  fixture.env.__DAFEIYU_DSH_CLIENT__ = undefined;
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-client-change' });
  fixture.flush();
  assert.equal(instances[0].disposals, 1);
  assert.equal(instances[0].updates.length, updatesBeforeExit, 'Queued work cannot update a disposed footer');
  assertInactive(fixture);
  fixture.env.__DAFEIYU_DSH_CLIENT__ = validHost();
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-client-change' });
  assert.equal(instances.length, 2, 'Reactivation creates a fresh instance');
  assert.equal(instances[1].updates.length, 1);
  dispose();
  dispose();
  fixture.flush();
  assert.deepEqual(instances.map(instance => instance.disposals), [1, 1]);
  assertInactive(fixture);
  assert.equal(fixture.listenerCount(), 0);
});

test('the footer forwards real DSH settings and keeps App settings reserved in a URL-only preview', () => {
  const fixture = browser({ href: 'http://127.0.0.1:8787/?dafeiyu-preview=mobile' });
  const shell = nativeShell(fixture);
  let settingsCalls = 0;
  let appCalls = 0;
  let connectionCalls = 0;
  shell.settings.addEventListener('click', () => { settingsCalls++; });
  fixture.env.__DAFEIYU_DSH_HOST__ = {
    version: 1, platform: 'android', capabilities: ['app.settings.open', 'connection.settings.open'],
    openAppSettings() { appCalls++; },
    openConnectionSettings() { connectionCalls++; },
  };
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText, undefined, {
    createFooter: createDafeiyuMobileFooter,
  });
  const footer = fixture.document.querySelector('[data-dafeiyu-mobile-layout="mobile-footer"]');
  assert.ok(footer);
  const app = footer.querySelector('[data-dafeiyu-mobile-control="app-settings"]');
  const dsh = footer.querySelector('[data-dafeiyu-mobile-control="dsh-settings"]');
  assert.equal(app.disabled, true);
  assert.match(app.textContent, /reserved/i);
  app.click();
  assert.equal(appCalls, 0);
  const presence = footer.querySelector('[data-dafeiyu-mobile-control="connection-settings"]');
  assert.equal(presence.disabled, true);
  presence.click();
  assert.equal(connectionCalls, 0, 'A URL-only preview cannot invoke the native connection workflow');
  assert.equal(footer.querySelector('strong').textContent, 'Unknown');
  const navToggle = fixture.document.querySelector('[data-dafeiyu-mobile-control="sidebar-toggle"]');
  navToggle.click();
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-drawer'), 'open');
  dsh.click();
  assert.equal(settingsCalls, 1);
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-drawer'), 'closed');
  shell.settings.setAttribute('aria-disabled', 'true');
  fixture.mutation(true, [{ type: 'attributes', target: shell.settings, attributeName: 'aria-disabled' }]);
  assert.equal(dsh.disabled, true);
  dsh.click();
  assert.equal(settingsCalls, 1);
  dispose();
  assert.equal(footer.isConnected, false);
  assert.equal(fixture.listenerCount(), 1, 'The native settings receiver survives footer disposal');
  assertInactive(fixture);
});

test('footer presence never guesses a roster and a late initial snapshot cannot overwrite a live update', async () => {
  const fixture = browser({ host: validHost({ mode: 'online' }) });
  fixture.document.documentElement.setAttribute('lang', 'zh-CN');
  const shell = nativeShell(fixture);
  let resolveInitial;
  let receive;
  let hostCleanups = 0;
  let connectionCleanups = 0;
  let appCalls = 0;
  let connectionCalls = 0;
  fixture.env.__DAFEIYU_DSH_HOST__ = {
    version: 1, platform: 'android', capabilities: ['app.settings.open', 'connection.state', 'connection.settings.open'],
    getConnectionState() { return new Promise(resolve => { resolveInitial = resolve; }); },
    subscribeConnectionState(callback) { receive = callback; return () => { hostCleanups++; }; },
    openAppSettings() { appCalls++; },
    openConnectionSettings(...args) { assert.deepEqual(args, []); connectionCalls++; },
  };
  const connection = { state: {
    getSnapshot() { return 'connected'; },
    subscribe() { return () => { connectionCleanups++; }; },
  } };
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText, undefined, {
    createFooter: (env, options) => createDafeiyuMobileFooter(env, { ...options, connection }),
  });
  const footer = fixture.document.querySelector('[data-dafeiyu-mobile-layout="mobile-footer"]');
  const count = footer.querySelector('strong');
  const detail = footer.querySelector('[role="status"]');
  const state = {
    protocol: 'dafeiyu-go.connection.v1', server_connected: true, pairing_status: 'paired', group_id: 'offline-test-group',
    capabilities: { presence: true }, remote_device: { id: 'pc-fixture', name: '电脑', online: 'online' },
  };
  receive(state);
  assert.equal(count.textContent, '未知', 'One peer does not establish the whole group count');
  assert.match(detail.textContent, /Aries 已连接 · 电脑 · 在线/u);
  receive({ ...state, members: [
    { id: 'android-fixture', active: true, online: 'online' }, { id: 'pc-fixture', active: true, online: 'online' },
    { id: 'inactive-fixture', active: false, online: 'unknown' },
  ] });
  assert.equal(count.textContent, '2');
  resolveInitial({ ...state, server_connected: false, members: [] });
  await Promise.resolve();
  assert.equal(count.textContent, '2');
  assert.match(detail.textContent, /Aries 已连接/u);
  receive({ ...state, remote_device: { id: 'pc-fixture', online: 'unknown' }, members: [
    { id: 'android-fixture', active: true, online: 'online' }, { id: 'pc-fixture', active: true, online: 'unknown' },
  ] });
  assert.equal(count.textContent, '未知');
  assert.match(detail.textContent, /对方尚未共享设备名称 · 状态未知/u);
  receive({ ...state, members: [
    { id: 'pc-fixture', active: true, online: 'online' }, { id: 'pc-fixture', active: true, online: 'online' },
  ] });
  assert.equal(count.textContent, '未知', 'Duplicate member IDs cannot inflate an online count');
  const app = footer.querySelector('[data-dafeiyu-mobile-control="app-settings"]');
  assert.equal(app.disabled, false);
  app.click();
  assert.equal(appCalls, 1);
  const presence = footer.querySelector('[data-dafeiyu-mobile-control="connection-settings"]');
  assert.equal(presence.disabled, false);
  fixture.document.querySelector('[data-dafeiyu-mobile-control="sidebar-toggle"]').click();
  const beforeConnection = { count: count.textContent, detail: detail.textContent };
  presence.click();
  assert.equal(connectionCalls, 1);
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-drawer'), 'closed');
  assert.deepEqual({ count: count.textContent, detail: detail.textContent }, beforeConnection,
    'Opening native connection settings does not manufacture a pairing or presence result');
  fixture.env.__DAFEIYU_DSH_HOST__.capabilities = ['app.settings.open', 'connection.state'];
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-host-change' });
  assert.equal(presence.disabled, true);
  presence.click();
  assert.equal(connectionCalls, 1);
  const beforeDispose = footer.outerHTML;
  dispose();
  receive({ ...state, members: [] });
  await Promise.resolve();
  assert.equal(footer.isConnected, false);
  assert.equal(footer.outerHTML, beforeDispose, 'A retained callback cannot repaint a disposed footer');
  assert.equal(hostCleanups, 1);
  assert.equal(connectionCleanups, 1);
  assertInactive(fixture);
  assert.equal(fixture.listenerCount(), 0);
  assert.equal(shell.settings.isConnected, true);
});

test('replacing a footer host releases its subscription and pending results stay inert after disposal', async () => {
  const fixture = browser({ host: validHost({ mode: 'online' }) });
  nativeShell(fixture);
  const requests = [];
  const cleanupCounts = [0, 0];
  const bridge = index => ({
    version: 1, platform: 'android', capabilities: ['connection.state'],
    getConnectionState() { return new Promise(resolve => { requests[index] = { resolve }; }); },
    subscribeConnectionState(callback) {
      requests[index].receive = callback;
      return () => { cleanupCounts[index]++; };
    },
  });
  fixture.env.__DAFEIYU_DSH_HOST__ = bridge(0);
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText, undefined, {
    createFooter: createDafeiyuMobileFooter,
  });
  const footer = fixture.document.querySelector('[data-dafeiyu-mobile-layout="mobile-footer"]');
  const count = footer.querySelector('strong');
  const detail = footer.querySelector('[role="status"]');
  const connected = {
    protocol: 'dafeiyu-go.connection.v1', server_connected: true, pairing_status: 'paired', group_id: 'late-fixture',
    capabilities: { presence: true }, remote_device: { id: 'pc-fixture', name: 'New host', online: 'online' },
    members: [{ id: 'pc-fixture', active: true, online: 'online' }],
  };
  fixture.env.__DAFEIYU_DSH_HOST__ = bridge(1);
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-host-change' });
  assert.deepEqual(cleanupCounts, [1, 0]);
  requests[1].receive(connected);
  const liveDetail = detail.textContent;
  assert.equal(count.textContent, '1');
  requests[0].resolve({ ...connected, server_connected: false });
  requests[0].receive({ ...connected, server_connected: false });
  await Promise.resolve();
  assert.equal(detail.textContent, liveDetail, 'An old host cannot overwrite the replacement bridge');
  const beforeDispose = footer.outerHTML;
  dispose();
  requests[1].resolve({ ...connected, members: [] });
  requests[1].receive({ ...connected, members: [] });
  await Promise.resolve();
  assert.deepEqual(cleanupCounts, [1, 1]);
  assert.equal(footer.outerHTML, beforeDispose);
  assert.equal(footer.isConnected, false);
  assert.equal(fixture.listenerCount(), 0);
  assertInactive(fixture);
});

test('the activity hub forwards official sources, closes real menus and protects its own category pointerdown', () => {
  const fixture = browser({ host: validHost() });
  const shell = nativeShell(fixture);
  const native = nativeActivity(fixture, shell);
  let documentPointerdowns = 0;
  fixture.document.addEventListener('pointerdown', () => { documentPointerdowns++; });
  const originalListeners = fixture.listenerCount();
  const rootBefore = attrs(fixture.document.documentElement);
  const bodyBefore = attrs(fixture.document.body);
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  const navigation = fixture.document.querySelector('[data-dafeiyu-mobile-layout="navigation"]');
  assert.deepEqual(navigation.children.map(child => child.getAttribute('data-dafeiyu-mobile-control')),
    ['sidebar-toggle', null, 'activity-hub', 'new-session']);
  const hub = navigation.querySelector('[data-dafeiyu-mobile-control="activity-hub"]');
  const panel = fixture.document.querySelector('[data-dafeiyu-mobile-layout="activity-panel"]');
  const agentsTab = panel.querySelector('[data-dafeiyu-mobile-control="activity-agents"]');
  const jobsTab = panel.querySelector('[data-dafeiyu-mobile-control="activity-jobs"]');
  const close = panel.querySelector('[data-dafeiyu-mobile-control="close"]');
  assert.equal(panel.parentElement, fixture.document.body);
  assert.equal(panel.hidden, true);
  assert.equal(hub.disabled, false);
  assert.equal(hub.querySelector('svg').getAttribute('data-fixture-icon'), 'native-team');
  assert.equal(native.agentSource.getAttribute('data-dafeiyu-mobile-control'), 'activity-source-agents');
  assert.equal(native.jobsSource.getAttribute('data-dafeiyu-mobile-control'), 'activity-source-jobs');

  hub.click();
  fixture.flush();
  const firstAgentMenu = native.agentMenu;
  assert.equal(native.calls.agents, 1);
  assert.equal(panel.hidden, false);
  assert.equal(panel.getAttribute('aria-hidden'), 'false');
  assert.equal(hub.getAttribute('aria-expanded'), 'true');
  assert.equal(agentsTab.getAttribute('aria-selected'), 'true');
  assert.equal(firstAgentMenu.getAttribute('data-dafeiyu-mobile-layout'), 'activity-native-agents');
  assert.equal(firstAgentMenu.parentElement, fixture.document.body, 'The native portal retains DOM ownership');
  assert.equal(firstAgentMenu.querySelector('button').getAttribute('data-dafeiyu-mobile-control'), null);
  assert.equal(native.agentSource.parentElement, shell.actions);

  const ownPointer = new fixture.env.MouseEvent('pointerdown', { bubbles: true, cancelable: true });
  jobsTab.dispatchEvent(ownPointer);
  assert.equal(documentPointerdowns, 0, 'Own categories do not trigger the native document dismissal listener');
  assert.equal(ownPointer.defaultPrevented, false, 'The category keeps its normal pointer default behavior');
  jobsTab.click();
  fixture.flush();
  const firstJobsMenu = native.jobsMenu;
  assert.equal(native.calls.agentEscapes, 1);
  assert.equal(firstAgentMenu.isConnected, false);
  assert.equal(firstAgentMenu.getAttribute('data-dafeiyu-mobile-layout'), null);
  assert.equal(native.calls.jobs, 1);
  assert.equal(jobsTab.getAttribute('aria-selected'), 'true');
  assert.equal(firstJobsMenu.getAttribute('data-dafeiyu-mobile-layout'), 'activity-native-jobs');
  assert.equal(firstJobsMenu.parentElement, native.jobsRoot);
  assert.equal(firstJobsMenu.querySelector('button').getAttribute('data-dafeiyu-mobile-control'), null,
    'Native task controls must not inherit header action styling');
  assert.equal(native.jobsRoot.getAttribute('data-dafeiyu-activity-ancestor'), 'true');
  assert.equal(shell.frame.getAttribute('data-dafeiyu-mobile-layout'), 'frame', 'The open popover preserves other layout roles');
  agentsTab.click();
  fixture.flush();
  assert.equal(native.calls.jobs, 2, 'Leaving jobs invokes its real close toggle');
  assert.equal(firstJobsMenu.isConnected, false);
  assert.equal(firstJobsMenu.getAttribute('data-dafeiyu-mobile-layout'), null);
  assert.equal(native.jobsRoot.getAttribute('data-dafeiyu-activity-ancestor'), null);
  assert.equal(native.calls.agents, 2);

  fixture.document.dispatchEvent(new fixture.env.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
  fixture.flush();
  assert.equal(native.calls.agentEscapes, 2, 'Escape reaches the real native portal callback once');
  assert.equal(panel.hidden, true);
  assert.equal(hub.getAttribute('aria-expanded'), 'false');
  assert.equal(fixture.document.activeElement, hub);
  hub.click();
  fixture.flush();
  const outside = fixture.create('button', { 'aria-label': 'Unrelated host action' });
  outside.dispatchEvent(new fixture.env.MouseEvent('pointerdown', { bubbles: true, cancelable: true }));
  fixture.flush();
  assert.equal(panel.hidden, true);
  assert.equal(native.calls.agentEscapes, 3);
  assert.equal(documentPointerdowns, 1, 'Outside pointer events keep the native document behavior');
  hub.click();
  fixture.flush();
  close.click();
  fixture.flush();
  assert.equal(native.calls.agentEscapes, 4);
  assert.equal(panel.hidden, true);
  hub.click();
  fixture.flush();
  fixture.mutation(false);
  dispose();
  dispose();
  fixture.flush();
  assert.equal(native.calls.agentEscapes, 5, 'Disposal closes the still-open official menu');
  const callsAtExit = { ...native.calls };
  hub.click(); agentsTab.click(); jobsTab.click(); close.click();
  fixture.flush();
  assert.deepEqual(native.calls, callsAtExit, 'Detached owned controls cannot invoke original sources');
  assert.equal(panel.isConnected, false);
  assert.equal(navigation.isConnected, false);
  assert.equal(fixture.listenerCount(), originalListeners);
  assert.deepEqual(attrs(fixture.document.documentElement), rootBefore);
  assert.deepEqual(attrs(fixture.document.body), bodyBefore);
  for (const original of [native.agentSource, native.jobsSource, native.teamRoot]) {
    assert.equal(original.getAttribute('data-dafeiyu-mobile-control'), null);
  }
  assert.equal(fixture.document.querySelectorAll('[data-dafeiyu-activity-ancestor]').length, 0);
  assertInactive(fixture);
});

test('touch session archive needs two independent left gestures and forwards its native callback once', () => {
  const fixture = sidebarTouchFixture();
  const { rows } = fixture;
  assert.equal(rows.sessionRow.getAttribute('data-dafeiyu-touch-row'), 'session');
  assert.equal(rows.workRow.getAttribute('data-dafeiyu-touch-row'), 'workspace');
  assert.equal(fixture.shell.newSession.getAttribute('data-dafeiyu-mobile-control'), 'sidebar-new-session-source');
  assert.equal(rows.sessionRow.getAttribute('data-dafeiyu-swipe-state'), 'closed');
  const move = swipeLeft(fixture, rows.sessionRow, { distance: 220 });
  assert.equal(move.defaultPrevented, true, 'Only an accepted horizontal row gesture consumes the native default');
  assert.equal(rows.sessionRow.getAttribute('data-dafeiyu-swipe-state'), 'open');
  assert.equal(rows.calls.archive, 0, 'Even one long first drag may only reveal the action');
  let action = fixture.document.querySelector('[data-dafeiyu-mobile-control="sidebar-archive"]');
  assert.ok(action);
  assert.equal(action.getAttribute('data-dafeiyu-mobile-layout'), 'sidebar-swipe-action');
  assert.equal(action.getAttribute('data-dafeiyu-mobile-control'), 'sidebar-archive');
  fixture.advance(450);
  swipeLeft(fixture, rows.sessionRow, { pointerId: 2, distance: 90 });
  assert.equal(rows.calls.archive, 1);
  assert.equal(rows.calls.restore, 0);
  assert.equal(rows.calls.sessionOpen, 0);
  userClick(fixture, rows.sessionRow);
  assert.equal(rows.calls.sessionOpen, 0, 'The synthesized click following a consumed swipe is not a session selection');
  touchPointer(fixture, rows.sessionRow, 'pointerup', { pointerId: 2, clientX: 150 });
  assert.equal(rows.calls.archive, 1, 'A duplicate terminal pointer event cannot repeat the native action');
  fixture.advance(1000);
  swipeLeft(fixture, rows.sessionRow, { pointerId: 3 });
  fixture.advance(450);
  action = fixture.document.querySelector('[data-dafeiyu-mobile-control="sidebar-archive"]');
  action.click(); fixture.flush();
  assert.equal(rows.calls.archive, 2, 'The exposed action also remains a working native archive entry');
  fixture.dispose(); fixture.flush();
  assert.equal(rows.sessionRow.getAttribute('data-dafeiyu-touch-row'), null);
  assert.equal(rows.sessionRow.getAttribute('data-dafeiyu-swipe-state'), null);
  assert.equal(action.isConnected, false);
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.listenerCount(), fixture.nativeListeners);
  assertInactive(fixture);
});

test('touch workspace delete forwards only its newly opened native menu and retains native confirmation', () => {
  const fixture = sidebarTouchFixture();
  const { rows } = fixture;
  const unrelated = fixture.create('div', { role: 'menu' });
  const foreignRename = fixture.create('button', { role: 'menuitem' }, unrelated);
  foreignRename.textContent = 'Rename';
  const foreignDelete = fixture.create('button', { role: 'menuitem' }, unrelated);
  foreignDelete.textContent = 'Delete workspace';
  let wrongDeletes = 0;
  const wrongDelete = () => { wrongDeletes++; };
  foreignDelete.addEventListener('click', wrongDelete);
  fixture.flush();
  swipeLeft(fixture, rows.workRow, { distance: 220, y: 145 });
  assert.equal(rows.workRow.getAttribute('data-dafeiyu-swipe-state'), 'open');
  const action = fixture.document.querySelector('[data-dafeiyu-mobile-control="sidebar-delete"]');
  assert.ok(action);
  assert.equal(rows.calls.workMore, 0);
  assert.equal(rows.calls.deleteConfirmation, 0);
  fixture.advance(450);
  swipeLeft(fixture, rows.workRow, { pointerId: 2, distance: 90, y: 145 });
  fixture.flush();
  assert.equal(rows.calls.workMore, 1, 'The real workspace owner supplies its actual delete flow');
  assert.equal(rows.calls.deleteConfirmation, 1, 'Only the delete menu item is forwarded; it still asks for native confirmation');
  assert.equal(rows.calls.fileDelete, 0, 'No confirmation or disk deletion is fabricated by the touch adapter');
  assert.equal(rows.calls.archive, 0);
  assert.equal(rows.calls.workToggle, 0);
  assert.equal(wrongDeletes, 0, 'An existing unrelated portal with identical text cannot be selected');
  fixture.dispose(); fixture.flush();
  foreignDelete.removeEventListener('click', wrongDelete);
  assert.equal(fixture.listenerCount(), fixture.nativeListeners);
  assert.equal(fixture.pendingTimers(), 0);
  assertInactive(fixture);
});

test('normal capture release after workspace swipe preserves a delayed native menu commit', () => {
  const fixture = sidebarTouchFixture({ deferWorkspaceMenu: true });
  const { rows } = fixture;
  swipeLeft(fixture, rows.workRow, { y: 145 }); fixture.advance(450);
  swipeLeft(fixture, rows.workRow, { pointerId: 2, y: 145 });
  assert.equal(rows.calls.workMore, 1);
  assert.equal(rows.calls.deleteConfirmation, 0, 'Native React can commit the menu after pointerup');
  touchPointer(fixture, rows.workRow, 'lostpointercapture', { pointerId: 2 });
  rows.commitWorkspaceMenu(); fixture.flush();
  assert.equal(rows.calls.deleteConfirmation, 1, 'Normal platform capture release must preserve the queued native delete menu forwarding');
  assert.equal(rows.calls.fileDelete, 0);
  fixture.advance(1000);
  touchPointer(fixture, rows.sessionRow, 'pointerdown', { pointerId: 3 });
  touchPointer(fixture, rows.sessionRow, 'pointermove', { pointerId: 3, clientX: 110 });
  touchPointer(fixture, rows.sessionRow, 'lostpointercapture', { pointerId: 3 });
  touchPointer(fixture, rows.sessionRow, 'pointerup', { pointerId: 3, clientX: 110 });
  fixture.advance(1000);
  assert.equal(rows.sessionRow.getAttribute('data-dafeiyu-swipe-state'), 'closed');
  assert.equal(rows.calls.archive, 0, 'Losing capture during an active gesture cancels its action');
  fixture.dispose(); fixture.flush();
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.listenerCount(), fixture.nativeListeners);
  assertInactive(fixture);
});

test('queued workspace menu forwarding cannot outlive its row, timeout, mobile gate or owner', () => {
  for (const [name, interrupt] of [
    ['row identity', fixture => fixture.rows.workRow.setAttribute('data-row-key', 'workspace:different')],
    ['timeout', fixture => fixture.advance(801)],
    ['mobile gate', fixture => {
      fixture.env.__DAFEIYU_DSH_CLIENT__ = undefined;
      fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-client-change' });
    }],
    ['owner disposal', fixture => fixture.dispose()],
  ]) {
    const fixture = sidebarTouchFixture({ deferWorkspaceMenu: true });
    swipeLeft(fixture, fixture.rows.workRow, { y: 145 }); fixture.advance(450);
    swipeLeft(fixture, fixture.rows.workRow, { pointerId: 2, y: 145 });
    assert.equal(fixture.rows.calls.workMore, 1);
    interrupt(fixture); fixture.flush();
    fixture.rows.commitWorkspaceMenu(); fixture.flush(); fixture.advance(1000);
    assert.equal(fixture.rows.calls.deleteConfirmation, 0, `A late native portal after ${name} cannot be selected`);
    assert.equal(fixture.rows.calls.fileDelete, 0);
    fixture.rows.closeMenu(); fixture.flush();
    fixture.dispose(); fixture.flush();
    assert.equal(fixture.pendingTimers(), 0);
    assert.equal(fixture.listenerCount(), fixture.nativeListeners);
    assertInactive(fixture);
  }
});

test('touch capture transfers from a native row descendant without cancelling a valid horizontal gesture', () => {
  const fixture = sidebarTouchFixture();
  const { rows } = fixture;
  const captureTrace = [];
  const trace = event => { captureTrace.push([event.type, event.target, event.pointerId]); };
  fixture.document.addEventListener('lostpointercapture', trace, true);
  touchPointer(fixture, rows.sessionTime, 'pointerdown');
  assert.equal(rows.sessionTime.hasPointerCapture(1), true);
  touchPointer(fixture, rows.sessionTime, 'pointermove', { clientX: 140 });
  assert.ok(captureTrace.some(([type, target, id]) => type === 'lostpointercapture' && target === rows.sessionTime && id === 1),
    'Moving from the implicitly captured span to the row produces the real platform transfer event');
  assert.equal(rows.sessionRow.hasPointerCapture(1), true, 'The accepted horizontal gesture owns row capture');
  touchPointer(fixture, rows.sessionTime, 'pointerup', { clientX: 140 });
  assert.equal(rows.sessionRow.getAttribute('data-dafeiyu-swipe-state'), 'open');
  assert.equal(rows.calls.archive, 0);
  assert.equal(fixture.document.pointerCaptures.size, 0, 'Normal pointerup releases the row capture');
  fixture.advance(450);
  touchPointer(fixture, rows.sessionTitle, 'pointerdown', { pointerId: 2 });
  touchPointer(fixture, rows.sessionTitle, 'pointermove', { pointerId: 2, clientX: 140 });
  touchPointer(fixture, rows.sessionTitle, 'pointerup', { pointerId: 2, clientX: 140 });
  assert.equal(rows.calls.archive, 1, 'The independent second swipe survives its own descendant capture transfer');
  userClick(fixture, rows.sessionTitle);
  assert.equal(rows.calls.sessionOpen, 0);
  assert.equal(fixture.document.pointerCaptures.size, 0);
  fixture.document.removeEventListener('lostpointercapture', trace, true);
  fixture.dispose(); fixture.flush();
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.listenerCount(), fixture.nativeListeners);
  assertInactive(fixture);
});

test('touch long press opens official rename and pin menus without performing a destructive action', () => {
  const fixture = sidebarTouchFixture();
  const { rows } = fixture;
  touchPointer(fixture, rows.sessionRow, 'pointerdown');
  fixture.advance(479);
  assert.equal(rows.calls.sessionMore, 0);
  fixture.advance(1);
  assert.equal(rows.calls.sessionMore, 1);
  assert.ok(rows.menu);
  assert.equal(rows.calls.archive, 0);
  assert.equal(rows.calls.pin, 0);
  touchPointer(fixture, rows.sessionRow, 'pointerup');
  userClick(fixture, rows.sessionRow);
  assert.equal(rows.calls.sessionOpen, 0);
  rows.menu.querySelectorAll('button[role="menuitem"]').find(item => item.textContent === 'Pin session').click();
  fixture.flush();
  assert.equal(rows.calls.pin, 1);
  fixture.advance(1000);
  touchPointer(fixture, rows.sessionRow, 'pointerdown', { pointerId: 2 }); fixture.advance(480);
  touchPointer(fixture, rows.sessionRow, 'pointerup', { pointerId: 2 });
  rows.menu.querySelectorAll('button[role="menuitem"]').find(item => item.textContent === 'Rename').click();
  fixture.flush();
  assert.equal(rows.calls.renameSession, 1);
  fixture.advance(1000);
  touchPointer(fixture, rows.workRow, 'pointerdown', { pointerId: 3, clientY: 145 }); fixture.advance(480);
  touchPointer(fixture, rows.workRow, 'pointerup', { pointerId: 3, clientY: 145 });
  assert.equal(rows.calls.workMore, 1);
  rows.menu.querySelectorAll('button[role="menuitem"]').find(item => item.textContent === 'Rename').click();
  fixture.flush();
  assert.equal(rows.calls.renameWork, 1);
  assert.equal(rows.calls.deleteConfirmation, 0);
  assert.equal(rows.calls.workToggle, 0);
  fixture.dispose(); fixture.flush();
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.listenerCount(), fixture.nativeListeners);
  assertInactive(fixture);
});

test('touch rows preserve vertical scrolling, ordinary taps, native controls and mouse behavior', () => {
  const fixture = sidebarTouchFixture();
  const { rows } = fixture;
  touchPointer(fixture, rows.sessionRow, 'pointerdown');
  const vertical = touchPointer(fixture, rows.sessionRow, 'pointermove', { clientX: 236, clientY: 240 });
  touchPointer(fixture, rows.sessionRow, 'pointerup', { clientX: 236, clientY: 240 });
  assert.equal(vertical.defaultPrevented, false, 'Vertical movement keeps normal browser scrolling');
  assert.equal(rows.sessionRow.getAttribute('data-dafeiyu-swipe-state'), 'closed');
  fixture.advance(500);
  assert.equal(rows.calls.sessionMore, 0, 'Scrolling cancels the long-press timer');
  touchPointer(fixture, rows.sessionRow, 'pointerdown', { pointerId: 2 });
  touchPointer(fixture, rows.sessionRow, 'pointerup', { pointerId: 2 });
  userClick(fixture, rows.sessionRow);
  assert.equal(rows.calls.sessionOpen, 1, 'An ordinary tap keeps the native row callback');
  fixture.toggle.click(); fixture.flush();
  touchPointer(fixture, rows.sessionRow, 'pointerdown', { pointerId: 3, pointerType: 'mouse' });
  fixture.advance(600);
  const mouseMove = touchPointer(fixture, rows.sessionRow, 'pointermove', { pointerId: 3, pointerType: 'mouse', clientX: 80 });
  touchPointer(fixture, rows.sessionRow, 'pointerup', { pointerId: 3, pointerType: 'mouse', clientX: 80 });
  assert.equal(mouseMove.defaultPrevented, false);
  assert.equal(rows.calls.sessionMore, 0);
  assert.equal(rows.calls.archive, 0);
  touchPointer(fixture, rows.archive, 'pointerdown', { pointerId: 4 }); fixture.advance(600);
  touchPointer(fixture, rows.archive, 'pointerup', { pointerId: 4 });
  rows.archive.click(); fixture.flush();
  assert.equal(rows.calls.archive, 1, 'Existing native icon controls retain their own interaction');
  assert.equal(rows.calls.sessionMore, 0);
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-drawer'), 'open',
    'A native session action must not also dismiss the drawer');
  fixture.dispose(); fixture.flush();
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.listenerCount(), fixture.nativeListeners);
});

test('native menu and modal Escape handling takes precedence over closing the mobile drawer', () => {
  const fixture = sidebarTouchFixture();
  const drawer = () => fixture.document.documentElement.getAttribute('data-dafeiyu-drawer');
  const escape = target => {
    const event = new fixture.env.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    target.dispatchEvent(event); fixture.flush(); return event;
  };
  assert.equal(drawer(), 'open');
  fixture.rows.sessionMore.click(); fixture.flush();
  assert.equal(drawer(), 'open');
  const nativeEscape = escape(fixture.rows.menu);
  assert.equal(nativeEscape.defaultPrevented, true);
  assert.equal(fixture.rows.menu, null);
  assert.equal(drawer(), 'open', 'The native menu consumes its Escape before the drawer');

  const alreadyConsumed = new fixture.env.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
  alreadyConsumed.preventDefault();
  fixture.document.dispatchEvent(alreadyConsumed); fixture.flush();
  assert.equal(drawer(), 'open');

  const lateMenu = fixture.create('div', { role: 'menu' }); fixture.flush();
  const lateDismiss = event => { if (event.key === 'Escape') lateMenu.remove(); };
  fixture.document.addEventListener('keydown', lateDismiss);
  escape(fixture.document);
  assert.equal(lateMenu.isConnected, false);
  assert.equal(drawer(), 'open', 'A native listener registered after the adapter still owns the first Escape');
  fixture.document.removeEventListener('keydown', lateDismiss);
  const modal = fixture.create('section', { role: 'dialog', 'aria-modal': 'true' }); fixture.flush();
  escape(fixture.document);
  assert.equal(drawer(), 'open', 'An active native confirmation keeps the drawer underneath it');
  modal.hidden = true; fixture.flush();
  escape(fixture.document);
  assert.equal(drawer(), 'closed', 'The next unconsumed Escape closes the drawer once native overlays are gone');
  fixture.dispose(); fixture.flush();
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.listenerCount(), fixture.nativeListeners);
  assertInactive(fixture);
});

test('touch gesture cancellation, multitouch and drawer closure leave no armed action or long-press work', () => {
  const fixture = sidebarTouchFixture();
  const { rows } = fixture;
  const closed = () => {
    assert.equal(rows.sessionRow.getAttribute('data-dafeiyu-swipe-state'), 'closed');
    const action = fixture.document.querySelector('[data-dafeiyu-mobile-control="sidebar-archive"]');
    assert.ok(!action || action.disabled && action.inert && action.getAttribute('aria-hidden') === 'true');
    assert.equal(rows.calls.archive, 0);
    assert.equal(rows.calls.sessionMore, 0);
  };
  touchPointer(fixture, rows.sessionRow, 'pointerdown');
  touchPointer(fixture, rows.sessionRow, 'pointercancel'); fixture.advance(500); closed();
  touchPointer(fixture, rows.sessionRow, 'pointerdown', { pointerId: 2 });
  touchPointer(fixture, rows.sessionRow, 'pointerdown', { pointerId: 3, isPrimary: false });
  touchPointer(fixture, rows.sessionRow, 'pointermove', { pointerId: 2, clientX: 100 });
  touchPointer(fixture, rows.sessionRow, 'pointerup', { pointerId: 2, clientX: 100 });
  touchPointer(fixture, rows.sessionRow, 'pointerup', { pointerId: 3, isPrimary: false });
  fixture.advance(500); closed();
  swipeLeft(fixture, rows.sessionRow, { pointerId: 4 });
  rows.region.dispatchEvent(new fixture.env.Event('scroll')); fixture.flush(); closed();
  touchPointer(fixture, rows.sessionRow, 'pointerdown', { pointerId: 5 });
  fixture.env.dispatchEvent({ type: 'blur' }); fixture.flush(); fixture.advance(500); closed();
  swipeLeft(fixture, rows.sessionRow, { pointerId: 6 });
  fixture.toggle.click(); fixture.flush(); closed();
  fixture.advance(1000);
  assert.equal(fixture.pendingTimers(), 0);
  fixture.dispose(); fixture.flush();
  assert.equal(fixture.listenerCount(), fixture.nativeListeners);
  assertInactive(fixture);
});

test('touch gesture state cannot cross row identity changes, tree replacement, gate loss or disposal', () => {
  const fixture = sidebarTouchFixture();
  const { rows } = fixture;
  swipeLeft(fixture, rows.sessionRow);
  rows.sessionRow.setAttribute('data-row-key', 'session:replacement'); fixture.flush();
  assert.equal(rows.sessionRow.getAttribute('data-dafeiyu-swipe-state'), 'closed');
  swipeLeft(fixture, rows.sessionRow, { pointerId: 2 });
  assert.equal(rows.calls.archive, 0, 'A reused DOM row starts with a fresh first swipe');
  rows.slot.remove(); fixture.flush();
  const oldAction = fixture.document.querySelector('[data-dafeiyu-mobile-control="sidebar-archive"]');
  assert.ok(!oldAction || oldAction.disabled && oldAction.inert);
  assert.equal(rows.sessionRow.getAttribute('data-dafeiyu-touch-row'), null);
  assert.equal(rows.sessionRow.getAttribute('data-dafeiyu-swipe-state'), null,
    'A removed native row must release all owned presentation state');
  assert.equal(rows.sessionRow.style.getPropertyValue('--df-sidebar-swipe-x'), '',
    'A removed native row must restore its original inline transform input');
  const next = nativeSidebarRows(fixture, fixture.shell, { suffix: 'two' }); fixture.flush();
  swipeLeft(fixture, next.sessionRow, { pointerId: 3 });
  assert.equal(next.calls.archive, 0);
  fixture.env.__DAFEIYU_DSH_CLIENT__ = undefined;
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-client-change' }); fixture.flush();
  assert.equal(next.sessionRow.getAttribute('data-dafeiyu-touch-row'), null);
  assert.equal(next.sessionRow.getAttribute('data-dafeiyu-swipe-state'), null);
  assert.equal(fixture.pendingTimers(), 0);
  assertInactive(fixture);
  fixture.env.__DAFEIYU_DSH_CLIENT__ = validHost();
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-client-change' }); fixture.flush();
  fixture.document.querySelector('[data-dafeiyu-mobile-control="sidebar-toggle"]').click(); fixture.flush();
  swipeLeft(fixture, next.sessionRow, { pointerId: 4 });
  assert.equal(next.calls.archive, 0, 'Reentry cannot inherit an earlier armed swipe');
  touchPointer(fixture, next.sessionRow, 'pointerdown', { pointerId: 5 });
  fixture.dispose(); fixture.advance(60_000);
  touchPointer(fixture, next.sessionRow, 'pointerup', { pointerId: 5 });
  assert.equal(next.calls.sessionMore, 0);
  assert.equal(next.calls.archive, 0);
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.listenerCount(), fixture.nativeListeners * 2, 'Only both original native row receiver sets remain');
  assertInactive(fixture);
});

test('touch enhancements fail closed on ordinary desktop pages and unknown extension rows', () => {
  const desktop = sidebarTouchFixture({ host: undefined, width: 390 });
  swipeLeft(desktop, desktop.rows.sessionRow);
  swipeLeft(desktop, desktop.rows.sessionRow, { pointerId: 2 });
  touchPointer(desktop, desktop.rows.workRow, 'pointerdown', { pointerId: 3 }); desktop.advance(1000);
  assert.equal(desktop.rows.sessionRow.getAttribute('data-dafeiyu-touch-row'), null);
  assert.equal(desktop.rows.workRow.getAttribute('data-dafeiyu-touch-row'), null);
  assert.equal(desktop.rows.calls.archive, 0);
  assert.equal(desktop.rows.calls.workMore, 0);
  assert.equal(desktop.pendingTimers(), 0);
  desktop.dispose(); assert.equal(desktop.listenerCount(), desktop.nativeListeners); assertInactive(desktop);

  const fixture = sidebarTouchFixture();
  const { rows } = fixture;
  rows.sessionMore.setAttribute('aria-label', 'Extension session operation'); fixture.flush();
  swipeLeft(fixture, rows.sessionRow); swipeLeft(fixture, rows.sessionRow, { pointerId: 2 });
  touchPointer(fixture, rows.sessionRow, 'pointerdown', { pointerId: 3 }); fixture.advance(600);
  touchPointer(fixture, rows.sessionRow, 'pointerup', { pointerId: 3 });
  assert.equal(rows.sessionRow.getAttribute('data-dafeiyu-touch-row'), null);
  assert.equal(rows.calls.archive, 0);
  assert.equal(rows.calls.sessionMore, 0);
  const outside = nativeSidebarRows(fixture, fixture.shell, { suffix: 'outside' });
  fixture.document.body.appendChild(outside.slot); fixture.flush();
  swipeLeft(fixture, outside.sessionRow); swipeLeft(fixture, outside.sessionRow, { pointerId: 4 });
  assert.equal(outside.sessionRow.getAttribute('data-dafeiyu-touch-row'), null);
  assert.equal(outside.calls.archive, 0);
  fixture.dispose(); fixture.flush();
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.listenerCount(), fixture.nativeListeners * 2);
  assertInactive(fixture);
});

test('activity hub occupies no header seat without current-session records and retains completed history', () => {
  const fixture = browser({ host: validHost(), autoMutations: true });
  const shell = nativeShell(fixture);
  const native = nativeActivity(fixture, shell, { agentLabel: 'Open subagent sessions', jobLabel: 'Open background jobs' });
  const unrelated = fixture.create('button', { class: 'ZKlsPq_trigger', 'aria-haspopup': 'tree',
    'aria-label': '4 subagents' }, shell.sidebarRoot);
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  fixture.flush();
  const navigation = fixture.document.querySelector('[data-dafeiyu-mobile-layout="navigation"]');
  const hub = navigation.querySelector('[data-dafeiyu-mobile-control="activity-hub"]');
  const panel = fixture.document.querySelector('[data-dafeiyu-mobile-layout="activity-panel"]');
  const assertEmpty = () => {
    assert.equal(navigation.getAttribute('data-dafeiyu-has-activity'), 'false');
    assert.equal(hub.hidden, true);
    assert.equal(hub.getAttribute('aria-hidden'), 'true');
    assert.equal(hub.inert, true);
    assert.equal(hub.disabled, true);
    assert.equal(panel.hidden, true);
  };
  assertEmpty();
  hub.click(); fixture.flush();
  assert.deepEqual(native.calls, { agents: 0, agentEscapes: 0, jobs: 0 });
  assert.equal(unrelated.getAttribute('data-dafeiyu-mobile-control'), null,
    'A count from another region is not current-session activity');
  native.agentSource.setAttribute('aria-label', '0 subagents');
  native.jobsSource.setAttribute('aria-label', '0 background jobs');
  fixture.flush(); assertEmpty();

  // No "running" suffix: retained historical records still expose the hub.
  for (const label of ['2 subagents', '2 个子智能体']) {
    native.agentSource.setAttribute('aria-label', label); fixture.flush();
    assert.equal(navigation.getAttribute('data-dafeiyu-has-activity'), 'true');
    assert.equal(hub.hidden, false);
    assert.equal(hub.getAttribute('aria-hidden'), 'false');
    assert.equal(hub.inert, false);
    hub.click(); fixture.flush();
    assert.equal(panel.hidden, false);
    assert.ok(native.agentMenu, 'Historical subagents open their real native catalog');
    native.agentSource.setAttribute('aria-label', '0 subagents'); fixture.flush();
    assertEmpty();
    assert.equal(native.agentMenu, null, 'Losing the final current-session record closes the real catalog');
  }
  for (const label of ['3 background jobs', '3 个后台任务']) {
    native.jobsSource.setAttribute('aria-label', label); fixture.flush();
    assert.equal(hub.hidden, false);
    hub.click(); fixture.flush();
    assert.ok(native.jobsMenu, 'Settled retained jobs open their real native list');
    native.jobsSource.setAttribute('aria-label', '0 background jobs'); fixture.flush();
    assertEmpty();
    assert.equal(native.jobsMenu, null);
  }
  for (const [source, label] of [[native.agentSource, '1 subagent running'], [native.agentSource, '1 个子智能体，正在运行'],
    [native.jobsSource, '1 background job running'], [native.jobsSource, '1 个后台任务运行中']]) {
    source.setAttribute('aria-label', label); fixture.flush();
    assert.equal(hub.hidden, false, `An actual running record remains available: ${label}`);
    source.setAttribute('aria-label', source === native.agentSource ? '0 subagents' : '0 background jobs');
    fixture.flush(); assertEmpty();
  }
  const settled = fixture.mutationStats(); fixture.flush();
  assert.deepEqual(fixture.mutationStats(), settled);
  dispose(); fixture.flush();
  assert.equal(fixture.listenerCount(), 4);
  assert.equal(fixture.pendingTimers(), 0);
  assertInactive(fixture);
});

test('missing, disabled and unrelated activity sources cannot open a synthetic task surface', () => {
  const fixture = browser({ host: validHost() });
  const shell = nativeShell(fixture);
  nativeActivity(fixture, shell, { agents: false, jobs: false });
  const unrelated = fixture.create('button', { 'aria-label': 'Subagent sessions', 'aria-haspopup': 'tree' }, shell.actions);
  let unrelatedCalls = 0;
  unrelated.addEventListener('click', () => { unrelatedCalls++; });
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  const hub = fixture.document.querySelector('[data-dafeiyu-mobile-control="activity-hub"]');
  const panel = fixture.document.querySelector('[data-dafeiyu-mobile-layout="activity-panel"]');
  const agentsTab = panel.querySelector('[data-dafeiyu-mobile-control="activity-agents"]');
  const jobsTab = panel.querySelector('[data-dafeiyu-mobile-control="activity-jobs"]');
  assert.equal(hub.disabled, true);
  assert.equal(agentsTab.disabled, true);
  assert.equal(jobsTab.disabled, true);
  hub.click(); agentsTab.click(); jobsTab.click();
  fixture.flush();
  assert.equal(panel.hidden, true);
  assert.equal(unrelatedCalls, 0, 'Similar labels and ARIA roles cannot hijack a third-party button');
  const native = nativeActivity(fixture, shell, { team: false });
  native.agentSource.disabled = true;
  native.jobsSource.setAttribute('aria-disabled', 'true');
  fixture.mutation();
  assert.equal(hub.disabled, true);
  native.agentSource.disabled = false;
  fixture.mutation(true, [{ type: 'attributes', target: native.agentSource, attributeName: 'disabled' }]);
  assert.equal(hub.disabled, false);
  assert.equal(agentsTab.disabled, false);
  assert.equal(jobsTab.disabled, true);
  hub.click();
  fixture.flush();
  assert.equal(native.calls.agents, 1);
  native.agentSource.disabled = true;
  fixture.mutation(true, [{ type: 'attributes', target: native.agentSource, attributeName: 'disabled' }]);
  assert.equal(hub.disabled, true);
  assert.equal(panel.hidden, true);
  assert.equal(native.agentMenu, null, 'Disabling the active source invokes its native Escape cleanup');
  native.jobsSource.removeAttribute('aria-disabled');
  fixture.mutation(true, [{ type: 'attributes', target: native.jobsSource, attributeName: 'aria-disabled' }]);
  hub.click();
  fixture.flush();
  assert.equal(native.calls.jobs, 1);
  native.jobsSource.disabled = true;
  fixture.mutation(true, [{ type: 'attributes', target: native.jobsSource, attributeName: 'disabled' }]);
  assert.equal(panel.hidden, true);
  assert.equal(native.jobsMenu, null, 'A disabled native jobs toggle still closes via its Escape callback');
  assert.equal(native.calls.jobs, 1, 'Closing a disabled source must not synthesize a click');
  dispose();
  assertInactive(fixture);
  assert.equal(fixture.listenerCount(), 5, 'Only the unrelated and four native receivers survive');
});

test('session changes, source replacement and whole-tree removal release activity state', () => {
  const fixture = browser({ host: validHost() });
  const first = nativeShell(fixture);
  const firstNative = nativeActivity(fixture, first);
  const selected = fixture.create('div', { 'data-row-key': 'session:first', 'aria-selected': 'true' }, first.sidebarRoot);
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  const hub = fixture.document.querySelector('[data-dafeiyu-mobile-control="activity-hub"]');
  const panel = fixture.document.querySelector('[data-dafeiyu-mobile-layout="activity-panel"]');
  hub.click(); fixture.flush();
  selected.setAttribute('data-row-key', 'session:second');
  fixture.mutation(true, [{ type: 'attributes', target: selected, attributeName: 'data-row-key' }]);
  assert.equal(panel.hidden, true);
  assert.equal(firstNative.calls.agentEscapes, 1);
  hub.click(); fixture.flush();
  first.title.textContent = 'Renamed offline session';
  fixture.mutation(true, [{ type: 'characterData', target: first.title }]);
  assert.equal(panel.hidden, true);
  assert.equal(firstNative.calls.agentEscapes, 2);
  hub.click(); fixture.flush();
  const detachedAgentMenu = firstNative.agentMenu;
  firstNative.agentSource.remove();
  const secondNative = nativeActivity(fixture, first, { jobs: false, team: false });
  fixture.mutation(true, [{ type: 'childList', target: first.actions }]);
  assert.equal(panel.hidden, true);
  assert.equal(detachedAgentMenu.getAttribute('data-dafeiyu-mobile-layout'), null);
  assert.equal(firstNative.agentSource.getAttribute('data-dafeiyu-mobile-control'), null);
  assert.equal(secondNative.agentSource.getAttribute('data-dafeiyu-mobile-control'), 'activity-source-agents');
  hub.click(); fixture.flush();
  assert.equal(secondNative.calls.agents, 1);
  first.frame.remove();
  const second = nativeShell(fixture, { rootSlot: first.rootSlot });
  const thirdNative = nativeActivity(fixture, second);
  fixture.mutation(true, [{ type: 'childList', target: first.rootSlot }]);
  assert.equal(panel.isConnected, false);
  assert.equal(secondNative.agentMenu, null);
  const newPanel = fixture.document.querySelector('[data-dafeiyu-mobile-layout="activity-panel"]');
  const newHub = fixture.document.querySelector('[data-dafeiyu-mobile-control="activity-hub"]');
  assert.notEqual(newPanel, panel);
  assert.notEqual(newHub, hub);
  assert.equal(newPanel.hidden, true);
  assert.equal(newPanel.parentElement, fixture.document.body);
  hub.click();
  assert.equal(secondNative.calls.agents, 1, 'The removed navigation no longer forwards clicks');
  newHub.click(); fixture.flush();
  assert.equal(thirdNative.calls.agents, 1);
  fixture.env.__DAFEIYU_DSH_CLIENT__ = undefined;
  fixture.mutation(false);
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-client-change' });
  fixture.flush();
  assert.equal(thirdNative.agentMenu, null);
  assert.equal(newPanel.isConnected, false);
  assert.equal(thirdNative.agentSource.getAttribute('data-dafeiyu-mobile-control'), null);
  assert.equal(fixture.document.querySelectorAll('[data-dafeiyu-activity-ancestor]').length, 0);
  assertInactive(fixture);
  dispose();
  assert.equal(fixture.listenerCount(), 10, 'Only the original, replacement and new native source receivers remain');
});

test('reviewed mobile-hidden controls spare native activity actions and unknown extension tabs', () => {
  const fixture = browser({ host: validHost() });
  const shell = nativeShell(fixture);
  const native = nativeActivity(fixture, shell);
  const preset = fixture.create('span', { class: 'SVAs4q_label' }, shell.actions);
  preset.textContent = 'Permission preset';
  const ordinaryPreset = fixture.create('span', {}, shell.actions);
  ordinaryPreset.textContent = 'Permission preset';
  const utilities = fixture.create('section', { 'data-slot': 'conversation.session.header.utilities' }, shell.header);
  const directory = fixture.create('div', { class: 'OMoRSG_split', 'data-open-target': 'directory' }, utilities);
  const ordinaryDirectory = fixture.create('div', { 'data-open-target': 'directory' }, utilities);
  const more = fixture.create('button', { class: 'nL4_yW_moreButton', 'aria-label': 'More' }, utilities);
  const ordinaryMore = fixture.create('button', { 'aria-label': 'More' }, utilities);
  const assistantActions = fixture.create('section', { 'data-slot': 'conversation.chat.assistant-actions' }, shell.conversation);
  const assistantCopy = fixture.create('button', { class: '_8_XoUG_action', 'aria-label': 'Copy' }, assistantActions);
  const ordinaryAssistant = fixture.create('button', { 'aria-label': 'Copy' }, assistantActions);
  const tabs = fixture.create('section', { 'data-conversation-tabs': '' }, shell.header);
  const chat = fixture.create('button', { role: 'tab', class: 'wSkVaW_tab' }, tabs);
  chat.textContent = 'Chat';
  const trajectory = fixture.create('button', { role: 'tab', class: 'wSkVaW_tab' }, tabs);
  trajectory.textContent = 'Trajectory';
  const extension = fixture.create('button', { role: 'tab', class: 'extension-tab' }, tabs);
  extension.textContent = 'Statistics';
  const microphone = fixture.create('button', { 'aria-label': 'Start recording' }, shell.activity);
  const tokenUsage = fixture.create('button', { 'aria-label': 'Token usage' }, shell.activity);
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  for (const hidden of [native.teamRoot, preset, directory, more, assistantCopy, chat, trajectory]) {
    assert.equal(hidden.getAttribute('data-dafeiyu-mobile-control'), 'mobile-hidden');
  }
  for (const neighbour of [ordinaryPreset, ordinaryDirectory, ordinaryAssistant, extension, tabs]) {
    assert.equal(neighbour.getAttribute('data-dafeiyu-mobile-control'), null);
  }
  assert.equal(ordinaryMore.getAttribute('data-dafeiyu-mobile-control'), 'session-action');
  assert.equal(microphone.getAttribute('data-dafeiyu-mobile-control'), 'microphone');
  assert.equal(tokenUsage.getAttribute('data-dafeiyu-mobile-control'), 'activity');
  const hub = fixture.document.querySelector('[data-dafeiyu-mobile-control="activity-hub"]');
  assert.equal(hub.disabled, false);
  extension.remove();
  fixture.mutation(true, [{ type: 'childList', target: tabs }]);
  assert.equal(tabs.getAttribute('data-dafeiyu-mobile-control'), 'mobile-hidden', 'A fully hidden native tab strip can collapse');
  preset.setAttribute('data-dafeiyu-mobile-control', 'later-host-value');
  dispose();
  assert.equal(preset.getAttribute('data-dafeiyu-mobile-control'), 'later-host-value');
  for (const restored of [native.teamRoot, directory, more, assistantCopy, chat, trajectory, tabs]) {
    assert.equal(restored.getAttribute('data-dafeiyu-mobile-control'), null);
  }
  assertInactive(fixture);
  assert.equal(fixture.listenerCount(), 4);
});

test('automatic mutation delivery converges when unchanged nonempty text still replaces DOM text nodes', t => {
  const fixture = browser({ host: validHost(), autoMutations: true });
  // Keep the text-only fallback regression even though modern browsers receive
  // a shipped SVG. It remains the supported fallback without SVG creation.
  fixture.document.createElementNS = undefined;
  const shell = nativeShell(fixture);
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  const navigation = fixture.document.querySelector('[data-dafeiyu-mobile-layout="navigation"]');
  const hub = navigation.querySelector('[data-dafeiyu-mobile-control="activity-hub"]');
  assert.equal(hub.textContent, 'Activity');
  assert.equal(hub.querySelector('svg'), null, 'This exercises the text fallback used without an official Team source');
  fixture.flush();
  assert.equal(fixture.mutationStats().queuedTasks, 0);
  assert.equal(fixture.mutationStats().pendingRecords, 0);

  for (let round = 0; round < 3; round++) {
    const before = fixture.mutationStats();
    // Real Element.textContent produces childList even for the same nonempty
    // string. There is no manual observer callback or plugin-node exemption.
    shell.title.textContent = shell.title.textContent;
    fixture.flush();
    const after = fixture.mutationStats();
    assert.ok(after.records > before.records, 'The unchanged host text must produce a real observed write');
    assert.ok(after.deliveries > before.deliveries, 'The observed write must reach the real reconciliation callback');
    assert.equal(after.queuedTasks, 0);
    assert.equal(after.pendingRecords, 0);
    assert.equal(hub.textContent, 'Activity');
  }
  shell.title.textContent = 'A new native title';
  fixture.flush();
  assert.equal(navigation.children[1].textContent, 'A new native title');
  fixture.document.documentElement.setAttribute('lang', 'zh-CN');
  fixture.env.__DAFEIYU_DSH_CLIENT__ = validHost({ theme: 'dark' });
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-client-change' });
  fixture.flush();
  assert.equal(hub.textContent, '活动');
  const settled = fixture.mutationStats();
  const settledDom = navigation.outerHTML;
  fixture.flush();
  assert.deepEqual(fixture.mutationStats(), settled, 'A settled DOM does not enqueue more reconciliation work');
  assert.equal(navigation.outerHTML, settledDom);
  t.diagnostic(`Auto-mutation convergence: ${settled.records} accepted records, ${settled.deliveries} observer deliveries; no queued work`);
  dispose();
  fixture.flush();
  shell.title.textContent = 'Detached observation must stay stopped';
  fixture.flush();
  assert.equal(fixture.mutationStats().deliveries, settled.deliveries);
  assert.equal(fixture.mutationStats().queuedTasks, 0);
  assert.equal(fixture.listenerCount(), 0);
  assertInactive(fixture);
});

test('direct and nested official Team markers clone the shipped icon and remain idempotent under observed writes', t => {
  for (const shape of ['direct', 'nested']) {
    const fixture = browser({ host: validHost(), autoMutations: true });
    const shell = nativeShell(fixture);
    const root = shape === 'nested' ? fixture.create('div', { 'data-team-action': '' }, shell.actions) : shell.actions;
    const source = fixture.create('button', { class: 'VoX2oq_trigger', 'aria-haspopup': 'dialog',
      'aria-label': 'Team', ...(shape === 'direct' ? { 'data-team-action': '' } : {}) }, root);
    const svg = fixture.create('svg', { id: `official-${shape}-icon`, 'aria-labelledby': `official-${shape}-description` }, source);
    fixture.create('path', { id: `official-${shape}-path`, d: 'M1 1 L2 2' }, svg);
    let sourceClicks = 0;
    source.addEventListener('click', () => { sourceClicks++; });
    const sourceBefore = source.outerHTML;
    const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
    const hub = fixture.document.querySelector('[data-dafeiyu-mobile-control="activity-hub"]');
    fixture.flush();
    const firstClone = hub.querySelector('svg');
    assert.ok(firstClone, `The ${shape} official marker contributes its icon`);
    assert.notEqual(firstClone, svg);
    assert.equal(firstClone.querySelector('path').getAttribute('d'), 'M1 1 L2 2');
    assert.equal(firstClone.getAttribute('aria-hidden'), 'true');
    assert.equal(firstClone.getAttribute('focusable'), 'false');
    assert.equal(firstClone.querySelectorAll('[id], [aria-labelledby], [aria-describedby]').length, 0);
    assert.equal(firstClone.getAttribute('id'), null);
    assert.equal(firstClone.getAttribute('aria-labelledby'), null);
    assert.equal(svg.id, `official-${shape}-icon`);
    assert.equal(svg.querySelector('path').id, `official-${shape}-path`);
    assert.equal(source.parentElement, root);
    assert.equal(sourceClicks, 0, 'Cloning a Team glyph must not open or replay its business action');
    source.setAttribute('aria-label', source.getAttribute('aria-label'));
    fixture.flush();
    assert.equal(hub.querySelector('svg'), firstClone, 'An unchanged native glyph reuses its existing clone');
    const path = fixture.create('path', { id: `official-${shape}-new-path`, d: 'M3 3 L4 4' }, svg);
    fixture.flush();
    const nextClone = hub.querySelector('svg');
    assert.notEqual(nextClone, firstClone, 'A real native glyph replacement refreshes the cloned icon');
    assert.equal(nextClone.querySelectorAll('path').length, 2);
    assert.equal(nextClone.querySelectorAll('[id]').length, 0);
    assert.equal(path.parentElement, svg);
    const settled = fixture.mutationStats();
    fixture.flush();
    assert.deepEqual(fixture.mutationStats(), settled);
    t.diagnostic(`${shape} Team: ${settled.records} accepted records, ${settled.deliveries} observer deliveries; no queued work`);
    dispose();
    fixture.flush();
    path.remove();
    assert.equal(source.outerHTML, sourceBefore, 'All temporary roles restore without mutating the original glyph');
    assert.equal(fixture.listenerCount(), 1, 'Only the original Team receiver survives');
    assertInactive(fixture);
  }
});

test('Team icon recognition fails closed for incomplete markers and contributions outside the official actions slot', () => {
  const cases = [
    { marker: true, popup: 'tree', officialClass: true, outside: false },
    { marker: true, popup: null, officialClass: true, outside: false, container: true },
    { marker: true, popup: 'dialog', officialClass: false, outside: false, container: true },
    { marker: false, popup: 'dialog', officialClass: true, outside: false },
    { marker: true, popup: 'dialog', officialClass: true, outside: true },
  ];
  for (const row of cases) {
    const fixture = browser({ host: validHost(), autoMutations: true });
    const shell = nativeShell(fixture);
    const scope = row.outside ? shell.activity : shell.actions;
    const root = row.container ? fixture.create('div', { 'data-team-action': '' }, scope) : scope;
    const candidate = fixture.create('button', { class: row.officialClass ? 'VoX2oq_trigger' : 'extension-team-trigger',
      ...(row.marker && !row.container ? { 'data-team-action': '' } : {}),
      ...(row.popup ? { 'aria-haspopup': row.popup } : {}), 'aria-label': 'Team' }, root);
    fixture.create('svg', { 'data-fixture-icon': 'not-a-valid-official-source' }, candidate);
    let clicks = 0;
    candidate.addEventListener('click', () => { clicks++; });
    const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
    fixture.flush();
    const hub = fixture.document.querySelector('[data-dafeiyu-mobile-control="activity-hub"]');
    const fallback = hub.querySelector('svg');
    assert.ok(fallback, 'A rejected Team source retains the shipped fallback icon');
    assert.equal(fallback.getAttribute('data-fixture-icon'), null, JSON.stringify(row));
    assert.equal(fallback.getAttribute('viewBox'), '0 0 16 16');
    assert.equal(hub.disabled, true);
    hub.click();
    fixture.flush();
    assert.equal(clicks, 0);
    assert.equal(fixture.mutationStats().queuedTasks, 0);
    dispose();
    fixture.flush();
    assert.equal(fixture.listenerCount(), 1);
    assertInactive(fixture);
  }
});

test('the hero activity glyph remains stable before official sources arrive and then uses their real icon', t => {
  const fixture = browser({ host: validHost(), autoMutations: true });
  const shell = nativeShell(fixture);
  shell.conversation.setAttribute('data-phase', 'hero');
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  fixture.flush();
  const hub = fixture.document.querySelector('[data-dafeiyu-mobile-control="activity-hub"]');
  const fallback = hub.querySelector('svg');
  assert.ok(fallback);
  assert.equal(fallback.getAttribute('viewBox'), '0 0 16 16');
  assert.equal(fallback.querySelectorAll('path').length, 4);
  assert.equal(fallback.getAttribute('aria-hidden'), 'true');
  assert.equal(hub.disabled, true, 'An icon does not invent an available activity source');
  shell.title.textContent = shell.title.textContent;
  fixture.flush();
  assert.equal(hub.querySelector('svg'), fallback);
  const native = nativeActivity(fixture, shell);
  fixture.flush();
  const official = hub.querySelector('svg');
  assert.notEqual(official, fallback);
  assert.equal(official.getAttribute('data-fixture-icon'), 'native-team');
  assert.equal(hub.disabled, false);
  hub.click(); fixture.flush();
  assert.equal(native.calls.agents, 1, 'The newly available official activity still receives the business action');
  fixture.document.dispatchEvent(new fixture.env.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  fixture.flush();
  const settled = fixture.mutationStats();
  fixture.flush();
  assert.deepEqual(fixture.mutationStats(), settled);
  t.diagnostic(`Hero icon convergence: ${settled.records} records, ${settled.deliveries} deliveries; no queued work`);
  dispose(); fixture.flush();
  assertInactive(fixture);
  assert.equal(fixture.listenerCount(), 4);
});

test('native header collapse preserves unknown controls, links, own text and a real jobs menu', t => {
  const fixture = browser({ host: validHost(), autoMutations: true });
  const shell = nativeShell(fixture);
  const header = nativeConversationHeader(fixture, shell);
  const native = nativeActivity(fixture, header, { agents: false });
  const originalHeaderAttributes = attrs(header.header);
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  fixture.flush();
  assert.equal(header.title.getAttribute('data-dafeiyu-mobile-control'), 'mobile-static-title');
  assert.equal(header.header.getAttribute('data-dafeiyu-mobile-control'), 'mobile-empty-header');
  assert.equal(header.lineage.getAttribute('data-dafeiyu-mobile-control'), null);
  const contributors = [
    () => fixture.create('button', { class: 'wSkVaW_titleCluster', 'aria-label': 'Extension action' }, header.row),
    () => fixture.create('a', { href: '/fixture-extension' }, header.cluster),
    () => fixture.create('section', { 'data-slot': 'extension.header.information' }, header.row),
    () => { const text = fixture.document.createTextNode('Extension direct text'); header.row.appendChild(text); return text; },
    () => fixture.create('span', { class: 'wSkVaW_titleCluster', tabindex: '0' }, header.row),
    () => fixture.create('span', { class: 'wSkVaW_titleCluster', contenteditable: 'true' }, header.row),
  ];
  for (const createContribution of contributors) {
    const contribution = createContribution();
    fixture.flush();
    assert.equal(header.header.getAttribute('data-dafeiyu-mobile-control'), null,
      'Unknown or interactive contribution keeps its enclosing native header');
    assert.equal(contribution.isConnected, true);
    contribution.remove(); fixture.flush();
    assert.equal(header.header.getAttribute('data-dafeiyu-mobile-control'), 'mobile-empty-header');
  }
  const text = fixture.document.createTextNode('Visible text');
  header.row.appendChild(text); fixture.flush();
  text.textContent = '   '; fixture.flush();
  assert.equal(header.header.getAttribute('data-dafeiyu-mobile-control'), 'mobile-empty-header');
  text.textContent = 'Restored text'; fixture.flush();
  assert.equal(header.header.getAttribute('data-dafeiyu-mobile-control'), null);
  text.remove(); fixture.flush();

  const hub = fixture.document.querySelector('[data-dafeiyu-mobile-control="activity-hub"]');
  hub.click(); fixture.flush();
  assert.equal(native.calls.jobs, 1);
  assert.equal(native.jobsMenu.parentElement, native.jobsRoot);
  assert.notEqual(header.header.getAttribute('data-dafeiyu-mobile-control'), 'mobile-empty-header',
    'The actual native task list keeps its ancestor available');
  assert.equal(native.jobsMenu.getAttribute('data-dafeiyu-mobile-layout'), 'activity-native-jobs');
  const unknown = fixture.create('button', { 'aria-label': 'Extension action while jobs run' }, header.row);
  fixture.flush();
  assert.equal(header.header.getAttribute('data-dafeiyu-mobile-control'), null);
  unknown.remove(); fixture.flush();
  fixture.document.dispatchEvent(new fixture.env.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  fixture.flush();
  assert.equal(native.calls.jobs, 2, 'Closing still uses the official jobs toggle');
  assert.equal(native.jobsMenu, null);
  assert.equal(header.header.getAttribute('data-dafeiyu-mobile-control'), 'mobile-empty-header');
  const settled = fixture.mutationStats(); fixture.flush();
  assert.deepEqual(fixture.mutationStats(), settled);
  t.diagnostic(`Header contribution convergence: ${settled.records} records, ${settled.deliveries} deliveries; no queued work`);
  dispose(); fixture.flush();
  assert.deepEqual(attrs(header.header), originalHeaderAttributes);
  assert.equal(header.title.getAttribute('data-dafeiyu-mobile-control'), null);
  assertInactive(fixture);
  assert.equal(fixture.listenerCount(), 2);
});

test('static-title recognition preserves interactive or unknown crumb content', () => {
  const fixture = browser({ host: validHost(), autoMutations: true });
  const shell = nativeShell(fixture);
  const header = nativeConversationHeader(fixture, shell);
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  fixture.flush();
  assert.equal(header.title.getAttribute('data-dafeiyu-mobile-control'), 'mobile-static-title');
  for (const [name, value] of [['role', 'status'], ['data-slot', 'extension.title'], ['tabindex', '0'],
    ['contenteditable', 'true'], ['onclick', 'extension-owned-handler']]) {
    header.title.setAttribute(name, value); fixture.flush();
    assert.equal(header.title.getAttribute('data-dafeiyu-mobile-control'), null);
    assert.equal(header.header.getAttribute('data-dafeiyu-mobile-control'), null);
    header.title.removeAttribute(name); fixture.flush();
    assert.equal(header.title.getAttribute('data-dafeiyu-mobile-control'), 'mobile-static-title');
  }
  header.title.classList.add('extension-owned-title'); fixture.flush();
  assert.equal(header.title.getAttribute('data-dafeiyu-mobile-control'), null);
  header.title.classList.remove('extension-owned-title'); fixture.flush();
  const extra = fixture.create('span', { 'data-slot': 'extension.crumb.content' }, header.title);
  fixture.flush();
  assert.equal(header.title.getAttribute('data-dafeiyu-mobile-control'), null);
  extra.remove(); fixture.flush();
  const button = fixture.create('button', { 'aria-label': 'Lineage extension action' }, header.crumbs);
  fixture.flush();
  assert.equal(header.title.getAttribute('data-dafeiyu-mobile-control'), null,
    'An interactive crumb keeps the original title and enclosing header');
  button.remove(); fixture.flush();
  assert.equal(header.title.getAttribute('data-dafeiyu-mobile-control'), 'mobile-static-title');
  const lineageContribution = fixture.create('a', { href: '/lineage-fixture' }, header.lineage);
  fixture.flush();
  assert.equal(header.lineage.getAttribute('data-dafeiyu-mobile-control'), null);
  assert.equal(header.header.getAttribute('data-dafeiyu-mobile-control'), null);
  lineageContribution.remove(); fixture.flush();
  dispose(); fixture.flush();
  assert.equal(header.title.getAttribute('data-dafeiyu-mobile-control'), null);
  assertInactive(fixture);
  assert.equal(fixture.listenerCount(), 0);
});

test('quick native actions require a validated Android host and exact capability with a callable method', () => {
  const baseBridge = () => ({ version: 1, platform: 'android', capabilities: ['app.scan.open', 'pairing.open'] });
  const cases = [
    { client: undefined, bridge: baseBridge() },
    { client: validHost({ version: 2 }), bridge: baseBridge() },
    { client: validHost(), bridge: { ...baseBridge(), version: 2 } },
    { client: validHost(), bridge: { ...baseBridge(), platform: 'windows' } },
    { client: validHost(), bridge: { ...baseBridge(), capabilities: [] } },
    { client: validHost(), bridge: { ...baseBridge(), capabilities: ['app.scan.open', {}] } },
    { client: validHost(), bridge: baseBridge(), absentMethods: true },
  ];
  for (const row of cases) {
    const fixture = browser({ href: 'http://127.0.0.1:8787/?dafeiyu-preview=mobile', host: row.client, autoMutations: true });
    nativeShell(fixture);
    let nativeCalls = 0;
    if (!row.absentMethods) {
      row.bridge.openScanner = () => { nativeCalls++; };
      row.bridge.openPairing = () => { nativeCalls++; };
    }
    fixture.env.__DAFEIYU_DSH_HOST__ = row.bridge;
    const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
    fixture.flush();
    fixture.document.querySelector('[data-dafeiyu-mobile-control="new-session"]').click(); fixture.flush();
    const menu = fixture.document.querySelector('[data-dafeiyu-mobile-layout="quick-actions"]');
    for (const kind of ['scan', 'pair']) {
      const button = menu.querySelector(`[data-dafeiyu-mobile-control="quick-${kind}"]`);
      assert.equal(button.disabled, true);
      assert.match(button.textContent, /requires phone/i);
      button.click();
    }
    assert.equal(nativeCalls, 0);
    dispose(); fixture.flush();
    assertInactive(fixture);
    assert.equal(fixture.listenerCount(), 0);
  }
});

test('quick actions forward approved native methods once, keep failures visible and ignore stale results', async () => {
  const fixture = browser({ host: validHost(), autoMutations: true });
  const shell = nativeShell(fixture);
  let sessions = 0;
  shell.newSession.addEventListener('click', () => { sessions++; });
  const calls = { scanner: 0, pairing: 0 };
  let scanResolve;
  let pairingOutcome = 'reject';
  const bridge = {
    version: 1, platform: 'android', capabilities: ['app.scan.open', 'pairing.open'],
    openScanner(...args) {
      assert.equal(this, bridge); assert.deepEqual(args, []); calls.scanner++;
      return new Promise(resolve => { scanResolve = resolve; });
    },
    openPairing(...args) {
      assert.equal(this, bridge); assert.deepEqual(args, []); calls.pairing++;
      if (pairingOutcome === 'reject') return Promise.reject(new Error('private-native-diagnostic-fixture'));
      return pairingOutcome;
    },
  };
  fixture.env.__DAFEIYU_DSH_HOST__ = bridge;
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  fixture.flush();
  const trigger = fixture.document.querySelector('[data-dafeiyu-mobile-control="new-session"]');
  const menu = fixture.document.querySelector('[data-dafeiyu-mobile-layout="quick-actions"]');
  const session = menu.querySelector('[data-dafeiyu-mobile-control="quick-new-session"]');
  const scan = menu.querySelector('[data-dafeiyu-mobile-control="quick-scan"]');
  const pair = menu.querySelector('[data-dafeiyu-mobile-control="quick-pair"]');
  const status = menu.querySelector('[role="status"]');
  trigger.click(); fixture.flush();
  assert.equal(sessions, 0);
  assert.equal(fixture.document.activeElement, session);
  session.dispatchEvent(new fixture.env.KeyboardEvent('keydown', { key: 'End', bubbles: true, cancelable: true }));
  assert.equal(fixture.document.activeElement, pair);
  pair.click(); pair.click();
  await Promise.resolve(); fixture.flush();
  assert.equal(calls.pairing, 1, 'Pending host actions cannot be duplicated');
  assert.equal(menu.getAttribute('data-dafeiyu-quick-state'), 'open');
  assert.match(status.textContent, /could not open/i);
  assert.doesNotMatch(status.textContent, /private-native|success|paired/i);
  assert.equal(pair.disabled, false);
  scan.click(); scan.click(); session.click();
  assert.equal(calls.scanner, 1);
  assert.equal(sessions, 0);
  assert.equal(menu.getAttribute('aria-busy'), 'true');
  assert.ok([session, scan, pair].every(button => button.disabled));
  fixture.document.dispatchEvent(new fixture.env.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  assert.equal(menu.getAttribute('aria-hidden'), 'true');
  assert.equal(menu.hasAttribute('inert'), true);
  assert.equal(menu.getAttribute('data-dafeiyu-quick-state'), 'closing');
  fixture.flush();
  assert.equal(menu.hidden, true);
  assert.equal(fixture.document.activeElement, trigger);
  const dismissed = menu.outerHTML;
  scanResolve(true); await Promise.resolve(); fixture.flush();
  assert.equal(menu.outerHTML, dismissed, 'A result after dismissal cannot reopen or repaint the menu');
  trigger.click(); fixture.flush();
  scan.click();
  const pendingSnapshot = status.textContent;
  bridge.capabilities = ['pairing.open'];
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-host-change' }); fixture.flush();
  assert.equal(menu.hidden, true);
  scanResolve({ ok: true }); await Promise.resolve(); fixture.flush();
  assert.equal(status.textContent, pendingSnapshot);
  trigger.click(); fixture.flush();
  assert.equal(scan.disabled, true);
  assert.equal(pair.disabled, false);
  for (const refusal of [false, { ok: false }]) {
    pairingOutcome = refusal;
    pair.click(); await Promise.resolve(); fixture.flush();
    assert.equal(menu.getAttribute('data-dafeiyu-quick-state'), 'open');
    assert.match(status.textContent, /could not open/i);
    assert.equal(pair.disabled, false);
  }
  pairingOutcome = undefined;
  pair.click(); await Promise.resolve(); fixture.flush();
  assert.equal(menu.hidden, true, 'Native workflow acceptance closes the menu without claiming pairing success');
  assert.doesNotMatch(status.textContent, /success|paired/i);
  trigger.click(); fixture.flush();
  session.click(); fixture.flush();
  assert.equal(sessions, 1, 'The menu action still forwards the actual new-session source');
  bridge.capabilities = ['app.scan.open', 'pairing.open'];
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-host-change' });
  trigger.click(); fixture.flush(); scan.click();
  const beforeDispose = menu.outerHTML;
  dispose(); fixture.flush();
  const afterDispose = menu.outerHTML;
  scanResolve(true); await Promise.resolve(); fixture.flush();
  assert.equal(menu.outerHTML, afterDispose, 'An accepted native action after disposal cannot repaint detached controls');
  assert.notEqual(beforeDispose, afterDispose, 'Disposal immediately withdraws pending menu interaction');
  const finalCalls = { ...calls };
  scan.click(); pair.click(); session.click(); trigger.click();
  await Promise.resolve(); fixture.flush();
  assert.deepEqual(calls, finalCalls);
  assert.equal(sessions, 1);
  assertInactive(fixture);
  assert.equal(fixture.listenerCount(), 1);
});

test('quick dismissal, native source replacement and press cancellation leave no interactive residue', t => {
  const fixture = browser({ host: validHost(), autoMutations: true });
  const shell = nativeShell(fixture);
  const native = nativeActivity(fixture, shell);
  const dispose = createDafeiyuMobileContext(fixture.env).install(cssText);
  fixture.flush();
  const trigger = fixture.document.querySelector('[data-dafeiyu-mobile-control="new-session"]');
  const hub = fixture.document.querySelector('[data-dafeiyu-mobile-control="activity-hub"]');
  const menu = fixture.document.querySelector('[data-dafeiyu-mobile-layout="quick-actions"]');
  const session = menu.querySelector('[data-dafeiyu-mobile-control="quick-new-session"]');
  trigger.dispatchEvent(new fixture.env.MouseEvent('pointerdown', { bubbles: true, pointerId: 11 }));
  assert.equal(trigger.getAttribute('data-dafeiyu-pressed'), 'true');
  fixture.document.dispatchEvent(new fixture.env.MouseEvent('pointerup', { pointerId: 12 }));
  assert.equal(trigger.getAttribute('data-dafeiyu-pressed'), 'true');
  fixture.document.dispatchEvent(new fixture.env.MouseEvent('pointercancel', { pointerId: 11 }));
  assert.equal(trigger.getAttribute('data-dafeiyu-pressed'), null);
  trigger.click(); fixture.flush();
  session.dispatchEvent(new fixture.env.MouseEvent('pointerdown', { bubbles: true, pointerId: 13 }));
  assert.equal(session.getAttribute('data-dafeiyu-pressed'), 'true');
  const outside = fixture.create('button', { 'aria-label': 'Outside quick actions' });
  outside.dispatchEvent(new fixture.env.MouseEvent('pointerdown', { bubbles: true, pointerId: 14 }));
  assert.equal(session.getAttribute('data-dafeiyu-pressed'), null);
  assert.equal(menu.hasAttribute('inert'), true);
  assert.equal(menu.getAttribute('aria-hidden'), 'true');
  assert.equal(menu.getAttribute('data-dafeiyu-quick-state'), 'closing');
  fixture.flush(); assert.equal(menu.hidden, true);
  trigger.click(); fixture.flush();
  hub.click(); fixture.flush();
  assert.equal(menu.hidden, true);
  assert.equal(native.calls.agents, 1);
  trigger.click(); fixture.flush();
  assert.equal(native.agentMenu, null, 'Opening quick actions closes the actual native activity');
  assert.equal(native.calls.agentEscapes, 1);
  shell.newSession.remove();
  const replacement = fixture.create('button', { 'aria-label': 'New Session' }, shell.sidebarRoot);
  fixture.create('svg', { 'data-fixture-icon': 'replacement-session' }, replacement);
  fixture.flush();
  assert.equal(menu.hidden, true, 'A changed business source dismisses the open menu');
  trigger.click(); fixture.flush();
  const sidebar = fixture.document.querySelector('[data-dafeiyu-mobile-control="sidebar-toggle"]');
  sidebar.click(); fixture.flush();
  assert.equal(menu.hidden, true);
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-drawer'), 'open');
  trigger.dispatchEvent(new fixture.env.MouseEvent('pointerdown', { bubbles: true, pointerId: 15 }));
  assert.equal(trigger.getAttribute('data-dafeiyu-pressed'), 'true');
  fixture.env.dispatchEvent({ type: 'blur' });
  assert.equal(trigger.getAttribute('data-dafeiyu-pressed'), null);
  trigger.dispatchEvent(new fixture.env.MouseEvent('pointerdown', { bubbles: true, pointerId: 16 }));
  trigger.setAttribute('data-dafeiyu-pressed', 'later-host-owned');
  fixture.document.dispatchEvent(new fixture.env.MouseEvent('pointerup', { pointerId: 16 }));
  assert.equal(trigger.getAttribute('data-dafeiyu-pressed'), 'later-host-owned');
  const settled = fixture.mutationStats(); fixture.flush();
  assert.deepEqual(fixture.mutationStats(), settled);
  t.diagnostic(`Quick/menu press convergence: ${settled.records} records, ${settled.deliveries} deliveries; no queued work`);
  dispose(); fixture.flush();
  assert.equal(trigger.getAttribute('data-dafeiyu-pressed'), 'later-host-owned');
  assert.equal(menu.isConnected, false);
  assertInactive(fixture);
  assert.equal(fixture.listenerCount(), 4);
});

test('startup readiness requires a connected engine, a composer and visible real sidebar target', () => {
  const fixture = browser({ host: validHost(), autoMutations: true, virtualTime: true });
  const shell = nativeShell(fixture);
  const connection = connectionFixture();
  const context = createDafeiyuMobileContext(fixture.env);
  const removeSurface = context.install(cssText);
  fixture.flush();
  const removeStartup = createDafeiyuStartup(fixture.env, { readContext: context.readContext, connection: connection.service });
  fixture.flush();
  const overlay = fixture.document.querySelector('[data-dafeiyu-mobile-layout="startup-overlay"]');
  const focus = overlay.querySelector('[data-dafeiyu-mobile-layout="startup-focus"]');
  const target = fixture.document.querySelector('[data-dafeiyu-mobile-control="sidebar-toggle"]');
  const status = () => overlay.getAttribute('data-dafeiyu-startup-state');
  assert.equal(status(), 'waiting');
  connection.publish('connected'); fixture.flush();
  assert.equal(status(), 'waiting', 'Handshake alone cannot reveal an unprepared page');
  nativeComposer(fixture, shell);
  fixture.flush();
  assert.equal(status(), 'waiting', 'A zero-sized sidebar target cannot receive the startup transition');
  target.rectangle = { x: 18, y: 12, width: 36, height: 36 };
  focus.rectangle = { x: 160, y: 260, width: 64, height: 64 };
  fixture.env.dispatchEvent({ type: 'resize' }); fixture.flush();
  assert.equal(status(), 'revealing');
  assert.equal(overlay.hidden, false);
  assert.equal(overlay.inert, true);
  assert.equal(overlay.style.pointerEvents, 'none');
  assert.match(overlay.style.getPropertyValue('--df-startup-target-scale'), /^0\.5625$/);
  assert.equal(focus.querySelector('svg').getAttribute('data-fixture-icon'), 'native-sidebar');
  assert.equal(target.querySelector('svg').getAttribute('data-fixture-icon'), 'native-sidebar');
  connection.publish('disconnected'); fixture.flush();
  assert.equal(status(), 'failed', 'Loss of the real handshake during reveal must stop completion');
  fixture.advance(740);
  assert.equal(status(), 'failed');
  assert.equal(overlay.hidden, false);
  connection.publish('connected'); fixture.flush();
  assert.equal(status(), 'revealing');
  focus.dispatchEvent(new fixture.env.Event('animationend', { animationName: 'unrelatedAnimation', bubbles: true }));
  assert.equal(status(), 'revealing');
  focus.dispatchEvent(new fixture.env.Event('animationend', { animationName: 'dfStartupReveal', bubbles: true }));
  fixture.flush();
  assert.equal(status(), 'ready');
  assert.equal(overlay.hidden, true);
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(connection.reconnects, 0, 'Readiness presentation does not restart a service');
  removeStartup(); removeSurface(); fixture.flush();
  assert.equal(connection.cleanups, 1);
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-startup'), null);
  assert.equal(fixture.listenerCount(), 0);
  assertInactive(fixture);
});

test('Android local and online hosts receive only real presentation readiness and its withdrawal', () => {
  for (const mode of ['local', 'online']) {
    const fixture = startupNativeFixture({ mode });
    const probe = fixture.env.__DAFEIYU_DSH_PRESENTATION__;
    assert.equal(probe.version, 1);
    assert.equal(Object.isFrozen(probe), true);
    assert.equal(Object.getOwnPropertyDescriptor(fixture.env, '__DAFEIYU_DSH_PRESENTATION__').writable, false);
    assert.equal(probe.isReady(), false);
    assert.deepEqual(fixture.notifications, [false]);
    fixture.connection.publish('connected'); fixture.flush();
    assert.deepEqual(fixture.notifications, [false], 'An engine handshake is not sufficient presentation readiness');
    const composer = fixture.addComposer(); fixture.flush();
    assert.equal(probe.isReady(), false, 'A zero-sized sidebar target is still unusable');
    fixture.showTarget();
    assert.equal(fixture.status(), 'revealing');
    assert.equal(probe.isReady(), true);
    assert.deepEqual(fixture.notifications, [false, true], 'Native readiness precedes only the finite reveal');
    fixture.advance(740);
    fixture.env.dispatchEvent({ type: 'resize' }); fixture.flush();
    assert.equal(fixture.status(), 'ready');
    assert.deepEqual(fixture.notifications, [false, true], 'Repeated layout observations do not duplicate reports');
    composer.remove(); fixture.flush();
    assert.equal(fixture.status(), 'failed');
    assert.equal(probe.isReady(), false);
    assert.deepEqual(fixture.notifications, [false, true, false]);
    fixture.addComposer(); fixture.flush(); fixture.advance(740);
    assert.equal(probe.isReady(), true);
    assert.deepEqual(fixture.notifications, [false, true, false, true]);
    fixture.dispose(); fixture.advance(60_000);
    assert.equal(probe.isReady(), false, 'A retained old probe cannot claim readiness after disposal');
    assert.equal(fixture.env.__DAFEIYU_DSH_PRESENTATION__, undefined);
    assert.deepEqual(fixture.notifications, [false, true, false, true, false]);
    assert.equal(fixture.connection.reconnects, 0);
    assert.equal(fixture.pendingTimers(), 0);
    assert.equal(fixture.listenerCount(), 0);
    assertInactive(fixture);
  }
});

test('startup with a connected engine rejects collapsed content and clipped composer geometry', () => {
  const fixture = startupNativeFixture({ connected: true });
  const composer = fixture.addComposer(); fixture.flush();
  const frame = fixture.shell.frame;
  const original = { ...frame.rectangle };
  frame.rectangle = { ...original, height: 64 };
  fixture.showTarget();
  const probe = fixture.env.__DAFEIYU_DSH_PRESENTATION__;
  assert.equal(probe.isReady(), false, 'The visible top button cannot stand in for a collapsed conversation');
  assert.deepEqual(fixture.notifications, [false]);
  frame.rectangle = original;
  composer.rectangle.y = -90;
  fixture.env.dispatchEvent({ type: 'resize' }); fixture.flush();
  assert.equal(probe.isReady(), false, 'A positive-sized composer outside the content viewport is unusable');
  composer.rectangle.y = fixture.env.innerHeight - 50;
  fixture.env.dispatchEvent({ type: 'resize' }); fixture.flush();
  assert.equal(probe.isReady(), false, 'The clipped bottom of a composer must also keep the waiting layer');
  composer.rectangle.y = fixture.env.innerHeight - 120;
  fixture.env.dispatchEvent({ type: 'resize' }); fixture.flush(); fixture.advance(740);
  assert.equal(probe.isReady(), true, 'An empty, usable composer does not require history to become ready');
  assert.equal(fixture.status(), 'ready');
  fixture.dispose();
  assert.equal(fixture.pendingTimers(), 0);
});

test('startup measures the actual composer card when official slot wrappers use display contents', () => {
  const fixture = startupNativeFixture({ connected: true });
  const composer = fixture.addComposer();
  const card = fixture.create('div', {}, composer);
  fixture.create('section', { 'data-slot': 'conversation.input.attachments' }, card);
  card.rectangle = { ...composer.rectangle };
  composer.rectangle = { x: 0, y: 0, width: 0, height: 0 };
  fixture.flush(); fixture.showTarget(); fixture.advance(740);
  assert.equal(card.getAttribute('data-dafeiyu-mobile-layout'), 'composer-card');
  assert.equal(fixture.env.__DAFEIYU_DSH_PRESENTATION__.isReady(), true);
  card.rectangle.height = 0;
  fixture.env.dispatchEvent({ type: 'resize' }); fixture.flush();
  assert.equal(fixture.env.__DAFEIYU_DSH_PRESENTATION__.isReady(), false);
  assert.equal(fixture.status(), 'failed');
  fixture.dispose();
});

test('a late native document binding reports current readiness once without replaying startup', () => {
  const fixture = startupNativeFixture({ bound: false, connected: true });
  fixture.addComposer(); fixture.flush(); fixture.showTarget(); fixture.advance(740);
  assert.equal(fixture.status(), 'ready');
  assert.equal(fixture.env.__DAFEIYU_DSH_PRESENTATION__.isReady(), true);
  assert.deepEqual(fixture.notifications, [], 'Unbound documents cannot send readiness to Android');
  fixture.bridge.document_bound = true;
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-host-change' }); fixture.flush();
  assert.deepEqual(fixture.notifications, [true]);
  assert.equal(fixture.overlay.hidden, true, 'Binding does not restart a completed presentation');
  fixture.connection.publish('connected'); fixture.flush();
  assert.deepEqual(fixture.notifications, [true]);
  fixture.dispose();
  assert.deepEqual(fixture.notifications, [true, false]);
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.listenerCount(), 0);
});

test('page hiding, cancellation and ownership changes invalidate the native presentation probe', () => {
  const fixture = startupNativeFixture({ connected: true });
  fixture.addComposer(); fixture.flush(); fixture.showTarget(); fixture.advance(740);
  const probe = fixture.env.__DAFEIYU_DSH_PRESENTATION__;
  fixture.env.dispatchEvent({ type: 'pagehide' }); fixture.flush();
  assert.equal(probe.isReady(), false);
  assert.deepEqual(fixture.notifications, [false, true, false]);
  fixture.env.dispatchEvent({ type: 'pageshow' }); fixture.flush();
  assert.equal(probe.isReady(), true);
  assert.deepEqual(fixture.notifications, [false, true, false, true]);
  fixture.connection.publish('disconnected'); fixture.flush();
  fixture.overlay.querySelector('[data-dafeiyu-mobile-control="startup-leave"]').click(); fixture.flush();
  fixture.connection.publish('connected'); fixture.flush(); fixture.advance(60_000);
  assert.equal(probe.isReady(), false, 'Cancelling presentation cannot be undone by a later connection event');
  assert.equal(fixture.overlay.hidden, true);
  assert.deepEqual(fixture.notifications, [false, true, false, true, false]);
  const replacement = Object.freeze({ version: 1, isReady: () => false });
  Object.defineProperty(fixture.env, '__DAFEIYU_DSH_PRESENTATION__', { configurable: true, value: replacement });
  fixture.dispose();
  assert.equal(fixture.env.__DAFEIYU_DSH_PRESENTATION__, replacement, 'Disposal does not overwrite a later owner');
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.listenerCount(), 0);
  assertInactive(fixture);
});

test('failed native readiness delivery can retry but a replaced host isolates an old rejection', async () => {
  const fixture = startupNativeFixture({ connected: true });
  fixture.addComposer(); fixture.flush(); fixture.showTarget(); fixture.advance(740);
  let rejectOld;
  fixture.bridge.notifyPresentationUnavailable = () => {
    fixture.notifications.push(false);
    return new Promise((resolve, reject) => { rejectOld = reject; });
  };
  fixture.connection.publish('disconnected'); fixture.flush();
  assert.equal(typeof rejectOld, 'function');
  const currentReports = [];
  fixture.env.__DAFEIYU_DSH_HOST__ = {
    ...fixture.bridge,
    notifyPresentationUnavailable() { currentReports.push(false); return Promise.resolve({ accepted: true }); },
    notifyPresentationReady() { currentReports.push(true); return Promise.resolve({ accepted: true }); },
  };
  fixture.env.dispatchEvent({ type: 'dafeiyu-dsh-host-change' }); fixture.flush();
  assert.deepEqual(currentReports, [false]);
  rejectOld(new Error('synthetic retired-document response'));
  await Promise.resolve(); await Promise.resolve();
  fixture.env.dispatchEvent({ type: 'resize' }); fixture.flush();
  assert.deepEqual(currentReports, [false], 'An old failure cannot reset the replacement host report guard');
  fixture.connection.publish('connected'); fixture.flush(); fixture.advance(740);
  assert.deepEqual(currentReports, [false, true]);
  fixture.env.__DAFEIYU_DSH_HOST__.notifyPresentationUnavailable = () => {
    currentReports.push(false); return Promise.reject(new Error('synthetic current-document transport failure'));
  };
  fixture.connection.publish('disconnected'); fixture.flush();
  await Promise.resolve(); await Promise.resolve();
  fixture.env.__DAFEIYU_DSH_HOST__.notifyPresentationUnavailable = () => {
    currentReports.push(false); return Promise.resolve({ accepted: true });
  };
  fixture.env.dispatchEvent({ type: 'resize' }); fixture.flush();
  assert.deepEqual(currentReports, [false, true, false, false], 'The current host can retry an actually rejected delivery');
  fixture.dispose();
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.listenerCount(), 0);
  assertInactive(fixture);
});

test('startup respects reduced motion, a timeout retry and explicit dismissal without claiming readiness', () => {
  for (const reduced of [false, true]) {
    const fixture = browser({ host: validHost({ motion: reduced }), reducedMotion: reduced,
      autoMutations: true, virtualTime: true });
    const shell = nativeShell(fixture);
    nativeComposer(fixture, shell);
    const context = createDafeiyuMobileContext(fixture.env);
    const removeSurface = context.install(cssText);
    fixture.flush();
    fixture.document.querySelector('[data-dafeiyu-mobile-control="sidebar-toggle"]').rectangle = { x: 0, y: 0, width: 36, height: 36 };
    const connection = connectionFixture('connected');
    const removeStartup = createDafeiyuStartup(fixture.env, { readContext: context.readContext, connection: connection.service });
    fixture.flush();
    const overlay = fixture.document.querySelector('[data-dafeiyu-mobile-layout="startup-overlay"]');
    assert.equal(overlay.getAttribute('data-dafeiyu-startup-state'), 'ready');
    assert.equal(overlay.hidden, true);
    assert.equal(fixture.pendingTimers(), 0);
    removeStartup(); removeSurface(); fixture.flush();
    assert.equal(fixture.listenerCount(), 0);
  }

  const fixture = browser({ host: validHost(), autoMutations: true, virtualTime: true });
  const context = createDafeiyuMobileContext(fixture.env);
  const connection = connectionFixture();
  const dispose = createDafeiyuStartup(fixture.env, { readContext: context.readContext, connection: connection.service });
  fixture.flush();
  const overlay = fixture.document.querySelector('[data-dafeiyu-mobile-layout="startup-overlay"]');
  fixture.advance(44_999);
  assert.equal(overlay.getAttribute('data-dafeiyu-startup-state'), 'waiting');
  fixture.advance(1);
  assert.equal(overlay.getAttribute('data-dafeiyu-startup-state'), 'failed');
  const retry = overlay.querySelector('[data-dafeiyu-mobile-control="startup-retry"]');
  assert.equal(retry.disabled, false);
  retry.click(); fixture.flush();
  assert.equal(connection.reconnects, 1);
  assert.equal(overlay.getAttribute('data-dafeiyu-startup-state'), 'waiting');
  overlay.querySelector('[data-dafeiyu-mobile-control="startup-leave"]').click(); fixture.flush();
  assert.equal(overlay.hidden, true);
  assert.notEqual(overlay.getAttribute('data-dafeiyu-startup-state'), 'ready');
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-startup'), null);
  assert.equal(fixture.pendingTimers(), 0);
  connection.publish('connected'); fixture.flush(); fixture.advance(60_000);
  assert.equal(overlay.hidden, true);
  dispose(); dispose(); fixture.flush();
  assert.equal(connection.cleanups, 1);
  assert.equal(overlay.isConnected, false);
  assert.equal(fixture.listenerCount(), 0);
  assert.equal(fixture.activeObservers(), 0);
});

test('startup stays absent on ordinary PC pages and pending readiness work stops at disposal', () => {
  for (const options of [{}, { width: 390, userAgent: 'Mozilla/5.0 (Linux; Android 16) Mobile' }]) {
    const fixture = browser({ ...options, autoMutations: true, virtualTime: true });
    nativeShell(fixture);
    const context = createDafeiyuMobileContext(fixture.env);
    const connection = connectionFixture('connected');
    const dispose = createDafeiyuStartup(fixture.env, { readContext: context.readContext, connection: connection.service });
    fixture.flush();
    assert.equal(fixture.document.querySelector('[data-dafeiyu-mobile-layout="startup-overlay"]'), null);
    assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-startup'), null);
    assert.equal(fixture.pendingTimers(), 0);
    dispose(); fixture.flush();
    assert.equal(fixture.listenerCount(), 0);
    assert.equal(fixture.activeObservers(), 0);
  }
  const fixture = browser({ host: validHost(), autoMutations: true, virtualTime: true });
  nativeShell(fixture);
  fixture.document.documentElement.setAttribute('data-dafeiyu-startup', 'original-host-state');
  const context = createDafeiyuMobileContext(fixture.env);
  const connection = connectionFixture();
  const dispose = createDafeiyuStartup(fixture.env, { readContext: context.readContext, connection: connection.service });
  const overlay = fixture.document.querySelector('[data-dafeiyu-mobile-layout="startup-overlay"]');
  assert.ok(overlay);
  dispose(); fixture.flush(); fixture.advance(60_000); connection.publish('connected'); fixture.flush();
  assert.equal(overlay.isConnected, false);
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-startup'), 'original-host-state');
  assert.equal(fixture.activeObservers(), 0);
  assert.equal(fixture.listenerCount(), 0);
});

test('startup preview replay accepts only the exact preview parent and a bounded message contract', () => {
  const fixture = startupPreviewFixture();
  assert.equal(fixture.status(), 'ready');
  assert.equal(fixture.overlay.hidden, true);
  const invalidOrigins = ['http://localhost:8791', 'https://127.0.0.1:8791', 'http://127.0.0.1:8792',
    'http://127.0.0.1:8791.evil.invalid', 'null', ''];
  for (const origin of invalidOrigins) fixture.replay(1, { origin });
  for (const source of [null, {}, fixture.env]) fixture.replay(1, { source });
  for (const data of [null, [], 'replay', {},
    { type: 'dafeiyu-startup-preview', version: 2, action: 'replay', requestId: 1 },
    { type: 'dafeiyu-startup-preview', version: 1, action: 'reconnect', requestId: 1 },
    ...[0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1, Infinity, '1', undefined].map(requestId => ({
      type: 'dafeiyu-startup-preview', version: 1, action: 'replay', requestId,
    }))]) fixture.replay(1, { data });
  fixture.env.parent = fixture.env;
  fixture.replay(1, { source: fixture.env });
  fixture.env.parent = fixture.previewParent;
  assert.equal(fixture.status(), 'ready');
  assert.equal(fixture.overlay.hidden, true);
  assert.deepEqual(fixture.notifications, []);
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.connection.reconnects, 0);
  fixture.dispose();
  assert.equal(fixture.listenerCount(), 0);

  for (const options of [{ host: validHost() }, { href: 'http://127.0.0.1:8787/' }]) {
    const other = startupPreviewFixture(options);
    other.replay(7);
    assert.deepEqual(other.notifications, [], 'A formal Android host and an ordinary desktop reject preview control');
    assert.equal(other.pendingTimers(), 0);
    assert.equal(other.connection.reconnects, 0);
    other.dispose();
    assert.equal(other.listenerCount(), 0);
  }
});

test('startup preview replay keeps the real connection ready and completes only the finite presentation', () => {
  const fixture = startupPreviewFixture();
  const expected = state => ({ data: { type: 'dafeiyu-startup-preview-state', version: 1, requestId: 17, state },
    origin: 'http://127.0.0.1:8791' });
  fixture.replay(17);
  assert.equal(fixture.status(), 'waiting');
  assert.equal(fixture.overlay.hidden, false);
  assert.equal(fixture.overlay.inert, false);
  assert.equal(fixture.connection.service.state.getSnapshot(), 'connected');
  assert.deepEqual(fixture.notifications, [expected('playing')]);
  fixture.advance(1799);
  assert.equal(fixture.status(), 'waiting');
  fixture.advance(1);
  assert.equal(fixture.status(), 'revealing');
  assert.equal(fixture.overlay.inert, true);
  assert.equal(fixture.overlay.style.pointerEvents, 'none');
  fixture.advance(739);
  assert.equal(fixture.status(), 'revealing');
  fixture.advance(1);
  assert.equal(fixture.status(), 'ready');
  assert.equal(fixture.overlay.hidden, true);
  assert.deepEqual(fixture.notifications, [expected('playing'), expected('complete')]);
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.connection.reconnects, 0);
  fixture.dispose(); fixture.advance(60_000);
  assert.equal(fixture.notifications.length, 2);
  assert.equal(fixture.connection.cleanups, 1);
  assert.equal(fixture.listenerCount(), 0);
});

test('startup preview replay replacement, cancellation and disposal cannot leave stale transitions', () => {
  const fixture = startupPreviewFixture();
  const states = () => fixture.notifications.map(({ data }) => [data.requestId, data.state]);
  fixture.replay(21); fixture.advance(500);
  fixture.replay(22);
  assert.deepEqual(states(), [[21, 'playing'], [21, 'cancelled'], [22, 'playing']]);
  fixture.advance(1300);
  assert.equal(fixture.status(), 'waiting', 'The replaced hold timer must not reveal the later replay');
  fixture.advance(500);
  assert.equal(fixture.status(), 'revealing');
  fixture.replay(23);
  assert.equal(fixture.status(), 'waiting');
  fixture.advance(740);
  assert.equal(fixture.status(), 'waiting', 'The replaced reveal timer must not complete the later replay');
  fixture.overlay.querySelector('[data-dafeiyu-mobile-control="startup-leave"]').click(); fixture.flush();
  assert.equal(fixture.overlay.hidden, true);
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-startup'), null);
  fixture.advance(60_000);
  assert.deepEqual(states(), [[21, 'playing'], [21, 'cancelled'], [22, 'playing'], [22, 'cancelled'],
    [23, 'playing'], [23, 'cancelled']]);
  fixture.replay(24);
  assert.equal(fixture.status(), 'waiting', 'An explicit later replay can follow cancellation');
  fixture.dispose(); fixture.advance(60_000); fixture.replay(25);
  assert.equal(fixture.overlay.isConnected, false);
  assert.equal(fixture.pendingTimers(), 0);
  assert.deepEqual(states().slice(-2), [[24, 'playing'], [24, 'cancelled']]);
  assert.equal(fixture.connection.reconnects, 0);
  assert.equal(fixture.activeObservers(), 0);
  assert.equal(fixture.listenerCount(), 0);
});

test('startup preview reports actual unavailability and respects reduced-motion controls', () => {
  const fixture = startupPreviewFixture();
  fixture.connection.publish('disconnected'); fixture.flush();
  fixture.replay(31);
  assert.deepEqual(fixture.notifications.map(({ data }) => [data.requestId, data.state]), [[31, 'unavailable']]);
  assert.equal(fixture.pendingTimers(), 0);
  fixture.connection.publish('connected'); fixture.flush();
  fixture.replay(32); fixture.advance(1800);
  assert.equal(fixture.status(), 'revealing');
  fixture.connection.publish('disconnected'); fixture.flush();
  assert.equal(fixture.status(), 'failed');
  fixture.advance(60_000);
  assert.equal(fixture.status(), 'failed');
  assert.deepEqual(fixture.notifications.map(({ data }) => [data.requestId, data.state]),
    [[31, 'unavailable'], [32, 'playing'], [32, 'unavailable']]);
  assert.equal(fixture.pendingTimers(), 0);
  assert.equal(fixture.connection.reconnects, 0);
  fixture.dispose();
  assert.equal(fixture.listenerCount(), 0);

  for (const options of [{ reducedMotion: true }, { href: 'http://127.0.0.1:8787/?dafeiyu-preview=mobile&dafeiyu-motion=false' }]) {
    const reduced = startupPreviewFixture(options);
    reduced.replay(33); reduced.advance(1800);
    assert.equal(reduced.status(), 'ready');
    assert.equal(reduced.overlay.hidden, true);
    assert.equal(reduced.pendingTimers(), 0);
    assert.deepEqual(reduced.notifications.map(({ data }) => data.state), ['playing', 'complete']);
    assert.equal(reduced.connection.reconnects, 0);
    reduced.dispose();
    assert.equal(reduced.listenerCount(), 0);
  }
});

test('the real Client apply installs under a Cordis effect and its disposer removes the surface', () => {
  const fixture = browser({ host: validHost({ mode: 'online' }) });
  const effects = [];
  const context = createDafeiyuMobileContext(fixture.env);
  const plugin = createDafeiyuMobileUiPlugin(context, cssText);
  const ctx = { effect(callback) { const dispose = callback(); effects.push(dispose); return dispose; } };
  plugin.apply(ctx);
  assert.equal(effects.length, 1);
  assert.equal(pluginStyles(fixture).length, 1);
  effects[0]();
  assertInactive(fixture);
  assert.equal(fixture.listenerCount(), 0);
});

test('Client and manifest declare the services that the active footer reads from injected context', async () => {
  const fixture = browser({ host: validHost() });
  nativeShell(fixture);
  const manifest = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'));
  assert.deepEqual(manifest.dsh.client.inject, ['@deepseek-ai/dsh-client-ui-layout', '@deepseek-ai/dsh-client-connection']);
  const connection = { state: { getSnapshot() { return 'connected'; } } };
  const layout = { toggleSidebar() {} };
  const effects = [];
  let connectionReads = 0;
  let footerDisposals = 0;
  const plugin = createDafeiyuMobileUiPlugin(createDafeiyuMobileContext(fixture.env), cssText,
    (env, options) => {
      assert.equal(env, fixture.env);
      assert.equal(options.connection, connection);
      assert.equal(typeof options.closeDrawer, 'function');
      return { update() {}, dispose() { footerDisposals++; } };
    });
  assert.deepEqual(plugin.inject, ['layout', 'connection']);
  const ctx = { effect(callback) { const dispose = callback(); effects.push(dispose); return dispose; } };
  // Cordis rejects lazy service reads without a declared injection. This guard
  // checks that contract only; it does not stand in for a real Cordis startup.
  for (const [service, value] of [['layout', layout], ['connection', connection]]) {
    Object.defineProperty(ctx, service, { get() {
      assert.ok(plugin.inject.includes(service), `Undeclared injected service read: ${service}`);
      if (service === 'connection') connectionReads++;
      return value;
    } });
  }
  assert.equal(connectionReads, 0, 'Factory creation must not read a lazy context service');
  plugin.apply(ctx);
  assert.equal(connectionReads, 1);
  assert.equal(effects.length, 1);
  assert.equal(pluginStyles(fixture).length, 1);
  fixture.mutation();
  assert.equal(connectionReads, 1, 'Ordinary DOM reconciliation reuses the installed footer');
  effects[0]();
  assert.equal(footerDisposals, 1);
  assert.equal(fixture.listenerCount(), 0);
  assertInactive(fixture);
});

test('the package Host imports and applies in Node without touching browser globals', () => {
  const script = `
    for (const name of ['window', 'document']) Object.defineProperty(globalThis, name, {
      configurable: true, get() { throw new Error('Host accessed browser global ' + name); }
    });
    const host = await import('dafeiyu-mobile-ui');
    const plugin = host.default ?? host;
    if (typeof plugin.apply !== 'function') throw new Error('Host has no apply entry');
    plugin.apply({});
    process.stdout.write('Host Node import/apply PASS\\n');
  `;
  const result = spawnSync(process.execPath, ['--input-type=module', '--eval', script], {
    cwd: packageRoot, encoding: 'utf8', env: { ...process.env }, timeout: 5000,
  });
  assert.equal(result.error, undefined);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout.trim(), 'Host Node import/apply PASS');
});

test('the actual built ModuleLoader resource stays lazy and applies the same explicit gate in a VM', async () => {
  const manifest = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'));
  assert.equal(manifest.dsh.client.platform, 'web');
  const clientPath = manifest.exports['./client'];
  assert.equal(typeof clientPath, 'string');
  const source = await readFile(new URL(`../${clientPath.replace(/^\.\//, '')}`, import.meta.url), 'utf8');
  const fixture = browser({ href: 'http://127.0.0.1:8787/?dafeiyu-preview=mobile&dafeiyu-theme=dark&dafeiyu-material=false&dafeiyu-motion=false' });
  const header = fixture.create('section', { 'data-slot': 'conversation.session.header' });
  const corner = fixture.create('section', { 'data-slot': 'conversation.session.header.corner' }, header);
  const rightbar = fixture.create('button', { 'data-sidebar-right-expand': '', 'aria-label': '打开右侧边栏' }, corner);
  const ordinaryAction = fixture.create('button', { 'aria-label': 'Copy conversation link' }, header);
  const tab = fixture.create('button', { role: 'tab', 'aria-label': 'Conversation tab' }, header);
  const activity = fixture.create('section', { 'data-slot': 'conversation.input.activity' });
  const microphone = fixture.create('button', { 'aria-label': '开始录音' }, activity);
  const ordinaryActivity = fixture.create('button', { 'aria-label': 'Token usage' }, activity);
  const rows = [];
  fixture.env.__ModuleLoader__ = { load(row) { rows.push(row); } };
  const sandbox = createContext(fixture.env);
  new Script(source, { filename: clientPath }).runInContext(sandbox, { timeout: 1000 });
  assert.equal(rows.length, 1);
  assert.equal(rows[0].id, manifest.name);
  assert.equal(pluginStyles(fixture).length, 0, 'Bundle registration must not install CSS');
  assert.equal(fixture.listenerCount(), 0, 'Bundle registration must not install listeners');
  const imported = rows[0].factory(() => { assert.fail('Self-contained Client must not require an external runtime'); });
  const plugin = imported.default ?? imported;
  const effects = [];
  plugin.apply({ effect(callback) { const dispose = callback(); effects.push(dispose); return dispose; } });
  assert.equal(pluginStyles(fixture).length, 1);
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-surface'), 'mobile');
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-theme'), 'dark');
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-material'), 'false');
  assert.equal(fixture.document.documentElement.getAttribute('data-dafeiyu-motion'), 'false');
  assert.equal(rightbar.getAttribute('data-dafeiyu-mobile-control'), 'rightbar-toggle');
  assert.equal(microphone.getAttribute('data-dafeiyu-mobile-control'), 'microphone');
  assert.equal(ordinaryAction.getAttribute('data-dafeiyu-mobile-control'), 'session-action');
  assert.equal(ordinaryActivity.getAttribute('data-dafeiyu-mobile-control'), 'activity');
  assert.equal(tab.getAttribute('data-dafeiyu-mobile-control'), null);
  for (const dispose of effects.reverse()) dispose();
  assertInactive(fixture);
  for (const control of [rightbar, microphone, ordinaryAction, ordinaryActivity, tab]) {
    assert.equal(control.getAttribute('data-dafeiyu-mobile-control'), null);
  }
  assert.equal(fixture.listenerCount(), 0);
});
