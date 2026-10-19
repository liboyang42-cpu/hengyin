'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const flush = () => new Promise(resolve => setImmediate(resolve));

function harness(options = {}) {
  const calls = { starts: [], stops: 0, off: [], listeners: [], toasts: [], ticks: 0 };
  global.getApp = () => ({
    globalData: { user_id: 9, features: {} }, isDevEnv: () => false, sendRequest() {},
    recordConsent: input => input.eventType === 'AGREE' && options.agree ? options.agree : Promise.resolve(),
  });
  global.wx = {
    getStorageSync: () => '', setStorageSync() {},
    getWindowInfo: () => ({ statusBarHeight: 20 }), getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    startLocationUpdateBackground(o) { calls.starts.push(o); if (!options.deferStart) o.success(); },
    stopLocationUpdate(o) { calls.stops++; if (o) { if (options.stopFail) o.fail({ errMsg: 'stopLocationUpdate:fail' }); else o.success(); } },
    onLocationChange(fn) { calls.listeners.push(fn); }, offLocationChange(fn) { calls.off.push(fn); },
    setKeepScreenOn() {}, showToast(o) { calls.toasts.push(o.title); }, showModal(o) { o.success({ confirm: true }); },
  };
  let definition;
  global.Page = p => { definition = p; };
  delete require.cache[require.resolve('../../utils/location/bg-tracker.js')];
  function page(module) {
    delete require.cache[require.resolve(module)]; require(module);
    const result = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) });
    result.setData = function (patch, cb) { Object.assign(this.data, patch); if (cb) cb(); };
    return result;
  }
  const roam = page('../../pages/roam/index.js');
  roam._syncGoal = () => {}; roam._realTick = () => { calls.ticks++; };
  const privacy = page('../../pages/privacy/index.js');
  const tracker = require('../../utils/location/bg-tracker.js');
  return { calls, roam, privacy, tracker };
}

test('撤回经过共享持有者释放，并解绑漫游监听和拒绝已排队位置回调', async () => {
  const h = harness(); h.roam._startReal(true); const old = h.calls.listeners[0];
  h.privacy.withdrawRoamLocationConsent(); await flush();
  assert.equal(h.tracker.isRunning(), false);
  assert.equal(h.roam._realOn, false);
  assert.ok(h.calls.off.includes(old));
  old({ latitude: 1, longitude: 2 }); assert.equal(h.calls.ticks, 0);
  assert.equal(h.privacy.data.submitError, '');
});

test('撤回等待旧启动完成并补偿停止，迟到成功不能注册漫游监听', async () => {
  const h = harness({ deferStart: true }); h.roam._startReal(true);
  h.privacy.withdrawRoamLocationConsent(); await flush();
  assert.equal(h.privacy.data.submitting, true, '启动尚未收口时不能宣称已停止');
  h.calls.starts[0].success(); await flush();
  assert.equal(h.calls.listeners.length, 0);
  assert.equal(h.tracker.isRunning(), false);
  assert.equal(h.calls.stops, 1);
  assert.equal(h.privacy.data.submitting, false);
});

test('撤回只取消roam，play持有者及共享定位继续工作', async () => {
  const h = harness(); h.roam._startReal(true);
  h.tracker.acquire('play:7', {});
  h.privacy.withdrawRoamLocationConsent(); await flush();
  assert.equal(h.calls.stops, 0);
  assert.equal(h.tracker.isRunning(), true);
  assert.equal(h.roam._realOn, false);
});

test('native停止失败保留停止错误，不弹撤回成功，重试可恢复', async () => {
  const options = { stopFail: true }; const h = harness(options); h.roam._startReal(true);
  h.privacy.withdrawRoamLocationConsent(); await flush();
  assert.match(h.privacy.data.submitError, /停止/);
  assert.equal(h.calls.toasts.includes('已撤回漫游定位同意'), false);
  options.stopFail = false; h.privacy.retrySubmit(); await flush();
  assert.equal(h.privacy.data.submitError, '');
  assert.equal(h.calls.stops, 2);
});

test('撤回前AGREE迟到不得重新acquire漫游', async () => {
  let resolveAgree; const h = harness({ agree: new Promise(resolve => { resolveAgree = resolve; }) });
  h.roam._startReal(); h.privacy.withdrawRoamLocationConsent(); await flush();
  resolveAgree(); await flush();
  assert.equal(h.calls.starts.length, 0);
});

