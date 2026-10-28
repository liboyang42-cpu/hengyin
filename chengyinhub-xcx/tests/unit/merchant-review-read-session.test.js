'use strict';
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const ROOT = path.resolve(__dirname, '../..');
const model = require('../../pages/merchant/reviews/view-model.js');
const flush = () => new Promise(resolve => setImmediate(resolve));

function harness(mode = 'manage', initialOwner = '7') {
  let definition, owner = initialOwner;
  const requests = [], logins = [];
  const app = { globalData: {}, getUserID: () => owner,
    getSessionManager: () => ({ ensureSession: () => new Promise(resolve => logins.push(resolve)) }),
    sendRequest(options) { requests.push(options); },
    getRequestErrorMessage: (response, fallback) => response && response.msg || fallback };
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'pages/merchant/reviews/index.js'), 'utf8'), {
    Page(value) { definition = value; }, getApp: () => app,
    require(name) {
      if (name === './reply-recovery') return require('../../pages/merchant/reviews/reply-recovery.js');
      if (name.includes('view-model')) return model;
      if (name.includes('ensure-session')) return require('../../pages/merchant/utils/session/ensure-session.js');
      if (name.includes('merchant-theme')) return { merchantPageShow() {}, merchantPageRestore() {} };
      if (name.includes('toast')) return {};
      if (name.includes('media-preview')) return { open() {} };
      throw new Error(name);
    }, wx: {}, getCurrentPages: () => [],
  });
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch); } };
  page.data.mode = mode; page._pageNum = 1; page._merchantRowId = 12;
  return { page, requests, logins, owner(value) { owner = value; } };
}
function response(mode, id = 71) {
  return { code: 200, data: { mode, pageNum: 1, pageSize: 20, total: 1, hasMore: false,
    items: [{ id, version: 0, rating: 5, status: 'VISIBLE', imageUrls: [],
      verifiedRedemption: true, canReply: mode === 'manage', canReport: true }] } };
}

for (const method of ['enterManage', 'recoverSession']) for (const lifecycle of ['onHide', 'onUnload']) {
  test(`口碑 ${method} 登录晚回包在 ${lifecycle} 后不读取或覆盖页面`, async () => {
    const h = harness('manage', method === 'enterManage' ? '' : '7');
    h.page[method](); h.page[lifecycle](); const before = JSON.stringify(h.page.data);
    h.owner('7'); h.logins[0]({ ok: true }); await flush();
    assert.equal(h.requests.length, 0); assert.equal(JSON.stringify(h.page.data), before);
  });
}
test('口碑隐藏后返回只继续新前台入口；旧登录回包不能重复读取', async () => {
  const h = harness('manage', ''); h.page.enterManage(); h.page.onHide(); h.page.onShow();
  h.owner('7'); h.logins[0]({ ok: true }); await flush(); assert.equal(h.requests.length, 0);
  h.logins[1]({ ok: true }); await flush(); assert.equal(h.requests.length, 1);
});
test('口碑续期期间换号，旧续期不发起新账号私有读取', async () => {
  const h = harness(); h.page.recoverSession(); h.owner('8'); h.logins[0]({ ok: true }); await flush();
  assert.equal(h.requests.length, 0);
});
for (const mode of ['manage', 'public']) test(`${mode} 口碑旧账号回包不能覆盖新账号`, () => {
  const h = harness(mode); h.page.loadReviews(false); h.owner('8'); h.requests[0].success(response(mode));
  assert.equal(h.page.data.items.length, 0);
});
test('口碑刷新旧响应不能覆盖最新响应，离页旧错误也不能覆盖状态', () => {
  const h = harness(); h.page.loadReviews(false); h.page.loadReviews(false);
  h.requests[1].success(response('manage', 72)); h.requests[0].success(response('manage', 71));
  assert.equal(h.page.data.items[0].id, '72');
  h.page.loadReviews(false); h.page.onUnload(); const before = JSON.stringify(h.page.data);
  h.requests[2].fail({ code: 500 }); assert.equal(JSON.stringify(h.page.data), before);
});
test('口碑账号变更立即清旧管理数据和回复草稿，返回页重新确认当前身份', () => {
  const h = harness(); h.page.loadReviews(false); h.requests[0].success(response('manage'));
  h.page.data.replyReviewId = 71; h.page.data.replyContent = '旧账号回复'; h.page.data.pendingReplyCount = 9;
  h.owner('8'); h.page.onShow();
  assert.equal(h.requests.length, 2); assert.equal(h.page.data.items.length, 0);
  assert.equal(h.page.data.replyContent, ''); assert.equal(h.page.data.replyReviewId, null);
  assert.equal(h.page.data.pendingReplyCount, 0);
});
test('匿名公开口碑仍正常读取；管理入口匿名先登录，当前失败仍明确可重试', async () => {
  const publicPage = harness('public', ''); publicPage.page.loadReviews(false);
  assert.equal(publicPage.logins.length, 0); assert.match(publicPage.requests[0].url, /reviews\/public/);
  publicPage.requests[0].success(response('public')); assert.equal(publicPage.page.data.items.length, 1);
  const h = harness('manage', ''); h.page.loadReviews(false); assert.equal(h.requests.length, 0);
  h.logins[0]({ ok: false }); await flush(); assert.match(h.page.data.errorMessage, /登录失败/);
});
