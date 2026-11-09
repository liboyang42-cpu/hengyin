// 我的项目：未开卖已发布活动给出「编辑」；已开卖只保留上下架/取消，点编辑留在本页说明。
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PAGE = '../../subpackageA/pages/myproject/index.js';

let sent = [];
let toasts = [];
let navigations = [];
let pageConfig = null;

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: (p) => { sent.push(p); },
  getUserID: () => 1,
  getToken: () => 'current-session',
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
});
global.wx = {
  getStorageSync: () => undefined,
  setStorageSync: () => {},
  showToast: (o) => { toasts.push(o && o.title); },
  showLoading: () => {},
  hideLoading: () => {},
  showModal: (o) => { if (o.success) o.success({ confirm: true }); },
  navigateTo: (o) => { navigations.push(o.url); },
  redirectTo: (o) => { navigations.push(o.url); },
  getSystemInfoSync: () => ({ windowWidth: 375 }),
  nextTick: (f) => f(),
  setNavigationBarColor: () => {},
  setNavigationBarTitle: () => {},
};

global.Page = (cfg) => { pageConfig = cfg; };

beforeEach(() => {
  sent = [];
  toasts = [];
  navigations = [];
  pageConfig = null;
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
});

function makePage() {
  const inst = Object.assign({}, pageConfig);
  inst.data = JSON.parse(JSON.stringify(pageConfig.data));
  inst.setData = function (patch) {
    for (const [path, value] of Object.entries(patch)) {
      const keys = path.split('.');
      const parent = keys.slice(0, -1).reduce((obj, key) => obj[key], inst.data);
      parent[keys.at(-1)] = value;
    }
  };
  return inst;
}

function activityRow(overrides) {
  return Object.assign({
    id: 301,
    bizType: 'activity',
    title: '外滩夜行',
    projectTypeText: '俱乐部活动',
    state: 'running',
    stateText: '进行中',
    ownerType: 'club',
    signupCount: 0,
    viewCount: 12,
  }, overrides);
}

function loadActivities(page, rows) {
  page.setData({ typeTab: 'activity' });
  page.loadProjects();
  sent[0].success({ code: '200', data: { rows: rows, total: rows.length } });
}

test('未开卖已发布活动显示编辑，点编辑进发布页带 id', () => {
  const page = makePage();
  loadActivities(page, [activityRow({ signupCount: 0, state: 'notStarted' })]);

  const card = page.data.list[0];
  assert.equal(card._canEdit, true);
  assert.equal(card._sold, false);

  page.editActivity({ currentTarget: { dataset: { item: card } } });
  assert.equal(navigations[0], '/pages/publish/activity/index?id=301');
});

test('已开卖活动点编辑留在本页并说明，不进表单；上下架仍在', () => {
  const page = makePage();
  loadActivities(page, [activityRow({ signupCount: 2, state: 'running' })]);

  const card = page.data.list[0];
  assert.equal(card._canEdit, true, '已售仍显示编辑，点下去才说明不能改');
  assert.equal(card._canToggle, true);
  assert.equal(card._sold, true);

  page.editActivity({ currentTarget: { dataset: { item: card } } });
  assert.equal(navigations.length, 0, '已开卖不得打开编辑表单');
  assert.ok(toasts.some((t) => t && String(t).indexOf('已有人买票，不能再改这场活动') >= 0),
    '必须留在本页并说明原因，实际:' + JSON.stringify(toasts));
});


test('待核销统计只有明确非负整数才展示，缺值不得冒充零', () => {
  const page = makePage()
  const values = [undefined, null, '', ' ', false, -1, 1.5, 'invalid', 0, '0', 3]
  loadActivities(page, values.map((value, index) => activityRow({ id: index + 1, pendingVerifyCount: value })))
  assert.deepEqual(page.data.list.map(row => row._showPendingVerify),
    [false, false, false, false, false, false, false, false, true, true, true])
  assert.deepEqual(page.data.list.slice(-3).map(row => row._pendingVerifyText), ['待核销 0', '待核销 0', '待核销 3'])
  assert.equal(page.data.list[1]._pendingVerifyText, '')
})


