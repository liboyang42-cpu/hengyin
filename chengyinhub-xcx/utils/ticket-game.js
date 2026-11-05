'use strict';
// 第十二轮 k2(j1 N1):业主第十轮起活动的玩法可选。场次票的活动既没有来源主题、也没有玩法模板 = 没有游戏,
// 票只用来出示入场码;不出「开始游戏」(进去只会是「活动或主题不存在」)。
// 读不到活动信息时按「有游戏」处理,不改变既有行为。
function activityHasGame(activity) {
  if (!activity || typeof activity !== 'object') return true;
  return Number(activity.topicId) > 0 || Number(activity.templateId) > 0;
}

function ticketHasGame(item) {
  const value = item || {};
  if (Number(value.ownerType) !== 2 && value.kind !== 'activity') return true;
  return activityHasGame(value.cmsActivity);
}

module.exports = { activityHasGame, ticketHasGame };
