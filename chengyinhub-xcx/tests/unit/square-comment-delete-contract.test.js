'use strict';

// A-04(P2):自己的评论删不掉 —— 后端 /api/comment/delete 早已存在且带 owner 校验,
// 小程序全仓零入口。本契约钉三件事:
//   ① 渲染层:本人评论行有删除入口(非本人只留举报);
//   ② 行为层:过确认闸后才发 /api/comment/delete,成功后本地摘行;
//   ③ 入口闸:别人的评论连确认框都不弹(后端仍会二次校验)。
// 负控:去掉归属闸 / 变异删除端点,对应断言必须真红。

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { createRequire } = require('node:module');
const path = require('node:path');
const vm = require('../helpers/ui-sandbox-vm.js');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const PAGE_REL = 'pages/square/detail/index.js';

function loadPage(opts) {
  const requests = [];
  const modalCalls = [];
  let definition;
  const absolutePath = path.join(ROOT, PAGE_REL);
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => 1,
    canUseCommunity: () => true, // Current-account server-open fixture (detail page is gated since F-L5C-06).
    getAvatar: () => '',
    getNickname: () => '',
    getPageSize: () => 10,
    getTotalPage: (total, size) => Math.ceil(Number(total || 0) / size),
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    sendRequest(options) {
      requests.push(options);
      return { aborted: false, abort() { this.aborted = true; } };
    },
  };
  const wxApi = {
    getSystemInfoSync: () => ({ windowWidth: 375, windowHeight: 812, statusBarHeight: 20, safeAreaInsets: { bottom: 0 } }),
    getMenuButtonBoundingClientRect: () => ({ bottom: 64 }),
    hideTabBar() {},
    navigateBack(options) { if (options && options.fail) options.fail(); },
    navigateTo() {},
    redirectTo() {},
    reLaunch() {},
    pageScrollTo() {},
    setClipboardData() {},
    previewImage() {},
    stopPullDownRefresh() {},
    showModal(options) {
      // 本文件的用例自己驱动「确认/取消」,沙箱里的弹层一律不自动确认
      modalCalls.push(options);
    },
    showToast() {}, hideToast() {}, showLoading() {}, hideLoading() {},
    createSelectorQuery: () => ({ select() { return this; }, selectViewport() { return this; }, boundingClientRect() { return this; }, fields() { return this; }, scrollOffset() { return this; }, exec(cb) { if (cb) cb([null]); } }),
  };
  const source = (opts && opts.source) || read(PAGE_REL);
  vm.runInNewContext(source, {
    console,
    getApp: () => app,
    getCurrentPages: () => (opts && opts.pages) || [],
    Page: (config) => { definition = config; },
    require: (id) => id.includes('community-post-api') ? require('../helpers/community-page-seam') : createRequire(absolutePath)(id),
    setTimeout() {}, clearTimeout() {}, setInterval() {}, clearInterval() {},
    wx: wxApi,
  }, { filename: absolutePath });

  assert.ok(definition, PAGE_REL + ' 必须注册 Page');
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) {
      Object.assign(this.data, patch);
      if (done) done.call(this);
    },
  });
  return { page, requests, modalCalls, app };
}

function readyWithComments(h, over) {
  h.page.onLoad({ id: '77' });
  const info = h.requests.find((r) => r.url === '/api/creativesquare/info');
  assert.ok(info, '详情必须先请求 info');
  info.success({ code: '200', data: Object.assign({ id: 77, memberId: 1, contents: '正文', commentCount: 2 }, over || {}) });
  if (info.complete) info.complete();
  const comments = h.requests.find((r) => r.url === '/api/comment/list');
  assert.ok(comments, '详情必须请求评论列表');
  comments.success({
    code: '200',
    data: {
      rows: [
        { id: 501, memberId: 1, memberNickname: '我自己', contents: '我的评论', likeCount: 0, lifecycle: 'PUBLISHED', author_approval_state: 'VISIBLE' },
        { id: 502, memberId: 2, memberNickname: '别人', contents: '别人的评论', likeCount: 0, lifecycle: 'PUBLISHED', author_approval_state: 'VISIBLE' },
      ],
      total: 2,
    },
  });
  assert.equal(h.page.data.list.length, 2);
}

test('渲染层:本人评论有删除入口,非本人只留举报', () => {
  const wxml = read('pages/square/detail/index.wxml');
  assert.match(wxml, /<button class="comment-report comment-delete"[^>]*wx:if="\{\{item\.memberId == userId\}\}"[^>]*bindtap="deleteComment"[^>]*aria-label="删除评论"/,
    '删除入口必须只对本人渲染且绑定 deleteComment');
  assert.match(wxml, /bindtap="reportComment"/, '举报入口不能被顶掉');
  const js = read(PAGE_REL);
  assert.match(js, /url: '\/api\/comment\/delete'/, '删除入口必须接后端已有端点');
});

