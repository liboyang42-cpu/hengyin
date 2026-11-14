'use strict';
// 第九轮 m4 第 2 项:play 按主题外观选底图;layer-style 不能运行时改 → 外观落定才放地图,外观真变了先撤再建。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const mapStyle = require('../../utils/map-style.js');
const playLook = require('../../pages/play/utils/play-look.js');

const KEY = 'ABCDE-FGHIJ-KLMNO-PQRST-UVWXY-ZABCD';

function page(data) {
  const calls = [];
  const ctx = Object.assign({}, playLook.methods(), {
    data: Object.assign({}, data),
    setData(patch) { calls.push(Object.assign({}, patch)); Object.assign(this.data, patch); },
  });
  return { ctx, calls };
}

function withKey(fn) {
  const saved = Object.assign({}, mapStyle.MAP_STYLE);
  Object.assign(mapStyle.MAP_STYLE, { subkey: KEY, black: 1, white: 2, paper: 3, cyber: 4 });
  try { return fn(); } finally { Object.assign(mapStyle.MAP_STYLE, saved); }
}

test('没配 key:外观不改底图,叠加层配色不变,地图一开始就能渲染', () => {
  const { ctx, calls } = page({ fmSubkey: '', fmLayerStyle: 1, fmTone: '', fmStyleReady: true });
  ctx._applyMapLook({ style: 'PAPER' });
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0], { fmSubkey: '', fmLayerStyle: 1, fmTone: '', fmStyleReady: true });
});

test('配了 key:第一次外观落定一次性给出样式并放地图(纸本 → paper)', () => withKey(() => {
  const { ctx, calls } = page({ fmSubkey: KEY, fmLayerStyle: 1, fmTone: '', fmStyleReady: false });
  ctx._applyMapLook({ style: 'PAPER' });
  assert.deepEqual(calls, [{ fmSubkey: KEY, fmLayerStyle: 3, fmTone: 'paper', fmStyleReady: true }]);
}));

test('配了 key:同一页里外观变了,先撤地图,下一拍按新样式重建(不在旧图上改 layer-style)', () => withKey(async () => {
  const { ctx, calls } = page({ fmSubkey: KEY, fmLayerStyle: 3, fmTone: 'paper', fmStyleReady: true });
  ctx._applyMapLook({ style: 'CYBER' });
  assert.deepEqual(calls, [{ fmStyleReady: false }]);
  await new Promise((r) => setTimeout(r, 5));
  assert.deepEqual(calls[1], { fmSubkey: KEY, fmLayerStyle: 4, fmTone: '2077', fmStyleReady: true });
}));

test('配了 key:外观没变不撤图(星尘 → 末世 都是 black)', () => withKey(() => {
  const { ctx, calls } = page({ fmSubkey: KEY, fmLayerStyle: 1, fmTone: 'black', fmStyleReady: true });
  ctx._applyMapLook({ style: 'SURVIVAL' });
  assert.deepEqual(calls, [{ fmSubkey: KEY, fmLayerStyle: 1, fmTone: 'black', fmStyleReady: true }]);
}));

test('_applyLook 把外观交给底图选择', () => {
  const src = read('pages/play/utils/play-look.js');
  assert.match(src, /this\._applyMapLook\(look\);/);
});

test('play 三张地图都等 fmStyleReady 并带 tone;初值:没配 key 立即可渲染', () => {
  const wxml = read('pages/play/index.wxml');
  const maps = wxml.match(/<free-map\b[^>]*>/g);
  assert.equal(maps.length, 3);
  // 2026-11-14 业主:左上雷达与另两张图一样跟主题外观(此前 11-06 固定深色)
  maps.forEach((tag) => assert.match(tag, /subkey="\{\{fmSubkey\}\}" layer-style="\{\{fmLayerStyle\}\}" tone="\{\{fmTone\}\}"/));
  assert.doesNotMatch(wxml, /radarMap/);
  assert.match(maps[0], /screen !== 'gamePlay' && fmStyleReady\}\}"/, '罗盘雷达等外观');
  assert.match(wxml, /<view class="story-map" wx:if="\{\{storyMap && fmStyleReady\}\}">/, '小地图整层等外观');
  assert.match(maps[2], /wx:if="\{\{fmStyleReady\}\}"/, '完成页回放等外观');
  assert.match(read('pages/play/index.js'), /fmTone: '', fmStyleReady: !mapStyleConfigured\(\),/);
});

test('free-map:浅底配色走 toneLines / toneCircle;3D 楼块与俯仰只跟 tilt3d', () => {
  const js = read('components/cy/free-map/index.js');
  assert.match(js, /lines: toneLines\(/);
  assert.equal((js.match(/toneCircle\('(candidate|target)', this\.data\.tone\)/g) || []).length, 2);
  const wxml = read('components/cy/free-map/index.wxml');
  assert.match(wxml, /enable-overlooking="\{\{false\}\}" skew="\{\{tilt3d \? skew3d : 0\}\}"/);
  assert.match(wxml, /enable-building="\{\{tilt3d\}\}" enable-3D="\{\{tilt3d\}\}"/);
  assert.match(read('pages/roam/index.wxml'), /id="roamMap"[\s\S]{0,200}tilt3d="\{\{map3d\}\}"/);
  assert.equal((read('pages/roam/index.js').match(/roam3dFor\(detail\.scale\) !== this\.data\.map3d/g) || []).length, 2);
});
