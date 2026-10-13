'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../subpackageB/pages/im/chat/index.js');
function harness(seed) {
  let page, account = 1, failStorage = false;
  const storage = seed || new Map(), requests = [], tips = [];
  const wx = { getStorageSync: key => storage.get(key),
    setStorageSync: (key, rows) => { if (failStorage) throw Error('disk full'); storage.set(key, rows); },
    removeStorageSync: key => storage.delete(key), navigateBack() {}, redirectTo() {} };
  global.wx = wx;
  const app = { globalData: { authorization: 'session-one' }, getUserID: () => account, sendRequest: request => requests.push(request), tips: t => tips.push(t) };
  vm.runInNewContext(fs.readFileSync(process.env.IM_B_SOURCE || file, 'utf8'), { require: createRequire(file), getApp: () => app,
    Page: definition => { page = definition; }, wx, setTimeout() {}, clearTimeout() {}, setInterval() {}, clearInterval() {} });
  page.data = { ...page.data, myId: 1, conversationId: 26, loadState: 'ready', text: 'original draft', msgs: [], topicCache: {} };
  page.setData = patch => Object.entries(patch).forEach(([key, value]) => {
    if (key.startsWith('topicCache.')) page.data.topicCache[key.slice(11)] = value;
    else page.data[key] = value;
  });
  return { page, requests, storage, tips, account: value => { account = value; }, credential: value => { app.globalData.authorization = value; }, failStorage: () => { failStorage = true; } };
}
function committed(id, key) { return { id, senderId: 1, msgType: 1, content: 'original draft', clientMessageId: key }; }

test('storage failure blocks sending and preserves current draft', () => {
  const h = harness(); h.failStorage(); h.page.onSend();
  assert.equal(h.requests.length, 0); assert.equal(h.page.data.text, 'original draft');
  assert.equal(h.page.data.msgs.length, 0); assert.equal(h.tips.length, 1);
});

test('unknown resend first reads original receipt; missing/failed response never blindly resends', () => {
  const h = harness(); h.page.onSend(); const original = h.requests[0]; original.fail({});
  h.page.onResend({ currentTarget: { dataset: { index: 0 } } });
  assert.equal(h.requests[1].url, '/api/im/send-receipt');
  assert.equal(h.requests[1].data.client_message_id, original.data.client_message_id);
  h.requests[1].fail({}); assert.equal(h.requests.filter(r => r.url === '/api/im/send').length, 1);
  assert.equal(h.page.data.msgs[0].failed, true);
});

test('found receipt reconciles original bubble without a second write', () => {
  const h = harness(); h.page.onSend(); const key = h.requests[0].data.client_message_id; h.requests[0].fail({});
  h.page.onResend({ currentTarget: { dataset: { index: 0 } } });
  h.requests[1].success({ code: 200, data: { found: true, message: committed(61, key) } });
  assert.equal(h.requests.length, 2); assert.equal(h.page.data.msgs.length, 1);
  assert.equal(h.page.data.msgs[0].id, 61); assert.equal(h.page.data.msgs[0].failed, undefined);
});

test('authoritative not-found retries only the original immutable payload', () => {
  const h = harness(); h.page.onSend(); const first = JSON.parse(JSON.stringify(h.requests[0].data)); h.requests[0].fail({});
  h.page.setData({ text: 'new draft' }); h.page.onResend({ currentTarget: { dataset: { index: 0 } } });
  h.requests[1].success({ code: 200, data: { found: false } });
  assert.equal(h.requests[2].url, '/api/im/send');
  assert.deepEqual(JSON.parse(JSON.stringify(h.requests[2].data)), first);
  assert.equal(h.page.data.text, 'new draft');
});

test('reentry persists only original IDs and recovers found receipts without chat plaintext', () => {
  const first = harness(); first.page.onSend(); const key = first.requests[0].data.client_message_id;
  assert.ok(!JSON.stringify([...first.storage.values()]).includes('original draft'));
  const next = harness(first.storage); next.page.recoverPending();
  assert.equal(next.requests[0].url, '/api/im/send-receipt');
  next.requests[0].success({ code: 200, data: { found: true, message: committed(61, key) } });
  assert.equal(next.page.data.msgs[0].id, 61);
  assert.equal(next.requests.filter(r => r.url === '/api/im/send').length, 0);
});

