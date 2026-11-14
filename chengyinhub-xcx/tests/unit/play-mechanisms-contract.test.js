'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const A = require('../../pages/play/utils/play-accuracy.js');

const src = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8');
const wxml = fs.readFileSync(path.join(__dirname, '../../pages/play/index.wxml'), 'utf8');

function locationHandler(source, node, questArrival = false, ready = false) {
  const start = source.indexOf('that._playLocationHandler = (loc) => {');
  const end = source.indexOf('\n        wx.onLocationChange', start);
  assert.ok(start >= 0 && end > start, 'exercise the production location callback');
  const arrivals = [];
  const page = { _watching: true, _advancedReadyNodeId: ready ? node.nodeId : null,
    data: { nextNode: node, nodes: [node], nav: { active: false }, storyDistText: '' },
    _noteLocAccuracy() {}, _distText: () => '', _questArrivalCandidates: () => [],
    _questAwaitsArrival: () => questArrival, haversine: () => 0,
    arrive: (...args) => arrivals.push(args), setData(patch) { Object.assign(this.data, patch); } };
  new Function('that', 'playAccuracy', 'current', 'release', 'arrivalReading', 'ARRIVAL_RADIUS_METERS', source.slice(start, end))(page, A, () => true, () => {},
    require('../../pages/play/utils/arrival-reading.js'), require('../../pages/play/utils/arrival-radius.js').ARRIVAL_RADIUS_METERS);
  return { page, arrivals };
}

function checkArrivalAccuracy(source) {
  for (const accuracy of [0, 80, 80.1, undefined, null, '', NaN, -1, '30']) {
    for (const vm of [0, 5]) for (const advanced of [false, true]) for (const quest of [false, true]) {
      const node = { nodeId: 12, lat: 31, lng: 121, vm, hasAdvanced: advanced, done: false };
      const { page, arrivals } = locationHandler(source, node, quest);
      page._playLocationHandler({ latitude: 31, longitude: 121, accuracy });
      assert.equal(arrivals.length, A.canAutoArrive(accuracy) && (!advanced || quest) ? 1 : 0);
      assert.equal(page._loc.accuracy, accuracy, 'carry the raw measured accuracy into arrival');
    }
    for (const vm of [1, 2, 3, 4, 8, 9, 10]) for (const ready of [false, true]) {
      const node = { nodeId: 12, lat: 31, lng: 121, vm, hasAdvanced: true, done: false };
      const { page, arrivals } = locationHandler(source, node, false, ready);
      page._playLocationHandler({ latitude: 31, longitude: 121, accuracy });
      assert.equal(arrivals.length, A.canAutoArrive(accuracy) && vm >= 8 && ready ? 1 : 0);
    }
  }
}

