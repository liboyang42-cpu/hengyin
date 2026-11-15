'use strict';
// T6 mini-program / S3a:商家套餐页。只读展示;只有服务端开售(资金开关开)且是店主才出现开通按钮。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const vmod = require('../../pages/merchant/plans/view-model.js');

const root = path.resolve(__dirname, '../..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');

const free = { code: 'FREE', nameZh: '免费版', monthlyPriceFen: 0, visitFeeFen: null,
  features: { weeklyProofDetail: false, extraNodeGames: 0, dealsOffersPerMonth: 0 } };
const basic = { code: 'BASIC', nameZh: '基础版', monthlyPriceFen: 19900, visitFeeFen: null,
  features: { weeklyProofDetail: true, extraNodeGames: 2, dealsOffersPerMonth: 2 } };
const perVisit = { code: 'PER_VISIT', nameZh: '按次', monthlyPriceFen: 0, visitFeeFen: 300, features: {} };
const view = (extra) => Object.assign({ plans: [free, basic], current: { code: 'FREE', periodEnd: null, plan: free },
  salesOpen: false, salesState: 'AVAILABLE_SOON', merchantId: 3 }, extra);

test('view-model:价格、包含内容、当前套餐,文案与 App 一致', () => {
  const p = vmod.shapePage(view());
  assert.equal(p.currentName, '免费版'); assert.equal(p.salesOpen, false);
  assert.equal(p.plans[0].priceText, '免费'); assert.equal(p.plans[0].current, true); assert.equal(p.plans[0].buyable, false);
  assert.equal(p.plans[1].priceText, '¥199.00 / 月'); assert.equal(p.plans[1].buyable, true);
  assert.deepEqual(p.plans[1].features.map(f => [f.label, f.value]),
    [['每周到店报告', '完整明细'], ['额外地图点位游戏', '2'], ['每月可上优惠图层的优惠', '2']]);
  assert.deepEqual(p.plans[0].features.map(f => f.value), ['汇总', '0', '0']);
  assert.equal(vmod.priceText(perVisit), '每次核验到店 ¥3.00');
  assert.equal(vmod.shapePlan(perVisit, 'FREE').buyable, false);
});

test('view-model:开售只认 salesState=OPEN 且 salesOpen=true;脏数据给错误态', () => {
  assert.equal(vmod.shapePage(view({ salesOpen: true, salesState: 'OPEN' })).salesOpen, true);
  assert.equal(vmod.shapePage(view({ salesOpen: true, salesState: 'AVAILABLE_SOON' })).salesOpen, false);
  assert.equal(vmod.shapePage(view({ salesOpen: 'true', salesState: 'OPEN' })).salesOpen, false);
  assert.equal(vmod.shapePage(null), null); assert.equal(vmod.shapePage({ plans: 'x' }), null);
  assert.equal(vmod.shapePage(view({ current: { code: 'PRO', periodEnd: '2026-12-31', plan: { code: 'PRO', nameZh: '专业版' } } })).periodEnd, '2026年12月31日');
  assert.equal(vmod.shapePlan({ code: 'HACK' }, 'FREE'), null);
});

test('view-model:幂等号符合服务端 [A-Za-z0-9_-]{16,64}', () => {
  for (let i = 0; i < 20; i++) assert.match(vmod.newRequestId(), /^[A-Za-z0-9_-]{16,64}$/);
});

function mount({ access, plan, checkout }) {
  let def; const requests = [], toasts = [], modals = [];
  const app = { globalData: { navBarHeight: 44 }, getRequestErrorMessage: (res, f) => (res && res.msg) || f,
    sendRequest(p) {
      requests.push(p);
      const reply = p.url === '/api/merchant/access/me' ? access : p.url === '/api/merchant/plan' ? plan : checkout;
      if (!reply) return;
      if (reply.status && reply.status !== 200) p.successStatusAbnormal(reply.body, reply.status); else p.success(reply.body);
    } };
  const file = path.join(root, 'pages/merchant/plans/index.js');
  const req = createRequire(file);
  vm.runInNewContext(read('pages/merchant/plans/index.js'), {
    Page: d => { def = d; }, getApp: () => app, console, getCurrentPages: () => [],
    wx: { getWindowInfo: () => ({ statusBarHeight: 20 }), stopPullDownRefresh() {}, navigateBack() {} },
    require: f => f.endsWith('toast.js') ? Object.assign(t => toasts.push(t), { success: t => toasts.push(t) })
      : f.endsWith('modal.js') ? { show: o => { modals.push(o); o.success({ confirm: true }); } }
      : f.endsWith('merchant-theme.js') ? { merchantPageShow() {}, merchantPageRestore() {} } : req(f),
  });
  const page = Object.assign({}, def, { data: JSON.parse(JSON.stringify(def.data)), setData(d) { Object.assign(this.data, d); } });
  page.onLoad();
  return { page, requests, toasts, modals };
}
const owner = { body: { code: 200, data: { active: true, merchant: { id: 3, name: 'S' }, roleCode: 'MERCHANT_OWNER', permissions: ['merchant:basic:read'] } } };

