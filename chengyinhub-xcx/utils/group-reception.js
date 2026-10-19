'use strict';
// 商家「已接待团」列表的纯函数口径(X034)。
//
// 后端 /api/verify/groupcode/receptions 只回本商家主体的接待记录;settled=1 表示所在主题的
// 合作结算已经开始(主题已被结算领取,或本合作单已挂结算单),此时接待数已进入分账基数,
// 页面不给撤销入口,后端也会拒。撤销时限是产品待定项,当前默认「仅结算开始前」。

const REVOKE_REASON_MAX = 200;

function text(value) {
  return value == null ? '' : String(value).trim();
}

function normalizeReception(row) {
  if (!row) return null;
  const id = Number(row.id);
  if (!Number.isSafeInteger(id) || id <= 0) return null;
  const settled = Number(row.settled) === 1 || row.settled === true;
  const activityName = text(row.activityName);
  const activityStart = text(row.activityStartText);
  return {
    id,
    topicName: text(row.topicName) || ('主题 #' + text(row.topicId)),
    activityText: [activityName, activityStart].filter(Boolean).join(' · ') || '场次信息缺失',
    leaderName: text(row.leaderName) || '领队',
    verifyTimeText: text(row.verifyTimeText),
    settled,
    canRevoke: !settled,
    statusText: settled ? '已进入结算，不可撤销' : '结算前可撤销',
  };
}

/** 非数组 = 接口形状不对,返回 null 交给页面走错误态,不当成「没有接待记录」。 */
function normalizeReceptions(rows) {
  if (!Array.isArray(rows)) return null;
  return rows.map(normalizeReception).filter(Boolean);
}

/** 撤销原因校验:必填、不超过 200 字。返回空串表示通过,与 modal.show 的 validate 约定一致。 */
function checkRevokeReason(reason) {
  const value = text(reason);
  if (!value) return '请填写撤销原因';
  if (value.length > REVOKE_REASON_MAX) return '撤销原因不超过 ' + REVOKE_REASON_MAX + ' 字';
  return '';
}

module.exports = {
  REVOKE_REASON_MAX,
  normalizeReception,
  normalizeReceptions,
  checkRevokeReason,
};
