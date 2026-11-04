// check-1104 j1 F7:玩家取消前看到这一单退多少,取消后在订单上看到退了多少。
// 金额只取服务端:取消前 = /api/registration/info 的 cancelRefundAmount(与实际退款同一公式),
// 取消后 = 该单退款申请的 refundAmount。服务端没给就不出数,只讲规则,不在前端推算。
function positive(value) {
  const n = Number(value);
  return value != null && value !== '' && Number.isFinite(n) && n > 0 ? n.toFixed(2) : null;
}

/** 取消确认里 {deadline} 那一句:有金额时在规则前写明这次退回多少。 */
function cancelConfirmDeadline(info) {
  const value = info || {};
  const rule = value.refundDeadlineDisplay || '已核销或已过开始时间不可退';
  const amount = positive(value.cancelRefundAmount);
  return amount ? '本次取消将原路退回 ¥' + amount + '，' + rule : rule;
}

/** 退款中 / 已退款的单在支付明细里显示的退款金额(两位小数字符串);没有就 null。 */
function refundedAmount(info) {
  const value = info || {};
  if (value.orderStateKey !== 'refunding' && value.orderStateKey !== 'refunded') return null;
  return positive(value.refundApplication && value.refundApplication.refundAmount);
}

module.exports = { cancelConfirmDeadline, refundedAmount };
