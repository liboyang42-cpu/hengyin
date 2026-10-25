'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const vm = require('node:vm');
const pageFile = path.resolve(__dirname, '../../pages/roam/index.js');
const trackerFile = require.resolve('../../utils/location/bg-tracker.js');
const source = fs.readFileSync(pageFile, 'utf8');
const settle = () => new Promise(resolve => setImmediate(resolve));

function harness(transform = value => value) {
  const h = { background: [], foreground: [], listeners: new Set(), modals: [], consent: [], stops: 0, toasts: [] };
  h.owner = '9';
  const app = { globalData: { features: {} }, getUserID: () => h.owner, isDevEnv: () => false,
    sendRequest() {}, recordConsent(value) { h.consent.push(value); return Promise.resolve(); } };
  global.getApp = () => app;
  global.wx = {
    getStorageSync: () => '', setStorageSync() {},
    getWindowInfo: () => ({ statusBarHeight: 20 }), getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    startLocationUpdateBackground(o) { h.background.push(o); },
    startLocationUpdate(o) { h.foreground.push(o); },
    stopLocationUpdate(o) { h.stops++; o.complete(); },
    onLocationChange(fn) { h.listeners.add(fn); }, offLocationChange(fn) { h.listeners.delete(fn); },
    setKeepScreenOn() {}, showToast(o) { h.toasts.push(o); }, showModal(o) { h.modals.push(o); },
  };
  delete require.cache[trackerFile];
  let definition;
  global.Page = value => { definition = value; };
  const loaded = new Module(pageFile, module);
  loaded.filename = pageFile;
  loaded.paths = Module._nodeModulePaths(path.dirname(pageFile));
  loaded._compile(transform(source), pageFile);
  h.page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done(); } });
  h.page._syncGoal = () => {};
  h.page.data.screen = 'map';
  h.tracker = require(trackerFile);
  return h;
}

function degrade(h) {
  h.page._startReal(true);
  h.background.at(-1).fail({ errMsg: 'background unavailable' });
  assert.equal(h.page.data.locationForegroundOnly, false, '前台尚未成功时不宣称已降级记录');
  h.foreground.at(-1).success();
  assert.equal(h.page.data.locationForegroundOnly, true);
  assert.equal(h.page._realOn, true);
  assert.equal(h.page.data.locErr, false);
  assert.equal(h.listeners.size, 1);
}

test('后台失败再前台成功：真实tracker回调驱动可见降级态，不更改权限字段', () => {
  const h = harness();
  const permissions = [h.page.data.gpsOk, h.page.data.bgGpsOk];
  degrade(h);
  assert.equal(h.tracker.isBackground(), false);
  assert.deepEqual([h.page.data.gpsOk, h.page.data.bgGpsOk], permissions);
  assert.equal(h.consent.length, 0, '已有同意路径不重复记录');
  h.page._stopReal();
});

test('后台成功不显示提示，停止后重新后台成功也清除旧降级态', () => {
  const h = harness(); degrade(h); h.page._stopReal();
  assert.equal(h.page.data.locationForegroundOnly, false);
  h.page._startReal(true); h.background.at(-1).success();
  assert.equal(h.page.data.locationForegroundOnly, false);
  assert.equal(h.tracker.isBackground(), true);
  assert.equal(h.foreground.length, 1);
  h.page._stopReal();
});

test('系统缺后台API时前台成功同样显示实际降级能力', () => {
  const h = harness(); delete wx.startLocationUpdateBackground;
  h.page._startReal(true);
  assert.equal(h.background.length, 0);
  assert.equal(h.page.data.locationForegroundOnly, false);
  h.foreground[0].success();
  assert.equal(h.page.data.locationForegroundOnly, true);
  h.page._stopReal();
});

test('后台与前台全失败：保留原错误态，释放忙状态且不显示降级', () => {
  const h = harness(); h.page._startReal(true);
  h.background[0].fail({}); h.foreground[0].fail({});
  assert.equal(h.page.data.locationForegroundOnly, false);
  assert.equal(h.page.data.locErr, true);
  assert.equal(h.page._realStarting, false);
  assert.equal(!!h.page._realOn, false);
  assert.equal(h.listeners.size, 0);
  h.page._startReal(true);
  assert.equal(h.background.length, 2, '失败后可重新启动');
  h.page._stopReal();
});

