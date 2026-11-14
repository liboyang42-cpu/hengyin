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
  channels: { xpay: { available: true } },
  holding: { active: false, validTo: null, coverageEnd: null }
};

test('关闭时整页不出售', () => {
  assert.deepEqual(pp.passView({ enabled: false, unavailableReason: 'PLATFORM_PASS_DISABLED' }), { enabled: false });
  assert.deepEqual(pp.passView(null), { enabled: false });
});

test('套餐卡:第一版只卖月卡并默认选中,标签写 30天·不自动续费,价格来自服务端分', () => {
  const v = pp.passView(ov, '', 'android', true);
  assert.equal(v.selected, 'MONTH');
  assert.deepEqual(v.plans.map((p) => [p.code, p.price, p.line, p.best]), [['MONTH', '¥39', '30天·不自动续费', false]]);
  assert.equal(v.count, 12);
});

test('按钮写服务端金额:未持有「开通 ¥39」,持有「续期 ¥39」', () => {
  assert.equal(pp.passView(ov, '', 'ios', true).buyLabel, '开通 ¥39');
  const held = pp.passView(Object.assign({}, ov, { holding: { active: true } }), '', 'ios', true);
  assert.equal(held.buyLabel, '续期 ¥39');
});

test('购买前须知:可玩主题 / 有效期取服务端,适用范围与退款规则取 terms,缺项不出', () => {
  const v = pp.passView(Object.assign({}, ov, { terms: { seat: '仅限本人席位', refund: '开通后 7 天内可申请退款' } }), '', 'ios', true);
  assert.deepEqual(v.facts.map((f) => [f.k, f.v]), [['可玩主题', '12 个主题'], ['适用范围', '仅限本人席位'], ['有效期', '30 天'], ['退款规则', '开通后 7 天内可申请退款']]);
  assert.deepEqual(pp.passView(Object.assign({}, ov, { libraryCount: null }), '', 'ios', true).facts.map((f) => f.k), ['有效期']);
});

test('数量未知不写 0;没有在售套餐按钮不可点', () => {
  const v = pp.passView(Object.assign({}, ov, { libraryCount: null, plans: [] }), '', 'android');
  assert.equal(v.count, null);
  assert.equal(v.buyOff, true);
  assert.equal(v.buyLabel, '暂未开售');
  assert.deepEqual(v.facts, []);
});

test('渠道:iOS 和安卓设备支持且服务端可用都走虚拟支付', () => {
  assert.equal(pp.pickChannel('ios', ov.channels, true), 'XPAY');
  assert.equal(pp.pickChannel('android', ov.channels, true), 'XPAY');
});

test('渠道:设备不支持虚拟支付 → 不出购买并给原因,不退回 JSAPI', () => {
  assert.equal(pp.pickChannel('android', { jsapi: { available: true }, xpay: { available: true } }, false), null);
  const v = pp.passView(ov, '', 'android', false);
  assert.equal(v.buyOff, true);
  assert.equal(v.buyLabel, '当前微信版本暂不支持开通，请升级后再试');
});

test('渠道:服务端虚拟支付不可用 → 暂未开放,JSAPI 可用也不退回', () => {
  const v = pp.passView(Object.assign({}, ov, { channels: { jsapi: { available: true }, xpay: { available: false } } }), '', 'ios', true);
  assert.equal(v.buyOff, true);
  assert.equal(v.buyLabel, '暂未开放开通');
});

test('渠道:Windows / 鸿蒙 / 开发者工具等未验证端不出购买', () => {
  for (const pf of ['windows', 'mac', 'ohos', 'devtools', 'unknown']) {
    assert.equal(pp.pickChannel(pf, ov.channels, true), null, pf);
  }
  assert.equal(pp.passView(ov, '', 'windows', true).buyLabel, '当前设备暂不支持开通');
});

