// BL-85 商家开通前置检查(只读):GET /api/merchant/ops/prerequisites
// 字段以 MerchantPrerequisiteServiceImpl.calculate 返回的 PrerequisiteChecklist 为准:
//   { merchantId, canContinue, checkedAt, items: [{ code, title, description, status: PASS|BLOCKED,
//     severity, actionTarget: { type, path, available, unavailableReason } }] }
// 只列 status !== 'PASS' 的项;全部完成(canContinue)或无数据时不展示。
// 去处只放行后端当前会下发的两个小程序页,不跳任意路径。
const ALLOWED_PATHS = ['/pages/merchant/apply/index', '/pages/merchant/decor/index'];

function safePath(target) {
  if (!target || typeof target !== 'object' || target.available !== true) return '';
  const path = String(target.path || '');
  return ALLOWED_PATHS.indexOf(path) >= 0 ? path : '';
}

function pendingPrerequisites(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return [];
  if (data.canContinue === true || !Array.isArray(data.items)) return [];
  return data.items
    .filter(item => item && typeof item === 'object' && item.status !== 'PASS' && item.title)
    .map((item, index) => ({
      key: String(item.code || index),
      title: String(item.title),
      description: String(item.description || ''),
      path: safePath(item.actionTarget),
    }));
}

module.exports = { pendingPrerequisites, ALLOWED_PATHS };
