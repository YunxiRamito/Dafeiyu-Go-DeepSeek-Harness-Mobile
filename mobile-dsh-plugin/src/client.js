/** Uses official owner props and icons, with no DSH-internal DOM selectors. */
export function createClient(React, icons, core, css, env) {
  const h = React.createElement;
  const inject = ["slots", "workspaces"];

  function apply(ctx) {
    ctx.effect(() => {
      let active = null;
      let activeBridge = null;
      let stopped = false;
      function activate() {
        if (stopped) return;
        if (active && activeBridge === env.__DAFEIYU_MOBILE_HOST__) return;
        active?.();
        const cleanups = [];
        let controller;
        const dispose = () => {
          if (active !== dispose) return;
          active = null;
          activeBridge = null;
          controller?.dispose();
          for (const cleanup of cleanups.reverse()) cleanup();
        };
        controller = core.createHostController(env.__DAFEIYU_MOBILE_HOST__, dispose);
        if (!controller) return;
        const root = env.document.getElementById("root");
        if (!root) { controller.dispose(); return; }
        active = dispose;
        activeBridge = env.__DAFEIYU_MOBILE_HOST__;
        const style = env.document.createElement("style");
        style.setAttribute("data-dafeiyu-mobile-style", "");
        style.textContent = css;
        env.document.head.appendChild(style);
        cleanups.push(() => style.remove());
        const originals = new Map();
        const installed = new Map();
        function setStyle(key, value) {
          if (!originals.has(key)) originals.set(key, [root.style.getPropertyValue(key), root.style.getPropertyPriority(key)]);
          root.style.setProperty(key, value);
          installed.set(key, value);
        }
        function updateViewport() {
          const host = controller.getSnapshot().host;
          const geometry = core.viewport(host, {
            width: env.document.documentElement.clientWidth,
            height: env.innerHeight
          }, env.visualViewport);
          for (const [key, value] of Object.entries({
            position: "fixed", "box-sizing": "border-box",
            left: `${geometry.left}px`, top: `${geometry.top}px`,
            width: `${geometry.width}px`, height: `${geometry.height}px`,
            "min-height": "0", "max-height": "none",
            "--dm-left": `${geometry.left}px`, "--dm-top": `${geometry.top}px`,
            "--dm-width": `${geometry.width}px`, "--dm-height": `${geometry.height}px`
          })) setStyle(key, value);
        }
        cleanups.push(() => {
          for (const [key, [value, priority]] of originals) {
            if (root.style.getPropertyValue(key) !== installed.get(key)) continue;
            if (value) root.style.setProperty(key, value, priority);
            else root.style.removeProperty(key);
          }
        });
        cleanups.push(controller.subscribe(updateViewport));
        env.addEventListener("resize", updateViewport);
        env.visualViewport?.addEventListener("resize", updateViewport);
        env.visualViewport?.addEventListener("scroll", updateViewport);
        cleanups.push(() => {
          env.removeEventListener("resize", updateViewport);
          env.visualViewport?.removeEventListener("resize", updateViewport);
          env.visualViewport?.removeEventListener("scroll", updateViewport);
        });
        updateViewport();
        function useState() {
          return React.useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot);
        }
        function attributes(host, extra = {}) {
          return {
            className: "dm-scope", "data-motion": host.reduceMotion ? "reduce" : "full",
            "data-transparency": host.reduceTransparency ? "reduce" : "full", ...extra
          };
        }
        function Icon({component}) {
          return h("span", {className: "dm-icon", "aria-hidden": true}, h(component, {size: 18}));
        }
        function GlassButton({label, icon, onClick, disabled, children, ...props}) {
          function release(event) {
            event.currentTarget.style.removeProperty("--dm-press-x");
            event.currentTarget.style.removeProperty("--dm-press-y");
            delete event.currentTarget.dataset.pressed;
          }
          return h("button", {
            type: "button", className: "dm-button", title: label, "aria-label": label,
            disabled, onClick, ...props,
            onPointerDown(event) {
              if (event.button !== 0) return;
              const bounds = event.currentTarget.getBoundingClientRect();
              const dx = event.clientX - (bounds.left + bounds.width / 2);
              const dy = event.clientY - (bounds.top + bounds.height / 2);
              const distance = Math.hypot(dx, dy);
              const resistance = 4 / (distance + 4);
              event.currentTarget.style.setProperty("--dm-press-x", `${dx * resistance}px`);
              event.currentTarget.style.setProperty("--dm-press-y", `${dy * resistance}px`);
              event.currentTarget.dataset.pressed = "";
              event.currentTarget.setPointerCapture(event.pointerId);
            },
            onPointerUp: release, onPointerCancel: release, onLostPointerCapture: release,
            onBlur: release
          }, h(Icon, {component: icon}), children);
        }
        let openOfficialSettings = null;
        function SettingsLauncher({wide, openSettings, settingsOpen}) {
          const {host} = useState();
          React.useEffect(() => {
            openOfficialSettings = openSettings;
            return () => { if (openOfficialSettings === openSettings) openOfficialSettings = null; };
          }, [openSettings]);
          if (!wide) return h("div", attributes(host, {
            className: "dm-scope dm-settings-rail"
          }), h(GlassButton, {
            label: "设置", icon: icons.IconSettingsOutlineRegular,
            onClick: () => controller.setPanel("settings")
          }));
          return h("div", attributes(host, {
            className: "dm-scope dm-settings", "data-wide": wide ? "true" : "false"
          }),
          h(GlassButton, {
            label: "DSH 设置", icon: icons.IconSettingsOutlineRegular,
            onClick: openSettings, "aria-expanded": settingsOpen
          }, wide && h("span", null, "DSH 设置")),
          h(GlassButton, {
            label: "App 设置", icon: icons.IconSettingsOutlineMedium,
            onClick: () => controller.openAppSettings(),
            disabled: !host.capabilities.includes("app-settings")
          }, wide && h("span", null, "App 设置")));
        }
        function Devices({wide}) {
          const state = useState();
          const summary = core.deviceSummary(state.host, state.tick);
          const label = summary.phase === "ready" ? `在线设备 ${summary.online}` :
            summary.phase === "stale" ? "设备状态待刷新" : "设备状态未接入";
          return h("div", attributes(state.host, {className: "dm-scope dm-devices", "data-wide": String(wide)}),
            h(GlassButton, {
              label, icon: icons.IconUsersOutlineRegular,
              onClick: () => controller.setPanel("devices")
            }, wide && h("span", null, label),
            h("i", {className: "dm-dot", "data-online": summary.phase === "ready" && summary.online > 0, "aria-hidden": true})));
        }
        function Workspace() {
          const {host} = useState();
          const list = React.useSyncExternalStore(
            callback => ctx.workspaces.list.subscribe(callback),
            () => ctx.workspaces.list.getSnapshot(),
            () => ctx.workspaces.list.getSnapshot()
          );
          const result = core.workspace(host, list);
          const value = result.path ?? ({
            unselected: "未选择工作目录", loading: "正在读取工作目录",
            error: "工作目录读取失败", missing: "所选工作目录已不可用"
          })[result.phase];
          return h("div", attributes(host, {className: "dm-scope dm-workspace", role: "status"}),
            h(Icon, {component: icons.IconFolderOpenOutlineRegular}),
            h("span", {className: "dm-workspace-label"}, "工作目录"),
            h("span", {className: "dm-path", title: value}, value));
        }
        function Overlay() {
          const state = useState();
          const ref = React.useRef(null);
          React.useEffect(() => {
            const dialog = ref.current;
            if (state.panel && !dialog.open) dialog.showModal();
            if (!state.panel && dialog.open) dialog.close();
            return () => { if (dialog.open) dialog.close(); };
          }, [state.panel]);
          const close = () => controller.setPanel(null);
          const devices = core.deviceSummary(state.host, state.tick);
          const request = state.request;
          return h("dialog", {
            ...attributes(state.host, {className: "dm-scope dm-dialog"}),
            ref, "aria-labelledby": "dm-panel-title",
            onCancel(event) { event.preventDefault(); close(); }
          },
          h("div", {className: "dm-dialog-heading"},
            h("h2", {id: "dm-panel-title"}, state.panel === "devices" ? "在线设备" :
              state.panel === "settings" ? "设置" : "App 设置"),
            h(GlassButton, {label: request.phase === "loading" ? "取消等待" : "关闭",
              icon: icons.IconCloseOutlineRegular, onClick: close})),
          state.panel === "devices" ? h("div", {className: "dm-device-list"},
            devices.phase !== "ready" ? h("p", {role: "status"},
              devices.phase === "stale" ? "设备状态已过期，正在等待 App 刷新" : "App 尚未提供设备状态") :
            state.host.devices.length === 0 ? h("p", null, "暂无设备") :
              state.host.devices.map(device => h("div", {key: device.id, className: "dm-device-row"},
                h(Icon, {component: icons.IconUsersOutlineRegular}),
                h("div", null, h("strong", null, device.name),
                  h("span", null, device.keyVerified && device.member ? "密钥已核验" : "密钥尚未核验")),
                h("span", {"data-online": device.online}, device.online ? "在线" : "离线")))) :
            state.panel === "settings" ? h("div", {className: "dm-settings"},
              h(GlassButton, {
                label: "DSH 设置", icon: icons.IconSettingsOutlineRegular,
                disabled: !openOfficialSettings,
                onClick: () => {
                  const open = openOfficialSettings;
                  close();
                  open?.();
                }
              }, h("span", null, "DSH 设置")),
              h(GlassButton, {
                label: "App 设置", icon: icons.IconSettingsOutlineMedium,
                disabled: !state.host.capabilities.includes("app-settings"),
                onClick: () => controller.openAppSettings()
              }, h("span", null, "App 设置"))) :
            h("div", {className: "dm-request", "aria-live": "polite"},
              h("p", {role: request.phase === "error" ? "alert" : "status"},
                request.phase === "loading" ? "正在请求打开 App 设置" :
                request.phase === "success" ? "请求已交给 App" :
                request.phase === "error" ? request.message : "已取消等待"),
              request.phase === "error" && h(GlassButton, {
                label: "重试", icon: icons.IconSettingsOutlineMedium,
                onClick: () => controller.openAppSettings()
              }, h("span", null, "重试"))));
        }
        try {
          for (const [name, options, component] of [
            ["settings.launcher", {priority: -100}, SettingsLauncher],
            ["sidebar.footer.action", {id: "dafeiyu-mobile-devices", order: -100}, Devices],
            ["shell.bottom", {priority: -100}, Workspace],
            ["shell.overlay", {id: "dafeiyu-mobile-dialog", order: 100}, Overlay]
          ]) {
            cleanups.push(ctx.slots.inject(name, () =>
              ctx.slots.register({name, ...options}, component)));
          }
        } catch (error) { dispose(); throw error; }
      }
      // The event only announces a bridge; activation always validates it again.
      env.addEventListener("dafeiyu-mobile-host-ready", activate);
      try { activate(); } catch (error) {
        env.removeEventListener("dafeiyu-mobile-host-ready", activate);
        throw error;
      }
      return () => {
        stopped = true;
        env.removeEventListener("dafeiyu-mobile-host-ready", activate);
        active?.();
      };
    }, "dafeiyu-mobile: native capability and official slots");
  }
  return {inject, apply};
}
