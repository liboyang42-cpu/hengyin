'use strict';

// 2026-10-29 l2-roam 第二轮(V-08 业主拍板):俱乐部 tab 改名「发现」,帖文看全站;
// 帖文流只剩发现 / 俱乐部详情 / 我的三处,「动态」二级页 pages/square/list 删除,原入口改去发现页帖文 tab。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8');
const discoverFeed = require('../../utils/discover-feed.js');
const discoverNav = require('../../utils/discover-nav.js');
const flush = () => new Promise((resolve) => setImmediate(resolve));

/* ---------------- 合并器 ---------------- */

function lane(pages, calls) {
  return {
    fetch(cursor) {
      calls.push(cursor);
      const page = pages.shift();
      if (page instanceof Error) return Promise.reject(page);
      return Promise.resolve(page || { rows: [], hasMore: false, cursor });
    },
  };
}
const row = (id, ts) => ({ feedKey: 'k' + id, id, sortTs: ts });

test('两路倒序流按时间归并,某一路见底先补下一页再比较,不被后到的页插队', async () => {
  const clubCalls = [];
  const postCalls = [];
  const merger = discoverFeed.createFeedMerger([
    lane([{ rows: [row(1, 100), row(2, 60)], hasMore: true, cursor: 2 }, { rows: [row(3, 40)], hasMore: false, cursor: 3 }], clubCalls),
    lane([{ rows: [row(11, 90), row(12, 50), row(13, 10)], hasMore: false, cursor: 2 }], postCalls),
  ]);
  const first = await merger.take(3);
  assert.deepEqual(first.rows.map((r) => r.id), [1, 11, 2]);
  assert.equal(first.hasMore, true);
  const second = await merger.take(3);
  assert.deepEqual(second.rows.map((r) => r.id), [12, 3, 13]);
  assert.equal(second.hasMore, false);
  assert.deepEqual(clubCalls, [null, 2], '俱乐部那一路按 beforeId 游标翻页');
  assert.deepEqual(postCalls, [null]);
});

test('某一路请求失败整页失败,游标不前进,下次重试同一页', async () => {
  const calls = [];
  const merger = discoverFeed.createFeedMerger([
    lane([new Error('offline'), { rows: [row(1, 5)], hasMore: false, cursor: 1 }], calls),
  ]);
  await assert.rejects(merger.take(2));
  const page = await merger.take(2);
  assert.deepEqual(page.rows.map((r) => r.id), [1]);
  assert.deepEqual(calls, [null, null]);
});

test('两种帖文规范化后带来源与不撞号的 key,社区帖空图不出空图位', () => {
  const club = discoverFeed.normalizeClubPost({ id: 7, clubId: 3, nickname: '小城', content: 'a&amp;b', images: 'x.jpg,y.jpg', createTime: '2026-10-29 12:30:00' });
  assert.equal(club.source, 'club');
  assert.equal(club.feedKey, 'club-7');
  assert.equal(club.memberNickname, '小城');
  assert.deepEqual(club.picList, ['x.jpg', 'y.jpg']);
  assert.ok(club.sortTs > 0);
  const post = discoverFeed.normalizeCommunityPost({ id: 7, pics: '', createTime: '2026-10-29 12:31:00' });
  assert.equal(post.source, 'community');
  assert.equal(post.feedKey, 'post-7');
  assert.deepEqual(post.picList, []);
  assert.ok(post.sortTs > club.sortTs);
});

/* ---------------- 发现页 ---------------- */

