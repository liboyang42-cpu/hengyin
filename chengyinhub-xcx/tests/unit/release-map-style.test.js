'use strict';

// 第九轮 m1 第 1 项:仓库里 subkey 永远为空;发布脚本只改上传拷贝,缺值按要求大声失败;
// 玩家地图走暗色序号,商家 / 编辑器地图走同一个 key 的浅色序号。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const script = require('../../scripts/release-map-style.js');
const mapStyle = require('../../utils/map-style.js');

const KEY = 'ABCDE-FGHIJ-KLMNO-PQRST-UVWXY-ZABCD';

function copyDir() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'map-style-'));
  fs.mkdirSync(path.join(dir, 'utils'));
  fs.copyFileSync(path.join(ROOT, 'utils/map-style.js'), path.join(dir, 'utils/map-style.js'));
  return dir;
}

const FULL = { CY_MAP_SUBKEY: KEY, CY_MAP_STYLE_BLACK: '1', CY_MAP_STYLE_WHITE: '2', CY_MAP_STYLE_PAPER: '3', CY_MAP_STYLE_2077: '4' };
const CONF = { subkey: KEY, black: 1, white: 2, paper: 3, cyber: 4 };

test('仓库里的 subkey 是空的,四个序号都没填', () => {
  assert.deepEqual(mapStyle.MAP_STYLE, { subkey: '', black: 0, white: 0, paper: 0, cyber: 0 });
  assert.equal(mapStyle.configured(), false);
  assert.deepEqual(mapStyle.playerMapStyle(), { subkey: '', layerStyle: 1 });
  assert.deepEqual(mapStyle.lightMapStyle(), { subkey: '', layerStyle: 1 });
  mapStyle.KINDS.forEach((k) => {
    assert.deepEqual(mapStyle.mapStyleFor(k), { subkey: '', layerStyle: 1 }, k);
    assert.equal(mapStyle.mapToneFor(k), '', '没配 key 时叠加层配色不变');
  });
});

test('第九轮 m4:同一个 key 四套样式,各取各的序号', () => {
  assert.deepEqual(mapStyle.playerMapStyle(CONF), { subkey: KEY, layerStyle: 1 });
  assert.deepEqual(mapStyle.lightMapStyle(CONF), { subkey: KEY, layerStyle: 2 });
  assert.deepEqual(mapStyle.mapStyleFor('paper', CONF), { subkey: KEY, layerStyle: 3 });
  assert.deepEqual(mapStyle.mapStyleFor('2077', CONF), { subkey: KEY, layerStyle: 4 });
  assert.equal(mapStyle.mapToneFor('paper', CONF), 'paper');
});

test('第九轮 m4:主题外观选底图:纸本 paper、夜城 2077、星尘 / 末世 / 没设外观 black', () => {
  assert.equal(mapStyle.kindForLook({ style: 'PAPER' }), 'paper');
  assert.equal(mapStyle.kindForLook({ style: 'CYBER' }), '2077');
  assert.equal(mapStyle.kindForLook({ style: 'STARDUST' }), 'black');
  assert.equal(mapStyle.kindForLook({ style: 'SURVIVAL' }), 'black');
  assert.equal(mapStyle.kindForLook({ style: 'STARDUST', legacy: true }), 'black');
  assert.equal(mapStyle.kindForLook(null), 'black');
  assert.equal(mapStyle.kindForLook({ style: 'PAPER', legacy: true }), 'black', '没配过外观的老主题按 black');
});

