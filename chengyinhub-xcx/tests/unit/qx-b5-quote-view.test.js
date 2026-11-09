// qx-b5 U07-01 / U07-02 / §17.5 / §17.6:结算页只读服务端报价。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const qv = require('../../pages/activity/utils/quote-view.js');

const ROOT = path.join(__dirname, '..', '..');

test('U07-01 单票种交付:人数、审核、服务时间、使用人、退款规则逐行来自 delivery', () => {
  const { rows, perTicket } = qv.deliveryRows({
    purchaseKind: 2, ticketName: '标准票', minPeople: 3, maxPeople: 5, needAudit: 1,
    serviceStartAt: '2026-11-20T02:00:00.000+00:00', serviceEndAt: '2026-11-20T06:00:00.000+00:00',
    refundRule: '开始前 24 小时可退', beneficiaryKind: 'PAYER_SELF', amountFromServer: true
  });
  assert.equal(perTicket, false);
  const byKey = Object.fromEntries(rows.map((r) => [r.key, r.value]));
  assert.equal(byKey.people, '3–5 人');
  assert.equal(byKey.audit, '需主办方审核');
  assert.equal(byKey.window, '2026.11.20 10:00 – 2026.11.20 14:00');
  assert.equal(byKey.beneficiary, '付款人本人');
  assert.equal(byKey.refundRule, '开始前 24 小时可退');
});

test('U07-01 多票混选(ticketName=null):只写「按票种分别说明」,不挑一个票种条款,不出可退', () => {
  const { rows, perTicket } = qv.deliveryRows({
    purchaseKind: 2, ticketName: null, teamSize: null, minPeople: null, maxPeople: null,
    refundRule: null, beneficiaryKind: 'PAYER_SELF'
  });
  assert.equal(perTicket, true);
  assert.deepEqual(rows.map((r) => r.key), ['kind', 'perTicket', 'beneficiary']);
  assert.equal(rows[1].value, '按票种分别说明');
});

test('U07-01 refundRule=null 时不出退款行;delivery 缺失时整块不显示', () => {
  const { rows } = qv.deliveryRows({ ticketName: '票', refundRule: null });
  assert.ok(!rows.some((r) => r.key === 'refundRule'));
  assert.deepEqual(qv.deliveryRows(null).rows, []);
});

test('U07-02 paymentChannels:缺字段=未知不拦;应付>0 无 JSAPI=不可付;只有 APP 也不能在小程序付', () => {
  assert.equal(qv.paymentChannelState({ payAmount: 50 }), 'unknown');
  assert.equal(qv.paymentChannelState({ payAmount: 50, paymentChannels: [] }), 'unavailable');
  assert.equal(qv.paymentChannelState({ payAmount: 50, paymentChannels: ['APP'] }), 'unavailable');
  assert.equal(qv.paymentChannelState({ payAmount: 50, paymentChannels: ['JSAPI', 'APP'] }), 'ready');
  assert.equal(qv.paymentChannelState({ payAmount: 0, paymentChannels: [] }), 'free');
});

test('§17.6 优惠分项:标签直用 label,原价划线只在有减免时出现,不自算应付', () => {
  const v = qv.marketingView({
    listAmount: 300, marketingDiscountAmount: 50, payAmount: 240,
    marketingAdjustments: [
      { kind: 'OFFER', code: 'DUO', label: '双人组合包', amount: 30 },
      { kind: 'PROMOTION', code: 'FIRST', label: '首关优惠', amount: 20 },
      { kind: 'TIER', code: 'T0', label: '', amount: 0 }
    ]
  });
  assert.equal(v.show, true);
  assert.equal(v.listAmount, '300.00');
  assert.deepEqual(v.adjustments.map((a) => [a.label, a.amount]), [['双人组合包', '30.00'], ['首关优惠', '20.00']]);
  assert.ok(!('payAmount' in v), '应付不由视图重算');
  const none = qv.marketingView({ listAmount: 300, marketingDiscountAmount: 0, marketingAdjustments: [] });
  assert.equal(none.show, false);
  assert.equal(none.listAmount, null);
});

