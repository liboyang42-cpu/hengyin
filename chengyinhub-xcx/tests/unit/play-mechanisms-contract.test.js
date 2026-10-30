'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const A = require('../../pages/play/utils/play-accuracy.js');

const src = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8');
const wxml = fs.readFileSync(path.join(__dirname, '../../pages/play/index.wxml'), 'utf8');

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
  // 自动到达的站:0/5 恒定;8/9/10 只在本站玩法已通过后(_advancedReadyNodeId)—— 精度闸照旧不能少
  // 0/5 站配了玩法(hasAdvanced)也要先过玩法再自动点亮(2026-10-30 复核:连线被 GPS 到达跳过)
  assert.match(src, /const arrivesHere = \(\(node\.vm === 0 \|\| node\.vm === 5\) && \(!node\.hasAdvanced \|\| that\._advancedReadyNodeId === node\.nodeId\)\)\s*\|\| \(\[8, 9, 10\]\.includes\(node\.vm\) && that\._advancedReadyNodeId === node\.nodeId\);/);
  assert.match(src, /arrivesHere && playAccuracy\.canAutoArrive\(loc\.accuracy\)\) that\.arrive\(node\.nodeId, 'gps'\)/);
  // 两个定位来源都把 accuracy 原样带进 _loc,arrive 请求从 _loc 取
  const locWrites = src.match(/_loc = \{ longitude: loc\.longitude, latitude: loc\.latitude, accuracy: loc\.accuracy \}/g) || [];
  assert.equal(locWrites.length, 2);
});

test('游玩页:5 处 InnerAudioContext(含主题背景音乐)全部经 audioSession.register', () => {
  const creates = src.match(/wx\.createInnerAudioContext\(\)/g) || [];
  const wrapped = src.match(/audioSession\.register\(wx\.createInnerAudioContext\(\)/g) || [];
  assert.equal(creates.length, 5);
  assert.equal(wrapped.length, 5);
  assert.match(src, /pauseAudio\(\) \{\s*[^}]*audioSession\.pauseAll\(\)/);
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
