/* Page-local readiness presentation. Never starts, stops, or authenticates DSH. */
export function createDafeiyuStartup(env = globalThis, options = {}) {
  const document = env.document;
  if (!document?.documentElement || typeof options.readContext !== 'function') return () => {};
  const root = document.documentElement;
  const layout = 'data-dafeiyu-mobile-layout';
  const stateAttribute = 'data-dafeiyu-startup-state';
  const rootAttribute = 'data-dafeiyu-startup';
  const previousRoot = root.getAttribute(rootAttribute);
  let installedRoot = previousRoot;
  let overlay, focus, heading, detail, actions, retry, leave;
  let timeout = null, finishTimer = null;
  let replayTimer = null, replayRequest = null;
  let replayHolding = false;
  let observer = null;
  let disposed = false, dismissed = false, scheduled = false;
  let pageActive = true;
  let presentationProbe = null, previousProbe = null;
  let nativeHost = null, reportedReady = null, reportGeneration = 0;
  const reduced = env.matchMedia?.('(prefers-reduced-motion: reduce)');
  const zh = () => (root.getAttribute('lang') || '').toLowerCase().startsWith('zh');
  const setText = (element, value) => { if (element.textContent !== value) element.textContent = value; };
  const state = () => overlay?.getAttribute(stateAttribute);
  const context = () => { try { return options.readContext(); } catch { return null; } };
  const motion = () => context()?.motion === true && !reduced?.matches;
  const connected = () => { try { return options.connection?.state?.getSnapshot?.() === 'connected'; } catch { return false; } };
  const previewOrigin = 'http://127.0.0.1:8791';
  function nativeContext() {
    const current = context();
    return current && current.preview !== true && current.platform === 'android' &&
      current.surface === 'mobile' && ['local', 'online'].includes(current.mode);
  }
  function notifyNative(ready) {
    let host;
    try { host = env.__DAFEIYU_DSH_HOST__; } catch { return; }
    if (!nativeContext() || host?.version !== 1 || host.platform !== 'android' ||
        host.document_bound !== true || !host.capabilities?.includes('app.web.presentation')) return;
    if (nativeHost !== host) { nativeHost = host; reportedReady = null; ++reportGeneration; }
    const method = ready ? 'notifyPresentationReady' : 'notifyPresentationUnavailable';
    if (reportedReady === ready || typeof host[method] !== 'function') return;
    reportedReady = ready;
    const generation = reportGeneration;
    try {
      Promise.resolve(host[method]()).catch(() => {
        if (generation === reportGeneration && nativeHost === host && reportedReady === ready) reportedReady = null;
      });
    } catch { reportedReady = null; }
  }
  function installPresentationProbe() {
    if (presentationProbe || !nativeContext()) return;
    try {
      previousProbe = Object.getOwnPropertyDescriptor(env, '__DAFEIYU_DSH_PRESENTATION__');
      if (previousProbe && !previousProbe.configurable) return;
      presentationProbe = Object.freeze({ version: 1,
        isReady: () => !disposed && !dismissed && nativeContext() && pageReady() });
      Object.defineProperty(env, '__DAFEIYU_DSH_PRESENTATION__',
        { configurable: true, writable: false, value: presentationProbe });
    } catch { presentationProbe = null; }
  }
  function restorePresentationProbe() {
    if (!presentationProbe) return;
    try {
      if (Object.getOwnPropertyDescriptor(env, '__DAFEIYU_DSH_PRESENTATION__')?.value === presentationProbe) {
        if (previousProbe) Object.defineProperty(env, '__DAFEIYU_DSH_PRESENTATION__', previousProbe);
        else delete env.__DAFEIYU_DSH_PRESENTATION__;
      }
    } catch { /* A later host definition keeps its ownership. */ }
    presentationProbe = previousProbe = null;
  }
  function notifyReplay(value) {
    if (replayRequest === null) return;
    try {
      env.parent?.postMessage({ type: 'dafeiyu-startup-preview-state', version: 1,
        requestId: replayRequest, state: value }, previewOrigin);
    } catch { /* Preview feedback never affects the real connection. */ }
  }
  function clearReplay(value) {
    if (replayTimer !== null) env.clearTimeout(replayTimer);
    replayTimer = null;
    replayHolding = false;
    if (value) notifyReplay(value);
    replayRequest = null;
  }
  const rootState = value => {
    if (root.getAttribute(rootAttribute) !== installedRoot) return;
    if (value === null) root.removeAttribute(rootAttribute);
    else if (installedRoot !== value) root.setAttribute(rootAttribute, value);
    installedRoot = value;
  };
  const clearTimer = name => {
    if (name === 'timeout') { if (timeout !== null) env.clearTimeout(timeout); timeout = null; }
    else { if (finishTimer !== null) env.clearTimeout(finishTimer); finishTimer = null; }
  };
  function setState(value) {
    if (state() !== value) overlay.setAttribute(stateAttribute, value);
    rootState(value);
    overlay.style.pointerEvents = value === 'waiting' || value === 'failed' ? 'auto' : 'none';
    overlay.setAttribute('aria-busy', String(value === 'waiting'));
  }
  function createPart(tag, name, parent) {
    const element = document.createElement(tag);
    element.setAttribute(layout, name); parent.appendChild(element);
    return element;
  }
  function create() {
    overlay = document.createElement('section');
    overlay.setAttribute(layout, 'startup-overlay');
    overlay.setAttribute('aria-label', zh() ? 'DSH 引擎连接状态' : 'DSH engine connection status');
    focus = createPart('div', 'startup-focus', overlay);
    focus.setAttribute('aria-hidden', 'true'); setText(focus, 'DSH');
    createPart('div', 'startup-orbit', overlay).setAttribute('aria-hidden', 'true');
    heading = createPart('h2', 'startup-title', overlay);
    detail = createPart('p', 'startup-detail', overlay); detail.setAttribute('role', 'status');
    actions = createPart('div', 'startup-actions', overlay);
    retry = document.createElement('button'); retry.type = 'button';
    retry.setAttribute('data-dafeiyu-mobile-control', 'startup-retry'); retry.addEventListener('click', reconnect);
    leave = document.createElement('button'); leave.type = 'button';
    leave.setAttribute('data-dafeiyu-mobile-control', 'startup-leave'); leave.addEventListener('click', cancel);
    actions.appendChild(retry); actions.appendChild(leave);
    (document.body || root).appendChild(overlay);
    wait();
  }
  function wait() {
    clearTimer('timeout'); clearTimer('finish');
    setText(focus, 'DSH');
    overlay.hidden = false; overlay.inert = false;
    setState('waiting');
    setText(heading, zh() ? '正在连接 DSH 引擎' : 'Connecting to DSH');
    setText(detail, zh() ? '等待服务连接与页面准备就绪' : 'Waiting for the service and page to become ready');
    setText(leave, zh() ? '查看连接提示' : 'View connection details');
    setText(retry, zh() ? '重新连接' : 'Reconnect');
    retry.hidden = true;
    timeout = env.setTimeout(() => { timeout = null; fail(); }, 45_000);
  }
  function fail() {
    notifyNative(false);
    if (!overlay || dismissed || disposed) return;
    clearTimer('timeout'); clearTimer('finish');
    clearReplay('unavailable');
    setState('failed');
    overlay.hidden = false; overlay.inert = false;
    setText(heading, zh() ? 'DSH 尚未准备就绪' : 'DSH is not ready yet');
    setText(detail, zh() ? '请检查服务状态或重新连接；不会自动重启服务' : 'Check the service or reconnect. The service will not be restarted automatically.');
    retry.hidden = false;
    retry.disabled = typeof options.connection?.reconnect !== 'function';
  }
  function pageReady() {
    const page = document.querySelector('[data-slot="root"]');
    const composer = document.querySelector('[data-slot="conversation.composer.bar"]');
    const frame = document.querySelector('[data-dafeiyu-mobile-layout="frame"]');
    // The official composer slots use display:contents. Test the actual card,
    // not their zero-sized wrapper, before releasing the native waiting layer.
    const card = document.querySelector('[data-dafeiyu-mobile-layout="composer-card"]') || composer;
    const button = document.querySelector('[data-dafeiyu-mobile-control="sidebar-toggle"]');
    const r = button?.getBoundingClientRect?.();
    const f = frame?.getBoundingClientRect?.();
    const c = card?.getBoundingClientRect?.();
    const width = env.innerWidth, height = env.innerHeight;
    return !!(pageActive && page && composer && r && f && c &&
      width > 0 && height > 0 && r.width > 0 && r.height > 0 &&
      f.width > 0 && f.height > r.height && c.width > 0 && c.height > 0 &&
      c.left >= Math.max(0, f.left) - 1 && c.right <= Math.min(width, f.right) + 1 &&
      c.top >= Math.max(r.bottom, f.top) - 1 && c.bottom <= Math.min(height, f.bottom) + 1 && connected());
  }
  function finish() {
    clearTimer('finish');
    if (!overlay || disposed || dismissed || !context()) return;
    if (!pageReady()) { fail(); return; }
    setState('ready'); overlay.hidden = true; overlay.inert = true;
    clearTimer('timeout');
    clearReplay('complete');
  }
  function reveal() {
    clearTimer('timeout');
    // This reports the real engine/UI predicate, before the finite reveal;
    // Android independently reads the owned probe in the committed document.
    notifyNative(true);
    if (!motion()) { finish(); return; }
    const button = document.querySelector('[data-dafeiyu-mobile-control="sidebar-toggle"]');
    const target = button.getBoundingClientRect();
    const from = focus.getBoundingClientRect();
    overlay.style.setProperty('--df-startup-target-x', `${target.x + target.width / 2 - from.x - from.width / 2}px`);
    overlay.style.setProperty('--df-startup-target-y', `${target.y + target.height / 2 - from.y - from.height / 2}px`);
    overlay.style.setProperty('--df-startup-target-scale', String(target.width / from.width));
    // Clone only the real decorative icon, keeping its callback and DOM ownership.
    const source = button.querySelector('svg');
    if (source) {
      const icon = source.cloneNode(true);
      icon.removeAttribute('id'); icon.removeAttribute('aria-labelledby'); icon.removeAttribute('aria-describedby');
      for (const item of icon.querySelectorAll('[id], [aria-labelledby], [aria-describedby]')) {
        item.removeAttribute('id'); item.removeAttribute('aria-labelledby'); item.removeAttribute('aria-describedby');
      }
      icon.setAttribute('aria-hidden', 'true'); icon.setAttribute('focusable', 'false');
      focus.textContent = ''; focus.appendChild(icon);
    }
    setState('revealing'); overlay.inert = true;
    // CSS finish event plus bounded fallback; neither extends service readiness.
    finishTimer = env.setTimeout(finish, 740);
  }
  function onAnimationEnd(event) {
    if (event.target === focus && event.animationName === 'dfStartupReveal' && state() === 'revealing') finish();
  }
  function reconnect() {
    if (disposed || dismissed || typeof options.connection?.reconnect !== 'function') return;
    wait();
    try { options.connection.reconnect(); } catch { fail(); }
    check();
  }
  function cancel() {
    notifyNative(false);
    dismissed = true;
    clearTimer('timeout'); clearTimer('finish');
    clearReplay('cancelled');
    overlay.hidden = true; overlay.inert = true;
    // Show the original connection/error UI without claiming a ready engine.
    rootState(null);
  }
  function remove() {
    notifyNative(false);
    ++reportGeneration; nativeHost = null; reportedReady = null;
    restorePresentationProbe();
    clearTimer('timeout'); clearTimer('finish');
    clearReplay('cancelled');
    retry?.removeEventListener('click', reconnect); leave?.removeEventListener('click', cancel);
    overlay?.remove(); overlay = focus = heading = detail = actions = retry = leave = null;
    rootState(previousRoot);
  }
  function check() {
    if (disposed) return;
    if (!context()) { remove(); dismissed = false; return; }
    installPresentationProbe();
    if (dismissed) return;
    if (!overlay) create();
    if (replayHolding) {
      if (!pageReady()) fail();
      return;
    }
    if (state() === 'ready') {
      if (pageReady()) notifyNative(true);
      else fail();
      return;
    }
    if (pageReady()) {
      notifyNative(true);
      if (state() === 'revealing') { if (!motion()) finish(); }
      else reveal();
    } else {
      notifyNative(false);
      if (state() === 'revealing') fail();
    }
  }
  function schedule() {
    if (scheduled || disposed) return;
    scheduled = true;
    const enqueue = env.queueMicrotask || (fn => Promise.resolve().then(fn));
    enqueue(() => { scheduled = false; check(); });
  }
  // Only the local preview parent can replay presentation. It never calls
  // reconnect(), changes a server setting, or delays a real readiness handshake.
  function onPreviewMessage(event) {
    if (disposed || context()?.preview !== true || env.parent === env ||
        event.source !== env.parent || event.origin !== previewOrigin) return;
    const data = event.data;
    if (!data || typeof data !== 'object' || Array.isArray(data) ||
        data.type !== 'dafeiyu-startup-preview' || data.version !== 1 || data.action !== 'replay' ||
        !Number.isSafeInteger(data.requestId) || data.requestId < 1) return;
    if (!pageReady()) {
      replayRequest = data.requestId;
      clearReplay('unavailable');
      return;
    }
    clearReplay('cancelled');
    replayRequest = data.requestId;
    dismissed = false;
    if (!overlay) create();
    wait();
    replayHolding = true;
    setText(heading, zh() ? '启动动画预览' : 'Startup animation preview');
    setText(detail, zh() ? '等待动画结束后进入当前页面' : 'The current page will appear after the waiting animation');
    setText(leave, zh() ? '结束预览' : 'End preview');
    notifyReplay('playing');
    replayTimer = env.setTimeout(() => {
      replayTimer = null;
      replayHolding = false;
      check();
    }, 1800);
  }
  function onPageHide() { pageActive = false; notifyNative(false); }
  function onPageShow() { pageActive = true; check(); }
  const events = ['dafeiyu-dsh-client-change', 'dafeiyu-dsh-host-change', 'popstate', 'hashchange'];
  for (const event of events) env.addEventListener?.(event, check);
  env.addEventListener?.('resize', check);
  env.addEventListener?.('message', onPreviewMessage);
  env.addEventListener?.('pagehide', onPageHide);
  env.addEventListener?.('pageshow', onPageShow);
  reduced?.addEventListener?.('change', check);
  document.addEventListener('animationend', onAnimationEnd);
  const unsubscribe = options.connection?.state?.subscribe?.(check);
  if (env.MutationObserver) {
    observer = new env.MutationObserver(schedule);
    observer.observe(root, { childList: true, subtree: true });
  }
  check();
  return () => {
    if (disposed) return;
    disposed = true;
    for (const event of events) env.removeEventListener?.(event, check);
    env.removeEventListener?.('resize', check);
    env.removeEventListener?.('message', onPreviewMessage);
    env.removeEventListener?.('pagehide', onPageHide);
    env.removeEventListener?.('pageshow', onPageShow);
    reduced?.removeEventListener?.('change', check);
    document.removeEventListener('animationend', onAnimationEnd);
    unsubscribe?.(); observer?.disconnect(); remove();
  };
}