test('行为层:走确认弹窗 → 发删除请求 → 成功后本地摘行', () => {
  const h = loadPage();
  readyWithComments(h);

  h.page.deleteComment({ currentTarget: { dataset: { index: 0 } } });
  assert.equal(h.modalCalls.length, 1, '删除必须先过确认(不可逆动作)');
  assert.equal(h.requests.some((r) => r.url === '/api/comment/delete'), false, '未确认前不得发请求');

  h.modalCalls[0].success({ confirm: true });
  const del = h.requests.find((r) => r.url === '/api/comment/delete');
  assert.ok(del, '确认后必须发删除请求');
  assert.equal(String(del.data.id), '501', '删的必须是这一条');

  del.success({ code: '200' });
  assert.deepEqual(h.page.data.list.map((row) => row.id), [502], '删除成功后本地摘掉该行');
  assert.equal(h.page.data.nodata, false);
});

test('入口闸:别人的评论不弹确认也不发请求(后端另有 owner 校验)', () => {
  const h = loadPage();
  readyWithComments(h);

  h.page.deleteComment({ currentTarget: { dataset: { index: 1 } } });
  assert.equal(h.modalCalls.length, 0, '别人的评论不该出现删除确认');
  assert.equal(h.requests.some((r) => r.url === '/api/comment/delete'), false);
});

test('负控:去掉归属闸,「别人的评论不弹确认」用例真红', () => {
  const source = read(PAGE_REL);
  const brokenSource = source.replace("    if (String(item.memberId) !== String(that.data.userId)) return; // 入口只给自己的评论;后端仍会再校验一次\n", '');
  assert.notEqual(brokenSource, source, '负控锚点失效:归属闸未命中');

  const h = loadPage({ source: brokenSource });
  readyWithComments(h);
  h.page.deleteComment({ currentTarget: { dataset: { index: 1 } } });
  assert.notEqual(h.modalCalls.length, 0, '负控必须真的把归属闸摘掉');
});

test('负控:变异删除端点,发请求用例真红', () => {
  const source = read(PAGE_REL);
  const brokenSource = source.replace("url: '/api/comment/delete'", "url: '/api/comment/report'");
  assert.notEqual(brokenSource, source, '负控锚点失效:删除端点未命中');

  const h = loadPage({ source: brokenSource });
  readyWithComments(h);
  h.page.deleteComment({ currentTarget: { dataset: { index: 0 } } });
  h.modalCalls[0].success({ confirm: true });
  assert.equal(h.requests.some((r) => r.url === '/api/comment/delete'), false, '负控必须真的把端点改掉');
});

function confirmedDelete(h) {
  h.page.deleteComment({ currentTarget: { dataset: { index: 0 } } });
  h.modalCalls.at(-1).success({ confirm: true });
  return h.requests.find((r) => r.url === '/api/comment/delete');
}

test('删除成功同步footer计数且重复回包不再减，并复用列表脏标', () => {
  let dirtyCalls = 0;
  const previous = { markSquareListDirty() { dirtyCalls++; } };
  const h = loadPage({ pages: [previous, {}] });
  readyWithComments(h);
  const request = confirmedDelete(h);
  request.success({ code: 200 });
  assert.equal(h.page.data.info.commentCount, 1);
  assert.equal(h.page.data.info.contents, '正文');
  assert.equal(h.app.globalData.squareListDirty, true);
  assert.equal(dirtyCalls, 1);
  request.complete();
  request.success({ code: 200 });
  assert.equal(h.page.data.info.commentCount, 1);
  assert.equal(h.page.data.list.length, 1);
  assert.equal(dirtyCalls, 1);
});

test('删除计数夹零，不从页面当前加载行数推算总数', () => {
  for (const [before, expected] of [[0, 0], [17, 16]]) {
    const h = loadPage();
    readyWithComments(h, { commentCount: before });
    confirmedDelete(h).success({ code: 200 });
    assert.equal(h.page.data.info.commentCount, expected);
  }
});

test('删除拒绝、网络失败或用户取消保持计数和列表且不置脏', () => {
  for (const outcome of ['refused', 'network', 'cancelled']) {
    const h = loadPage();
    readyWithComments(h);
    h.page.deleteComment({ currentTarget: { dataset: { index: 0 } } });
    if (outcome === 'cancelled') h.modalCalls[0].success({ confirm: false });
    else {
      h.modalCalls[0].success({ confirm: true });
      const request = h.requests.find((r) => r.url === '/api/comment/delete');
      if (outcome === 'refused') request.success({ code: 403, msg: 'forbidden' });
      else request.fail({});
      request.complete();
    }
    assert.equal(h.page.data.info.commentCount, 2);
    assert.equal(h.page.data.list.length, 2);
    assert.equal(h.app.globalData.squareListDirty, undefined);
  }
});


