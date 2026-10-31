'use strict';

// A-05(P2):详情页编辑/删除后返回广场列表,列表还是旧卡(列表 onShow 只重算横幅、从不重拉)。
// 修法:详情侧删/改成功后给上一页(列表)置脏标,列表 onShow 只在有脏标时刷一次首屏 ——
// 不是每次 onShow 全量重拉。
//
// 2026-10-29:广场列表页(pages/square/list)已删,列表侧用例随之删除;
// 详情侧仍钉:删除/编辑保存成功真的会调用上一页的置脏方法 / 落 app 级脏标(我的页帖文列表消费)。

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { createRequire } = require('node:module');
const path = require('node:path');
const vm = require('../helpers/ui-sandbox-vm.js');

const ROOT = path.resolve(__dirname, '../..');
const DETAIL_REL = 'pages/square/detail/index.js';
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

function loadDetail(prev, opts) {
  const requests = [];
  const toasts = [];
  let definition = null;
  const absolutePath = path.join(ROOT, DETAIL_REL);
  const app = {
    canUseCommunity: () => true, // Current-account server-open fixture.
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => 1,
    getAvatar: () => '', getNickname: () => '',
    getPageSize: () => 10, getTotalPage: () => 1,
    getRequestErrorMessage: (res, fallback) => fallback,
    sendRequest(options) { requests.push(options); return { abort() {}, aborted: false }; },
  };
  const wxApi = {
    getSystemInfoSync: () => ({ windowWidth: 375, windowHeight: 812, statusBarHeight: 20, safeAreaInsets: { bottom: 0 } }),
    getMenuButtonBoundingClientRect: () => ({ bottom: 64 }),
    hideTabBar() {}, setNavigationBarColor() {}, navigateBack(o) { if (o && o.fail) o.fail(); }, navigateTo() {}, redirectTo() {}, reLaunch() {},
    showToast(options) { toasts.push(options); }, hideToast() {}, showLoading() {}, hideLoading() {},
    createSelectorQuery: () => ({ select() { return this; }, selectViewport() { return this; }, boundingClientRect() { return this; }, fields() { return this; }, scrollOffset() { return this; }, exec(cb) { if (cb) cb([null]); } }),
  };
  vm.runInNewContext((opts && opts.source) || read(DETAIL_REL), {
    console,
    getApp: () => app,
    // A-RPT-5:分享直开时栈里只有详情本页(opts.share),没有上一页实例。
    getCurrentPages: () => (opts && opts.share ? [page] : [prev, page]),
    Page: (config) => { definition = config; },
    require: id => id.includes('community-post-api') ? require('../helpers/community-page-seam') : createRequire(absolutePath)(id),
    setTimeout() {}, clearTimeout() {}, setInterval() {}, clearInterval() {},
    wx: wxApi,
  }, { filename: DETAIL_REL });
  assert.ok(definition, DETAIL_REL + ' 必须注册 Page');
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done.call(this); },
  });
  return { page, requests, app, toasts };
}

test('详情:删除成功调用上一页 markSquareListDirty', () => {
  const prev = { marked: 0, markSquareListDirty() { this.marked += 1; } };
  const { page, requests } = loadDetail(prev);

  page.onLoad({ id: '77' });
  const info = requests.find((r) => r.url === '/api/creativesquare/info');
  info.success({ code: '200', data: { id: 77, memberId: 1, contents: '正文' } });
  page.deleteSquarePost();
  const del = requests.find((r) => r.url === '/api/creativesquare/delete');
  assert.ok(del, '删除必须发请求');
  del.success({ code: '200' });
  assert.equal(prev.marked, 1, '详情删帖成功必须给列表置脏标，否则返回后还是旧卡');
});

test('详情:编辑保存成功也置脏标(列表正文同样会过期)', () => {
  const source = read(DETAIL_REL);
  const onComposePublished = source.slice(source.indexOf('onComposePublished('), source.indexOf('markListDirty() {'));
  assert.match(onComposePublished, /this\.markListDirty\(\)/, '编辑保存成功必须置脏标');
});

