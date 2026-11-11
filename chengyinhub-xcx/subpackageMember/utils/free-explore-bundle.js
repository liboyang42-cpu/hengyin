'use strict';

// F3 wire IDs are decimal strings. Never recover a rounded JSON number.
function exactId(value) {
  if (typeof value === 'number' && Number.isSafeInteger(value)) value = String(value);
  return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value)
    && (value.length < 19 || value <= '9223372036854775807') ? value : '';
}
const STATES = { AVAILABLE: '可用', RESERVED: '已预约', REDEEMED: '已使用', FORFEITED: '已扣除本次权益',
  HELD: '冻结中', REFUND_PENDING: '退款处理中', REFUNDED: '已退款',
  BOOKED: '待使用', CANCELLED: '已取消', WAIVED: '已豁免取消', LATE_CANCELLED: '晚取消', NO_SHOW: '未到店', FULFILLED: '已使用', MERCHANT_EXITED: '商家已退出' };
function owned(row, owner, registrationId) {
  if (!row || exactId(row.holderMemberId) !== owner || exactId(row.registrationId) !== registrationId) throw Error('订单信息有误，请刷新');
  return row;
}
function reservation(row, owner, registrationId) {
  owned(row, owner, registrationId);
  if (!exactId(row.id) || !exactId(row.unitId) || !['BOOKED', 'CANCELLED', 'WAIVED', 'LATE_CANCELLED', 'NO_SHOW', 'FULFILLED', 'MERCHANT_EXITED'].includes(row.status)) throw Error('预约状态暂时读不到，请刷新');
  return { id: exactId(row.id), unitId: exactId(row.unitId), merchantId: exactId(row.merchantId), status: row.status,
    statusText: STATES[row.status], startAt: String(row.startAt || ''), canCancel: row.status === 'BOOKED' };
}
function order(value, owner, registrationId) {
  if (!value || exactId(value.registrationId) !== registrationId) throw Error('订单状态暂时读不到，请刷新');
  const result = { registrationId, registrationNo: String(value.registrationNo || ''),
    paymentStatus: value.paymentStatus, registrationStatus: value.registrationStatus,
    paymentText: ({ 0: '待支付', 1: '支付中', 2: '已支付', 3: '支付失败', 4: '已退款' })[value.paymentStatus] || '支付状态确认中',
    registrationText: ({ 1: '待支付', 2: '报名成功', 3: '已取消', 4: '已过期' })[value.registrationStatus] || '报名状态确认中', legacy: value.bundle == null,
    refundHold: null, units: [], reservations: [], fulfilledMerchants: [], exitCases: [], versions: [] };
  if (result.legacy) return result;
  const bundle = value.bundle;
  owned(bundle.order, owner, registrationId);
  if (!Array.isArray(bundle.units) || !Array.isArray(bundle.reservations) || !Array.isArray(bundle.exitCases)) throw Error('权益暂时读不全，请刷新');
  if (bundle.refundHold) {
    const hold = owned(bundle.refundHold, owner, registrationId);
    const states = { PREPARED: '退款准备中', BOUND: '退款已申请',
      APPROVED: '退款已批准', SUCCEEDED: '原路退款已确认到账',
      ZERO_CASH_COMPLETED: '无现金取消已完成，没有现金退款', REJECTED_HELD: '退款未通过，权益暂时冻结',
      MANUAL_REVIEW_HELD: '退款人工处理中，权益暂时冻结', RECONCILIATION_REQUIRED: '退款处理中' };
    if (!states[hold.state] || !exactId(hold.sourceRegistrationId)
        || hold.refundId != null && !exactId(hold.refundId)) throw Error('退款状态暂时读不到，请刷新');
    result.refundHold = { text: states[hold.state], refundId: exactId(hold.refundId), state: hold.state };
    // Legacy paymentStatus=4 is written at approval, so only the original intent confirms cash arrival.
    result.paymentText = hold.state === 'SUCCEEDED' ? '原路退款已到账'
      : hold.state === 'ZERO_CASH_COMPLETED' ? '无现金取消已完成' : states[hold.state];
  }
  result.units = bundle.units.map(row => {
    owned(row, owner, registrationId);
    if (!exactId(row.id) || !['AVAILABLE', 'RESERVED', 'REDEEMED', 'FORFEITED', 'HELD', 'REFUND_PENDING', 'REFUNDED'].includes(row.status)) throw Error('权益暂时读不全，请刷新');
    const balance = Array.isArray(bundle.balances) && bundle.balances.find(item => exactId(item.grantId)
      && exactId(item.grantId) === exactId(row.grantId) && exactId(item.holderMemberId) === owner);
    const statusText = row.status === 'AVAILABLE' ? balance && balance.expired === true ? '当前不在有效期内'
      : balance && balance.expired === false ? '可用' : '可用' : STATES[row.status];
    return { id: exactId(row.id), chapterId: exactId(row.chapterId), status: row.status, statusText,
      validTo: String(row.validTo || ''), unitCode: String(row.unitCode || '') };
  });
  if (bundle.fulfilledMerchants != null && !Array.isArray(bundle.fulfilledMerchants)) throw Error('商家信息暂时读不到，请刷新');
  result.fulfilledMerchants = (bundle.fulfilledMerchants || []).map(row => {
    owned(row, owner, registrationId);
    if (!exactId(row.merchantId)) throw Error('商家信息暂时读不到，请刷新');
    return { merchantId: exactId(row.merchantId) };
  });
  result.reservations = bundle.reservations.map(row => reservation(row, owner, registrationId));
  result.exitCases = bundle.exitCases.map(row => {
    owned(row, owner, registrationId);
    if (!exactId(row.id) || !['SWAP_CONSENTED', 'REFUND_UNFULFILLED'].includes(row.decision)) throw Error('售后记录暂时读不到，请刷新');
    return { id: exactId(row.id), unitId: exactId(row.unitId), targetOfferId: exactId(row.targetOfferId),
      idempotencyKey: String(row.idempotencyKey || ''), decision: row.decision, text: row.decision === 'SWAP_CONSENTED' ? '已同意替换商家' : '已申请未使用部分退款' };
  });
  for (const key of ['productVersion', 'quotaVersion', 'policyVersion', 'contractVersion', 'settlementRuleVersion']) {
    result.versions.push({ key, value: String(bundle.order[key] || '历史版本未记录') });
  }
  return result;
}
function quote(row) {
  if (!row || !['ORIGINAL_METHOD', 'PLATFORM_BALANCE', 'NOT_REFUNDABLE'].includes(row.route)
      || !/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(String(row.amount))) throw Error('可退金额暂时读不到，请刷新');
  return { amount: String(row.amount), reason: String(row.reason || ''), route: row.route,
    notice: '尚未提交退款' };
}
function cancellationQuote(row, owner, registrationId, reservationId, useWaiver) {
  owned(row, owner, registrationId);
  if (exactId(row.reservationId) !== reservationId || row.useWaiver !== useWaiver
      || !['CANCELLED', 'WAIVED', 'LATE_CANCELLED'].includes(row.outcome)
      || typeof row.expectedOutcome !== 'string'
      || row.expectedOutcome !== (row.outcome === 'WAIVED' ? 'WAIVED:' + row.waiverMonth : row.outcome)
      || row.outcome === 'WAIVED' && !/^\d{4}-\d{2}$/.test(row.waiverMonth)) throw Error('取消条件暂时读不到，请刷新');
  return { expectedOutcome: row.expectedOutcome, canCancel: row.canCancel === true,
    message: row.outcome === 'CANCELLED' ? '当前可免费取消，不扣本次权益。'
      : row.outcome === 'WAIVED' ? '将使用 ' + row.waiverMonth + ' 月的免费取消机会（每月一次）。'
        : '当前不足12小时，取消会扣除本次权益，不另收现金。' };
}
function exitOptions(row, owner, registrationId, unitId) {
  owned(row, owner, registrationId);
  if (exactId(row.unitId) !== unitId || !Array.isArray(row.targets)) throw Error('可换商家暂时读不到，请刷新');
  return { unitId, merchantExited: row.merchantExited === true, targets: row.targets.map(target => {
    if (!exactId(target.offerId)) throw Error('可换商家暂时读不到，请刷新');
    return { offerId: exactId(target.offerId), merchantName: String(target.merchantName || '商家'), validEnd: String(target.validEnd || '') };
  }) };
}
function catalog(row, topicId) {
  if (!row || exactId(row.topicId) !== topicId || Number(row.productType) !== 2 || !Array.isArray(row.items)) throw Error('套票信息暂时读不到，请刷新');
  return { topicId, topicName: String(row.topicName || '自由探索'), saleEnabled: row.saleEnabled === true,
    items: row.items.map(item => {
      if (!exactId(item.chapterId) || !Array.isArray(item.stores)) throw Error('套票信息暂时读不到，请刷新');
      return { chapterId: exactId(item.chapterId), name: String(item.name || '章节'),
        unitsText: Number.isInteger(item.unitsPerHolder) && item.unitsPerHolder > 0 ? String(item.unitsPerHolder) + '次' : '次数待确认',
        stores: item.stores.map(store => {
          if (!exactId(store.offerId)) throw Error('套票信息暂时读不到，请刷新');
          return { offerId: exactId(store.offerId), merchantName: String(store.merchantName || '商家'), validEnd: String(store.validEnd || ''), available: store.available === true };
        }) };
    }) };
}
module.exports = { exactId, reservation, order, quote, cancellationQuote, exitOptions, catalog };

