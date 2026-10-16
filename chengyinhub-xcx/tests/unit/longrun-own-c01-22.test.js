'use strict';
// Written only; NOT_RUN.
const { test } = require('node:test'); const assert = require('node:assert/strict'); const path = require('node:path');
function load() {
  let definition, member = '11'; const requests = [], feedback = [], reads = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => member, sendRequest: r => requests.push(r) });
  global.Page = v => { definition = v; }; global.wx = {};
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8 }, diegetic: v => feedback.push(v), loadTeamProgress: () => reads.push(true) });
  return { page, requests, feedback, reads, account: v => { member = v; } };
}
for (const action of ['leadArrive', 'leadStart']) {
  for (const change of ['account', 'activity', 'lifecycle']) test(action + ' ignores old ' + change, async () => {
    const h = load(); h.page[action]();
    if (change === 'account') h.account('22');
    if (change === 'activity') h.page.data.activityId = 19;
    if (change === 'lifecycle') h.page._leadLifecycleEpoch = 1;
    h.requests[0].success({ code: 200, msg: 'old fixture success' });
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(h.feedback, []); assert.deepEqual(h.reads, []);
  });
  test(action + ' latest response keeps its own original activity and reads once', async () => {
    const h = load(); h.page[action](); h.page[action]();
    assert.equal(h.requests[0].data.activityId, 9);
    h.requests[1].success({ code: 200, msg: 'new fixture success' });
    h.requests[0].success({ code: 200, msg: 'old fixture success' });
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(h.feedback, ['new fixture success']); assert.equal(h.reads.length, 1);
  });
}
