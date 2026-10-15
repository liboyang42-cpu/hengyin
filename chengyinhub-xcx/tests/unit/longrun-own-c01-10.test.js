'use strict';
// Authored, NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition, member = '11';
  const requests = [], applied = [], notices = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => member, sendRequest: r => requests.push(r) });
  global.Page = v => { definition = v; };
  global.wx = {};
  const file = path.resolve(__dirname, '../../pages/play/index.js');
  delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8 },
    _applyRouteState: value => { applied.push(value); return true; },
    _failClosedRouteState: message => notices.push(message) });
  return { page, requests, applied, notices, account: value => { member = value; } };
}
for (const change of ['activity', 'account', 'load', 'unload']) test('route GET ignores old ' + change, async () => {
  const h = load();
  const pending = h.page.refreshRouteState();
  if (change === 'activity') h.page.data.activityId = 19;
  if (change === 'account') h.account('22');
  if (change === 'load') h.page._playLoadEpoch = 1;
  if (change === 'unload') h.page._playUnloaded = true;
  h.requests[0].success({ code: 200, data: { revision: 3 } });
  assert.equal(await pending, false);
  assert.deepEqual(h.applied, []);
});
for (const oldOutcome of ['success', 'fail']) test('older route ' + oldOutcome + ' cannot override current refresh', async () => {
  const h = load();
  const old = h.page.refreshRouteState();
  const current = h.page.refreshRouteState();
  h.requests[1].success({ code: 200, data: { revision: 12 } });
  assert.equal(await current, true);
  if (oldOutcome === 'success') h.requests[0].success({ code: 200, data: { revision: 10 } });
  else h.requests[0].fail({});
  assert.equal(await old, false);
  assert.deepEqual(h.applied, [{ revision: 12 }]);
  assert.equal(h.notices.length, 2, 'only the two initial loading notices are expected');
});
