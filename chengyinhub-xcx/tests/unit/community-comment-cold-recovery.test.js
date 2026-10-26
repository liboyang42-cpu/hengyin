'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createClient } = require('../../utils/community-post-api');
function setup(storage = new Map()) {
  const calls = [], results = [];
  let actor = '1';
  const wx = { getStorageSync: k => storage.get(k), setStorageSync: (k,v) => storage.set(k, JSON.parse(JSON.stringify(v))), removeStorageSync: k => storage.delete(k) };
  const app = { globalData: {}, getUserID: () => actor, sendRequest: r => { calls.push(r); return { abort() {} }; } };
  const client = createClient(app, wx);
  return { calls, results, client, wx, account: id => { actor = id; },
    send: (body = 'original', parent = 0) => client.request({ url: '/api/comment/add', data: { owner_id: '77', contents: body, reply_id: parent }, success: r => results.push(r), fail: r => results.push(r) }) };
}
test('cold client retries the same frozen comment key after an unknown response', () => {
  const storage = new Map(), a = setup(storage); a.send(); const key = a.calls[0].data.clientRequestId;
  a.calls[0].fail({ errMsg: 'timeout' });
  const b = setup(storage); b.send(); assert.equal(b.calls[0].data.clientRequestId, key);
  b.calls[0].success({ code: 200, data: {} });
  assert.equal(b.client.pendingComment('77'), null);
});
test('cold recovery refuses changed body or parent, retaining the original intent', () => {
  const storage = new Map(), a = setup(storage); a.send('original', '9007199254740993');
  const b = setup(storage); b.send('changed', '9007199254740993'); b.send('original', 12);
  assert.equal(b.calls.length, 0); assert.equal(b.results[0].code, 409);
  assert.equal(b.client.pendingComment('77').body, 'original');
  assert.equal(b.client.pendingComment('77').parentId, '9007199254740993');
});
test('storage failure prevents a network write and a second owner cannot recover the first payload', () => {
  const h = setup(); h.wx.setStorageSync = () => { throw Error('full'); }; h.send();
  assert.equal(h.calls.length, 0); assert.equal(h.results[0].code, 507);
  const a = setup(); a.send(); a.account('2'); assert.equal(a.client.pendingComment('77'), null);
});
test('a first explicit rejection allows corrected input, but rejection after UNKNOWN preserves the original', () => {
  const h = setup(); h.send(); h.calls[0].success({ code: 400 }); h.send('corrected');
  assert.equal(h.calls.length, 2);
  const storage = new Map(), a = setup(storage); a.send(); a.calls[0].fail({});
  const b = setup(storage); b.send(); b.calls[0].success({ code: 403 }); b.send('changed');
  assert.equal(b.calls.length, 1); assert.equal(b.client.pendingComment('77').body, 'original');
});
