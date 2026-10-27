// 开放商机的唯一口径(2026-10-27 业主 M-23):营销页「N 个开放商机」与合作中心广场列表
// 必须是同一个查询、同一道过滤 —— 原来计数直接读 recruiting.count(后端口径),列表却在端上
// 再滤掉缺 id/名称与已过期的条目,于是会出现「1 个开放商机」点进去一条都没有。
const { toTimestamp } = require('../../../utils/datetime.js');

function hasValue(value) {
  return (typeof value === 'string' || typeof value === 'number') && !!String(value).trim();
}

// 2026-10-22 owner:已过期的不展示 —— 招商截止或活动已结束都不进广场(后端 recruiting() 同口径先滤一遍)。
function isRouteExpired(item, now) {
  const deadline = toTimestamp(item.recruitDeadline || item.merchantSignUpEndDate);
  if (deadline && deadline <= now) return true;
  const end = toTimestamp(item.endDate);
  return !!(end && end <= now);
}

/** 广场真正会画出来的那几条(marketing-home.recruiting.items 过同一道过滤)。 */
function visibleRecruitItems(items, now) {
  const at = now === undefined ? Date.now() : now;
  return (items || []).filter((item) => item && hasValue(item.id) && hasValue(item.name) && !isRouteExpired(item, at));
}

/**
 * 营销页标签上的开放商机数。列表能画出几条就写几条;后端说明还有没列出的(hasMoreOpen),
 * 且列表至少有一条可点时,才用后端的总数 —— 保证「N 个开放商机」点进去永远不是空列表。
 */
function openOpportunityCount(recruiting, now) {
  const visible = visibleRecruitItems(recruiting && recruiting.items, now).length;
  if (!visible) return 0;
  const total = recruiting && recruiting.hasMoreOpen === true ? Number(recruiting.openCount) : NaN;
  return Number.isFinite(total) && total > visible ? total : visible;
}

module.exports = { hasValue, isRouteExpired, visibleRecruitItems, openOpportunityCount };
