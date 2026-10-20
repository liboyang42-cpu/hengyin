const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ROOT = path.resolve(__dirname, '../..');
const flush = () => new Promise(resolve => setImmediate(resolve));
function mount(initialOwner = '', initialToken = '') {
  let owner = initialOwner, token = initialToken, definition;
  const requests = [], logins = [];
  const app = { globalData: {}, isAppReady: true, getUserID: () => owner,
    getAuthorization: () => token, getRequestErrorMessage: (r, f) => r && r.msg || f,
    sendRequest(options) { requests.push(options); return { abort() {} }; },
    firstLogin: () => new Promise(resolve => logins.push(resolve)),
    reLogin: () => new Promise(resolve => logins.push(resolve)) };
  global.getApp = () => app; global.Page = value => { definition = value; }; global.wx = {};
  const file = path.join(ROOT, 'subpackageMember/complaint/index.js');
  delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done(); } });
  return { page, app, requests, logins, identity(id, auth) { owner = id; token = auth; } };
}
function topicResponse(request, name = '本人活动') {
  request.success({ code: 200, data: [{ topicId: 11, topicName: name }] });
}
function fillDraft(h) {
  Object.assign(h.page.data, { reason: '我填写的投诉原因', contact: '本人联系方式', typeIndex: 1 });
}
for (const [owner, token] of [['', ''], ['7', ''], ['', 'Bearer unit-fixture']]) {
  test(`投诉入口缺完整正常会话不读私有topics/history (${owner || 'anon'}/${!!token})`, () => {
    const h = mount(owner, token); h.page.onLoad(); h.page.onShow();
    assert.equal(h.requests.length, 0); assert.equal(h.page.data.readAccess, 'login');
    assert.equal(h.page.data.loading, false); assert.equal(h.page.data.canSubmit, false);
  });
}
test('正常本人会话首进只读取各一次，活动和公开处理进展正常回填', () => {
  const h = mount('7', 'Bearer unit-fixture'); h.page.onLoad(); h.page.onShow();
  assert.deepEqual(h.requests.map(r => r.url), ['/api/coop/complaint/topics', '/api/coop/complaint/mine']);
  topicResponse(h.requests[0]); h.requests[1].success({ code: 200, data: [{ id: 4, topicName: '本人活动', statusText: '处理中', internalDeductAmount: 100 }] });
  assert.equal(h.page.data.topics[0].name, '本人活动'); assert.equal(h.page.data.history[0].statusText, '处理中');
  assert.equal(h.page.data.history[0].internalDeductAmount, undefined);
});
test('可见登录入口单飞，正常登录成功才双重读；拒绝仍有入口', async () => {
  const h = mount(); h.page.onLoad(); h.page.loginToRead(); h.page.loginToRead();
  assert.equal(h.logins.length, 1); h.logins[0]({ ok: false }); await flush();
  assert.equal(h.requests.length, 0); assert.equal(h.page.data.readAccess, 'login');
  h.page.loginToRead(); assert.equal(h.logins.length, 2);
  h.identity('7', 'Bearer new-unit-fixture'); h.logins[1]({ ok: true, isCurrentSession: () => true }); await flush();
  assert.equal(h.requests.length, 2); assert.equal(h.page.data.readAccess, '');
});
test('同步登录失败可再次尝试，不丢可见入口或假装成功', async () => {
  const h = mount(); h.page.onLoad(); h.app.firstLogin = () => { throw new Error('unavailable'); };
  assert.doesNotThrow(() => h.page.loginToRead()); assert.equal(h.page.data.readLoginPending, false);
  assert.equal(h.page.data.readAccess, 'login'); assert.equal(h.requests.length, 0);
});
test('隐藏前旧登录完成不能解锁返回后的新登录单飞', async () => {
  const h = mount(); h.page.onLoad(); h.page.loginToRead(); h.page.onHide(); h.page.onShow(); h.page.loginToRead();
  assert.equal(h.logins.length, 2); h.logins[0]({ ok: false }); await flush(); h.page.loginToRead();
  assert.equal(h.logins.length, 2); assert.equal(h.page.data.readLoginPending, true);
  h.logins[1]({ ok: false }); await flush(); assert.equal(h.page.data.readLoginPending, false);
});
test('同owner断网与隐藏返回保留reason/contact，旧回包不覆盖', () => {
  const h = mount('7', 'Bearer unit-fixture'); h.page.onLoad(); fillDraft(h);
  h.requests[0].fail({ errMsg: 'offline' }); assert.equal(h.page.data.readAccess, '');
  assert.equal(h.page.data.reason, '我填写的投诉原因'); assert.equal(h.page.data.contact, '本人联系方式');
  h.page.onHide(); topicResponse(h.requests[0], '迟到活动');
  assert.equal(h.page.data.topics.length, 0); h.page.onShow();
  assert.equal(h.page.data.reason, '我填写的投诉原因'); assert.equal(h.page.data.contact, '本人联系方式');
  assert.equal(h.requests.length, 4);
});
test('同owner续期失败再正常登录保留草稿，清空私有列表与旧选择', async () => {
  const h = mount('7', 'Bearer unit-fixture'); h.page.onLoad(); topicResponse(h.requests[0]); fillDraft(h);
  Object.assign(h.page.data, { topicIndex: 0, canSubmit: true });
  h.identity('', ''); h.requests[1].fail({ code: 401 });
  assert.equal(h.page.data.readAccess, 'login'); assert.equal(h.page.data.topics.length, 0);
  assert.equal(h.page.data.topicIndex, -1); assert.equal(h.page.data.canSubmit, false);
  h.page.loginToRead(); h.identity('7', 'Bearer renewed-unit-fixture'); h.logins[0]({ ok: true }); await flush();
  assert.equal(h.page.data.reason, '我填写的投诉原因'); assert.equal(h.page.data.contact, '本人联系方式'); assert.equal(h.requests.length, 4);
});
test('真实换号清除前账号PII与选择，旧topics/history不能覆写新账号', () => {
  const h = mount('7', 'Bearer unit-fixture'); h.page.onLoad(); fillDraft(h);
  h.identity('8', 'Bearer other-unit-fixture'); h.page.onShow();
  assert.equal(h.page.data.reason, ''); assert.equal(h.page.data.contact, ''); assert.equal(h.page.data.typeIndex, -1);
  assert.equal(h.page.data.topicIndex, -1); assert.equal(h.page.data.canSubmit, false);
  topicResponse(h.requests[2], '新账号活动'); topicResponse(h.requests[0], '前账号活动');
  h.requests[1].success({ code: 200, data: [{ id: 5, reason: '前账号投诉内容' }] });
  assert.equal(h.page.data.topics[0].name, '新账号活动'); assert.equal(h.page.data.history.length, 0);
});
test('同owner换token与卸载使旧回包失效，隐藏中的登录成功不启动读', async () => {
  const h = mount('7', 'Bearer unit-fixture'); h.page.onLoad();
  h.identity('7', 'Bearer replacement-unit-fixture'); topicResponse(h.requests[0], '旧token'); assert.equal(h.page.data.topics.length, 0);
  h.page.onUnload(); h.requests[1].success({ code: 200, data: [{ id: 6 }] }); assert.equal(h.page.data.history.length, 0);
  const g = mount(); g.page.onLoad(); g.page.loginToRead(); g.page.onHide();
  g.identity('7', 'Bearer unit-fixture'); g.logins[0]({ ok: true }); await flush(); assert.equal(g.requests.length, 0);
});
test('切号后的旧表单不能发投诉提交，普通本人提交逻辑另由原契约保持', () => {
  const h = mount('7', 'Bearer unit-fixture'); h.page.onLoad(); topicResponse(h.requests[0]); fillDraft(h);
  Object.assign(h.page.data, { topicIndex: 0, canSubmit: true }); h.identity('8', 'Bearer other-unit-fixture');
  h.page.submit(); assert.equal(h.requests.filter(r => r.url.endsWith('/report')).length, 0);
  assert.equal(h.page.data.contact, ''); assert.equal(h.page.data.canSubmit, false);
});
test('游客正常WXML主区有明确登录CTA，历史区不再重复无操作的报错', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'subpackageMember/complaint/index.wxml'), 'utf8');
  assert.match(wxml, /<cy-empty[^>]*wx:if="\{\{readAccess === 'login'\}\}"[^>]*title="登录后发起投诉与查看进展"[^>]*bind:cta="loginToRead"/);
  assert.match(wxml, /class="cp-history"[^>]*wx:if="\{\{!readAccess\}\}"/);
});
