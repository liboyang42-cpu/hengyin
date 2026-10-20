'use strict';

/**
 * 「如何获取积分」弹窗的规则整理(纯函数,单测直接 require)。
 *
 * 真源:POST /api/points/result_list → CmsPointsSetting[](后台「积分规则」type=1 的全部行)
 *   { id, title, description, value(BigDecimal,每次得/扣分), eventType, status(1 启用), pointsNum(我已触发次数) }
 * 只做展示,不改规则(积分服务归 Codex B03)。
 *
 * eventType=14 是「解锁提示」的**花分**规则:后端 ApiPlayProgressController 以 eventType=14 记消费流水,
 * 所以它不是赚分任务。原来客户端直接把它过滤掉、用户看不到花分口径;这里改成单独列为「积分消耗」,
 * 数值同样取自后台配置,不在客户端写死任何分值。
 *
 * 「积分抵扣现金」的比例目前是服务端常量(CmsRegistrationServiceImpl.POINT_DEDUCT_RATE),
 * 没有任何只读接口下发 —— 本弹窗不展示汇率,也不编造接口;见 PR 未完成项。
 */

const SPEND_EVENT_TYPE = 14;

function pointValue(raw) {
  const n = Number(raw);
  if (raw === null || raw === undefined || raw === '' || !Number.isFinite(n) || n < 0) return null;
  return Math.round(n * 100) / 100;
}

/**
 * @returns {{earn: Array, spend: Array}|null} 回包不是数组(或含非对象行)时返回 null,
 *   调用方落失败态 —— 不把畸形回包伪装成「暂无积分任务」。
 */
function splitPointsRules(list) {
  if (!Array.isArray(list)) return null;
  if (list.some((row) => !row || typeof row !== 'object' || Array.isArray(row))) return null;
  const earn = [];
  const spend = [];
  list.forEach((row) => {
    if (Number(row.status) !== 1) return; // 只列启用中的规则
    const value = pointValue(row.value);
    const item = {
      id: row.id,
      eventType: row.eventType,
      title: typeof row.title === 'string' && row.title.trim() ? row.title.trim() : '积分规则',
      description: typeof row.description === 'string' ? row.description.trim() : '',
      points: value,
      // null = 后台没配数值,页面显示「以平台规则为准」而不是 +0
      pointsText: value === null ? '以平台规则为准' : '',
      doneCount: Number(row.pointsNum) > 0 ? Number(row.pointsNum) : 0,
    };
    if (Number(row.eventType) === SPEND_EVENT_TYPE) spend.push(item);
    else earn.push(item);
  });
  return { earn, spend };
}

module.exports = { SPEND_EVENT_TYPE, splitPointsRules, pointValue };
