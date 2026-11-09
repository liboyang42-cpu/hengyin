const { positiveId } = require('../reviews/view-model.js');

function normalizeMerchantFinanceRoute(options) {
  const opt = options || {};
  if (Object.prototype.hasOwnProperty.call(opt, 'ownerType')
      || Object.prototype.hasOwnProperty.call(opt, 'ownerId')) {
    if (opt.view !== 'messages'
        || !['string', 'number'].includes(typeof opt.ownerType)
        || !['string', 'number'].includes(typeof opt.ownerId)) return null;
    const ownerType = positiveId(opt.ownerType);
    const ownerId = positiveId(opt.ownerId);
    if ((ownerType !== 1 && ownerType !== 2) || !ownerId) return null;
    return { view: 'messages', source: '', ownerType, ownerId };
  }
  if (opt.view !== undefined && opt.view !== null && opt.view !== '') {
    const view = String(opt.view);
    // 2026-11-01 业主:主题工作台「台账」带 topicId,只看这个主题的核销
    if (view === 'redemptions' && positiveId(opt.topicId)) return { view, source: '', topicId: positiveId(opt.topicId) };
    if (view === 'redemptions' || view === 'settlement' || view === 'messages') {
      return { view, source: view === 'settlement' && opt.source ? String(opt.source) : '' };
    }
    return null;
  }
  const legacy = opt.tab === undefined || opt.tab === null || opt.tab === '' ? '0' : String(opt.tab);
  if (legacy === '0') return { view: 'redemptions', source: '' };
  if (legacy === '1') return { view: 'messages', source: '' };
  if (legacy === '2') return { view: 'settlement', source: '' };
  if (legacy === '3') return { view: 'settlement', source: 'coop' };
  return null;
}

function isFinancePagePayload(response) {
  const data = response && response.data;
  return !!(response && (response.code === 200 || response.code === '200')
    && data && isRecordList(data.rows) && Number.isFinite(Number(data.total)));
}

function isFinanceObjectPayload(response) {
  return !!(response && (response.code === 200 || response.code === '200')
    && response.data && typeof response.data === 'object' && !Array.isArray(response.data));
}

function isMerchantEventsPayload(response) {
  return !!(response && (response.code === 200 || response.code === '200')
    && isRecordList(response.data));
}

// 两位小数数字串(保留负号);显示用 yuan()(utils/money-format.js,负数「−¥」)
const { fixed2: money, yuan } = require('../../../utils/money-format.js');

module.exports = { normalizeMerchantFinanceRoute, isFinancePagePayload, isFinanceObjectPayload, isMerchantEventsPayload, money, yuan };
const { isRecordList } = require('../../../utils/response-shape.js');
