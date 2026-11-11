'use strict';
// f-merchant(G-5 收尾,2026-11-11):店铺参谋与消息页订阅身份广播。
// 品牌中心换店名/Logo 后回到店铺参谋:本页缓存的身份作废,以 /api/merchant/info 回读整体覆盖;
// 消息列表:身份广播过就整表重拉会话(不走「保留已确认行」的静默刷新)。
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');

function freshRequire(rel) {
  Object.keys(require.cache).forEach((k) => { if (k.startsWith(ROOT) && !k.includes('/tests/')) delete require.cache[k]; });
  return require(path.join(ROOT, rel));
}

function setupGlobals(requests) {
  const storage = {};
  global.wx = {
    getSystemInfoSync: () => ({ statusBarHeight: 20, windowWidth: 375 }),
    getStorageSync: (k) => storage[k],
    setStorageSync: (k, v) => { storage[k] = v; },
    removeStorageSync: (k) => { delete storage[k]; },
    setNavigationBarColor() {}, setBackgroundColor() {}, setBackgroundTextStyle() {},
    getMenuButtonBoundingClientRect: () => ({ left: 280 }),
    showToast() {}, navigateTo() {}, reLaunch() {},
    nextTick: (fn) => fn(), onAppRoute() {}, offAppRoute() {},
  };
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => 7, getToken: () => 'tok', getAvatar: () => '', getImgUrl: (v) => v,
    sendRequest: (opts) => { requests.push(opts); },
    getRequestErrorMessage: (res, fb) => fb, showToast() {}, tips() {},
  };
  global.getApp = () => app;
  global.getCurrentPages = () => [];
  let def = null;
  global.Page = (d) => { def = d; };
  return { app, page: () => def };
}

function instantiate(def) {
  const page = Object.assign({}, def);
  page.data = JSON.parse(JSON.stringify(def.data || {}));
  page.setData = function (patch) {
    Object.keys(patch).forEach((k) => {
      const parts = k.split('.');
      let target = this.data;
      while (parts.length > 1) { const p = parts.shift(); target = target[p] = target[p] || {}; }
      target[parts[0]] = patch[k];
    });
  };
  page.selectComponent = () => null;
  page.route = 'x';
  return page;
}

test('店铺参谋:身份广播后再回来,以 /api/merchant/info 回读整体覆盖旧店名与旧 Logo', () => {
  const requests = [];
  const env = setupGlobals(requests);
  freshRequire('pages/merchant/marketing/ai-insight/index.js');
  const identitySync = require(path.join(ROOT, 'utils/identity-sync.js'));
  identitySync._resetForTest();
  const page = instantiate(env.page());
  page.onLoad();
  page.setData({ storeName: '旧店名', storeLogo: 'old.png' });
  page.onShow();
  page.onHide();

  identitySync.publishIdentity('merchant', { name: '新店名', logo: 'new-local.png' });
  assert.equal(page.data.storeName, '新店名', '订阅方:广播到达即先盖上新店名');

  requests.length = 0;
  page.onShow();
  assert.equal(page._identityStale, true, 'epoch 变了 ⇒ 本页身份标记作废');
  page._loadStoreIdentity(() => true);
  const info = requests.find((r) => r.url === '/api/merchant/info');
  assert.ok(info, '作废后必须回读商家行');
  info.success({ code: 200, data: { name: '回读店名', logo: 'server.png' } });
  assert.equal(page.data.storeName, '回读店名');
  assert.equal(page.data.storeLogo, 'server.png');
  assert.equal(page._identityStale, false);

  // 未作废时回读只补缺,不覆盖参谋包里已有的值
  requests.length = 0;
  page._loadStoreIdentity(() => true);
  requests.find((r) => r.url === '/api/merchant/info').success({ code: 200, data: { name: '别的', logo: 'other.png' } });
  assert.equal(page.data.storeName, '回读店名');
  page.onUnload();
});

test('消息列表:订阅身份广播,epoch 变了回到页面整表重拉(非静默)', () => {
  const requests = [];
  const env = setupGlobals(requests);
  freshRequire('subpackageB/pages/im/list/index.js');
  const identitySync = require(path.join(ROOT, 'utils/identity-sync.js'));
  identitySync._resetForTest();
  const page = instantiate(env.page());
  const calls = [];
  page.loadConversations = (silent) => calls.push(silent);
  page.loadInvites = () => {};
  page.loadMerchantEntry = () => {};
  page._syncCommunityEntry = () => {};
  page._syncViewTheme = () => {};
  page.onLoad();
  page.setData({ loaded: true });
  page._hidden = true;
  calls.length = 0;
  page.onShow();
  assert.deepEqual(calls, [true], '身份没变:沿用静默刷新');

  identitySync.publishIdentity('member', { nickname: '新昵称' });
  calls.length = 0;
  page.onShow();
  assert.deepEqual(calls, [false], '身份变了:丢掉已确认快照整表重拉');

  calls.length = 0;
  page.onShow();
  assert.deepEqual(calls, [true], '重拉一次后恢复静默刷新');
  page.onUnload();
  calls.length = 0;
  identitySync.publishIdentity('member', { nickname: '再改' });
  assert.equal(page._identityEpoch, identitySync.identityEpoch() - 1, '卸载后不再订阅');
});
