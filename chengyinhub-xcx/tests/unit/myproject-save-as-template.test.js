// BL-120:「我的项目」主题卡长按面板 →「另存为模板」(POST /api/template/topic-template/save-as)。
// 不变量:只给审核通过派生态的主题卡出这一项;二次确认后才发请求;入参只有 id + scope;
// 失败按后端文案提示;在途守卫防重复点。
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PAGE = '../../subpackageA/pages/myproject/index.js';

let sent = [];
let toasts = [];
let modalAnswer = true;
let modals = [];

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: (p) => { sent.push(p); },
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  getUserID: () => 1,
  getToken: () => 'fixture-session',
});
global.wx = {
  getStorageSync: () => undefined,
  setStorageSync: () => {},
  showToast: (o) => { toasts.push(o && o.title); },
  showLoading: () => {}, hideLoading: () => {},
  showModal: (o) => { modals.push(o); if (o.success) o.success({ confirm: modalAnswer, cancel: !modalAnswer }); },
  navigateTo: () => {}, redirectTo: () => {},
  getSystemInfoSync: () => ({ windowWidth: 375 }),
  nextTick: (f) => f(),
  setNavigationBarColor: () => {}, setNavigationBarTitle: () => {},
};

let pageConfig = null;
global.Page = (cfg) => { pageConfig = cfg; };

beforeEach(() => {
  sent = []; toasts = []; modals = []; modalAnswer = true;
  pageConfig = null;
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
});

function makePage() {
  const inst = Object.assign({}, pageConfig);
  inst.data = JSON.parse(JSON.stringify(pageConfig.data));
  inst.setData = function (patch) {
    Object.keys(patch).forEach((k) => {
      if (k.indexOf('.') > 0) { const [a, b] = k.split('.'); inst.data[a][b] = patch[k]; }
      else inst.data[k] = patch[k];
    });
  };
  inst.reloads = 0;
  inst.reload = function () { inst.reloads++; };
  inst.loadTemplates = function () {};
  return inst;
}

function loadRows(page, rows) {
  page.loadProjects();
  const req = sent.pop();
  req.success({ code: '200', data: { rows: rows, total: rows.length } });
  return page._all;
}

test('只有审核通过派生态的主题卡可另存为模板', () => {
  const page = makePage();
  const all = loadRows(page, [
    { id: 1, bizType: 'topic', state: 'running' },
    { id: 2, bizType: 'topic', state: 'notStarted' },
    { id: 3, bizType: 'topic', state: 'offline' },
    { id: 4, bizType: 'topic', state: 'completed' },
    { id: 5, bizType: 'topic', state: 'draft' },
    { id: 6, bizType: 'topic', state: 'pending' },
    { id: 7, bizType: 'topic', state: 'rejected' },
    { id: 8, bizType: 'activity', state: 'running' },
  ]);
  const can = all.filter((r) => r._canSaveAsTemplate).map((r) => r.id);
  assert.deepEqual(can, [1, 2, 3, 4]);
});

test('长按面板带出 canSaveAsTemplate;选中后先确认再发 save-as', () => {
  const page = makePage();
  const all = loadRows(page, [{ id: 11, bizType: 'topic', state: 'running', title: '夜游' }]);
  page.onCardLong({ currentTarget: { dataset: { item: all[0] } } });
  assert.equal(page.data.opsSheet.canSaveAsTemplate, true);

  page.onOpsPick({ currentTarget: { dataset: { k: 'saveAsTemplate' } } });
  assert.equal(modals.length, 1, '必须二次确认');
  assert.equal(sent.length, 1);
  assert.equal(sent[0].url, '/api/template/topic-template/save-as');
  assert.equal(sent[0].method, 'POST');
  assert.deepEqual(Object.keys(sent[0].data).sort(), ['id', 'scope']);
  assert.equal(sent[0].data.id, 11);

  sent[0].success({ code: '200', msg: '已另存为主题模板' });
  assert.equal(page.reloads, 1, '成功后回读列表');
  assert.equal(toasts.pop(), '已另存为主题模板');
});

test('取消确认不发请求', () => {
  const page = makePage();
  modalAnswer = false;
  page.saveAsTemplate({ currentTarget: { dataset: { id: 12, title: 'x' } } });
  assert.equal(sent.length, 0);
});

test('失败按后端文案如实提示,不回读', () => {
  const page = makePage();
  page.saveAsTemplate({ currentTarget: { dataset: { id: 13, title: 'x' } } });
  sent[0].success({ code: '500', msg: '仅审核通过的主题可另存为模板' });
  assert.equal(toasts.pop(), '仅审核通过的主题可另存为模板');
  assert.equal(page.reloads, 0);
});

test('在途守卫:同一主题重复确认只发一次,结束后放行', () => {
  const page = makePage();
  page.saveAsTemplate({ currentTarget: { dataset: { id: 14 } } });
  page.saveAsTemplate({ currentTarget: { dataset: { id: 14 } } });
  assert.equal(sent.length, 1);
  sent[0].fail({});
  page.saveAsTemplate({ currentTarget: { dataset: { id: 14 } } });
  assert.equal(sent.length, 2);
});
