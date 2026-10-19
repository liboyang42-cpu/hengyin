'use strict';

// F0385 撤回漫游位置同意:撤回同步关所有定位持有者;旧授权缓存不放行重启;停止失败有反馈。
const assert = require('node:assert/strict');
const test = require('node:test');

const CONSENT = require.resolve('../../utils/location/roam-consent.js');
const TRACKER = require.resolve('../../utils/location/bg-tracker.js');
const ROAM = require.resolve('../../pages/roam/index.js');

function makeWx(overrides) {
  const storage = {};
  const calls = { start: 0, stop: 0, onChange: 0, offChange: 0, modals: [] };
  const wx = Object.assign({
    getStorageSync: (k) => (k in storage ? storage[k] : ''),
    setStorageSync: (k, v) => { storage[k] = v; },
    removeStorageSync: (k) => { delete storage[k]; },
    startLocationUpdateBackground: undefined,
    startLocationUpdate: (o) => { calls.start++; o.success && o.success(); },
    stopLocationUpdate: (o) => { calls.stop++; if (o && o.success) o.success(); if (o && o.complete) o.complete(); },
    onLocationChange: () => { calls.onChange++; },
    offLocationChange: () => { calls.offChange++; },
    setKeepScreenOn() {},
    showToast() {},
    getWindowInfo: () => ({ statusBarHeight: 20 }),
    getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    createSelectorQuery: () => ({ select: () => ({ fields: () => ({ exec: (cb) => cb([{ node: null }]) }) }) }),
  }, overrides || {});
  return { wx, storage, calls };
}

function fresh(env) {
  global.wx = env.wx;
  [CONSENT, TRACKER, ROAM].forEach((m) => { delete require.cache[m]; });
  return { consent: require(CONSENT), tracker: require(TRACKER) };
}

test('撤回:先本机停止所有持有者并落标记,再写 REVOKE;留痕失败本机仍已停', async () => {
  const env = makeWx();
  const { consent, tracker } = fresh(env);
  tracker.acquire('roam', {});
  tracker.acquire('play:1', {});
  assert.equal(tracker.isRunning(), true);
  let notified = 0;
  consent.subscribe(() => { notified++; });
  const records = [];
  await assert.rejects(consent.revoke({
    tracker,
    recordConsent: (p) => { records.push(p); return Promise.reject(new Error('offline')); },
  }), (err) => err.stop && err.stop.ok === true);
  assert.equal(tracker.isRunning(), false, '另一定位消费者(play)也必须被停');
  assert.equal(consent.isRevoked(), true);
  assert.equal(notified, 1);
  assert.deepEqual(records.map((r) => r.eventType), ['REVOKE']);
  assert.match(consent.describeStatus(tracker).text, /已撤回同意/);
});

test('系统 stop 报错时撤回结果带 ok=false,调用方必须反馈', async () => {
  const env = makeWx({ stopLocationUpdate: (o) => { o.fail && o.fail({ errMsg: 'stopLocationUpdate:fail system error' }); } });
  const { consent, tracker } = fresh(env);
  const result = await consent.revoke({ tracker, recordConsent: () => Promise.resolve() });
  assert.equal(result.stop.ok, false);
  assert.match(result.stop.errMsg, /system error/);
});

function loadRoamPage(env, recordConsent) {
  let cfg;
  global.Page = (c) => { cfg = c; };
  global.getApp = () => ({ globalData: { user_id: 9, features: {} }, isDevEnv: () => false, sendRequest() {}, recordConsent });
  const mods = fresh(env);
  require(ROAM);
  const page = Object.assign({}, cfg, { data: JSON.parse(JSON.stringify(cfg.data)) });
  page.setData = (patch, cb) => { Object.assign(page.data, patch); if (cb) cb(); };
  page._syncGoal = () => {};
  return Object.assign({ page }, mods);
}

test('撤回后 _startReal(true) 不复用旧授权:必须重新征求同意,取消则不启动', () => {
  const env = makeWx();
  const modals = [];
  env.wx.showModal = (o) => { modals.push(o); o.success && o.success({ confirm: false, cancel: true }); };
  const { page, consent } = loadRoamPage(env, () => Promise.resolve());
  consent.markRevoked();
  page._startReal(true);
  assert.equal(env.calls.start, 0, '撤回后旧授权缓存不得放行');
  assert.equal(page._realOn, undefined);
});

test('撤回后重新同意成功才清标记并启动;撤回事件让漫游页摘监听', async () => {
  const env = makeWx();
  env.wx.showModal = (o) => { o.success && o.success({ confirm: true }); };
  const records = [];
  const { page, consent, tracker } = loadRoamPage(env, (p) => { records.push(p.eventType); return Promise.resolve(); });
  page._bindRoamConsent();
  consent.markRevoked();
  page._startReal(true);
  await new Promise((r) => setImmediate(r));
  assert.deepEqual(records, ['AGREE']);
  assert.equal(consent.isRevoked(), false);
  assert.equal(page._realOn, true);

  await consent.revoke({ tracker, recordConsent: () => Promise.resolve() });
  assert.equal(page._realOn, false, '撤回订阅必须停掉漫游页实时定位');
  assert.equal(env.calls.offChange, 1);
  assert.equal(tracker.isRunning(), false);
});
