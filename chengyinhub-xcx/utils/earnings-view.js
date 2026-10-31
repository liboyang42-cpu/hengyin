// 账户收益(2026-10-28 业主「参考 Revolut」):本月收入明细、我的资产、分析页共用的读数。
// 数据源只有两条现成接口:/api/user/balance/list(余额流水)与 /api/wallet/stages(到账三段),
// 这里只做聚合,不造字段。changeType 1 收入 / 2 支出;eventType 1 主题分润 2 活动分润 3 提现 4 提现驳回 5 商家邀请分润。

const SOURCE = {
  1: { key: 'topic', label: '主题售卖', icon: 'route' },
  2: { key: 'activity', label: '活动分润', icon: 'flag' },
  4: { key: 'refund', label: '提现退回', icon: 'finance' },
  5: { key: 'invite', label: '邀请奖励', icon: 'gift' },
};
const OTHER = { key: 'other', label: '其他收入', icon: 'finance' };

function toDate(value) {
  if (value instanceof Date) return value;
  const d = new Date(String(value || '').replace(/-/g, '/'));
  return isNaN(d.getTime()) ? null : d;
}
function amount(value) {
  const n = Number(value);
  return Number.isFinite(n) ? Math.abs(n) : 0;
}
function isIncome(row) { return !!row && Number(row.changeType) === 1; }
function sameMonth(d, now) { return !!d && d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth(); }
function sourceOf(row) { return SOURCE[Number(row && row.eventType)] || OTHER; }

/** 本月收入:合计 + 最近几条(倒序) */
function monthIncome(rows, now, limit) {
  const today = now || new Date();
  const list = (Array.isArray(rows) ? rows : []).filter((r) => isIncome(r) && sameMonth(toDate(r.createTime), today));
  list.sort((a, b) => (toDate(b.createTime) || 0) - (toDate(a.createTime) || 0));
  const total = list.reduce((sum, r) => sum + amount(r.changeBalance), 0);
  const items = list.slice(0, limit || 3).map((r, i) => {
    const d = toDate(r.createTime); const s = sourceOf(r);
    return { key: String(r.id || i), title: String(r.changeReason || s.label), tag: s.label, icon: s.icon,
      date: d ? (d.getMonth() + 1) + '月' + d.getDate() + '日' : '', amountText: '+' + amount(r.changeBalance).toFixed(2),
      at: d ? d.getTime() : 0 };
  });
  return { total: total.toFixed(2), count: list.length, items, monthLabel: (today.getMonth() + 1) + '月' };
}

// M-32(2026-10-29 业主「要的」):俱乐部计酬(俱乐部直接付款,/api/club-direct-merchant/merchant)
// 不进余额流水,并进本月收入明细作单独来源行。只取本月待结清/已结清两态(撤销、退回不是收入),
// 行已经过 club-direct-ledger/view-model 的 ledgerView 校验;不计入余额口径的 total。
const CLUB_PAY = { label: '俱乐部计酬', icon: 'connect' };
const CLUB_PAY_STATUS = ['OPEN', 'SETTLED'];
function clubPayMonthItems(ledgerRows, now) {
  const today = now || new Date();
  return (Array.isArray(ledgerRows) ? ledgerRows : []).filter((r) => r && CLUB_PAY_STATUS.indexOf(r.status) !== -1)
    .map((r) => ({ r, d: toDate(r.timeText) }))
    .filter((x) => sameMonth(x.d, today))
    .map(({ r, d }) => ({ key: 'club-' + r.id, title: '核销 #' + r.redemptionId, tag: CLUB_PAY.label + ' · ' + r.statusText,
      icon: CLUB_PAY.icon, date: (d.getMonth() + 1) + '月' + d.getDate() + '日',
      amountText: '+' + String(r.amountText || '').replace(/^¥/, ''), at: d.getTime() }));
}
/** 本月收入明细 = 余额收入行 + 俱乐部计酬行,按时间倒序 */
function withClubPay(month, clubItems) {
  const items = (month && month.items || []).concat(clubItems || []);
  items.sort((a, b) => (b.at || 0) - (a.at || 0));
  return Object.assign({}, month, { items });
}

/** 近 n 个月每月收入(旧 → 新),给分析页柱图;height 是相对最高那个月的 0–100 */
function monthlySeries(rows, now, n) {
  const today = now || new Date(); const count = n || 6; const out = [];
  for (let i = count - 1; i >= 0; i--) {
    const d = new Date(today.getFullYear(), today.getMonth() - i, 1);
    out.push({ key: d.getFullYear() + '-' + (d.getMonth() + 1), label: (d.getMonth() + 1) + '月', total: 0, current: i === 0 });
  }
  (Array.isArray(rows) ? rows : []).forEach((r) => {
    if (!isIncome(r)) return;
    const d = toDate(r.createTime); if (!d) return;
    const hit = out.find((m) => m.key === d.getFullYear() + '-' + (d.getMonth() + 1));
    if (hit) hit.total += amount(r.changeBalance);
  });
  const max = out.reduce((m, x) => Math.max(m, x.total), 0);
  return out.map((m) => Object.assign(m, { totalText: m.total.toFixed(2), height: max > 0 ? Math.round(m.total / max * 100) : 0 }));
}

/** 本月收入来源占比 */
function sourceBreakdown(rows, now) {
  const today = now || new Date(); const map = {};
  (Array.isArray(rows) ? rows : []).forEach((r) => {
    if (!isIncome(r) || !sameMonth(toDate(r.createTime), today)) return;
    const s = sourceOf(r); map[s.key] = map[s.key] || { key: s.key, label: s.label, icon: s.icon, total: 0 };
    map[s.key].total += amount(r.changeBalance);
  });
  const list = Object.keys(map).map((k) => map[k]).sort((a, b) => b.total - a.total);
  const sum = list.reduce((s, x) => s + x.total, 0);
  return list.map((x) => Object.assign(x, { totalText: x.total.toFixed(2), percent: sum > 0 ? Math.round(x.total / sum * 100) : 0 }));
}

/** 我的资产三行:可提现 / 待结算(活动未结束 + 客诉期) / 客诉处理中(有才出) */
function assetRows(stages) {
  if (!stages) return [];
  const pending = Number(stages.pending || 0) + (stages.complaintPeriod || []).reduce((s, x) => s + Number(x.amount || 0), 0);
  const rows = [
    { key: 'withdrawable', label: '可提现', icon: 'finance', amount: Number(stages.withdrawable || 0).toFixed(2) },
    { key: 'pending', label: '待结算', icon: 'clock', amount: pending.toFixed(2) },
  ];
  if (Number(stages.disputed) > 0) rows.push({ key: 'disputed', label: '客诉处理中', icon: 'warning', amount: Number(stages.disputed).toFixed(2) });
  return rows;
}

module.exports = { monthIncome, monthlySeries, sourceBreakdown, assetRows, sourceOf, clubPayMonthItems, withClubPay };
