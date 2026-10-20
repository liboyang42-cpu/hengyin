'use strict';
const { test } = require('node:test'); const assert = require('node:assert/strict'); const path = require('node:path');
function load() {
  let definition, member = '11'; const scans = [], requests = [], feedback = [], hides = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => member, sendRequest: r => requests.push(r) });
  global.Page = v => { definition = v; }; global.getCurrentPages = () => [];
  global.wx = { scanCode: r => scans.push(r), showLoading() {}, hideLoading: () => hides.push(true), getStorageSync: () => '', vibrateShort() {} };
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, lead: { exists: true, isLeader: true } }, diegetic: text => feedback.push(text) });
  return { page, scans, requests, feedback, hides, account: value => { member = value; } };
}
for (const change of ['activity', 'account', 'leader']) test('leader scan does not send after changed ' + change, () => {
  const h = load(); h.page.leadVerifyMemberTicket();
  if (change === 'activity') h.page.data.activityId = 19;
  if (change === 'account') h.account('22');
  if (change === 'leader') h.page.data.lead.isLeader = false;
  h.scans[0].success({ result: 'fixture-ticket' }); assert.deepEqual(h.requests, []);
});
test('old verification completion only pairs its own loading and leaves the latest request feedback', async () => {
  const h = load(); h.page.leadVerifyMemberTicket(); h.scans[0].success({ result: 'fixture-ticket-A' });
  h.page.leadVerifyMemberTicket(); h.scans[1].success({ result: 'fixture-ticket-B' });
  assert.equal(h.requests[0].data.activityId, 9);
  h.requests[0].success({ code: 200 }); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.feedback, []); assert.deepEqual(h.hides, []);
  h.requests[1].success({ code: 200 }); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.feedback, ['核销成功']); assert.equal(h.hides.length, 1);
});
