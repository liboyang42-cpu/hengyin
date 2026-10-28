// 2026-10-27 陌生人内测走查:地图点位统一(序号画进圆里)、雷达小地图框住「我 + 下一站」、
// 完成卡只留一条成就气泡、开场副标只取简介首行。
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

function fakeCtx() {
  const calls = [];
  const ctx = new Proxy({ calls }, {
    get(t, k) { if (k in t) return t[k]; return (...a) => calls.push([k, ...a]); },
    set(t, k, v) { calls.push(['set:' + String(k), v]); return true; },
  });
  return ctx;
}

test('序号画进点位圆里:已完成黑底白字带勾,未解锁灰底带锁,待去白底黑字', () => {
  const { drawNumberedMarker } = require('../../utils/play-marker-draw.js');
  const done = fakeCtx(); drawNumberedMarker(done, 108, 'completed', 7);
  assert.ok(done.calls.some((c) => c[0] === 'fillText' && c[1] === '7'));
  assert.ok(done.calls.some((c) => c[0] === 'set:fillStyle' && c[1] === '#111111'));
  const locked = fakeCtx(); drawNumberedMarker(locked, 108, 'restricted', 12);
  assert.ok(locked.calls.some((c) => c[0] === 'fillText' && c[1] === '12'));
  assert.ok(locked.calls.some((c) => c[0] === 'fillRect'), '未解锁要画小锁');
  const open = fakeCtx(); drawNumberedMarker(open, 108, 'actionable', 3);
  assert.ok(!open.calls.some((c) => c[0] === 'fillRect'), '待去不带角标');
});

function loadFreeMap() {
  let def;
  global.Component = (d) => { def = d; };
  const file = path.join(ROOT, 'components/cy/free-map/index.js');
  delete require.cache[file];
  require(file);
  const inst = Object.assign({}, def.methods, {
    data: { nodes: [], player: null, target: null, compact: false, showPhotoCallouts: false, fit: false, fitPadding: [40, 40, 40, 40] },
    setData(p) { Object.assign(this.data, p); },
  });
  return inst;
}

test('有序号且图标已生成:不再挂浮在头上的序号胶囊;成就气泡照旧挂', () => {
  const m = loadFreeMap();
  m._icons = { actionable: 'a', completed: 'c', restricted: 'r' };
  m._iconCanvas = { g: {}, S: 108, save: async () => 'x' };
  m._numIcons = { 'completed:1': 'c1', 'restricted:2': 'r2' };
  m.data.nodes = [
    { nodeId: 1, num: 1, state: 'completed', lat: 31.2, lng: 121.4, reviewLabel: '最快一段 · 49秒' },
    { nodeId: 2, num: 2, state: 'restricted', lat: 31.21, lng: 121.41 },
  ];
  m._rebuild();
  const [a, b] = m.data.markers;
  assert.strictEqual(a.iconPath, 'c1');
  assert.strictEqual(a.callout.content, '最快一段 · 49秒');
  assert.strictEqual(b.iconPath, 'r2');
  assert.strictEqual(b.callout, undefined);
});

test('canvas 不可用时退回旧的序号胶囊,序号不丢', () => {
  const m = loadFreeMap();
  m._icons = { actionable: 'a' };
  m._numIcons = {};
  m.data.nodes = [{ nodeId: 5, num: 5, state: 'actionable', lat: 31.2, lng: 121.4 }];
  m._rebuild();
  assert.strictEqual(m.data.markers[0].callout.content, '5');
});

test('雷达小地图与完成卡地图都开 fit;雷达点位缩小', () => {
  const wxml = read('pages/play/index.wxml');
  assert.match(wxml, /fx-story__radar-map[\s\S]{0,700}fit="\{\{true\}\}"/);
  assert.match(wxml, /finsheet__map-native[\s\S]{0,300}fit="\{\{true\}\}"/);
  assert.match(read('components/cy/free-map/index.js'), /this\.data\.compact \? 22 : 34/);
});

test('开场副标只取简介第一行,不以分隔符结尾', () => {
  const src = read('pages/play/index.js');
  const fn = src.match(/function introLine\(desc\) \{[\s\S]*?\n\}/)[0];
  const introLine = new Function(fn + '; return introLine;')();
  assert.strictEqual(introLine('城市异常档案局 · 外勤档案（游戏设定）\n档案编号：CUA-NJ-17「高峰」｜你的身份'), '城市异常档案局 · 外勤档案（游戏设定）');
  assert.strictEqual(introLine('一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九｜后'), '一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九');
  assert.strictEqual(introLine(''), '');
});

test('完成卡只留一条成就气泡,按 徽章 > 券 > 最快一段 > 首达', () => {
  const src = read('pages/play/index.js');
  assert.match(src, /\['徽章', '到店券', '最快一段', '首达'\]/);
  assert.match(src, /const tags = topId != null \? \{ \[topId\]: all\[topId\] \} : \{\};/);
});

test('浅底整屏玩法把状态栏字换黑', () => {
  const src = read('pages/play/components/playkit/index.js');
  assert.match(src, /LIGHT_KITS = \['estimate', 'countdown', 'stopwatch'/);
  assert.match(src, /frontColor: light \? '#000000' : '#ffffff'/);
});
