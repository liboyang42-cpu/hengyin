// U07-04 取消与部分退款的实际影响:只读 /api/registration/info 回带的 settlementSummary。
// - 受理/批准 ≠ 到账:只有 COMPLETED 才写「已退回」。
// - amountsComplete=false:不出合计;单项为 null 写「金额待确认」,不补 0。
// - 退一张不撤整单:按票数分别写已退/有效。
// - refundStage=UNKNOWN:写「退款状态待确认」,不当作没有退款。

const STAGE_TEXT = {
  UNDER_REVIEW: '退款审核中',
  PAYOUT_PENDING: '已批准，等待原路退回',
  MANUAL_PAYOUT: '人工退款处理中',
  COMPLETED: '已原路退回',
  REJECTED: '退款未通过',
  MANUAL_REVIEW: '售后处理中',
  UNKNOWN: '退款状态待确认'
};

function isNum(v) {
  return v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v));
}

function money(v) {
  return isNum(v) ? '¥' + Number(v).toFixed(2) : '金额待确认';
}

function settlementView(summary) {
  const hidden = { show: false, stage: '', rows: [] };
  if (!summary || typeof summary !== 'object') return hidden;
  const stage = summary.refundStage || 'NONE';
  const refundedTickets = Number(summary.refundedTicketCount) || 0;
  const tickets = Number(summary.ticketCount) || 0;
  if (stage === 'NONE' && refundedTickets === 0) return hidden;

  const rows = [];
  const complete = summary.amountsComplete === true;
  const amountRow = (key, label, value) => {
    // 已知为 0 的分项不占行;未知(null)且分项不全时如实写待确认
    if (isNum(value) && Number(value) === 0) return;
    if (!isNum(value) && complete) return;
    rows.push({ key, label, value: money(value) });
  };
  amountRow('pending', '审核中', summary.refundPendingAmount);
  amountRow('processing', '退款处理中', summary.refundProcessingAmount);
  amountRow('completed', '已退回', summary.refundCompletedAmount);
  if (complete && isNum(summary.refundRequestedAmount) && Number(summary.refundRequestedAmount) > 0
      && rows.length > 1) {
    rows.push({ key: 'total', label: '退款合计', value: money(summary.refundRequestedAmount) });
  }
  if (tickets > 1 && refundedTickets > 0) {
    const active = Number(summary.activeTicketCount) || 0;
    rows.push({ key: 'tickets', label: '票数', value: '已退 ' + refundedTickets + ' 张 · 有效 ' + active + ' 张' });
  }
  if (isNum(summary.entitlementTotal) && Number(summary.entitlementTotal) > 0) {
    rows.push({
      key: 'entitlement',
      label: '已使用',
      value: (Number(summary.entitlementRedeemed) || 0) + ' / ' + Number(summary.entitlementTotal)
    });
  }
  return {
    show: true,
    stage: STAGE_TEXT[stage] || (refundedTickets > 0 ? '部分已退款' : STAGE_TEXT.UNKNOWN),
    stageTone: stage === 'COMPLETED' ? 'done' : (stage === 'REJECTED' ? 'muted' : 'pending'),
    rows
  };
}

module.exports = { settlementView, STAGE_TEXT };