test('撤回前迷雾就绪回调迟到，不得借旧启动意图重新定位', async () => {
  const h = harness();
  h.roam._fogLifecycleGen = 3; h.roam._fogMode = 'screen'; h.roam.data.fogScreenReady = true;
  h.roam._fogStartPending = { p: {}, startTracking: true, generation: 3, locationEpoch: h.tracker.epoch('roam') };
  h.roam._seenSweepHistory = h.roam._syncSparkCircles = h.roam._syncMarkers = h.roam._startClock = () => {};
  h.privacy.withdrawRoamLocationConsent(); await flush();
  h.roam._completeRoamAfterFogLoad(3);
  assert.equal(h.calls.starts.length, 0);
});

test('停止只有complete且无成功结果时保持未知，不能显示成功', async () => {
  const h = harness(); h.roam._startReal(true);
  global.wx.stopLocationUpdate = o => o.complete({ errMsg: 'stopLocationUpdate:fail denied' });
  h.privacy.withdrawRoamLocationConsent(); await flush();
  assert.match(h.privacy.data.submitError, /停止尚未确认/);
  assert.equal(h.calls.toasts.includes('已撤回漫游定位同意'), false);
});

test('撤回等待中的旧启动失败，不降级为前台定位且可结束撤回', async () => {
  const h = harness({ deferStart: true }); h.roam._startReal(true);
  let foreground = 0; global.wx.startLocationUpdate = () => { foreground++; };
  h.privacy.withdrawRoamLocationConsent(); await flush();
  h.calls.starts[0].fail({ errMsg: 'denied' }); await flush();
  assert.equal(foreground, 0); assert.equal(h.privacy.data.submitting, false);
  assert.equal(h.privacy.data.submitError, '');
});

test('旧定位启动始终无回包时超时显示未知，迟到回包仍补偿停止', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  const h = harness({ deferStart: true }); h.roam._startReal(true);
  h.privacy.withdrawRoamLocationConsent(); await flush();
  context.mock.timers.tick(10001); await flush();
  assert.equal(h.privacy.data.submitting, false);
  assert.match(h.privacy.data.submitError, /停止尚未确认/);
  h.calls.starts[0].success(); await flush();
  assert.equal(h.calls.stops, 1); assert.equal(h.calls.listeners.length, 0);
});

test('GO的一次定位结果在撤回后迟到，不能打开地图或恢复持续采集', async () => {
  const h = harness(); let location; let opened = 0;
  global.wx.getLocation = o => { location = o; };
  h.roam.data.introCards = [{}]; h.roam.data.introCardIdx = 0;
  h.roam.markRoamIntroSeen = () => {}; h.roam._openRoamMap = () => { opened++; };
  h.roam.goStart(); h.privacy.withdrawRoamLocationConsent(); await flush();
  location.success({ latitude: 1, longitude: 2 }); location.complete();
  assert.equal(opened, 0); assert.equal(h.roam._roamStartPending, false);
});

test('GO的登录在撤回后才完成，不能自动再发定位请求', async () => {
  const h = harness(); let finishLogin; let loggedIn = false; let locations = 0;
  const app = global.getApp(); app.getUserID = () => loggedIn ? 9 : null;
  app.firstLogin = () => new Promise(resolve => { finishLogin = resolve; });
  global.getApp = () => app; global.wx.getLocation = () => { locations++; };
  h.roam.data.introCards = [{}]; h.roam.data.introCardIdx = 0;
  h.roam.goStart(); h.privacy.withdrawRoamLocationConsent(); await flush();
  loggedIn = true; finishLogin(); await flush();
  assert.equal(locations, 0); assert.equal(h.roam._roamStartPending, false);
});

test('停止失败后的异步重试等待新结果，不被上次错误提前拒绝', async () => {
  const h = harness({ stopFail: true }); h.roam._startReal(true);
  h.privacy.withdrawRoamLocationConsent(); await flush();
  let retry; global.wx.stopLocationUpdate = o => { retry = o; };
  h.privacy.retrySubmit(); await flush();
  assert.equal(h.privacy.data.submitting, true);
  retry.success(); await flush();
  assert.equal(h.privacy.data.submitting, false); assert.equal(h.privacy.data.submitError, '');
});