test('页面:开关关着 → 只读,没有购买请求', () => {
  const h = mount({ access: owner, plan: { body: { code: 200, data: view() } } });
  assert.equal(h.page.data.pageState, 'ready'); assert.equal(h.page.data.page.salesOpen, false);
  h.page.onBuy({ currentTarget: { dataset: { code: 'BASIC' } } });
  assert.equal(h.requests.some(r => r.url === '/api/merchant/plan/checkout'), false);
  const wxml = read('pages/merchant/plans/index.wxml');
  assert.match(wxml, /即将开放/);
});

test('页面:开售且店主 → 确认后 checkout {planCode, months:1, requestId},展示待确认订单', () => {
  const h = mount({ access: owner, plan: { body: { code: 200, data: view({ salesOpen: true, salesState: 'OPEN' }) } },
    checkout: { body: { code: 200, data: { orderNo: 'MPLAN_x', planCode: 'BASIC', status: 'PENDING', payment: 'OPERATIONS_CONFIRM' } } } });
  h.page.onBuy({ currentTarget: { dataset: { code: 'BASIC' } } });
  const post = h.requests.find(r => r.url === '/api/merchant/plan/checkout');
  assert.ok(post); assert.equal(post.data.planCode, 'BASIC'); assert.equal(post.data.months, 1);
  assert.match(post.data.requestId, /^[A-Za-z0-9_-]{16,64}$/);
  assert.equal(h.page.data.order.orderNo, 'MPLAN_x'); assert.equal(h.page.data.buying, '');
});

test('页面:服务端 409 PLAN_SALES_CLOSED → 重新读取并说即将开放', () => {
  const h = mount({ access: owner, plan: { body: { code: 200, data: view({ salesOpen: true, salesState: 'OPEN' }) } },
    checkout: { status: 409, body: { code: 409, errorCode: 'PLAN_SALES_CLOSED', msg: '商家套餐即将开放' } } });
  h.page.onBuy({ currentTarget: { dataset: { code: 'BASIC' } } });
  assert.equal(h.page.data.order, null); assert.ok(h.toasts.includes('套餐购买即将开放'));
  assert.equal(h.requests.filter(r => r.url === '/api/merchant/plan').length, 2);
});

test('页面:非店主看不到可点的开通;没有 BASIC_READ 给无权限态', () => {
  const staff = { body: { code: 200, data: { active: true, merchant: { id: 3 }, roleCode: 'MERCHANT_CHECKIN', permissions: ['merchant:basic:read'] } } };
  const h = mount({ access: staff, plan: { body: { code: 200, data: view({ salesOpen: true, salesState: 'OPEN' }) } } });
  assert.equal(h.page.data.isOwner, false);
  h.page.onBuy({ currentTarget: { dataset: { code: 'BASIC' } } });
  assert.equal(h.requests.some(r => r.url === '/api/merchant/plan/checkout'), false);
  const none = mount({ access: { body: { code: 200, data: { active: true, merchant: { id: 3 }, roleCode: 'MERCHANT_CHECKIN', permissions: [] } } } });
  assert.equal(none.page.data.pageState, 'no-permission');
});

test('入口:工作台「更多」有套餐入口,页面已注册', () => {
  const app = JSON.parse(read('app.json'));
  const pkg = app.subPackages.find(p => p.root === 'pages/merchant');
  assert.ok(pkg.pages.includes('plans/index'));
  assert.match(read('pages/merchant/index/index.wxml'), /data-action="plans"/);
  assert.match(read('pages/merchant/index/index.js'), /url: '\/pages\/merchant\/plans\/index'/);
});
