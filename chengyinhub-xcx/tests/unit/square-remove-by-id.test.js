'use strict';

// BL-191：广场动态删除/举报、评论举报的成功回调(2026-10-29「动态」列表页已删,只剩详情页评论举报)必须按发起时的 item.id 摘行，
// 不能用发起时的 index splice —— 请求期间下拉刷新或另一条先返回就会摘掉别人的行。

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


test('详情评论举报：两条先后返回、请求期间列表被替换都按 id 摘行', () => {
  const h = loadPage('pages/square/detail/index.js');
  h.page.data.list = [{ id: 1 }, { id: 2 }, { id: 3 }];
  const tap = (index) => h.page.reportComment({ currentTarget: { dataset: { index } } });
  tap(0);
  tap(1);
  const reports = h.requests.filter((r) => r.url === '/api/comment/report');
  assert.deepEqual(reports.map((r) => r.data.id), [1, 2]);
  reports[0].success({ code: '200' });
  reports[1].success({ code: '200' });
  assert.deepEqual(ids(h.page.data.list), [3]);

  tap(0);
  h.page.data.list = [{ id: 9 }, { id: 3 }];
  h.requests.filter((r) => r.url === '/api/comment/report')[2].success({ code: '200' });
  assert.deepEqual(ids(h.page.data.list), [9]);
  h.page.data.list = [{ id: 8 }];
  tap(0);
  h.page.data.list = [{ id: 7 }];
  h.requests.filter((r) => r.url === '/api/comment/report')[3].success({ code: '200' });
  assert.deepEqual(ids(h.page.data.list), [7], '目标已不在列表里时不得摘别人的行');
});
