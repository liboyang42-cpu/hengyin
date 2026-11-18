'use strict';
// FE-STORY 5.5 / 7.4:
//   · 队友到访解锁的章(visitedByTeam)卡面写「队友已到访」,不写日期;
//   · 票过期(/api/play/nodes 回 402 或 playable:false):改读 /api/play/history(不带 routeSessionId),
//     只有已到访的章可读,其余锁定、写「已过期，不能再解锁」,没有到访按钮,绝不调 /api/play/arrive。
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const visit = require('../../pages/play/utils/fe-story-visit.js');
const expired = require('../../pages/play/utils/fe-story-expired.js');

const PLAY_PAGE = '../../pages/play/index.js';
let requests;
let store;
let wxCalls;

global.getApp = () => ({
  globalData: { user_id: 9, features: {}, statusBarHeight: 20 },
  getUserID: () => '9',
  isDevEnv: () => false,
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  sendRequest: (request) => { requests.push(request); },
});
global.getCurrentPages = () => [];
global.wx = {
  getStorageSync: (k) => store.get(k),
  setStorageSync: (k, v) => { store.set(k, JSON.parse(JSON.stringify(v))); },
  removeStorageSync: (k) => { store.delete(k); },
  getWindowInfo: () => ({ statusBarHeight: 20, windowWidth: 375, windowHeight: 667 }),
  getSystemInfoSync: () => ({ windowWidth: 375, windowHeight: 667, statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ top: 24, bottom: 56, left: 278, right: 365, width: 87, height: 32 }),
  createSelectorQuery: () => { const q = { in: () => q, select: () => q, selectAll: () => q, boundingClientRect: () => q, fields: () => q, exec: () => {} }; return q; },
  getLocation(o) { wxCalls.push('getLocation'); if (o.fail) o.fail({ errMsg: 'getLocation:fail' }); },
  scanCode() { wxCalls.push('scanCode'); },
  vibrateShort() {},
  showLoading() {}, hideLoading() {}, showToast() {},
};

let pageConfig;
function loadPlayPage() {
  delete require.cache[require.resolve(PLAY_PAGE)];
  require(PLAY_PAGE);
  const page = Object.assign({}, pageConfig, { data: JSON.parse(JSON.stringify(pageConfig.data)) });
  page.setData = function (patch, cb) {
    Object.keys(patch).forEach((k) => {
      if (k.indexOf('.') > 0) { const [a, b] = k.split('.'); this.data[a] = Object.assign({}, this.data[a], { [b]: patch[k] }); }
      else this.data[k] = patch[k];
    });
    if (cb) cb();
  };
  page.rebuild = function () {};   // 卡包排版不在本测范围
  page._playOwner = '9';
  page._isPlayAccountCurrent = () => true;
  return page;
}
const tick = () => new Promise((resolve) => setImmediate(resolve));

const HISTORY = {
  readOnly: true, mode: 2, storyUnlocked: 1, storyTotal: 2,
  chapters: [{ chapterId: 11, name: '第一章', sortId: 1, description: '已到访章节的正文', blocks: [] }],
  nodes: [{ nodeId: 1, name: '示例站点', chapterId: 11, address: '示例路 1 号', description: '地点正文', cardHookLong: '', fragmentText: '碎片一',
    storyText: '', storyImg: '', visitTime: null, visitedByTeam: true }],
};

beforeEach(() => {
  requests = [];
  store = new Map();
  wxCalls = [];
  pageConfig = null;
  global.Page = (config) => { pageConfig = config; };
});

