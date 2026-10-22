// L21 · 交 Codex 遗留:fabu「新建玩法」onCreateGame 连点只跳一次;complete 回调后可再跳。
// 同 BL-230…254 的 _navigating 写法(例:pages/merchant/index goManageCoupon)。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');

const ROOT = path.resolve(__dirname, '../..');
const FABU_JS = path.join(ROOT, 'pages/publish/fabu/index.js');
const noop = () => {};

function loadFabu() {
  const navs = [];
  const wx = {
    getStorageSync: () => '', setStorageSync: noop, removeStorageSync: noop,
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
    showToast: noop, hideLoading: noop, showLoading: noop, showModal: noop,
    navigateTo: (options) => navs.push(options),
  };
  const app = { globalData: {}, getUserID: () => 42, sendRequest: noop, tips: noop };
  global.getApp = () => app;
  global.wx = wx;
  let definition;
  vm.runInNewContext(fs.readFileSync(FABU_JS, 'utf8'), {
    getApp: () => app, Page(p) { definition = p; }, Component(p) { definition = p; },
    getCurrentPages: () => [{}], require: createRequire(FABU_JS),
    setTimeout: () => 1, clearTimeout: noop, setInterval: () => 1, clearInterval: noop,
    console, Date, JSON, wx,
  }, { filename: FABU_JS });
  const page = Object.assign({}, definition, { data: { formData: { name: '城市漫游' }, nodesForm: { name: '钟楼' } } });
  page.setData = (patch) => Object.assign(page.data, patch);
  return { page, navs };
}

test('onCreateGame 连点只打开一层玩法编辑页,complete 后放开', () => {
  const { page, navs } = loadFabu();
  const tap = () => page.onCreateGame({ currentTarget: { dataset: {} } });
  tap(); tap(); tap();
  assert.equal(navs.length, 1, '跳转在途时连点不再叠页');
  assert.match(navs[0].url, /^\/pages\/publish\/temp\/index\?from=fabu&themeName=/);
  assert.equal(typeof navs[0].events.templateCreated, 'function', '回传绑定的事件通道不能丢');
  navs[0].complete();
  tap();
  assert.equal(navs.length, 2, 'complete(成功或失败)后可以再进');
});

test('onCreateGame 守卫在跳转之前、只在源码里出现一次(不改其他入口)', () => {
  const src = fs.readFileSync(FABU_JS, 'utf8');
  const body = src.slice(src.indexOf('  onCreateGame: function (e) {'), src.indexOf('  getPublicTempList: function () {'));
  assert.ok(body.indexOf('if (this._navigating) return;') < body.indexOf('wx.navigateTo('));
  assert.match(body, /complete: \(\) => \{ this\._navigating = false; \}/);
});