function openTopicMenu(page, state = 'offline', id = 990039) {
  page.setData({ typeTab: 'topic' });
  page.loadProjects();
  sent.at(-1).success({ code: 200, data: { rows: [{ id, bizType: 'topic', state, title: '主题' }], total: 1 } });
  if (page.data.operationScope === 'MERCHANT') {
    sent.findLast(r => r.url === '/api/registration/merchant/list').success({ code: 200, data: [] });
    sent.findLast(r => r.url === '/api/merchant/chapter-application/mine').success({ code: 200, data: [] });
  }
  page.onCardLong({ currentTarget: { dataset: { item: page.data.list[0] } } });
  const request = sent.at(-1);
  assert.equal(request.url, '/api/project/home');
  assert.deepEqual(JSON.parse(request.data), { topicId: id, scope: page.data.operationScope });
  return request;
}

test('已下架主题菜单在服务端拒绝编辑时不提供编辑，其余管理动作保留', () => {
  const page = makePage();
  const request = openTopicMenu(page);
  assert.equal(page.data.opsSheet.canEdit, false, '资格返回前不承诺可编辑');
  request.success({ code: 200, data: { role: 'host', host: { canEdit: false, editReason: '主题已开始，内容已锁定' } } });
  assert.equal(page.data.opsSheet.canEdit, false);
  assert.equal(page.data.opsSheet.canToggle, true);
  assert.equal(page.data.opsSheet.canDelete, true);
});

test('草稿、驳回和下架主题仅凭已有服务端门禁显示编辑并保留商家 scope', () => {
  for (const state of ['draft', 'rejected', 'offline']) {
    const page = makePage();
    page.data.operationScope = 'MERCHANT';
    openTopicMenu(page, state).success({ code: 200, data: { role: 'host', host: { canEdit: true } } });
    assert.equal(page.data.opsSheet.canEdit, true, state);
    page.onOpsPick({ currentTarget: { dataset: { k: 'edit' } } });
    assert.equal(navigations.at(-1), '/pages/publish/fabu/index?id=990039&scope=MERCHANT');
  }
});

test('主题编辑门禁缺失、非布尔许可、承接身份或失败均不提供编辑', () => {
  for (const data of [null, {}, { role: 'host', host: {} }, { role: 'host', host: { canEdit: 'true' } },
    { role: 'join', host: { canEdit: true } }]) {
    const page = makePage();
    openTopicMenu(page).success({ code: 200, data });
    assert.equal(page.data.opsSheet.canEdit, false);
  }
  for (const callback of ['fail', 'successStatusAbnormal', 'success']) {
    const page = makePage();
    openTopicMenu(page)[callback]({ code: 500, msg: '加载失败' });
    assert.equal(page.data.opsSheet.canEdit, false);
    assert.equal(toasts.at(-1), '加载失败');
  }
});

test('迟到门禁回包不能覆盖新菜单、重开菜单、关闭菜单或失效会话', () => {
  const allowed = { code: 200, data: { role: 'host', host: { canEdit: true } } };
  for (const change of ['other', 'reopen', 'close', 'hidden', 'session']) {
    const page = makePage();
    const stale = openTopicMenu(page);
    if (change === 'other') openTopicMenu(page, 'offline', 990040);
    if (change === 'reopen') { page.closeOpsSheet(); openTopicMenu(page); }
    if (change === 'close') page.closeOpsSheet();
    if (change === 'hidden') page._visible = false;
    if (change === 'session') page._readEpoch = (page._readEpoch || 0) + 1;
    stale.success(allowed);
    assert.equal(page.data.opsSheet.canEdit, false, change);
  }
});