test('持有中:按钮写续期,有效期取服务端 coverageEnd', () => {
  const v = pp.passView(Object.assign({}, ov, { holding: { active: true, validTo: '2027-11-10 12:00:00', coverageEnd: '2027-12-10 12:00:00' } }), '', 'android', true);
  assert.equal(v.active, true);
  assert.equal(v.untilText, '2027.12.10');
  assert.equal(v.buyLabel, '续期 ¥39');
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

function xpayScript(extra) {
  const state = { confirmPaid: false, polls: 0 };
  const script = (o) => {
    if (extra && extra(o)) return extra(o);
    if (o.url === '/api/platform-pass/orders') return { code: 200, data: { orderNo: 'PPO2', status: 'PENDING', channel: 'XPAY' } };
    if (/\/pay$/.test(o.url)) { return { code: 200, data: { virtualPayment: { mode: 'short_series_goods', signData: '{}', paySig: 'a', signature: 'b' } } }; }
    if (/\/confirm$/.test(o.url)) return { code: 200, data: { orderNo: 'PPO2', status: state.confirmPaid ? 'PAID' : 'PENDING' } };
    state.polls++;
    return { code: 200, data: { orderNo: 'PPO2', status: 'PENDING' } };
  };
  script.state = state;
  script.wx = { login: (p) => p.success({ code: 'code-x' }), requestVirtualPayment: (p) => p.success() };
  return script;
}

test('虚拟支付:先 wx.login 拿新 code,拉起 requestVirtualPayment,再服务端回查确认', async () => {
  let vp = null;
  const script = xpayScript();
  script.state.confirmPaid = true;
  script.wx.requestVirtualPayment = (p) => { vp = p; p.success(); };
  const r = await pp.purchase(fakeDeps(script).deps, 'MONTH', 'XPAY');
  assert.equal(r.state, 'paid');
  assert.equal(vp.mode, 'short_series_goods');
  assert.equal(vp.paySig, 'a');
});

test('虚拟支付:SDK 成功但服务端一直未付 → 确认中并带回订单号,不冒充已开通', async () => {
  const r = await pp.purchase(fakeDeps(xpayScript()).deps, 'MONTH', 'XPAY');
  assert.equal(r.state, 'pending');
  assert.equal(r.orderNo, 'PPO2');
});

test('同一次购买意图重试沿用同一个 requestId', async () => {
  const script = xpayScript();
  const { deps, calls } = fakeDeps(script);
  await pp.purchase(deps, 'MONTH', 'XPAY', { requestId: 'ppfixed12345' });
  await pp.purchase(deps, 'MONTH', 'XPAY', { requestId: 'ppfixed12345' });
  const ids = calls.filter((c) => c.url === '/api/platform-pass/orders').map((c) => c.data.requestId);
  assert.deepEqual(ids, ['ppfixed12345', 'ppfixed12345']);
  assert.match(calls[0].data.requestId, /^pp/);
});

test('不传 requestId 时每次生成新的', async () => {
  const { deps, calls } = fakeDeps(xpayScript());
  await pp.purchase(deps, 'MONTH', 'XPAY');
  assert.match(calls[0].data.requestId, /^pp[a-z0-9]{8,}/);
});

test('非 XPAY 渠道一律拒绝,不发任何请求', async () => {
  const { deps, calls } = fakeDeps(xpayScript());
  const r = await pp.purchase(deps, 'MONTH', 'JSAPI');
  assert.equal(r.state, 'failed');
  assert.equal(calls.length, 0);
});

test('用户取消:不回查不报错,订单号带回', async () => {
  const script = xpayScript();
  script.wx.requestVirtualPayment = (p) => p.fail({ errCode: -2, errMsg: 'cancel' });
  const r = await pp.purchase(fakeDeps(script).deps, 'MONTH', 'XPAY');
  assert.deepEqual(r, { state: 'cancelled', orderNo: 'PPO2' });
});

test('回到订单:已付直接返回已开通,不再支付', async () => {
  const { deps, calls } = fakeDeps(() => ({ code: 200, data: { orderNo: 'PPO2', status: 'PAID' } }));
  const r = await pp.resume(deps, 'PPO2', true);
  assert.equal(r.state, 'paid');
  assert.equal(calls.length, 1);
});

test('回到订单:待支付且设备可付 → 继续拉起支付并回查', async () => {
  const script = xpayScript((o) => (o.method === 'GET' && o.url.endsWith('PPO2') && !script.state.polls ? { code: 200, data: { orderNo: 'PPO2', status: 'PENDING', channel: 'XPAY' } } : null));
  script.state.confirmPaid = true;
  const r = await pp.resume(fakeDeps(script).deps, 'PPO2', true);
  assert.equal(r.state, 'paid');
});

test('回到订单:设备不能付或历史非 XPAY 订单 → 只回查保持确认中,不支付', async () => {
  const { deps, calls } = fakeDeps(() => ({ code: 200, data: { orderNo: 'PPO9', status: 'PENDING', channel: 'JSAPI' } }));
  assert.equal((await pp.resume(deps, 'PPO9', true)).state, 'pending');
  assert.equal((await pp.resume(deps, 'PPO9', false)).state, 'pending');
  assert.ok(calls.every((c) => c.method === 'GET'));
});

test('回到订单:已取消 / 已退款订单不再支付', async () => {
  const { deps } = fakeDeps(() => ({ code: 200, data: { orderNo: 'PPO9', status: 'CANCELLED' } }));
  assert.equal((await pp.resume(deps, 'PPO9', true)).state, 'closed');
});

test('订单状态与退款进度只来自服务端字段,读不到的状态不编', () => {
  const base = { orderNo: 'PPO1', priceFen: 3900, validDays: 30, paidTime: '2026-11-13 10:00:00', validTo: '2026-12-13 10:00:00' };
  const st = (status, refund) => pp.orderRow(Object.assign({}, base, { status, refund }));
  assert.equal(st('PENDING').state, '待支付');
  assert.equal(st('PAID').state, '已开通');
  assert.equal(st('CANCELLED').state, '已取消');
  assert.equal(st('REFUNDING').state, '退款中');
  assert.equal(st('REFUNDED').state, '已退款');
  assert.equal(st('WHAT').state, '状态确认中');
  assert.equal(st('PAID', { reviewStatus: 'PENDING' }).refund, '退款申请审核中');
  assert.equal(st('PAID', { reviewStatus: 'REJECTED' }).refund, '退款申请未通过');
  assert.equal(pp.orderRow(base).amount, '¥39');
});

test('订单可操作性:待支付可继续、已开通且无退款记录才可申请退款', () => {
  const base = { orderNo: 'PPO1', priceFen: 3900, validDays: 30 };
  assert.equal(pp.orderRow(Object.assign({ status: 'PENDING' }, base)).canResume, true);
  assert.equal(pp.orderRow(Object.assign({ status: 'PAID' }, base)).canRefund, true);
  assert.equal(pp.orderRow(Object.assign({ status: 'PAID', refund: { reviewStatus: 'NONE' } }, base)).canRefund, true);
  assert.equal(pp.orderRow(Object.assign({ status: 'PAID', refund: { reviewStatus: 'PENDING' } }, base)).canRefund, false);
  assert.equal(pp.orderRow(Object.assign({ status: 'REFUNDED' }, base)).canRefund, false);
});

test('下单被拒:原话带回', async () => {
  const script = () => ({ code: 503, msg: '支付暂不可用' });
  script.wx = {};
  const r = await pp.purchase(fakeDeps(script).deps, 'MONTH', 'XPAY');
  assert.deepEqual(r, { state: 'failed', message: '支付暂不可用' });
});

const info = { id: 990014, selfPlay: 1, selfPlayPrice: 39, productType: 1 };

test('结账:通行证覆盖 → ¥0、免费领取、明细多一行通行证、不出俱乐部减免与通票', () => {
  const quote = { payAmount: 0, memberDiscountYuan: 39, platformPassCovered: true, refundableNow: null };
  const products = [{ productId: 1, productName: '城市包', productKind: 2, cashPrice: 88, items: [{ topicId: 990014 }] }];
  const v = checkoutView({ info, quote, quoteState: 'ready', products });
  assert.equal(v.member, true);
  assert.equal(v.totalText, '¥0.00');
  assert.equal(v.payLabel, '免费领取');
  assert.deepEqual(v.bill.map((r) => [r.k, r.v]), [['自玩门票 × 1', '¥39.00'], ['通行证', '你已可直接游玩']]);
  assert.equal(v.single.strike, '');
  assert.deepEqual(v.passes, []);
});

test('结账:未覆盖时与原来一致(俱乐部减免照旧)', () => {
  const v = checkoutView({ info, quote: { payAmount: 31.2, memberDiscountYuan: 7.8 }, quoteState: 'ready' });
  assert.equal(v.member, false);
  assert.deepEqual(v.bill.map((r) => r.v), ['¥39.00', '-¥7.80']);
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
  assert.match(ok, /r\.state === 'paid'\) \{[^}]*this\._celebrate/);
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

test('页面:订单详情页已登记、我的订单接入通行证订单、退款走服务端接口', () => {
  const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'));
  const member = (app.subpackages || app.subPackages).find((s) => s.root === 'subpackageMember');
  assert.ok(member.pages.includes('market/pass-order/index'));
  const passes = fs.readFileSync(path.join(ROOT, 'subpackageMember/market/passes/index.js'), 'utf8');
  assert.match(passes, /\/api\/platform-pass\/orders/);
  const order = fs.readFileSync(path.join(ROOT, 'subpackageMember/market/pass-order/index.js'), 'utf8');
  assert.match(order, /\/refund-apply/);
  assert.doesNotMatch(order, /requestPayment\(/);
});

test('页面:购买页不再引用普通 JSAPI 支付,确认中回到同一订单', () => {
  const js = fs.readFileSync(path.join(ROOT, 'subpackageMember/market/membership/index.js'), 'utf8');
  assert.doesNotMatch(js, /JSAPI|requestPayment\(/);
  assert.match(js, /_confirmOrderNo = r\.orderNo/);
  assert.match(js, /buyError: '确认中'/);
});
