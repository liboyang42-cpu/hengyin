'use strict';
// BL-66:商家安全感受摘要(只读)。接口 GET /api/community-trust/safety/merchants/{merchantId}
// 返回 MerchantSafetySummaryVO(CommunityTrustServiceImpl.getSafetySummary)。
// 只有 sampleStatus=SUFFICIENT 且 level 是已知值时才展示;样本不足 / 策略未启用 / 失败一律不展示。
var LEVEL_TEXT = {
  SAFE: '到访用户感到安全',
  NEUTRAL: '到访用户感受一般',
  UNSAFE: '部分到访用户感到不安全'
};

function positiveInt(value) {
  var n = Number(value);
  return Number.isInteger(n) && n > 0 ? n : 0;
}

function shapeMerchantSafety(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
  if (data.sampleStatus !== 'SUFFICIENT') return null;
  var label = LEVEL_TEXT[data.level];
  if (!label) return null;
  var count = positiveInt(data.sampleCount);
  if (!count) return null;
  var days = positiveInt(data.windowDays);
  var scope = (days ? '近 ' + days + ' 天 ' : '') + count + ' 人反馈';
  return { level: data.level, text: label, note: scope + '，仅代表用户感受，不构成安全保证' };
}

module.exports = { shapeMerchantSafety: shapeMerchantSafety, LEVEL_TEXT: LEVEL_TEXT };
