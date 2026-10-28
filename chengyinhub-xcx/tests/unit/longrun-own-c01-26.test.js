'use strict';
// Agent C 已在轻量 Node 环境实际执行。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
// W8 起登录态写意图按账号/场次持久化到本机(play-route-state scopedRouteActionStore → wx storage),
// 模块级按作用域缓存;每个用例给新存储并重载该模块。基础写载荷须已绑定原局 routeSessionId,
// 服务端终态回执带 intentTerminal(PlayBasicIntentApplication)。
const ROUTE_STATE = path.resolve(__dirname, '../../pages/play/utils/play-route-state.js');
function memoryStorage() {
  const store = new Map();
  return { getStorageSync: k => store.get(k), setStorageSync: (k, v) => { store.set(k, JSON.parse(JSON.stringify(v))); },
    removeStorageSync: k => { store.delete(k); } };
}
function load() {
  let definition, scan, picked; const requests = [], completed = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => '11', sendRequest: r => requests.push(r), chooseImage: cb => { picked = cb; } });
  global.Page = value => { definition = value; };
  global.wx = { scanCode: options => { scan = options; }, showToast() {}, ...memoryStorage() };
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; delete require.cache[ROUTE_STATE]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, mode: 1, game: { nodeId: 31 }, answerInput: 'fixture' },
    _routeState: { sessionId: 's9', version: 4 }, _routeActions: {}, setData(patch) { Object.assign(this.data, patch); },
    _routeWritePayload(kind, nodeId, extra) { return Object.assign({ activityId: this.data.activityId, routeActionId: 'original-fixture-key', routeSessionId: this._routeState.sessionId, expectedRouteVersion: this._routeState.version }, extra); },
    clearGameNetworkError() {}, _resolveRouteAction() {}, onComplete: id => completed.push(id), _applyAiScore() {} });
  return { page, requests, completed, scan: () => scan, picked: () => picked };
}
test('old scanner and picker cannot submit for replacement game', () => {
  const h = load(); const calls = []; h.page.checkin = (...args) => calls.push(args); h.page.submitPhoto = (...args) => calls.push(args);
  h.page.scanGame(); h.page.photoGame(); h.page.data.game = { nodeId: 32 };
  h.scan().success({ result: 'fixture' }); h.picked()(['fixture-image']); assert.deepEqual(calls, []);
});
for (const method of ['submitPhoto', 'submitGame', 'revealAnswer']) test(method + ' cannot send after thought wait changes scope', async () => {
  const h = load(); let done; h.page.data.game.puzzleScoring = true;
  h.page._thoughtSyncPending = new Promise(resolve => { done = resolve; }); h.page._syncThoughts = () => Promise.resolve(true);
  h.page[method](31, 'fixture-image'); h.page.data.activityId = 10; done(true);
  await new Promise(resolve => setImmediate(resolve)); assert.deepEqual(h.requests, []); assert.deepEqual(h.completed, []);
});
test('old photo response cannot finish replacement game or release its write lock', async () => {
  const h = load(); h.page.submitPhoto(31, 'fixture-image'); await new Promise(resolve => setImmediate(resolve));
  h.page.data.game = { nodeId: 32 }; h.page._gameWriteInFlight = false; h.page.submitPhoto(32, 'next-image');
  const next = h.page._gameWriteIntent; h.requests[0].success({ code: 200, intentTerminal: true, data: {} });
  await new Promise(resolve => setImmediate(resolve)); assert.equal(h.page._gameWriteIntent, next); assert.equal(h.page._gameWriteInFlight, true); assert.deepEqual(h.completed, []);
});