function checkAudioRegistration(source) {
  const start = source.indexOf('  _preloadNextStationAudio(node) {');
  const end = source.indexOf('  _stopThemeBgm()', start);
  assert.ok(start >= 0 && end > start);
  const preload = source.slice(start, end);
  const audible = source.slice(0, start) + source.slice(end);
  assert.equal((audible.match(/wx\.createInnerAudioContext\(\)/g) || []).length, 6);
  assert.equal((audible.match(/audioSession\.register\(wx\.createInnerAudioContext\(\)/g) || []).length, 6);
  assert.equal((preload.match(/wx\.createInnerAudioContext\(\)/g) || []).length, 1);
  assert.doesNotMatch(preload, /audioSession\.register/);
  const contexts = [], timers = [];
  const wx = { getNetworkType: o => o.success({ networkType: 'wifi' }), createInnerAudioContext() {
    const ctx = { volume: 1, plays: 0, destroyed: false, play() { this.plays++; }, destroy() { this.destroyed = true; },
      onCanplay(fn) { this.canplay = fn; }, onError(fn) { this.error = fn; } };
    contexts.push(ctx); return ctx;
  } };
  const run = new Function('wx', 'secureOwnMedia', 'setTimeout', 'return ({' + preload + '})._preloadNextStationAudio;')(
    wx, url => url, (fn, ms) => timers.push({ fn, ms }));
  run.call({ data: { nodes: [{ nodeId: 1 }, { nodeId: 2, audio: 'next.mp3' }] } }, { nodeId: 1 });
  assert.equal(contexts.length, 1);
  assert.equal(contexts[0].volume, 0);
  assert.equal(contexts[0].plays, 0, 'preload must never start an audible channel');
  assert.equal(contexts[0].src, 'next.mp3');
  assert.equal(timers.length, 1); assert.equal(timers[0].ms, 20000);
  for (const finish of [contexts[0].canplay, contexts[0].error, timers[0].fn]) {
    contexts[0].destroyed = false; finish(); assert.equal(contexts[0].destroyed, true);
  }
}

test('精度阈值 80m:>80 不足,=80/缺失/非法放行', () => {
  assert.equal(A.MAX_ACCURACY_M, 80);
  assert.equal(A.isLowAccuracy(80.1), true);
  assert.equal(A.isLowAccuracy(80), false);
  for (const v of [undefined, null, '', NaN, -1, '90']) assert.equal(A.isLowAccuracy(v), false);
  assert.equal(A.accuracyOf({ accuracy: 12 }), 12);
  assert.equal(A.accuracyOf({}), undefined);
  assert.equal(A.accuracyOf(null), undefined);
  assert.equal(JSON.stringify({ a: A.accuracyOf({}) }), '{}');   // 缺失时整字段省略
  assert.equal(A.isLowAccuracyMsg('抱歉，定位精度不足(120m)'), true);
  assert.equal(A.isLowAccuracyMsg('还没到这一站附近'), false);
  assert.equal(A.LOW_ACCURACY_HINT, '定位精度不足 · 可扫码完成');
});

test('O-01 删空精度放行:自动到达只在精度已知且 ≤80m 时发(缺失/非法后端必拒 ARRIVE_ACCURACY_MISSING/INVALID)', () => {
  assert.equal(A.canAutoArrive(0), true);
  assert.equal(A.canAutoArrive(80), true);
  assert.equal(A.canAutoArrive(80.1), false);
  for (const v of [undefined, null, '', NaN, -1, '30']) assert.equal(A.canAutoArrive(v), false, String(v));
  checkArrivalAccuracy(src);
  assert.throws(() => checkArrivalAccuracy(src.replace(' && playAccuracy.canAutoArrive(loc.accuracy)', '')),
    'removing the accuracy guard must fail this behavior check');
  // 定位来源(含开局 300m 闸的开局定位)都把 accuracy 原样带进 _loc,arrive 请求从 _loc 取
  const locWrites = src.match(/_loc = arrivalReading\.stamp\(\{ longitude: loc\.longitude, latitude: loc\.latitude, accuracy: loc\.accuracy \}\)/g) || [];
  assert.equal(locWrites.length, 3);
});

test('游玩页:6 路可播放音轨全部登记,唯一静音预热不播放且有界销毁', () => {
  checkAudioRegistration(src);
  assert.match(src, /pauseAudio\(\) \{\s*[^}]*audioSession\.pauseAll\(\)/);
});

test('音频负控:额外裸播放器、预热播放或非零音量都必须判红', () => {
  assert.throws(() => checkAudioRegistration(src + '\nwx.createInnerAudioContext();'));
  assert.throws(() => checkAudioRegistration(src.replace('ctx.volume = 0;', 'ctx.volume = 0; ctx.play();')));
  assert.throws(() => checkAudioRegistration(src.replace('ctx.volume = 0;', 'ctx.volume = 0.1;')));
});

test('游玩页:onHide/onUnload 释放亮屏;onHide 暂停仍停轮询', () => {
  const hide = src.slice(src.indexOf('  onHide() {'), src.indexOf('_clearTransientUiTimers() {'));
  assert.match(hide, /audioSession\.releaseAwake\(\)/);
  assert.match(hide, /_stopLeadPoll\(\)/);
  const unload = src.slice(src.indexOf('  onUnload() {'), src.indexOf('  onHide() {'));
  assert.match(unload, /audioSession\.releaseAwake\(\)/);
});

test('游玩页:带领轮询走 poller,不再有 setInterval(…4000)', () => {
  assert.doesNotMatch(src, /_leadTimer/);
  assert.match(src, /createPoller\(\{ run: \(\) => this\.loadTeamProgress\(\), interval: 4000, maxInterval: 10000 \}\)/);
  assert.match(src, /_startLeadPoll\(\) \{/);
  assert.match(src, /_stopLeadPoll\(\) \{/);
});

test('游玩页:arrive 带 accuracy;群聊 tabOf/长按不弹窗', () => {
  assert.match(src, /accuracy: playAccuracy\.accuracyOf\(loc\)/);
  const hold = src.slice(src.indexOf('onRunHoldStart() {'), src.indexOf('_finishRun() {'));
  assert.doesNotMatch(hold, /cyModal|showModal/);
  assert.match(wxml, /还有 ' \+ runHoldLeft \+ ' 站未完成/);
});
