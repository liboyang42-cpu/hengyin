'use strict';

// BL-194：广场详情评论点赞成功回调必须按发起时的 item.id 在当前评论列表里定位后生成新对象回写，
// 不能按发起时的 index 原地改 list[index]；仍以回包 data.isLiked 为终态、缺省按开关取反。

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { createRequire } = require('node:module');
const path = require('node:path');
const vm = require('../helpers/ui-sandbox-vm.js');

const ROOT = path.resolve(__dirname, '../..');
// 页面层回调测试:社区客户端换成同步透传(见 tests/helpers/fake-community-client.js)
require('../helpers/fake-community-client.js').installFakeCommunityClient(ROOT)
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

function loadPage(relativePath, opts) {
  const requests = [];
  const modalCalls = [];
  const navigations = [];
  const toasts = [];
  let definition;
  const absolutePath = path.join(ROOT, relativePath);
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => 1,
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
    navigateTo(options) { navigations.push(options && options.url); },
    redirectTo(options) { navigations.push(options && options.url); },
    reLaunch(options) { navigations.push(options && options.url); },
    pageScrollTo() {},
    setClipboardData() {},
    previewImage() {},
    stopPullDownRefresh() {},
    showModal(options) {
      modalCalls.push(options);
      if (!opts || !opts.manualModal) options.success({ confirm: true, content: '测试理由' });
    },
    showToast() {}, hideToast() {}, showLoading() {}, hideLoading() {},
    createSelectorQuery: () => ({ select() { return this; }, selectViewport() { return this; }, boundingClientRect() { return this; }, fields() { return this; }, scrollOffset() { return this; }, exec(cb) { if (cb) cb([null]); } }),
  };
  const source = (opts && opts.source) || read(relativePath);
  vm.runInNewContext(source, {
    console,
    getApp: () => app,
    Page: (config) => { definition = config; },
    require: createRequire(absolutePath),
    setTimeout() {}, clearTimeout() {}, setInterval() {}, clearInterval() {},
    wx: wxApi,
  }, { filename: absolutePath });

  assert.ok(definition, relativePath + ' 必须注册 Page');
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) {
      Object.entries(patch).forEach(([key, value]) => {
        const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.');
        let cursor = this.data;
        parts.slice(0, -1).forEach((part) => {
          if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {};
          cursor = cursor[part];
        });
        cursor[parts[parts.length - 1]] = value;
      });
      if (done) done.call(this);
    },
  });
  return { page, requests, modalCalls, navigations, toasts };
}

const plain = (value) => JSON.parse(JSON.stringify(value));
const tap = (page, index) => page.commentLikeClick({ currentTarget: { dataset: { index: index } } });

test('评论点赞：请求期间评论列表被替换，只回写同 id 那条且不原地改旧对象', () => {
  const h = loadPage('pages/square/detail/index.js');
  const original = { id: 55, isLiked: 0, likeCount: 1 };
  h.page.data.list = [original, { id: 56, isLiked: 0, likeCount: 2 }];
  tap(h.page, 0);
  const like = h.requests.find((r) => r.url === '/api/comment/like');
  h.page.data.list = [{ id: 60, isLiked: 0, likeCount: 0 }, { id: 55, isLiked: 0, likeCount: 1 }];
  like.success({ code: '200', data: { isLiked: 1 } });
  assert.deepEqual(plain(h.page.data.list[0]), { id: 60, isLiked: 0, likeCount: 0 }, '不得点亮别人的评论');
  assert.deepEqual(plain(h.page.data.list[1]), { id: 55, isLiked: 1, likeCount: 2 });
  assert.deepEqual(original, { id: 55, isLiked: 0, likeCount: 1 }, '不得原地改发起时的对象');
});

test('评论点赞：回包缺 isLiked 时按开关取反；目标已不在列表里时不回写', () => {
  const h = loadPage('pages/square/detail/index.js');
  h.page.data.list = [{ id: 55, isLiked: 1, likeCount: 3 }];
  tap(h.page, 0);
  h.requests.find((r) => r.url === '/api/comment/like').success({ code: '200' });
  assert.deepEqual(plain(h.page.data.list), [{ id: 55, isLiked: 0, likeCount: 2 }]);

  const g = loadPage('pages/square/detail/index.js');
  g.page.data.list = [{ id: 55, isLiked: 0, likeCount: 1 }];
  tap(g.page, 0);
  g.page.data.list = [{ id: 70, isLiked: 0, likeCount: 4 }];
  g.requests.find((r) => r.url === '/api/comment/like').success({ code: '200', data: { isLiked: 1 } });
  assert.deepEqual(plain(g.page.data.list), [{ id: 70, isLiked: 0, likeCount: 4 }]);
});
