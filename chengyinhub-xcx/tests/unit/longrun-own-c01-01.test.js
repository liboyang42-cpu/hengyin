'use strict';
// Authored for deferred execution; NOT_RUN under the source-only protocol.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition;
  const requests = [], writes = [];
  global.getApp = () => ({ getUserID: () => '11', sendRequest: r => requests.push(r) });
  global.Page = value => { definition = value; };
  global.wx = { removeStorageSync() {}, setStorageSync: (key, value) => writes.push([key, value]), showToast() {} };
  const file = path.resolve(__dirname, '../../pages/play/index.js');
  delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: '9', topicId: '8' }, _pendingInviteCode: 'fixture-invite' });
  return { page, requests, writes };
}
test('late invitation cannot write another topic cache or start another entry load', async () => {
  const h = load();
  const pending = h.page._consumeInviteCode();
  h.page.data.topicId = '18';
  h.requests[0].success({ code: 200, data: { id: 99 } });
  assert.equal(await pending, false);
  assert.deepEqual(h.writes, []);
});
test('current invitation applies once to its original topic cache', async () => {
  const h = load();
  const pending = h.page._consumeInviteCode();
  h.requests[0].success({ code: 200, data: { id: 99 } });
  h.requests[0].success({ code: 200, data: { id: 100 } });
  assert.equal(await pending, true);
  assert.deepEqual(h.writes, [['circle_session_m11_t8', 99]]);
});
test('older invitation response is ignored after a newer invite starts', async () => {
  const h = load();
  const first = h.page._consumeInviteCode();
  h.page._pendingInviteCode = 'new-fixture-invite';
  const second = h.page._consumeInviteCode();
  h.requests[1].success({ code: 200, data: { id: 100 } });
  h.requests[0].success({ code: 200, data: { id: 99 } });
  assert.equal(await first, false); assert.equal(await second, true);
  assert.deepEqual(h.writes, [['circle_session_m11_t8', 100]]);
});
