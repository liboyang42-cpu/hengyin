// BL-103 合作方近 90 天履约信用摘要(只读)。
// POST /api/coop/credit body {memberId} → CoopInviteServiceImpl.creditSummary:
//   days / fulfil / timeout / cancelOutside / violateInside / violationCount / fulfillmentRate。
// 样本为 0 时后端 rate 兜底 100,那不是真履约率 ⇒ 不展示。不做评分/排序。

// 对方的 memberId:收件箱看发起方(fromId 恒为 memberId);发件箱只有受邀方是商家时
// toId 才是 memberId(club 时是 clubId,不能拿去查信用)。与 reviewCoop 的被评对象同口径。
function partnerMemberId(inv, box) {
  if (!inv) return 0;
  const raw = box === 'received' ? inv.fromId : (inv.toType === 'merchant' ? inv.toId : null);
  const id = Number(raw);
  return Number.isSafeInteger(id) && id > 0 ? id : 0;
}

function nonNegInt(value) {
  const n = Number(value);
  return Number.isSafeInteger(n) && n >= 0 ? n : null;
}

function shapeCredit(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
  const fulfil = nonNegInt(data.fulfil);
  const violations = nonNegInt(data.violationCount);
  const rate = nonNegInt(data.fulfillmentRate);
  const days = nonNegInt(data.days) || 90;
  if (fulfil === null || violations === null || rate === null || rate > 100) return null;
  if (fulfil + violations === 0) return null;
  return {
    label: `对方履约(近${days}天)`,
    text: `履约率 ${rate}% · 违约 ${violations} 次`,
  };
}

module.exports = { partnerMemberId, shapeCredit };
