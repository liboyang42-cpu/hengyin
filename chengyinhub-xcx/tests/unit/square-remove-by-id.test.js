'use strict';

// BL-191：广场动态删除/举报、评论举报的成功回调必须按发起时的 item.id 摘行，
// 不能用发起时的 index splice —— 请求期间下拉刷新或另一条先返回就会摘掉别人的行。

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { createRequire } = require('node:module');
const path = require('node:path');
const vm = require('../helpers/ui-sandbox-vm.js');

const ROOT = path.resolve(__dirname, '../..');
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


test('列表删除：请求期间列表被替换，成功后只摘这条、找不到不动', () => {
  const h = loadPage('pages/square/list/index.js');
  h.page.data.userId = 1;
  h.page.data.list = [{ id: 11, memberId: 1 }, { id: 12, memberId: 2 }];
  h.page.showAction({ detail: { index: 0 } });
  h.page.onPostAction({ detail: { action: 'delete', id: '11' } });
  const write = h.requests.find((r) => r.url === '/api/creativesquare/delete');
  h.page.data.list = [{ id: 20 }, { id: 11, memberId: 1 }, { id: 21 }];
  write.success({ code: '200' });
  assert.deepEqual(ids(h.page.data.list), [20, 21]);
  assert.equal(h.page.data.nodata, false);

  const g = loadPage('pages/square/list/index.js');
  g.page.data.userId = 1;
  g.page.data.list = [{ id: 11, memberId: 1 }];
  g.page.showAction({ detail: { index: 0 } });
  g.page.onPostAction({ detail: { action: 'delete', id: '11' } });
  g.page.data.list = [{ id: 30 }, { id: 31 }];
  g.requests.find((r) => r.url === '/api/creativesquare/delete').success({ code: '200' });
  assert.deepEqual(ids(g.page.data.list), [30, 31], '目标已不在列表里时不得摘别人的行');
});

test('列表举报：两条先后返回删掉的是正确的两条', () => {
  const h = loadPage('pages/square/list/index.js');
  h.page.data.userId = 1;
  h.page.data.list = [{ id: 11, memberId: 2 }, { id: 12, memberId: 3 }, { id: 13, memberId: 4 }];
  h.page.showAction({ detail: { index: 0 } });
  h.page.onPostAction({ detail: { action: 'report', id: '11' } });
  h.page.showAction({ detail: { index: 1 } });
  h.page.onPostAction({ detail: { action: 'report', id: '12' } });
  const reports = h.requests.filter((r) => r.url === '/api/creativesquare/report');
  assert.deepEqual(reports.map((r) => r.data.id), [11, 12]);
  reports[0].success({ code: '200' });
  reports[1].success({ code: '200' });
  assert.deepEqual(ids(h.page.data.list), [13]);
});

test('列表举报：请求期间列表被替换，删光后 nodata', () => {
  const h = loadPage('pages/square/list/index.js');
  h.page.data.userId = 1;
  h.page.data.list = [{ id: 11, memberId: 2 }];
  h.page.showAction({ detail: { index: 0 } });
  h.page.onPostAction({ detail: { action: 'report', id: '11' } });
  h.page.data.list = [{ id: 11, memberId: 2 }];
  h.requests.find((r) => r.url === '/api/creativesquare/report').success({ code: '200' });
  assert.equal(h.page.data.list.length, 0);
  assert.equal(h.page.data.nodata, true);
});

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
