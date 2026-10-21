// success 回调是 HTTP 200 的业务包；fail/abnormal 的第二参数来自 request-client。
// code=202 也用于本地 deadline 提示，不代表服务端提供异步核销协议。
const UNKNOWN_MESSAGE = '核销结果待确认，请勿重复核销；请先查看核销记录';
function classifyVerificationOutcome(response, channel, httpStatus) {
  const status = Number(httpStatus);
  const rejectedHttp = [400, 401, 403, 404, 405, 409, 410, 413, 415, 422, 426, 429];
  if (rejectedHttp.includes(status)) {
    return { status: 'failed', response: { code: status,
      msg: response && typeof response.msg === 'string' && response.msg.trim()
        ? response.msg : '请求被拒绝，未执行核销' } };
  }
  // 传输失败、网关/服务端异常均不能证明没有提交；不把 HTTP 5xx 包装成业务拒绝。
  if (channel === 'success' && (!httpStatus || status === 200)
      && response && typeof response === 'object' && !Array.isArray(response)
      && (typeof response.code === 'number' || typeof response.code === 'string')) {
    const code = Number(response.code);
    if (!Number.isInteger(code) || String(code) !== String(response.code))
      return { status: 'unknown', response: { code: 202, msg: UNKNOWN_MESSAGE } };
    if (code === 200) return { status: 'success', response: response };
    // 现有扫码合同：选章/选据点尚未执行核销，继续使用原候选面板。
    const data = response.data;
    const choice = data && ((data.needChapterChoice === true
      && ((Array.isArray(data.chapters) && data.chapters.length) || (Array.isArray(data.chapterIds) && data.chapterIds.length)))
      || (data.needStationChoice === true && Array.isArray(data.stations) && data.stations.length));
    if (code === 500 && choice) return { status: 'failed', response: response };
    // 完整的业务拒绝仍可重试；业务 code=500 与 HTTP 500 有不同的执行语义。
    if (Number.isInteger(code) && code >= 400 && code <= 599
        && typeof response.msg === 'string' && response.msg.trim())
      return { status: 'failed', response: response };
  }
  return { status: 'unknown', response: { code: 202, msg: UNKNOWN_MESSAGE } };
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
  if (isReplayed(response)) return '该章节已核销，请勿重复提供权益';
  const message = (response && response.msg) || fallback;
  const earnedXp = Number(response && response.data && response.data.completionEarnedXp) || 0;
  return earnedXp > 0 ? (message + ' · 玩家通关 +' + earnedXp + ' 探索值已到账') : message;
}

function resolveVerificationResult(response, action) {
  const label = action || '核销';
  if (classifyVerificationOutcome(response, 'success').status === 'unknown') {
    return { state: 'unknown', title: label + '结果待确认', message: UNKNOWN_MESSAGE, show: true };
  }
  const message = (response && response.msg) || (label + '失败，请重试');
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

module.exports = { resolveVerificationResult, completionReceiptMessage, classifyVerificationOutcome };
