const test = require('node:test');
const assert = require('node:assert/strict');

function host() {
  const app = { getUserID: () => 9, _loginEpoch: 1 };
  global.getApp = () => app; global.Behavior = value => value;
  let definition, pending;
  global.Component = value => { definition = value; };
  global.wx = { getStorageSync: () => '', scanCode: options => { pending = options; }, vibrateShort() {} };
  const file = require.resolve('../../pages/play/components/playkit-scan/index.js'); delete require.cache[file]; require(file);
  const events = [];
  const component = { ...definition.methods, data: { ...definition.data, show: true, sessionId: 51, reply: '', imageUrl: '', audioUrl: '', overlayUrl: '' },
    triggerEvent: (name, detail) => events.push({ name, detail }) };
  return { component, definition, app, events, callback: () => pending };
}

test('扫码取消或空码可重试,当前局只转交原码给服务端判定', () => {
  const h = host(); h.component.onScan(); h.callback().fail();
  h.component.onScan(); h.callback().success({ result: ' DOCK-CODE ' });
  assert.deepEqual(h.events, [{ name: 'scanned', detail: { code: 'DOCK-CODE' } }]);
});

for (const change of [
  h => { h.component.data.show = false; },
  h => { h.component.data.sessionId = 52; },
  h => { h.app.getUserID = () => 10; },
  h => { h.app._loginEpoch++; },
  h => { h.definition.lifetimes.detached.call(h.component); },
]) test('原生扫码晚到隐藏/换局/换号/换登录代次/销毁的组件时丢弃', () => {
  const h = host(); h.component.onScan(); change(h); h.callback().success({ result: 'DOCK-CODE' });
  assert.deepEqual(h.events, []);
});

test('相机旧局去重定时器不能清掉新局同码的去重状态', () => {
  const h = host(), original = global.setTimeout; let callback;
  try {
    global.setTimeout = fn => { callback = fn; };
    h.component.onScanCode({ detail: { result: 'DOCK-CODE' } });
  } finally { global.setTimeout = original; }
  h.definition.observers['show, sessionId'].call(h.component);
  h.component._lastCode = 'DOCK-CODE';
  callback();
  assert.equal(h.component._lastCode, 'DOCK-CODE');
});

test('原生扫码双击只开一个相机请求', () => {
  const h = host(); let opens = 0; global.wx.scanCode = () => { opens++; };
  h.component.onScan(); h.component.onScan(); assert.equal(opens, 1);
});
