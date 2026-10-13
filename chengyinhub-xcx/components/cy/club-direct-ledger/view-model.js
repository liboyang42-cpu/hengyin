const STATUS = {
  OPEN: '待结清', SETTLED: '已结清', VOID: '核销已撤销',
  REFUND_DUE: '待退回', REFUNDED: '已退回',
};

function positiveId(value) { return Number.isSafeInteger(Number(value)) && Number(value) > 0; }
function cents(value) {
  if (value === null || value === undefined || !/^\d+(\.\d{1,2})?$/.test(String(value))) return null;
  const amount = Math.round(Number(value) * 100);
  return Number.isSafeInteger(amount) ? amount : null;
}
function amountText(value) { return '¥' + (value / 100).toFixed(2); }

function ledgerView(response, scope) {
  if (!response || ![200, '200'].includes(response.code) || !Array.isArray(response.data)) return null;
  const seen = new Set();
  const totals = { OPEN: 0, SETTLED: 0, REFUND_DUE: 0 };
  let activeCount = 0;
  const rows = [];
  for (const row of response.data) {
    const amount = cents(row && row.perHeadFeeSnapshot);
    if (!row || !positiveId(row.id) || seen.has(Number(row.id)) || !positiveId(row.redemptionId)
        || !positiveId(row.topicId) || !positiveId(row.payerClubId) || !positiveId(row.merchantMemberId)
        || !Object.prototype.hasOwnProperty.call(STATUS, row.status) || amount === null
        || (scope.topicId && Number(row.topicId) !== Number(scope.topicId))
        || (scope.kind === 'club' && Number(row.payerClubId) !== Number(scope.clubId))) return null;
    seen.add(Number(row.id));
    if (row.status === 'OPEN' || row.status === 'SETTLED') activeCount += 1;
    if (Object.prototype.hasOwnProperty.call(totals, row.status)) {
      totals[row.status] += amount;
      if (!Number.isSafeInteger(totals[row.status])) return null;
    }
    rows.push({ id: row.id, redemptionId: row.redemptionId, amountText: amountText(amount),
      statusText: STATUS[row.status], status: row.status,
      timeText: typeof row.createTime === 'string' ? row.createTime.slice(0, 16).replace('T', ' ') : '',
      voucher: row.status === 'SETTLED' || row.status === 'REFUND_DUE'
        ? row.payVoucherNo || '' : (row.status === 'REFUNDED' ? row.refundVoucherNo || '' : ''),
    });
  }
  return { rows, activeCount, stats: [
    { key: 'open', label: '待结清', value: amountText(totals.OPEN) },
    { key: 'settled', label: '已结清', value: amountText(totals.SETTLED) },
    { key: 'refund', label: '待退回', value: amountText(totals.REFUND_DUE) },
  ] };
}

module.exports = { ledgerView };
