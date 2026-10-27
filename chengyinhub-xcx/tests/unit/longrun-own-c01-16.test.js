'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
// W8 起检定写走「原局原请求」恢复(pages/play/utils/journey-check-recovery.js):
// 写前把原动作冻结进本机(按账号/原局/节点/检定),回执须回显同一 sessionId/nodeId/checkId/actionId 才落定;
// 结果不明不再自动 settle,而是由显式 recover 读 /api/play/check/receipt。夹具补本机存储与原局号。
function load() {
  let definition;
  const requests = [], patches = [], storage = new Map();
  global.getApp = () => ({ globalData: {}, getUserID: () => '11', sendRequest: r => requests.push(r) });
  global.Page = v => { definition = v; };
  global.wx = { showToast() {}, getStorageSync: k => storage.get(k),
    setStorageSync: (k, v) => { storage.set(k, JSON.parse(JSON.stringify(v))); }, removeStorageSync: k => { storage.delete(k); } };
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { _playLoadEpoch: 1, _routeState: { sessionId: '9' },
    data: { activityId: 9, topicId: 8, journeyCheck: { show: true, nodeId: 31, checkId: 'old-check' } },
    setData(patch) {
      patches.push(patch);
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.'); let target = this.data;
        for (const part of parts.slice(0, -1)) target = target[part];
        target[parts.at(-1)] = value;
      }
    } });
  return { page, requests, patches, storage };
}
const roll = { detail: { action: 'roll' } };
const recover = { detail: { action: 'recover' } };
const receipt = (nodeId, checkId, action = 'roll') => ({ code: 200, data: { sessionId: '9', nodeId: String(nodeId), checkId,
  actionId: 'check:' + nodeId + ':' + checkId + ':' + action, total: 12, dc: 13, dice: [12] } });
const flush = () => new Promise(resolve => setImmediate(resolve));
test('old check response never clears a newer intent', async () => {
  const h = load(); h.page.onJourneyCheckAction(roll);
  h.page.closeJourneyCheck();
  h.page.data.journeyCheck = { show: true, nodeId: 32, checkId: 'new-check' };
  h.page.onJourneyCheckAction(roll);
  const current = h.page._checkWriteIntent, before = h.patches.length;
  h.requests[0].success(receipt(31, 'old-check'));
  await flush();
  assert.equal(h.page._checkActing, true); assert.equal(h.page._checkWriteIntent, current);
  assert.equal(h.patches.length, before);
  h.requests[1].success(receipt(32, 'new-check'));
  await flush();
  assert.equal(h.page._checkActing, false);
});
test('old settle recovery also remains bound to the original check', async () => {
  const h = load(); h.page.onJourneyCheckAction(roll);
  h.requests[0].fail({ errMsg: 'response lost' });
  await flush();
  h.page.onJourneyCheckAction(recover);
  assert.equal(h.requests[1].url, '/api/play/check/receipt');
  h.page.closeJourneyCheck();
  h.page.data.journeyCheck = { show: true, nodeId: 32, checkId: 'new-check' };
  h.page.onJourneyCheckAction(roll);
  const before = h.patches.length;
  h.requests[1].success(receipt(31, 'old-check'));
  await flush();
  assert.equal(h.page._checkActing, true); assert.equal(h.patches.length, before);
});
test('closing and reopening the same check never resends its still-pending operation', async () => {
  const h = load(); h.page.onJourneyCheckAction(roll);
  h.page.closeJourneyCheck(); h.page.data.journeyCheck.show = true;
  h.page.onJourneyCheckAction(roll);
  assert.equal(h.requests.length, 1);
  h.requests[0].success({ code: 400, msg: 'fixture rejection' });
  await flush();
  assert.equal(h.storage.size, 1, '旧回包不能释放原请求');
  h.page.onJourneyCheckAction(recover);
  assert.equal(h.requests.length, 2); assert.equal(h.requests[1].data.action, 'roll');
  h.requests[1].success(receipt(31, 'old-check'));
  await flush();
  assert.equal(h.storage.size, 0);
});
