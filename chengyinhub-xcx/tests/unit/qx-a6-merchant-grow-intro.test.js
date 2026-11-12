'use strict';
// qx-a6(2026-11-09):走查 v2 W2-15 经营优化带图卡 / W2-16 AI 店铺角色首次介绍;r2-merchant(2026-11-10)改横滑、回到 R12 版并分步动效
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const { GROW_TIPS, visibleGrowTips, growTipUrl } = require('../../pages/merchant/utils/grow-tips.js');

test('W2-15:经营优化卡只在守卫放行且岗位有对应权限时出现', () => {
  assert.deepEqual(visibleGrowTips(null), [], '没确认身份不画入口');
  assert.deepEqual(visibleGrowTips({ active: false, canManageCoop: true }), []);
  const owner = { active: true, canManageCoop: true, canManageCoupons: true, canWriteProfile: true };
  assert.deepEqual(visibleGrowTips(owner).map((t) => t.key), ['coop', 'node', 'coupon', 'npc']);
  const staff = { active: true, canManageCoop: false, canManageCoupons: true, canWriteProfile: false };
  assert.deepEqual(visibleGrowTips(staff).map((t) => t.key), ['coop', 'coupon'], '没权限的卡整张不出');
  assert.equal(visibleGrowTips(owner)[0].url, undefined, '卡面数据不带路由,点卡按 key 查');
});

