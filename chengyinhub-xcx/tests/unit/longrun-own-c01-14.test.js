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
  const page = Object.assign({}, definition, { data: { mode: 2, activityId: 9, topicId: 8, newLifeOS: {} },
    setData(patch) { patches.push(patch); if (patch.newLifeOS) this.data.newLifeOS = patch.newLifeOS; } });
  return { page, requests, patches, account: value => { member = value; } };
}
const response = { code: 200, data: { tags: [{ id: 1 }], mapAnchors: [], resultCards: [] } };
for (const change of ['topic', 'activity', 'account', 'load', 'unload']) test('OS read rejects old ' + change, async () => {
  const h = load(); h.page.loadNewLifeOS();
  if (change === 'topic') h.page.data.topicId = 18;
  if (change === 'activity') h.page.data.activityId = 19;
  if (change === 'account') h.account('22');
  if (change === 'load') h.page._playLoadEpoch = 1;
  if (change === 'unload') h.page._playDisposed = true;
  const before = h.patches.length; h.requests[0].success(response);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.patches.length, before);
});
test('late OS error cannot hide a newer successful read', async () => {
  const h = load(); h.page.loadNewLifeOS(); h.page.loadNewLifeOS();
  h.requests[1].success(response); h.requests[0].fail({});
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.page.data.newLifeOS.tags, [{ id: 1 }]);
  assert.equal(h.page.data.newLifeOS.show, true);
});
