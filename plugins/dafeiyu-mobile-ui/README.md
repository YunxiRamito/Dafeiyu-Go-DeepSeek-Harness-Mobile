# dafeiyu-mobile-ui

This package is the mobile presentation layer for the official DSH web client. It has no runtime npm dependencies and does not replace the official sidebar, conversation, settings, model, or session slots. Its browser factory is loaded through DSH's standard `window.__ModuleLoader__.load` client-module contract.

The package is inert on an ordinary PC page. Activation requires the explicit host marker below:

```js
globalThis.__DAFEIYU_DSH_CLIENT__ = {
  version: 1,
  platform: 'android',
  surface: 'mobile',
  mode: 'local', // or 'online'
  theme: 'system', // or 'light' / 'dark'
  material: true,
  motion: true,
};
```

For a desktop visual review, the page URL may explicitly contain `?dafeiyu-preview=mobile`. A narrow viewport, browser user-agent, or desktop platform marker never enables the package. The host can publish a `dafeiyu-dsh-client-change` event after changing the marker; the plugin revalidates the complete object, updates its `data-dafeiyu-*` attributes, and removes all style, marker, observer, and listener state when the marker is absent or the Cordis fiber is disposed.

Only in explicit preview, optional `dafeiyu-theme=system|light|dark`, `dafeiyu-material=true|false`, and `dafeiyu-motion=true|false` parameters control appearance. Invalid values fall back to system theme and enabled material/motion. For example, `?dafeiyu-preview=mobile&dafeiyu-theme=dark&dafeiyu-material=true&dafeiyu-motion=false` previews a dark surface with motion disabled. These parameters are ignored without the preview gate, and a complete Android host context always takes precedence. Preview changes affect this page's owned presentation markers, not DSH's saved global theme settings.

The DOM adapter uses the official renderer's stable `data-slot` anchors and owned `data-dafeiyu-mobile-control` / `data-dafeiyu-mobile-layout` attributes. It adds a small mobile navigation and drawer backdrop beside the official frame. The navigation clones the installed official SVGs and invokes the original sidebar and New Session actions. Opening a collapsed sidebar uses the official layout service; closing the mobile drawer does not toggle the persisted sidebar preference. Session-row selection, Escape, and the backdrop close the mobile drawer. The official settings, session callbacks, and task disclosure controls remain in place.

The mobile surface hides the right-sidebar launcher and microphone using their specific official attributes and localized accessible names. It merges the official subagent tree and background job list into a single circular activity control in the top bar. The control appears only when the current session has real subagent or background-job records; completed records still count, while empty sessions reserve no space. Its two categories invoke the real native triggers and keep the real menus, task lifecycle, and subscriptions; no React-owned nodes or lists are copied. Native menu clipping is removed only while the actual jobs menu is open. The original team/static-mode controls, file/open/more utility group, Chat/Trajectory tabs, and thumb feedback buttons are hidden. Copy, share, usage, time, and unrelated extension contributions remain available. Precise selectors for menu contributions are bound to the reviewed SDK version and fail closed if that implementation changes. The navigation reads the actual session lineage or selected sidebar row, never headings from the conversation body. The title aligns left; the background uses a gradient blur mask, while text and icons remain clear.

The top-right New Session control opens a three-item menu: New Session, Scan and Pairing Code. New Session forwards the original native action. Scan and Pairing require the corresponding fixed Android host capability; in PC preview they remain disabled. Real pointer press/release markers drive finite elastic CSS transitions. Menus become inert immediately on exit and retain only their short disappearance animation; reduced-motion preference disables both transitions and scaling.

The duplicate sidebar New Session button is hidden in the mobile surface, with its original callback retained for the top-right menu. Reviewed session and workspace rows support touch and pen gestures: a first left swipe reveals the yellow Archive or red Delete action; only a new, independent left swipe after release and settling invokes it. Long press opens the real session rename/pin menu or workspace rename menu. Vertical scrolling, multiple pointers, cancellation, loss of row capture, drawer closure and context removal cancel pending gestures. Native row buttons and mouse interaction keep their original callbacks. Delayed workspace menus are bounded and tied to the exact row identity; the adapter never presses a confirmation button.

Workspace deletion currently forwards the official DSH behavior: unregister the workspace, retain its directory/logs and move its sessions to Ungrouped. It does not add filesystem deletion, stop running tasks or archive all workspace sessions. The Android application's separate workspace lifecycle and broader parity requirements must not be inferred from this presentation adapter.

New conversations keep the workspace and agent preset controls above a bottom-aligned composer. The hero, composer, and sidebar sections are marked in place; React-owned nodes are not moved or replaced. The drawer groups its real navigation, workspace list, and footer, and removes the duplicate desktop brand and collapse row. User bubbles, thought and tool disclosures, and task panels use explicit light/dark presentation tokens. Layout and controls are verified in the real DSH page at 320, 390, and 430 pixels; screenshots and artifact hashes are recorded separately in the repository handover.

Settings use compact typography, a full-width content area and horizontally scrollable category navigation, including dynamically contributed categories and plugin forms. The mobile drawer has a fixed footer with device presence and separate native DSH settings (wrench) and Android App settings (gear) actions. App and connection settings are disabled and marked reserved in a PC preview. An unknown or incomplete roster is displayed as unknown, never as a fabricated zero. PC's existing online-device panel remains unchanged on ordinary PC pages. In the active surface only, the package publishes the page-local `__DSH_MOBILE_SHELL__` / `data-dsh-mobile-ui` yield signal recognized by the installed bridge, and supplies its own navigation instead of depending on bridge navigation.

