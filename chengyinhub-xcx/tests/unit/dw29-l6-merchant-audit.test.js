// 2026-10-29 走查 lane l6:商家 M-01～M-35 核对后本轮补的三项
// (docs/delivery/device-walkthrough-20261029/merchant-audit.md)。
// M-25 订单页商家视角改去「我的项目」;G-10 / M-35 商家收益明细整页浅色;M-30 品牌中心承接摘要右对齐。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

function loadPage(rel, { role }) {
  const calls = [];
  let def = null;
  const saved = { Page: global.Page, getApp: global.getApp, wx: global.wx };
  global.Page = (d) => { def = d; };
  global.getApp = () => ({
    globalData: {},
    getUserRole: () => role,
    getUserType: () => '',
  });
  global.wx = {
    getStorageSync: () => '',
    redirectTo: (o) => calls.push(['redirectTo', o.url]),
    navigateTo: (o) => calls.push(['navigateTo', o.url]),
    navigateBack: () => {},
    setNavigationBarColor: (o) => calls.push(['navColor', o.frontColor]),
    setBackgroundColor: () => {},
    setBackgroundTextStyle: () => {},
    setTabBarStyle: () => {},
  };
  const file = path.join(ROOT, rel);
  for (const k of Object.keys(require.cache)) {
    if (k.startsWith(path.join(ROOT, 'utils')) || k === file) delete require.cache[k];
  }
  try {
    require(file);
  } finally {
    Object.assign(global, saved);
  }
  const page = Object.assign({ data: JSON.parse(JSON.stringify(def.data || {})) }, def);
  page.setData = function (patch) { Object.assign(this.data, patch); };
  return { page, calls, env: () => {
    global.getApp = () => ({ globalData: {}, getUserRole: () => role, getUserType: () => '' });
    global.wx = Object.assign({}, global.wx || {}, {
      getStorageSync: () => '',
      redirectTo: (o) => calls.push(['redirectTo', o.url]),
      setNavigationBarColor: (o) => calls.push(['navColor', o.frontColor]),
      setBackgroundColor: () => {}, setBackgroundTextStyle: () => {}, setTabBarStyle: () => {},
    });
  } };
}

function withEnv(loaded, fn) {
  const saved = { getApp: global.getApp, wx: global.wx };
  loaded.env();
  try { return fn(); } finally { Object.assign(global, saved); }
}

test('M-25:商家视角打开订单页直接换成「我的项目」(商家 scope),不渲染订单', () => {
  const loaded = loadPage('subpackageMember/order/order.js', { role: 'merchant' });
  withEnv(loaded, () => loaded.page.onLoad({ detailId: '42' }));
  assert.deepEqual(loaded.calls.filter((c) => c[0] === 'redirectTo'),
    [['redirectTo', '/subpackageA/pages/myproject/index?scope=MERCHANT']]);
  assert.equal(loaded.page.data.sceneCurrent, null, '商家视角不打开订单详情');
});

test('M-25:玩家视角订单页照旧(带 detailId 打开订单详情,不跳转)', () => {
  const loaded = loadPage('subpackageMember/order/order.js', { role: 'player' });
  withEnv(loaded, () => loaded.page.onLoad({ detailId: '42' }));
  assert.equal(loaded.calls.filter((c) => c[0] === 'redirectTo').length, 0);
  assert.equal(loaded.page.data.sceneCurrent.id, 'member-order-detail');
});

test('G-10 / M-35:商家从账户收益进收益明细带 theme=merchant', () => {
  const js = read('components/cy/scene-asset-earnings/index.js');
  const m = js.match(/openIncomeDetail\(\)\s*\{([^\n]*)\n/);
  assert.ok(m, '缺少 openIncomeDetail');
  assert.match(m[1], /income-detail\/income-detail' \+ \(this\.data\.theme === 'merchant' \? '\?theme=merchant' : ''\)/);
});

test('G-10 / M-35:收益明细页 theme=merchant 时根节点浅色、导航栏浅色;玩家仍 theme-dark', () => {
  const wxml = read('subpackageA/pages/assetcenter/income-detail/income-detail.wxml');
  assert.match(wxml, /<view class="\{\{merchantView \? 'theme-merchant' : 'theme-dark'\}\}">/);
  assert.match(wxml, /<cy-scene-asset-income-detail id="incomeDetail" surface="page" theme="\{\{merchantView \? 'merchant' : 'player'\}\}" \/>/);
  const wxss = read('subpackageA/pages/assetcenter/income-detail/income-detail.wxss');
  assert.match(wxss, /@import '..\/..\/..\/..\/style\/merchant-light-scope\.wxss';/);
  assert.match(wxss, /\.theme-dark, \.theme-merchant \{ flex: 1;[^}]*background: var\(--cy-color-bg-page\)/);

  const merchant = loadPage('subpackageA/pages/assetcenter/income-detail/income-detail.js', { role: 'merchant' });
  withEnv(merchant, () => { merchant.page.onLoad({ theme: 'merchant' }); merchant.page.onShow(); });
  assert.equal(merchant.page.data.merchantView, true);
  const player = loadPage('subpackageA/pages/assetcenter/income-detail/income-detail.js', { role: 'player' });
  withEnv(player, () => { player.page.onLoad({}); player.page.onShow(); });
  assert.equal(player.page.data.merchantView, false);
  const navOf = (l) => l.calls.filter((c) => c[0] === 'navColor').map((c) => c[1]).pop();
  assert.notEqual(navOf(merchant), navOf(player), '商家与玩家的原生导航栏前景色不同档');
});

test('M-30:品牌中心「合作经营」的承接摘要放进「承接设置」行内,与合作诉求一样右对齐', () => {
  const wxml = read('pages/merchant/decor/index.wxml');
  const start = wxml.indexOf('让合作发生在店里');
  const block = wxml.slice(start, wxml.indexOf('title="合作诉求"', start));
  assert.match(block,
    /<view class="dc-heading" bindtap="goCoopSetting"[^>]*>\s*承接设置\s*<text class="dc-muted">\{\{coopSummary \|\| '完善你的承接能力'\}\}<\/text>\s*<\/view>/);
  const css = read('pages/merchant/decor/design.wxss').replace(/\/\*[\s\S]*?\*\//g, '');
  assert.match(css, /\.dc-heading \{[^}]*justify-content: space-between/);
});