test('停止清提示、监听及持有者；重复停止幂等', () => {
  const h = harness(); degrade(h);
  h.page._stopReal(); h.page._stopReal();
  assert.equal(h.page.data.locationForegroundOnly, false);
  assert.equal(h.page._realOn, false);
  assert.equal(h.page._realStarting, false);
  assert.equal(h.listeners.size, 0);
  assert.equal(h.tracker.hasOwner('roam'), false);
  assert.equal(h.stops, 1);
});

test('旧前台成功迟到不能覆盖新一代后台成功', () => {
  const h = harness(); h.page._startReal(true); h.background[0].fail({});
  const oldForeground = h.foreground[0];
  h.page._stopReal(); h.page._startReal(true); h.background[1].success();
  oldForeground.success();
  assert.equal(h.page.data.locationForegroundOnly, false);
  assert.equal(h.listeners.size, 1);
  h.page._stopReal();
});

test('旧后台成功迟到不能清除新一代前台降级提示', () => {
  const h = harness(); h.page._startReal(true);
  const oldBackground = h.background[0]; h.page._stopReal();
  degrade(h); oldBackground.success();
  assert.equal(h.page.data.locationForegroundOnly, true);
  assert.equal(h.listeners.size, 1);
  h.page._stopReal();
});

test('忙状态重复启动不清除正在记录的提示，也不重复启动或注册', () => {
  const h = harness(); h.page._startReal(true); h.page._startReal(true);
  assert.equal(h.background.length, 1);
  h.background[0].fail({}); h.page._startReal(true);
  assert.equal(h.foreground.length, 1);
  h.foreground[0].success(); h.page._startReal(true);
  assert.equal(h.page.data.locationForegroundOnly, true);
  assert.equal(h.background.length, 1);
  assert.equal(h.listeners.size, 1);
  h.page._stopReal();
});

test('真实onShow切号路径清除降级与旧监听，旧回调不恢复提示', () => {
  const h = harness(); degrade(h);
  const oldForeground = h.foreground[0];
  h.page._roamMemory = { playerId: '9' }; h.owner = '10';
  for (const name of ['_syncRoamPermission', '_syncPresencePrivacy', '_syncCommunityEntry', '_stopClock',
    '_resetRoamRecoverySession', '_loadLocalHistory', '_restoreRoamRecovery', '_loadIntroPassport']) h.page[name] = () => {};
  h.page.onShow(); oldForeground.success();
  assert.equal(h.page.data.locationForegroundOnly, false);
  assert.equal(h.page._realOn, false);
  assert.equal(h.listeners.size, 0);
});

test('启动在途切号：旧前台成功不能设置降级态或挂监听', () => {
  const h = harness(); h.page._startReal(true); h.background[0].fail({});
  h.owner = '10'; h.foreground[0].success();
  assert.equal(h.page.data.locationForegroundOnly, false);
  assert.equal(h.listeners.size, 0);
  h.page._stopReal();
});

test('真实onUnload清除降级，销毁后旧回调和新启动均不重新显示', () => {
  const h = harness(); degrade(h);
  for (const name of ['_unbindRoamNetwork', '_cancelShareWork', '_stopClock', '_clearRollTimers',
    '_disposeFogRenderer', '_flushReveal', '_persistTiles']) h.page[name] = () => {};
  h.page.onUnload(); h.foreground[0].success(); h.page._startReal(true);
  assert.equal(h.page.data.locationForegroundOnly, false);
  assert.equal(h.page._realOn, false);
  assert.equal(h.listeners.size, 0);
  assert.equal(h.background.length, 1);
});

