'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../subpackageB/pages/im/list/index.js');
function mount() {
  let page, id = '71', token = 'session-a';
  const requests = [];
  const app = { globalData: {}, getUserID: () => id, getAuthorization: () => token,
    sendRequest: r => requests.push(r) };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Page: p => { page = p; }, getApp: () => app,
    wx: {}, require: createRequire(file), console });
  page.data = JSON.parse(JSON.stringify(page.data));
  page.setData = patch => Object.assign(page.data, patch);
  page.selectComponent = () => ({ close() {} });
  return { page, requests, account(next, auth) { id = next; token = auth; } };
}
const response = { code: 200, data: { received: [{ inviteId: '9007199254740993', status: 0 }], sent: [] } };
for (const transition of ['account', 'token', 'unload', 'hide']) {
  for (const result of ['success', 'failure']) test('invitation ' + result + ' ignored after ' + transition, () => {
    const h = mount(); h.page.loadInvites(); const old = h.requests.at(-1);
    if (transition === 'account') h.account('72', 'session-b');
    if (transition === 'token') h.account('71', 'session-renewed');
    if (transition === 'unload') h.page.onUnload();
    if (transition === 'hide') h.page.onHide();
    const before = JSON.stringify(h.page.data);
    if (result === 'success') old.success(response); else old.fail();
    assert.equal(JSON.stringify(h.page.data), before);
  });
}
test('same reader accepts exact invite ID once and ignores duplicate callbacks', () => {
  const h = mount(); h.page.loadInvites(); const request = h.requests.at(-1);
  request.success(response);
  assert.equal(h.page.data.invitesIn[0].inviteId, '9007199254740993');
  assert.equal(h.page.data.tabs[0].badge, 1);
  const before = JSON.stringify(h.page.data);
  request.fail(); request.success({ code: 200, data: { received: [], sent: [] } });
  assert.equal(JSON.stringify(h.page.data), before);
});
test('switching while initial conversation load is pending refreshes all private sources', () => {
  const h = mount(); h.page.onLoad(); const old = [...h.requests];
  h.account('72', 'session-b'); h.page.onShow();
  assert.equal(h.requests.filter(r => r.url === '/api/im/conversations').length, 2);
  assert.equal(h.requests.filter(r => r.url === '/api/coop/list').length, 2);
  const before = JSON.stringify(h.page.data);
  old.find(r => r.url === '/api/coop/list').success(response);
  old.find(r => r.url === '/api/im/conversations').success({ code: 200, data: [{ conversationId: 8, unread: 7 }] });
  assert.equal(JSON.stringify(h.page.data), before);
});
test('account change clears old badge even while the new conversation request fails', () => {
  const h = mount(); h.page.onLoad();
  h.requests.find(r => r.url === '/api/coop/list').success(response);
  assert.equal(h.page.data.tabs[0].badge, 1);
  h.account('72', 'session-b'); h.page.loadConversations();
  assert.equal(h.page.data.invitesIn.length, 0);
  assert.equal(h.page.data.tabs[0].badge, 0);
  h.requests.at(-1).fail();
  assert.equal(h.page.data.tabs[0].badge, 0);
});
test('hide and resume before first response restarts reads; old reply cannot overwrite', () => {
  const h = mount(); h.page.onLoad(); const old = h.requests.find(r => r.url === '/api/coop/list');
  h.page.onHide(); h.page.onShow();
  assert.equal(h.requests.filter(r => r.url === '/api/coop/list').length, 2);
  h.requests.filter(r => r.url === '/api/coop/list').at(-1).success(response);
  const before = JSON.stringify(h.page.data); old.fail();
  assert.equal(JSON.stringify(h.page.data), before);
});
test('A to B to A does not revive an earlier invitation request', () => {
  const h = mount(); h.page.onLoad(); const old = h.requests.find(r => r.url === '/api/coop/list');
  h.account('72', 'session-b'); h.page.onShow();
  h.account('71', 'session-a'); h.page.onShow();
  h.requests.filter(r => r.url === '/api/coop/list').at(-1).success({ code: 200, data: { received: [], sent: [] } });
  const before = JSON.stringify(h.page.data); old.success(response);
  assert.equal(JSON.stringify(h.page.data), before);
});
test('same member with a new session rejects pending conversations and refreshes on show', () => {
  const h = mount(); h.page.onLoad(); const old = h.requests.find(r => r.url === '/api/im/conversations');
  h.account('71', 'session-new');
  const before = JSON.stringify(h.page.data);
  old.success({ code: 200, data: [{ conversationId: 9, unread: 5 }] });
  assert.equal(JSON.stringify(h.page.data), before);
  h.page.onShow();
  assert.equal(h.requests.filter(r => r.url === '/api/im/conversations').length, 2);
});
test('business rejection clears invitation projection and its badge; retry can recover', () => {
  const h = mount(); h.page.loadInvites(); h.requests.at(-1).success(response);
  h.page.loadInvites(); h.requests.at(-1).success({ code: 403 });
  assert.equal(h.page.data.invitesIn.length, 0); assert.equal(h.page.data.tabs[0].badge, 0);
  assert.ok(h.page.data.inviteError);
  h.page.loadInvites(); h.requests.at(-1).success(response);
  assert.equal(h.page.data.tabs[0].badge, 1); assert.equal(h.page.data.inviteError, '');
});
