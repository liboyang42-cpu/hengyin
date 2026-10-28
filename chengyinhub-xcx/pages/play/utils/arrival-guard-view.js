'use strict';
const { id } = require('../../../utils/orient-score-view.js');
function project(response, nodeId, arrivalRequestId) {
  const r = response || {};
  if (r.httpFail || r.netFail || !/^ARRIVE_[A-Z_]+$/.test(r.errorCode || r.reason || '')) return null;
  if (r.nodeId != null && id(r.nodeId) !== id(nodeId)) return null;
  const reason = r.errorCode || r.reason;
  const logId = id(r.guardLogId);
  return { nodeId: id(nodeId), reason, guardLogId: logId,
    arrivalRequestId: /^[A-Za-z0-9:_-]{1,80}$/.test(arrivalRequestId || '') ? arrivalRequestId : '',
    routeSessionId: id(r.routeSessionId), appealable: r.appealable === true,
    appealBizType: r.appealBizType === 'play_arrive_guard' ? r.appealBizType : '',
    runtimeInstance: typeof r.runtimeInstance === 'string' ? r.runtimeInstance : '',
    authorityEpoch: Number.isSafeInteger(r.authorityEpoch) ? r.authorityEpoch : null,
    message: typeof r.msg === 'string' && r.msg.length <= 160 ? r.msg : '本次到达未通过平台核验',
    next: reason === 'ARRIVE_ACCURACY_MISSING' || reason === 'ARRIVE_ACCURACY_INVALID'
      ? '请重新获取定位，或使用现场扫码。此结果不是作弊判定。'
      : r.appealable === true ? '可读取原局申诉资格并提交理由；当前尚未提交申诉。'
        : '请按提示重新现场核验；此结果没有在线申诉入口。' };
}
module.exports = { project };
