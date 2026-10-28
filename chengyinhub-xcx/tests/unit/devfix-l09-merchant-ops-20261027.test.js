/**
 * 2026-10-27 真机走查修复 · L09 商家经营页(实施文档 M-04/M-07/M-20/G-7/M-21/M-22/M-23/M-26/M-28/M-30)。
 * 跑真代码为主:开放商机计数与合作中心列表同源、营销页首帧即骨架、AI 店铺角色读回性格枚举与只读态、
 * 我的项目长按「编辑」主题;源码断言只钉业主点名的版式与文案。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const strip = (src) => src.replace(/<!--[\s\S]*?-->/g, '');

function loadPage(rel, globals) {
  let definition = null;
  const file = path.join(ROOT, rel);
  vm.runInNewContext(read(rel), Object.assign({
    Page: (value) => { definition = value; },
    getApp: () => ({ globalData: {}, getUserID: () => '7', sendRequest() {}, getRequestErrorMessage: (r, f) => f }),
    wx: { getSystemInfoSync: () => ({ statusBarHeight: 20 }) },
    console,
    setTimeout() {},
    clearTimeout() {},
    require: (id) => require(path.resolve(path.dirname(file), id)),
  }, globals || {}));
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) });
  page.setData = function (patch, cb) {
    Object.keys(patch).forEach((key) => {
      const parts = key.split('.');
      let target = this.data;
      while (parts.length > 1) { const k = parts.shift(); target[k] = target[k] || {}; target = target[k]; }
      target[parts[0]] = patch[key];
    });
    if (typeof cb === 'function') cb();
  };
  return page;
}

// ---------- G-7 / M-20 ----------
test('G-7:共享行组件右侧值单行省略;地址只显示到路名', () => {
  const css = read('components/cy/cell/index.wxss').replace(/\/\*[\s\S]*?\*\//g, '');
  const rule = css.match(/\.cell__value\s*\{[^}]*\}/)[0];
  assert.match(rule, /white-space:\s*nowrap/);
  assert.match(rule, /text-overflow:\s*ellipsis/);
  assert.doesNotMatch(rule, /white-space:\s*normal/);
  const { streetOnly } = require(path.join(ROOT, 'pages/merchant/utils/address-short.js'));
  assert.equal(streetOnly('广东省深圳市南山区科苑路15号科兴科学园A栋'), '科苑路15号');
  assert.equal(streetOnly('杭州市西湖区文三路'), '文三路');
  assert.equal(streetOnly('上海市黄浦区咖啡'), '上海市黄浦区咖啡', '认不出路名就原样交给行省略');
  assert.equal(streetOnly(null), '');
  const npc = strip(read('pages/merchant/decor/ai-npc/index.wxml'));
  assert.doesNotMatch(npc, /填写菜单、招牌与营业信息/);
  assert.match(npc, /knowledge \|\| '菜单与招牌'/);
});

// ---------- M-23 ----------
test('M-23:「N 个开放商机」与合作中心广场列表同一过滤,计数非零则列表非空', () => {
  const { openOpportunityCount, visibleRecruitItems } = require(path.join(ROOT, 'pages/merchant/utils/recruit-routes.js'));
  const now = Date.parse('2026-10-27T08:00:00Z');
  const expired = { id: 1, name: '过期', endDate: '2026-10-01 00:00:00' };
  const nameless = { id: 2, name: ' ' };
  const open = { id: 3, name: '开放', recruitDeadline: '2026-11-30 00:00:00' };
  // 后端 count=2 但端上一条都画不出来 ⇒ 标签不能写「2 个开放商机」
  assert.equal(openOpportunityCount({ count: 2, items: [expired, nameless] }, now), 0);
  assert.equal(openOpportunityCount({ count: 3, items: [expired, nameless, open] }, now), 1);
  // 还有没列出的(hasMoreOpen)且至少一条可点 ⇒ 用后端总数
  assert.equal(openOpportunityCount({ items: [open], hasMoreOpen: true, openCount: 12 }, now), 12);
  assert.equal(openOpportunityCount({ items: [expired], hasMoreOpen: true, openCount: 12 }, now), 0);
  assert.deepEqual(visibleRecruitItems([expired, nameless, open], now).map((i) => i.id), [3]);
  assert.match(read('pages/merchant/coop-center/index.js'), /visibleRecruitItems\(items, now\)/, '广场列表必须走同一过滤');
  assert.match(read('pages/merchant/marketing/index.js'), /recruitCount: openOpportunityCount\(home\.recruiting\)/);
});

test('M-23:营销工具入口「接活动」改「发活动」,直达商家活动发布页且按项目管理权限出', () => {
  const js = read('pages/merchant/marketing/index.js');
  assert.doesNotMatch(js, /title: '接活动'/);
  assert.match(js, /\{ title: '发活动', icon: 'calendar', action: 'publishActivity', permission: 'canManageProjects' \}/);
  const navs = [];
  const page = loadPage('pages/merchant/marketing/index.js', {
    wx: { getSystemInfoSync: () => ({ statusBarHeight: 20 }), navigateTo: (o) => navs.push(o.url) },
  });
  page.go({ currentTarget: { dataset: { action: 'publishActivity' } } });
  assert.deepEqual(navs, ['/pages/publish/activity/index?scope=MERCHANT']);
});

// ---------- M-22 ----------
test('M-22:营销页首帧就是固定高度骨架,失败分支同时撤骨架;刷新胶囊不占文档流', () => {
  const page = loadPage('pages/merchant/marketing/index.js');
  assert.equal(page.data.funnelLoading, true, '首帧必须是骨架,不先画一版 0 再换骨架');
  const wxml = read('pages/merchant/marketing/index.wxml');
  for (const kind of ['hero', 'mine', 'deck']) {
    assert.match(wxml, new RegExp(`<view class="mk-hold mk-hold--${kind}"[^>]*funnelLoading && !marketingLoaded`), `${kind} 骨架缺固定高度占位`);
  }
  const wxss = read('pages/merchant/marketing/index.wxss').replace(/\/\*[\s\S]*?\*\//g, '');
  assert.match(wxss, /\.mk-hold--hero\s*\{\s*height:\s*\d+rpx/);
  assert.match(wxss, /\.marketing-sync\s*\{[^}]*position:\s*absolute/, '刷新提示浮层化,出入不推页面');
  // 登录失败落页内错误时必须撤骨架,否则骨架永远在错误下面转
  const js = read('pages/merchant/marketing/index.js');
  for (const msg of ['登录失败，请重试', '登录已失效，请重新登录', '网络好像出了点小差']) {
    assert.match(js, new RegExp(`entryError: '${msg}'[^}]*funnelLoading: false`), `${msg}:没撤骨架`);
  }
});

// ---------- M-21 ----------
test('M-21:无逐日数据也铺满全浅色方块;比率无数据写 0%;页头无「店主」占位', () => {
  const page = loadPage('pages/merchant/marketing/ai-insight/index.js', {
    wx: { getSystemInfoSync: () => ({ statusBarHeight: 20 }), setNavigationBarColor() {} },
  });
  page._trends = null;
  const hero = page._buildHero({ window: '近30天', attribution: { visitors: 0, checkins: 0, redeems: 0 } }, 30, 'visitors', false);
  assert.equal(hero.cells.length, 30);
  assert.ok(hero.cells.every((c) => c.lv === 0));
  assert.equal(hero.num, '0');
  assert.equal(page._pct(null), '0%');
  const wxml = strip(read('pages/merchant/marketing/ai-insight/index.wxml'));
  assert.doesNotMatch(wxml, /'店主'|'店'/);
  assert.match(wxml, /<block wx:if="\{\{hero && hero\.cells\.length\}\}">/);
  assert.match(read('pages/merchant/marketing/ai-insight/index.wxss'), /\.greet\s*\{[^}]*align-items:\s*center/);
});

// ---------- M-04 ----------
// 2026-10-28 m679/m694 业主:底部菜单统一成 Threads 式(style/threads-menu.wxss)—— 文字左、图标右、不写说明
test('M-04:客户管理半屏 = Threads 式分组菜单(文字 + 右侧图标),按权限出行', () => {
  const wxml = read('pages/merchant/customer/index.wxml');
  const start = wxml.indexOf('<cy-sheet inset show="{{toolsOpen}}"');
  const sheet = wxml.slice(start, wxml.indexOf('</cy-sheet>', start));
  const rows = sheet.match(/<view wx:if="\{\{can\w+\}\}" class="tm-row"/g) || [];
  assert.equal(rows.length, 3);
  assert.equal((sheet.match(/<text class="tm-label">[\s\S]*?<\/text><cy-icon class="tm-ico" name="[a-z-]+"/g) || []).length, 3);
  assert.doesNotMatch(sheet, /cu-op-desc|title="客户管理"/);
  assert.match(read('pages/merchant/customer/index.wxss'), /@import '\.\.\/\.\.\/\.\.\/style\/threads-menu\.wxss';/);
});

// ---------- M-07 ----------
test('M-07:商家优惠券页主按钮「发布优惠券」加载中即在位,空态有提示', () => {
  const wxml = read('subpackageMember/coupon/coupon.wxml');
  assert.match(wxml, /<cy-footer-bar wx:if="\{\{loadState !== 'error' && !tpShow\}\}">\s*<cy-btn variant="primary" bindtap="GoAdd">发布优惠券<\/cy-btn>/);
  assert.match(wxml, /title="还没有优惠券" sub="[^"]+"/);
});

// ---------- M-26 ----------
test('M-26:我的项目长按面板 = Threads 式菜单,编辑在首、删除单独一组标红;草稿主题可编辑', () => {
  const wxml = read('subpackageA/pages/myproject/index.wxml');
  const start = wxml.indexOf('<cy-sheet show="{{opsSheet.show}}"');
  const sheet = wxml.slice(start, wxml.indexOf('</cy-sheet>', start));
  const keys = [...sheet.matchAll(/data-k="(\w+)"/g)].map((m) => m[1]);
  assert.equal(keys[0], 'edit');
  assert.equal(keys[keys.length - 1], 'delete');
  assert.match(sheet, /<view class="tm-group" wx:if="\{\{opsSheet\.canDelete\}\}">\s*<view class="tm-row tm-row--danger"[^>]*data-k="delete"/);
  assert.equal((sheet.match(/<cy-icon class="tm-ico"/g) || []).length, keys.length, '每行都有图标');
  assert.match(read('style/threads-menu.wxss'), /\.tm-row--danger, \.tm-row--danger \.tm-ico \{ color: var\(--cy-color-status-danger\); \}/);
  const js = read('subpackageA/pages/myproject/index.js');
  assert.match(js, /const editableTopic = p\.bizType === 'topic' && \(p\.state === 'draft' \|\| p\.state === 'rejected' \|\| p\.state === 'offline'\);/);
  assert.match(js, /if \(k === 'edit' && p\.biz === 'topic'\) return this\.editTopic\(p\.id\);/);
});

// ---------- M-28 ----------
function npcPage(storage) {
  const store = Object.assign({}, storage);
  return loadPage('pages/merchant/decor/ai-npc/index.js', {
    getApp: () => ({ globalData: {}, getUserID: () => '7', sendRequest() {} }),
    wx: {
      getSystemInfoSync: () => ({ statusBarHeight: 20 }),
      getStorageSync: (k) => store[k],
      setStorageSync: (k, v) => { store[k] = v; },
    },
  });
}

test('M-28:首次进入出说明,知道了之后不再出;行默认只读,编辑态才分派', () => {
  const page = npcPage();
  page._loadProfile = function () {};
  page.onLoad();
  assert.equal(page.data.introVisible, true);
  page.dismissIntro();
  assert.equal(page.data.introVisible, false);
  const again = npcPage({ cy_ai_npc_intro_seen: 1 });
  again._loadProfile = function () {};
  again.onLoad();
  assert.equal(again.data.introVisible, false);

  let opened = '';
  page.editField = (e) => { opened = e.currentTarget.dataset.key; };
  page.onHomeAction({ currentTarget: { dataset: { action: 'editField', key: 'name' } } });
  assert.equal(opened, '', '只读态点行不动');
  page.toggleEditing();
  page.onHomeAction({ currentTarget: { dataset: { action: 'editField', key: 'name' } } });
  assert.equal(opened, 'name');
  page.onHomeAction({ currentTarget: { dataset: { action: 'leavePage' } } });
  const wxml = strip(read('pages/merchant/decor/ai-npc/index.wxml'));
  assert.match(wxml, /<cy-nav-bar title="\{\{view === 'home' \? '' : '上传角色形象'\}\}"/);
  assert.match(wxml, /<text class="dc-brand-title">AI 店铺角色<\/text>/);
  assert.match(wxml, /bindtap="toggleEditing"[^>]*>\{\{editing \? '完成编辑' : '编辑'\}\}/);
  assert.doesNotMatch(wxml, /bind:tap="editField"/, '行不再直接绑编辑');
  assert.match(wxml, /<cy-btn wx:if="\{\{voiceNeedSetting\}\}"[^>]*bindtap="openRecordSetting">去设置开启麦克风/);
});

test('M-28:读回优先认 personality 枚举,其次认 persona 恰好是标签;自由文本原样保留', async () => {
  for (const [data, expectKey, expectPersona] of [
    [{ personality: 'cute', persona: '随便写的' }, 'cute', '可爱'],
    [{ persona: '温柔' }, 'gentle', '温柔'],
    [{ persona: '老朋友口吻' }, '', '老朋友口吻'],
  ]) {
    const page = npcPage({ cy_ai_npc_intro_seen: 1 });
    page.restoreAvatar = function () {};
    page.refreshVoice = function () {};
    page._loadReview = function () {};
    page._request = () => Promise.resolve({ data: Object.assign({ avatar: 'px1:p01', name: '小店长' }, data) });
    page._loadProfile();
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(page.data.personality, expectKey);
    assert.equal(page.data.persona, expectPersona);
    assert.equal(page._saved.persona, expectPersona, '基线与显示一致,不误判脏');
  }
});
