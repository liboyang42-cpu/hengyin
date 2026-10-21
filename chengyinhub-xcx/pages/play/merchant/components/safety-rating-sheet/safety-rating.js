// BL-89 到访安全感受评价的纯函数。档位以 CommunityTrustServiceImpl.getSafetyTaxonomy 的 levels 为准
// (UNSAFE/NEUTRAL/SAFE),中文名只给这三个已知值;未知档位不展示。
// 请求体按 MerchantSafetyRatingRequest:{ merchantId, overallLevel, reason, idempotencyKey }。
// 回执按 MerchantSafetyRatingReceiptVO:{ ratingId, merchantId, overallLevel, ratingPeriod, acceptedAt }。
const LEVEL_LABELS = { SAFE: '安心', NEUTRAL: '一般', UNSAFE: '不安心' };

function levelTabs(taxonomy) {
  const levels = taxonomy && Array.isArray(taxonomy.levels) ? taxonomy.levels : [];
  return levels
    .map(level => String(level || '').toUpperCase())
    .filter((level, index, list) => LEVEL_LABELS[level] && list.indexOf(level) === index)
    .map(level => ({ key: level, label: LEVEL_LABELS[level] }));
}

function buildRatingBody(merchantId, level, reason, idempotencyKey) {
  return { merchantId, overallLevel: level, reason: String(reason || '').trim(), idempotencyKey };
}

function receiptText(receipt) {
  const row = receipt && typeof receipt === 'object' ? receipt : {};
  const parts = [];
  if (LEVEL_LABELS[row.overallLevel]) parts.push('你的感受：' + LEVEL_LABELS[row.overallLevel]);
  if (row.ratingPeriod) parts.push('评价周期 ' + row.ratingPeriod);
  if (row.ratingId) parts.push('受理编号 ' + row.ratingId);
  return parts.join(' · ');
}

module.exports = { LEVEL_LABELS, levelTabs, buildRatingBody, receiptText };
