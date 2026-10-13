/**
 * R9-19 v2 回归: 审核结论与打款回读必须【并列】，不能互相覆盖。
 *
 * 后端 RefundApplication.java:20-23 明确 status(审核) 与 payoutStatus(打款) 解耦：
 *   0 处理中 / 1 原路成功 / 2 待人工 / 3 人工已退待复核 / 4 双人复核完成 / 5 待派发 / 6 派发中
 * 矛盾组合(钱已动/在途但审核未通过或已驳回)必须同时显示两侧并标「状态冲突 · 需核查」。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const refundSource = fs.readFileSync(
  path.join(uiRoot, 'src/views/business/finance/refund/index.vue'),
  'utf8'
)

function loadRefundWorkbench() {
  const script = refundSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listRefund, addRefund, reviewRefund \} from '@\/api\/business\/refund'$/m, 'const { listRefund, addRefund, reviewRefund } = {};')
    .replace(/^import auth from '@\/plugins\/auth'$/m, 'const auth = {};')
    .replace(/^import (ActivityCancelRefundCases|ManualPayoutQueue|Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function(script.replace(/export\s+default\s*/, 'return '))()
}

test('R9-19 打款结果永远按后端 payoutStatus 解释, 不被审核状态覆盖', () => {
  const m = loadRefundWorkbench().methods

  assert.equal(m.payoutLabel(1, 2), '原路渠道成功', '已驳回但原路成功: 必须仍显示真实打款结果')
  assert.equal(m.payoutLabel(4, 0), '人工凭证复核完成', '未审批但有复核结果: 必须仍显示真实打款结果')
  assert.equal(m.payoutLabel(5, 1), '待派发微信', 'payoutStatus=5 按后端合同解释为待派发')
  assert.equal(m.payoutLabel(6, 1), '派发中', 'payoutStatus=6 按后端合同解释为派发中')
  assert.equal(m.payoutLabel(0, 1), '原路退款处理中', '已通过且默认处理中')
  assert.equal(m.payoutLabel(0, 0), '未发起打款', '待审核且 payout=0 是默认值, 不能说处理中')
  assert.equal(m.payoutLabel(0, 2), '未执行退款', '已驳回且 payout=0 是默认值, 明确不执行退款')
  assert.equal(m.payoutLabel(undefined, 1), '待确认', '未知打款态显式待确认')
  assert.equal(m.payoutLabel(7, 1), '待确认', '越界打款态显式待确认')
})

test('R9-19 矛盾组合并列标出「状态冲突 · 需核查」, 正常组合不误报', () => {
  const m = loadRefundWorkbench().methods

  assert.match(m.payoutConflict({ status: 2, payoutStatus: 1 }), /审核已驳回.*需核查/, '驳回但原路成功必须并列冲突')
  assert.match(m.payoutConflict({ status: 0, payoutStatus: 4 }), /尚未审核通过.*需核查/, '未审批但已复核必须并列冲突')
  assert.match(m.payoutConflict({ status: 0, payoutStatus: 6 }), /尚未审核通过.*需核查/, '未审批但派发中必须并列冲突')
  assert.match(m.payoutConflict({ status: 2, payoutStatus: 5 }), /审核已驳回.*需核查/, '驳回但待派发必须并列冲突')

  assert.equal(m.payoutConflict({ status: 1, payoutStatus: 0 }), '', '正常处理中不报冲突')
  assert.equal(m.payoutConflict({ status: 1, payoutStatus: 5 }), '', '已通过待派发不报冲突')
  assert.equal(m.payoutConflict({ status: 0, payoutStatus: 0 }), '', '待审核默认 0 不报冲突')
  assert.equal(m.payoutConflict({ status: 2, payoutStatus: 0 }), '', '已驳回默认 0 不报冲突')
  assert.equal(m.payoutConflict({ status: null, payoutStatus: 1 }), '', '审核未知时不武断报冲突')
})

test('R9-19 列表与调证抽屉同时呈现审核与打款两侧, 并把冲突文案上屏', () => {
  assert.match(refundSource, /payoutLabel\(scope\.row\.payoutStatus,\s*scope\.row\.status\)/, '列表打款列必须同时传入审核状态')
  assert.match(refundSource, /payoutConflict\(scope\.row\)/, '列表必须把冲突并列标出')
  assert.match(refundSource, /payoutConflict\(detailRow\)/, '调证抽屉必须把冲突并列标出')
  assert.match(refundSource, /状态冲突 · 需核查/, '冲突要有明确文案')
  assert.doesNotMatch(refundSource, /payoutLabel\([^)]*payoutStatus\s*\)/, '不得再有只按打款状态渲染的调用')
})
