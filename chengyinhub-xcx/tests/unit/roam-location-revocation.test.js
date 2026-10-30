'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const BG = '../../utils/location/bg-tracker.js';
const ROAM = '../../pages/roam/index.js';
const PRIVACY = '../../pages/privacy/index.js';
const SHEET = '../../pages/shezhi/components/privacy-sheet/index.js';
const settle = () => new Promise(resolve => setImmediate(resolve));

function harness(recordBehavior) {
  const h = { locations: [], starts: [], listeners: new Set(), records: [], requests: [], modals: [], stops: 0 };
  const app = { globalData: { user_id: 9, features: {} }, getUserID: () => 9, isDevEnv: () => false,
    sendRequest(options) { h.requests.push(options); },
    recordConsent(payload) { h.records.push(payload); return recordBehavior ? recordBehavior(payload) : Promise.resolve(); } };
  global.getApp = () => app;
  global.wx = {
    getStorageSync: () => '', setStorageSync() {}, showToast() {},
    getWindowInfo: () => ({ statusBarHeight: 20 }), getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    getLocation(o) { h.locations.push(o); }, startLocationUpdateBackground(o) { h.starts.push(o); },
    stopLocationUpdate(o) { h.stops++; if (o && o.success) o.success(); },
    onLocationChange(fn) { h.listeners.add(fn); }, offLocationChange(fn) { h.listeners.delete(fn); },
    setKeepScreenOn() {}, showModal(o) { h.modals.push(o); },
  };
  for (const file of [BG, ROAM, PRIVACY, SHEET]) delete require.cache[require.resolve(file)];
  let definition;
  global.Page = value => { definition = value; };
  require(ROAM);
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done(); } });
  page._syncGoal = () => {};
  page._track = []; page._reveals = []; page._pendingTiles = [];
  h.page = page; h.tracker = require(BG);
  require(PRIVACY);
  h.privacy = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)), setData: page.setData });
  global.Component = value => { definition = value; };
  require(SHEET);
  h.sheet = Object.assign({}, definition.methods, { data: JSON.parse(JSON.stringify(definition.data)), setData: page.setData });
  return h;
}

for (const entry of ['privacy', 'sheet']) {
  test(`${entry}真实撤回入口清tracker和roam监听，旧坐标不能写轨迹或请求`, async () => {
    const h = harness(); h.page._startReal(true); h.starts[0].success();
    const oldListener = h.page._onRealLoc;
    assert.equal(h.page._realOn, true); assert.equal(h.listeners.size, 1);
    h[entry].withdrawRoamLocationConsent(); await settle();
    assert.equal(h.records.at(-1).eventType, 'REVOKE');
    assert.equal(h.tracker.isRunning(), false); assert.equal(h.page._realOn, false);
    assert.equal(h.page._onRealLoc, null); assert.equal(h.page._realStarting, false);
    assert.equal(h.listeners.size, 0); assert.equal(h.stops, 1);
    h.page.data.screen = 'map';
    assert.equal(h.page._blocked(), false, '旧回包负控必须处于会采集坐标的真实地图态');
    oldListener({ latitude: 31, longitude: 121 });
    assert.equal(h.page._player, undefined); assert.equal(h.page._track.length, 0);
    assert.equal(h.requests.length, 0, '旧定位不能触发揭雾／位置上传');
    h.page._startReal(); h.modals.at(-1).success({ confirm: true }); await settle();
    assert.equal(h.records.at(-1).eventType, 'AGREE'); assert.equal(h.starts.length, 2);
    h.starts[1].success(); assert.equal(h.page._realOn, true); assert.equal(h.listeners.size, 1);
    h.page._stopReal();
  });
}

test('撤回审计失败仍停止本机追踪并保留补传错误，不假报已记录', async () => {
  let failed = true;
  const h = harness(() => failed ? Promise.reject(new Error('offline')) : Promise.resolve());
  h.page._startReal(true); h.starts[0].success();
  h.privacy.withdrawRoamLocationConsent(); await settle();
  assert.equal(h.tracker.isRunning(), false); assert.equal(h.page._realOn, false);
  assert.equal(h.stops, 1); assert.equal(h.privacy.data.failedAction, 'revoke');
  assert.match(h.privacy.data.submitError, /本机.*已停/);
  failed = false; h.privacy.retrySubmit(); await settle();
  assert.equal(h.tracker.isRunning(), false); assert.equal(h.privacy.data.submitError, '');
});

test('弹窗AGREE记录在途时撤回，旧promise完成不能重新acquire', async () => {
  let agree;
  const h = harness(p => p.eventType === 'AGREE' ? new Promise(resolve => { agree = resolve; }) : Promise.resolve());
  h.page._startReal(); h.modals[0].success({ confirm: true });
  assert.equal(h.page._realStarting, true);
  h.sheet.withdrawRoamLocationConsent(); await settle();
  agree(); await settle();
  assert.equal(h.starts.length, 0); assert.equal(h.page._realOn, false);
  assert.equal(h.page._realStarting, false); assert.equal(h.requests.length, 0);
});