test('队友到访解锁:已解锁卡写「队友已到访」,不写日期;本人到访仍写日期', () => {
  const team = visit.tileView({ storyLocked: false, visitedByTeam: true, visitTime: null });
  assert.equal(team.visitText, '队友已到访');
  const teamWithTime = visit.tileView({ storyLocked: false, visitedByTeam: true, visitTime: '2026-11-17T02:00:00Z' });
  assert.equal(teamWithTime.visitText, '队友已到访', '队友解锁时不写日期');
  const self = visit.tileView({ storyLocked: false, visitedByTeam: false, visitTime: '2026-11-17T02:00:00Z' });
  assert.equal(self.visitText, '11月17日到访');
  const locked = visit.tileView({ storyLocked: true, visitedByTeam: true, visitTime: null });
  assert.equal(locked.visitText, '', '锁卡不写到访说明');
  // normNode 映射 visitedByTeam(只在 mode 2);离线缓存也带上
  const js = read('pages/play/index.js');
  assert.match(js, /visitedByTeam: n\.visitedByTeam === true \} : \{\}\),/);
  assert.match(read('pages/play/utils/fe-story-cache.js'), /'visitedByTeam'\]/);
  const page = loadPlayPage();
  assert.equal(page.normNode({ nodeId: 1, visitedByTeam: true, visited: true }, 0, 2).visitedByTeam, true);
  assert.equal('visitedByTeam' in page.normNode({ nodeId: 1, visitedByTeam: true }, 0, 1), false, '城市定向节点形状不变');
});

test('只读历史拼集章册:只有已到访的章节可读,缓存里见过的其它地点与章节一律锁定且不带正文', () => {
  assert.deepEqual(expired.historyQuery('', '73'), { activityId: 0, topicId: '73' });
  assert.deepEqual(expired.historyQuery('5', 73), { activityId: '5', topicId: '73' });
  assert.equal(expired.historyQuery('5', ''), null);
  assert.equal(expired.merge({ mode: 1, readOnly: true, nodes: [{ nodeId: 1 }] }, null), null, '城市定向不进来');
  assert.equal(expired.merge({ mode: 2, nodes: [{ nodeId: 1 }] }, null), null, '不是只读历史不进来');
  assert.equal(expired.merge(Object.assign({}, HISTORY, { nodes: [], chapters: [] }), null), null, '一处可读的都没有就交给「获取通行证」');
  const cached = { mode: 2, topicName: '示例主题', nodes: [
    { nodeId: 2, name: '另一处', chapterId: 12, hookText: '一句钩子', storyLocked: true },
    { nodeId: 1, name: '示例站点', chapterId: 11, storyLocked: false, fragmentText: '旧碎片' },
  ], chapters: [{ chapterId: 11, name: '第一章' }, { chapterId: 12, name: '第二章', chapterLocked: true }] };
  const book = expired.merge(HISTORY, cached);
  assert.equal(book.readOnly, true);
  assert.deepEqual(book.nodes.map((n) => [n.nodeId, n.storyLocked]), [[2, true], [1, false]], '顺序沿用原来的顺序');
  assert.equal(book.nodes[1].visitedByTeam, true);
  assert.equal(book.nodes[1].fragmentText, '碎片一');
  assert.equal(book.nodes[0].fragmentText, undefined);
  assert.deepEqual(book.chapters.map((c) => [c.chapterId, c.chapterLocked]), [[11, false], [12, true]]);
  assert.equal(book.chapters[0].description, '已到访章节的正文');
  assert.equal('description' in book.chapters[1], false, '锁定章节不带正文');
  assert.equal(book.storyUnlocked, 1);
  assert.equal(book.storyTotal, 2);
  assert.equal(book.topicName, '示例主题');
});

