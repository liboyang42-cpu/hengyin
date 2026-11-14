'use strict';
// E-C1:通关页「本局已展示」标记。键 = 账号 + 主题/场次 + routeSessionId;
// 同一局从别页(结算、足迹卡、分享)返回只刷新数据,不再重放通关动画 / 勋章页 / finale;新开一局(换 routeSessionId)才会再放。

function finishShownKey(owner, topicId, activityId, routeSessionId) {
  return [owner, topicId || 0, activityId || 0, routeSessionId || ''].map(String).join('|');
}

/** 重进时是否要自动打开通关页:这一局已经展示过就不再开。 */
function shouldAutoOpenFinish(shownKey, key) {
  return !key || shownKey !== key;
}

module.exports = { finishShownKey, shouldAutoOpenFinish };