test('§17.6 coveredByPass 只提示不禁用;完局奖励券为 null 时整块不显示', () => {
  assert.equal(qv.marketingView({ coveredByPass: true }).coveredByPass, true);
  assert.equal(qv.marketingView({ completeRewardCoupon: null }).rewardCoupon, null);
  assert.deepEqual(qv.marketingView({ completeRewardCoupon: { name: '下一关 8 折券', amount: 8 } }).rewardCoupon,
    { name: '下一关 8 折券' });
});

test('§17.6 两种 409 分开提示', () => {
  assert.equal(qv.classifyCreateConflict({ code: 409, msg: '优惠已变化或与本单不一致,请重新报价后再提交' }), 'marketing');
  assert.equal(qv.classifyCreateConflict({ code: 409, msg: '价格已变化' }), 'price');
  assert.equal(qv.classifyCreateConflict({ code: 200 }), null);
  assert.equal(qv.CONFLICT_TEXT.price, '价格已更新，请重新确认');
  assert.equal(qv.CONFLICT_TEXT.marketing, '优惠已变化或与本单不一致，请重新报价后再提交');
});

test('§17.6 报价签名变化 ⇒ 换新 requestId;已建单不换;首次报价不换', () => {
  assert.equal(qv.shouldRotateRequestId('sigA', 'sigB', false), true);
  assert.equal(qv.shouldRotateRequestId('sigA', 'sigA', false), false);
  assert.equal(qv.shouldRotateRequestId('sigA', 'sigB', true), false);
  assert.equal(qv.shouldRotateRequestId('', 'sigB', false), false);
});

test('§17.5 促销目录:空则不渲染;不可用写 reasonCode 文案;剩余时间只用 serverNow', () => {
  assert.equal(qv.catalogView({ serverNow: 0, offers: [], promotions: [], tiers: [] }).show, false);
  assert.equal(qv.catalogView(null).show, false);
  const now = Date.UTC(2026, 10, 9, 12, 0, 0);
  const v = qv.catalogView({
    serverNow: now,
    offers: [{ offerCode: 'DUO', name: '双人组合包', available: true, differenceAmount: 50 },
      { offerCode: 'TRIO', name: '三人组合包', available: false, reasonCode: 'OFFER_ITEMS_MISMATCH', differenceAmount: 80 }],
    promotions: [{ promoCode: 'AFTER', name: '完局限时优惠', eligible: true, windowEndsAt: now + 5 * 3600000 + 1000 },
      { promoCode: 'FIRST', name: '首关优惠', eligible: false, reasonCode: 'TRIGGER_FACT_MISSING', sourceFactTime: now }],
    tiers: [{ tierCode: 'T1', minQuantity: 3, maxQuantity: 5, unitPrice: 80 }]
  });
  assert.equal(v.show, true);
  assert.equal(v.offers[0].saveText, '省 ¥50.00');
  assert.equal(v.offers[1].saveText, '', '不可用组合包不显示省多少');
  assert.equal(v.offers[1].reason, '该优惠与当前票数不匹配');
  assert.equal(v.promotions[0].remain, '还剩 5 小时');
  assert.equal(v.promotions[1].reason, '通关后解锁', 'sourceFactTime 非空不推断资格');
  assert.deepEqual(v.tiers.map((t) => [t.range, t.unitPrice]), [['3–5 人', '80.00']]);
});

test('结算页接线:下单带 marketingSign,报价走 _applyQuoteFacts,409 分类提示', () => {
  const src = fs.readFileSync(path.join(ROOT, 'pages/activity/baoming/baoming.js'), 'utf8');
  assert.match(src, /marketingSign: marketingSign \|\| null/);
  assert.equal((src.match(/that\._applyQuoteFacts\(res\.data\)/g) || []).length, 2, '两处报价都存 marketingSign');
  assert.match(src, /quoteView\.classifyCreateConflict\(res\)/);
  assert.match(src, /that\._reqIdQuoteSign = that\._quoteSign/);
  assert.match(src, /url: '\/api\/marketing\/promotions\/catalog',\s*method: 'GET',\s*data: \{ ownerType: 2, ownerId: activityId \}/);
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/activity/baoming/baoming.wxml'), 'utf8');
  assert.match(wxml, /wx:for="\{\{deliveryRows\}\}"/);
  assert.match(wxml, /wx:for="\{\{mkt\.adjustments\}\}"/);
  assert.doesNotMatch(wxml, /暂无优惠/);
});
