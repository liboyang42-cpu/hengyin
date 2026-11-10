// qx-b5 §17.1 / §17.2 / §17.3 / §17.4 / P6:通行证与礼品只读服务端事实。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const mv = require('../../subpackageMember/utils/market-view.js');

const ROOT = path.join(__dirname, '..', '..');

test('商品卡:期限按 validityKind 原样写,固定期不写订阅,首次使用型写首次游玩起', () => {
  assert.equal(mv.validityText({ validityKind: 1, validDays: 30 }), '购买后 30 天内有效');
  assert.equal(mv.validityText({ validityKind: 2, fixedValidFrom: '2026-11-01 00:00:00', fixedValidTo: '2027-01-31 00:00:00' }),
    '2026.11.01 – 2027.01.31');
  assert.equal(mv.validityText({ validityKind: 3, validDays: 7 }), '首次游玩起 7 天内有效');
  assert.equal(mv.validityText({ validityKind: 0 }), '');
  const card = mv.productCard({ productId: 1, productName: '上海城市包', productKind: 2, cashPrice: 99, pointsPrice: 990,
    validityKind: 2, fixedValidFrom: '2026-11-01 00:00:00', fixedValidTo: '2027-01-31 00:00:00', seatCount: 1,
    items: [{ topicTitle: 'A' }, { topicTitle: 'B' }] });
  assert.equal(card.kind, '城市包');
  assert.equal(card.price, '¥99.00 / 990 积分');
  assert.equal(card.coverCount, 2);
  assert.equal(card.seats, '', '一人票不写人数');
  assert.equal(mv.productCard({ productId: 2, productName: 'x' }).price, '', '价格未知不写 0');
});

test('购买区:按钮只来自 availablePayModes;现金关闭只写原因', () => {
  const v = mv.purchaseView({ purchaseRoute: 1, availablePayModes: [2], totalPointsAmount: 990, totalCashAmount: 99,
    cashUnavailableReason: 'PAYMENT_OWNER_ROUTE_NOT_WIRED', quoteSign: 's', quoteExpiresAt: '2026-11-10T05:09:03.652+08:00' });
  assert.equal(v.state, 'buyable');
  assert.deepEqual(v.payModes.map((m) => m.label), ['990 积分兑换']);
  assert.equal(v.reason, '现金支付暂未开通');
  const none = mv.purchaseView({ purchaseRoute: 1, availablePayModes: [], cashUnavailableReason: 'PAYMENT_OWNER_ROUTE_NOT_WIRED',
    quoteSign: 's', quoteExpiresAt: '2026-11-10T05:09:03.652+08:00' });
  assert.equal(none.state, 'unavailable');
  assert.equal(none.payModes.length, 0);
});

test('团体/学校 route=2:不在本域收款,给原报名结算入口;加座 route=3 只写不可用原因', () => {
  const g = mv.purchaseView({ purchaseRoute: 2, purchaseRouteReason: 'USE_REGISTRATION_CHECKOUT', availablePayModes: [],
    checkout: { activityId: 9, ticketId: 76 } });
  assert.equal(g.state, 'checkout');
  assert.deepEqual(g.checkout, { activityId: 9, ticketId: 76 });
  const s = mv.purchaseView({ purchaseRoute: 3 });
  assert.equal(s.state, 'unavailable');
  assert.equal(s.payModes.length, 0);
  assert.equal(s.reason, '加座需从原订单发起，暂不可直接购买');
  assert.equal(mv.productCard({ productId: 5, productName: '加座', productKind: 8 }).isExtraSeat, true);
});

test('下单请求只回传服务端报价,不带金额;quoteExpiresAt 转毫秒', () => {
  const body = mv.orderRequest(9101, { quoteSign: 'sig', quoteExpiresAt: '2026-11-10T05:09:03.652+08:00', quantity: 1, totalCashAmount: 99 }, 2, 1, 'rid');
  assert.deepEqual(Object.keys(body).sort(), ['fulfillmentKind', 'payMode', 'productId', 'quantity', 'quoteExpiresAt', 'quoteSign', 'requestId']);
  assert.equal(body.quoteExpiresAt, Date.parse('2026-11-10T05:09:03.652+08:00'));
});

test('订单状态只认服务端 statusLabel;首次使用型写待激活;未知不写已购', () => {
  assert.equal(mv.orderStateText({ statusLabel: 'PAID', fulfillmentState: 2 }), '已购买 · 首次游玩时开始计算有效期');
  assert.equal(mv.orderStateText({ statusLabel: 'PENDING_CASH', payment: { blockedReason: 'PAYMENT_OWNER_ROUTE_NOT_WIRED' } }),
    '待支付 · 现金支付暂未开通');
  assert.equal(mv.orderStateText({ statusLabel: 'UNKNOWN' }), '状态待确认');
  const row = mv.orderRow({ orderNo: 'MKO1', productId: 9101, statusLabel: 'PAID', payMode: 2, pointsAmount: 990 }, { 9101: '上海城市包' });
  assert.equal(row.name, '上海城市包');
  assert.equal(row.amount, '990 积分');
});

test('礼品:待领取时说明双方都不能玩;领取失败按 reasonCode 分别说', () => {
  assert.equal(mv.giftRow({ giftId: 1, status: 0 }, 'bought').state, '待领取 · 领取前你和对方都不能游玩');
  assert.equal(mv.giftRow({ giftId: 1, status: 3 }, 'bought').state, '已撤销 · 退回给你');
  assert.equal(mv.giftRow({ giftId: 1, status: 1 }, 'received').state, '已领取');
  assert.equal(mv.claimReasonText('GIFT_ALREADY_CLAIMED'), '礼品已被领取');
  assert.equal(mv.claimReasonText('GIFT_CLAIM_EXPIRED'), '礼品领取码已过期');
  assert.equal(mv.claimReasonText('GIFT_SOURCE_OWNER_MISMATCH'), '礼品当前不可领取', '不泄露原单归属细节');
});

test('页面接线:通行证三页已注册;详情页下单只回传报价,网络未知保留 requestId', () => {
  const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'));
  const member = app.subPackages.find((p) => p.root === 'subpackageMember');
  ['market/passes/index', 'market/pass/index', 'market/gift/index'].forEach((pg) => assert.ok(member.pages.includes(pg), pg));
  const js = fs.readFileSync(path.join(ROOT, 'subpackageMember/market/pass/index.js'), 'utf8');
  assert.match(js, /mv\.orderRequest\(/);
  assert.doesNotMatch(js, /totalCashAmount\s*\*/, '不在端上乘价');
  assert.match(js, /fail: \(\) => this\.setData\(\{ buying: false, buyError: '结果待确认/);
  assert.match(js, /complete: \(\) => this\._verify\(order\.orderNo, 0\)/, 'SDK 返回后只查服务端订单');
});
