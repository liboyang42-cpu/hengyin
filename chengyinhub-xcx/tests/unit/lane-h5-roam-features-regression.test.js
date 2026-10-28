'use strict';
// PR #15 已移植到当前消费者、但 master 上缺回归的子项：B8 半径常量、C9 继续漫游条、C5 路线整形、C1 清除对话。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

test('B8: /api/roam/pois 与 nearby-runners 共用 ROAM_POI_RADIUS_M', () => {
  const src = read('pages/roam/index.js');
  assert.match(src, /const ROAM_POI_RADIUS_M = 3000;/);
  assert.match(src, /'\/api\/roam\/nearby-runners', 'POST', \{ lat: p\.lat, lng: p\.lng, radius: ROAM_POI_RADIUS_M \}/);
  // /api/roam/pois 走 BL-01 筛选半径，未设置时回落同一常量
  assert.match(src, /radiusM \|\| ROAM_POI_RADIUS_M;[^\n]*\n\s*return req\('\/api\/roam\/pois', 'GET', \{ lat: String\(c\.lat\), lng: String\(c\.lng\), radius: String\(radiusM\) \}/);
  assert.doesNotMatch(src, /'\/api\/roam\/pois', 'GET', \{[^}]*radius: '3000'/);
});

// 2026-10-27 真机走查 P-08:owner 要求玩家首页不再放「继续上次漫游」,首页撤掉这条的挂载;
// 组件本身仍归漫游线所有，组件内部约束照旧钉住，挂载断言改为「首页不再挂」。
test('C9: 继续漫游条只读本机恢复记录、复用 validRecovery，不发请求;首页不再挂载(P-08)', () => {
  const src = read('subpackageRoam/components/scene-roam-resume-bar/index.js');
  assert.match(src, /require\('\.\.\/\.\.\/\.\.\/utils\/roam-recovery\.js'\)/);
  assert.match(src, /validRecovery\(state\.record\)/);
  assert.match(src, /readRecoveryState\(\)/);
  assert.doesNotMatch(src, /sendRequest|wx\.request/);
  assert.match(src, /wx\.switchTab\(\{ url: '\/pages\/roam\/index' \}\)/);
  assert.doesNotMatch(read('pages/index/index.json'), /scene-roam-resume-bar/);
  assert.doesNotMatch(read('pages/index/index.wxml'), /<cy-roam-resume-bar/);
});

test('C9: 恢复记录键与 roam-player-memory 一致（按玩家分桶）', () => {
  const { createPlayerRoamMemory } = require('../../utils/roam-player-memory.js');
  const store = {};
  const storage = { getStorageSync: (k) => store[k], setStorageSync: (k, v) => { store[k] = v; } };
  createPlayerRoamMemory('42', storage).writeRecovery({ clientSessionKey: 'a'.repeat(32), pendingTiles: [], finishRequested: false });
  assert.deepEqual(Object.keys(store), ['roam_memory_v1:42:recovery']);
});

test('C5: 官方路线整形——单位、缺字段、坏坐标', () => {
  const route = require('../../subpackageRoam/utils/official-route.js');
  assert.equal(route.distanceText(3.5), '3.5 km');
  assert.equal(route.distanceText(0), '');
  assert.equal(route.durationText(45), '45 分钟');
  assert.equal(route.durationText(150), '2 小时 30 分');
  assert.equal(route.normalizeRoute({ id: 1 }), null);
  assert.equal(route.normalizeRouteList({}), null);
  const list = route.normalizeRouteList([{ id: 7, name: '老城', city: '上海', totalDistance: 4, totalTime: 90 }]);
  assert.equal(list[0].metaText, '上海 · 4 km · 1 小时 30 分');
  const detail = route.normalizeRouteDetail({ id: 7, name: '老城', nodes: [
    { name: 'A', latitude: '31.2', longitude: '121.4', nodeTime: 20 },
    { name: 'B', latitude: '', longitude: '' },
    { name: '', latitude: '1', longitude: '1' },
    { name: 'C', latitude: '99', longitude: '10' },
  ] });
  assert.deepEqual(detail.nodes.map((n) => [n.order, n.name, n.lat]), [[1, 'A', 31.2], [2, 'B', null], [3, 'C', null]]);
});

test('C1: 清除对话——危险确认、端点、先作废在途提问、成功后才清本地', () => {
  const src = read('subpackageMember/components/cy/npc-chat/index.js');
  assert.match(src, /dc\.open\('ai\.npc\.chatData\.delete', \{\}\)/);
  const start = src.indexOf("url: '/api/ai/npc/chat/data/delete'");
  assert.ok(start > 0);
  const before = src.slice(src.lastIndexOf('getApp().sendRequest', start) - 400, start);
  assert.match(before, /this\._invalidate\(false\)/);
  const success = src.slice(start, src.indexOf('fail:', start));
  assert.ok(success.indexOf("res.code != 200") < success.indexOf('messages: []'), '失败分支必须先于清空本地');
  const actions = require('../../utils/danger-actions.js');
  const a = actions.getDangerAction('ai.npc.chatData.delete', {});
  assert.ok(a.consequences.some((c) => c.text.includes('此操作不可撤销')));
  assert.ok(a.consequences.some((c) => c.text.includes('所有 AI 对话')));
});