test('撤回前弹窗的迟到确认不能记录AGREE或重新启动定位', async () => {
  const h = harness(); h.page._startReal();
  const previousModal = h.modals[0];
  h.privacy.withdrawRoamLocationConsent(); await settle();
  previousModal.success({ confirm: true }); await settle();
  assert.equal(h.records.filter(p => p.eventType === 'AGREE').length, 0);
  assert.equal(h.starts.length, 0); assert.equal(h.page._realStarting, false);
  assert.equal(h.requests.length, 0);
});

test('系统start在途时真实撤回入口失效旧成功，之后新明确AGREE实际重启', async () => {
  const h = harness(); h.page._startReal(true);
  h.sheet.withdrawRoamLocationConsent(); await settle();
  h.starts[0].success();
  assert.equal(h.tracker.isRunning(), false); assert.equal(h.page._realOn, false);
  assert.equal(h.listeners.size, 0); assert.equal(h.requests.length, 0);
  h.page._startReal(); h.modals.at(-1).success({ confirm: true }); await settle();
  assert.equal(h.starts.length, 2); h.starts[1].success();
  assert.equal(h.page._realOn, true); assert.equal(h.listeners.size, 1);
  h.page._stopReal();
});

test('真实GO入口旧getLocation回包在撤回后不能开图、AGREE或后台上传', async () => {
  const h = harness(); let maps = 0; h.page._openRoamMap = () => { maps++; };
  h.page.goStart(); h.modals[0].success({ confirm: true }); await settle(); assert.equal(h.locations.length, 1);
  h.privacy.withdrawRoamLocationConsent(); await settle();
  h.locations[0].success({ latitude: 31, longitude: 121 }); h.locations[0].complete();
  assert.equal(maps, 0); assert.equal(h.starts.length, 0); assert.equal(h.requests.length, 0);
  assert.equal(h.records.filter(p => p.eventType === 'AGREE').length, 1, '只有撤回前明确确认的那一条');
  assert.equal(h.page._roamStartPending, false);
});

test('真实GO明确AGREE在途撤回后迟到保存不能读定位；新明确同意才能恢复', async () => {
  let oldAgree;
  const h = harness(p => p.eventType === 'AGREE' ? new Promise(resolve => { oldAgree = resolve; }) : Promise.resolve());
  let maps = 0; h.page._openRoamMap = () => { maps++; };
  h.page.goStart(); h.modals[0].success({ confirm: true }); await settle();
  assert.equal(h.records.filter(p => p.eventType === 'AGREE').length, 1);
  assert.equal(h.locations.length, 0, '真实GO尚在等待同意保存，不得先开图');
  h.sheet.withdrawRoamLocationConsent(); await settle(); oldAgree(); await settle();
  assert.equal(h.locations.length, 0); assert.equal(h.starts.length, 0); assert.equal(maps, 0);
  assert.equal(h.page._roamStartPending, false);
  h.page.goStart(); h.modals.at(-1).success({ confirm: true }); await settle(); oldAgree(); await settle();
  assert.equal(h.records.filter(p => p.eventType === 'AGREE').length, 2);
  assert.equal(h.locations.length, 1);
  h.locations[0].success({ latitude: 31, longitude: 121 }); assert.equal(maps, 1);
});

test('真实GO开图timer的setupFog在途回包不能在撤回后点亮新格或启动定位', async () => {
  const h = harness(); const page = h.page; let finishFog; let setupCalls = 0;
  page._disposeFogRenderer = () => { page._fogLifecycleGen = (page._fogLifecycleGen || 0) + 1; };
  for (const method of ['_initWorld', '_syncPlayHeader', '_scheduleFootprintHint', '_mapLoadWatch']) page[method] = () => {};
  page._buildOpeningEmojis = () => [];
  page.selectComponent = () => ({ getMapContext: () => ({}) });
  page._setupFog = () => { setupCalls++; return new Promise(resolve => { finishFog = resolve; }); };
  const timeout = global.setTimeout; const timers = [];
  global.setTimeout = fn => { timers.push(fn); return timers.length; };
  try {
    page.goStart(); h.modals[0].success({ confirm: true }); await settle();
    h.locations[0].success({ latitude: 31, longitude: 121 });
    timers.at(-1)();
  } finally { global.setTimeout = timeout; }
  assert.equal(setupCalls, 1, '必须进入真实开图timer与异步setup等待点');
  const pending = page._fogStartPending;
  h.privacy.withdrawRoamLocationConsent(); await settle();
  page._fogMode = 'screen'; finishFog(true); await settle();
  assert.equal(page._fogStartPending, pending);
  assert.equal(pending.startTracking, false); assert.equal(pending.revealAdded, undefined);
  assert.equal(page._reveals.length, 0, '旧setup成功不能调用真实_addReveal采集新格');
  assert.equal(h.starts.length, 0); assert.equal(h.requests.length, 0);
});

