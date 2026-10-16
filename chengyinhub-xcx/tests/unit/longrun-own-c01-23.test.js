'use strict';
// Written only; NOT_RUN.
const { test } = require('node:test'); const assert = require('node:assert/strict'); const path = require('node:path');
function load() {
  let definition; const modals = [], requests = [], reads = [], feedback = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => '11', sendRequest: r => requests.push(r) }); global.Page = v => { definition = v; }; global.getCurrentPages = () => [];
  global.wx = { showModal: r => modals.push(r), showLoading() {}, hideLoading() {} };
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, lead: { exists: true, isLeader: true } },
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
  h.page.data.activityId = 9; h.page.leadUnlock();
  assert.equal(h.requests.length, 2, 'returning to unknown activity only audits; it never resends');
  assert.equal(h.reads.length, 1); assert.ok(h.reads[0].unlockAuditToken);
  h.requests[1].success({ code: 200 }); await new Promise(resolve => setImmediate(resolve));
});
