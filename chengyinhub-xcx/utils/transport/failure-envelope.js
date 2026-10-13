function fromHttpStatusAbnormal(body) {
  const source = body && typeof body === 'object' ? body : {};
  return {
    code: 'fail',
    httpFail: true,
    upstreamCode: source.code == null ? '' : source.code,
    msg: source.msg || '服务暂时不可用',
  };
}

// 写请求没有回执、408 或 5xx 时，只能回读结果，不能推断未提交。
function isUnknownHttpStatus(statusCode) {
  if (statusCode == null || statusCode === '') return true;
  const code = Number(statusCode);
  return !Number.isFinite(code) || code === 408 || code >= 500;
}

module.exports = { fromHttpStatusAbnormal, isUnknownHttpStatus };
