const { seatTarget, validSeatReceipt } = require('./registration-seat.js');
const { createVerificationWorkflow } = require('./verification-readback.js');
// 只使用现有端点的回执合同；通用业务500可能发生在核销事务提交之后。
const UNKNOWN_MESSAGE = '核销结果待确认，请勿重复核销；请先查看核销记录';
const record = value => value && typeof value === 'object' && !Array.isArray(value);
const positiveId = value => (typeof value === 'number' || typeof value === 'string')
  && /^[1-9]\d*$/.test(String(value)) && Number.isSafeInteger(Number(value));
function chapterReceipt(data, request) {
  return record(data) && positiveId(data.redemptionId) && positiveId(data.chapterId)
    && (!request.data || request.data.chapterId == null || String(data.chapterId) === String(request.data.chapterId))
    && (data.replayed === undefined || typeof data.replayed === 'boolean');
}
function groupReceipt(data, request) {
  if (!record(data) || !['topicId', 'activityId', 'leaderMemberId', 'coopOrderId'].every(key => positiveId(data[key]))
      || typeof data.alreadyVerified !== 'boolean') return false;
  const parts = String(request.data && request.data.code || '').split('.');
  const type = (parts[2] || '').split('_');
  return parts[0] === 'v1' && type.length === 4 && type[0] === 'group' && type[1] === 'v2'
    && String(data.topicId) === parts[1] && String(data.activityId) === type[2] && String(data.leaderMemberId) === type[3];
}
function validChoice(data) {
  if (!record(data)) return false;
  const list = (rows, key) => Array.isArray(rows) && rows.length > 0 && rows.every(row => record(row) && positiveId(row[key || 'id']));
  return data.needChapterChoice === true && !data.needStationChoice
    && (list(data.chapters) || Array.isArray(data.chapterIds) && data.chapterIds.length > 0 && data.chapterIds.every(positiveId))
    || data.needStationChoice === true && !data.needChapterChoice && list(data.stations, 'registrationMerchantId');
}
function classifyVerificationOutcome(response, channel, httpStatus, request) {
  const unknown = () => ({ status: 'unknown', response: { code: 202, msg: UNKNOWN_MESSAGE } });
  const status = Number(httpStatus);
  // request-client 只在历史 code=2 鉴权拒绝且重登失败时以 fail(body, 2) 返回。
  if (channel === 'fail' && httpStatus === 2 && record(response) && (response.code === 2 || response.code === '2'))
    return { status: 'failed', response: { code: 401, msg: '登录状态已失效，未执行核销' } };
  // request-client 保留的 HTTP 拒绝；408/5xx/无 HTTP 回执绝不走此分支。
  if ([400, 401, 403, 404, 405, 413, 415, 426, 429].includes(status))
    return { status: 'failed', response: { code: status, msg: '请求被拒绝，未执行核销' } };
  if (channel !== 'success' || httpStatus && status !== 200 || !record(response)) return unknown();
  request = request || {};
  const url = request.url, data = response.data;
  const originalSeat = seatTarget(request);
  const ticket = ['/api/registration/scan_qr_code', '/api/registration/scan_dynamic_code',
    '/api/registration/scan_group_member_ticket', '/api/registration/scan_qr_code_station'].includes(url);
  const chapter = url === '/api/registration/scan_qr_code_chapter';
  const automaticChapter = url === '/api/registration/scan_qr_code' || url === '/api/registration/scan_dynamic_code';
  const coupon = url === '/api/coupon/verification';
  const group = url === '/api/verify/groupcode/redeem';
  if (!(ticket || chapter || coupon || group)) return unknown();
  if (response.code === 200 || response.code === '200') {
    if (originalSeat) return validSeatReceipt(data, originalSeat)
      ? { status: 'success', response } : unknown();
    const simple = response.msg === '核销成功' && (data == null || record(data) && Object.keys(data).length === 0);
    if (group ? groupReceipt(data, request) && response.msg === (data.alreadyVerified ? '已记录过' : '已记录接待该团') : chapter ? chapterReceipt(data, request)
        : coupon ? simple || record(data) && data.alreadyUsed === true && response.msg === '该优惠券已核销'
        : simple || automaticChapter && chapterReceipt(data, request)) return { status: 'success', response };
    return unknown();
  }
  if (response.code === 500 || response.code === '500') {
    // Controller 在选择分支尚未调用核销事务；重复标记是服务端现有的幂等事实。
    if (ticket && (validChoice(data) || record(data) && data.alreadyVerified === true && response.msg === '该订单已核销'))
      return { status: 'failed', response };
  }
  // 包括带非空 msg 的业务500：verifyAndSettle 已提交后 award 抛异常也会包装成此形状。
  return unknown();
}

function isSuccess(response) {
  return response && (response.code === 200 || response.code === '200');
}

function isDuplicate(response) {
  const data = (response && response.data) || {};
  return data.alreadyUsed === true || data.alreadyVerified === true;
}

function isReplayed(response) {
  return isSuccess(response) && response.data && response.data.replayed === true;
}

function completionReceiptMessage(response, fallback) {
  if (isReplayed(response)) return response.data.seatId
    ? '该席位已核销，请勿重复提供权益' : '该章节已核销，请勿重复提供权益';
  const message = (response && response.msg) || fallback;
  const earnedXp = Number(response && response.data && response.data.completionEarnedXp) || 0;
  return earnedXp > 0 ? (message + ' · 玩家通关 +' + earnedXp + ' 探索值已到账') : message;
}

function resolveVerificationResult(response, action) {
  const label = action || '核销';
  // 展示层不作提交结论；写闸已按端点将未知结果正规化为202。
  if (!record(response) || ![200, '200', 400, 401, 403, 404, 405, 413, 415, 426, 429, 500, '500'].includes(response.code)
      || response.code == 500 && !response.msg && !isDuplicate(response)) {
    return { state: 'unknown', title: label + '结果待确认', message: UNKNOWN_MESSAGE, show: true };
  }
  const message = (response && response.msg) || (isSuccess(response) ? label + '成功' : label + '失败，请重试');
  if (isReplayed(response)) {
    return { state: 'duplicate', title: '已核销', message: completionReceiptMessage(response, message), show: true };
  }
  if (isDuplicate(response)) {
    return {
      state: 'duplicate',
      title: '重复' + label,
      message: message,
      show: true
    };
  }
  if (isSuccess(response)) {
    return {
      state: 'success',
      title: label + '成功',
      message: completionReceiptMessage(response, message),
      show: true
    };
  }
  return {
    state: 'failure',
    title: label + '失败',
    message: message,
    show: true
  };
}

module.exports = { resolveVerificationResult, completionReceiptMessage, classifyVerificationOutcome, createVerificationWorkflow };