The startup presentation waits for the official connection service's real `connected` state, the root/composer and a visible sidebar button. Its finite reveal shrinks toward that button's measured coordinates and hands over to a short button fade. A timeout exposes retry/cancel instead of reporting success; cancellation reveals the original connection UI. It never starts or stops DSH. It covers the page after scripts load; the earlier Android service-launch waiting screen belongs to subsequent host integration.

Every changed attribute records both its prior value and the installed value. Gate removal or disposal restores it only if it still belongs to this plugin; later host changes are preserved. Detached marked elements are restored too. The context gate controls presentation and grants no task, provider, filesystem, or administrative permission.

`lib/index.js` is the server ESM export and contains a no-op `apply`. `client/context.js` exports the self-contained `createDafeiyuMobileContext` factory; `client/index.js` exports the Cordis client plugin factory; `client/host-footer.js` supplies the optional host display adapter; `client/startup.js` owns readiness presentation. `tools/build.mjs` serializes those factories and `client/styles.css` into `client/client.js`, the distinct `./client` export. The generated resource registers a lazy ModuleLoader factory; registration itself does not activate the surface.

## Android host adapter reserved for the subsequent integration

After validating the full Android client context, the footer optionally reads `globalThis.__DAFEIYU_DSH_HOST__` with `version: 1`, `platform: 'android'`, and a string-array `capabilities`. The `app.settings.open` capability requires `openAppSettings()`; `connection.settings.open` requires `openConnectionSettings()`; `app.scan.open` requires `openScanner()`; `pairing.open` requires `openPairing()`. These fixed actions accept no URL, command, parameters or credentials. `connection.state` requires `getConnectionState()` and optionally `subscribeConnectionState(listener)` returning a disposer. Connection snapshots use the existing `dafeiyu-go.connection.v1` display state. The footer counts only a complete active member roster with known presence on a connected, confirmed group, and otherwise displays unknown. A late initial read cannot overwrite a newer subscription event. The host publishes `dafeiyu-dsh-host-change` when replacing its bridge object. The Android repository now has a document-start/WebMessageListener adapter source candidate for these callbacks. It is not yet compiled, installed or device-verified. The current PC service does not provide these application callbacks and this package does not invent a server endpoint or pairing state. A URL-only preview cannot enable application actions through a stray bridge global.

## Installation and lifecycle boundary

`dsh.bundle.patch` points to `cordis.patch.yml`, which adds the package using its real package name. The client manifest declares the already-installed official layout and connection packages as module graph dependencies; the exported client plugin separately declares `layout` and `connection` as Cordis service dependencies. There are no additional runtime npm packages.

Install the built package into the intended DSH web profile through that host's plugin or bundle installation path. Official module reconciliation and optional HMR handle client entry changes; actual live reload depends on the running host configuration. This package performs no process restart, profile rewrite, or global webserver injection. Installation and live-service verification are recorded separately in the repository handover.

Build on a machine with the repository's existing Node runtime:

```text
node tools/build.mjs
node --test tests/*.test.mjs
```

These commands run from the canonical `Dafeiyu-Go-DeepSeek-Harness-Mobile-CSS` directory beside the Android repository. The old Android `plugins/dafeiyu-mobile-ui` path is a compatibility junction to that single source. The generated browser file is self-contained and must be rebuilt when a client factory or the stylesheet changes. Node boundary tests cover activation and cleanup; they do not replace a live browser check of the official layout, actions, or appearance.

For the current local development service, run `node tools/preview.mjs` and open [the phone preview](http://127.0.0.1:8791/). It embeds the actual DSH page at port 8787 and provides 320/390/430px widths, theme, material, and motion controls. The Replay Startup button repeats the waiting presentation and actual reveal animation without restarting DSH or changing its connection state. Replay messages require explicit mobile preview context, the exact parent window and `http://127.0.0.1:8791` origin; production Android contexts reject them. The helper listens only on loopback, reads the existing local login entry at runtime, and never stores or prints that credential. It is a repository development tool, separate from the installed client plugin.

The reviewed `tools/install-local-preview.mjs` is a development-only profile helper. It backs up the selected web profile manifest, appends only this zero-dependency bundle and a source junction under an official atomic writer lock, and requires the profile's live-reload mode. It does not run package-manager scripts or restart DSH. Actual boot-graph, served-resource, and browser verification are required after installation; the manifest change alone is not proof of a loaded plugin.

## Reviewed extension sources

The official extension contract was reviewed from [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness/tree/5badb15009ae1756c3afe0ae0cef1faafc290ccc), tag `dsh-v0.2.1-alpha.1`, commit `5badb15009ae1756c3afe0ae0cef1faafc290ccc` (MIT). The inspected parts are renderer slot anchors, AppFrame, layout service, sidebar and conversation seats, module loading, theme, and bundle metadata. The activity button includes the official UsersOutlineRegular fallback when activity exists but the team icon source is absent. Its source and full license are preserved in `THIRD_PARTY_NOTICES.md`. This plugin does not modify official business components.

Compatibility with the existing bridge's page-local yield contract was reviewed against `@wenbin_wb/dsh-bridge` `2.14.0`, fixed commit `06ef0e273c9cec8f405b50d4599d541d623552ee`. The bridge remains independently installed and unmodified. Navigation and the mobile stylesheet also work when it is absent.
