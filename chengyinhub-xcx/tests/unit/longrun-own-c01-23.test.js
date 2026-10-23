'use strict';
const { test } = require('node:test'); const assert = require('node:assert/strict'); const path = require('node:path');
function load() {
  let definition; const modals = [], requests = [], reads = [], feedback = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => '11', sendRequest: r => requests.push(r) }); global.Page = v => { definition = v; }; global.getCurrentPages = () => [];
  const disk = new Map();
  global.wx = { getStorageSync: k => disk.get(k), setStorageSync: (k,v) => disk.set(k,v), showModal: r => modals.push(r), showLoading() {}, hideLoading() {} };
  delete require.cache[require.resolve('../../pages/play/utils/play-route-state')];
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, lead: { exists: true, isLeader: true, currentChapterId: 10, nextChapterId: 20 } },
    setData(p) { Object.assign(this.data, p); }, loadTeamProgress: p => reads.push(p), diegetic: v => feedback.push(v) });
  return { page, modals, requests, reads, feedback };
}
test('old unlock confirmation cannot send to a later activity', () => {
  const h = load(); h.page.leadUnlock(); h.page.data.activityId = 19;
  h.modals[0].success({ confirm: true }); assert.deepEqual(h.requests, []);
});
test('late unknown result retains the original activity intent without touching the newer one', async () => {
  const h = load(); h.page.leadUnlock(); h.modals[0].success({ confirm: true });
  h.page.data.activityId = 19; h.page.leadUnlock(); h.modals[1].success({ confirm: true });
  const current = h.page._leadUnlockIntent;
  h.requests[0].successStatusAbnormal({ code: 503 }); await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.page._leadUnlockIntent, current); assert.equal(h.page._leadUnlockInFlight, true);
  assert.deepEqual(h.reads, []);
  const currentBody = h.requests[1].data;
  h.page.data.activityId = 9; h.page.leadUnlock();
  assert.equal(h.requests.length, 3, 'returning to unknown activity only reads the original receipt');
  assert.equal(h.requests[2].method, 'GET');
  assert.equal(h.requests[2].url, '/api/club/lead/unlock-chapter-receipt');
  assert.equal(h.requests[2].data.commandId, h.requests[0].data.commandId);
  assert.equal(h.reads.length, 0);
  h.requests[1].success({ code: 200, data: currentBody });
  h.requests[2].success({ code: 520 }); await new Promise(resolve => setImmediate(resolve));
});
test('cancelling the replacement activity confirmation cannot inherit another activity in-flight lock', async () => {
  const h = load(); h.page.leadUnlock(); h.modals[0].success({ confirm: true });
  h.page.data.activityId = 19; h.page.leadUnlock(); h.modals[1].success({ confirm: false });
  h.page.leadUnlock();
  assert.equal(h.modals.length, 3, 'replacement activity has no submitted command and can confirm again');
  h.modals[2].success({ confirm: true });
  const replacement = h.page._leadUnlockIntent;
  h.requests[0].fail(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.page._leadUnlockIntent, replacement);
  assert.equal(h.page._leadUnlockInFlight, true, 'late original failure cannot unlock the replacement command');
  h.requests[1].success({ code: 200, data: h.requests[1].data });
  await new Promise(resolve => setImmediate(resolve));
});
