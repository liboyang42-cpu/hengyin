'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createClient } = require('../../utils/community-post-api');
const post = (version = 1) => ({ post: { id: '77', authorId: '1', version, body: 'visible', lifecycle: 'PUBLISHED' }, media: [] });
function harness() {
  let owner = '1', token = 'a';
  const calls = [], seen = [], app = { globalData: {}, getUserID: () => owner, getAuthorization: () => token,
    sendRequest(r) { calls.push(r); return { abort() {} }; } };
  const storage = new Map();
  const client = createClient(app, { getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, JSON.parse(JSON.stringify(value))),
    removeStorageSync: key => storage.delete(key) });
  return { app, calls, seen, client, account(id, auth) { owner = id; token = auth; },
    request(url, data, extra = {}) { return client.request({ url, data, success: r => seen.push(r), ...extra }); } };
}
test('comment reply preserves a Long parent ID and small legacy numeric IDs', () => {
  for (const [id, expected] of [['9007199254740993', '9007199254740993'], ['9223372036854775807', '9223372036854775807'], [5, 5], ['5', 5], [0, null]]) {
    const h = harness();
    h.request('/api/comment/add', { owner_id: 77, contents: 'reply', reply_id: id });
    assert.equal(h.calls.at(-1).data.parentId, expected);
  }
});
for (const id of [9007199254740992, '9223372036854775808', '1e3', -1, true, {}]) test('invalid reply target never sends: ' + String(id), () => {
  const h = harness(); h.request('/api/comment/add', { owner_id: 77, contents: 'reply', reply_id: id });
  assert.equal(h.calls.length, 0); assert.equal(h.seen[0].code, 409);
});
test('same member session change rejects old detail and cannot seed deletion cache', () => {
  const h = harness(); h.request('/api/creativesquare/info', { id: 77 }); const old = h.calls[0];
  h.account('1', 'b'); old.success({ code: 200, data: post() });
  assert.equal(h.seen.at(-1).code, 409);
  h.request('/api/creativesquare/delete', { id: 77 });
  assert.equal(h.calls.length, 1); assert.equal(h.seen.at(-1).code, 409);
});
test('A B A does not accept response from the earlier account generation', () => {
  const h = harness(); h.request('/api/creativesquare/info', { id: 77 }); const old = h.calls[0];
  h.account('2', 'b'); h.request('/api/creativesquare/info', { id: 77 });
  h.account('1', 'a'); h.request('/api/creativesquare/info', { id: 77 });
  h.calls.at(-1).success({ code: 200, data: post(9) });
  old.success({ code: 200, data: post(1) });
  h.request('/api/creativesquare/delete', { id: 77 });
  assert.equal(h.calls.at(-1).data.expectedVersion, 9);
});
test('old feed generation cannot overwrite object version used by a later delete', () => {
  const h = harness();
  h.request('/api/creativesquare/list', { pageNum: 1 }); const old = h.calls[0];
  h.request('/api/creativesquare/list', { pageNum: 1 });
  h.calls.at(-1).success({ code: 200, data: { items: [post(9)], hasMore: false } });
  old.success({ code: 200, data: { items: [post(1)], hasMore: true, nextCursor: 77 } });
  h.request('/api/creativesquare/delete', { id: 77 });
  assert.equal(h.calls.at(-1).data.expectedVersion, 9);
});
test('duplicate write callback invalidates the feed only once', () => {
  const h = harness(); h.request('/api/comment/add', { owner_id: 77, contents: 'reply' });
  const request = h.calls[0]; request.success({ code: 200, data: {} }); request.success({ code: 200, data: {} });
  assert.equal(h.app.globalData.squareListDirtyVersion, 1); assert.equal(h.seen.length, 1);
});
test('aborted and obsolete page requests never populate mutation caches', () => {
  for (const cancel of ['abort', 'current']) {
    const h = harness(); let current = true;
    const task = h.request('/api/creativesquare/info', { id: 77 }, { current: () => current });
    if (cancel === 'abort') task.abort(); else current = false;
    h.calls[0].success({ code: 200, data: post() });
    assert.equal(h.seen.length, 0);
    h.request('/api/creativesquare/delete', { id: 77 });
    assert.equal(h.calls.length, 1);
  }
});
test('confirmed delete invalidates earlier reads and removes cached mutation eligibility', () => {
  const h = harness(); h.request('/api/creativesquare/info', { id: 77 });
  h.calls[0].success({ code: 200, data: post() });
  h.request('/api/creativesquare/list', { pageNum: 1 }); const old = h.calls.at(-1);
  h.request('/api/creativesquare/delete', { id: 77 }); h.calls.at(-1).success({ code: 200 });
  old.success({ code: 200, data: { items: [post()], hasMore: false } });
  assert.equal(h.seen.at(-1).code, 409);
  const before = h.calls.length; h.request('/api/creativesquare/delete', { id: 77 });
  assert.equal(h.calls.length, before); assert.equal(h.seen.at(-1).code, 409);
});
test('obsolete caller cannot dispatch a write', () => {
  const h = harness(); h.request('/api/comment/add', { owner_id: 77, contents: 'old' }, { current: () => false });
  assert.equal(h.calls.length, 0);
});
test('publish pipeline cannot advance after same-member credential change', async () => {
  const h = harness(); h.request('/api/creativesquare/action', { contents: 'draft' });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.calls.length, 1);
  h.account('1', 'new-session');
  h.calls[0].success({ code: 200, data: {} });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.calls.length, 1); assert.equal(h.seen.length, 0);
});
test('failed write preserves the original request number and does not invalidate concurrent reads', () => {
  const h = harness(); h.request('/api/comment/add', { owner_id: 77, contents: 'same' });
  const first = h.calls[0]; first.fail({ errMsg: 'timeout' });
  h.request('/api/comment/add', { owner_id: 77, contents: 'same' });
  assert.equal(h.calls.at(-1).data.clientRequestId, first.data.clientRequestId);
  assert.equal(h.app.globalData.squareListDirtyVersion, undefined);
});
