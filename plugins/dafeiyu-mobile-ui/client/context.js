/*
 * Explicit mobile gate and disposable presentation-only DOM adapter.
 * Keep every helper inside this factory: the build serializes it into the
 * official ModuleLoader entry without a bundler or runtime dependencies.
 */
export function createDafeiyuMobileContext(env = globalThis) {
  const eventName = 'dafeiyu-dsh-client-change';
  const controlAttribute = 'data-dafeiyu-mobile-control';
  const layoutAttribute = 'data-dafeiyu-mobile-layout';
  const sectionAttribute = 'data-dafeiyu-mobile-section';
  const activityAncestorAttribute = 'data-dafeiyu-activity-ancestor';

  // Fixed portable schema from Material Lab 1.0.2. Only numeric ranges, hex
  // colors and closed enums enter this adapter; neither CSS nor a photo does.
  const themeRanges = Object.freeze({
    blurDp: [0, 48, 2], refractionScale: [0, 4, 1], refractionFalloff: [.05, 8, 2],
    dispersion: [0, .8, .1], bevelScale: [0, 4, 1], glassThicknessDp: [0, 36, 7],
    innerRefractionStrength: [0, 4, 0], innerRefractionWidthDp: [0, 24, 4], innerDispersion: [0, 1, .18],
    trackingLightWidthDp: [1, 80, 18], trackingLightIntensity: [0, 2, .65], pressDarken: [0, 1, .12],
    reflectionStrength: [0, 4, .8], lightAngleDegrees: [0, 360, 233.1301], highlightStrength: [0, 4, 1],
    rimStrength: [0, 1, .05], borderWidthDp: [0, 12, 0], borderOpacity: [0, 1, 0],
    saturation: [0, 3, 1.25], dimAmount: [0, 1, 0], surfaceAlpha: [0, 1, 1], tintAlpha: [0, 1, .12],
    pressScale: [.5, 1.5, 1.035], dragStretch: [0, .5, .045], dragCompression: [0, .5, .018],
    dragDistanceDp: [0, 48, 6], dragResistance: [0, 4, 1], pressMillis: [0, 2000, 150],
    releaseMillis: [0, 3000, 360], shadowBlurDp: [0, 48, 5.76], shadowSpreadDp: [0, 32, 0],
    shadowOffsetXDp: [-32, 32, 0], shadowOffsetYDp: [-32, 32, 1.2], shadowOpacity: [0, 1, .08],
    widthDp: [32, 360, 48], heightDp: [32, 320, 48], cornerDp: [0, 160, 24], roundness: [0, 1, 1],
    iconSizeDp: [8, 64, 23], iconAlpha: [0, 1, 1], textSizeSp: [8, 32, 15], textAlpha: [0, 1, 1],
    paddingDp: [0, 32, 12], iconTextGapDp: [0, 32, 8], textWeight: [100, 900, 500],
    textLetterSpacingSp: [-2, 6, 0], contentOffsetXDp: [-24, 24, 0], contentOffsetYDp: [-24, 24, 0],
    rotationDegrees: [-180, 180, 0], disabledAlpha: [0, 1, .4], contentOutlineWidthDp: [0, 3, 0],
    contentOutlineAlpha: [0, 1, 0], contentHighlightAlpha: [0, 1, 0], shadowPressScale: [0, 2, 1],
    springDamping: [.1, 1, .62], springStiffness: [50, 1200, 380], grainAlpha: [0, .3, 0],
    grainSizeDp: [.3, 3, .8], innerShadowAlpha: [0, 1, 0], innerShadowWidthDp: [0, 24, 0],
    sheenAlpha: [0, 1, 0], sheenWidth: [0, 1, .35], sheenAngleDegrees: [0, 360, 233.13],
    focusBorderWidthDp: [0, 8, 2], focusBorderAlpha: [0, 1, .45], hoverAlpha: [0, 1, .06],
    pressedTintAlpha: [0, 1, .08], fusionStrength: [0, 1, .55], fusionDistanceDp: [0, 64, 18],
    fusionMillis: [0, 1500, 150], bridgeReleaseMillis: [0, 1500, 240], bridgeReachDp: [0, 64, 24],
    buttonGapDp: [0, 80, 8], backgroundAlpha: [0, 1, 1], backgroundPatternScale: [.25, 4, 1],
    backgroundBlurDp: [0, 32, 0],
  });
  const themeTypes = ['accent', 'circle', 'regular', 'panel'];
  const themeColorKeys = ['surface', 'icon', 'text', 'highlight', 'border', 'shadow', 'background',
    'backgroundAccent', 'contentOutline', 'contentHighlight', 'innerShadow', 'sheen', 'focus', 'hover', 'pressed', 'trackingLight'];

  function normalizeMaterialTheme(raw) {
    try {
      const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
      const owns = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
      const exact = (value, keys) => object(value) && Object.keys(value).length === keys.length &&
        Object.keys(value).every(key => keys.includes(key));
      if (!exact(raw, ['schema', 'renderer', 'light', 'dark']) || raw.schema !== 1 ||
          raw.renderer !== 'dafeiyu-native-glass') return null;
      const result = { schema: 1, renderer: 'dafeiyu-native-glass' };
      for (const mode of ['light', 'dark']) {
        if (!exact(raw[mode], themeTypes)) return null;
        const groups = {};
        for (const type of themeTypes) {
          const p = raw[mode][type];
          if (!object(p) || !object(p.values) || !object(p.colors) ||
              Object.keys(p).some(key => !['values', 'colors', 'iconBlend', 'icon', 'glassEnabled',
                'motionEnabled', 'springEnabled', 'disabled', 'shape', 'pressEffect'].includes(key)) ||
              Object.keys(p.values).some(key => !owns(themeRanges, key)) ||
              Object.keys(p.colors).some(key => !themeColorKeys.includes(key))) return null;
          const values = {};
          for (const [key, [minimum, maximum, fallback]] of Object.entries(themeRanges)) {
            const value = owns(p.values, key) ? p.values[key] : fallback;
            // Android exports Float.toDouble(): e.g. .8f is .800000011920929.
            // Accept only the decimal bound or its actual Float32 endpoint.
            const low = Math.min(minimum, Math.fround(minimum));
            const high = Math.max(maximum, Math.fround(maximum));
            if (typeof value !== 'number' || !Number.isFinite(value) || value < low || value > high) return null;
            values[key] = value;
          }
          const colors = {};
          for (const key of themeColorKeys) {
            const value = p.colors[key] ?? (key === 'surface' ? (mode === 'dark' ? '#FF1C1C1E' : '#FFFFFFFF') :
              ['icon', 'text'].includes(key) ? (mode === 'dark' ? '#FFFFFFFF' : '#FF1C1C1E') : '#FFFFFFFF');
            if (typeof value !== 'string' || !/^#[0-9a-f]{6}(?:[0-9a-f]{2})?$/iu.test(value)) return null;
            colors[key] = value.toUpperCase();
          }
          const group = { values: Object.freeze(values), colors: Object.freeze(colors) };
          for (const key of ['glassEnabled', 'motionEnabled', 'springEnabled', 'disabled']) {
            const value = p[key] ?? key !== 'disabled';
            if (typeof value !== 'boolean') return null;
            group[key] = value;
          }
          for (const [key, choices, fallback] of [
            ['iconBlend', ['srcOver', 'multiply', 'screen', 'overlay', 'difference', 'plus', 'srcIn'], 'srcOver'],
            ['pressEffect', ['none', 'darken', 'glow', 'beam', 'lift'], 'glow'],
            ['shape', ['rounded', 'pill', 'circle', 'rectangle'], 'rounded'],
            ['icon', ['add', 'send', 'settings', 'folder', 'agent', 'archive', 'trash', 'shield'], 'add'],
          ]) {
            const value = p[key] ?? fallback;
            if (!choices.includes(value)) return null;
            group[key] = value;
          }
          groups[type] = Object.freeze(group);
        }
        result[mode] = Object.freeze(groups);
      }
      // All accepted data is ASCII and bounded by the fixed maps, not user CSS.
      if (JSON.stringify(result).length > 128 * 1024) return null;
      return Object.freeze(result);
    } catch { return null; }
  }

  function normalizeHostContext(raw) {
    try {
      if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) return null;
      // Read once. Getters and malformed bridge objects must not escape the gate.
      const { version, platform, surface, mode, theme, material, motion } = raw;
      if (version !== 1 || platform !== 'android' || surface !== 'mobile' ||
          !['local', 'online'].includes(mode) || !['system', 'light', 'dark'].includes(theme) ||
          typeof material !== 'boolean' || typeof motion !== 'boolean') return null;
      const result = { version, platform, surface, mode, theme, material, motion, preview: false };
      const materialTheme = normalizeMaterialTheme(raw.materialTheme);
      if (materialTheme) result.materialTheme = materialTheme;
      return Object.freeze(result);
    } catch {
      return null;
    }
  }

  function readContext() {
    let host = null;
    try { host = normalizeHostContext(env.__DAFEIYU_DSH_CLIENT__); } catch { /* Fail closed. */ }
    if (host) return host;
    let search;
    try {
      search = new URL(env.location.href).searchParams;
      if (search.get('dafeiyu-preview') !== 'mobile') return null;
    } catch {
      return null;
    }
    const requestedTheme = search.get('dafeiyu-theme');
    const theme = ['system', 'light', 'dark'].includes(requestedTheme) ? requestedTheme : 'system';
    const readBoolean = name => search.get(name) === 'false' ? false : true;
    return Object.freeze({
      version: 1, platform: 'android', surface: 'mobile', mode: 'online',
      theme, material: readBoolean('dafeiyu-material'), motion: readBoolean('dafeiyu-motion'), preview: true,
    });
  }

  function createAttributeOwner() {
    const elements = new Map();
    function set(element, name, value, replaceExisting = true) {
      if (!element) return;
      const current = element.getAttribute(name);
      let entries = elements.get(element);
      let record = entries?.get(name);
      if (record && current !== record.installed) return;
      if (!record) {
        if (!replaceExisting && current !== null) return;
        if (!entries) elements.set(element, entries = new Map());
        entries.set(name, record = { original: current, installed: current });
      }
      if (current !== value) {
        if (value === null) element.removeAttribute(name);
        else element.setAttribute(name, value);
      }
      record.installed = value;
    }
    function release(element, name) {
      const entries = elements.get(element);
      const record = entries?.get(name);
      if (!record) return;
      if (element.getAttribute(name) === record.installed) {
        if (record.original === null) element.removeAttribute(name);
        else element.setAttribute(name, record.original);
      }
      entries.delete(name);
      if (!entries.size) elements.delete(element);
    }
    function clear() {
      // Detached nodes are restored too: the host may mount them again later.
      for (const [element, entries] of [...elements]) {
        for (const name of [...entries.keys()]) release(element, name);
      }
    }
    function reconcile(name, desired) {
      for (const [element, entries] of [...elements]) {
        if (entries.has(name) && !desired.has(element)) release(element, name);
      }
      for (const [element, value] of desired) set(element, name, value, false);
    }
    return { set, release, clear, reconcile };
  }

  function createMaterialThemeController(document, root) {
    const attributes = createAttributeOwner();
    const styles = new Map();
    const nodes = new Map();
    const reduced = env.matchMedia?.('(prefers-reduced-motion: reduce)');
    const lessTransparent = env.matchMedia?.('(prefers-reduced-transparency: reduce)');
    const darkSystem = env.matchMedia?.('(prefers-color-scheme: dark)');
    let context = null;
    let groups = null;
    let pressed = null;
    let pointer = null;
    let origin = null;
    let disposed = false;
    const supports = (name, value) => { try { return env.CSS?.supports?.(name, value) === true; } catch { return false; } };
    const backdrop = supports('backdrop-filter', 'blur(1px)') || supports('-webkit-backdrop-filter', 'blur(1px)');
    const clamp = (value, low = 0, high = 1) => Math.max(low, Math.min(high, value));
    const number = value => String(Math.round(value * 100000) / 100000);
    const rgba = (color, multiplier = 1) => {
      const hex = color.slice(1);
      const offset = hex.length === 8 ? 2 : 0;
      const alpha = hex.length === 8 ? parseInt(hex.slice(0, 2), 16) / 255 : 1;
      const channels = [0, 2, 4].map(index => parseInt(hex.slice(offset + index, offset + index + 2), 16));
      return `rgba(${channels.join(', ')}, ${number(clamp(alpha * multiplier))})`;
    };

    function setStyle(element, name, value) {
      if (!element?.style?.setProperty) return;
      let entries = styles.get(element);
      let record = entries?.get(name);
      const current = element.style.getPropertyValue(name);
      const priority = element.style.getPropertyPriority(name);
      if (record && (current !== record.installed || priority !== record.installedPriority)) return;
      if (!record) {
        if (!entries) styles.set(element, entries = new Map());
        entries.set(name, record = { original: current, priority, installed: current, installedPriority: priority });
      }
      if (current !== value || priority) element.style.setProperty(name, value);
      record.installed = value; record.installedPriority = '';
    }

    function restoreStyles(element) {
      const entries = styles.get(element);
      if (!entries) return;
      for (const [name, record] of entries) {
        if (element.style.getPropertyValue(name) !== record.installed ||
            element.style.getPropertyPriority(name) !== record.installedPriority) continue;
        if (record.original) element.style.setProperty(name, record.original, record.priority);
        else element.style.removeProperty(name);
      }
      styles.delete(element);
    }

    function clearPress() {
      if (pressed) {
        attributes.release(pressed, 'data-dafeiyu-theme-pressed');
        for (const [name, value] of Object.entries({ '--df-theme-drag-x': '0px', '--df-theme-drag-y': '0px',
          '--df-theme-drag-scale-x': '1', '--df-theme-drag-scale-y': '1' })) setStyle(pressed, name, value);
      }
      pressed = null; pointer = null; origin = null;
    }

    function clear() {
      clearPress(); attributes.clear();
      for (const element of [...styles.keys()]) restoreStyles(element);
      resizeObserver?.disconnect(); nodes.clear(); groups = null;
    }

    function enabled(p) { return context?.motion && p.motionEnabled && !reduced?.matches; }
    function glass(p) { return context?.material && p.glassEnabled && !lessTransparent?.matches; }
    function radius(element, p) {
      const rect = element.getBoundingClientRect();
      const width = Number(element.offsetWidth) || rect.width;
      const height = Number(element.offsetHeight) || rect.height;
      if (width > 0 && height > 0) setStyle(element, '--df-theme-radius', `${number(Math.min(width, height) * p.values.roundness / 2)}px`);
    }
    const resizeObserver = typeof env.ResizeObserver === 'function' ? new env.ResizeObserver(entries => {
      if (disposed || !groups) return;
      for (const { target } of entries) { const type = nodes.get(target); if (type) radius(target, groups[type]); }
    }) : null;

    function properties(p) {
      const v = p.values; const c = p.colors;
      const material = glass(p); const motion = enabled(p); const spring = motion && p.springEnabled;
      const alpha = material ? v.tintAlpha * v.surfaceAlpha : 1;
      const fallbackAlpha = material && !backdrop ? Math.max(alpha, .36) : alpha;
      const reflection = material ? rgba(c.highlight, v.reflectionStrength * .12 * v.surfaceAlpha) : 'transparent';
      const backReflection = material ? rgba(c.border, v.reflectionStrength * .04 * v.surfaceAlpha) : 'transparent';
      const shadow = factor => `${number(v.shadowOffsetXDp)}px ${number(v.shadowOffsetYDp)}px ` +
        `${number(v.shadowBlurDp * factor)}px ${number(v.shadowSpreadDp * factor)}px ${rgba(c.shadow, v.shadowOpacity)}`;
      const inset = `inset 0 0 ${number(v.innerShadowWidthDp)}px ${rgba(c.innerShadow, v.innerShadowAlpha)}, ` +
        `inset 0 0 0 ${number(v.borderWidthDp)}px ${rgba(c.border, v.borderOpacity * v.surfaceAlpha)}, ` +
        `inset 0 1px 0 ${rgba(c.highlight, material ? v.rimStrength : .12)}`;
      const blend = { srcOver: 'normal', multiply: 'multiply', screen: 'screen', overlay: 'overlay',
        difference: 'difference', plus: 'plus-lighter', srcIn: 'normal' }[p.iconBlend];
      return {
        fill: material ? rgba(c.surface, fallbackAlpha) : rgba(`#FF${c.surface.slice(-6)}`),
        text: rgba(c.text, v.textAlpha), icon: rgba(c.icon, v.iconAlpha),
        'icon-size': `${number(v.iconSizeDp)}px`, 'icon-blend': supports('mix-blend-mode', blend) ? blend : 'normal',
        filter: material && backdrop ? `blur(${number(v.blurDp)}px) saturate(${number(v.saturation)})` : 'none',
        reflection: `linear-gradient(${number(v.lightAngleDegrees)}deg, ${reflection}, transparent 45%, ${backReflection}), ` +
          `linear-gradient(${number(v.sheenAngleDegrees)}deg, transparent, ${material ? rgba(c.sheen, v.sheenAlpha * v.surfaceAlpha) : 'transparent'} 50%, transparent), ` +
          `linear-gradient(${rgba('#FF000000', material ? v.dimAmount : 0)}, ${rgba('#FF000000', material ? v.dimAmount : 0)})`,
        shadow: `${shadow(1)}, ${inset}`,
        'pressed-shadow': `${shadow(v.shadowPressScale)}, ${inset}`, 'focus-width': `${number(v.focusBorderWidthDp)}px`,
        'focus-color': rgba(c.focus, v.focusBorderAlpha), 'hover-fill': rgba(c.hover, v.hoverAlpha),
        'tracking-color': rgba(c.trackingLight, material && motion ? v.trackingLightIntensity * v.highlightStrength * .35 : 0),
        'tracking-width': `${number(v.trackingLightWidthDp)}px`, darken: rgba('#FF000000', material && motion ? v.pressDarken : 0),
        'press-scale': number(spring ? v.pressScale : 1),
        'press-duration': `${number(motion ? v.pressMillis : 0)}ms`, 'release-duration': `${number(motion ? v.releaseMillis : 0)}ms`,
        'spring-ease': spring ? `cubic-bezier(.22, ${number(1 + (1 - v.springDamping) * .6)}, .32, 1)` : 'cubic-bezier(.16, 1, .3, 1)',
      };
    }

    function classify() {
      const desired = new Map();
      const mark = (selector, type) => {
        for (const node of document.querySelectorAll(selector)) {
          if (node.getAttribute(controlAttribute) === 'close' || node.getAttribute('role') === 'switch' ||
              node.closest('[data-dafeiyu-mobile-control="mobile-hidden"], [inert]')) continue;
          const existing = node.getAttribute('data-dafeiyu-theme-kind');
          if (existing !== null && !nodes.has(node)) continue;
          desired.set(node, type);
        }
      };
      mark('[data-dafeiyu-mobile-layout="sidebar"], [data-dafeiyu-mobile-layout="composer-card"], ' +
        '[data-dafeiyu-mobile-layout="quick-actions"], [data-dafeiyu-mobile-layout="activity-panel"], ' +
        '[data-dafeiyu-mobile-layout="presence-panel"], [data-dafeiyu-mobile-layout="startup-focus"], ' +
        '[data-shortcut-modal="settings"], [data-plugin-entry], [data-agent-preset-id]', 'panel');
      mark('[data-dafeiyu-mobile-control="model"], [data-dafeiyu-mobile-control="permission"], ' +
        '[data-dafeiyu-mobile-control="action"], [data-dafeiyu-mobile-control="session-action"], ' +
        '[data-dafeiyu-mobile-layout="quick-actions"] > button, [data-dafeiyu-mobile-layout="footer-actions"] > button, ' +
        '[data-slot="settings.section"] button, [data-slot="settings.action"] button', 'regular');
      mark('[data-dafeiyu-mobile-control="sidebar-toggle"], [data-dafeiyu-mobile-control="new-session"], ' +
        '[data-dafeiyu-mobile-control="activity-hub"]', 'circle');
      mark('[data-dafeiyu-mobile-control="send"], [data-slot="settings.action"] button[data-variant="primary"]', 'accent');
      for (const [node] of nodes) if (!desired.has(node)) {
        if (node === pressed) clearPress();
        resizeObserver?.unobserve?.(node); restoreStyles(node);
        for (const name of ['data-dafeiyu-theme-kind', 'data-dafeiyu-theme-glass', 'data-dafeiyu-theme-motion',
          'data-dafeiyu-theme-spring', 'data-dafeiyu-theme-effect']) attributes.release(node, name);
      }
      attributes.reconcile('data-dafeiyu-theme-kind', desired);
      for (const [node, type] of desired) {
        const p = groups[type];
        attributes.set(node, 'data-dafeiyu-theme-glass', String(glass(p)));
        attributes.set(node, 'data-dafeiyu-theme-motion', String(enabled(p)));
        attributes.set(node, 'data-dafeiyu-theme-spring', String(enabled(p) && p.springEnabled));
        attributes.set(node, 'data-dafeiyu-theme-effect', p.pressEffect);
        for (const key of Object.keys(properties(p))) setStyle(node, `--df-theme-${key}`, `var(--df-theme-${type}-${key})`);
        // Legacy finite press transitions read this alias without changing the button's hit box.
        setStyle(node, '--df-press-scale', `var(--df-theme-${type}-press-scale)`);
        radius(node, p);
        if (!nodes.has(node)) resizeObserver?.observe(node);
      }
      nodes.clear(); for (const entry of desired) nodes.set(...entry);
      if (pressed && (!nodes.has(pressed) || !enabled(groups[nodes.get(pressed)]))) clearPress();
    }

    function update() {
      if (disposed || !context?.materialTheme || root.getAttribute('data-dafeiyu-surface') !== 'mobile') { clear(); return; }
      const appearance = context.theme === 'dark' || context.theme === 'system' &&
        (document.body?.hasAttribute('data-ds-dark-theme') || darkSystem?.matches) ? 'dark' : 'light';
      groups = context.materialTheme[appearance];
      attributes.set(root, 'data-dafeiyu-material-theme', '1');
      attributes.set(root, 'data-dafeiyu-material-theme-appearance', appearance);
      // CSS gradients/blur are intentionally not advertised as native AGSL refraction or spectral optics.
      attributes.set(root, 'data-dafeiyu-material-capability', backdrop ? 'css-backdrop' : 'css-tint');
      for (const [type, p] of Object.entries(groups)) {
        for (const [key, value] of Object.entries(properties(p))) setStyle(root, `--df-theme-${type}-${key}`, value);
      }
      classify();
    }

    function down(event) {
      const previous = pointer;
      clearPress();
      if (previous !== null || event.isPrimary === false || !groups) return;
      const node = event.target?.closest?.('button, [role="button"]');
      const type = nodes.get(node);
      if (!type || !enabled(groups[type]) || node.disabled || node.getAttribute('aria-disabled') === 'true' ||
          node.closest('[hidden], [inert]')) return;
      pressed = node; pointer = event.pointerId; origin = { x: event.clientX, y: event.clientY };
      attributes.set(node, 'data-dafeiyu-theme-pressed', 'true', false);
      move(event);
    }

    function move(event) {
      if (!pressed || event.pointerId !== pointer || !groups) return;
      const p = groups[nodes.get(pressed)];
      if (!p) { clearPress(); return; }
      const rect = pressed.getBoundingClientRect();
      if (!(rect.width > 0 && rect.height > 0)) return;
      const x = Number.isFinite(event.clientX) ? event.clientX : rect.left + rect.width / 2;
      const y = Number.isFinite(event.clientY) ? event.clientY : rect.top + rect.height / 2;
      setStyle(pressed, '--df-theme-touch-x', `${number(clamp((x - rect.left) / rect.width) * 100)}%`);
      setStyle(pressed, '--df-theme-touch-y', `${number(clamp((y - rect.top) / rect.height) * 100)}%`);
      if (!p.springEnabled) return;
      const dx = x - (Number.isFinite(origin?.x) ? origin.x : x);
      const dy = y - (Number.isFinite(origin?.y) ? origin.y : y);
      const length = Math.hypot(dx, dy);
      const progress = length > 0 ? 1 - Math.exp(-length / (32 * (1 + p.values.dragResistance))) : 0;
      const distance = p.values.dragDistanceDp * progress;
      const horizontal = length > 0 ? Math.abs(dx / length) : 0;
      setStyle(pressed, '--df-theme-drag-x', `${number(length > 0 ? dx / length * distance : 0)}px`);
      setStyle(pressed, '--df-theme-drag-y', `${number(length > 0 ? dy / length * distance : 0)}px`);
      setStyle(pressed, '--df-theme-drag-scale-x', number(1 + progress * (horizontal * p.values.dragStretch - (1 - horizontal) * p.values.dragCompression)));
      setStyle(pressed, '--df-theme-drag-scale-y', number(1 + progress * ((1 - horizontal) * p.values.dragStretch - horizontal * p.values.dragCompression)));
    }
    function end(event) { if (pointer === event.pointerId) clearPress(); }
    function resetPointer() { clearPress(); }
    document.addEventListener('pointerdown', down, true);
    document.addEventListener('pointermove', move, { capture: true, passive: true });
    document.addEventListener('pointerup', end, true);
    document.addEventListener('pointercancel', end, true);
    document.addEventListener('lostpointercapture', end, true);
    env.addEventListener?.('blur', resetPointer);
    env.addEventListener?.('resize', update);
    for (const media of [reduced, lessTransparent, darkSystem]) media?.addEventListener?.('change', update);
    return {
      setContext(next) { clearPress(); context = next; update(); }, update,
      dispose() {
        if (disposed) return;
        disposed = true; context = null; clear();
        document.removeEventListener('pointerdown', down, true);
        document.removeEventListener('pointermove', move, true);
        document.removeEventListener('pointerup', end, true);
        document.removeEventListener('pointercancel', end, true);
        document.removeEventListener('lostpointercapture', end, true);
        env.removeEventListener?.('blur', resetPointer); env.removeEventListener?.('resize', update);
        for (const media of [reduced, lessTransparent, darkSystem]) media?.removeEventListener?.('change', update);
      },
    };
  }

  function installBridgeYield() {
    const name = '__DSH_MOBILE_SHELL__';
    let before;
    let installed;
    try {
      before = Object.getOwnPropertyDescriptor(env, name);
      // An existing mobile shell owns its signal. Never invoke a host setter.
      if ((before && !('value' in before)) || env[name] ||
          (before && before.writable === false)) return () => {};
      Object.defineProperty(env, name, before ? { ...before, value: true } : {
        value: true, writable: true, enumerable: true, configurable: true,
      });
      installed = Object.getOwnPropertyDescriptor(env, name);
    } catch {
      return () => {};
    }
    return () => {
      try {
        const current = Object.getOwnPropertyDescriptor(env, name);
        if (!current || !('value' in current) || current.value !== true ||
            current.writable !== installed.writable ||
            current.enumerable !== installed.enumerable ||
            current.configurable !== installed.configurable) return;
        if (before) Object.defineProperty(env, name, before);
        else delete env[name];
      } catch { /* A later host definition takes precedence. */ }
    };
  }

  // Adapt only reviewed 0.2.1-alpha.1 rows. The official React tree continues
  // to own every row, menu and confirmation; no business action is recreated.
  function createSidebarTouch(document, root, enabled) {
    const rowAttribute = 'data-dafeiyu-touch-row';
    const stateAttribute = 'data-dafeiyu-swipe-state';
    const offsetProperty = '--df-sidebar-swipe-x';
    const attributes = createAttributeOwner();
    const offsets = new Map();
    const records = new Map();
    const pointers = new Set();
    const distance = 78;
    let gesture = null;
    let opened = null;
    let openReady = false;
    let action = null;
    let actionTimer = null;
    let readyTimer = null;
    let holdTimer = null;
    let pending = null;
    let pendingTimer = null;
    let suppressed = null;
    let disposed = false;
    const reduced = env.matchMedia?.('(prefers-reduced-motion: reduce)');
    const clock = () => Date.now();
    const assign = (node, name, value) => { if (node.getAttribute(name) !== value) node.setAttribute(name, value); };
    const motion = () => root.getAttribute('data-dafeiyu-motion') !== 'false' && !reduced?.matches;
    const label = node => node?.getAttribute('aria-label') || '';
    const available = node => Boolean(node && node.isConnected !== false && !node.disabled &&
      node.getAttribute('aria-disabled') !== 'true' && !node.closest('[hidden], [inert]'));
    const clearTimer = name => {
      const timer = name === 'hold' ? holdTimer : name === 'ready' ? readyTimer : name === 'pending' ? pendingTimer : actionTimer;
      if (timer !== null) env.clearTimeout?.(timer);
      if (name === 'hold') holdTimer = null;
      else if (name === 'ready') readyTimer = null;
      else if (name === 'pending') pendingTimer = null;
      else actionTimer = null;
    };
    function setOffset(row, value) {
      if (!row.style?.setProperty) return;
      let owned = offsets.get(row);
      const current = row.style.getPropertyValue(offsetProperty);
      if (owned && current !== owned.installed) return;
      if (!owned) {
        owned = { original: current, priority: row.style.getPropertyPriority(offsetProperty), installed: current };
        offsets.set(row, owned);
      }
      const next = `${value}px`;
      if (current !== next) row.style.setProperty(offsetProperty, next);
      owned.installed = next;
    }
    function restoreOffset(row) {
      const owned = offsets.get(row);
      if (!owned) return;
      if (row.style?.getPropertyValue(offsetProperty) === owned.installed) {
        if (owned.original) row.style.setProperty(offsetProperty, owned.original, owned.priority);
        else row.style.removeProperty(offsetProperty);
      }
      offsets.delete(row);
    }
    function nativeRecord(row) {
      if (!row || row.tagName?.toLowerCase() !== 'div' || row.getAttribute('role') !== 'treeitem' ||
          !row.closest('[data-slot="sidebar.workspaces"]')?.closest('[data-slot="sidebar"]')) return null;
      const key = row.getAttribute('data-row-key') || '';
      const kind = key.startsWith('session:') && row.classList.contains('YDXeBa_sessionRow') ? 'session' :
        key.startsWith('workspace:') && row.classList.contains('YDXeBa_projectRow') ? 'workspace' : null;
      if (!kind || key === `${kind}:`) return null;
      const menuPattern = kind === 'session' ? /^(?:会话“.+”的操作|Session actions for .+)$/iu :
        /^(?:工作区“.+”的操作|Workspace actions for .+)$/iu;
      const menus = [...row.querySelectorAll('button.YDXeBa_iconButton')].filter(button => menuPattern.test(label(button)));
      if (menus.length !== 1) return null;
      const archives = kind === 'session' ? [...row.querySelectorAll('button.YDXeBa_iconButton')].filter(button =>
        /^(?:归档会话|Archive session)$/iu.test(label(button))) : [];
      return { row, key, kind, menu: menus[0], archive: archives.length === 1 ? archives[0] : null };
    }
    function current(record) {
      const next = nativeRecord(record?.row);
      return Boolean(next && next.key === record.key && next.kind === record.kind &&
        next.menu === record.menu && next.archive === record.archive && available(record.row));
    }
    function suppress(row) { suppressed = { row, until: clock() + 900 }; }
    function stopPending() { clearTimer('pending'); pending = null; }
    function hideAction(immediate = false) {
      clearTimer('action');
      if (!action) return;
      action.inert = true;
      action.disabled = true;
      action.setAttribute('aria-hidden', 'true');
      action.setAttribute(stateAttribute, 'closing');
      const finish = () => {
        actionTimer = null;
        if (action) { action.hidden = true; action.setAttribute(stateAttribute, 'closed'); }
      };
      if (immediate || !motion() || typeof env.setTimeout !== 'function') finish();
      else actionTimer = env.setTimeout(finish, 150);
    }
    function close(immediate = false) {
      clearTimer('ready');
      if (opened) {
        attributes.set(opened.row, stateAttribute, 'closed');
        setOffset(opened.row, 0);
      }
      opened = null;
      openReady = false;
      hideAction(immediate);
    }
    function cancelGesture(closeRow = true) {
      clearTimer('hold');
      const previous = gesture;
      gesture = null;
      if (previous) suppress(previous.record.row);
      if (previous?.record?.row.hasPointerCapture?.(previous.id)) {
        try { previous.record.row.releasePointerCapture(previous.id); } catch { /* Already released by the platform. */ }
      }
      if (previous && previous.record !== opened) {
        attributes.set(previous.record.row, stateAttribute, 'closed');
        setOffset(previous.record.row, 0);
      }
      if (closeRow) close();
    }
    function placeAction(record, offset, state) {
      const bounds = record.row.getBoundingClientRect();
      const viewport = env.innerHeight || root.clientHeight;
      if (!(bounds.width > distance && bounds.height > 0) || bounds.bottom <= 0 || bounds.top >= viewport) return false;
      let clipTop = 0;
      let clipBottom = viewport;
      let ancestor = record.row.parentElement;
      const sidebar = record.row.closest('[data-slot="sidebar"]')?.parentElement;
      while (ancestor && ancestor !== document.body) {
        const overflow = env.getComputedStyle?.(ancestor)?.overflowY;
        if (ancestor === sidebar || /^(?:hidden|clip|auto|scroll)$/u.test(overflow || '')) {
          const clip = ancestor.getBoundingClientRect();
          clipTop = Math.max(clipTop, clip.top);
          clipBottom = Math.min(clipBottom, clip.bottom);
        }
        ancestor = ancestor.parentElement;
      }
      if (bounds.top < clipTop || bounds.bottom > clipBottom) return false;
      if (!action) {
        action = document.createElement('button');
        action.type = 'button';
        action.setAttribute(layoutAttribute, 'sidebar-swipe-action');
        action.addEventListener('click', onAction);
        (document.body || root).appendChild(action);
      }
      clearTimer('action');
      if (action.hidden) action.hidden = false;
      const ready = state === 'open' && openReady;
      if (action.inert !== !ready) action.inert = !ready;
      if (action.disabled !== !ready) action.disabled = !ready;
      assign(action, 'aria-hidden', String(!ready));
      assign(action, stateAttribute, state);
      assign(action, controlAttribute, record.kind === 'session' ? 'sidebar-archive' : 'sidebar-delete');
      const chinese = (root.getAttribute('lang') || '').toLowerCase().startsWith('zh');
      const text = record.kind === 'session' ? (chinese ? '归档' : 'Archive') : (chinese ? '删除' : 'Delete');
      if (action.textContent !== text) action.textContent = text;
      assign(action, 'aria-label', record.kind === 'session' ? (chinese ? '归档会话' : 'Archive session') :
        (chinese ? '删除工作区' : 'Delete workspace'));
      // Native translation is independent from the list's own transform/FLIP.
      action.style.left = `${(record.anchor?.right ?? bounds.right - offset) - 72}px`;
      action.style.top = `${bounds.top}px`;
      action.style.width = '72px';
      action.style.height = `${Math.max(44, bounds.height)}px`;
      return true;
    }
    function open(record) {
      if (!current(record) || (record.kind === 'session' && !available(record.archive))) { close(true); return; }
      clearTimer('ready');
      opened = record;
      openReady = !motion();
      attributes.set(record.row, stateAttribute, 'open');
      setOffset(record.row, -distance);
      if (!placeAction(record, -distance, 'open')) { close(true); return; }
      if (!openReady && typeof env.setTimeout === 'function') readyTimer = env.setTimeout(() => {
        readyTimer = null;
        if (opened !== record || !current(record) || !enabled()) return;
        openReady = true;
        placeAction(record, -distance, 'open');
      }, 360);
      else openReady = true;
    }
    function tryDeleteMenu() {
      const operation = pending;
      if (!operation) return;
      if (!enabled() || !current(operation.record) || clock() > operation.expires) { stopPending(); return; }
      if (!operation.record.row.classList.contains('YDXeBa_menuOpen')) return;
      const candidates = [...document.querySelectorAll('[role="menu"]')].filter(menu => {
        if (operation.before.has(menu) || menu.closest('[hidden], [inert]')) return false;
        const items = [...menu.querySelectorAll('button[role="menuitem"]')];
        return items.length === 2 && items.some(item => /^(?:重命名|Rename)$/iu.test(item.textContent?.trim() || '')) &&
          items.some(item => /^(?:删除工作区|Delete workspace)$/iu.test(item.textContent?.trim() || ''));
      });
      if (candidates.length !== 1) return;
      const item = [...candidates[0].querySelectorAll('button[role="menuitem"]')].find(button =>
        /^(?:删除工作区|Delete workspace)$/iu.test(button.textContent?.trim() || ''));
      if (!available(item)) { stopPending(); return; }
      stopPending();
      // This opens the native confirmation only; never click its confirm button.
      item.click();
    }
    function invoke(record) {
      if (!enabled() || !current(record)) { close(true); return; }
      suppress(record.row);
      stopPending();
      close();
      if (record.kind === 'session') {
        if (available(record.archive)) record.archive.click();
        return;
      }
      if (!available(record.menu) || record.row.classList.contains('YDXeBa_menuOpen')) return;
      pending = { record, before: new Set(document.querySelectorAll('[role="menu"]')), expires: clock() + 800 };
      pendingTimer = env.setTimeout?.(stopPending, 800) ?? null;
      record.menu.click();
      // React's commit may arrive later; update() also checks after mutations.
      env.queueMicrotask?.(tryDeleteMenu);
    }
    function onAction(event) {
      event.preventDefault?.();
      event.stopPropagation?.();
      if (opened && openReady && !action?.disabled) invoke(opened);
    }
    function down(event) {
      if (disposed || !enabled()) return;
      if (event.pointerType !== 'touch' && event.pointerType !== 'pen') return;
      // A new intentional touch must never inherit a previous synthetic-click
      // guard when the platform omitted that previous compatibility click.
      suppressed = null;
      pointers.add(event.pointerId);
      stopPending();
      if (pointers.size > 1 || event.isPrimary === false) { cancelGesture(); return; }
      if (event.button !== undefined && event.button !== 0) return;
      const target = event.target;
      if (action?.contains(target)) return;
      const row = target?.closest?.('[data-row-key][role="treeitem"]');
      const record = records.get(row);
      if (!record || !current(record) || target.closest('button, a, input, textarea, select, [contenteditable], [role="button"]')) {
        cancelGesture();
        return;
      }
      if (opened?.row !== row) close();
      const bounds = row.getBoundingClientRect();
      record.anchor = { right: bounds.right + (opened?.row === row ? distance : 0) };
      gesture = { id: event.pointerId, record, x: event.clientX, y: event.clientY,
        dx: 0, mode: 'pending', wasOpen: opened?.row === row && openReady };
      const held = gesture;
      holdTimer = env.setTimeout?.(() => {
        holdTimer = null;
        if (gesture !== held || held.mode !== 'pending' || pointers.size !== 1 || !enabled() || !current(record)) return;
        held.mode = 'held';
        suppress(row);
        close();
        if (available(record.menu) && !row.classList.contains('YDXeBa_menuOpen')) record.menu.click();
      }, 480) ?? null;
    }
    function move(event) {
      const currentGesture = gesture;
      if (!currentGesture || event.pointerId !== currentGesture.id) return;
      if (!enabled() || !current(currentGesture.record)) { cancelGesture(); return; }
      const dx = event.clientX - currentGesture.x;
      const dy = event.clientY - currentGesture.y;
      if (currentGesture.mode === 'held') { event.preventDefault?.(); return; }
      if (currentGesture.mode === 'pending') {
        if (Math.hypot(dx, dy) <= 10) return;
        clearTimer('hold');
        if (Math.abs(dy) >= Math.abs(dx) * 0.8 || (dx > 0 && !opened)) {
          cancelGesture();
          return;
        }
        if (currentGesture.record.kind === 'session' && !available(currentGesture.record.archive)) {
          cancelGesture();
          return;
        }
        currentGesture.mode = 'dragging';
        try { currentGesture.record.row.setPointerCapture?.(event.pointerId); } catch { /* Document listeners still track it. */ }
      }
      currentGesture.dx = dx;
      event.preventDefault?.();
      const offset = Math.min(0, Math.max(-distance * 1.8, (currentGesture.wasOpen ? -distance : 0) + dx));
      attributes.set(currentGesture.record.row, stateAttribute, 'dragging');
      setOffset(currentGesture.record.row, offset);
      if (!placeAction(currentGesture.record, offset, 'dragging')) cancelGesture();
    }
    function up(event) {
      pointers.delete(event.pointerId);
      const ended = gesture;
      if (!ended || event.pointerId !== ended.id) return;
      clearTimer('hold');
      gesture = null;
      if (ended.mode === 'held') { event.preventDefault?.(); suppress(ended.record.row); return; }
      if (ended.mode !== 'dragging') return;
      event.preventDefault?.();
      suppress(ended.record.row);
      if (!enabled() || !current(ended.record)) { close(true); return; }
      if (ended.wasOpen && ended.dx <= -48) invoke(ended.record);
      else if (ended.dx <= -36 || (ended.wasOpen && ended.dx < 28)) open(ended.record);
      else close();
    }
    function cancel(event) {
      // Normal pointerup releases capture after invoke() has queued React's
      // native menu. A touch target's implicit capture also transfers to its
      // row on drag; losing that descendant capture must not cancel the row.
      if (event.type === 'lostpointercapture' && (!gesture || event.pointerId !== gesture.id ||
          event.target !== gesture.record.row)) return;
      pointers.delete(event.pointerId);
      if (gesture && event.pointerId === gesture.id) cancelGesture();
      else if (event.type !== 'lostpointercapture') close();
      stopPending();
    }
    function click(event) {
      if (suppressed && clock() <= suppressed.until && suppressed.row.contains(event.target) &&
          (event.isTrusted !== false || event.detail > 0)) {
        event.preventDefault?.();
        event.stopImmediatePropagation?.();
        suppressed = null;
      }
    }
    function contextMenu(event) {
      if (gesture && gesture.record.row.contains(event.target)) event.preventDefault?.();
    }
    function dragStart(event) {
      if (gesture?.record.row.contains(event.target)) event.preventDefault?.();
    }
    function scroll() { if (gesture || opened) cancelGesture(); stopPending(); }
    function blur() { pointers.clear(); cancelGesture(); stopPending(); }
    function update() {
      if (disposed) return;
      const desired = new Map();
      for (const row of document.querySelectorAll('[data-slot="sidebar.workspaces"] [data-row-key][role="treeitem"]')) {
        const record = nativeRecord(row);
        if (!record) continue;
        desired.set(row, record.kind);
        records.set(row, record);
        if (opened?.row !== row && gesture?.record.row !== row) attributes.set(row, stateAttribute, 'closed', false);
      }
      // Reset active state before releasing owned marks; a detached row must
      // not acquire a fresh closed mark or offset after its cleanup below.
      if (gesture && (!enabled() || !current(gesture.record))) cancelGesture();
      if (opened && (!enabled() || !current(opened))) close(true);
      attributes.reconcile(rowAttribute, desired);
      for (const [row] of records) if (!desired.has(row)) {
        records.delete(row);
        attributes.release(row, stateAttribute);
        restoreOffset(row);
      }
      if (opened && !gesture) {
        if (!motion() && !openReady) { clearTimer('ready'); openReady = true; }
        if (!placeAction(opened, -distance, 'open')) close(true);
      }
      tryDeleteMenu();
    }
    document.addEventListener('pointerdown', down, true);
    document.addEventListener('pointermove', move, { capture: true, passive: false });
    document.addEventListener('pointerup', up, true);
    document.addEventListener('pointercancel', cancel, true);
    document.addEventListener('lostpointercapture', cancel, true);
    document.addEventListener('click', click, true);
    document.addEventListener('contextmenu', contextMenu, true);
    document.addEventListener('dragstart', dragStart, true);
    document.addEventListener('scroll', scroll, true);
    env.addEventListener?.('resize', scroll);
    env.addEventListener?.('blur', blur);
    return {
      update,
      close: () => { cancelGesture(); stopPending(); },
      dispose() {
        disposed = true;
        blur();
        hideAction(true);
        for (const name of ['hold', 'ready', 'pending', 'action']) clearTimer(name);
        document.removeEventListener('pointerdown', down, true);
        document.removeEventListener('pointermove', move, true);
        document.removeEventListener('pointerup', up, true);
        document.removeEventListener('pointercancel', cancel, true);
        document.removeEventListener('lostpointercapture', cancel, true);
        document.removeEventListener('click', click, true);
        document.removeEventListener('contextmenu', contextMenu, true);
        document.removeEventListener('dragstart', dragStart, true);
        document.removeEventListener('scroll', scroll, true);
        env.removeEventListener?.('resize', scroll);
        env.removeEventListener?.('blur', blur);
        action?.removeEventListener('click', onAction);
        action?.remove(); action = null;
        attributes.clear();
        for (const [row] of offsets) restoreOffset(row);
        records.clear(); pointers.clear(); suppressed = null;
      },
    };
  }

  function install(cssText, layout, options = {}) {
    const document = env.document;
    if (!document?.documentElement) return () => {};
    const root = document.documentElement;
    const rootAttributes = createAttributeOwner();
    const markers = createAttributeOwner();
    let active = false;
    let disposed = false;
    let scheduled = false;
    let style = null;
    let observer = null;
    let restoreYield = null;
    let navigation = null;
    let backdrop = null;
    let navFrame = null;
    let sidebarButton = null;
    let newSessionButton = null;
    let navTitle = null;
    let drawerOpen = false;
    let awaitingExpansion = false;
    let expansionTimer = null;
    let footer = null;
    let sidebarTouch = null;
    let materialTheme = null;
    let activityButton = null;
    let activityPanel = null;
    let activityAgents = null;
    let activityJobs = null;
    let activityStatus = null;
    let activityClose = null;
    let activityOpen = false;
    let activityCategory = 'agents';
    let activitySource = null;
    let activityMenu = null;
    let activitySession = null;
    let activityPending = false;
    let activityTimer = null;
    let activityVisibilityTimer = null;
    let activityClosing = false;
    let quickPanel = null;
    let quickNewSession = null;
    let quickScan = null;
    let quickPair = null;
    let quickStatus = null;
    let quickOpen = false;
    let quickSource = null;
    let quickHost = null;
    let quickPending = false;
    let quickTimer = null;
    let quickGeneration = 0;
    let pressedButton = null;
    let pressedPointer = null;
    const pressedAttributes = createAttributeOwner();
    const iconSnapshots = new Map();
    const reducedMotion = env.matchMedia?.('(prefers-reduced-motion: reduce)');
    const viewportProperty = '--df-viewport-height';
    let viewportStyle = null;

    // Android WebView may resolve dynamic viewport units to zero while its
    // real window is positive. Own only this mobile variable, follow the
    // resized WebView (including IME), and preserve a later host's writer.
    function updateViewport() {
      if (!active || disposed || !root.style?.setProperty) return;
      const height = Number(env.innerHeight);
      if (!Number.isFinite(height) || height <= 0) return;
      const current = root.style.getPropertyValue(viewportProperty);
      const priority = root.style.getPropertyPriority(viewportProperty);
      if (viewportStyle && (current !== viewportStyle.installed || priority !== viewportStyle.installedPriority)) return;
      if (!viewportStyle) viewportStyle = { original: current, priority, installed: current, installedPriority: priority };
      const value = `${height}px`;
      if (current !== value || priority) root.style.setProperty(viewportProperty, value);
      viewportStyle.installed = value;
      viewportStyle.installedPriority = '';
    }

    function restoreViewport() {
      if (viewportStyle && root.style?.getPropertyValue(viewportProperty) === viewportStyle.installed &&
          root.style.getPropertyPriority(viewportProperty) === viewportStyle.installedPriority) {
        if (viewportStyle.original) root.style.setProperty(viewportProperty, viewportStyle.original, viewportStyle.priority);
        else root.style.removeProperty(viewportProperty);
      }
      viewportStyle = null;
    }

    const query = selector => document.querySelector(selector);
    const all = selector => document.querySelectorAll(selector);
    const labelOf = element => element.getAttribute('aria-label') || element.getAttribute('title') || '';
    const setLocalAttribute = (element, name, value) => {
      if (element.getAttribute(name) !== value) element.setAttribute(name, value);
    };
    const setLocalDisabled = (element, value) => {
      if (element.disabled !== value) element.disabled = value;
    };
    const isChinese = () => (root.getAttribute('lang') || '').toLowerCase().startsWith('zh');
    const isClose = label => /^(?:close|back|dismiss|cancel)(?:$|\s)/iu.test(label.trim()) ||
      /^(?:关闭|返回|取消)(?:$|[ ·：:]|设置|窗口|面板|对话框|菜单|弹窗|侧栏)/u.test(label.trim());
    const isMicrophone = label => /^(?:开始录音|语音输入|麦克风|Start recording|Voice input|Dictate|Microphone)$/iu.test(label.trim());
    const isRightbarToggle = label => /^(?:打开右侧边栏|收起右侧边栏|打开右侧栏|收起右侧栏|Open right sidebar|Collapse right sidebar)$/iu.test(label.trim());

    function findLayout() {
      const rootSlot = query('[data-slot="root"]');
      return {
        frame: rootSlot?.firstElementChild || null,
        sidebar: query('[data-slot="sidebar"]')?.parentElement || null,
        center: query('[data-slot="main"]')?.parentElement || null,
        rightbar: query('[data-slot="rightbar"]')?.parentElement || null,
      };
    }

    function findSidebarSource() {
      const badgeButton = query('[data-slot="sidebar.toggle.badge"]')?.closest('button');
      if (badgeButton) return badgeButton;
      // The official badge seat exists only in the collapsed rail. The same
      // business toggle remains in the expanded sidebar with a localized label.
      const scopes = [query('[data-slot="sidebar"]'), query('[data-shell-leading]')];
      for (const scope of scopes) {
        const source = [...(scope?.querySelectorAll('button') || [])].find(button =>
          /^(?:打开侧边栏|收起侧边栏|打开侧栏|收起侧栏|Open sidebar|Collapse sidebar)$/iu.test(labelOf(button).trim()));
        if (source) return source;
      }
      return null;
    }

    function findNewSessionSource() {
      const sidebar = query('[data-slot="sidebar"]');
      if (!sidebar) return null;
      const candidates = [...sidebar.querySelectorAll('button')].filter(button =>
        /^(?:新建会话|New Session)$/iu.test(labelOf(button).trim()));
      const actions = candidates.filter(button => !button.querySelector(
        '[data-slot="sidebar.brand.mark"], [data-slot="sidebar.brand.name"]'));
      return actions.find(button => button.querySelector('svg')) || actions[0] || null;
    }

    function clearExpansionWait() {
      awaitingExpansion = false;
      if (expansionTimer !== null) env.clearTimeout?.(expansionTimer);
      expansionTimer = null;
    }

    function setDrawer(open) {
      if (!open) sidebarTouch?.close();
      if (open) {
        closeActivity(false);
        closeQuick(false);
      }
      drawerOpen = open;
      if (!open) clearExpansionWait();
      rootAttributes.set(root, 'data-dafeiyu-drawer', open ? 'open' : 'closed');
      sidebarButton?.setAttribute('aria-expanded', String(open));
      backdrop?.setAttribute('aria-hidden', String(!open));
      if (backdrop) backdrop.tabIndex = open ? 0 : -1;
    }

    function toggleDrawer() {
      if (!active) return;
      if (drawerOpen) { setDrawer(false); return; }
      const { frame } = findLayout();
      const source = findSidebarSource();
      const hasLayoutAction = typeof layout?.toggleSidebar === 'function';
      if (!frame || (!hasLayoutAction && !source) ||
          (source && (source.disabled || source.getAttribute('aria-disabled') === 'true'))) return;
      if (frame.getAttribute('data-sidebar-collapsed') === 'true') {
        awaitingExpansion = true;
        if (typeof env.setTimeout === 'function') expansionTimer = env.setTimeout(() => {
          expansionTimer = null;
          if (active && awaitingExpansion) setDrawer(false);
        }, 1200);
        try {
          if (hasLayoutAction) layout.toggleSidebar();
          else source.click();
        } catch {
          clearExpansionWait();
          return;
        }
      }
      setDrawer(true);
    }

    function startNewSession() {
      const source = findNewSessionSource();
      if (!active || !source || source.disabled || source.getAttribute('aria-disabled') === 'true') return;
      closeActivity(false);
      closeQuick(false);
      source.click();
      setDrawer(false);
    }

    function readQuickHost() {
      try {
        // A URL preview or Android user agent grants no native authority. The
        // application must provide the same explicit, validated mobile signal.
        if (!normalizeHostContext(env.__DAFEIYU_DSH_CLIENT__)) return null;
        const bridge = env.__DAFEIYU_DSH_HOST__;
        if (!bridge || bridge.version !== 1 || bridge.platform !== 'android' ||
            !Array.isArray(bridge.capabilities) ||
            !bridge.capabilities.every(value => typeof value === 'string')) return null;
        const capabilities = bridge.capabilities.slice();
        const scanner = capabilities.includes('app.scan.open') ? bridge.openScanner : null;
        const pairing = capabilities.includes('pairing.open') ? bridge.openPairing : null;
        return {
          bridge,
          scanner: typeof scanner === 'function' ? scanner : null,
          pairing: typeof pairing === 'function' ? pairing : null,
        };
      } catch { return null; }
    }

    function sameQuickHost(left, right) {
      return left?.bridge === right?.bridge && left?.scanner === right?.scanner && left?.pairing === right?.pairing;
    }

    function finishQuickClose() {
      quickTimer = null;
      if (quickOpen || !quickPanel) return;
      if (!quickPanel.hidden) quickPanel.hidden = true;
      setLocalAttribute(quickPanel, 'data-dafeiyu-quick-state', 'closed');
    }

    function closeQuick(restoreFocus = false, immediate = false) {
      const changed = quickOpen || quickPending;
      if (!changed && !immediate) return;
      if (quickTimer !== null) env.clearTimeout?.(quickTimer);
      quickTimer = null;
      ++quickGeneration;
      quickOpen = quickPending = false;
      quickSource = null;
      quickHost = null;
      if (quickPanel) {
        // Its exit remains visible for CSS, but it is no longer interactive or
        // exposed to accessibility APIs from the first instant of dismissal.
        setLocalAttribute(quickPanel, 'aria-hidden', 'true');
        setLocalAttribute(quickPanel, 'inert', '');
        setLocalAttribute(quickPanel, 'aria-busy', 'false');
        if (pressedButton && quickPanel.contains(pressedButton)) clearPressed();
        if (!immediate && !quickPanel.hidden && root.getAttribute('data-dafeiyu-motion') !== 'false' &&
            !env.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches && typeof env.setTimeout === 'function') {
          setLocalAttribute(quickPanel, 'data-dafeiyu-quick-state', 'closing');
          quickTimer = env.setTimeout(finishQuickClose, 180);
        } else finishQuickClose();
      }
      if (newSessionButton) setLocalAttribute(newSessionButton, 'aria-expanded', 'false');
      if (restoreFocus) env.queueMicrotask?.(() => { if (active) newSessionButton?.focus(); });
      if (changed) scheduleDomUpdate();
    }

    function toggleQuick() {
      if (!active || newSessionButton?.disabled || !quickPanel) return;
      if (quickOpen) { closeQuick(true); return; }
      closeActivity(false);
      setDrawer(false);
      if (quickTimer !== null) env.clearTimeout?.(quickTimer);
      quickTimer = null;
      const generation = ++quickGeneration;
      quickOpen = true;
      quickPending = false;
      quickSource = findNewSessionSource();
      quickHost = readQuickHost();
      if (quickPanel.hidden) quickPanel.hidden = false;
      quickPanel.removeAttribute('inert');
      setLocalAttribute(quickPanel, 'aria-hidden', 'false');
      setLocalAttribute(quickPanel, 'data-dafeiyu-quick-state', 'open');
      setLocalAttribute(newSessionButton, 'aria-expanded', 'true');
      if (!quickStatus.hidden) quickStatus.hidden = true;
      if (quickStatus.textContent) quickStatus.textContent = '';
      updateQuick();
      env.queueMicrotask?.(() => {
        if (!active || !quickOpen || generation !== quickGeneration) return;
        [quickNewSession, quickScan, quickPair].find(button => !button.disabled)?.focus();
      });
    }

    function selectQuickSession() {
      if (!active || !quickOpen || quickPending || quickNewSession?.disabled) return;
      if (quickSource !== findNewSessionSource()) { closeQuick(false); return; }
      startNewSession();
    }

    function selectQuickHost(kind) {
      if (!active || !quickOpen || quickPending) return;
      const current = readQuickHost();
      const method = kind === 'scan' ? current?.scanner : current?.pairing;
      if (!sameQuickHost(current, quickHost) || !method) { closeQuick(false); return; }
      const generation = ++quickGeneration;
      quickPending = true;
      if (quickStatus.hidden) quickStatus.hidden = false;
      const opening = kind === 'scan' ? (isChinese() ? '正在打开扫码…' : 'Opening scanner…') :
        (isChinese() ? '正在打开配对…' : 'Opening pairing…');
      if (quickStatus.textContent !== opening) quickStatus.textContent = opening;
      updateQuick();
      const failure = () => {
        if (!active || !quickOpen || generation !== quickGeneration ||
            !sameQuickHost(current, readQuickHost())) return;
        quickPending = false;
        const message = kind === 'scan' ? (isChinese() ? '扫码未能打开，请重试。' : 'The scanner could not open. Try again.') :
          (isChinese() ? '配对页面未能打开，请重试。' : 'Pairing could not open. Try again.');
        if (quickStatus.textContent !== message) quickStatus.textContent = message;
        updateQuick();
      };
      try {
        // These no-argument business methods are the complete authority exposed
        // here. No URL, command, device id, camera stream or unpair operation is
        // passed from webpage content. The native page owns permissions/errors.
        Promise.resolve(method.call(current.bridge)).then(result => {
          if (!active || !quickOpen || generation !== quickGeneration ||
              !sameQuickHost(current, readQuickHost())) return;
          if (result === false || result?.ok === false) { failure(); return; }
          // Acceptance closes the menu; it never claims a scan or pairing has
          // succeeded. Those outcomes belong to the real native workflow.
          closeQuick(false);
        }, failure);
      } catch { failure(); }
    }

    function selectQuickScan() { selectQuickHost('scan'); }
    function selectQuickPair() { selectQuickHost('pair'); }

    function updateQuick() {
      if (!navigation || !newSessionButton) return;
      if (!quickPanel) {
        quickPanel = document.createElement('div');
        quickPanel.id = 'dafeiyu-mobile-quick-actions';
        quickPanel.setAttribute(layoutAttribute, 'quick-actions');
        quickPanel.setAttribute('role', 'menu');
        quickPanel.hidden = true;
        quickPanel.setAttribute('aria-hidden', 'true');
        quickPanel.setAttribute('inert', '');
        quickPanel.setAttribute('data-dafeiyu-quick-state', 'closed');
        for (const [kind, listener] of [['new-session', selectQuickSession], ['scan', selectQuickScan], ['pair', selectQuickPair]]) {
          const button = document.createElement('button');
          button.type = 'button';
          button.setAttribute(controlAttribute, `quick-${kind}`);
          button.setAttribute('role', 'menuitem');
          button.appendChild(document.createElement('span'));
          button.addEventListener('click', listener);
          quickPanel.appendChild(button);
          if (kind === 'new-session') quickNewSession = button;
          else if (kind === 'scan') quickScan = button;
          else quickPair = button;
        }
        quickStatus = document.createElement('p');
        quickStatus.setAttribute(layoutAttribute, 'quick-status');
        quickStatus.setAttribute('role', 'status');
        quickStatus.hidden = true;
        quickPanel.appendChild(quickStatus);
        (document.body || root).appendChild(quickPanel);
        newSessionButton.setAttribute('aria-controls', quickPanel.id);
      }
      const source = findNewSessionSource();
      const host = readQuickHost();
      if (quickOpen && (quickSource !== source || !sameQuickHost(quickHost, host))) closeQuick(false);
      const chinese = isChinese();
      setLocalAttribute(quickPanel, 'aria-label', chinese ? '快捷操作' : 'Quick actions');
      setLocalAttribute(quickPanel, 'aria-busy', String(quickPending));
      const labels = [
        [quickNewSession, sourceAvailable(source), chinese ? '新建会话' : 'New session'],
        [quickScan, Boolean(host?.scanner), chinese ? '扫码' : 'Scan'],
        [quickPair, Boolean(host?.pairing), chinese ? '配对码' : 'Pairing code'],
      ];
      for (const [button, available, label] of labels) {
        const text = available || button === quickNewSession ? label :
          `${label}${chinese ? '（需要手机）' : ' (requires phone)'}`;
        const span = button.firstElementChild;
        if (span.textContent !== text) span.textContent = text;
        setLocalDisabled(button, quickPending || !available);
      }
      setLocalDisabled(newSessionButton, !sourceAvailable(source) && !host?.scanner && !host?.pairing);
    }

    function removeQuick() {
      closeQuick(false, true);
      quickNewSession?.removeEventListener('click', selectQuickSession);
      quickScan?.removeEventListener('click', selectQuickScan);
      quickPair?.removeEventListener('click', selectQuickPair);
      quickPanel?.remove();
      quickPanel = quickNewSession = quickScan = quickPair = quickStatus = null;
    }

    function cloneOfficialIcon(button, source) {
      // The collapsed toggle also contains a nested FishLogo in its brand
      // mark. Its direct SVG child is the actual shipped PanelLeft glyph.
      const svg = [...(source?.children || [])].find(child => child.tagName?.toLowerCase() === 'svg') ||
        source?.querySelector('svg');
      // Preserve a previously cloned icon across a brief official re-render.
      if (!svg) return;
      const snapshot = svg?.outerHTML || '';
      if (iconSnapshots.get(button) === snapshot) return;
      iconSnapshots.set(button, snapshot);
      button.replaceChildren();
      const icon = svg.cloneNode(true);
      // Shipped sidebar icons have no paint-server ids; remove accessibility ids
      // from a clone so it cannot compete with the original button's identity.
      for (const node of [icon, ...icon.querySelectorAll('[id], [aria-labelledby], [aria-describedby]')]) {
        node.removeAttribute('id');
        node.removeAttribute('aria-labelledby');
        node.removeAttribute('aria-describedby');
      }
      icon.setAttribute('aria-hidden', 'true');
      icon.setAttribute('focusable', 'false');
      button.appendChild(icon);
    }

    function installActivityFallbackIcon() {
      if (!activityButton || activityButton.querySelector('svg') || typeof document.createElementNS !== 'function') return;
      // IconUsersOutlineRegular, @deepseek-ai/dsh-client-ui-primitives
      // 0.2.1-alpha.1, lib/index.js / packages/client/ui-primitives; MIT.
      // deepseek-ai/deepseek-harness@5badb15009ae1756c3afe0ae0cef1faafc290ccc.
      /*
       * MIT License
       * Copyright (c) 2026 DeepSeek
       *
       * Permission is hereby granted, free of charge, to any person obtaining a
       * copy of this software and associated documentation files (the "Software"),
       * to deal in the Software without restriction, including without limitation
       * the rights to use, copy, modify, merge, publish, distribute, sublicense,
       * and/or sell copies of the Software, and to permit persons to whom the
       * Software is furnished to do so, subject to the following conditions:
       *
       * The above copyright notice and this permission notice shall be included
       * in all copies or substantial portions of the Software.
       *
       * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
       * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
       * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
       * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
       * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
       * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
       * DEALINGS IN THE SOFTWARE.
       */
      const namespace = 'http://www.w3.org/2000/svg';
      const svg = document.createElementNS(namespace, 'svg');
      for (const [name, value] of Object.entries({
        width: '14', height: '14', viewBox: '0 0 16 16', fill: 'none',
        xmlns: namespace, 'aria-hidden': 'true', focusable: 'false', 'stroke-width': '1',
      })) svg.setAttribute(name, value);
      for (const path of [
        'M6 8.25C7.51878 8.25 8.75 7.01878 8.75 5.5C8.75 3.98122 7.51878 2.75 6 2.75C4.48122 2.75 3.25 3.98122 3.25 5.5C3.25 7.01878 4.48122 8.25 6 8.25Z',
        'M1 14.5C1 11.5 3.5 10.25 6 10.25C8.5 10.25 11 11.5 11 14.5',
        'M10.5 2.9C11.65 3.35 12.45 4.35 12.45 5.5C12.45 6.65 11.65 7.65 10.5 8.1',
        'M12.4 10.6C13.9 11.3 15 12.6 15 14.5',
      ]) {
        const node = document.createElementNS(namespace, 'path');
        node.setAttribute('d', path);
        node.setAttribute('stroke', 'currentColor');
        svg.appendChild(node);
      }
      activityButton.replaceChildren(svg);
    }

    function currentTitle() {
      const lineages = [...all('[data-slot="conversation.session.header.lineage"]')];
      const current = lineages.at(-1);
      // The default renderer puts its title before an empty lineage seat. A
      // plugin may instead render the title in that seat. Never read chat h1/h2.
      const title = current?.textContent?.trim() || current?.previousElementSibling?.textContent?.trim();
      if (title) return title;
      const selected = query('[data-slot="sidebar"] [data-row-key^="session:"][aria-selected="true"]');
      const leading = selected?.querySelector('[data-slot="sidebar.session.row.leading"]');
      // During a running task the leading seat becomes status dots. The native
      // row retains the title as its second direct child in either state.
      const titleNode = leading?.parentElement?.nextElementSibling || selected?.children[1];
      if (titleNode && selected.contains(titleNode)) {
        const selectedTitle = titleNode.textContent?.trim();
        if (selectedTitle) return selectedTitle;
      }
      const pageTitle = document.title?.replace(/\s*[·|—–-]\s*DeepSeek Harness\s*$/u, '').trim();
      return pageTitle || 'DSH';
    }

    // These class tokens identify the reviewed, pinned official 0.2.1-alpha.1
    // contributions. A changed upstream must fail closed rather than borrowing
    // an unrelated extension's button merely because its label looks similar.
    function findActivitySources() {
      const actions = query('[data-slot="conversation.session.header.actions"]');
      const lineage = [...all('[data-slot="conversation.session.header.lineage"]')].at(-1);
      const agents = actions?.querySelector('button.ZKlsPq_trigger[aria-haspopup="tree"]') ||
        lineage?.querySelector('button.ZKlsPq_trigger[aria-haspopup="tree"]') ||
        [...(lineage?.querySelectorAll('button.ZKlsPq_switcherTrigger[aria-haspopup="tree"]') || [])].at(-1) || null;
      const jobs = actions?.querySelector('.QsffPG_root > button.QsffPG_trigger') || null;
      const teamRoot = actions?.querySelector('[data-team-action]');
      const team = teamRoot?.tagName?.toLowerCase() === 'button' &&
        teamRoot.getAttribute('aria-haspopup') === 'dialog' ? teamRoot :
        teamRoot?.querySelector('button.VoX2oq_trigger[aria-haspopup="dialog"]') || null;
      return { agents, jobs, team };
    }

    function activityCount(category, source) {
      // These labels are the pinned official record projections, not generic
      // menu availability. Catalog totals include completed descendants; jobs
      // totals include both live and settled rows. Unknown labels fail closed.
      const text = labelOf(source || { getAttribute: () => null }).trim();
      const patterns = category === 'agents' ? [
        /^(\d+) 个子智能体(?:，正在运行)?$/u,
        /^(\d+) subagents?(?: running)?$/iu,
      ] : [
        /^(\d+) 个后台任务(?:运行中)?$/u,
        /^(\d+) background jobs?(?: running)?$/iu,
      ];
      for (const pattern of patterns) {
        const match = pattern.exec(text);
        if (!match) continue;
        const value = Number(match[1]);
        return Number.isSafeInteger(value) && value > 0 ? value : 0;
      }
      return 0;
    }

    function hasActivity(category, source) { return activityCount(category, source) > 0 && sourceAvailable(source); }

    function findActivityMenu(category, source) {
      if (!source) return null;
      if (category === 'jobs') {
        const menu = source.parentElement?.querySelector('ul.QsffPG_menu');
        return menu && /^(?:后台任务|Background jobs)$/iu.test(labelOf(menu).trim()) ? menu : null;
      }
      return [...all('div.ZKlsPq_menu')].find(menu => {
        const tree = menu.querySelector('[role="tree"]');
        return tree && /^(?:子智能体会话|Subagent sessions)$/iu.test(labelOf(tree).trim());
      }) || null;
    }

    function activitySessionKey() {
      const selected = query('[data-slot="sidebar"] [data-row-key^="session:"][aria-selected="true"]');
      return `${selected?.getAttribute('data-row-key') || ''}\n${currentTitle()}`;
    }

    function sourceAvailable(source) {
      return Boolean(source && !source.disabled && source.getAttribute('aria-disabled') !== 'true');
    }

    function clearActivityWait() {
      activityPending = false;
      if (activityTimer !== null) env.clearTimeout?.(activityTimer);
      activityTimer = null;
    }

    function closeNativeActivity() {
      const source = activitySource;
      const menu = activityMenu || findActivityMenu(activityCategory, source);
      if (!source || activityClosing) return;
      activityClosing = true;
      try {
        if (activityCategory === 'jobs') {
          // JobListAction's original click is a true open/close toggle.
          if (source.getAttribute('aria-expanded') === 'true') {
            if (sourceAvailable(source)) source.click();
            else if (typeof env.KeyboardEvent === 'function') (menu || source.parentElement || source).dispatchEvent(
              new env.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
          }
        } else if (source.getAttribute('aria-expanded') === 'true' || menu) {
          // CatalogDropdown's click only pins/opens. Its shipped Escape handler
          // closes branches and subscriptions through the real React callback.
          if (typeof env.KeyboardEvent === 'function') (menu || source).dispatchEvent(
            new env.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
        }
      } finally {
        activityClosing = false;
      }
    }

    function closeActivity(restoreFocus = false) {
      const changed = activityOpen || Boolean(activitySource || activityMenu);
      closeNativeActivity();
      clearActivityWait();
      activityOpen = false;
      activitySource = activityMenu = activitySession = null;
      if (activityPanel) {
        if (!activityPanel.hidden) activityPanel.hidden = true;
        activityPanel.setAttribute('aria-hidden', 'true');
        activityPanel.setAttribute('data-dafeiyu-activity-state', 'closed');
      }
      activityButton?.setAttribute('aria-expanded', 'false');
      markers.reconcile(activityAncestorAttribute, new Map());
      if (restoreFocus) env.queueMicrotask?.(() => { if (active) activityButton?.focus(); });
      if (changed) scheduleDomUpdate();
    }

    function selectActivity(category) {
      if (!active || !activityOpen) return;
      const source = findActivitySources()[category];
      if (!hasActivity(category, source)) return;
      if (activityCategory === category && activitySource === source &&
          source.getAttribute('aria-expanded') === 'true') return;
      closeNativeActivity();
      clearActivityWait();
      activityCategory = category;
      activitySource = source;
      activityMenu = null;
      activitySession = activitySessionKey();
      activityPending = true;
      if (activityStatus) {
        activityStatus.hidden = false;
        activityStatus.textContent = isChinese() ? '正在打开活动…' : 'Opening activity…';
      }
      if (typeof env.setTimeout === 'function') activityTimer = env.setTimeout(() => {
        activityTimer = null;
        if (!active || !activityOpen || activitySource !== source) return;
        activityPending = false;
        if (source.getAttribute('aria-expanded') !== 'true' && !findActivityMenu(category, source)) {
          activitySource = null;
          if (activityStatus) activityStatus.textContent = isChinese() ? '活动菜单未能打开，请重新尝试。' : 'The activity menu could not open. Try again.';
        }
        scheduleDomUpdate();
      }, 1200);
      try {
        // No synthetic roster, event replay, direct store mutation, or task
        // action is involved: this opens the official live UI contribution.
        source.click();
      } catch {
        clearActivityWait();
        activitySource = null;
        if (activityStatus) activityStatus.textContent = isChinese() ? '活动菜单未能打开，请重新尝试。' : 'The activity menu could not open. Try again.';
      }
      env.queueMicrotask?.(() => {
        if (active && activityOpen && activityCategory === category) {
          (category === 'agents' ? activityAgents : activityJobs)?.focus();
        }
      });
      scheduleDomUpdate();
    }

    function selectAgents() { selectActivity('agents'); }
    function selectJobs() { selectActivity('jobs'); }
    function dismissActivity() { closeActivity(true); }

    function toggleActivity() {
      if (!active || activityButton?.disabled) return;
      if (activityOpen) { closeActivity(true); return; }
      const sources = findActivitySources();
      const category = hasActivity(activityCategory, sources[activityCategory]) ? activityCategory :
        hasActivity('agents', sources.agents) ? 'agents' : 'jobs';
      if (!hasActivity(category, sources[category]) || !activityPanel) return;
      closeQuick(false);
      setDrawer(false);
      activityOpen = true;
      activityPanel.hidden = false;
      activityPanel.setAttribute('aria-hidden', 'false');
      activityPanel.setAttribute('data-dafeiyu-activity-state', 'open');
      activityButton.setAttribute('aria-expanded', 'true');
      selectActivity(category);
    }

    function removeActivity() {
      clearActivityVisibility();
      closeActivity(false);
      activityButton?.removeEventListener('click', toggleActivity);
      activityAgents?.removeEventListener('click', selectAgents);
      activityJobs?.removeEventListener('click', selectJobs);
      activityClose?.removeEventListener('click', dismissActivity);
      if (activityButton) iconSnapshots.delete(activityButton);
      activityButton?.remove();
      activityPanel?.remove();
      activityButton = activityPanel = activityAgents = activityJobs = activityStatus = activityClose = null;
    }

    function clearActivityVisibility() {
      if (activityVisibilityTimer !== null) env.clearTimeout?.(activityVisibilityTimer);
      activityVisibilityTimer = null;
    }

    function updateActivityVisibility(present) {
      const button = activityButton;
      if (!button || !navigation) return;
      if (button.inert !== !present) button.inert = !present;
      setLocalAttribute(button, 'aria-hidden', String(!present));
      setLocalAttribute(navigation, 'data-dafeiyu-has-activity', String(present));
      if (present) {
        clearActivityVisibility();
        if (button.hidden) button.hidden = false;
        setLocalAttribute(button, 'data-dafeiyu-visibility', 'open');
        return;
      }
      const finish = () => {
        clearActivityVisibility();
        if (button !== activityButton) return;
        if (!button.hidden) button.hidden = true;
        setLocalAttribute(button, 'data-dafeiyu-visibility', 'closed');
      };
      if (button.hidden || root.getAttribute('data-dafeiyu-motion') === 'false' ||
          reducedMotion?.matches || typeof env.setTimeout !== 'function') { finish(); return; }
      if (button.getAttribute('data-dafeiyu-visibility') === 'closing') return;
      setLocalAttribute(button, 'data-dafeiyu-visibility', 'closing');
      activityVisibilityTimer = env.setTimeout(finish, 150);
    }

    function updateActivity() {
      if (!navigation || !activityButton) return;
      const sources = findActivitySources();
      const present = hasActivity('agents', sources.agents) || hasActivity('jobs', sources.jobs);
      updateActivityVisibility(present);
      if (!present && activityOpen) closeActivity(false);
      if (!activityPanel) {
        activityPanel = document.createElement('div');
        activityPanel.setAttribute(layoutAttribute, 'activity-panel');
        activityPanel.setAttribute('role', 'dialog');
        activityPanel.setAttribute('aria-modal', 'false');
        activityPanel.id = 'dafeiyu-mobile-activity-panel';
        activityPanel.hidden = true;
        activityPanel.setAttribute('aria-hidden', 'true');
        activityPanel.setAttribute('data-dafeiyu-activity-state', 'closed');
        const tabs = document.createElement('div');
        tabs.setAttribute('role', 'tablist');
        for (const category of ['agents', 'jobs']) {
          const button = document.createElement('button');
          button.type = 'button';
          button.setAttribute(controlAttribute, `activity-${category}`);
          button.setAttribute('role', 'tab');
          button.addEventListener('click', category === 'agents' ? selectAgents : selectJobs);
          tabs.appendChild(button);
          if (category === 'agents') activityAgents = button;
          else activityJobs = button;
        }
        activityClose = document.createElement('button');
        activityClose.type = 'button';
        activityClose.setAttribute(controlAttribute, 'close');
        activityClose.textContent = '×';
        activityClose.addEventListener('click', dismissActivity);
        activityStatus = document.createElement('p');
        activityStatus.setAttribute('role', 'status');
        activityStatus.setAttribute(layoutAttribute, 'activity-status');
        activityPanel.appendChild(tabs);
        activityPanel.appendChild(activityClose);
        activityPanel.appendChild(activityStatus);
        (document.body || root).appendChild(activityPanel);
        activityButton.setAttribute('aria-controls', activityPanel.id);
      }
      const chinese = isChinese();
      setLocalAttribute(activityButton, 'aria-label', chinese ? '子智能体与后台任务' : 'Subagents and background jobs');
      setLocalAttribute(activityPanel, 'aria-label', chinese ? '活动' : 'Activity');
      setLocalAttribute(activityClose, 'aria-label', chinese ? '关闭活动' : 'Close activity');
      if (sources.team?.querySelector('svg')) cloneOfficialIcon(activityButton, sources.team);
      else installActivityFallbackIcon();
      if (!activityButton.querySelector('svg')) {
        const label = chinese ? '活动' : 'Activity';
        // A same-value textContent write still replaces children in browsers
        // and wakes our observer. Only write when the fallback label changes.
        if (activityButton.textContent !== label) activityButton.textContent = label;
      }
      setLocalDisabled(activityButton, !present);
      for (const [category, button] of [['agents', activityAgents], ['jobs', activityJobs]]) {
        const label = category === 'agents' ? (chinese ? '子智能体' : 'Subagents') : (chinese ? '后台任务' : 'Background jobs');
        if (button.textContent !== label) button.textContent = label;
        setLocalAttribute(button, 'title', sources[category] ? labelOf(sources[category]) : (chinese ? '当前会话暂无此类活动' : 'No activity of this kind in the current session'));
        setLocalAttribute(button, 'aria-selected', String(category === activityCategory));
        setLocalDisabled(button, !hasActivity(category, sources[category]));
      }
      if (!activityOpen) return;
      if (activitySession !== activitySessionKey() ||
          (activitySource && (sources[activityCategory] !== activitySource || !hasActivity(activityCategory, activitySource)))) {
        closeActivity(false);
        return;
      }
      activityMenu = findActivityMenu(activityCategory, activitySource);
      if (activityMenu || activitySource?.getAttribute('aria-expanded') === 'true') clearActivityWait();
      if (activitySource && !activityMenu && !activityPending &&
          activitySource.getAttribute('aria-expanded') !== 'true') {
        closeActivity(false);
        return;
      }
      if (activityStatus.hidden !== Boolean(activityMenu)) activityStatus.hidden = Boolean(activityMenu);
    }

    function markStaticTitles(controls) {
      for (const crumbs of all('[data-slot="conversation.session.header"] nav.wSkVaW_crumbs')) {
        if (!/^(?:会话层级|Session hierarchy)$/iu.test(labelOf(crumbs).trim()) ||
            crumbs.querySelector('button, a, [role="button"], [tabindex], input, select, textarea, [contenteditable], [onclick]')) continue;
        for (const title of crumbs.querySelectorAll('span.wSkVaW_crumbCurrent')) {
          const parent = title.parentElement;
          const direct = parent === crumbs || (parent?.tagName?.toLowerCase() === 'span' &&
            parent.classList?.contains('wSkVaW_crumbSeg') && parent.parentElement === crumbs);
          const knownClasses = ['wSkVaW_crumb', 'wSkVaW_crumbCurrent', 'wSkVaW_crumbSubagent'];
          // Hide only the reviewed title text, never a plugin lineage seat or a
          // title wrapper that can contain controls, links or unknown children.
          if (direct && title.children.length === 0 && title.textContent?.trim() &&
              !title.hasAttribute('data-slot') && !title.hasAttribute('role') &&
              !title.hasAttribute('tabindex') && !title.hasAttribute('contenteditable') &&
              !title.hasAttribute('onclick') && typeof title.onclick !== 'function' &&
              title.className.split(/\s+/u).every(name => knownClasses.includes(name))) {
            controls.set(title, 'mobile-static-title');
          }
        }
      }
    }

    function hasHeaderContribution(element, controls, ignoreActivityMenu = false) {
      // Jobs are rendered inside this header. An actual open native menu keeps
      // its ancestor mounted and visible even when the old controls are empty.
      if (ignoreActivityMenu && activityOpen && activityCategory === 'jobs' && element === activityMenu) return false;
      const role = controls.get(element);
      if (['mobile-hidden', 'mobile-static-title', 'activity-source-agents', 'activity-source-jobs', 'rightbar-toggle'].includes(role) ||
          element.hasAttribute('hidden')) return false;
      if (element.matches?.('button, a, input, select, textarea, [role="button"], [tabindex], [contenteditable], [onclick]') ||
          typeof element.onclick === 'function') return true;
      const tag = element.tagName?.toLowerCase();
      const slot = element.getAttribute('data-slot');
      const knownSlots = ['conversation.session.header', 'conversation.session.header.lineage',
        'conversation.session.header.actions', 'conversation.session.header.utilities',
        'conversation.session.header.corner', 'conversation.header.leading'];
      if (slot && !knownSlots.includes(slot)) return true;
      const knownClasses = ['wSkVaW_header', 'wSkVaW_headerLeading', 'wSkVaW_titleRow',
        'wSkVaW_titleCluster', 'wSkVaW_headerActions', 'wSkVaW_headerUtilities',
        'wSkVaW_headerCorner', 'wSkVaW_tabs', 'ZKlsPq_root', 'QsffPG_root',
        'OMoRSG_menuAnchor', '_root_4ub78_1'];
      const knownCrumb = (tag === 'nav' && element.classList?.contains('wSkVaW_crumbs') &&
        /^(?:会话层级|Session hierarchy)$/iu.test(labelOf(element).trim())) ||
        (tag === 'span' && element.classList?.contains('wSkVaW_crumbSeg'));
      if (!slot && !knownCrumb && !(['header', 'div', 'span'].includes(tag) &&
                     knownClasses.some(name => element.classList?.contains(name)))) return true;
      if (hasOwnHeaderText(element)) return true;
      return [...element.children].some(child => hasHeaderContribution(child, controls, ignoreActivityMenu));
    }

    function hasOwnHeaderText(element) {
      return [...(element.childNodes || [])].some(node => node.nodeType === 3 && node.textContent?.trim());
    }

    function removeNavigation() {
      clearExpansionWait();
      removeActivity();
      removeQuick();
      clearPressed();
      sidebarButton?.removeEventListener('click', toggleDrawer);
      newSessionButton?.removeEventListener('click', toggleQuick);
      backdrop?.removeEventListener('click', closeDrawer);
      navigation?.remove();
      backdrop?.remove();
      navigation = backdrop = navFrame = sidebarButton = newSessionButton = navTitle = null;
      iconSnapshots.clear();
      drawerOpen = false;
    }

    function closeDrawer() { if (active) setDrawer(false); }

    function updateNavigation(parts) {
      const sidebarSource = findSidebarSource();
      const hasLayoutAction = typeof layout?.toggleSidebar === 'function';
      if (!parts.frame || !parts.sidebar || (!sidebarSource && !hasLayoutAction && !navigation)) {
        removeNavigation();
        rootAttributes.set(root, 'data-dafeiyu-drawer', 'closed');
        return;
      }
      if (navFrame !== parts.frame) removeNavigation();
      if (!navigation) {
        navFrame = parts.frame;
        navigation = document.createElement('nav');
        navigation.setAttribute(layoutAttribute, 'navigation');
        navigation.setAttribute('aria-label', isChinese() ? '主导航' : 'Main navigation');
        sidebarButton = document.createElement('button');
        sidebarButton.type = 'button';
        sidebarButton.setAttribute(controlAttribute, 'sidebar-toggle');
        sidebarButton.addEventListener('click', toggleDrawer);
        navTitle = document.createElement('span');
        newSessionButton = document.createElement('button');
        newSessionButton.type = 'button';
        newSessionButton.setAttribute(controlAttribute, 'new-session');
        newSessionButton.setAttribute('aria-haspopup', 'menu');
        newSessionButton.setAttribute('aria-expanded', 'false');
        newSessionButton.addEventListener('click', toggleQuick);
        navigation.appendChild(sidebarButton);
        navigation.appendChild(navTitle);
        activityButton = document.createElement('button');
        activityButton.type = 'button';
        activityButton.setAttribute(controlAttribute, 'activity-hub');
        activityButton.setAttribute('aria-haspopup', 'dialog');
        activityButton.setAttribute('aria-expanded', 'false');
        activityButton.hidden = true;
        activityButton.inert = true;
        activityButton.addEventListener('click', toggleActivity);
        navigation.appendChild(activityButton);
        navigation.appendChild(newSessionButton);
        backdrop = document.createElement('button');
        backdrop.type = 'button';
        backdrop.setAttribute(layoutAttribute, 'backdrop');
        backdrop.setAttribute('aria-label', isChinese() ? '关闭侧栏' : 'Close sidebar');
        backdrop.addEventListener('click', closeDrawer);
        parts.frame.appendChild(backdrop);
        parts.frame.appendChild(navigation);
        setDrawer(false);
      }
      const newSource = findNewSessionSource();
      setLocalAttribute(sidebarButton, 'aria-label', isChinese() ? '打开或收起侧栏' : 'Toggle sidebar');
      setLocalDisabled(sidebarButton, (!sidebarSource && !hasLayoutAction) || Boolean(
        sidebarSource && (sidebarSource.disabled || sidebarSource.getAttribute('aria-disabled') === 'true')));
      setLocalAttribute(newSessionButton, 'aria-label', isChinese() ? '新建会话、扫码与配对码' : 'New session, scan and pairing code');
      cloneOfficialIcon(sidebarButton, sidebarSource);
      cloneOfficialIcon(newSessionButton, newSource);
      const title = currentTitle();
      if (navTitle.textContent !== title) navTitle.textContent = title;
      setLocalAttribute(navTitle, 'title', title);
      if (parts.frame.getAttribute('data-sidebar-collapsed') !== 'true') clearExpansionWait();
      else if (drawerOpen && !awaitingExpansion) setDrawer(false);
    }

    function updateMarkers(parts) {
      const controls = new Map();
      const groups = [
        ['header', '[data-slot="conversation.header.leading"] button'],
        ['session-action', '[data-slot="conversation.session.header"] button, [data-slot="conversation.session.header.actions"] button, [data-slot="conversation.session.header.utilities"] button'],
        ['model', '[data-slot="conversation.input.model"] button'],
        ['permission', '[data-slot="conversation.input.permission"] button'],
        ['activity', '[data-slot="conversation.input.activity"] button'],
        ['send', '[data-slot="conversation.composer.bar"] button[type="submit"], [data-slot="conversation.composer.bar"] button[aria-label^="发送"], [data-slot="conversation.composer.bar"] button[aria-label^="Send" i]'],
      ];
      // Specific roles overwrite the generic header entry in this desired map.
      // Native view tabs have their own layout; preserve that independently of
      // the session actions even when a plugin contributes tabs in its header.
      for (const [role, selector] of groups) for (const button of all(selector)) {
        if (button.closest('ul.QsffPG_menu, div.ZKlsPq_menu, [data-team-panel]')) continue;
        if ((role === 'header' || role === 'session-action') &&
            (button.getAttribute('role') === 'tab' || button.closest('[data-conversation-tabs]'))) continue;
        controls.set(button, role);
      }
      for (const button of all('[data-slot="conversation.input.activity"] button')) {
        if (isMicrophone(labelOf(button))) controls.set(button, 'microphone');
      }
      for (const button of all('button[data-sidebar-right-expand], [data-slot="conversation.session.header.corner"] button')) {
        if (button.hasAttribute('data-sidebar-right-expand') || isRightbarToggle(labelOf(button))) {
          controls.set(button, 'rightbar-toggle');
        }
      }
      const sidebarToggle = findSidebarSource();
      if (sidebarToggle) controls.set(sidebarToggle, 'sidebar-toggle-original');
      const sidebarNewSession = findNewSessionSource();
      if (sidebarNewSession?.classList?.contains('hHd-Xa_newSession')) controls.set(sidebarNewSession, 'sidebar-new-session-source');
      const sources = findActivitySources();
      if (sources.agents) controls.set(sources.agents, 'activity-source-agents');
      if (sources.jobs) controls.set(sources.jobs, 'activity-source-jobs');
      const hiddenSelectors = [
        '[data-slot="conversation.session.header.actions"] [data-team-action]',
        '[data-slot="conversation.session.header.actions"] .SVAs4q_label',
        '[data-slot="conversation.session.header.utilities"] .OMoRSG_split[data-open-target="directory"]',
        '[data-slot="conversation.session.header.utilities"] button.nL4_yW_moreButton',
        '[data-slot="conversation.chat.assistant-actions"] button._8_XoUG_action',
      ];
      for (const selector of hiddenSelectors) for (const element of all(selector)) controls.set(element, 'mobile-hidden');
      for (const tabs of all('[data-conversation-tabs]')) {
        const nativeTabs = [...tabs.querySelectorAll('button[role="tab"]')];
        const hiddenTabs = nativeTabs.filter(button => button.classList?.contains('wSkVaW_tab') &&
          /^(?:对话|轨迹|Chat|Trajectory)$/iu.test(button.textContent.trim()));
        for (const button of hiddenTabs) controls.set(button, 'mobile-hidden');
        // An extension tab remains accessible. Only the entirely hidden native
        // strip can be collapsed, so it does not leave a blank header band.
        if (hiddenTabs.length > 0 && hiddenTabs.length === nativeTabs.length &&
            [...tabs.children].every(child => hiddenTabs.includes(child))) controls.set(tabs, 'mobile-hidden');
      }
      for (const closeSlot of all('[data-slot="settings.close"]')) {
        const button = closeSlot.closest('button');
        if (button) controls.set(button, 'close');
      }
      for (const button of all('button[aria-label], button[title], [role="dialog"] button')) {
        if (navigation?.contains(button) || activityPanel?.contains(button) || quickPanel?.contains(button) || button === backdrop) continue;
        if (isClose(labelOf(button))) controls.set(button, 'close');
      }
      markStaticTitles(controls);
      for (const header of all('[data-slot="conversation.header"] > header.wSkVaW_header')) {
        if (!hasHeaderContribution(header, controls)) controls.set(header, 'mobile-empty-header');
        else if (activityOpen && activityCategory === 'jobs' && activityMenu && header.contains(activityMenu) &&
                 !hasHeaderContribution(header, controls, true)) controls.set(header, 'mobile-activity-only-header');
      }
      markers.reconcile(controlAttribute, controls);
      const layouts = new Map();
      for (const [role, element] of Object.entries(parts)) if (element) layouts.set(element, role);
      const activityAncestors = new Map();
      if (activityOpen && activityMenu) {
        layouts.set(activityMenu, `activity-native-${activityCategory}`);
        // The jobs popover is a real descendant of the header, rather than a
        // portal. Preserve its DOM ownership while CSS removes containing and
        // clipping ancestors only for the lifetime of this actual open menu.
        if (activityCategory === 'jobs') {
          let ancestor = activityMenu.parentElement;
          while (ancestor && ancestor !== root) {
            activityAncestors.set(ancestor, 'true');
            ancestor = ancestor.parentElement;
          }
        }
      }
      const conversation = query('[data-slot="main.conversation"]')?.firstElementChild;
      if (conversation && ['hero', 'active', 'settling'].includes(conversation.getAttribute('data-phase'))) {
        layouts.set(conversation, 'conversation-root');
      }
      for (const seat of all('[data-composer-seat]')) layouts.set(seat, 'composer-seat');
      for (const bar of all('[data-slot="conversation.composer.bar"]')) {
        const stack = bar.parentElement;
        const seat = bar.closest('[data-composer-seat]');
        if (!stack || !seat || stack === seat || !seat.contains(stack)) continue;
        layouts.set(stack, 'composer-stack');
        const brand = stack.querySelector('[data-slot="conversation.hero.brand.mark"]');
        if (!brand || !stack.closest('[data-phase="hero"], [data-content-phase="hero"]')) continue;
        let hero = brand.parentElement;
        while (hero && hero.parentElement !== stack && hero !== stack) hero = hero.parentElement;
        if (hero && hero !== stack && hero.parentElement === stack && !hero.contains(bar)) {
          layouts.set(hero, 'hero');
        }
      }
      const sidebar = query('[data-slot="sidebar"]');
      const sidebarRoot = sidebar?.firstElementChild;
      const sections = new Map();
      const addSection = (element, role) => {
        if (!element || !sidebar?.contains(element)) return;
        layouts.set(element, 'sidebar-section');
        sections.set(element, role);
      };
      for (const child of sidebarRoot?.children || []) {
        if (child.tagName?.toLowerCase() === 'nav') addSection(child, 'navigation');
      }
      const workspaces = sidebar?.querySelector('[data-slot="sidebar.workspaces"]');
      addSection(workspaces?.firstElementChild, 'workspaces');
      const brandMark = sidebar?.querySelector('[data-slot="sidebar.brand.mark"]');
      const brandButton = brandMark?.closest('button');
      if (brandButton && brandButton !== sidebarToggle) layouts.set(brandButton, 'sidebar-brand');
      else if (!brandButton && brandMark?.parentElement?.parentElement &&
               sidebar.contains(brandMark.parentElement.parentElement)) {
        layouts.set(brandMark.parentElement.parentElement, 'sidebar-brand');
      }
      const brandRow = brandMark?.closest('[data-window-drag]');
      if (brandRow && sidebar.contains(brandRow)) layouts.set(brandRow, 'sidebar-brand-row');
      const settings = sidebar?.querySelector('[data-slot="sidebar.settings"]');
      const footerAction = sidebar?.querySelector('[data-slot="sidebar.footer.action"]');
      if (settings && footerAction) {
        let footer = footerAction.parentElement;
        while (footer && footer !== sidebarRoot && !footer.contains(settings)) footer = footer.parentElement;
        if (footer && footer !== sidebarRoot) addSection(footer, 'footer');
      }
      for (const attachments of all('[data-slot="conversation.input.attachments"]')) {
        const card = attachments.parentElement;
        if (!card) continue;
        layouts.set(card, 'composer-card');
        const model = card.querySelector('[data-slot="conversation.input.model"]');
        const left = card.querySelector('[data-slot="conversation.input.left"]');
        let row = model?.parentElement;
        if (left) {
          while (row && row !== card && !row.contains(left)) row = row.parentElement;
        } else {
          // The no-session composer keeps the shipped standardControls /
          // trailing / row nesting without exposing session-only left slots.
          row = row?.parentElement?.parentElement;
        }
        if (row && row !== card && card.contains(row)) layouts.set(row, 'composer-actions');
      }
      markers.reconcile(layoutAttribute, layouts);
      markers.reconcile(sectionAttribute, sections);
      markers.reconcile(activityAncestorAttribute, activityAncestors);
    }

    function updateDom() {
      scheduled = false;
      if (!active || disposed) return;
      const parts = findLayout();
      updateNavigation(parts);
      updateQuick();
      updateActivity();
      updateMarkers(parts);
      sidebarTouch?.update();
      footer?.update?.(parts);
      materialTheme?.update();
    }

    function scheduleDomUpdate() {
      if (scheduled || !active || disposed) return;
      scheduled = true;
      if (typeof env.queueMicrotask === 'function') env.queueMicrotask(updateDom);
      else if (typeof env.setTimeout === 'function') env.setTimeout(updateDom, 0);
      else updateDom();
    }

    function onKey(event) {
      if (event.defaultPrevented) return;
      if (active && quickOpen) {
        if (event.key === 'Escape') {
          event.preventDefault?.();
          closeQuick(true);
          return;
        }
        if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key) &&
            (quickPanel?.contains(event.target) || newSessionButton?.contains(event.target))) {
          const buttons = [quickNewSession, quickScan, quickPair].filter(button => !button.disabled);
          if (!buttons.length) return;
          event.preventDefault?.();
          const current = buttons.indexOf(document.activeElement);
          const index = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 :
            (current + (event.key === 'ArrowUp' ? -1 : 1) + buttons.length) % buttons.length;
          buttons[index].focus();
          return;
        }
      }
      if (active && activityOpen && !activityClosing && event.key === 'Escape') {
        event.preventDefault?.();
        closeActivity(true);
        return;
      }
      if (active && drawerOpen && event.key === 'Escape') {
        // Native menus and confirmations own the first Escape. Their capture
        // handlers may already have consumed it; modal listeners registered
        // after this adapter must also get to close without losing the drawer.
        if ([...all('[role="menu"], [role="dialog"][aria-modal="true"]')].some(element =>
          element !== quickPanel && element !== activityPanel &&
          !element.closest('[hidden], [inert], [aria-hidden="true"]'))) return;
        setDrawer(false);
        sidebarButton?.focus();
      }
    }

    function onDocumentClick(event) {
      if (!active || event.defaultPrevented || event.target?.closest?.(
        'button, a, input, textarea, select, [contenteditable], [role="button"], [role="menuitem"]')) return;
      const target = event.target?.closest?.('[data-row-key^="session:"]');
      if (target && findLayout().sidebar?.contains(target)) {
        closeActivity(false);
        closeQuick(false);
        if (drawerOpen) setDrawer(false);
      }
    }

    function onPointerDown(event) {
      if (!active) return;
      const target = event.target;
      clearPressed();
      const button = target?.closest?.('button[data-dafeiyu-mobile-control]');
      if (button && sourceAvailable(button) && !button.closest('[inert], [hidden]') &&
          root.getAttribute('data-dafeiyu-motion') !== 'false') {
        pressedButton = button;
        pressedPointer = event.pointerId;
        pressedAttributes.set(button, 'data-dafeiyu-pressed', 'true', false);
      }
      if (quickOpen && !quickPanel?.contains(target) && !newSessionButton?.contains(target)) closeQuick(false);
      if (!activityOpen) return;
      if (activityPanel?.contains(target) || activityButton?.contains(target)) {
        // Both official menus dismiss on a document bubble pointerdown. Own
        // tabs and hub are inside this merged surface; avoid closing the old
        // menu before its category-switch click reaches the real callback.
        event.stopPropagation?.();
        return;
      }
      const menu = activityMenu || findActivityMenu(activityCategory, activitySource);
      if (!menu?.contains(target) && !activitySource?.contains(target)) closeActivity(false);
    }

    function clearPressed() {
      if (pressedButton) pressedAttributes.release(pressedButton, 'data-dafeiyu-pressed');
      pressedButton = null;
      pressedPointer = null;
    }

    function onPointerEnd(event) {
      if (pressedButton && (pressedPointer === undefined || event.pointerId === pressedPointer)) clearPressed();
    }

    function deactivate() {
      if (!active) return;
      active = false;
      scheduled = false;
      observer?.disconnect();
      observer = null;
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('click', onDocumentClick);
      document.removeEventListener('pointerdown', onPointerDown, true);
      document.removeEventListener('pointerup', onPointerEnd, true);
      document.removeEventListener('pointercancel', onPointerEnd, true);
      reducedMotion?.removeEventListener?.('change', scheduleDomUpdate);
      env.removeEventListener?.('blur', clearPressed);
      env.removeEventListener?.('resize', updateViewport);
      sidebarTouch?.dispose();
      sidebarTouch = null;
      materialTheme?.dispose();
      materialTheme = null;
      removeNavigation();
      footer?.dispose?.();
      footer = null;
      markers.clear();
      pressedAttributes.clear();
      style?.remove();
      style = null;
      rootAttributes.clear();
      restoreViewport();
      restoreYield?.();
      restoreYield = null;
    }

    function activate(next) {
      if (!active) {
        active = true;
        restoreYield = installBridgeYield();
        rootAttributes.set(root, 'data-dsh-mobile-ui', 'dafeiyu-mobile-ui', false);
        style = document.createElement('style');
        style.setAttribute('data-plugin', 'dafeiyu-mobile-ui');
        style.setAttribute('data-plugin-css', 'dafeiyu-mobile-ui/styles');
        style.textContent = String(cssText);
        (document.head || root).appendChild(style);
        document.addEventListener('keydown', onKey);
        document.addEventListener('click', onDocumentClick);
        document.addEventListener('pointerdown', onPointerDown, true);
        document.addEventListener('pointerup', onPointerEnd, true);
        document.addEventListener('pointercancel', onPointerEnd, true);
        reducedMotion?.addEventListener?.('change', scheduleDomUpdate);
        env.addEventListener?.('blur', clearPressed);
        env.addEventListener?.('resize', updateViewport);
        sidebarTouch = createSidebarTouch(document, root, () => active && drawerOpen && !disposed);
        materialTheme = createMaterialThemeController(document, root);
        if (typeof options?.createFooter === 'function') footer = options.createFooter(env, { closeDrawer: () => setDrawer(false) }) || null;
        if (typeof env.MutationObserver === 'function') {
          observer = new env.MutationObserver(scheduleDomUpdate);
          observer.observe(root, {
            subtree: true, childList: true, characterData: true, attributes: true,
            attributeFilter: ['aria-label', 'title', 'type', 'data-slot', 'role',
              'data-sidebar-collapsed', 'disabled', 'aria-disabled', 'aria-selected',
              'data-sidebar-right-expand', 'data-row-key', 'data-phase',
              'data-content-phase', 'data-composer-seat', 'data-conversation-tabs',
              'aria-expanded', 'aria-haspopup', 'data-team-action', 'data-open-target', 'class', 'hidden',
              // A previously static title can gain an interaction without a
              // childList change. Re-run the conservative classifier so its
              // owned presentation marker is released immediately.
              'tabindex', 'contenteditable', 'onclick', 'data-ds-dark-theme'],
          });
        }
      }
      const attributes = {
        'data-dafeiyu-surface': 'mobile',
        'data-dafeiyu-platform': next.platform,
        'data-dafeiyu-mode': next.mode,
        'data-dafeiyu-theme': next.theme,
        'data-dafeiyu-material': String(next.material),
        'data-dafeiyu-motion': String(next.motion),
      };
      for (const [name, value] of Object.entries(attributes)) rootAttributes.set(root, name, value);
      if (next.preview) rootAttributes.set(root, 'data-dafeiyu-preview', 'mobile');
      else rootAttributes.release(root, 'data-dafeiyu-preview');
      materialTheme?.setContext(next);
      updateViewport();
      updateDom();
    }

    function sync() {
      if (disposed) return;
      const next = readContext();
      if (next) activate(next);
      else deactivate();
    }

    const events = [eventName, 'dafeiyu-dsh-host-change', 'popstate', 'hashchange', 'pageshow'];
    for (const name of events) env.addEventListener?.(name, sync);
    sync();
    return () => {
      if (disposed) return;
      disposed = true;
      for (const name of events) env.removeEventListener?.(name, sync);
      deactivate();
    };
  }

  return { normalizeHostContext, normalizeMaterialTheme, readContext, install };
}