test('前台启动在途销毁后迟到成功不能显示提示或监听', () => {
  const h = harness(); h.page._startReal(true); h.background[0].fail({});
  for (const name of ['_unbindRoamNetwork', '_cancelShareWork', '_stopClock', '_clearRollTimers',
    '_disposeFogRenderer', '_flushReveal', '_persistTiles']) h.page[name] = () => {};
  h.page.onUnload(); h.foreground[0].success();
  assert.equal(h.page.data.locationForegroundOnly, false);
  assert.equal(h.page._realStarting, false);
  assert.equal(!!h.page._realOn, false);
  assert.equal(h.listeners.size, 0);
});

test('地图忙态不抹去提示；onHide仍保留原采集与时钟语义', () => {
  const h = harness(); degrade(h); h.page.data.mapLoad = 'slow';
  let pauses = 0;
  h.page._sessionClock = { isPaused: () => false, pause() { pauses++; } };
  for (const name of ['_saveRoamRecovery', '_flushReveal', '_persistTiles']) h.page[name] = () => {};
  h.page.onHide();
  assert.equal(h.page.data.locationForegroundOnly, true);
  assert.equal(h.page._realOn, true);
  assert.equal(h.listeners.size, 1);
  assert.equal(pauses, 0);
  h.page._stopReal();
});

test('撤回仍经既有tracker协议停止并清提示', () => {
  const h = harness(); degrade(h); h.tracker.revoke('roam');
  assert.equal(h.page.data.locationForegroundOnly, false);
  assert.equal(h.page._realOn, false);
  assert.equal(h.listeners.size, 0);
});

test('原同意流程未旁路：取消不启动，同意记录成功后才启动', async () => {
  const h = harness(); h.page._startReal();
  assert.equal(h.background.length, 0);
  h.modals[0].success({ confirm: false });
  assert.equal(h.page._realStarting, false);
  h.page._startReal(); h.modals[1].success({ confirm: true });
  assert.equal(h.background.length, 0);
  await settle();
  assert.equal(h.consent.length, 1);
  assert.equal(h.consent[0].eventType, 'AGREE');
  assert.equal(h.background.length, 1);
  h.page._stopReal();
});

test('提示保留实际能力状态，并让地图、离线和恢复告警优先显示', () => {
  const wxml = fs.readFileSync(pageFile.replace('.js', '.wxml'), 'utf8');
  const wxss = fs.readFileSync(pageFile.replace('.js', '.wxss'), 'utf8');
  const tip = wxml.match(/class="maptip maptip--offline maptip--foreground" wx:if="\{\{([^}]+)\}\}" aria-role="status" aria-live="polite">\s*<text>仅前台记录，锁屏后可能中断<\/text>/);
  assert.ok(tip);
  const state = { locationForegroundOnly: true, screen: 'map', mapLoad: 'ready', roamIdle: false,
    syncOffline: false, revealWriteUnknown: false, recoveryError: false };
  const visible = patch => vm.runInNewContext(tip[1], Object.assign({}, state, patch));
  assert.equal(visible({}), true);
  for (const flag of ['roamIdle', 'syncOffline', 'revealWriteUnknown', 'recoveryError']) {
    assert.equal(visible({ [flag]: true }), false, flag);
  }
  assert.equal(visible({ mapLoad: 'slow' }), false);
  assert.equal(visible({ screen: 'entry-map' }), false);
  assert.equal(visible({ locationForegroundOnly: false }), false);
  assert.match(wxss, /\.maptip--foreground\s*\{\s*white-space:normal;/);
});

test('回调反例：忽略实际能力时，同一消费者断言确实失败', () => {
  const h = harness(value => value.replace('locationForegroundOnly: usingBackground === false', 'locationForegroundOnly: false'));
  assert.throws(() => degrade(h), assert.AssertionError);
  h.page._stopReal();
});

test('清理反例：删除停止清理时，同一状态断言确实失败', () => {
  const h = harness(value => value.replace(
    'this._realStarting = false;\n    this.setData({ locationForegroundOnly: false });',
    'this._realStarting = false;'));
  degrade(h); h.page._stopReal();
  assert.throws(() => assert.equal(h.page.data.locationForegroundOnly, false), assert.AssertionError);
});
