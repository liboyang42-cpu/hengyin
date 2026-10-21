'use strict';

// BL-193：广场列表点赞/关注的成功回调必须按发起时的 item.id 在当前列表里定位后生成新对象回写，
// 不能按发起时的 index 原地改 list[index]，也不能把发起时的列表快照整表写回。

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
const ids = (list) => list.map((item) => item.id);

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

const likeEvent = (index, type) => ({ detail: { index: index, type: type }, currentTarget: { dataset: {} } });

test('列表点赞：请求期间列表被替换，只回写同 id 那一行且不原地改旧对象', () => {
  const h = loadPage('pages/square/list/index.js');
  const original = { id: 11, isLiked: 0, likeCount: 3 };
  h.page.data.list = [original, { id: 12, isLiked: 0, likeCount: 5 }];
  h.page.lickClick(likeEvent(0, 1));
  const write = h.requests.find((r) => r.url === '/api/creativesquare/like');
  const other = { id: 20, isLiked: 0, likeCount: 1 };
  h.page.data.list = [other, { id: 11, isLiked: 0, likeCount: 3 }];
  // master 社区客户端点赞成功回的是服务端帖文(isLiked/likeCount/version),页面以服务端为准回写
  write.success({ code: '200', data: { id: 11, isLiked: 1, likeCount: 4, version: 2 } });
  assert.deepEqual(h.page.data.list[0], { id: 20, isLiked: 0, likeCount: 1 }, '不得点亮别人的动态');
  assert.equal(h.page.data.list[1].isLiked, 1);
  assert.equal(h.page.data.list[1].likeCount, 4);
  assert.deepEqual(original, { id: 11, isLiked: 0, likeCount: 3 }, '不得原地改发起时的对象');
});

test('列表点赞：目标已不在列表里时不回写', () => {
  const h = loadPage('pages/square/list/index.js');
  h.page.data.list = [{ id: 11, isLiked: 0, likeCount: 3 }];
  h.page.lickClick(likeEvent(0, 1));
  h.page.data.list = [{ id: 30, isLiked: 0, likeCount: 0 }];
  h.requests.find((r) => r.url === '/api/creativesquare/like').success({ code: '200' });
  assert.deepEqual(h.page.data.list, [{ id: 30, isLiked: 0, likeCount: 0 }]);
});

test('列表关注：请求期间列表被替换，不把旧列表整表写回，只改同 id 那一行', () => {
  const h = loadPage('pages/square/list/index.js');
  h.page.data.list = [{ id: 11, memberId: 88, isFollowTheUser: 0 }, { id: 12, memberId: 89, isFollowTheUser: 0 }];
  h.page.followClick({ currentTarget: { dataset: { index: 0 } } });
  const write = h.requests.find((r) => r.url === '/api/user/follow/action');
  assert.equal(write.data.follow, 1);
  h.page.data.list = [{ id: 40, memberId: 90, isFollowTheUser: 0 }, { id: 11, memberId: 88, isFollowTheUser: 0 }, { id: 41 }];
  write.success({ code: '200' });
  assert.deepEqual(ids(h.page.data.list), [40, 11, 41], '不得用发起时的快照覆盖刷新后的列表');
  assert.equal(h.page.data.list[0].isFollowTheUser, 0);
  assert.equal(h.page.data.list[1].isFollowTheUser, 1);

  const g = loadPage('pages/square/list/index.js');
  g.page.data.list = [{ id: 11, memberId: 88, isFollowTheUser: 0 }];
  g.page.followClick({ currentTarget: { dataset: { index: 0 } } });
  g.page.data.list = [{ id: 50, memberId: 91, isFollowTheUser: 0 }];
  g.requests.find((r) => r.url === '/api/user/follow/action').success({ code: '200' });
  assert.deepEqual(g.page.data.list, [{ id: 50, memberId: 91, isFollowTheUser: 0 }], '目标已不在列表里时不回写');
});
