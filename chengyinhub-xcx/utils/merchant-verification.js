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

module.exports = { resolveVerificationResult, completionReceiptMessage };
