'use strict';
// 1119:非商家玩家打开商家「发现」页(pages/merchant/relation),relation-home 回 403。
// 旧实现页面自己 wx.switchTab 回「我的」,与 cy-access-gate 的原地 deny 抢路由:开发者工具里整屏黑、
// 无导航栏、后续路由与元素查询全挂起,pageState 永远停在 loading。
// 这里把页面与守卫挂在同一页面实例上走一遍 403:setData 必须收敛、不发任何导航、落到原地无权限态。
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const ROOT = path.join(__dirname, '..', '..');
const PAGE = path.join(ROOT, 'pages/merchant/relation/index.js');
const GATE = path.join(ROOT, 'components/cy/access-gate/index.js');
const SETDATA_LIMIT = 200;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function mount({ relationReply, accessReply }) {
  const requests = [];
  const navigations = [];
  const counter = { setData: 0 };
  const app = {
    globalData: { statusBarHeight: 24, navBarHeight: 44 },
    getUserID: () => '42',
    getAvatar: () => '',
    getNickname: () => '',
    getImgUrl: (v) => v || '',
    getRequestErrorMessage: (_res, fallback) => fallback,
    sendRequest: (options) => { requests.push(options); },
  };
  const nav = (method) => (options) => { navigations.push({ method, url: options && options.url }); };
  global.getApp = () => app;
  global.wx = {
    getSystemInfoSync: () => ({ statusBarHeight: 24 }),
    setNavigationBarColor() {},
    setBackgroundColor() {},
    stopPullDownRefresh() {},
    switchTab: nav('switchTab'),
    reLaunch: nav('reLaunch'),
    redirectTo: nav('redirectTo'),
    navigateTo: nav('navigateTo'),
    navigateBack: nav('navigateBack'),
  };
  let pageDef;
  let gateDef;
  global.Page = (def) => { pageDef = def; };
  global.Component = (def) => { gateDef = def; };
  delete require.cache[PAGE];
  delete require.cache[GATE];
  require(PAGE);
  require(GATE);

  const page = Object.assign({}, pageDef, {
    route: 'pages/merchant/relation/index',
    options: {},
    data: JSON.parse(JSON.stringify(pageDef.data)),
    setData(patch, done) {
      counter.setData += 1;
      if (counter.setData > SETDATA_LIMIT) throw new Error('setData 不收敛:疑似渲染循环');
      Object.assign(this.data, patch);
      if (typeof done === 'function') done();
    },
  });
  global.getCurrentPages = () => [page];

  const gate = Object.assign({}, gateDef.methods, {
    data: Object.assign({}, gateDef.data, { kind: 'merchant', need: 'canManageCoop', clubId: '', activityId: '', backUrl: '' }),
    setData(patch, done) {
      counter.setData += 1;
      if (counter.setData > SETDATA_LIMIT) throw new Error('setData 不收敛:疑似渲染循环');
      Object.assign(this.data, patch);
      if (typeof done === 'function') done();
    },
    triggerEvent() {},
  });

  // 与真机同序:组件 attached 先于页面 onLoad
  gateDef.lifetimes.attached.call(gate);
  page.onLoad({});
  page.onShow();

  const reply = async () => {
    for (let round = 0; round < 5; round += 1) {
      await sleep(0);
      for (const req of requests.splice(0)) {
        const answer = req.url === '/api/merchant/relation-home' ? relationReply
          : req.url === '/api/merchant/access/me' ? accessReply
          : req.url === '/api/club/post/feed' ? { code: 200, data: { rows: [], hasMore: false } }
          : { code: 200, data: { owned: [], joined: [] } };
        const ok = Number(answer.code) === 200;
        if (ok) req.success(answer);
        else if (req.successStatusAbnormal) req.successStatusAbnormal(answer, answer.code);
        else req.success(answer);
        if (req.complete) req.complete();
      }
    }
  };
  return { page, gate, requests, navigations, counter, reply };
}

