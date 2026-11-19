// F11 自由探索结算（参考）：服务端 /api/fe/reference-settlement/{merchant|payer} 的展示模型。
// 合同(小程序与 iOS 共用)：{groups:[{activityId, activityTitle, payerName, openAmount, settledAmount,
//   rows:[{id, chapterName, merchantName, supplierKind, basis, quantity, unitAmount, amount, status,
//   statusText, voucherNo, settledAt}]}], totalOpenAmount, totalSettledAmount}。
// 金额只做格式化，不在前端重算合计；任何字段不合合同整份判失败(fail-closed)。
const STATUS = { OPEN: '待线下结款', SETTLED_OFFLINE: '已线下结清', DISPUTED: '有异议', VOID: '已作废' };
const STATUS_VARIANT = { OPEN: 'warning', SETTLED_OFFLINE: 'success', DISPUTED: 'danger', VOID: 'neutral' };
const BASIS = { PERK_REDEEMED: '权益核销', REVSHARE_HEAD: '计酬人头', TRAFFIC: '引流', DISTRIBUTION: '分销' };

function positiveId(value) {
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^\d+$/.test(value))) return false;
  return Number.isSafeInteger(Number(value)) && Number(value) > 0;
}
function cents(value) {
  if (value === null || value === undefined || !/^\d+(\.\d{1,2})?$/.test(String(value))) return null;
  const amount = Math.round(Number(value) * 100);
  return Number.isSafeInteger(amount) ? amount : null;
}
function amountText(value) { return '¥' + (value / 100).toFixed(2); }
function text(value) { return typeof value === 'string' ? value.trim() : ''; }

function rowView(row, kind) {
  const amount = cents(row && row.amount);
  const unit = cents(row && row.unitAmount);
  if (!row || !positiveId(row.id) || !Object.prototype.hasOwnProperty.call(STATUS, row.status)
      || !Object.prototype.hasOwnProperty.call(BASIS, row.basis)
      || !['MERCHANT', 'AUTHOR'].includes(row.supplierKind)
      || amount === null || unit === null || !Number.isInteger(row.quantity) || row.quantity < 0) return null;
  const author = row.supplierKind === 'AUTHOR';
  // 商家权益卡没填结算参考价时服务端记 0 —— 这不是「0 元」，是双方另行约定。
  const agreed = !author && amount === 0 && row.status !== 'VOID';
  return {
    id: row.id,
    status: row.status,
    title: [text(row.chapterName), kind === 'payer' ? text(row.merchantName) : ''].filter(Boolean).join(' · ')
      || BASIS[row.basis],
    metaText: [BASIS[row.basis], row.quantity + ' 人次'].join(' · '),
    amount,
    amountText: agreed ? '按约定' : amountText(amount),
    statusText: text(row.statusText) || STATUS[row.status],
    statusVariant: author ? 'neutral' : STATUS_VARIANT[row.status],
    voucherNo: text(row.voucherNo),
    settledAtText: typeof row.settledAt === 'string' ? row.settledAt.slice(0, 16) : '',
    canDispute: kind === 'merchant' && !author && (row.status === 'OPEN' || row.status === 'SETTLED_OFFLINE'),
    open: !author && (row.status === 'OPEN' || row.status === 'DISPUTED'),
  };
}

/** 返回 null = 响应不合合同(由调用方显示取回失败)。 */
function referenceView(response, kind) {
  if (!['merchant', 'payer'].includes(kind)) return null;
  if (!response || ![200, '200'].includes(response.code) || !response.data
      || typeof response.data !== 'object' || !Array.isArray(response.data.groups)) return null;
  const totalOpen = cents(response.data.totalOpenAmount);
  const totalSettled = cents(response.data.totalSettledAmount);
  if (totalOpen === null || totalSettled === null) return null;
  const seen = new Set();
  const groups = [];
  for (const group of response.data.groups) {
    if (!group || !Array.isArray(group.rows) || !group.rows.length
        || (group.activityId !== null && group.activityId !== undefined && !positiveId(group.activityId))) return null;
    const open = cents(group.openAmount);
    const settled = cents(group.settledAmount);
    if (open === null || settled === null) return null;
    const rows = [];
    let redeemCount = 0;
    for (const raw of group.rows) {
      const row = rowView(raw, kind);
      if (!row || seen.has(Number(row.id))) return null;
      seen.add(Number(row.id));
      if (row.status !== 'VOID') redeemCount += raw.quantity;
      rows.push(row);
    }
    const payerName = text(group.payerName);
    groups.push({
      key: group.activityId == null ? 'topic-' + rows[0].id : 'activity-' + group.activityId,
      title: text(group.activityTitle) || '自由探索场次',
      metaText: [payerName ? '主办方：' + payerName : '', '核销 ' + redeemCount + ' 人次'].filter(Boolean).join(' · '),
      openText: amountText(open),
      settledText: amountText(settled),
      openIds: rows.filter((row) => row.open).map((row) => row.id),
      rows,
    });
  }
  return {
    groups,
    // MC-011=B：没有数据时金额照常显示 ¥0.00。
    stats: [
      { key: 'open', label: '待线下结款', value: amountText(totalOpen) },
      { key: 'settled', label: '已线下结清', value: amountText(totalSettled) },
    ],
  };
}

const DISPUTE_NOTE_MAX = 200;
const VOUCHER_MAX = 64;
function disputeNoteError(note) {
  const value = text(note);
  if (!value) return '请写明异议原因';
  return value.length > DISPUTE_NOTE_MAX ? '异议原因最多 ' + DISPUTE_NOTE_MAX + ' 字' : '';
}
function voucherError(voucher) {
  return text(voucher).length > VOUCHER_MAX ? '凭证号最多 ' + VOUCHER_MAX + ' 字' : '';
}

module.exports = { referenceView, disputeNoteError, voucherError, STATUS };
