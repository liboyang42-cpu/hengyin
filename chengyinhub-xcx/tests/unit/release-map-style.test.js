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

test('仓库里的 subkey 是空的', () => {
  assert.equal(mapStyle.MAP_STYLE.subkey, '');
  assert.deepEqual(mapStyle.playerMapStyle(), { subkey: '', layerStyle: 1 });
  assert.deepEqual(mapStyle.lightMapStyle(), { subkey: '', layerStyle: 1 });
});

test('同一个 key:玩家用暗色序号,商家用浅色序号', () => {
  const s = { subkey: KEY, layerStyle: 1, lightLayerStyle: 2 };
  assert.deepEqual(mapStyle.playerMapStyle(s), { subkey: KEY, layerStyle: 1 });
  assert.deepEqual(mapStyle.lightMapStyle(s), { subkey: KEY, layerStyle: 2 });
});

test('填值只进上传拷贝,渲染出的模块可直接加载', () => {
  const dir = copyDir();
  const r = script.run(['--dir', dir, '--require'], { CY_MAP_SUBKEY: KEY, CY_MAP_LAYER_STYLE: '1', CY_MAP_LIGHT_STYLE: '2' });
  assert.equal(r.changed, true);
  const mod = require(path.join(dir, 'utils/map-style.js'));
  assert.deepEqual(mod.MAP_STYLE, { subkey: KEY, layerStyle: 1, lightLayerStyle: 2 });
  assert.deepEqual(mod.lightMapStyle(), { subkey: KEY, layerStyle: 2 });
  assert.match(fs.readFileSync(path.join(ROOT, 'utils/map-style.js'), 'utf8'), /subkey: '',/);
});

test('--require 缺值时失败', () => {
  const dir = copyDir();
  assert.throws(() => script.run(['--dir', dir, '--require'], {}), /缺少环境变量: CY_MAP_SUBKEY, CY_MAP_LAYER_STYLE, CY_MAP_LIGHT_STYLE/);
  assert.throws(() => script.run(['--dir', dir, '--require'], { CY_MAP_SUBKEY: KEY, CY_MAP_LAYER_STYLE: '1' }), /CY_MAP_LIGHT_STYLE/);
});

test('不带 --require 且没配:保持默认,不改文件', () => {
  const dir = copyDir();
  const before = fs.readFileSync(path.join(dir, 'utils/map-style.js'), 'utf8');
  assert.equal(script.run(['--dir', dir], {}).changed, false);
  assert.equal(fs.readFileSync(path.join(dir, 'utils/map-style.js'), 'utf8'), before);
});

test('只给 key 不给浅色序号永远失败(商家地图会跟着变暗)', () => {
  const dir = copyDir();
  assert.throws(() => script.run(['--dir', dir], { CY_MAP_SUBKEY: KEY, CY_MAP_LAYER_STYLE: '1' }), /商家地图会跟着变暗/);
  assert.throws(() => script.run(['--dir', dir], { CY_MAP_SUBKEY: KEY, CY_MAP_LAYER_STYLE: '2', CY_MAP_LIGHT_STYLE: '2' }), /序号相同/);
});

test('拒绝改仓库本身', () => {
  assert.throws(() => script.run(['--dir', ROOT], { CY_MAP_SUBKEY: KEY, CY_MAP_LAYER_STYLE: '1', CY_MAP_LIGHT_STYLE: '2' }), /拒绝改仓库本身/);
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