test('A-14-1:评论点赞失败且后端无 msg 时,兜底文案不静默也不弹 undefined', () => {
  const { page, requests, toasts } = loadDetail(null, { share: true });
  page.setData({ list: [{ id: 55, isLiked: 0, likeCount: 1 }] });
  page.commentLikeClick({ currentTarget: { dataset: { index: 0 } } });
  const like = requests.find((r) => r.url === '/api/comment/like');
  assert.ok(like, '点赞要发请求');

  like.success({ code: 500 });
  assert.equal(toasts.length, 1, '失败必须有可见反馈');
  assert.equal(toasts[0].title, '操作失败，请重试');
  assert.doesNotMatch(String(toasts[0].title), /undefined/);

  // 负控:退回原始的一行 toast(res.msg),无 msg 时用户什么都看不到
  const source = read(DETAIL_REL).replace(
    "toast(app.getRequestErrorMessage(res, '操作失败，请重试'));",
    'toast(res.msg);',
  );
  assert.notEqual(source, read(DETAIL_REL), '负控锚点失效:保底文案未命中');
  const broken = loadDetail(null, { share: true, source });
  broken.page.setData({ list: [{ id: 55, isLiked: 0, likeCount: 1 }] });
  broken.page.commentLikeClick({ currentTarget: { dataset: { index: 0 } } });
  broken.requests.find((r) => r.url === '/api/comment/like').success({ code: 500 });
  assert.equal(broken.toasts.length, 0, '负控必须复现「失败还完全没提示」');
});

test('A-RPT-5:分享直开详情(栈里没有列表)时,置脏落 app 级脏标', () => {
  const { page, requests, app } = loadDetail(null, { share: true });
  page.onLoad({ id: '77' });
  const info = requests.find((r) => r.url === '/api/creativesquare/info');
  info.success({ code: '200', data: { id: 77, memberId: 1, contents: '正文' } });
  page.deleteSquarePost();
  requests.find((r) => r.url === '/api/creativesquare/delete').success({ code: '200' });
  assert.equal(app.globalData.squareListDirty, true,
    '分享直开没有上一页实例，脏标必须落到 app 级，否则之后进列表仍是旧卡');
});

test('A-RPT-5 负控:详情没上一页时去掉 app 级脏标,再进列表不刷 ⇒ 用例真红', () => {
  const source = read(DETAIL_REL);
  const broken = source.replace(
    '    if (app.globalData) app.globalData.squareListDirty = true;\n',
    '',
  );
  assert.notEqual(broken, source, '负控锚点失效:app 级置脏未命中');

  // 用同一份变异源加载详情,验证「分享直开」这一路真的不再产生任何脏标
  const { page, requests, app } = loadDetail(null, { share: true, source: broken });
  page.onLoad({ id: '77' });
  requests.find((r) => r.url === '/api/creativesquare/info').success({ code: '200', data: { id: 77, memberId: 1, contents: '正文' } });
  page.deleteSquarePost();
  requests.find((r) => r.url === '/api/creativesquare/delete').success({ code: '200' });
  assert.equal(app.globalData.squareListDirty, undefined, '负控必须真的把 app 级置脏摘掉');
});

const detailReads = h => h.requests.filter(r => r.url === '/api/creativesquare/info');
const commentReads = h => h.requests.filter(r => r.url === '/api/comment/list');
function cachedDetail() {
  const h = loadDetail({});
  Object.assign(h.page.data, { id: 56, detailState: 'ready', info: { id: 56, version: 1, contents: 'old' } });
  return h;
}
function finishDetail(request, data) {
  request.success({ code: 200, data });
  request.complete();
}
function finishComments(request, rows, hasMore = false) {
  request.success({ code: 200, data: { rows, hasMore } });
  request.complete();
}

