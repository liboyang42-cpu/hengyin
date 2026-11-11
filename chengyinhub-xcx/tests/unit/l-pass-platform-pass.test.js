'use strict';
// l-pass 通行证:视图只读服务端事实;iOS 走虚拟支付;SDK 成功不等于已付;持有者结账 ¥0「免费领取」。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const pp = Object.assign({}, require('../../subpackageMember/utils/platform-pass.js'), require('../../subpackageMember/utils/platform-pass-pay.js'));
const { checkoutView } = require('../../pages/topic/utils/checkout-view.js');

const ROOT = path.join(__dirname, '../..');
const ov = {
  enabled: true,
  plans: [{ planCode: 'YEAR', title: '年卡', priceFen: 29900, validDays: 365 }, { planCode: 'MONTH', title: '月卡', priceFen: 3900, validDays: 30 }],
  libraryCount: 12,
  channels: { jsapi: { available: true }, xpay: { available: true } },
  holding: { active: false, validTo: null, coverageEnd: null }
};

test('关闭时整页不出售', () => {
  assert.deepEqual(pp.passView({ enabled: false, unavailableReason: 'PLATFORM_PASS_DISABLED' }), { enabled: false });
  assert.deepEqual(pp.passView(null), { enabled: false });
});

test('套餐卡:年卡默认选中、按天最便宜挂最划算、价格来自服务端分', () => {
  const v = pp.passView(ov, '', 'android');
  assert.equal(v.selected, 'YEAR');
  assert.deepEqual(v.plans.map((p) => [p.code, p.price, p.best]), [['YEAR', '¥299', true], ['MONTH', '¥39', false]]);
  assert.equal(v.plans[0].line, '年卡 · 每月不到 ¥25');
  assert.equal(v.count, 12);
  assert.equal(v.buyLabel, '开通通行证');
  assert.equal(v.terms, '开通后期间内可玩主题价格为 ¥0 · 不自动续费');
});

test('数量未知不写 0;没有在售套餐按钮不可点', () => {
  const v = pp.passView(Object.assign({}, ov, { libraryCount: null, plans: [] }), '', 'android');
  assert.equal(v.count, null);
  assert.equal(v.buyOff, true);
  assert.equal(v.buyLabel, '暂未开售');
  assert.equal(v.terms, '');
});

test('渠道:iOS 只走虚拟支付,其他走 JSAPI;服务端不可用就不出可点按钮', () => {
  assert.equal(pp.pickChannel('ios', ov.channels), 'XPAY');
  assert.equal(pp.pickChannel('android', ov.channels), 'JSAPI');
  assert.equal(pp.pickChannel('devtools', ov.channels), 'JSAPI');
  assert.equal(pp.pickChannel('ios', { jsapi: { available: true }, xpay: { available: false } }), null, 'iOS 不能退回 JSAPI 卖虚拟商品');
  const v = pp.passView(Object.assign({}, ov, { channels: { jsapi: { available: true }, xpay: { available: false } } }), '', 'ios');
  assert.equal(v.buyOff, true);
});

test('持有中:按钮写续期,有效期取服务端 coverageEnd', () => {
  const v = pp.passView(Object.assign({}, ov, { holding: { active: true, validTo: '2027-11-10 12:00:00', coverageEnd: '2027-12-10 12:00:00' } }), '', 'android');
  assert.equal(v.active, true);
  assert.equal(v.untilText, '2027.12.10');
  assert.equal(v.buyLabel, '续期通行证');
});

test('主题库行:无 id / 名称不出,时长按分钟取整,不编城市', () => {
  assert.equal(pp.libraryRow({ name: 'x' }), null);
  const r = pp.libraryRow({ topicId: 5, name: '老城三封信', productType: 1, totalTime: 3600, addressName: '外滩源', imgUrl: 'u' });
  assert.deepEqual(r, { id: '5', name: '老城三封信', cover: 'u', data: '城市定向 · 约 60 分钟', tags: ['城市定向', '外滩源'] });
});