// Public merchant identity is resolved by memberId, never inferred from offer/SKU IDs.
function publicMerchant(row, memberId) {
  if (!row || exactId(row.memberId) !== memberId || !exactId(row.id)) throw Error('商家信息暂时读不到，请刷新');
  return { memberId, merchantRowId: exactId(row.id) };
}
function reviews(row, registrationId) {
  if (!row || row.mode !== 'public' || !Array.isArray(row.items) || !row.eligibility
      || !Number.isSafeInteger(row.pageNum) || row.pageNum < 1) throw Error('评价暂时读不到，请刷新');
  const e = row.eligibility, eligibleId = exactId(e.registrationId);
  if (exactId(e.requestedRegistrationId) !== registrationId) throw Error('评价暂时读不到，请刷新');
  if (e.canCreate === true && !eligibleId) throw Error('评价暂时读不到，请刷新');
  return { pageNum: row.pageNum, hasMore: row.hasMore === true,
    canCreate: e.canCreate === true && eligibleId === registrationId,
    message: e.canCreate === true ? eligibleId === registrationId ? '可以评价这笔订单'
      : '这笔订单暂不能评价'
      : '这笔订单暂不能评价',
    items: row.items.map(item => {
      if (!exactId(item.id) || item.status !== 'VISIBLE') throw Error('评价暂时读不到，请刷新');
      return { id: exactId(item.id), content: String(item.content || ''), rating: item.rating,
        merchantReply: String(item.merchantReply || '') };
    }) };
}
function reviewIntent(row, owner, registrationId) {
  if (!row || row.owner !== owner || row.registrationId !== registrationId || !exactId(row.merchantRowId)
      || !/^[A-Za-z0-9_-]{1,64}$/.test(row.requestId) || !Number.isInteger(row.rating) || row.rating < 1 || row.rating > 5
      || typeof row.content !== 'string' || row.content.trim().length < 2 || row.content.length > 1000) throw Error('上次的评价没能恢复，请重新填写');
  return row;
}
module.exports.publicMerchant = publicMerchant;
module.exports.reviews = reviews;
module.exports.reviewIntent = reviewIntent;