test('第九轮 m4:浅底上白线换墨色、描边换浅色;队伍线保留队色只加深描边;深底原样', () => {
  const lines = [
    { points: [], color: '#FFFFFF38', width: 14, borderWidth: 0 },
    { points: [], color: '#FFFFFFF2', width: 4, borderColor: '#00000052', borderWidth: 1 },
    { points: [], color: '#FFFFFF', width: 6, borderColor: '#111111', borderWidth: 2, arrowLine: true },
    { points: [], color: '#3DE08A42', width: 13, borderWidth: 0 },
    { points: [], color: '#3DE08A', width: 4, borderColor: '#00000052', borderWidth: 1 },
  ];
  ['black', '2077', ''].forEach((t) => assert.equal(mapStyle.toneLines(lines, t), lines, t));
  ['white', 'paper'].forEach((t) => {
    const out = mapStyle.toneLines(lines, t);
    assert.notEqual(out[0].color.slice(0, 7), '#FFFFFF');
    assert.ok(parseInt(out[0].color.slice(7), 16) < 128, '垫层仍是半透明');
    assert.ok(parseInt(out[1].color.slice(7), 16) > 200, '亮线换成实色墨线');
    assert.ok(/^#[0-3]/.test(out[1].color), '墨色够深');
    assert.equal(out[1].borderColor, out[2].borderColor, '描边换成同一种浅色');
    assert.equal(out[2].arrowLine, true);
    assert.equal(out[3], lines[3], '队色垫层不动');
    assert.equal(out[4].color, '#3DE08A');
    assert.notEqual(out[4].borderColor, '#00000052', '队伍线描边加深');
    assert.equal(lines[1].color, '#FFFFFFF2', '不改入参');
  });
  assert.deepEqual(mapStyle.toneCircle('target', ''), { fillColor: 'rgba(255,255,255,.22)', color: 'rgba(255,255,255,.55)' });
  assert.notDeepEqual(mapStyle.toneCircle('candidate', 'paper'), mapStyle.toneCircle('candidate', 'black'));
});

test('第九轮 m4:3D 楼块与俯仰只在开关打开且 16 级以上', () => {
  assert.equal(mapStyle.ROAM_3D.on, false, '默认关');
  assert.equal(mapStyle.roam3dFor(18), false);
  const on = { on: true, minScale: 16, skew: 30 };
  assert.equal(mapStyle.roam3dFor(15.9, on), false);
  assert.equal(mapStyle.roam3dFor(16, on), true);
  assert.equal(mapStyle.roam3dFor(19, on), true);
});

test('填值只进上传拷贝,渲染出的模块可直接加载', () => {
  const dir = copyDir();
  const r = script.run(['--dir', dir, '--require'], FULL);
  assert.equal(r.changed, true);
  const mod = require(path.join(dir, 'utils/map-style.js'));
  assert.deepEqual(mod.MAP_STYLE, CONF);
  assert.deepEqual(mod.lightMapStyle(), { subkey: KEY, layerStyle: 2 });
  assert.deepEqual(mod.mapStyleFor('2077'), { subkey: KEY, layerStyle: 4 });
  assert.match(fs.readFileSync(path.join(ROOT, 'utils/map-style.js'), 'utf8'), /subkey: '',\n  black: 0,\n  white: 0,\n  paper: 0,\n  cyber: 0,/);
});

test('旧变量名 CY_MAP_LAYER_STYLE / CY_MAP_LIGHT_STYLE 仍认作 black / white', () => {
  const dir = copyDir();
  script.run(['--dir', dir, '--require'], { CY_MAP_SUBKEY: KEY, CY_MAP_LAYER_STYLE: '1', CY_MAP_LIGHT_STYLE: '2', CY_MAP_STYLE_PAPER: '3', CY_MAP_STYLE_2077: '4' });
  assert.match(fs.readFileSync(path.join(dir, 'utils/map-style.js'), 'utf8'), /black: 1,\n  white: 2,\n  paper: 3,\n  cyber: 4,/);
});

test('--require 缺值时失败', () => {
  const dir = copyDir();
  assert.throws(() => script.run(['--dir', dir, '--require'], {}), /缺少环境变量: CY_MAP_SUBKEY, CY_MAP_STYLE_BLACK, CY_MAP_STYLE_WHITE, CY_MAP_STYLE_PAPER, CY_MAP_STYLE_2077/);
  assert.throws(() => script.run(['--dir', dir, '--require'], Object.assign({}, FULL, { CY_MAP_STYLE_2077: '' })), /CY_MAP_STYLE_2077/);
});

test('不带 --require 且没配:保持默认,不改文件', () => {
  const dir = copyDir();
  const before = fs.readFileSync(path.join(dir, 'utils/map-style.js'), 'utf8');
  assert.equal(script.run(['--dir', dir], {}).changed, false);
  assert.equal(fs.readFileSync(path.join(dir, 'utils/map-style.js'), 'utf8'), before);
});

test('给了 key 却缺任何一个序号或序号重复,永远失败', () => {
  const dir = copyDir();
  assert.throws(() => script.run(['--dir', dir], { CY_MAP_SUBKEY: KEY, CY_MAP_STYLE_BLACK: '1', CY_MAP_STYLE_WHITE: '2' }), /CY_MAP_STYLE_PAPER, CY_MAP_STYLE_2077/);
  assert.throws(() => script.run(['--dir', dir], Object.assign({}, FULL, { CY_MAP_STYLE_PAPER: '2' })), /序号相同/);
  assert.throws(() => script.run(['--dir', dir], { CY_MAP_STYLE_PAPER: '3' }), /没有 CY_MAP_SUBKEY/);
});

test('拒绝改仓库本身', () => {
  assert.throws(() => script.run(['--dir', ROOT], FULL), /拒绝改仓库本身/);
});

test('玩家地图接暗色,商家 / 编辑器地图接浅色', () => {
  const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
  ['pages/roam/index.js', 'pages/play/index.js', 'subpackageRoam/nearby/index.js'].forEach((p) => assert.match(read(p), /playerMapStyle\(\)/, p));
  ['pages/publish/fabu/index.js', 'pages/topic/index/index.js', 'pages/square/components/route-map/index.js'].forEach((p) => assert.match(read(p), /lightMapStyle\(\)/, p));
  assert.match(read('pages/searchmap/index.js'), /isMerchantView\(\) \? lightMapStyle\(\) : playerMapStyle\(\)/);
  // 所有原生 <map> 都带上同一套 subkey / layer-style(一个小程序只用一个 subkey)
  ['pages/searchmap/index.wxml', 'pages/topic/index/index.wxml', 'pages/square/components/route-map/index.wxml', 'pages/publish/fabu/index.wxml', 'components/cy/free-map/index.wxml']
    .forEach((p) => assert.match(read(p), /subkey="\{\{/, p));
});