function fakeDeps(script) {
  const calls = [];
  const request = (o) => { calls.push(o); return Promise.resolve(script(o)); };
  return { calls, deps: { request, wx: script.wx, pollTimes: 2, wait: () => Promise.resolve() } };
}

test('JSAPI:SDK 成功后必须等服务端 PAID 才算开通', async () => {
  let polls = 0;
  const script = (o) => {
    if (o.url === '/api/platform-pass/orders') return { code: 200, data: { orderNo: 'PPO1', status: 'PENDING' } };
    if (/\/pay$/.test(o.url)) return { code: 200, data: { orderNo: 'PPO1', payParams: { package: 'p' } } };
    polls++;
    return { code: 200, data: { orderNo: 'PPO1', status: polls >= 2 ? 'PAID' : 'PENDING', validTo: '2027-11-10 00:00:00' } };
  };
  script.wx = { requestPayment: (p) => p.success() };
  const { deps, calls } = fakeDeps(script);
  const r = await pp.purchase(deps, 'YEAR', 'JSAPI');
  assert.equal(r.state, 'paid');
  assert.equal(polls, 2);
  assert.equal(calls[0].data.planCode, 'YEAR');
  assert.match(calls[0].data.requestId, /^pp[a-z0-9]{8,}/);
});

test('JSAPI:SDK 成功但服务端一直未付 → 确认中,不冒充已开通', async () => {
  const script = (o) => {
    if (o.url === '/api/platform-pass/orders') return { code: 200, data: { orderNo: 'PPO1' } };
    if (/\/pay$/.test(o.url)) return { code: 200, data: { payParams: { package: 'p' } } };
    return { code: 200, data: { status: 'PENDING' } };
  };
  script.wx = { requestPayment: (p) => p.success() };
  const r = await pp.purchase(fakeDeps(script).deps, 'YEAR', 'JSAPI');
  assert.equal(r.state, 'pending');
});

test('虚拟支付:先 wx.login 拿新 code,拉起 requestVirtualPayment,再服务端回查确认', async () => {
  let vp = null;
  const script = (o) => {
    if (o.url === '/api/platform-pass/orders') return { code: 200, data: { orderNo: 'PPO2' } };
    if (/\/pay$/.test(o.url)) { assert.equal(o.data.jsCode, 'code-x'); return { code: 200, data: { virtualPayment: { mode: 'short_series_goods', signData: '{}', paySig: 'a', signature: 'b' } } }; }
    if (/\/confirm$/.test(o.url)) return { code: 200, data: { orderNo: 'PPO2', status: 'PAID' } };
    throw new Error('unexpected ' + o.url);
  };
  script.wx = { login: (p) => p.success({ code: 'code-x' }), requestVirtualPayment: (p) => { vp = p; p.success(); } };
  const r = await pp.purchase(fakeDeps(script).deps, 'MONTH', 'XPAY');
  assert.equal(r.state, 'paid');
  assert.equal(vp.mode, 'short_series_goods');
  assert.equal(vp.paySig, 'a');
});

test('用户取消:不回查不报错', async () => {
  const script = (o) => {
    if (o.url === '/api/platform-pass/orders') return { code: 200, data: { orderNo: 'PPO3' } };
    if (/\/pay$/.test(o.url)) return { code: 200, data: { payParams: {} } };
    throw new Error('should not poll');
  };
  script.wx = { requestPayment: (p) => p.fail({ errMsg: 'requestPayment:fail cancel' }) };
  assert.equal((await pp.purchase(fakeDeps(script).deps, 'YEAR', 'JSAPI')).state, 'cancelled');
});

test('下单被拒:原话带回', async () => {
  const script = () => ({ code: 503, msg: '支付暂不可用' });
  script.wx = {};
  const r = await pp.purchase(fakeDeps(script).deps, 'YEAR', 'JSAPI');
  assert.deepEqual(r, { state: 'failed', message: '支付暂不可用' });
});

const info = { id: 990014, selfPlay: 1, selfPlayPrice: 39, productType: 1 };