test('cached outer detail removes a comment deleted from another detail', () => {
  const h = cachedDetail();
  Object.assign(h.page.data, { list: [{ id: 901, contents: 'deleted' }], commentsState: 'ready' });
  h.app.globalData.squareListDirtyVersion = 1;
  h.page.onShow();
  assert.equal(commentReads(h).length, 1);
  finishDetail(detailReads(h)[0], { id: 56, version: 2, commentCount: 0 });
  finishComments(commentReads(h)[0], []);
  assert.equal(h.page.data.list.length, 0);
  assert.equal(h.page.data.nodata, true);
  h.page.onShow();
  assert.equal(commentReads(h).length, 1);
});

test('comment read failure preserves old rows and retries independently of successful body read', () => {
  for (const failure of ['network', 'business', 'invalid']) {
    const h = cachedDetail();
    h.page.data.list = [{ id: 901 }];
    h.app.globalData.squareListDirtyVersion = 1;
    h.page.onShow();
    assert.equal(commentReads(h).length, 1, failure);
    finishDetail(detailReads(h)[0], { id: 56, version: 2 });
    const request = commentReads(h)[0];
    if (failure === 'network') request.fail({});
    else request.success(failure === 'business' ? { code: 409 } : { code: 200, data: { rows: ['invalid'] } });
    request.complete();
    assert.equal(h.page.data.list[0].id, 901, failure);
    h.page.onShow();
    assert.equal(detailReads(h).length, 1, failure);
    assert.equal(commentReads(h).length, 2, failure);
    finishComments(commentReads(h)[1], [{ id: 902 }]);
    assert.equal(h.page.data.list[0].id, 902, failure);
    h.page.onShow();
    assert.equal(commentReads(h).length, 2, failure);
  }
});

test('successful comment refresh is not repeated when the body refresh failed', () => {
  const h = cachedDetail();
  h.app.globalData.squareListDirtyVersion = 1;
  h.page.onShow();
  assert.equal(commentReads(h).length, 1);
  detailReads(h)[0].fail(); detailReads(h)[0].complete();
  finishComments(commentReads(h)[0], [{ id: 902 }]);
  h.page.onShow();
  assert.equal(detailReads(h).length, 2);
  assert.equal(commentReads(h).length, 1);
});

test('repeated show does not abort the current revision comment refresh', () => {
  const h = cachedDetail();
  h.app.globalData.squareListDirtyVersion = 1;
  h.page.onShow(); h.page.onShow();
  assert.equal(commentReads(h).length, 1);
  assert.equal(h.page._commentsRequestId, 1);
});

test('return after another mutation replaces an older comment read and rejects its late result', () => {
  const h = cachedDetail();
  h.app.globalData.squareListDirtyVersion = 1;
  h.page.getList(1, true);
  const old = commentReads(h)[0];
  h.app.globalData.squareListDirtyVersion = 2;
  h.page.onShow();
  assert.equal(commentReads(h).length, 2);
  finishComments(commentReads(h)[1], [{ id: 903 }]);
  finishComments(old, [{ id: 901 }]);
  assert.equal(h.page.data.list[0].id, 903);
});

test('changed revision replaces load-more instead of keeping deleted first-page comments', () => {
  const h = cachedDetail();
  h.page.getList(1);
  finishComments(commentReads(h)[0], [{ id: 901 }], true);
  h.app.globalData.squareListDirtyVersion = 1;
  h.page.getList(2);
  const more = commentReads(h)[1];
  h.page.onShow();
  assert.equal(commentReads(h).length, 3);
  assert.equal(commentReads(h)[2].data.pageNum, 1);
  finishComments(commentReads(h)[2], [{ id: 902 }]);
  finishComments(more, [{ id: 903 }]);
  assert.deepEqual(h.page.data.list.map(row => row.id), [902]);
  assert.equal(h.page.data.page_no, 1);
});

