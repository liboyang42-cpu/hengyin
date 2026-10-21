'use strict';

// BL-207 官方邀约:已有列表时刷新失败要按来源给反馈——下拉失败轻提示、动作后回读失败提示未同步并清掉回读期望,
// onShow 静默回读失败不提示;首屏失败仍进错误态。
const assert = require('node:assert/strict');
const path = require('node:path');
const test = require('node:test');

const PAGE = path.resolve(__dirname, '../../pages/activity/official-inbox/index.js');

function setup() {
  let definition;
  const requests = [];
  const toasts = [];
  let stops = 0;
  global.getApp = () => ({
    sendRequest: (request) => requests.push(request),
    getRequestErrorMessage: (_error, fallback) => fallback,
    getUserRole: () => 'merchant',
    getUserType: () => 2,
  });
  global.wx = {
    getStorageSync: () => '',
    showModal: () => {},
    showToast: (t) => toasts.push(t.title),
    stopPullDownRefresh: () => { stops += 1; },
    navigateTo: () => {},
    setNavigationBarColor: () => {},
    setBackgroundColor: () => {},
    setBackgroundTextStyle: () => {},
  };
  global.Page = (config) => { definition = config; };
  delete require.cache[PAGE];
  require(PAGE);
  const page = Object.assign({}, definition);
  page.data = JSON.parse(JSON.stringify(definition.data));
  page.setData = (patch) => { Object.assign(page.data, patch); };
  const finish = (req, outcome) => { outcome(req); if (req.complete) req.complete(); };
  return { page, requests, toasts, finish, stops: () => stops };
}

const INVITE = { partyId: 21, partyType: 'MERCHANT', role: 'FULFILLMENT_MERCHANT', status: 'INVITED' };

function loaded() {
  const h = setup();
  h.page.fetch();
  h.finish(h.requests[0], (r) => r.success({ code: 200, data: [INVITE] }));
  assert.equal(h.page.data.invites.length, 1);
  return h;
}

test('下拉刷新失败:轻提示「刷新失败，稍后再试」并保留列表', () => {
  const h = loaded();
  h.page.onPullDownRefresh();
  h.finish(h.requests[1], (r) => r.fail({ errMsg: 'request:fail timeout' }));
  assert.deepEqual(h.toasts, ['刷新失败，稍后再试']);
  assert.equal(h.page.data.invites.length, 1);
  assert.equal(h.page.data.error, '');
  assert.equal(h.page.data.refreshing, false);
  assert.ok(h.stops() >= 2, '下拉失败也要收转圈');
});

test('动作成功后回读失败:提示「已提交，状态未同步」并清掉回读期望', () => {
  const h = loaded();
  h.page._submitAction({ partyId: 21, action: 'accept', organizerInvite: false, isAccept: true });
  h.finish(h.requests[1], (r) => r.success({ code: 200 }));
  assert.equal(h.requests[2].method, 'GET');
  h.finish(h.requests[2], (r) => r.success({ code: 500 }));
  assert.ok(h.toasts.includes('已提交，状态未同步，稍后下拉刷新'));
  assert.equal(h.page._expectReadback, null);
  assert.equal(h.page.data.invites[0].status, 'INVITED', '回读失败不假更新列表');
});

test('onShow 静默回读失败不提示;首屏失败仍进错误态', () => {
  const h = loaded();
  h.page.onShow();
  h.finish(h.requests[1], (r) => r.fail({ errMsg: 'request:fail' }));
  assert.deepEqual(h.toasts, []);
  assert.equal(h.page.data.invites.length, 1);

  const first = setup();
  first.page.onPullDownRefresh();
  first.finish(first.requests[0], (r) => r.fail({ errMsg: 'request:fail' }));
  assert.equal(first.page.data.error, '邀约加载失败');
  assert.deepEqual(first.toasts, []);
});

test('过期回包仍丢弃:被后发请求取代的下拉失败不提示', () => {
  const h = loaded();
  h.page.onPullDownRefresh();
  h.page.fetch();
  h.finish(h.requests[1], (r) => r.fail({ errMsg: 'request:fail' }));
  assert.deepEqual(h.toasts, []);
});