test('结账:通行证覆盖 → ¥0、免费领取、明细多一行通行证、不出俱乐部减免与通票', () => {
  const quote = { payAmount: 0, memberDiscountYuan: 39, platformPassCovered: true, refundableNow: null };
  const products = [{ productId: 1, productName: '城市包', productKind: 2, cashPrice: 88, items: [{ topicId: 990014 }] }];
  const v = checkoutView({ info, quote, quoteState: 'ready', products });
  assert.equal(v.member, true);
  assert.equal(v.totalText, '¥0');
  assert.equal(v.payLabel, '免费领取');
  assert.deepEqual(v.bill.map((r) => [r.k, r.v]), [['自玩通行证 × 1', '¥39'], ['通行证', '你已可直接游玩']]);
  assert.equal(v.single.strike, '');
  assert.deepEqual(v.passes, []);
});

test('结账:未覆盖时与原来一致(俱乐部减免照旧)', () => {
  const v = checkoutView({ info, quote: { payAmount: 31.2, memberDiscountYuan: 7.8 }, quoteState: 'ready' });
  assert.equal(v.member, false);
  assert.deepEqual(v.bill.map((r) => r.v), ['¥39', '-¥7.80']);
});

test('页面:新页在分包、插图用分包副本、新样式不加 keyframes、入口只在服务端开放时出现', () => {
  const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'));
  const member = (app.subpackages || app.subPackages).find((s) => s.root === 'subpackageMember');
  assert.ok(member.pages.includes('market/membership/index'));
  assert.ok(member.pages.includes('market/library/index'));
  const json = JSON.parse(fs.readFileSync(path.join(ROOT, 'subpackageMember/market/membership/index.json'), 'utf8'));
  // 2026-11-11 l-icons:英雄区插图换成 3D 玻璃序列帧(cy-frames 分包副本),cy-illus 不再登记
  assert.equal(json.usingComponents['cy-frames'], '/subpackageMember/components/cy-frames/index');
  for (const f of ['subpackageMember/market/membership/index.wxss', 'subpackageMember/market/library/index.wxss', 'subpackageMember/market/passes/index.wxss', 'pages/topic/components/cy/self-play-checkout/index.wxss']) {
    assert.ok(!/@keyframes/.test(fs.readFileSync(path.join(ROOT, f), 'utf8')), f);
  }
  const passes = fs.readFileSync(path.join(ROOT, 'subpackageMember/market/passes/index.wxml'), 'utf8');
  assert.match(passes, /wx:if="\{\{member\}\}"/);
  const ok = fs.readFileSync(path.join(ROOT, 'subpackageMember/market/membership/index.js'), 'utf8');
  assert.match(ok, /r\.state === 'paid'\) \{ this\._celebrate/);
  // 2026-11-11 l-icons:开通成功的 ps-success 层换成 subpackagePass/reveal 钥匙开场(完整版),成功动效仍只在 PAID 后出现
  assert.match(ok, /_celebrate\(order\) \{[\s\S]*wx\.redirectTo\(\{ url: REVEAL \+ '\?' \+ q\.join/);
  assert.doesNotMatch(fs.readFileSync(path.join(ROOT, 'subpackageMember/market/membership/index.wxss'), 'utf8'), /\.ok\.is-reduced/);
});

test('cy-illus 每张图层都是合法 XML:同一标签无重复属性(data URI 按 XML 解析,重复属性整张不显示)', () => {
  const art = require('../../pages/play/components/cy-illus/art.js');
  for (const name of art.LIST) {
    const l = art.layers(name);
    for (const k of ['blob', 'fg']) {
      const svg = decodeURIComponent(l[k].replace(/^data:image\/svg\+xml;charset=utf8,/, ''));
      for (const tag of svg.match(/<[a-zA-Z]+(?:\s+[a-zA-Z:-]+="[^"]*")*\s*\/?>/g) || []) {
        const names = (tag.match(/\s([a-zA-Z:-]+)="/g) || []).map((x) => x.trim());
        assert.equal(new Set(names).size, names.length, name + '.' + k + ' 重复属性: ' + tag.slice(0, 80));
      }
    }
  }
});
