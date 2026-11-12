// §19 / §27.10 个人分佣:只读 /api/marketing/revenue/author/mine 的本人分项。
// - 状态按码分支:accrued 已入账·待可结算 / settleable 可结算 / paid 已打款 / partially_reversed / reversed / unknown。
// - 金额优先 amountFen 整数分累加;正向与冲回分开列,不相加成「负收益」。
// - 同一人既是作者又是推荐人时保留两项,不合并。reversedAmount 是父订单上下文,不当个人收入。
// - 不出提现按钮、费率或 ruleVersion;读取失败不显示 0。
const datetime = require('../../utils/datetime.js');

const STATUS_TEXT = {
  accrued: '已入账 · 待可结算',
  settleable: '可结算',
  paid: '已打款',
  partially_reversed: '部分退款已冲回',
  reversed: '已全额冲回',
  unknown: '状态待确认'
};
const ROLE_TEXT = { author: '作者分佣', referrer: '推荐分佣' };
const ORDER_TYPE_TEXT = { 1: '报名订单', 2: '通行证订单' };

function fen(line) {
  if (line && Number.isInteger(Number(line.amountFen)) && line.amountFen !== null && line.amountFen !== '') return Number(line.amountFen);
  if (line && line.amount !== null && line.amount !== undefined && Number.isFinite(Number(line.amount))) {
    return Math.round(Number(line.amount) * 100);
  }
  return null;
}
function yuanFromFen(f, currency) {
  const sign = f < 0 ? '−' : '';
  const prefix = !currency || currency === 'CNY' ? '¥' : (currency + ' ');
  return sign + prefix + (Math.abs(f) / 100).toFixed(2);
}
function day(v) {
  const t = datetime.toTimestamp(v);
  return Number.isFinite(t) ? datetime.formatDayDots(t) : '';
}

function allocationRow(a) {
  if (!a || a.allocationId === undefined || a.allocationId === null) return null;
  const code = STATUS_TEXT[a.statusText] ? a.statusText : 'unknown';
  const lines = Array.isArray(a.lines) ? a.lines : [];
  const items = [];
  // 正向:每个角色一行
  lines.filter((l) => l && Number(l.entryType) === 1).forEach((l, i) => {
    const f = fen(l);
    items.push({ key: 'a' + i, label: ROLE_TEXT[l.role] || '分佣', value: f === null ? '金额待确认' : yuanFromFen(f, a.currency) });
  });
  // 冲回:按 sourceFact(refund:<id>)+角色分组,单列负数
  const reversals = {};
  lines.filter((l) => l && Number(l.entryType) === 2).forEach((l) => {
    const k = (l.sourceFact || 'refund') + '|' + (l.role || '');
    const f = fen(l);
    if (!reversals[k]) reversals[k] = { role: l.role, fen: 0, unknown: false };
    if (f === null) reversals[k].unknown = true; else reversals[k].fen += f;
  });
  Object.keys(reversals).forEach((k, i) => {
    const r = reversals[k];
    items.push({ key: 'r' + i, label: '退款冲回' + (ROLE_TEXT[r.role] ? ' · ' + ROLE_TEXT[r.role] : ''),
      value: r.unknown ? '金额待确认' : yuanFromFen(-Math.abs(r.fen), a.currency), reverse: true });
  });
  return {
    key: String(a.allocationId),
    title: ORDER_TYPE_TEXT[a.orderType] || '订单',
    status: STATUS_TEXT[code],
    statusCode: code,
    when: code === 'accrued' && day(a.settleableAt) ? (day(a.settleableAt) + ' 起可结算') : '',
    items
  };
}

/** 列表:null(读取失败)→ error;[] → empty;否则行。 */
function revenueView(data) {
  if (!Array.isArray(data)) return { state: 'error', rows: [] };
  const rows = data.map(allocationRow).filter(Boolean);
  return { state: rows.length ? 'ready' : 'empty', rows };
}

module.exports = { revenueView, allocationRow, STATUS_TEXT };