async function settle(ctx) {
  await ctx.reply();
  await sleep(450);   // 跨过帖文流 400ms 慢骨架计时器
  const before = ctx.counter.setData;
  await sleep(50);
  await ctx.reply();
  assert.equal(ctx.counter.setData, before, 'setData 必须收敛,不能每帧重渲');
  assert.equal(ctx.requests.length, 0, '不得反复重发请求');
}

for (const [label, accessReply] of [
  ['access/me 回 active:false', { code: 200, data: { active: false, applicationState: 'NONE' } }],
  ['access/me 也回 403', { code: 403, msg: '商家身份无效或权限不足' }],
]) {
  test(`非商家打开发现页(${label}):收敛到原地无权限态,不切 tab、不跳走`, async () => {
    const ctx = mount({ relationReply: { code: 403, msg: '商家身份无效或权限不足' }, accessReply });
    await settle(ctx);
    assert.deepEqual(ctx.navigations, [], '无权限时不许自己导航(旧实现 switchTab 回「我的」导致整屏黑、路由挂起)');
    assert.equal(ctx.page.data.pageState, 'denied', '页面必须落终态,不能停在 loading');
    assert.equal(ctx.gate.data.state, 'deny');
    assert.equal(ctx.gate.data.reason, '当前身份不能查看商家内容');
    assert.equal(ctx.gate.data.denyCta, '申请入驻');
    assert.equal(ctx.page.__cyDenyInPlace, true);
    ctx.gate.onDenyCta();
    assert.deepEqual(ctx.navigations, [{ method: 'navigateTo', url: '/pages/merchant/apply/index' }], '唯一动作:申请入驻');
  });
}

test('页面自身 403 兜底:原地无权限态的唯一动作也是申请入驻', async () => {
  const ctx = mount({ relationReply: { code: 403, msg: '商家身份无效或权限不足' }, accessReply: { code: 500, msg: '服务繁忙' } });
  await settle(ctx);
  assert.equal(ctx.gate.data.state, 'unknown', '守卫读不到时不误锁');
  assert.equal(ctx.page.data.pageState, 'denied', '页面自己落原地无权限态');
  ctx.page.onDeniedApply();
  ctx.page.onDeniedApply();
  assert.deepEqual(ctx.navigations, [{ method: 'navigateTo', url: '/pages/merchant/apply/index' }]);
});

test('商家(有合作权限)行为不变:加载成功、守卫放行、不导航', async () => {
  const ctx = mount({
    relationReply: { code: 200, data: { stats: { merchantCount: 0, clubCount: 0, pendingCoopCount: 0 }, relations: [], discovery: { merchants: [], clubs: [] } } },
    accessReply: { code: 200, data: { active: true, merchant: { id: 1, name: '河畔咖啡' }, roleCode: 'MERCHANT_OWNER', permissions: ['merchant:coop:manage', 'merchant:basic:read'] } },
  });
  await ctx.reply();
  await sleep(1300);   // 跨过 page-motion 入场计时
  const before = ctx.counter.setData;
  await sleep(50);
  assert.equal(ctx.counter.setData, before, 'setData 必须收敛');
  assert.equal(ctx.page.data.pageState, 'ready');
  assert.notEqual(ctx.gate.data.state, 'deny');
  assert.deepEqual(ctx.navigations, []);
});

test('页面模板:无权限终态是锁 + 一行 + 申请入驻,不加副文案', () => {
  const wxml = require('node:fs').readFileSync(path.join(ROOT, 'pages/merchant/relation/index.wxml'), 'utf8');
  assert.match(wxml, /<cy-empty wx:elif="\{\{pageState === 'denied'\}\}" kind="no-permission" fill size="lg" title="当前身份不能查看商家内容" cta="申请入驻" bind:cta="onDeniedApply" \/>/);
  assert.match(wxml, /<cy-access-gate id="cy-access-gate" kind="merchant" need="canManageCoop" \/>/, '权限守卫不得削弱');
  assert.match(wxml, /<cy-nav-bar title="发现"/, '导航栏保留');
});
