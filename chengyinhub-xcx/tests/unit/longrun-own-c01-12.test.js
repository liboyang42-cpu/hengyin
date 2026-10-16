'use strict';
// Written only, NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition, member = '11';
  const requests = [], patches = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => member, sendRequest: r => requests.push(r) });
  global.Page = v => { definition = v; }; global.wx = {};
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const node = { nodeId: 31, done: false };
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8,
    nodes: [node], sheet: { show: true, node }, journeyCheck: { show: false } }, setData: p => patches.push(p) });
  return { page, node, requests, patches, account: value => { member = value; } };
}
for (const change of ['current', 'activity', 'account', 'sheet', 'closed', 'done', 'load']) {
  test('journey probe respects ' + change + ' context', async () => {
    const h = load(); h.page._probeJourneyCheck(h.node);
    if (change === 'activity') h.page.data.activityId = 19;
    if (change === 'account') h.account('22');
    if (change === 'sheet') h.page.data.sheet.node = { nodeId: 32 };
    if (change === 'closed') h.page.data.sheet.show = false;
    if (change === 'done') h.node.done = true;
    if (change === 'load') h.page._playLoadEpoch = 1;
    h.requests[0].success({ code: 200, data: { allowedActions: ['check'], check: { checkId: 'fixture-check' } } });
    await new Promise(resolve => setImmediate(resolve));
    if (change === 'current') assert.equal(h.patches[0].journeyCheck.checkId, 'fixture-check');
    else assert.deepEqual(h.patches, []);
  });
}