function loadDiscover(appExtra) {
  const requests = [];
  const navs = [];
  let definition;
  const app = Object.assign({
    globalData: { statusBarHeight: 20, navBarHeight: 44, menuButtonInfo: { left: 300 } },
    sendRequest(options) { requests.push(options); },
    getRequestErrorMessage(res, fallback) { return (res && res.msg) || fallback; },
    getUserID() { return 7; },
    getAuthorization() { return 'token'; },
    getUserRole() { return 'player'; },
    getUserType() { return 1; },
    getImgUrl(value) { return value || ''; },
  }, appExtra || {});
  global.getApp = () => app;
  global.Page = (page) => { definition = page; };
  global.wx = {
    getStorageSync() { return ''; }, setStorageSync() {}, removeStorageSync() {},
    showToast() {}, showModal() {}, stopPullDownRefresh() {}, setNavigationBarColor() {}, setBackgroundColor() {}, setBackgroundTextStyle() {},
    navigateTo(o) { navs.push(o.url); },
    getWindowInfo() { return { windowWidth: 375, statusBarHeight: 20 }; },
    getMenuButtonBoundingClientRect() { return { top: 24, height: 32, left: 300 }; },
  };
  const file = path.join(ROOT, 'pages/talent/list/index.js');
  delete require.cache[require.resolve(file)];
  require(file);
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.entries(patch).forEach(([k, v]) => { this.data[k] = v; }); },
  });
  return { page, requests, navs, app };
}

test('发现页顶栏标题是「发现」,帖文请求走全站 scope=all,翻页带 beforeId', async () => {
  assert.match(read('pages/talent/list/index.wxml'), /<text class="nav-title">发现<\/text>/);
  const { page, requests } = loadDiscover();
  page.loadFeed();
  await flush();
  assert.equal(requests[0].url, '/api/club/post/feed');
  assert.deepEqual(JSON.parse(requests[0].data), { scope: 'all', limit: 20 });
  const rows = Array.from({ length: 20 }, (_, i) => ({ id: 100 - i, clubId: 1, createTime: '2026-10-29 12:' + String(59 - i).padStart(2, '0') + ':00' }));
  requests[0].success({ code: 200, data: { rows, hasMore: true } });
  await flush();
  assert.equal(page.data.feedState, 'ready');
  assert.equal(page.data.feed.length, 20);
  page.loadMoreFeed();
  await flush();
  assert.deepEqual(JSON.parse(requests[1].data), { scope: 'all', limit: 20, beforeId: 81 });
  requests[1].success({ code: 200, data: { rows: [{ id: 80, clubId: 2, createTime: '2026-10-29 11:00:00' }], hasMore: false } });
  await flush();
  assert.deepEqual(page.data.feed.slice(-1).map((r) => r.feedKey), ['club-80']);
  page.loadMoreFeed();
  await flush();
  assert.equal(requests.length, 2, '没有更多就不再请求');
});

test('翻页失败不出错误文案,保留已加载帖文,下次触底重试', async () => {
  const { page, requests } = loadDiscover();
  page.loadFeed();
  await flush();
  const rows = Array.from({ length: 20 }, (_, i) => ({ id: 28 - i, clubId: 1, createTime: '2026-10-29 12:' + String(40 - i).padStart(2, '0') + ':00' }));
  requests[0].success({ code: 200, data: { rows, hasMore: true } });
  await flush();
  page.data.activeTopTab = 'feed';
  page.onReachBottom();
  await flush();
  requests[1].fail({ errMsg: 'request:fail' });
  await flush();
  assert.equal(page.data.feedState, 'ready');
  assert.equal(page.data.feed.length, 20);
  page.onReachBottom();
  await flush();
  assert.deepEqual(JSON.parse(requests[2].data), { scope: 'all', limit: 20, beforeId: 9 });
});

test('社区帖文点开去帖文详情,俱乐部帖文点开去俱乐部详情', async () => {
  const { page, navs } = loadDiscover();
  page.data.feed = [
    discoverFeed.normalizeCommunityPost({ id: 5, createTime: '2026-10-29 12:00:00' }),
    discoverFeed.normalizeClubPost({ id: 6, clubId: 2, createTime: '2026-10-29 11:00:00' }),
  ];
  page.onPostTap({ detail: { index: 0, postId: 5 } });
  page.onPostTap({ detail: { index: 1, postId: 6, clubId: 2 } });
  assert.deepEqual(navs, ['/pages/square/detail/index?id=5', '/pages/club/detail/index?id=2&postId=6']);
});

