'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition, member = '11';
  const requests = [], patches = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => member, sendRequest: r => requests.push(r) });
  global.Page = v => { definition = v; }; global.wx = {};
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, showFinish: true },
    setData(patch, callback) { patches.push(patch); Object.assign(this.data, patch); if (callback) callback(); } });
  return { page, requests, patches, account: value => { member = value; } };
}
for (const change of ['account', 'activity', 'closed', 'reopened', 'newread']) test('milestone rejects old ' + change, () => {
  const h = load(); h.page.loadMilestone();
  if (change === 'account') h.account('22');
  if (change === 'activity') h.page.data.activityId = 19;
  if (change === 'closed' || change === 'reopened') h.page._hideFinish();
  if (change === 'reopened') h.page.data.showFinish = true;
  if (change === 'newread') h.page.loadMilestone();
  const before = h.patches.length;
  h.requests[0].success({ code: 200, data: [{ topicId: 8 }] });
  assert.equal(h.patches.length, before);
});
test('current milestone still uses the real distinct completed topics', () => {
  const h = load(); h.page.loadMilestone();
  h.requests[0].success({ code: 200, data: [{ topicId: 8 }, { topicId: 8 }, { topicId: 9 }] });
  assert.ok(h.page.data.milestone);
  assert.notEqual(h.page.data.milestone, null);
});
