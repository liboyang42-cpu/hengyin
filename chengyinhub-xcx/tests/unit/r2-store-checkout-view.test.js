'use strict';
// r2-store 主题结账 view:只读服务端事实,不伪造价格/席位/积分。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { checkoutView, coveringPasses, playerText } = require('../../pages/topic/utils/checkout-view.js');

const info = { id: 990014, selfPlay: 1, selfPlayPrice: 39, productType: 1, chaptersList: [{ nodes: [{ id: 1 }, { id: 2 }] }] };
const quote = { payAmount: 39, pointsUsed: 0, memberDiscountYuan: 0, refundableNow: false, refundPercentNow: 0 };

test('报价到达:价格取 payAmount,无减免不划线,积分/席位步进器不出现', () => {
  const v = checkoutView({ info, quote, quoteState: 'ready', win: { untilText: '1 月 9 日' } });
  assert.equal(v.single.price, '¥39');
  assert.equal(v.single.strike, '');
  assert.equal(v.totalText, '¥39');
  assert.equal(v.payLabel, '支付 ¥39');
  assert.deepEqual(v.bill.map((r) => r.k), ['自玩门票 × 1']);
  assert.equal(v.points, undefined);
  assert.equal(v.seats, undefined);
  assert.deepEqual(v.trust.map((t) => t.label), ['购买后不可退', '1 人自玩']);
});

test('俱乐部会员减免:划线原价 = 应付 + memberDiscountYuan,明细出减项', () => {
  const v = checkoutView({ info, quote: Object.assign({}, quote, { payAmount: 31.2, memberDiscountYuan: 7.8 }), quoteState: 'ready' });
  assert.equal(v.single.strike, '¥39');
  assert.equal(v.single.price, '¥31.20');
  assert.deepEqual(v.bill.map((r) => r.v), ['¥39', '-¥7.80']);
});

test('可退时只按服务端百分比写', () => {
  const full = checkoutView({ info, quote: Object.assign({}, quote, { refundableNow: true, refundPercentNow: 100 }), quoteState: 'ready' });
  assert.equal(full.trust[0].label, '现在可全额退');
  const half = checkoutView({ info, quote: Object.assign({}, quote, { refundableNow: true, refundPercentNow: 50 }), quoteState: 'ready' });
  assert.equal(half.trust[0].label, '现在可退 50%');
});

test('报价加载中/网络失败:用主题详情价,不写退款行', () => {
  const v = checkoutView({ info, quoteState: 'loading' });
  assert.equal(v.loading, true);
  assert.equal(v.totalText, '¥39');
  assert.ok(!v.trust.some((t) => t.key === 'refund'));
});

test('报价被拒:不出价格与明细,按钮不可付,字段名原话不给玩家看', () => {
  const v = checkoutView({ info, quoteState: 'refused', refusal: 'sellingStartedAt 缺失，拒绝售卖' });
  assert.equal(v.refused, true);
  assert.equal(v.totalText, '—');
  assert.equal(v.bill.length, 0);
  assert.equal(v.payLabel, '暂不可购买');
  assert.equal(v.refusal, '暂不可购买');
  assert.equal(playerText('自玩票已售罄'), '自玩票已售罄');
});

test('未知价格不写 0', () => {
  const v = checkoutView({ info: { id: 1 }, quoteState: 'failed' });
  assert.equal(v.single.price, '—');
  assert.equal(v.free, false);
});

test('通票:只列 items 覆盖本主题的商品,排除礼品与加座', () => {
  const products = [
    { productId: 1, productName: '上海城市包', productKind: 2, saleCity: '上海', cashPrice: 88, validityKind: 1, validDays: 30, items: [{ topicId: 990014 }, { topicId: 2 }] },
    { productId: 2, productName: '别的城市包', productKind: 2, cashPrice: 66, items: [{ topicId: 3 }] },
    { productId: 3, productName: '礼品', productKind: 7, cashPrice: 39, items: [{ topicId: 990014 }] },
    { productId: 4, productName: '加座', productKind: 8, cashPrice: 10, items: [{ topicId: 990014 }] }
  ];
  const rows = coveringPasses(products, 990014);
  assert.deepEqual(rows, [{ id: '1', name: '上海城市包', meta: '上海 · 2 个主题 · 30 天有效', price: '¥88' }]);
  assert.deepEqual(coveringPasses(null, 990014), []);
});

test('结账层组件:无积分开关、无席位步进器、通票点进原详情页', () => {
  const root = path.join(__dirname, '../../pages/topic');
  const wxml = fs.readFileSync(path.join(root, 'components/cy/self-play-checkout/index.wxml'), 'utf8');
  assert.ok(!/积分抵扣/.test(wxml));
  assert.ok(!/stepper/.test(wxml));
  const page = fs.readFileSync(path.join(root, 'index/index.js'), 'utf8');
  assert.match(page, /\/subpackageMember\/market\/pass\/index\?id=/);
  assert.match(page, /url: '\/api\/registration\/quote'/);
});