test('其他页去发现:标记一次性生效,发现页 onShow 消费后切到帖文 tab 并拉流', async () => {
  const { page, requests, app } = loadDiscover();
  page.initNav = () => {};
  page.loadImUnread = () => {};
  page.loadClubHome = () => {};
  assert.equal(page.data.activeTopTab, 'club');
  discoverNav.markDiscoverFeed(app);
  page.onShow();
  await flush();
  assert.equal(page.data.activeTopTab, 'feed');
  assert.equal(app.globalData.discoverFeedTab, false);
  assert.equal(requests.filter((r) => r.url === '/api/club/post/feed').length, 1);
  assert.equal(discoverNav.consumeDiscoverFeed(app), false);
});

test('帖文通用分享卡 ?tab=feed 直达发现页帖文 tab', async () => {
  const { page, requests } = loadDiscover();
  page.initNav = () => {};
  page.detectMerchant = () => {};
  page.loadClubHome = () => {};
  page.onLoad({ tab: 'feed' });
  await flush();
  assert.equal(page.data.activeTopTab, 'feed');
  assert.equal(requests[0].url, '/api/club/post/feed');
  const { buildSquareShare } = require('../../pages/square/utils/square-share.js');
  assert.equal(buildSquareShare({ id: 1, audience: 'FOLLOWERS' }).path, '/pages/talent/list/index?tab=feed');
});

/* ---------------- 「动态」二级页退场 ---------------- */

test('pages/square/list 已从 app.json 与分包删除,全仓没有代码再跳过去', () => {
  const app = JSON.parse(read('app.json'));
  const square = app.subPackages.find((p) => p.root === 'pages/square');
  assert.deepEqual(square.pages, ['detail/index']);
  assert.equal(Object.prototype.hasOwnProperty.call(app.preloadRule, 'pages/square/list/index'), false);
  assert.equal(fs.existsSync(path.join(ROOT, 'pages/square/list/index.js')), false);
  for (const file of ['pages/play/index.js', 'subpackageB/pages/im/list/index.js', 'pages/square/detail/index.js', 'pages/square/utils/square-share.js']) {
    assert.doesNotMatch(read(file), /\/pages\/square\/list\/index/, file + ' 不再指向动态二级页');
  }
});

test('通关「去发现」、私信空态「去发现找人」、详情返回兜底都先打标记再 switchTab 到发现页', () => {
  const entries = [
    ['pages/play/index.js', /shareFinish\(\) \{[\s\S]*?discoverNav\.markDiscoverFeed\(app\);\s*wx\.switchTab\(\{ url: '\/pages\/talent\/list\/index' \}\);/],
    ['subpackageB/pages/im/list/index.js', /onEmptyCta\(\) \{[\s\S]*?discoverNav\.markDiscoverFeed\(app\); wx\.switchTab\(\{ url: '\/pages\/talent\/list\/index'/],
    ['pages/square/detail/index.js', /goBack\(\) \{[\s\S]*?fail: \(\) => \{ discoverNav\.markDiscoverFeed\(app\); wx\.switchTab\(\{ url: '\/pages\/talent\/list\/index' \}\); \}/],
  ];
  for (const [file, re] of entries) assert.match(read(file), re, file);
  assert.match(read('pages/play/index.wxml'), /bindtap="shareFinish">去发现<\/cy-btn>/);
  assert.match(read('subpackageB/pages/im/list/index.wxml'), /'去发现找人'/);
});

test('我的 → 帖文只读本人帖子(is_my=1 → /me/posts),俱乐部详情只读本俱乐部', () => {
  const profile = read('subpackageMember/components/cy/profile/index.js');
  assert.match(profile, /is_my: that\.data\.isSelf \? 1 : 0/);
  assert.match(read('utils/community-post-api.js'), /data\.is_my \? '\/me\/posts'/);
  const club = read('pages/club/detail/index.js');
  assert.match(club, /\/api\/club\/post\/list/);
  assert.doesNotMatch(club, /scope: 'all'/);
});
