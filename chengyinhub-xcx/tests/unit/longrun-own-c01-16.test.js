'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition;
  const requests = [], patches = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => '11', sendRequest: r => requests.push(r) });
  global.Page = v => { definition = v; }; global.wx = { showToast() {} };
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8,
    journeyCheck: { show: true, nodeId: 31, checkId: 'old-check' } },
    setData(patch) { patches.push(patch); if ('journeyCheck.show' in patch) this.data.journeyCheck.show = patch['journeyCheck.show']; } });
  return { page, requests, patches };
}
const roll = { detail: { action: 'roll' } };
test('old check response never clears a newer intent', async () => {
  const h = load(); h.page.onJourneyCheckAction(roll);
  h.page.closeJourneyCheck();
  h.page.data.journeyCheck = { show: true, nodeId: 32, checkId: 'new-check' };
  h.page.onJourneyCheckAction(roll);
  const current = h.page._checkWriteIntent, before = h.patches.length;
  h.requests[0].success({ code: 200, data: {} });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.page._checkActing, true); assert.equal(h.page._checkWriteIntent, current);
  assert.equal(h.patches.length, before);
  h.requests[1].success({ code: 200, data: {} });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.page._checkActing, false);
});
test('old settle recovery also remains bound to the original check', async () => {
  const h = load(); h.page.onJourneyCheckAction(roll);
  h.requests[0].success({ code: 400, msg: '已结算' });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.requests[1].url, '/api/play/check/settle');
  h.page.closeJourneyCheck();
  h.page.data.journeyCheck = { show: true, nodeId: 32, checkId: 'new-check' };
  h.page.onJourneyCheckAction(roll);
  const before = h.patches.length;
  h.requests[1].success({ code: 200, data: {} });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.page._checkActing, true); assert.equal(h.patches.length, before);
});
test('closing and reopening the same check never resends its still-pending operation', async () => {
  const h = load(); h.page.onJourneyCheckAction(roll);
  h.page.closeJourneyCheck(); h.page.data.journeyCheck.show = true;
  h.page.onJourneyCheckAction(roll);
  assert.equal(h.requests.length, 1);
  h.requests[0].success({ code: 400, msg: 'fixture rejection' });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(Object.keys(h.page._checkPendingByKey).length, 0);
});
