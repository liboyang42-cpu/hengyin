// qx-b5 U07-04:订单退款进度只读 settlementSummary。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { settlementView } = require('../../subpackageMember/utils/order-settlement-view.js');

const ROOT = path.join(__dirname, '..', '..');

test('无退款事实 ⇒ 整块不显示', () => {
  assert.equal(settlementView(null).show, false);
  assert.equal(settlementView({ refundStage: 'NONE', refundedTicketCount: 0, amountsComplete: true }).show, false);
});

test('已批准未到账:写「等待原路退回」,金额只进处理中,不写已退回', () => {
  const v = settlementView({
    refundStage: 'PAYOUT_PENDING', amountsComplete: true,
    refundPendingAmount: 0, refundProcessingAmount: 59, refundCompletedAmount: 0, refundRequestedAmount: 59,
    ticketCount: 1, refundedTicketCount: 0, activeTicketCount: 1
  });
  assert.equal(v.show, true);
  assert.equal(v.stage, '已批准，等待原路退回');
  assert.deepEqual(v.rows.map((r) => [r.label, r.value]), [['退款处理中', '¥59.00']]);
});

test('amountsComplete=false:未知分项写「金额待确认」,不出合计,不补 0', () => {
  const v = settlementView({
    refundStage: 'UNDER_REVIEW', amountsComplete: false,
    refundPendingAmount: null, refundProcessingAmount: 0, refundCompletedAmount: 20, refundRequestedAmount: null
  });
  const map = Object.fromEntries(v.rows.map((r) => [r.key, r.value]));
  assert.equal(map.pending, '金额待确认');
  assert.equal(map.completed, '¥20.00');
  assert.ok(!('total' in map));
  assert.ok(!('processing' in map), '已知为 0 的分项不占行');
});

test('部分退款:退一张不撤整单,按票数分别写', () => {
  const v = settlementView({
    refundStage: 'COMPLETED', amountsComplete: true,
    refundPendingAmount: 0, refundProcessingAmount: 0, refundCompletedAmount: 69, refundRequestedAmount: 69,
    ticketCount: 3, refundedTicketCount: 1, activeTicketCount: 2
  });
  assert.equal(v.stage, '已原路退回');
  assert.equal(v.stageTone, 'done');
  assert.ok(v.rows.some((r) => r.key === 'tickets' && r.value === '已退 1 张 · 有效 2 张'));
});

test('refundStage=UNKNOWN 写「退款状态待确认」;权益为 null 时不出已使用', () => {
  const v = settlementView({ refundStage: 'UNKNOWN', amountsComplete: false, entitlementTotal: null });
  assert.equal(v.stage, '退款状态待确认');
  assert.ok(!v.rows.some((r) => r.key === 'entitlement'));
});

test('订单详情接线:有退款进度时支付明细不再出看似到账的「退款」行', () => {
  const dir = path.join(ROOT, 'subpackageMember/components/cy/scene-member-order-detail');
  const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8');
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8');
  assert.match(js, /info\.settleView = settlementView\(info\.settlementSummary\)/);
  assert.match(wxml, /wx:if="\{\{info\.settleView\.show\}\}"/);
  assert.match(wxml, /info\.refundAmountShown && !info\.settleView\.show/);
});
