// check-1104 p2 F-3(第十轮 f1 第 10 项):工作台「全部 N」与点进去的「我的项目」(scope=MERCHANT)必须同数。
// 同一组接口回包分别喂给工作台与我的项目两张真页面,工作台 hostProjectTotal + joinTotal == 我的项目里未结束的卡数。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');

function setPath(target, dotted, value) {
  const parts = dotted.split('.');
  let cursor = target;
  parts.slice(0, -1).forEach((part) => { cursor = cursor[part] || (cursor[part] = {}); });
  cursor[parts.at(-1)] = value;
}

function harness(relativePath) {
  const requests = [];
  let definition;
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44, menuButtonInfo: { left: 300 } },
    sendRequest(options) { requests.push(options); },
    getRequestErrorMessage(_res, fallback) { return fallback; },
    getUserID() { return 7; },
    getUserRole() { return 'merchant'; },
    getUserType() { return 1; },
    getPageSize() { return 10; },
    getTotalPage() { return 1; },
    setUserRole() {},
    getImgUrl(value) { return value || ''; },
    getAuthorization: () => 'current-session',
  };
  global.getApp = () => app;
  global.wx = {
    getStorageSync() { return ''; },
    getAccountInfoSync() { return { miniProgram: { envVersion: 'release' } }; },
    showToast() {}, showLoading() {}, hideLoading() {}, stopPullDownRefresh() {}, pageScrollTo() {},
    navigateTo() {},
  };
  global.Page = (value) => { definition = value; };
  global.Component = (value) => { definition = value; };
  global.Behavior = (value) => value;
  const modulePath = path.join(ROOT, relativePath);
  delete require.cache[require.resolve(modulePath)];
  require(modulePath);
  const vm = Object.assign({}, definition);
  vm.data = JSON.parse(JSON.stringify(definition.data || {}));
  vm.setData = function (patch, callback) {
    Object.entries(patch).forEach(([key, value]) => setPath(this.data, key, value));
    if (callback) callback();
  };
  vm.triggerEvent = function () {};
  return { vm, requests };
}

const DAY = 24 * 3600 * 1000;
const iso = (offsetDays) => new Date(Date.now() + offsetDays * DAY).toISOString().slice(0, 10) + ' 10:00:00';
const LEGACY = [
  { id: 1, topicId: 11, status: 1, topicName: 'E2E 探店日一期', startDate: iso(-1), endDate: iso(3) },
  { id: 2, topicId: 12, status: 0, topicName: '审核中的承接', startDate: iso(2), endDate: iso(4) },
  { id: 3, topicId: 13, status: 1, topicName: '已结束的承接', startDate: iso(-9), endDate: iso(-8) },
];
const CHAPTER = [
  { topicId: 11, status: 1, chapterName: '一章', topicName: 'E2E 探店日一期' },
  { topicId: 14, status: 1, chapterName: '一章', topicName: '章节承接主题' },
  { topicId: 14, status: 1, chapterName: '二章', topicName: '章节承接主题' },
  { topicId: 15, status: 0, chapterName: '一章', topicName: '未通过的章节' },
];
const HOST = { rows: [{ id: 21, bizType: 'topic', title: '自己的主题', state: 'running' }], total: 1, activeTotal: 1 };

function respond(requests, url, data) {
  const request = requests.find((r) => r.url === url && !r._answered);
  assert.ok(request, '未发出请求 ' + url);
  request._answered = true;
  request.success({ code: '200', data });
}

