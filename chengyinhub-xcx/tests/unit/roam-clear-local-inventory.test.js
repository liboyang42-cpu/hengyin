'use strict';

// F0386 清理本地漫游缓存:清理前列出将丢的恢复项;未结算/结算未知时先核对原会话;
// 只清展示缓存保 recovery/足迹,且不等于分享撤回。
const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const { createPlayerRoamMemory } = require('../../utils/roam-player-memory.js');

function memStorage() {
  const values = {};
  return { values, getStorageSync: (k) => values[k], setStorageSync: (k, v) => { values[k] = v; } };
}

test('inspectLocal 列出未结算/待上报/本机足迹;读失败标为 unknown', () => {
  const storage = memStorage();
  const memory = createPlayerRoamMemory('9', storage);
  memory.writeRecovery({ clientSessionKey: 'a'.repeat(32), pendingTiles: ['wtw3sjq', 'wtw3sjr'] });
  memory.writeSessions([{ ts: 1, track: [] }, { ts: 2, track: [] }]);
  memory.writeTiles({ wtw3sjq: 1 }, 3);
  const inv = memory.inspectLocal();
  assert.equal(inv.recovery, true);
  assert.equal(inv.pendingTiles, 2);
  assert.equal(inv.sessions, 2);
  assert.equal(inv.tiles, 1);
  assert.equal(inv.recoveryUnknown, false);

  const broken = createPlayerRoamMemory('9', { getStorageSync() { throw new Error('io'); }, setStorageSync() {} });
  assert.equal(broken.inspectLocal().recoveryUnknown, true);
});

test('只清展示缓存:迷雾格子清掉,recovery 与本机足迹保留', () => {
  const storage = memStorage();
  const memory = createPlayerRoamMemory('9', storage);
  const record = { clientSessionKey: 'a'.repeat(32), pendingTiles: ['wtw3sjq'] };
  memory.writeRecovery(record);
  memory.writeSessions([{ ts: 1 }]);
  memory.writeTiles({ wtw3sjq: 1 }, 3);
  assert.equal(memory.clearDisplayCache(), true);
  assert.deepEqual(memory.readTiles(), { tiles: {}, serverCursor: 0 });
  assert.deepEqual(memory.readRecoveryState().record, record);
  assert.equal(memory.readSessions().length, 1);
});

function loadRoamPage(storage) {
  let cfg;
  global.Page = (c) => { cfg = c; };
  global.getApp = () => ({ globalData: { user_id: 9, features: {} }, getUserID: () => '9', isDevEnv: () => false, sendRequest() {}, recordConsent: () => Promise.resolve() });
  global.wx = Object.assign({
    showToast() {},
    getWindowInfo: () => ({ statusBarHeight: 20 }),
    getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    createSelectorQuery: () => ({ select: () => ({ fields: () => ({ exec: (cb) => cb([{ node: null }]) }) }) }),
  }, storage);
  delete require.cache[require.resolve('../../pages/roam/index.js')];
  require('../../pages/roam/index.js');
  const page = Object.assign({}, cfg, { data: JSON.parse(JSON.stringify(cfg.data)) });
  page.setData = (patch, cb) => { Object.assign(page.data, patch); if (cb) cb(); };
  return page;
}

test('未结算漫游时「全部清空」被阻断,恢复记录不丢,也不去撤分享链接', async () => {
  const storage = memStorage();
  createPlayerRoamMemory('9', storage).writeRecovery({ clientSessionKey: 'a'.repeat(32), pendingTiles: ['wtw3sjq'] });
  const page = loadRoamPage(storage);
  page.clearRoamLocal();
  assert.equal(page.data.clearInv.blocked, true);
  assert.equal(page.data.clearInv.pending, 1);
  const ok = await page.confirmClearRoamLocal();
  assert.equal(ok, false);
  assert.ok(createPlayerRoamMemory('9', storage).readRecoveryState().record, '恢复线索必须保留');
});

test('无未结算时列出本机足迹数,全部清空后才作废分享;只清展示不作废', async () => {
  const storage = memStorage();
  const memory = createPlayerRoamMemory('9', storage);
  memory.writeSessions([{ ts: 1 }, { ts: 2 }, { ts: 3 }]);
  const page = loadRoamPage(storage);
  const sent = [];
  global.getApp = () => ({ getUserID: () => '9', sendRequest(o) { sent.push(o.url); o.success({ code: 200 }); } });
  page.clearRoamLocal();
  assert.equal(page.data.clearInv.blocked, false);
  assert.equal(page.data.clearInv.sessions, 3);
  await page.confirmClearRoamDisplay();
  assert.equal(sent.length, 0, '只清展示缓存不是分享撤回');
  assert.equal(memory.readSessions().length, 3);
});

test('WXML:确认层列出不可恢复的本机足迹与「只清展示缓存」出口', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/roam/index.wxml'), 'utf8');
  assert.match(wxml, /bindtap="recoverBeforeClearRoamLocal"/);
  assert.match(wxml, /bindtap="confirmClearRoamDisplay"/);
  assert.match(wxml, /删除后不可恢复/);
  assert.match(wxml, /已保存或发出的图片无法召回/);
});
