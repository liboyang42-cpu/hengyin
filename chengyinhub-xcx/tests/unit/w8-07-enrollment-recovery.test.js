'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { platform, receipt } = require('./helpers/team-event-platform');
const { createRecovery } = require('../../utils/team-event-command');
function boot(kind, wx = platform()) {
  let uid = '71', token = 'a', definition;
  const requests = [];
  const app = { getRequestErrorMessage: (res, fallback) => fallback, globalData: {}, getUserID: () => uid, getAuthorization: () => token,
    sendRequest: r => requests.push(r) };
  Object.assign(wx, { setNavigationBarColor() {}, getSystemInfoSync: () => ({}), showToast() {}, requestSubscribeMessage() {} });
  global.wx = wx; global.getApp = () => app; global.Page = p => { definition = p; };
  const file = path.resolve(__dirname, '../../pages', kind, 'index.js');
  delete require.cache[file]; require(file);
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)), setData(p) { Object.assign(this.data, p); } };
  page.onLoad(kind === 'team/join' ? { code: 'opaque-invite' } : { id: '9007199254740993' });
  return { page, requests, wx, app, switchAccount() { uid = '72'; token = 'b'; } };
}
function invite(h) { h.requests[0].success({ code: 200, data: { team: { id: '9007199254740993', status: 0 }, joined: false } }); }
test('join persists exact target and invitation; failed read on reentry does not remove recovery', async () => {
  const h = boot('team/join'); invite(h); await h.page.join();
  const original = h.requests.at(-1); assert.equal(original.url, '/api/team/command');
  assert.deepEqual(Object.keys(JSON.parse(original.data)).sort(), ['action','inviteCode','requestId','targetId']);
  assert.equal(JSON.parse(original.data).targetId, '9007199254740993'); original.fail({}); h.page.onUnload();
  const next = boot('team/join', h.wx); next.requests[0].success({ code: 500 });
  assert.equal(next.page.data.team, null); assert.equal(next.page.data.commandRecovery.state, 'UNKNOWN');
  next.page.retryCommand(); assert.equal(next.requests.at(-1).data, original.data);
  next.requests.at(-1).fail({}); next.page.cancelCommand();
  assert.equal(next.requests.at(-1).data, original.data);
  next.requests.at(-1).success(receipt(original, 'CANCELLED'));
  assert.equal(next.page.data.commandRecovery.state, 'CANCELLED');
});
test('join terminal triggers fresh membership read, never assumes current membership from old receipt', async () => {
  const h = boot('team/join'); invite(h); await h.page.join(); const command = h.requests.at(-1);
  command.success(receipt(command, 'APPLIED')); assert.equal(h.requests.at(-1).url, '/api/team/info');
  assert.equal(h.page.data.joined, false);
  h.requests.at(-1).success({ code: 200, data: { team: { id: '9007199254740993' }, joined: false } });
  assert.equal(h.page.data.joined, false); assert.equal(h.page.data.commandRecovery.state, 'APPLIED');
  await h.page.join(); assert.equal(h.requests.filter(r => r.url === '/api/team/command').length, 1);
});
test('join pending request and late callback cannot cross accounts', async () => {
  const h = boot('team/join'); invite(h); await h.page.join(); const command = h.requests.at(-1);
  h.switchAccount(); h.page.onShow(); const count = h.requests.length;
  command.success(receipt(command, 'APPLIED'));
  assert.equal(h.requests.length, count); assert.equal(h.page.data.commandRecovery.state, '');
  assert.equal(h.page.data.joined, false);
});
test('signup uses player receipt route and keeps same request across unknown and reentry', async () => {
  const h = boot('activity/official-detail'); await h.page.doSignup(); const command = h.requests.at(-1);
  assert.equal(command.url, '/api/official/signup-command');
  assert.equal(JSON.parse(command.data).targetId, '9007199254740993');
  command.fail({}); h.page.queryCommand(); const query = h.requests.at(-1);
  query.success({ code: 200, data: { requestId: JSON.parse(command.data).requestId, state: 'UNKNOWN' } });
  await h.page.doSignup(); assert.equal(h.requests.length, 3); h.page.onUnload();
  const next = boot('activity/official-detail', h.wx); next.page.retryCommand();
  assert.equal(next.requests.at(-1).data, command.data);
  next.requests.at(-1).success(receipt(command, 'REJECTED'));
  assert.equal(next.page.data.commandRecovery.state, 'REJECTED');
});
test('signup late response after switching account does not refresh or confirm another user', async () => {
  const h = boot('activity/official-detail'); await h.page.doSignup(); const command = h.requests.at(-1);
  h.switchAccount(); h.page.onShow(); const count = h.requests.length;
  command.success(receipt(command, 'APPLIED'));
  assert.equal(h.requests.length, count); assert.equal(h.page.data.commandRecovery.state, '');
});
test('public application freezes original message and does not replace it after negative query', async () => {
  const requests = [], wx = platform();
  const app = { getUserID: () => '71', getAuthorization: () => 'a', sendRequest: r => requests.push(r) };
  const open = () => createRecovery({ app, platform: wx, domain: 'TEAM', targetId: '9', changed() {}, terminal() {} });
  let recovery = open(); await recovery.start('apply', { message: '原留言' });
  const original = requests[0]; original.fail({}); recovery.query();
  requests[1].success({ code: 200, data: { requestId: JSON.parse(original.data).requestId, state: 'UNKNOWN' } });
  await recovery.start('apply', { message: '新留言' }); assert.equal(requests.length, 2);
  recovery.dispose(); recovery = open(); recovery.retry(); assert.equal(requests[2].data, original.data);
});