test('W2-15:每张卡都有封面和一个现有路由,图片都在商家分包里;r2 起不叠 3D 图标', () => {
  for (const tip of GROW_TIPS) {
    assert.ok(growTipUrl(tip.key).startsWith('/pages/') || growTipUrl(tip.key).startsWith('/subpackage'), tip.key);
    assert.equal(tip.icon, undefined, 'r2 a6-w2-15:没有 3D logo');
    for (const img of [tip.cover]) {
      assert.match(img, /^\/pages\/merchant\/images\/grow\//);
      assert.ok(fs.existsSync(path.join(root, img)), img);
      assert.ok(fs.statSync(path.join(root, img)).size <= 60 * 1024, img + ' ≤ 60KB');
    }
    const route = growTipUrl(tip.key).split('?')[0].replace(/^\//, '');
    assert.ok(fs.existsSync(path.join(root, route + '.wxml')), route);
  }
  assert.equal(growTipUrl('nope'), '');
  assert.ok(!fs.readdirSync(path.join(root, 'pages/merchant/images/grow')).some((f) => f.endsWith('.png')), '3D 图标随 r2 退场');
});

test('W2-15:店铺参谋接守卫 state 事件,放行时按权限挑卡;页面渲染经营优化区', () => {
  const view = read('pages/merchant/marketing/ai-insight/index.wxml');
  assert.match(view, /<cy-access-gate[^>]*need="canReadMarketing" bind:state="onGateState"/);
  assert.match(view, /wx:if="\{\{growTips\.length\}\}"[\s\S]*经营优化[\s\S]*bindtap="goGrowTip" data-key="\{\{item\.key\}\}"/);
  // r2 a6-w2-15:经营优化是横滑(scroll-x),卡宽固定、右边露出下一张;不再有 grow-ic 图标层
  const grow = view.slice(view.indexOf('经营优化'));
  assert.match(grow, /<scroll-view class="tscroll[^"]*"[^>]*scroll-x/);
  assert.doesNotMatch(view, /grow-ic/);
  assert.match(read('pages/merchant/marketing/ai-insight/index.wxss'), /\.grow-card \{ flex: none; width: 560rpx;/);
  const gate = read('components/cy/access-gate/index.js');
  assert.match(gate, /triggerEvent\('state', \{ state: that\.data\.state, access: decision\.state === 'allow' && decision\.access \? decision\.access : null \}\)/);

  let page;
  global.getApp = () => ({ globalData: {} });
  global.Page = (def) => { page = def; };
  global.wx = { navigateTo: (o) => { global.__nav = o.url; }, getSystemInfoSync: () => ({}) };
  require('../../pages/merchant/marketing/ai-insight/index.js');
  const inst = Object.assign({ data: JSON.parse(JSON.stringify(page.data)), setData(p) { Object.assign(this.data, p); } }, page);
  inst.onGateState({ detail: { state: 'allow', access: { active: true, canManageCoupons: true } } });
  assert.deepEqual(inst.data.growTips.map((t) => t.key), ['coop', 'coupon']);
  inst.goGrowTip({ currentTarget: { dataset: { key: 'coupon' } } });
  assert.equal(global.__nav, '/subpackageMember/coupon/coupon?scope=MERCHANT');
  inst.onGateState({ detail: { state: 'unknown', access: null } });
  assert.deepEqual(inst.data.growTips, []);
});

test('r2 a6-w2-16:AI 店铺角色首次介绍回到 R12 那一版(3D 物件),每步一种动效,不用 logo 积木', () => {
  const view = read('pages/merchant/decor/ai-npc/index.wxml');
  assert.doesNotMatch(view, /logo-blocks|logoBlocks|ni-lp|ni-hi/, '积木版退场');
  assert.match(view, /class="ni-glow ni-glow--\{\{introStep\}\}"/);
  assert.match(view, /src="\/pages\/merchant\/images\/npc-intro\/\{\{item\.a\}\}\.png"/);
  assert.match(view, /src="\/pages\/merchant\/images\/npc-intro\/\{\{item\.b\}\}\.png"/);
  assert.match(view, /ni-o--k\{\{index\}\}/);
  assert.ok(!fs.existsSync(path.join(root, 'pages/merchant/images/logo-blocks')));
  const js = read('pages/merchant/decor/ai-npc/index.js');
  assert.doesNotMatch(js, /logoBlocks/);
  const objs = [['convenience_store', 'sparkles'], ['waving_hand', 'speech_balloon'], ['fork_and_knife_with_plate', 'alarm_clock'], ['name_badge', 'performing_arts']];
  for (const [a, b] of objs) {
    assert.match(js, new RegExp("a: '" + a + "', b: '" + b + "'"));
    for (const n of [a, b]) assert.ok(fs.existsSync(path.join(root, 'pages/merchant/images/npc-intro/' + n + '.png')), n);
  }
  const css = read('pages/merchant/decor/ai-npc/index.wxss');
  // qx-green:棘轮(MOTION.md 不新增 @keyframes)要求改成 transition —— 每步两个物件各有自己的起点(未上场时停在那里)
  // 与到位补间,多段动作(星光闪 / 挥手 / 闹钟摆)由 JS 按原关键帧段落逐段换 ni-stage--bN;四步仍互不相同
  assert.doesNotMatch(css, /@keyframes ni-/);
  const rule = (sel) => { const m = css.match(new RegExp('(?:^|\\n)' + sel.replace(/\./g, '\\.') + ' \\{([^}]*)\\}')); return m ? m[1] : ''; };
  const sigs = [0, 1, 2, 3].map((k) => {
    const from = rule('.ni-o--k' + k), fromSm = rule('.ni-o--sm.ni-o--k' + k);
    const on = rule('.ni-o--k' + k + '.ni-o--on'), onSm = rule('.ni-o--sm.ni-o--k' + k + '.ni-o--on');
    assert.ok(/transform:/.test(from) && /transform:/.test(fromSm), 'step ' + k + ' 两个物件都有起点');
    assert.ok(/transition:/.test(on) && /transition:/.test(onSm), 'step ' + k + ' 两个物件都有到位补间');
    return from.match(/transform: ([^;]+)/)[1] + '+' + fromSm.match(/transform: ([^;]+)/)[1];
  });
  assert.equal(new Set(sigs).size, 4, '每步动效不一样:' + sigs.join(' / '));
  for (const sel of ['.ni-stage--b1 .ni-o--sm.ni-o--k0.ni-o--on', '.ni-stage--b1 .ni-o--k1.ni-o--on', '.ni-stage--b1 .ni-o--sm.ni-o--k2.ni-o--on']) {
    assert.ok(css.includes(sel + ' {'), '多段动作有分段样式:' + sel);
  }
  assert.match(js, /0: \{ frames: \[\[1, 700\], \[2, 700\]\], opts: \{ delay: 400/, '星光一直闪');
  assert.match(js, /1: \{ frames: \[\[1, 240\], \[2, 240\], \[3, 240\], \[4, 880\]\], opts: \{ delay: 200, times: 2/, '手挥两下');
  assert.match(js, /2: \{ frames: \[\[1, 300\], \[2, 300\], \[3, 300\], \[4, 300\]\], opts: \{ delay: 500, times: 2/, '闹钟摆两下');
  assert.match(view, /index === introPrev \? 'ni-o--out'/, '上一步的物件缩走');
  for (const k of [1, 2, 3]) assert.match(css, new RegExp('\\.ni-glow--' + k + ' \\{'), '每步光晕换色');
  assert.match(css, /prefers-reduced-motion: reduce\) \{ \.ni-o \{ transition: none; \} \.ni-stage \.ni-o \{ transition: none !important; \} \}/);
  assert.match(css, /\.ni--still \.ni-stage \.ni-o \{ transition: none !important; \}/, '应用内减少动态效果也直接到位');
  assert.match(view, /class="ni \{\{niStill \? 'ni--still' : ''\}\}"/);
  assert.match(js, /INTRO_SEEN_KEY = 'cy_ai_npc_intro_seen'/, '只弹一次的存储标记不变');
});
