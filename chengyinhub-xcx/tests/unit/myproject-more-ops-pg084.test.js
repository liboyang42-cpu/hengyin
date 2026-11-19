// PG-084 = A:我的项目卡片右上角「···」与长按打开同一个操作面板(_openOps);
// 「···」显隐用 _hasOps,与 _openOps 的弹出判据一致。F10:自由探索招募中/待定价主题也给「编辑」。
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const PAGE = '../../subpackageA/pages/myproject/index.js';
const ROOT = path.resolve(__dirname, '../..');

let sent = [];
let pageConfig = null;

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: (p) => { sent.push(p); },
  getUserID: () => 1,
  getToken: () => 'current-session',
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
});
global.wx = {
  getStorageSync: () => undefined, setStorageSync: () => {},
  showToast: () => {}, showLoading: () => {}, hideLoading: () => {},
  showModal: (o) => { if (o.success) o.success({ confirm: true }); },
  navigateTo: () => {}, redirectTo: () => {},
  getSystemInfoSync: () => ({ windowWidth: 375 }),
  nextTick: (f) => f(), setNavigationBarColor: () => {}, setNavigationBarTitle: () => {},
};
global.Page = (cfg) => { pageConfig = cfg; };

beforeEach(() => {
  sent = [];
  pageConfig = null;
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
});

function makePage() {
  const inst = Object.assign({}, pageConfig);
  inst.data = JSON.parse(JSON.stringify(pageConfig.data));
  inst.setData = function (patch) {
    for (const [p, value] of Object.entries(patch)) {
      const keys = p.split('.');
      const parent = keys.slice(0, -1).reduce((obj, key) => obj[key], inst.data);
      parent[keys.at(-1)] = value;
    }
  };
  return inst;
}

function topicRow(overrides) {
  return Object.assign({
    id: 501, bizType: 'topic', title: '示例主题', state: 'running', stateText: '进行中',
    ownerType: 'member', signupCount: 0, viewCount: 0, acceptStatus: 'selfRun',
  }, overrides);
}

function loadTopics(page, rows) {
  page.setData({ typeTab: 'topic' });
  page.loadProjects();
  const req = sent.find((r) => r.url === '/api/project/my');
  req.success({ code: '200', data: { rows: rows, total: rows.length } });
  sent = [];
  return page.data.list;
}

test('卡片 _hasOps 与 _openOps 判据一致:有动作才出「···」,无动作既不出按钮也不弹面板', () => {
  const page = makePage();
  const list = loadTopics(page, [
    topicRow({ id: 1, state: 'draft' }),
    topicRow({ id: 2, state: 'pending' }),
  ]);
  const draft = list.find((r) => r.id === 1);
  const pending = list.find((r) => r.id === 2);
  assert.equal(draft._hasOps, true);
  assert.equal(page._hasOps(draft), true);
  // pending 主题:不可上下架、不可删、不可编辑、无审核记录 —— 一个动作都没有
  assert.equal(pending._hasOps, page._hasOps(pending));
  page._openOps(pending);
  assert.equal(page.data.opsSheet.show === true, pending._hasOps === true,
    '_openOps 是否弹面板必须与 _hasOps 同判据');
  for (const row of list) assert.equal(row._hasOps, page._hasOps(row));
});

test('点「···」与长按打开同一个面板(同一组行)', () => {
  const page = makePage();
  const [row] = loadTopics(page, [topicRow({ id: 7, state: 'offline' })]);
  page.onCardMore({ currentTarget: { dataset: { item: row } } });
  const viaMore = JSON.parse(JSON.stringify(page.data.opsSheet));
  page.closeOpsSheet();
  page.onCardLong({ currentTarget: { dataset: { item: row } } });
  const viaLong = JSON.parse(JSON.stringify(page.data.opsSheet));
  assert.equal(viaMore.show, true);
  assert.deepEqual(viaLong, viaMore);
});

test('F10:自由探索招募中/待定价主题给「编辑」,其他在售主题仍不给', () => {
  const page = makePage();
  const list = loadTopics(page, [
    topicRow({ id: 11, freeExplore: true, lifecycle: 1 }),
    topicRow({ id: 12, freeExplore: true, lifecycle: 2 }),
    topicRow({ id: 13, freeExplore: true, lifecycle: 3 }),
    topicRow({ id: 14, freeExplore: false, lifecycle: 2 }),
  ]);
  const byId = (id) => list.find((r) => r.id === id);
  assert.equal(byId(11)._canEdit, true);
  assert.equal(byId(12)._canEdit, true);
  assert.equal(byId(13)._canEdit, false);
  assert.equal(byId(14)._canEdit, false);
  // 编辑资格仍以服务端编辑窗口为准:打开面板时回读 /api/project/home 的 host.canEdit
  page.onCardMore({ currentTarget: { dataset: { item: byId(12) } } });
  const home = sent.find((r) => r.url === '/api/project/home');
  assert.ok(home, '主题编辑资格必须回读服务端');
  home.success({ code: '200', data: { role: 'host', host: { canEdit: true } } });
  assert.equal(page.data.opsSheet.canEdit, true);
});

test('卡面:「···」用 catchtap(不冒泡触发 onCardTap),长按保留,提示行已删除', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'subpackageA/pages/myproject/index.wxml'), 'utf8');
  const wxss = fs.readFileSync(path.join(ROOT, 'subpackageA/pages/myproject/index.wxss'), 'utf8');
  assert.match(wxml, /<view class="mp-card-more" wx:if="\{\{item\._hasOps\}\}" catchtap="onCardMore"[^>]*aria-role="button"[^>]*aria-label="更多">···<\/view>/);
  assert.doesNotMatch(wxml, /bindtap="onCardMore"/);
  assert.match(wxml, /bindlongpress="onCardLong"/);
  assert.doesNotMatch(wxml, /长按卡片管理|mp-longtip/);
  assert.doesNotMatch(wxss, /mp-longtip/);
  assert.doesNotMatch(wxml, /整行可点,不放按钮/);
  const rule = wxss.match(/\.mp-card-more\s*\{([^}]*)\}/);
  assert.ok(rule, '缺少 .mp-card-more');
  assert.match(rule[1], /min-width:\s*var\(--cy-btn-h\)/);
  assert.match(rule[1], /min-height:\s*var\(--cy-btn-h\)/);
});