test('play保留依据实际本次游玩明示用途和gpsOk，不借roam_location许可', () => {
  const source = fs.readFileSync(path.resolve(__dirname, '../../pages/play/index.js'), 'utf8');
  assert.match(source, /gpsOk: false/);
  assert.match(source, /requestPlayLocation\(\)[\s\S]*?cyModal\.show\([\s\S]*?if \(!result\.confirm\)[\s\S]*?wx\.getLocation\([\s\S]*?gpsOk: true[\s\S]*?that\.watchLocation\(\)/);
  assert.doesNotMatch(source, /roam_location/);
  assert.match(source, /this\.data\.gpsOk\) this\.watchLocation\(\)/);
});

test('撤回后再次点击真实GO必须先明确确认，旧系统定位授权不等于新同意', async () => {
  const h = harness(); let maps = 0; h.page._openRoamMap = () => { maps++; };
  h.privacy.withdrawRoamLocationConsent(); await settle();
  h.page.goStart();
  assert.equal(h.locations.length, 0, '不得在明确确认前调用系统定位');
  assert.equal(h.records.filter(p => p.eventType === 'AGREE').length, 0);
  assert.equal(h.modals.length, 1);
  h.modals[0].success({ confirm: false }); await settle();
  assert.equal(h.locations.length, 0); assert.equal(h.starts.length, 0); assert.equal(maps, 0);
  assert.equal(h.page._roamStartPending, false);
  h.page.goStart(); h.modals[1].success({ confirm: true }); await settle();
  assert.equal(h.records.filter(p => p.eventType === 'AGREE').length, 1);
  assert.equal(h.locations.length, 1);
  h.locations[0].success({ latitude: 31, longitude: 121 }); h.locations[0].complete();
  assert.equal(maps, 1);
});

for (const reason of ['offline', '同意记录超时']) {
  test(`真实GO保存同意${reason}仍零定位零启动并可重试`, async () => {
    let failing = true;
    const h = harness(() => failing ? Promise.reject(new Error(reason)) : Promise.resolve());
    h.page.goStart(); assert.equal(h.locations.length, 0);
    h.modals[0].success({ confirm: true }); await settle();
    assert.equal(h.locations.length, 0); assert.equal(h.starts.length, 0); assert.equal(h.requests.length, 0);
    assert.equal(h.page._roamStartPending, false); assert(h.page.data.introError);
    failing = false; h.page.goStart(); h.modals[1].success({ confirm: true }); await settle();
    assert.equal(h.locations.length, 1);
  });
}

for (const changed of ['hide', 'unload', 'uid', 'token']) {
  test(`真实GO同意保存途中${changed}变化后迟到成功不能定位或启动`, async () => {
    let complete;
    const h = harness(() => new Promise(resolve => { complete = resolve; }));
    h.page.goStart(); assert.equal(h.locations.length, 0);
    h.modals[0].success({ confirm: true }); await settle();
    if (changed === 'hide') h.page.onHide();
    if (changed === 'unload') h.page.onUnload();
    if (changed === 'uid') global.getApp().getUserID = () => 10;
    if (changed === 'token') global.getApp().globalData.authorization = 'replacement-fixture-token';
    complete(); await settle();
    assert.equal(h.locations.length, 0); assert.equal(h.starts.length, 0);
    assert.equal(!!h.page._realOn, false);
    assert.equal(h.page._roamStartPending, false, '当前在途确认已失效，仍要释放GO重试');
  });
}

for (const changed of ['uid', 'token']) {
  test(`真实GO定位已在途${changed}变化后旧坐标拒绝开图并释放当前门闩`, async () => {
    const h = harness(); let maps = 0; h.page._openRoamMap = () => { maps++; };
    h.page.goStart(); h.modals[0].success({ confirm: true }); await settle();
    assert.equal(h.locations.length, 1); assert.equal(h.page._roamStartPending, true);
    if (changed === 'uid') global.getApp().getUserID = () => 10;
    if (changed === 'token') global.getApp().globalData.authorization = 'replacement-fixture-token';
    h.locations[0].success({ latitude: 31, longitude: 121 }); h.locations[0].complete();
    assert.equal(maps, 0); assert.equal(h.starts.length, 0);
    assert.equal(h.page._roamStartPending, false);
    h.page.goStart(); assert.equal(h.modals.length, 2, '新账号当前流程仍可重新明确确认');
  });
}