test('cleared recovery records make late send success unable to rebuild local state', () => {
  const h = harness(); h.page.onSend(); const snapshot = JSON.stringify(h.page.data);
  h.storage.clear(); h.requests[0].success({ code: 200, data: committed(61, h.requests[0].data.client_message_id) });
  assert.equal(JSON.stringify(h.page.data), snapshot); assert.equal(h.storage.size, 0);
});

test('delete success invalidates outstanding message/send callbacks', () => {
  const h = harness(); h.page.onSend(); h.page.loadMessages(false);
  h.page._pendingClearId = 26;
  h.page.onDangerConfirm({ detail: { key: 'im.conversation.delete' } });
  h.requests.find(r => r.url === '/api/im/delete').success({ code: 200 });
  h.requests.find(r => r.url === '/api/im/messages').success({ code: 200, data: { list: [committed(99, 'old')] } });
  h.requests.find(r => r.url === '/api/im/send').success({ code: 200, data: committed(61, 'old-send') });
  assert.equal(h.page.data.msgs.length, 0); assert.equal(h.page._deleted, true);
});

test('switched account cannot apply old history, current load, polling or route results', () => {
  const h = harness(); h.page.setData({ hasMore: true, cursor: 80 }); h.page.loadMore(); h.page.loadMessages(false); h.page.pollNew();
  const before = JSON.stringify(h.page.data); h.account(2);
  h.requests.forEach(r => r.success({ code: 200, data: { list: [committed(99, 'other')], hasMore: false } }));
  assert.equal(JSON.stringify(h.page.data), before);
});

test('incremental pagination drains every missing segment and preserves displayed readTo', () => {
  const h = harness(); h.page.data.msgs = [committed(1, 'first'), committed(100, 'optimistic-new')]; h.page._syncAfter = 1;
  h.page.pollNew(); assert.equal(h.requests[0].data.after_id, 1);
  h.requests[0].success({ code: 200, data: { list: [committed(2, 'two'), committed(3, 'three')], nextAfter: 3, hasMore: true } });
  const next = h.requests.find(r => r.url === '/api/im/messages' && r.data.after_id === 3);
  assert.ok(next); next.success({ code: 200, data: { list: [committed(4, 'four')], nextAfter: 4, hasMore: false } });
  assert.deepEqual(h.page.data.msgs.map(m => m.id).sort((a,b) => a-b), [1,2,3,4,100]);
  assert.equal(h.page._syncAfter, 4);
  assert.deepEqual(h.requests.filter(r => r.url === '/api/im/read').map(r => r.data.read_to), [3,4]);
  assert.deepEqual(h.page.data.msgs.map(m => m.id), [1,2,3,4,100]);
});

test('private history route uses safe projection, never privileged normal details, and replaces old cached name', () => {
  const h = harness(); h.page.data.topicCache[8] = { name: 'old secret' };
  h.page._mergeMessages([{ id: 80, senderId: 2, msgType: 3, content: '路线已不可用', extraJson: '{"cardType":"route","topicId":8}' }]);
  assert.equal(h.page.data.topicCache[8].name, '路线已不可用');
  assert.equal(h.requests.length, 0); assert.equal(h.page.data.msgs[0].card.topicId, 8);
});

test('historical route revalidation hides cached names and drops older public response after private result', () => {
  const h = harness(); h.page._mergeMessages([{ id: 80, senderId: 2, msgType: 3, content: '[路线] former public', extraJson: '{"cardType":"route","topicId":8}' }]);
  h.page.refreshRouteProjections(); h.page.refreshRouteProjections();
  assert.equal(h.page.data.topicCache[8].name, '路线已不可用');
  h.requests[1].success({ code: 200, data: { list: [{ id: 80, senderId: 2, msgType: 3, content: '路线已不可用', extraJson: '{"cardType":"route","topicId":8}' }] } });
  h.requests[0].success({ code: 200, data: { list: [{ id: 80, senderId: 2, msgType: 3, content: '[路线] former public', extraJson: '{"cardType":"route","topicId":8}' }] } });
  assert.equal(h.page.data.topicCache[8].name, '路线已不可用');
});

test('same-account re-login with different request credential drops old callback', () => {
  const h = harness(); h.page.loadMessages(false); const before = JSON.stringify(h.page.data);
  h.credential('session-two');
  h.requests[0].success({ code: 200, data: { list: [committed(99, 'old-session')] } });
  assert.equal(JSON.stringify(h.page.data), before);
});
