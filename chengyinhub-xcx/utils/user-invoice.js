'use strict';

const STATUS = { SUBMITTED: '待开具', ISSUED: '已开具', REJECTED: '已驳回', CANCELLED: '已撤回', CLAIMED: '已认领', PROCESSING: '开票处理中', ISSUE_UNKNOWN: '开票结果待确认', PAUSED: '退款核对中' };
const RECONCILIATION = { REQUIRED: '退款情况正在人工核对，原申请和订单占用保留', NO_ISSUE_CONFIRMED: '已确认未开具，申请已关闭；可申请订单以最新查询为准', TAX_REVIEW_REQUIRED: '已转税务人工核对，请勿重复申请或开具' };
const requestId = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value);
function exactId(value) {
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value)) return '';
    value = String(value);
  }
  return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value)
    && (value.length < 19 || value <= '9223372036854775807') ? value : '';
}
function masked(value) {
  if (value == null || value === '') return '';
  if (typeof value !== 'string' || !/^(?:[0-9A-Z]{2}\*{6}[0-9A-Z]{4}|\*{6})$/.test(value)) throw Error('税号回读格式异常');
  return value;
}
function title(value) {
  if (!value || !exactId(value.id) || !['PERSONAL', 'COMPANY'].includes(value.titleType)
      || typeof value.titleName !== 'string' || !value.titleName.trim()) throw Error('抬头数据无法确认');
  return { id: exactId(value.id), titleType: value.titleType, titleName: value.titleName,
    taxNoMasked: masked(value.taxNoMasked), isDefault: value.isDefault === true };
}
function order(value) {
  if (!value || !exactId(value.orderId) || !/^(?:0|[1-9]\d*)(?:\.\d{1,2})?$/.test(String(value.amount))) throw Error('订单数据无法确认');
  return { orderId: exactId(value.orderId), amount: String(value.amount), orderTitle: String(value.orderTitle || '报名订单'),
    issuerId: value.issuerId == null ? '' : exactId(value.issuerId), issuerName: String(value.issuerName || '待配置开票主体'),
    ticketName: String(value.ticketName || ''), paymentTime: String(value.paymentTime || ''), selected: false };
}
function application(value, summary = false) {
  if (!value || !exactId(value.id) || !Object.prototype.hasOwnProperty.call(STATUS, value.status) || !requestId(value.requestId)
      || typeof value.applicationNo !== 'string' || !value.applicationNo
      || !/^(?:0|[1-9]\d*)(?:\.\d{1,2})?$/.test(String(value.totalAmount))
      || !Array.isArray(value.orders) || !Number.isInteger(value.orderCount) || value.orderCount < 1
      || value.orderCount > 50 || value.orderCount !== value.orders.length
        && !(summary === true && value.orders.length === 0)) throw Error('申请结果无法确认');
  const reconciliationStatus = value.reconciliationStatus == null ? '' : value.reconciliationStatus;
  if (reconciliationStatus !== '' && !Object.prototype.hasOwnProperty.call(RECONCILIATION, reconciliationStatus)) throw Error('核对结果无法确认');
  const orders = value.orders.map(order);
  if (new Set(orders.map(row => row.orderId)).size !== orders.length) throw Error('申请订单无法确认');
  return { id: exactId(value.id), requestId: value.requestId, applicationNo: value.applicationNo,
    reconciliationStatus, reconciliationText: RECONCILIATION[reconciliationStatus] || '',
    status: value.status, statusText: STATUS[value.status], titleName: String(value.titleName || ''),
    titleType: ['PERSONAL', 'COMPANY'].includes(value.titleType) ? value.titleType : '', issuerName: String(value.issuerName || ''),
    taxNoMasked: masked(value.taxNoMasked), totalAmount: String(value.totalAmount), orderCount: value.orderCount,
    invoiceNo: String(value.invoiceNo || ''), rejectReason: String(value.rejectReason || ''),
    createTime: String(value.createTime || ''), issuedAt: String(value.issuedAt || ''), orders };
}
function titlePayload(draft) {
  const titleName = String(draft.titleName || '').trim(), titleType = draft.titleType;
  if (!['PERSONAL', 'COMPANY'].includes(titleType) || !titleName || titleName.length > 100) throw Error('请填写有效的抬头名称（最多100字）');
  const taxNo = titleType === 'COMPANY' ? String(draft.taxNo || '').trim().toUpperCase() : '';
  if (titleType === 'COMPANY' && !/^[0-9A-Z]{15,20}$/.test(taxNo)) throw Error('请重新填写完整税号，不能使用掩码');
  if (draft.id && !exactId(draft.id)) throw Error('抬头编号无法确认，请刷新');
  return Object.assign({ titleType, titleName, isDefault: draft.isDefault === true },
    draft.id ? { id: exactId(draft.id) } : {}, taxNo ? { taxNo } : {});
}
function titleSaveReceipt(value, expectedRequestId, expectedTitleId) {
  if (!value || value.status !== 'SAVED' || !requestId(value.requestId) || !exactId(value.titleId)
      || expectedRequestId && value.requestId !== expectedRequestId
      || expectedTitleId && exactId(value.titleId) !== expectedTitleId) throw Error('抬头保存结果与原请求不匹配');
  return { requestId: value.requestId, titleId: exactId(value.titleId), status: 'SAVED' };
}
function classify(res, status, decode, transportFailure = false) {
  if (res && res.errorCode === 'INVOICE_FEATURE_OFF') return { status: 'failed', response: res };
  if (transportFailure || !res || res.reason === 'sessionChanged' || status && Number(status) !== 200) return { status: 'unknown' };
  if (res.code === 200 || res.code === '200') {
    try { return { status: 'success', value: decode(res.data) }; } catch (error) { return { status: 'unknown' }; }
  }
  // 业务异常由事务回滚后返回；并发处理中不等于失败，不能释放原请求。
  if (['ARGUMENT_INVALID', 'TITLE_TYPE_INVALID', 'TITLE_NAME_INVALID', 'TAX_NO_INVALID', 'TITLE_NOT_FOUND',
    'TITLE_LIMIT', 'CRYPTO_UNAVAILABLE', 'ORDERS_REQUIRED', 'ORDERS_TOO_MANY', 'ORDER_NOT_ELIGIBLE',
    'ORDER_ALREADY_INVOICED', 'NOT_FOUND', 'STATE_CONFLICT'].some(code => res.errorCode === 'INVOICE_' + code)) {
    return { status: 'failed', response: res };
  }
  return { status: 'unknown' };
}
function send(app, endpoint, options) {
  switch (endpoint) {
    case 'titles': return app.sendRequest(Object.assign({}, options, { url: '/api/invoice/titles' }));
    case 'titles/save': return app.sendRequest(Object.assign({}, options, { url: '/api/invoice/titles/save' }));
    case 'titles/save-request': return app.sendRequest(Object.assign({}, options, { url: '/api/invoice/titles/save-request' }));
    case 'titles/request': return app.sendRequest(Object.assign({}, options, { url: '/api/invoice/titles/request' }));
    case 'titles/delete': return app.sendRequest(Object.assign({}, options, { url: '/api/invoice/titles/delete' }));
    case 'orders/eligible': return app.sendRequest(Object.assign({}, options, { url: '/api/invoice/orders/eligible' }));
    case 'apply': return app.sendRequest(Object.assign({}, options, { url: '/api/invoice/apply' }));
    case 'list': return app.sendRequest(Object.assign({}, options, { url: '/api/invoice/list' }));
    case 'detail': return app.sendRequest(Object.assign({}, options, { url: '/api/invoice/detail' }));
    case 'request': return app.sendRequest(Object.assign({}, options, { url: '/api/invoice/request' }));
    case 'cancel': return app.sendRequest(Object.assign({}, options, { url: '/api/invoice/cancel' }));
    default: throw Error('未登记的发票接口');
  }
}
module.exports = { exactId, title, order, application, applicationSummary: value => application(value, true), titlePayload, titleSaveReceipt, classify, send };
