'use strict';
// qx-a6(2026-11-09):走查 v2 W2-15 经营优化带图卡 / W2-16 AI 店铺角色首次介绍改城瘾 logo 积木
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

test('W2-15:每张卡都有图、图标和一个现有路由,图片都在商家分包里', () => {
  for (const tip of GROW_TIPS) {
    assert.ok(growTipUrl(tip.key).startsWith('/pages/') || growTipUrl(tip.key).startsWith('/subpackage'), tip.key);
    for (const img of [tip.cover, tip.icon]) {
      assert.match(img, /^\/pages\/merchant\/images\/grow\//);
      assert.ok(fs.existsSync(path.join(root, img)), img);
      assert.ok(fs.statSync(path.join(root, img)).size <= 60 * 1024, img + ' ≤ 60KB');
    }
    const route = growTipUrl(tip.key).split('?')[0].replace(/^\//, '');
    assert.ok(fs.existsSync(path.join(root, route + '.wxml')), route);
  }
  assert.equal(growTipUrl('nope'), '');
  assert.ok(fs.existsSync(path.join(root, 'pages/merchant/images/grow/LICENSE')), '3D 图标 MIT 许可随图');
});

test('W2-15:店铺参谋接守卫 state 事件,放行时按权限挑卡;页面渲染经营优化区', () => {
  const view = read('pages/merchant/marketing/ai-insight/index.wxml');
  assert.match(view, /<cy-access-gate[^>]*need="canReadMarketing" bind:state="onGateState"/);
  assert.match(view, /wx:if="\{\{growTips\.length\}\}"[\s\S]*经营优化[\s\S]*bindtap="goGrowTip" data-key="\{\{item\.key\}\}"/);
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

test('W2-16:AI 店铺角色首次介绍用城瘾 logo 六块积木,按步散开 → 靠拢 → 拼合 → 说「嗨」', () => {
  const view = read('pages/merchant/decor/ai-npc/index.wxml');
  assert.match(view, /ni-stage--s\{\{introStep\}\}/);
  assert.match(view, /src="\/pages\/merchant\/images\/logo-blocks\/\{\{item\}\}\.png"/);
  assert.match(view, /class="ni-hi"><text>嗨<\/text>/);
  assert.doesNotMatch(view, /npc-intro\//, '旧 3D 物件图退场');
  const js = read('pages/merchant/decor/ai-npc/index.js');
  assert.match(js, /logoBlocks: \['l', 't', 'b', 'i1', 'i2', 'i3'\]/);
  for (const b of ['l', 't', 'b', 'i1', 'i2', 'i3']) assert.ok(fs.existsSync(path.join(root, 'pages/merchant/images/logo-blocks/' + b + '.png')), b);
  const css = read('pages/merchant/decor/ai-npc/index.wxss');
  assert.match(css, /\.ni-stage--s0 \.ni-lp--l \{ transform:/, '第 1 步散开');
  assert.match(css, /\.ni-stage--s1 \.ni-lp--l \{ transform:/, '第 2 步靠拢');
  assert.doesNotMatch(css, /\.ni-stage--s2 \.ni-lp/, '第 3 步归位(无 transform)');
  assert.match(css, /\.ni-stage--s3 \.ni-hi \{ opacity: 1/);
  assert.match(css, /prefers-reduced-motion: reduce\) \{ \.ni-lp, \.ni-hi \{ transition: none; \}/);
  assert.match(js, /INTRO_SEEN_KEY = 'cy_ai_npc_intro_seen'/, '只弹一次的存储标记不变');
});
