'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const { createRequire } = require('node:module')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js') // node:vm + 沙箱里装真 utils/toast.js·loading.js

const ROOT = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
}

function setByPath(target, dotted, value) {
  const parts = dotted.replace(/\[(\d+)\]/g, '.$1').split('.')
  let cursor = target
  parts.slice(0, -1).forEach((part) => {
    if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {}
    cursor = cursor[part]
  })
  cursor[parts[parts.length - 1]] = value
}

function loadPage(relativePath) {
  const requests = []
  const requestTasks = []
  const toasts = []
  let pullStops = 0
  let definition
  const absolutePath = path.join(ROOT, relativePath)
  const app = {
    canUseCommunity: () => true, // Current-account server-open fixture.
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getAvatar: () => '',
    getNickname: () => '',
    getPageSize: () => 10,
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    getTotalPage: (total, size) => Math.ceil(Number(total || 0) / size),
    currentUserId: 7,
    getUserID() { return this.currentUserId },
    sendRequest(options) {
      requests.push(options)
      const task = { aborted: false, abort() { this.aborted = true } }
      requestTasks.push(task)
      return task
    },
    tips(message) { toasts.push(message) },
  }
  const wxApi = {
    getSystemInfoSync: () => ({ windowWidth: 375, windowHeight: 812, statusBarHeight: 20 }),
    getMenuButtonBoundingClientRect: () => ({ bottom: 64 }),
    hideTabBar() {},
    setNavigationBarColor() {},
    navigateBack() {},
    navigateTo() {},
    pageScrollTo() {},
    reLaunch() {},
    showToast(options) { toasts.push(options && options.title) },
    stopPullDownRefresh() { pullStops += 1 },
  }
  global.getApp = () => app;
  global.wx = wxApi;
  vm.runInNewContext(process.env.MINI_B_SQUARE_SOURCE ? fs.readFileSync(process.env.MINI_B_SQUARE_SOURCE, 'utf8') : read(relativePath), {
    console,
    getApp: () => app,
    Page: (config) => { definition = config },
    require: (id) => id.includes('community-post-api') ? require('../helpers/community-page-seam') : createRequire(absolutePath)(id),
    setTimeout() {},
    clearTimeout() {},
    wx: wxApi,
  }, { filename: absolutePath })

  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) {
      Object.entries(patch).forEach(([key, value]) => setByPath(this.data, key, value))
      if (done) done.call(this)
    },
  })
  return {
    page,
    app,
    requests,
    requestTasks,
    toasts,
    get pullStops() { return pullStops },
  }
}


const ids = (page) => Array.from(page.data.list, (item) => item.id);
function reply(request, rows, hasMore) {
  request.success({ code: 200, data: { rows, hasMore } });
  request.complete();
}

test('真实 offset 广场追加按稳定 ID 去重，同时推进原服务端页码', () => {
  const h = loadPage('pages/square/list/index.js');
  h.page.getList(false, 1);
  reply(h.requests[0], [{ id: 3 }, { id: 2 }, { id: 2 }], true);
  assert.deepEqual(ids(h.page), [3, 2]);
  h.page.getList(false, 2);
  assert.equal(h.requests[1].url, '/api/creativesquare/list');
  assert.equal(h.requests[1].data.pageNum, 2);
  reply(h.requests[1], [{ id: '2' }, { id: 1 }, { id: 1 }], true);
  assert.deepEqual(ids(h.page), [3, 2, 1]);
  assert.equal(h.page.data.page_no, 2);
  assert.equal(h.page.data.hasMore, true);
});

test('整页重复也推进 offset 页码，下一页可继续；空尾页按服务端 hasMore 停止', () => {
  const h = loadPage('pages/square/list/index.js');
  h.page.data.list = [{ id: 2 }];
  h.page.getList(false, 2);
  reply(h.requests[0], [{ id: 2 }, { id: '2' }], true);
  assert.deepEqual(ids(h.page), [2]);
  assert.equal(h.page.data.page_no, 2);
  assert.equal(h.page.data.hasMore, true);
  h.page.onReachBottom();
  assert.equal(h.requests[1].data.pageNum, 3);
  reply(h.requests[1], [], false);
  assert.equal(h.page.data.page_no, 3);
  assert.equal(h.page.data.hasMore, false);
});

test('刷新失败保留旧卡，重试成功替换旧卡；迟到原追加不能复活', () => {
  const h = loadPage('pages/square/list/index.js');
  h.page.data.list = [{ id: 7 }];
  h.page.getList(false, 2);
  h.page.refreshList();
  assert.equal(h.requestTasks[0].aborted, true);
  h.requests[1].fail({ msg: 'timeout' });
  h.requests[1].complete();
  assert.deepEqual(ids(h.page), [7]);
  h.page.refreshList();
  reply(h.requests[2], [{ id: 9 }], false);
  reply(h.requests[0], [{ id: 8 }], true);
  assert.deepEqual(ids(h.page), [9]);
  assert.equal(h.page.data.page_no, 1);
});

test('账号迟到响应丢弃，回前台清上一账号私有待审及个人态缓存', () => {
  const h = loadPage('pages/square/list/index.js');
  h.page.getList(false, 1);
  h.app.currentUserId = 9;
  reply(h.requests[0], [{ id: 7, status: 0, memberId: 7 }], false);
  assert.deepEqual(ids(h.page), []);
  h.page.data.list = [{ id: 7, status: 0, memberId: 7 }];
  h.page.onShow();
  assert.deepEqual(ids(h.page), []);
  const feed = h.requests.findLast((request) => request.url === '/api/creativesquare/list');
  assert.equal(feed.data.pageNum, 1);
  reply(feed, [{ id: 9 }], false);
  assert.deepEqual(ids(h.page), [9]);
});