test('本人待审核或非VISIBLE行删除不减公开计数，根删除只摘单ID', () => {
  for (const [lifecycle, approval] of [['CHECKING', 'VISIBLE'], ['PUBLISHED', 'PENDING']]) {
    const h = loadPage();
    readyWithComments(h, { commentCount: 17 });
    h.page.data.list[0].lifecycle = lifecycle;
    h.page.data.list[0].author_approval_state = approval;
    confirmedDelete(h).success({ code: 200 });
    assert.equal(h.page.data.info.commentCount, 17);
    assert.deepEqual(h.page.data.list.map(row => row.id), [502]);
  }
});

test('缺计数状态不猜，成功权威详情刷新且保留评论草稿，重复回包不重拉', () => {
  const h = loadPage();
  readyWithComments(h, { commentCount: 17 });
  delete h.page.data.list[0].author_approval_state;
  h.page.data.commentContent = '尚未提交的草稿';
  const request = confirmedDelete(h);
  request.success({ code: 200 });
  assert.equal(h.page.data.info.commentCount, 17);
  const reads = h.requests.filter(r => r.url === '/api/creativesquare/info');
  assert.equal(reads.length, 2);
  reads[1].success({ code: 200, data: { id: 77, memberId: 1, contents: '正文', commentCount: 16 } });
  assert.equal(h.page.data.info.commentCount, 16);
  assert.equal(h.page.data.commentContent, '尚未提交的草稿');
  request.success({ code: 200 });
  assert.equal(h.requests.filter(r => r.url === '/api/creativesquare/info').length, 2);
});

test('缺状态权威刷新失败保留原计数及评论回复草稿，沿用详情错误处理', () => {
  for (const outcome of ['network', 'refused']) {
    const h = loadPage();
    readyWithComments(h, { commentCount: 17 });
    delete h.page.data.list[0].lifecycle;
    h.page.data.commentContent = '评论草稿';
    h.page.data.replyContent = '回复草稿';
    h.page.data.replyId = 502;
    confirmedDelete(h).success({ code: 200 });
    const refresh = h.requests.filter(r => r.url === '/api/creativesquare/info')[1];
    assert.ok(refresh);
    if (outcome === 'network') refresh.fail({});
    else refresh.success({ code: 403, msg: '无法读取详情' });
    refresh.complete();
    assert.equal(h.page.data.info.commentCount, 17);
    assert.equal(h.page.data.commentContent, '评论草稿');
    assert.equal(h.page.data.replyContent, '回复草稿');
    assert.equal(h.page.data.replyId, 502);
    assert.equal(h.page.data.detailState, 'error');
    assert.ok(h.page.data.detailError);
  }
});

test('详情通过真实上一页#profile组件桥置本地脏，保留广场全局标记', () => {
  let marked = 0;
  const previous = { selectComponent(selector) {
    assert.equal(selector, '#profile');
    return { markSquareListDirty() { marked++; } };
  } };
  const h = loadPage({ pages: [previous, {}] });
  readyWithComments(h);
  confirmedDelete(h).success({ code: 200 });
  assert.equal(marked, 1);
  assert.equal(h.app.globalData.squareListDirty, true);
});

test('详情点赞取消与收藏取消成功标记返回列表脏，失败及在途重复tap不标', () => {
  for (const action of ['like', 'favorite']) {
    for (const enabled of [0, 1]) {
      let marked = 0;
      const h = loadPage({ pages: [{ selectComponent: () => ({ markSquareListDirty() { marked++; } }) }, {}] });
      readyWithComments(h);
      h.page.data.info.isLiked = enabled ? 0 : 1;
      h.page.data.info.isBookmarked = enabled ? 0 : 1;
      h.page.burstLike = () => {};
      const tap = () => action === 'like'
        ? h.page.lickClick({ currentTarget: { dataset: { type: 1 } } }) : h.page.favoriteClick();
      tap(); tap();
      const url = '/api/creativesquare/' + (action === 'like' ? 'like' : 'bookmark');
      const writes = h.requests.filter(r => r.url === url);
      assert.equal(writes.length, 1);
      assert.equal(marked, 0);
      assert.equal(h.app.globalData.squareListDirty, undefined);
      writes[0].success({ code: 200, data: { isLiked: enabled, isBookmarked: enabled, likeCount: enabled, version: 2 } });
      assert.equal(marked, 1);
      assert.equal(h.app.globalData.squareListDirty, true);
    }
    for (const outcome of ['refused', 'network']) {
      const h = loadPage();
      readyWithComments(h);
      if (action === 'like') h.page.lickClick({ currentTarget: { dataset: { type: 1 } } });
      else h.page.favoriteClick();
      const url = '/api/creativesquare/' + (action === 'like' ? 'like' : 'bookmark');
      const write = h.requests.find(r => r.url === url);
      if (outcome === 'refused') write.success({ code: 403, msg: '拒绝' });
      else write.fail({});
      assert.equal(h.app.globalData.squareListDirty, undefined);
    }
  }
});