test('工作台「全部 N」== 我的项目(商家)里未结束的项目卡数,承接项目都在列表里', () => {
  const home = harness('pages/merchant/index/index.js');
  home.vm._dataEpoch = 0;
  home.vm.refreshProjectCards = function () {};
  home.vm.loadJoinList(0);
  home.vm.loadChapterProjectList(0);
  home.vm.loadHostProjects(0);
  respond(home.requests, '/api/registration/merchant/list', { rows: LEGACY });
  respond(home.requests, '/api/merchant/chapter-application/mine', CHAPTER);
  respond(home.requests, '/api/project/my', HOST);
  const homeCount = home.vm.data.joinTotal + home.vm.data.hostProjectTotal;
  assert.equal(homeCount, 3, '自己的主题 1 + 报名承接(未结束、已审核)1 + 章节独有主题 1');

  const list = harness('subpackageA/pages/myproject/index.js');
  list.vm.setData({ operationScope: 'MERCHANT' });
  list.vm.loadProjects();
  respond(list.requests, '/api/project/my', HOST);
  respond(list.requests, '/api/registration/merchant/list', { rows: LEGACY });
  respond(list.requests, '/api/merchant/chapter-application/mine', CHAPTER);
  const rows = list.vm._all;
  const unended = rows.filter((p) => p.state !== 'completed');
  assert.equal(unended.length, homeCount, '列表与工作台计数一致');
  assert.deepEqual(rows.filter((p) => p._join).map((p) => p.id).sort(), [11, 14]);
  const join = rows.find((p) => p.id === 11);
  assert.equal(join._roleText, '承办');
  assert.equal(join.stateText, '进行中', '状态签与工作台承接卡同源');
  assert.equal(join._showAccept, true);
  assert.equal(join.acceptStatusText, '我承接的', '承接卡不印 0 报名 · 0 浏览');
  assert.equal(join._canDelete || join._canToggle || join._canEdit || join._canCancel, false, '承接卡只读');
  assert.equal(list.vm.data.projectState, 'ready');
  assert.equal(list.vm.data.list.length, 3, '主题 tab 直接看到三张卡');
  const navigations = [];
  global.wx.navigateTo = options => navigations.push(options.url);
  list.vm.onCardTap({ currentTarget: { dataset: { item: join } } });
  assert.equal(navigations[0], '/pages/topic/merchantinfo/merchantinfo?topicId=11&scope=MERCHANT',
    '已承接项目继续走服务端private项目主页');
});

test('任一路承接读取失败:落错误态,不出半截列表', () => {
  const list = harness('subpackageA/pages/myproject/index.js');
  list.vm.setData({ operationScope: 'MERCHANT' });
  list.vm.loadProjects();
  respond(list.requests, '/api/project/my', HOST);
  respond(list.requests, '/api/registration/merchant/list', { rows: LEGACY });
  const chapter = list.requests.find((r) => r.url === '/api/merchant/chapter-application/mine');
  chapter.success({ code: '500', msg: '系统繁忙' });
  assert.equal(list.vm.data.projectState, 'error');
  assert.equal(list.vm.data.list.length, 0);
});

test('非商家态不读承接接口', () => {
  const list = harness('subpackageA/pages/myproject/index.js');
  list.vm.loadProjects();
  respond(list.requests, '/api/project/my', HOST);
  assert.equal(list.requests.length, 1);
  assert.equal(list.vm.data.list.length, 1);
});


test('本人待接受章节邀请在全部项目可见且保持只读，不冒称已承接', () => {
  const list = harness('subpackageA/pages/myproject/index.js');
  list.vm.setData({ operationScope: 'MERCHANT' });
  list.vm.loadProjects();
  respond(list.requests, '/api/project/my', { rows: [], total: 0, activeTotal: 0 });
  respond(list.requests, '/api/registration/merchant/list', { rows: [
    { id: 9, topicId: 990041, status: 1, endDate: iso(3) },
  ] });
  respond(list.requests, '/api/merchant/chapter-application/mine', [
    { id: 5, topicId: 990041, status: 1, offerActive: true },
    { id: 1, topicId: 990041, topicName: '待接受邀请', status: '0', source: '1', offerActive: false },
    { id: 2, topicId: 990042, status: 0, source: 0 },
    { id: 3, topicId: 990043, status: 2, source: 1 },
  ]);
  assert.equal(list.vm.data.list.length, 1);
  const card = list.vm.data.list[0];
  assert.equal(card.id, 990041);
  assert.equal(card.stateText, '待接受');
  assert.equal(card.acceptStatusText, '待接受邀请');
  assert.equal(card._roleText, '受邀');
  assert.equal(card._canDelete || card._canToggle || card._canEdit || card._canCancel, false);
  const navigations = [];
  global.wx.navigateTo = options => navigations.push(options.url);
  list.vm.onCardTap({ currentTarget: { dataset: { item: card } } });
  assert.equal(navigations[0], '/pages/topic/merchantinfo/merchantinfo?id=990041&scope=MERCHANT',
    '同topic已有legacy承接时，新邀请仍进入既有招商浏览/邀请处理入口');
});
