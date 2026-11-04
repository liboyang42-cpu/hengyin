// check-1104 j1 F7(第十轮 f1 第 10 项):取消前写明退多少,取消后订单上看得到退了多少。
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const view = require(path.join(__dirname, '../../subpackageMember/utils/order-refund-amount.js'));
const DETAIL = path.join(__dirname, '../../subpackageMember/components/cy/scene-member-order-detail');

test('取消确认:服务端给了 cancelRefundAmount 就在规则前写明退回金额', () => {
  assert.strictEqual(view.cancelConfirmDeadline({
    cancelRefundAmount: 19.5, refundDeadlineDisplay: '集合前 24 小时内取消,退实付的 50%,原路退回(预计1-3个工作日)',
  }), '本次取消将原路退回 ¥19.50，集合前 24 小时内取消,退实付的 50%,原路退回(预计1-3个工作日)');
  assert.strictEqual(view.cancelConfirmDeadline({ cancelRefundAmount: '69.90', refundDeadlineDisplay: '可免费取消至 11月6日 19:00' }),
    '本次取消将原路退回 ¥69.90，可免费取消至 11月6日 19:00');
  // 没给金额(多票从单 / 现金未知)只讲规则,不在前端推算
  assert.strictEqual(view.cancelConfirmDeadline({ refundDeadlineDisplay: '可免费取消至 11月6日 19:00', payableAmount: 39 }),
    '可免费取消至 11月6日 19:00');
  assert.strictEqual(view.cancelConfirmDeadline({}), '已核销或已过开始时间不可退');
});

test('取消后:退款中 / 已退款的单显示退款申请里的金额,其它状态不显示', () => {
  const refund = { refundAmount: 19.5 };
  assert.strictEqual(view.refundedAmount({ orderStateKey: 'refunding', refundApplication: refund }), '19.50');
  assert.strictEqual(view.refundedAmount({ orderStateKey: 'refunded', refundApplication: { refundAmount: '69.90' } }), '69.90');
  assert.strictEqual(view.refundedAmount({ orderStateKey: 'paid', refundApplication: refund }), null);
  assert.strictEqual(view.refundedAmount({ orderStateKey: 'refunding' }), null);
});

test('订单详情接线:取消确认用这份文案,支付明细出「退款」一行', () => {
  const js = fs.readFileSync(path.join(DETAIL, 'index.js'), 'utf8');
  const wxml = fs.readFileSync(path.join(DETAIL, 'index.wxml'), 'utf8');
  assert.match(js, /dangerParams: \{ deadline: refundAmount\.cancelConfirmDeadline\(info\) \}/);
  assert.match(js, /info\.refundAmountShown = refundAmount\.refundedAmount\(info\)/);
  assert.match(wxml, /wx:if="\{\{info\.refundAmountShown\}\}"[\s\S]*?退款[\s\S]*?money\.amount\(info\.refundAmountShown\)/);
});

test('订单列表与参与详情的取消确认同样写明退回金额(第十轮 f1 F7 补全)', () => {
  // 列表行的 cancelRefundAmount 由后端列表同一处 attachRefundVerdict 随行下发,前端不逐行补查
  assert.strictEqual(view.cancelConfirmDeadline({ cancelRefundAmount: '19.50' }, '集合前 24 小时内取消,退实付的 50%'),
    '本次取消将原路退回 ¥19.50，集合前 24 小时内取消,退实付的 50%');
  assert.strictEqual(view.cancelConfirmDeadline({}, '退款规则以提交后的平台判定为准'), '退款规则以提交后的平台判定为准');
  const list = fs.readFileSync(path.join(__dirname, '../../subpackageMember/components/cy/scene-member-order-history/index.js'), 'utf8');
  assert.match(list, /deadline: refundAmount\.cancelConfirmDeadline\(that\.data\.list\[index\], refundDeadlineText\(that\.data\.list\[index\]\)\)/);
  assert.doesNotMatch(list, /\/api\/registration\/info/, '列表不逐行补查详情');
  const participation = fs.readFileSync(path.join(__dirname, '../../subpackageMember/components/scene-member-participation-detail/index.js'), 'utf8');
  assert.match(participation, /deadline: require\('\.\.\/\.\.\/utils\/order-refund-amount\.js'\)\.cancelConfirmDeadline\(this\.data\.info\)/);
});
