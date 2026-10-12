# 原生宿主注入契约

这份契约属于 Dafeiyu Go 移动插件，不是已有 DSH API。实现者必须在原生端核验当前 WebView 顶层 document origin、当前会话、消息来源和权限；网页上的 origin/session 字符串不能作为授权凭据。任意普通网页都能伪造 JavaScript 全局对象，因此真正的 App 独占边界在原生 host，不能仅靠此插件建立。

建议在受信任的顶层文档建立前安装冻结/不可重写的桥接包装，限定桥接对允许 origin 可见，导航即撤销；不要给 iframe 或任意导航继承会话。会话标识由 host 生成，导航/登出/host 实例变化换新。host 后续实现此部分，本轮没有 Android 修改。

## 桥接对象

```javascript
window.__DAFEIYU_MOBILE_HOST__ = {
  getSnapshot() { /* 返回当前规范快照 */ },
  subscribe(listener) { /* 推送事件，返回 unsubscribe 函数 */ },
  postMessage(jsonString) { /* 只发送下面的固定请求协议 */ }
};
window.dispatchEvent(new Event("dafeiyu-mobile-host-ready"));
```

快照不可原地修改；更新须先替换 getSnapshot 的返回值，递增 revision，再通知 listener。单个 revision 的规范值必须保持不变。disabled/无效数据/session 变化/桥接对象替换会撤销插件。权限减少在每个动作前重新检查，旧 snapshot 通知无法覆盖当前 getSnapshot。

```javascript
{
  protocol: "dafeiyu.mobile/1",
  enabled: true,
  origin: "https://受信任的实际页面来源",
  sessionId: "host生成的当前文档会话",
  revision: 1,
  capabilities: ["mobile-ui", "app-settings", "devices", "workspace"],
  reduceMotion: false,
  reduceTransparency: false,
  safeArea: {top: 24, right: 0, bottom: 20, left: 0},
  keyboard: {visible: false, height: 0, mode: "resize"},
  devicesObservedAt: 1791676800000,
  devices: [
    {id: "设备id", name: "设备名", online: true, member: true, keyVerified: false}
  ],
  workspaceId: null
}
```

示例数值只描述格式，不能用作实际状态。时间戳来自 host 当前真实观测；host 不应续写旧状态的时间以伪装新鲜度。`devices` 可以为空；没有设备能力显示未接入。

safeArea/keyboard 单位均为 **CSS px**，host 需正确换算 Android 物理像素。safeArea 表示尚未由原生 WebView 布局消费的 inset；已避让系统栏时应注入 0，避免重复避让。`resize` 表示页面布局已缩小；`overlay` 表示键盘仍遮挡页面，height 是遮挡深度。插件优先利用 VisualViewport，再只扣其未覆盖的遮挡，不承诺在错误的 host 输入下正确。

`workspaceId` 是官方 Workspace ID，未选中设 null。插件只展示官方 `ctx.workspaces.list.getSnapshot().items` 中的对应 path；不把手机 URI 当成 DSH 文件系统路径，不自动创建目录，不推断列表第一项为当前目录。

## 事件

```javascript
listener({kind: "snapshot", snapshot: currentSnapshot});
listener({
  protocol: "dafeiyu.mobile/1",
  kind: "reply",
  action: "open_app_settings",
  origin: currentOrigin,
  sessionId: currentSessionId,
  requestId: originalRequestId,
  ok: true
});
```

失败回包 `ok: false`，code 可为 `denied`、`unavailable` 或其他值。任意网页错误文本都不会直接展示，插件使用自己的中文错误文案。

## 唯一请求

```json
{
  "protocol": "dafeiyu.mobile/1",
  "kind": "request",
  "action": "open_app_settings",
  "origin": "https://实际页面来源",
  "sessionId": "当前文档会话",
  "requestId": "crypto.randomUUID()生成的请求ID"
}
```

host 只允许这个动作和这六个字段，限定消息长度/速率，拒绝任意附加参数，避免将消息映射到反射式方法调用。原生端必须验证实际来源和会话，不只比较消息自带字符串。请求 ID 仅用于关联，不是凭据。成功回包表示请求已交给 App，不代表用户已改设置。

插件一次只保留一个请求，12 秒超时。取消只取消页面等待，不发送其他 host 动作，也不能撤销已经开始的原生操作；取消后的迟到回包被丢弃。重试生成新的请求 ID，host 可对同一 document session 做合理幂等和防重入处理。