test('new mutation during comment read remains pending until a subsequent return', () => {
  const h = cachedDetail();
  h.app.globalData.squareListDirtyVersion = 1;
  h.page.onShow();
  assert.equal(commentReads(h).length, 1);
  h.app.globalData.squareListDirtyVersion = 2;
  finishComments(commentReads(h)[0], [{ id: 902 }]);
  h.page.onShow();
  assert.equal(commentReads(h).length, 2);
});

test('page-two response never marks older first-page comments as current', () => {
  const h = cachedDetail();
  h.page.getList(1);
  finishComments(commentReads(h)[0], [{ id: 901 }], true);
  h.app.globalData.squareListDirtyVersion = 1;
  h.page.getList(2);
  finishComments(commentReads(h)[1], [{ id: 902 }]);
  h.page.onShow();
  assert.equal(commentReads(h).length, 3);
  assert.equal(commentReads(h)[2].data.pageNum, 1);
});

test('unchanged closed or editing detail does not start automatic comment reads', () => {
  for (const state of ['unchanged', 'missing', 'unloaded', 'compose']) {
    const h = cachedDetail();
    if (state !== 'unchanged') h.app.globalData.squareListDirtyVersion = 1;
    if (state === 'missing') h.page.data.id = null;
    if (state === 'unloaded') h.page._unloaded = true;
    if (state === 'compose') h.page.data.composeShow = true;
    h.page.onShow();
    assert.equal(commentReads(h).length, 0, state);
  }
});

test('cached outer detail reads the saved body once after another detail edits', () => {
  const h = cachedDetail();
  h.app.globalData.squareListDirtyVersion = 1;
  h.page.onShow();
  h.page.onShow();
  assert.equal(detailReads(h).length, 1);
  finishDetail(detailReads(h)[0], { id: 56, version: 2, contents: 'new' });
  assert.equal(h.page.data.info.contents, 'new');
  h.page.onShow();
  assert.equal(detailReads(h).length, 1);
});

test('failed detail refresh retains the old body and retries on return', () => {
  const h = cachedDetail();
  h.app.globalData.squareListDirtyVersion = 1;
  h.page.onShow();
  const request = detailReads(h)[0];
  request.fail();
  request.complete();
  assert.equal(h.page.data.info.contents, 'old');
  h.page.onShow();
  assert.equal(detailReads(h).length, 2);
});

test('business failure leaves the detail revision pending', () => {
  const h = cachedDetail();
  h.app.globalData.squareListDirtyVersion = 1;
  h.page.onShow();
  const request = detailReads(h)[0];
  request.success({ code: 409, msg: 'conflict' });
  request.complete();
  h.page.onShow();
  assert.equal(detailReads(h).length, 2);
});

test('a newer revision arriving during a request is refreshed on the next return', () => {
  const h = cachedDetail();
  h.app.globalData.squareListDirtyVersion = 1;
  h.page.onShow();
  h.app.globalData.squareListDirtyVersion = 2;
  finishDetail(detailReads(h)[0], { id: 56, version: 2, contents: 'first' });
  h.page.onShow();
  assert.equal(detailReads(h).length, 2);
  finishDetail(detailReads(h)[1], { id: 56, version: 3, contents: 'second' });
  h.page.onShow();
  assert.equal(detailReads(h).length, 2);
});

test('unchanged detail never fetches just because it is shown', () => {
  const h = cachedDetail();
  h.page.onShow();
  h.page.onShow();
  assert.equal(detailReads(h).length, 0);
});

test('missing id unloaded page and open composer do not trigger background reads', () => {
  for (const state of ['missing', 'unloaded', 'compose']) {
    const h = cachedDetail();
    h.app.globalData.squareListDirtyVersion = 1;
    if (state === 'missing') h.page.data.id = null;
    if (state === 'unloaded') h.page._unloaded = true;
    if (state === 'compose') h.page.data.composeShow = true;
    h.page.onShow();
    assert.equal(detailReads(h).length, 0, state);
  }
});
