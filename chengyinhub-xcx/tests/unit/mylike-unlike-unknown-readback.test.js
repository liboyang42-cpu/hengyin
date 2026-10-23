'use strict';

// PRIVATE-GROUP-F-current-favorites-AI / PC-024:取消收藏断网 = 结果未知。
// /api/topic/like 是 toggle,未知时再发一次可能把已取消的收藏插回来:
// 锁不放、原卡片不摘，按 like_list 刷新——读到=没取消成功，读完没有=已取消；刷新失败保持未知。

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const PAGE_PATH = path.resolve(__dirname, '../../pages/mylike/mylike.js');

function loadPage() {
  const requests = [];
  const modals = [];
  const toasts = [];
  let uid = '7';
  global.getApp = () => ({
    globalData: {},
    getUserID: () => uid,
    getRequestErrorMessage: (_res, fallback) => fallback,
    sendRequest(options) { requests.push(options); return { abort() {} }; },
  });
  global.Page = (config) => { global.__mylikeDef = config; };
  global.wx = {
    getStorageSync: () => '',
    setNavigationBarColor() {},
    stopPullDownRefresh() {},
    showToast(options) { toasts.push(options.title); },
    showModal(options) { modals.push(options); },
  };
  delete require.cache[require.resolve(PAGE_PATH)];
  require(PAGE_PATH);
  const definition = global.__mylikeDef;
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) });
  page.setData = function (patch, cb) { Object.assign(this.data, patch); if (cb) cb(); };
  return { page, requests, modals, toasts, switchUser(next) { uid = next; } };
}

const toggles = (requests) => requests.filter((r) => r.url === '/api/topic/like');
const reads = (requests) => requests.filter((r) => r.url === '/api/topic/like_list');
const tap = (page, id) => page.topicUnlike({ currentTarget: { dataset: { id } } });
const rows = (ids, n) => ids.concat(Array.from({ length: (n || 0) }, (_, i) => 1000 + i)).map((id) => ({ id }));

function unknownUnlike(env, id) {
  tap(env.page, id);
  env.modals[env.modals.length - 1].success({ confirm: true });
  const req = toggles(env.requests)[toggles(env.requests).length - 1];
  req.fail();
  req.complete();
  return req;
}

test('断网后保持锁定与原卡片，刷新读完没有=已取消并按 ID 摘卡', () => {
  const env = loadPage();
  env.page.setData({ ztList1: [{ id: 11 }, { id: 12 }] });
  unknownUnlike(env, 11);
  assert.equal(env.toasts.includes('取消结果未确认，正在确认'), true);
  assert.ok(!env.toasts.some((t) => /网络异常，请重试/.test(t)));
  assert.deepEqual(env.page.data.ztList1.map((t) => t.id), [11, 12], '结果未知不摘卡');
  assert.equal(reads(env.requests).length, 1);
  assert.deepEqual(reads(env.requests)[0].data, { pageNum: 1, pageSize: 50 });

  // 刷新在途:再点既不开确认框也不发 toggle、不重复刷新
  tap(env.page, 11);
  assert.equal(env.modals.length, 1);
  assert.equal(toggles(env.requests).length, 1);
  assert.equal(reads(env.requests).length, 1);

  // 第一页满页且没有 → 读第二页；第二页不满且没有 → 已取消
  reads(env.requests)[0].success({ code: '200', data: { rows: rows([12], 49) } });
  assert.deepEqual(reads(env.requests)[1].data, { pageNum: 2, pageSize: 50 });
  reads(env.requests)[1].success({ code: '200', data: { rows: rows([13]) } });
  assert.deepEqual(env.page.data.ztList1.map((t) => t.id), [12]);
  assert.equal(env.toasts.pop(), '已取消收藏');
  // 锁已放:另一个主题照常走确认
  tap(env.page, 12);
  assert.equal(env.modals.length, 2);
});

test('刷新读到该主题=没取消成功，放锁可再试；刷新失败/畸形保持未知，再点只刷新', () => {
  const env = loadPage();
  env.page.setData({ ztList1: [{ id: 11 }] });
  unknownUnlike(env, 11);

  reads(env.requests)[0].fail();
  assert.equal(env.toasts.pop(), '取消结果未确认，请稍后再点确认');
  tap(env.page, 11);
  assert.equal(env.modals.length, 1, '未知期间不开确认框');
  assert.equal(toggles(env.requests).length, 1, '未知期间不发 toggle');
  assert.equal(reads(env.requests).length, 2);

  reads(env.requests)[1].success({ code: '200', data: { rows: 'oops' } });
  assert.equal(env.toasts.pop(), '取消结果未确认，请稍后再点确认');
  tap(env.page, 11);
  assert.equal(reads(env.requests).length, 3);

  reads(env.requests)[2].success({ code: '200', data: { rows: rows([11]) } });
  assert.equal(env.toasts.pop(), '取消没成功，可以再试一次');
  assert.deepEqual(env.page.data.ztList1.map((t) => t.id), [11]);
  tap(env.page, 11);
  assert.equal(env.modals.length, 2, '确认未生效后才允许再次取消');
});

test('换号后旧刷新丢弃', () => {
  const env = loadPage();
  env.page.setData({ ztList1: [{ id: 11 }] });
  env.page._syncFavoriteAccount();
  unknownUnlike(env, 11);
  env.switchUser('8');
  env.page._syncFavoriteAccount();
  env.page.setData({ ztList1: [{ id: 11 }] });
  env.toasts.length = 0;
  reads(env.requests)[0].success({ code: '200', data: { rows: [] } });
  assert.deepEqual(env.page.data.ztList1.map((t) => t.id), [11]);
  assert.equal(env.toasts.length, 0);
});

test('收藏与推荐说明:如实讲不撤回已记录兴趣，且不暗示推荐排除/偏好已上线', () => {
  const env = loadPage();
  env.page.showRecommendNote();
  const content = env.modals.pop().content;
  assert.match(content, /不会撤回已经记录的推荐兴趣/);
  assert.match(content, /目前还不能设置推荐偏好，也不能把某个内容排除出推荐/);
  assert.doesNotMatch(content, /已排除|不再推荐给你|将不再推荐|已减少推荐/);
  const view = require('node:fs').readFileSync(path.resolve(__dirname, '../../pages/mylike/mylike.wxml'), 'utf8');
  assert.match(view, /bindtap="showRecommendNote"/);
  assert.doesNotMatch(view, /不感兴趣|不再推荐|减少推荐/);
});
