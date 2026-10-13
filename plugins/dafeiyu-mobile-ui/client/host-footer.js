/* Optional Android display bridge. No credentials, commands, or task authority. */
export function createDafeiyuMobileFooter(env = globalThis, options = {}) {
  const document = env.document;
  const layout = 'data-dafeiyu-mobile-layout';
  const control = 'data-dafeiyu-mobile-control';
  let footer, parent, panel, count, detail, appButton, appLabel, dshButton;
  let host = null;
  let state = null;
  let unwatch = null;
  let generation = 0;
  let disposed = false;
  const text = (element, value) => { if (element && element.textContent !== value) element.textContent = value; };
  const chinese = () => (document.documentElement.getAttribute('lang') || '').toLowerCase().startsWith('zh');
  // Original paths: pinned Fluent Wrench24 and official DSH Settings16.
  // Exact sources and full MIT notices are preserved in THIRD_PARTY_NOTICES.md.
  const wrenchPaths = ["M11.0002 7.5C11.0002 4.46243 13.4627 2 16.5002 2C17.267 2 17.999 2.15735 18.6641 2.44223C18.8929 2.54023 19.0582 2.74534 19.1053 2.98974C19.1524 3.23414 19.0751 3.48599 18.8991 3.66198L16.3111 6.25L17.7505 7.68934L20.3384 5.10142C20.5144 4.92542 20.7663 4.8482 21.0107 4.8953C21.2551 4.94241 21.4602 5.10771 21.5582 5.33652C21.8429 6.00153 22.0002 6.73337 22.0002 7.5C22.0002 10.5376 19.5378 13 16.5002 13C16.058 13 15.6272 12.9477 15.214 12.8486L6.90165 21.1609C5.78033 22.2823 3.96231 22.2823 2.84099 21.1609C1.71967 20.0396 1.71967 18.2216 2.84099 17.1003L11.1523 8.78899C11.0528 8.37496 11.0002 7.94322 11.0002 7.5ZM16.5002 3.5C14.2911 3.5 12.5002 5.29086 12.5002 7.5C12.5002 7.94506 12.5726 8.37169 12.7057 8.76964C12.7959 9.0393 12.7258 9.33677 12.5248 9.53783L3.90165 18.1609C3.36612 18.6965 3.36612 19.5648 3.90165 20.1003C4.43718 20.6358 5.30546 20.6358 5.84099 20.1003L14.465 11.4763C14.6659 11.2754 14.9632 11.2053 15.2327 11.2952C15.6301 11.4279 16.056 11.5 16.5002 11.5C18.7094 11.5 20.5002 9.70914 20.5002 7.5C20.5002 7.35886 20.4929 7.21955 20.4787 7.08239L18.6344 8.92678C18.1462 9.41493 17.3547 9.41493 16.8666 8.92678L15.0737 7.13388C14.5855 6.64573 14.5855 5.85427 15.0737 5.36612L16.9183 3.52153C16.781 3.5073 16.6415 3.5 16.5002 3.5Z"];
  const gearPaths = ["M8 9.75012C8.9665 9.75012 9.75 8.96662 9.75 8.00012C9.75 7.03362 8.9665 6.25012 8 6.25012C7.0335 6.25012 6.25 7.03362 6.25 8.00012C6.25 8.96662 7.0335 9.75012 8 9.75012Z","M13.0107 7.79377C12.9505 7.89401 12.9205 7.94413 12.9205 7.99951C12.9205 8.0549 12.9505 8.10502 13.0106 8.20528L13.9849 9.83006C14.045 9.93029 14.0751 9.9804 14.0751 10.0358C14.0751 10.0911 14.045 10.1413 13.9849 10.2415L13.0037 11.8777C12.9468 11.9726 12.9184 12.0201 12.8725 12.0461C12.8267 12.072 12.7713 12.072 12.6607 12.072H10.6704C10.5598 12.072 10.5045 12.072 10.4586 12.098C10.4128 12.1239 10.3843 12.1714 10.3274 12.2662L9.33825 13.9142C9.28133 14.009 9.25287 14.0564 9.20703 14.0823C9.16118 14.1083 9.10588 14.1083 8.99529 14.1083H7.00486C6.89426 14.1083 6.83896 14.1083 6.79312 14.0823C6.74727 14.0564 6.71881 14.009 6.6619 13.9142L5.67273 12.2662C5.61581 12.1714 5.58735 12.1239 5.54151 12.098C5.49566 12.072 5.44036 12.072 5.32977 12.072H3.33945C3.2288 12.072 3.17347 12.072 3.12761 12.0461C3.08176 12.0201 3.0533 11.9726 2.9964 11.8777L2.0152 10.2415C1.9551 10.1413 1.92505 10.0911 1.92505 10.0358C1.92505 9.9804 1.9551 9.93029 2.0152 9.83006L2.98951 8.20528C3.04963 8.10502 3.07969 8.0549 3.07969 7.99951C3.07968 7.94413 3.04961 7.89401 2.98946 7.79377L2.01529 6.17011C1.95514 6.06987 1.92507 6.01975 1.92507 5.96437C1.92506 5.90899 1.95512 5.85886 2.01524 5.7586L2.9964 4.1224C3.0533 4.0275 3.08176 3.98005 3.12761 3.95408C3.17347 3.92811 3.2288 3.92811 3.33945 3.92811H5.32977C5.44036 3.92811 5.49566 3.92811 5.54151 3.90216C5.58735 3.87621 5.61581 3.82879 5.67273 3.73397L6.6619 2.08599C6.71881 1.99116 6.74727 1.94375 6.79312 1.9178C6.83896 1.89185 6.89426 1.89185 7.00486 1.89185H8.99529C9.10588 1.89185 9.16118 1.89185 9.20703 1.9178C9.25287 1.94375 9.28133 1.99116 9.33825 2.08599L10.3274 3.73397C10.3843 3.82879 10.4128 3.87621 10.4586 3.90216C10.5045 3.92811 10.5598 3.92811 10.6704 3.92811H12.6607C12.7713 3.92811 12.8267 3.92811 12.8725 3.95408C12.9184 3.98005 12.9468 4.0275 13.0037 4.1224L13.9849 5.7586C14.045 5.85886 14.0751 5.90899 14.0751 5.96437C14.0751 6.01975 14.045 6.06987 13.9849 6.17011L13.0107 7.79377Z"];
  function icon(kind) {
    const box = document.createElement('span');
    box.setAttribute(layout, 'footer-icon');
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', kind === 'wrench' ? '0 0 24 24' : '0 0 16 16');
    svg.setAttribute('width', '20'); svg.setAttribute('height', '20');
    svg.setAttribute('aria-hidden', 'true'); svg.setAttribute('focusable', 'false');
    svg.setAttribute('fill', kind === 'wrench' ? 'currentColor' : 'none');
    if (kind !== 'wrench') { svg.setAttribute('stroke', 'currentColor'); svg.setAttribute('stroke-width', '1'); }
    for (const d of kind === 'wrench' ? wrenchPaths : gearPaths) {
      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('d', d); svg.appendChild(path);
    }
    box.appendChild(svg);
    return box;
  }
  const source = () => {
    const slot = document.querySelector('[data-slot="sidebar.settings"]');
    return slot?.closest('button') || slot?.querySelector('button') || null;
  };
  const usable = button => !!button && !button.disabled && button.getAttribute('aria-disabled') !== 'true';

  function readHost() {
    try {
      const client = env.__DAFEIYU_DSH_CLIENT__;
      // Explicit URL previews never gain application actions from a stray global.
      if (client?.version !== 1 || client.platform !== 'android' || client.surface !== 'mobile' ||
          !['local', 'online'].includes(client.mode) || !['system', 'light', 'dark'].includes(client.theme) ||
          typeof client.material !== 'boolean' || typeof client.motion !== 'boolean') return null;
      const bridge = env.__DAFEIYU_DSH_HOST__;
      if (bridge?.version !== 1 || bridge.platform !== 'android' || bridge.document_bound === false ||
          !Array.isArray(bridge.capabilities)) return null;
      return bridge;
    } catch { return null; }
  }

  function normalizeState(raw) {
    try {
      if (!raw || raw.protocol !== 'dafeiyu-go.connection.v1' || typeof raw.server_connected !== 'boolean' ||
          !['paired', 'unpaired', 'awaiting_approval', 'unknown', 'revoked'].includes(raw.pairing_status)) return null;
      const remote = raw.remote_device;
      const presence = raw.capabilities?.presence === true && raw.server_connected && raw.pairing_status === 'paired' &&
        typeof raw.group_id === 'string' && raw.group_id.length > 0;
      if (remote !== null && remote !== undefined && (typeof remote.id !== 'string' ||
          !['online', 'offline', 'unknown'].includes(remote.online))) return null;
      // Count a roster only when its complete active membership and each device's
      // presence are known. A single counterpart is not a complete group census.
      const roster = Array.isArray(raw.members) && raw.members.every(member =>
        member && typeof member.id === 'string' && typeof member.active === 'boolean' &&
        ['online', 'offline', 'unknown'].includes(member.online)) ? raw.members : null;
      const active = roster?.filter(member => member.active);
      const complete = presence && active && active.every(member => member.online !== 'unknown') &&
        new Set(active.map(member => member.id)).size === active.length;
      return {
        server: raw.server_connected,
        paired: raw.pairing_status === 'paired',
        count: complete ? active.filter(member => member.online === 'online').length : null,
        remoteName: typeof remote?.name === 'string' ? remote.name.trim().slice(0, 128) : '',
        remoteOnline: presence && remote ? remote.online : 'unknown',
      };
    } catch { return null; }
  }

  function render() {
    if (!footer || disposed) return;
    const zh = chinese();
    text(count, state?.count === null || state === null ? (zh ? '未知' : 'Unknown') : String(state.count));
    let value;
    if (state) {
      value = state.server ? (zh ? 'Aries 已连接' : 'Aries connected') : (zh ? 'Aries 未连接' : 'Aries disconnected');
      if (state.paired) {
        const name = state.remoteName || (zh ? '对方尚未共享设备名称' : 'Peer has not shared its name');
        const status = state.remoteOnline === 'online' ? (zh ? '在线' : 'online') :
          state.remoteOnline === 'offline' ? (zh ? '离线' : 'offline') : (zh ? '状态未知' : 'status unknown');
        value += ` · ${name} · ${status}`;
      }
    } else {
      const live = options.connection?.state?.getSnapshot?.();
      value = live === 'connected' ? (zh ? 'DSH 已连接 · 设备状态未知' : 'DSH connected · device state unknown') :
        live === 'disconnected' ? (zh ? 'DSH 未连接 · 人数未知' : 'DSH disconnected · count unknown') :
        (zh ? '设备状态未知' : 'Device state unknown');
    }
    text(detail, value);
    const canOpen = host?.capabilities.includes('app.settings.open') && typeof host.openAppSettings === 'function';
    if (appButton.disabled !== !canOpen) appButton.disabled = !canOpen;
    text(appLabel, canOpen ? (zh ? '软件设置' : 'App settings') : (zh ? '软件设置（预留）' : 'App settings (reserved)'));
    const canOpenConnection = host?.capabilities.includes('connection.settings.open') && typeof host.openConnectionSettings === 'function';
    if (panel.disabled !== !canOpenConnection) panel.disabled = !canOpenConnection;
    const panelLabel = zh ? '在线设备状态，打开线上服务设置' : 'Online device state, open connection settings';
    const accessible = canOpenConnection ? panelLabel : (zh ? '在线设备状态，线上服务设置（预留）' : 'Online device state, connection settings (reserved)');
    if (panel.getAttribute('aria-label') !== accessible) panel.setAttribute('aria-label', accessible);
    const dshDisabled = !usable(source());
    if (dshButton.disabled !== dshDisabled) dshButton.disabled = dshDisabled;
  }

  function bindHost() {
    const next = readHost();
    if (host === next) return;
    unwatch?.(); unwatch = null;
    host = next; state = null;
    const current = ++generation;
    if (host?.capabilities.includes('connection.state') && typeof host.getConnectionState === 'function') {
      let version = 0;
      const accept = raw => {
        if (disposed || current !== generation) return;
        state = normalizeState(raw); render();
      };
      try {
        const pending = host.getConnectionState();
        const requested = version;
        Promise.resolve(pending).then(raw => { if (requested === version) accept(raw); },
          () => { if (requested === version) accept(null); });
        if (typeof host.subscribeConnectionState === 'function') {
          const cleanup = host.subscribeConnectionState(raw => { ++version; accept(raw); });
          if (typeof cleanup === 'function') unwatch = cleanup;
        }
      } catch { accept(null); }
    }
  }

  function openDsh() {
    const original = source();
    if (!usable(original)) return;
    options.closeDrawer?.();
    original.click();
  }
  function openApp() {
    if (!host?.capabilities.includes('app.settings.open') || typeof host.openAppSettings !== 'function') return;
    try {
      options.closeDrawer?.();
      Promise.resolve(host.openAppSettings()).catch(() => {});
    } catch { /* The host owns its visible error, never fabricate a success. */ }
  }
  function openConnection() {
    if (!host?.capabilities.includes('connection.settings.open') || typeof host.openConnectionSettings !== 'function') return;
    try {
      options.closeDrawer?.();
      Promise.resolve(host.openConnectionSettings()).catch(() => {});
    } catch { /* Native host owns the error. No settings or pairing state is fabricated. */ }
  }

  function remove() {
    dshButton?.removeEventListener('click', openDsh);
    appButton?.removeEventListener('click', openApp);
    panel?.removeEventListener('click', openConnection);
    footer?.remove();
    footer = parent = panel = count = detail = appButton = appLabel = dshButton = null;
  }

  function update(parts) {
    if (disposed) return;
    const target = parts.sidebar?.querySelector('[data-slot="sidebar"]')?.firstElementChild ||
      document.querySelector('[data-slot="sidebar"]')?.firstElementChild;
    if (!target) { remove(); return; }
    if (target !== parent) {
      remove(); parent = target;
      footer = document.createElement('footer');
      footer.setAttribute(layout, 'mobile-footer');
      panel = document.createElement('button');
      panel.type = 'button';
      panel.setAttribute(layout, 'presence-panel');
      panel.setAttribute(control, 'connection-settings');
      panel.setAttribute('aria-label', chinese() ? '在线设备状态' : 'Online device state');
      panel.addEventListener('click', openConnection);
      const heading = document.createElement('div');
      heading.setAttribute(layout, 'presence-heading');
      const title = document.createElement('span');
      text(title, chinese() ? '在线人数' : 'Online count');
      count = document.createElement('strong');
      heading.appendChild(title); heading.appendChild(count);
      detail = document.createElement('p');
      detail.setAttribute('role', 'status');
      panel.appendChild(heading); panel.appendChild(detail);
      const actions = document.createElement('div');
      actions.setAttribute(layout, 'footer-actions');
      dshButton = document.createElement('button');
      dshButton.type = 'button';
      dshButton.setAttribute(control, 'dsh-settings');
      const dshLabel = document.createElement('span');
      dshLabel.setAttribute(layout, 'footer-label');
      text(dshLabel, chinese() ? 'DSH 内置设置' : 'DSH settings');
      dshButton.appendChild(icon('wrench')); dshButton.appendChild(dshLabel);
      dshButton.addEventListener('click', openDsh);
      appButton = document.createElement('button');
      appButton.type = 'button';
      appButton.setAttribute(control, 'app-settings');
      appLabel = document.createElement('span');
      appLabel.setAttribute(layout, 'footer-label');
      appButton.appendChild(icon('gear')); appButton.appendChild(appLabel);
      appButton.addEventListener('click', openApp);
      actions.appendChild(dshButton); actions.appendChild(appButton);
      footer.appendChild(panel); footer.appendChild(actions);
      parent.appendChild(footer);
    }
    bindHost(); render();
  }

  const unwatchConnection = options.connection?.state?.subscribe?.(() => render());
  function onHostChange() { bindHost(); render(); }
  env.addEventListener?.('dafeiyu-dsh-host-change', onHostChange);
  return {
    update,
    dispose() {
      if (disposed) return;
      disposed = true; ++generation;
      env.removeEventListener?.('dafeiyu-dsh-host-change', onHostChange);
      unwatch?.(); unwatchConnection?.();
      unwatch = null; host = state = null;
      remove();
    },
  };
}
