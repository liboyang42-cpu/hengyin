// P0-14 生成式 AI 内容显式标识。判据镜像后端 AigcLabel:publishMode === 'ai_simple'(快速配置 AI 起草)。
// 备案号来自 /api/config/features 的 aigcFilingNo(后台 sys_config aigc.algorithm.filing-no);空则不显示。
function isAiGenerated(publishMode) {
  return typeof publishMode === 'string' && publishMode.trim().toLowerCase() === 'ai_simple';
}

function filingNo(app) {
  const features = app && app.globalData && app.globalData.features;
  const value = features && features.aigcFilingNo;
  return typeof value === 'string' ? value.trim().slice(0, 64) : '';
}

module.exports = { isAiGenerated, filingNo };
