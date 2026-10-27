'use strict';
const { test } = require('node:test'); const assert = require('node:assert/strict'); const path = require('node:path');
// W8 起登录态写意图按账号/场次持久化到本机(play-route-state scopedRouteActionStore → wx storage),
// 模块级按作用域缓存;每个用例给新存储并重载该模块,避免上一个用例的待确认意图串进来。
const ROUTE_STATE = path.resolve(__dirname, '../../pages/play/utils/play-route-state.js');
function memoryStorage() {
  const store = new Map();
  return { getStorageSync: k => store.get(k), setStorageSync: (k, v) => { store.set(k, JSON.parse(JSON.stringify(v))); },
    removeStorageSync: k => { store.delete(k); } };
}
// W8 基础写协议:载荷须已绑定原局 routeSessionId(否则先 GET action-context 绑定),
// 服务端只有带 intentTerminal 的回执才算终态(PlayBasicIntentApplication),否则按待确认处理。
function load() {
  let definition, member = '11'; const requests = [], completed = [], payloads = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => member, sendRequest: r => requests.push(r) }); global.Page = v => { definition = v; }; global.wx = { showToast() {}, ...memoryStorage() };
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; delete require.cache[ROUTE_STATE]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, regId: 71, mode: 1 },
    _routeState: { sessionId: 'route-session-9', version: 4 }, _routeActions: {},
    _routeWritePayload(kind, nodeId, extra) { const body = Object.assign({ activityId: this.data.activityId,
      routeActionId: 'fixture-action-' + payloads.length, expectedRouteVersion: this._routeState.version,
      routeSessionId: this._routeState.sessionId }, extra); payloads.push(body); return body; },
    _resolveRouteAction() {}, onComplete: nodeId => completed.push(nodeId),
    // W8 到达成功先清到达守卫视图(setData arrivalGuard:null)再完成节点
    setData(patch) { Object.assign(this.data, patch); } });
  return { page, requests, completed, payloads, account: v => { member = v; } };
}
for (const kind of ['arrive', 'checkin']) test('thought wait cannot send old ' + kind + ' under a new activity', async () => {
  const h = load(); let resolve;
  h.page._thoughtSyncPending = new Promise(done => { resolve = done; });
  let syncs = 0; h.page._syncThoughts = () => { syncs += 1; return Promise.resolve(true); };
  if (kind === 'arrive') h.page.arrive(31, 'fixture'); else h.page.checkin(31, 'fixture-code');
  h.page.data.activityId = 19; resolve(true);
  await new Promise(done => setImmediate(done));
  assert.deepEqual(h.requests, []); assert.equal(syncs, 0); assert.equal(h.page._arrivalWriteInFlight, false);
});
test('thought sync keeps the original action ID and registration while using current same-session version', async () => {
  const h = load(); let resolve;
  h.page._thoughtSyncPending = new Promise(done => { resolve = done; });
  h.page._syncThoughts = () => { h.page._routeState.version = 5; return Promise.resolve(true); };
  h.page.checkin(31, 'fixture-code'); h.page.data.regId = 99; resolve(true);
  await new Promise(done => setImmediate(done));
  assert.equal(h.payloads.length, 1); assert.equal(h.requests[0].data.routeActionId, h.payloads[0].routeActionId);
  assert.equal(h.requests[0].data.registrationId, 71); assert.equal(h.requests[0].data.expectedRouteVersion, 5);
  h.requests[0].success({ code: 200, intentTerminal: true, data: { nodeId: 31 } }); await new Promise(done => setImmediate(done));
  assert.deepEqual(h.completed, [31]);
});
test('old arrival callback cannot clear the replacement arrival lock or complete its old node', async () => {
  const h = load(); h.page.arrive(31, 'fixture'); h.page.data.activityId = 19;
  h.page._arrivalWriteInFlight = false; h.page.arrive(32, 'fixture');
  const current = h.page._arrivalWriteIntent;
  h.requests[0].success({ code: 200, intentTerminal: true, data: {} }); await new Promise(done => setImmediate(done));
  assert.equal(h.page._arrivalWriteIntent, current); assert.equal(h.page._arrivalWriteInFlight, true); assert.deepEqual(h.completed, []);
  h.requests[1].success({ code: 200, intentTerminal: true, data: {} }); await new Promise(done => setImmediate(done));
  assert.deepEqual(h.completed, [32]);
});