test('402:改读 /api/play/history(不带 routeSessionId),落只读集章册;锁卡点了也绝不调 /api/play/arrive', async () => {
  const page = loadPlayPage();
  page.data.topicId = '73';
  page.loadData(true);
  assert.equal(requests[0].url, '/api/play/nodes');
  requests[0].success({ code: 402, msg: '请先购买自玩通行证' });
  await tick();
  assert.equal(requests.length, 2);
  assert.equal(requests[1].url, '/api/play/history');
  assert.equal(requests[1].method, 'GET');
  assert.deepEqual(requests[1].data, { activityId: 0, topicId: '73' });
  const lockedFromServer = Object.assign({}, HISTORY, { storyTotal: 2 });
  requests[1].success({ code: 200, data: lockedFromServer });
  await tick();
  assert.equal(page.data.emptyKind, '', '不落「获取通行证」空态');
  assert.equal(page.data.storyGate, true);
  assert.equal(page.data.storyExpired, true);
  assert.equal(page.data.storyOffline, false);
  assert.equal(page.data.storyUnlocked, 1);
  assert.deepEqual(page.data.nodes.map((n) => [n.nodeId, n.storyLocked]), [[1, false]], '只有已到访的地点');
  assert.deepEqual(page.data.chapterCards.filter((c) => !c.chapterLocked).map((c) => String(c.id)), ['11'], '只有已到访的章可读');
  assert.ok(store.size >= 1, '只读册也存进 fe-story-cache');

  // 再进一次:缓存里有锁定地点(上一次 /nodes 看到的),过期后按锁定显示
  const key = [...store.keys()].find((k) => k.indexOf('cy_fe_story:') === 0);
  const snap = store.get(key);
  snap.nodes.unshift({ nodeId: 2, name: '另一处', chapterId: 12, hookText: '一句钩子', storyLocked: true, visitCheck: 'GPS' });
  snap.chapters.push({ chapterId: 12, name: '第二章', chapterLocked: true });
  store.set(key, snap);
  requests = [];
  page.loadData();
  requests[0].success({ code: 402, msg: '请先购买自玩通行证' });
  await tick();
  requests[1].success({ code: 200, data: HISTORY });
  await tick();
  assert.deepEqual(page.data.nodes.map((n) => [n.nodeId, n.storyLocked]), [[2, true], [1, false]]);
  assert.equal(page.data.chapterCards.find((c) => String(c.id) === '12').chapterLocked, true);

  // 锁卡:不说明用途、不取定位、不扫码、不发任何写
  requests = [];
  page.onVisitTap({ currentTarget: { dataset: { id: 2 } } });
  await tick();
  assert.deepEqual(wxCalls, []);
  assert.equal(requests.filter((r) => r.url === '/api/play/arrive' || r.url === '/api/play/checkin').length, 0);
  assert.equal(requests.length, 0);
});

test('playable:false 的自由探索同样改读只读历史;历史读不到时 402 照旧落「获取通行证」', async () => {
  const page = loadPlayPage();
  page.data.topicId = '73';
  page.loadData(true);
  requests[0].success({ code: 402, msg: '请先购买自玩通行证' });
  await tick();
  requests[1].success({ code: 403, msg: 'no' });
  await tick();
  assert.equal(page.data.emptyKind, 'needPass');
  const js = read('pages/play/index.js');
  assert.match(js, /storyExpired: mode === 2 && d\.storyTotal != null && d\.playable === false/);
  assert.match(js, /if \(mode === 2 && d\.storyTotal != null && d\.playable === false\) that\._loadFeExpiredHistory\(isCurrent\);/);
});

test('过期只读册的界面:锁卡没有到访按钮,只写「已过期，不能再解锁」;大卡同样;不进玩法', () => {
  const wxml = read('pages/play/index.wxml');
  const lockedTile = wxml.slice(wxml.indexOf('<view class="fx-tile__c" wx:if="{{item.storyLocked}}">'), wxml.indexOf('<view class="fx-tile__c" wx:elif="{{storyGate}}">'));
  assert.match(lockedTile, /<text class="fx-tile__expired" wx:if="\{\{storyExpired\}\}">已过期，不能再解锁<\/text>\s*<view wx:else class="fx-tile__visit/);
  assert.match(wxml, /<view class="fx-hero__cta fx-hero__in" style="--i:3" wx:if="\{\{storyExpired\}\}">\s*<text class="fx-hero__expired" wx:if="\{\{hero\.node\.storyLocked\}\}">已过期，不能再解锁<\/text>\s*<\/view>/);
  assert.equal(visit.EXPIRED_TEXT, '已过期，不能再解锁');
  const gate = read('pages/play/utils/fe-story-gate.js');
  assert.match(gate, /if \(this\.data\.storyExpired \|\| this\._playable === false\) return;/);
  assert.match(read('pages/play/index.js'), /if \(this\.data\.storyExpired\) return;   \/\/ 票过期的只读集章册/);
});
