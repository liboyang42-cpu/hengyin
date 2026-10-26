// F4 only prepares candidates. The host owns the online redemption command and its receipt.
const ENDPOINTS = Object.freeze({
  GROUP_CODE: '/api/verify/groupcode/redeem',
  DYNAMIC_TICKET: '/api/registration/scan_dynamic_code',
  COUPON: '/api/coupon/verification',
  LEGACY_TICKET: '/api/registration/scan_qr_code',
});
const TARGETS = Object.freeze({ PRODUCT_SAVE: 'pms_product', PRODUCT_DELIST: 'pms_product',
  SKU_SAVE: 'pms_sku', REVIEW_EVIDENCE: 'verification_review_evidence', ANCHOR_SAVE: 'merchant_redemption_anchor' });
function candidate(row) {
  if (!row || typeof row.accepted !== 'boolean') throw Error('无法确认候选结果');
  if (!row.accepted) return { accepted: false, message: String(row.message || '此码不可在本工作台核销'),
    redirectPath: row.redirectPath === '/pages/merchant/citynode/index' ? row.redirectPath : '' };
  const form = row.form;
  if (!ENDPOINTS[row.kind] || !form || form.submitEndpoint !== ENDPOINTS[row.kind] || form.method !== 'POST'
      || form.offline !== false || !Array.isArray(form.fields)) throw Error('候选提交合同无法确认');
  const fields = [], values = {}, names = new Set();
  for (const field of form.fields) {
    if (!field || !['code', 'type', 'scope'].includes(field.name) || names.has(field.name)) throw Error('候选字段无法确认');
    names.add(field.name);
    if (field.widget === 'hidden') {
      if (typeof field.value !== 'string' || !field.value) throw Error('候选字段缺失');
      values[field.name] = field.value;
    } else if (field.widget === 'select' && field.name === 'type' && row.kind === 'LEGACY_TICKET') {
      // Never auto-select the claimed type for an untyped legacy ticket.
      if (!Array.isArray(field.options) || field.options.length !== 2
          || !['activity', 'topic'].every(value => field.options.some(option => option.value === value))) throw Error('票类型选项无法确认');
      fields.push({ name: 'type', label: '票的类型', options: [{ value: 'activity', label: '活动票' }, { value: 'topic', label: '主题票' }] });
    } else throw Error('不支持的候选字段');
  }
  const expected = row.kind === 'COUPON' ? ['code', 'scope'] : row.kind === 'LEGACY_TICKET' ? ['code', 'type'] : ['code'];
  if (names.size !== expected.length || expected.some(name => !names.has(name))) throw Error('候选字段无法确认');
  if (!values.code || values.code.length > 512 || (row.kind === 'COUPON' && values.scope !== 'MERCHANT')
      || (values.type && !['activity', 'topic'].includes(values.type))
      || (row.kind === 'LEGACY_TICKET' && !values.type && !fields.length)) throw Error('候选内容不完整');
  return { accepted: true, kind: row.kind, endpoint: form.submitEndpoint, fields, values };
}
function targetReceipt(row, requestId) {
  return row && row.state === 'APPLIED' && row.requestId === requestId && Object.prototype.hasOwnProperty.call(TARGETS, row.action) && TARGETS[row.action] === row.targetType;
}
module.exports = { candidate, targetReceipt };

function id(value) {
  const text = typeof value === 'string' ? value : Number.isSafeInteger(value) && value > 0 ? String(value) : '';
  return /^[1-9]\d{0,18}$/.test(text) && (text.length < 19 || text <= '9223372036854775807') ? text : '';
}
function list(value, key) {
  if (!Array.isArray(value)) throw Error('列表格式无效');
  const seen = new Set();
  return value.map(row => {
    if (!row || typeof row !== 'object' || !id(row[key]) || seen.has(id(row[key]))) throw Error('记录标识无法确认');
    seen.add(id(row[key]));
    const result = Object.assign({}, row);
    for (const field of Object.keys(result)) if ((field === 'id' || field.endsWith('Id')) && result[field] != null) {
      if (!id(result[field])) throw Error('记录标识无法确认'); result[field] = id(result[field]);
    }
    return result;
  });
}
function count(value) { return Number.isSafeInteger(value) && value >= 0; }
function money(value) { return typeof value === 'string' && /^-?\d+(\.\d+)?$/.test(value)
  || typeof value === 'number' && Number.isFinite(value); }
function reconciliation(row) {
  const bases = ['CHAPTER_OFFER', 'COOP_ORDER', 'REGISTRATION_SPLIT', 'NONE'];
  if (!row || row.readOnly !== true || !Array.isArray(row.bases) || !Array.isArray(row.outsideFourBases)
      || row.bases.length !== 4 || !bases.every(route => row.bases.some(item => item.route === route))) throw Error('四口径结果不完整');
  const seen = new Set();
  for (const item of row.bases.concat(row.outsideFourBases)) {
    if (!item || !/^[A-Z_]{2,32}$/.test(item.route) || seen.has(item.route) || !count(item.claimCount)
        || !item.byStatus || typeof item.byStatus !== 'object' || Array.isArray(item.byStatus)
        || Object.values(item.byStatus).some(value => !count(value))) throw Error('对账状态无法确认');
    seen.add(item.route);
    if (Object.values(item.byStatus).reduce((sum, value) => sum + value, 0) !== item.claimCount) throw Error('主张计数不一致');
  }
  const chapter = row.bases.find(item => item.route === 'CHAPTER_OFFER');
  if (!money(chapter.payableNetWithClaim) || !money(chapter.payableNetWithoutClaim) || !count(chapter.payableRowsWithoutClaim)) throw Error('应付口径缺失');
  return row;
}
module.exports.id = id;
module.exports.list = list;
module.exports.reconciliation = reconciliation;
module.exports.arrivals = row => {
  if (!row || typeof row.date !== 'string') throw Error('日期缺失');
  const redeemed = list(row.redeemed, 'redemptionId'), openByChapter = list(row.openByChapter, 'offerId');
  if (!count(row.redeemedCount) || row.redeemedCount !== redeemed.length) throw Error('核销记录数无法确认');
  return Object.assign({}, row, { redeemed, openByChapter });
};
