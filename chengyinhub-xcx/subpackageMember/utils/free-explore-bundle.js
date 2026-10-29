'use strict';

// F3 wire IDs are decimal strings. Never recover a rounded JSON number.
function exactId(value) {
  if (typeof value === 'number' && Number.isSafeInteger(value)) value = String(value);
  return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value)
    && (value.length < 19 || value <= '9223372036854775807') ? value : '';
}
const STATES = { AVAILABLE: '可用', RESERVED: '已预约', REDEEMED: '已履约', FORFEITED: '已扣除本次权益',
  HELD: '冻结中', REFUND_PENDING: '退款待处理（未到账）', REFUNDED: '权益已退款处理',
  BOOKED: '待履约', CANCELLED: '已取消', WAIVED: '已豁免取消', LATE_CANCELLED: '晚取消', NO_SHOW: '爽约', FULFILLED: '已履约', MERCHANT_EXITED: '商家已退出' };
function owned(row, owner, registrationId) {
  if (!row || exactId(row.holderMemberId) !== owner || exactId(row.registrationId) !== registrationId) throw Error('订单归属无法确认');
  return row;
}
function reservation(row, owner, registrationId) {
  owned(row, owner, registrationId);
  if (!exactId(row.id) || !exactId(row.unitId) || !['BOOKED', 'CANCELLED', 'WAIVED', 'LATE_CANCELLED', 'NO_SHOW', 'FULFILLED', 'MERCHANT_EXITED'].includes(row.status)) throw Error('预约回读无法确认');
  return { id: exactId(row.id), unitId: exactId(row.unitId), merchantId: exactId(row.merchantId), status: row.status,
    statusText: STATES[row.status], startAt: String(row.startAt || ''), canCancel: row.status === 'BOOKED' };
}
function order(value, owner, registrationId) {
  if (!value || exactId(value.registrationId) !== registrationId) throw Error('订单回读无法确认');
  const result = { registrationId, registrationNo: String(value.registrationNo || ''),
    paymentStatus: value.paymentStatus, registrationStatus: value.registrationStatus,
    paymentText: ({ 0: '待支付', 1: '支付中', 2: '已支付', 3: '支付失败', 4: '已退款' })[value.paymentStatus] || '支付状态待确认',
    registrationText: ({ 1: '待支付', 2: '报名成功', 3: '已取消', 4: '已过期' })[value.registrationStatus] || '报名状态待确认', legacy: value.bundle == null,
    refundHold: null, units: [], reservations: [], fulfilledMerchants: [], exitCases: [], versions: [] };
  if (result.legacy) return result;
  const bundle = value.bundle;
  owned(bundle.order, owner, registrationId);
  if (!Array.isArray(bundle.units) || !Array.isArray(bundle.reservations) || !Array.isArray(bundle.exitCases)) throw Error('权益回读不完整');
  if (bundle.refundHold) {
    const hold = owned(bundle.refundHold, owner, registrationId);
    const states = { PREPARED: '退款准备中，权益已冻结', BOUND: '退款已申请，尚未到账',
      APPROVED: '退款已批准，尚未确认到账', SUCCEEDED: '原路退款已确认到账',
      ZERO_CASH_COMPLETED: '无现金取消已完成，没有现金退款', REJECTED_HELD: '退款已驳回或取代，权益仍冻结待核对',
      MANUAL_REVIEW_HELD: '原退款意图待人工核对，权益仍冻结', RECONCILIATION_REQUIRED: '资金与权益记录待核对' };
    if (!states[hold.state] || !exactId(hold.sourceRegistrationId)
        || hold.refundId != null && !exactId(hold.refundId)) throw Error('退款状态回读无法确认');
    result.refundHold = { text: states[hold.state], refundId: exactId(hold.refundId), state: hold.state };
    // Legacy paymentStatus=4 is written at approval, so only the original intent confirms cash arrival.
    result.paymentText = hold.state === 'SUCCEEDED' ? '原路退款已到账'
      : hold.state === 'ZERO_CASH_COMPLETED' ? '无现金取消已完成' : states[hold.state];
  }
  result.units = bundle.units.map(row => {
    owned(row, owner, registrationId);
    if (!exactId(row.id) || !['AVAILABLE', 'RESERVED', 'REDEEMED', 'FORFEITED', 'HELD', 'REFUND_PENDING', 'REFUNDED'].includes(row.status)) throw Error('权益回读无法确认');
    const balance = Array.isArray(bundle.balances) && bundle.balances.find(item => exactId(item.grantId)
      && exactId(item.grantId) === exactId(row.grantId) && exactId(item.holderMemberId) === owner);
    const statusText = row.status === 'AVAILABLE' ? balance && balance.expired === true ? '当前不在有效期内'
      : balance && balance.expired === false ? '可用' : '可用状态，须核对有效期' : STATES[row.status];
    return { id: exactId(row.id), chapterId: exactId(row.chapterId), status: row.status, statusText,
      validTo: String(row.validTo || ''), unitCode: String(row.unitCode || '') };
  });
  if (bundle.fulfilledMerchants != null && !Array.isArray(bundle.fulfilledMerchants)) throw Error('履约商家回读无法确认');
  result.fulfilledMerchants = (bundle.fulfilledMerchants || []).map(row => {
    owned(row, owner, registrationId);
    if (!exactId(row.merchantId)) throw Error('履约商家编号无法确认');
    return { merchantId: exactId(row.merchantId) };
  });
  result.reservations = bundle.reservations.map(row => reservation(row, owner, registrationId));
  result.exitCases = bundle.exitCases.map(row => {
    owned(row, owner, registrationId);
    if (!exactId(row.id) || !['SWAP_CONSENTED', 'REFUND_UNFULFILLED'].includes(row.decision)) throw Error('售后记录无法确认');
    return { id: exactId(row.id), unitId: exactId(row.unitId), targetOfferId: exactId(row.targetOfferId),
      idempotencyKey: String(row.idempotencyKey || ''), decision: row.decision, text: row.decision === 'SWAP_CONSENTED' ? '已同意替换商家' : '已选择未履约退款，到账须核对退款记录' };
  });
  for (const key of ['productVersion', 'quotaVersion', 'policyVersion', 'contractVersion', 'settlementRuleVersion']) {
    result.versions.push({ key, value: String(bundle.order[key] || '历史版本未记录') });
  }
  return result;
}
function quote(row) {
  if (!row || !['ORIGINAL_METHOD', 'PLATFORM_BALANCE', 'NOT_REFUNDABLE'].includes(row.route)
      || !/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(String(row.amount))) throw Error('退款试算无法确认');
  return { amount: String(row.amount), reason: String(row.reason || ''), route: row.route,
    notice: '仅为退款试算，尚未提交退款，不代表到账' };
}
function cancellationQuote(row, owner, registrationId, reservationId, useWaiver) {
  owned(row, owner, registrationId);
  if (exactId(row.reservationId) !== reservationId || row.useWaiver !== useWaiver
      || !['CANCELLED', 'WAIVED', 'LATE_CANCELLED'].includes(row.outcome)
      || typeof row.expectedOutcome !== 'string'
      || row.expectedOutcome !== (row.outcome === 'WAIVED' ? 'WAIVED:' + row.waiverMonth : row.outcome)
      || row.outcome === 'WAIVED' && !/^\d{4}-\d{2}$/.test(row.waiverMonth)) throw Error('取消试算无法确认');
  return { expectedOutcome: row.expectedOutcome, canCancel: row.canCancel === true,
    message: row.outcome === 'CANCELLED' ? '当前可免费取消，不扣本次权益。'
      : row.outcome === 'WAIVED' ? '尝试使用' + row.waiverMonth + '唯一一次主动豁免，已用完会拒绝。'
        : '当前不足12小时，取消会扣除本次权益，不另收现金。' };
}
function exitOptions(row, owner, registrationId, unitId) {
  owned(row, owner, registrationId);
  if (exactId(row.unitId) !== unitId || !Array.isArray(row.targets)) throw Error('换店方案无法确认');
  return { unitId, merchantExited: row.merchantExited === true, targets: row.targets.map(target => {
    if (!exactId(target.offerId)) throw Error('换店方案无法确认');
    return { offerId: exactId(target.offerId), merchantName: String(target.merchantName || '商家'), validEnd: String(target.validEnd || '') };
  }) };
}
function catalog(row, topicId) {
  if (!row || exactId(row.topicId) !== topicId || Number(row.productType) !== 2 || !Array.isArray(row.items)) throw Error('商品目录无法确认');
  return { topicId, topicName: String(row.topicName || '自由探索'), saleEnabled: row.saleEnabled === true,
    items: row.items.map(item => {
      if (!exactId(item.chapterId) || !Array.isArray(item.stores)) throw Error('商品章节无法确认');
      return { chapterId: exactId(item.chapterId), name: String(item.name || '章节'),
        unitsText: Number.isInteger(item.unitsPerHolder) && item.unitsPerHolder > 0 ? String(item.unitsPerHolder) + '次' : '次数待确认',
        stores: item.stores.map(store => {
          if (!exactId(store.offerId)) throw Error('商家供给无法确认');
          return { offerId: exactId(store.offerId), merchantName: String(store.merchantName || '商家'), validEnd: String(store.validEnd || ''), available: store.available === true };
        }) };
    }) };
}
module.exports = { exactId, reservation, order, quote, cancellationQuote, exitOptions, catalog };

// Public merchant identity is resolved by memberId, never inferred from offer/SKU IDs.
function publicMerchant(row, memberId) {
  if (!row || exactId(row.memberId) !== memberId || !exactId(row.id)) throw Error('公开商家身份无法确认');
  return { memberId, merchantRowId: exactId(row.id) };
}
function reviews(row, registrationId) {
  if (!row || row.mode !== 'public' || !Array.isArray(row.items) || !row.eligibility
      || !Number.isSafeInteger(row.pageNum) || row.pageNum < 1) throw Error('评价回读无法确认');
  const e = row.eligibility, eligibleId = exactId(e.registrationId);
  if (exactId(e.requestedRegistrationId) !== registrationId) throw Error('评价资格查询范围无法确认，请刷新后重试');
  if (e.canCreate === true && !eligibleId) throw Error('评价资格订单编号无法确认');
  return { pageNum: row.pageNum, hasMore: row.hasMore === true,
    canCreate: e.canCreate === true && eligibleId === registrationId,
    message: e.canCreate === true ? eligibleId === registrationId ? '平台确认当前订单可评价，提交时将再次核验'
      : '平台返回的是另一订单的资格，尚不能确认本订单可评价'
      : '平台尚未确认本订单有可用评价资格（可能未核销、已评价或商家不可用）',
    items: row.items.map(item => {
      if (!exactId(item.id) || item.status !== 'VISIBLE') throw Error('公开评价内容无法确认');
      return { id: exactId(item.id), content: String(item.content || ''), rating: item.rating,
        merchantReply: String(item.merchantReply || '') };
    }) };
}
function reviewIntent(row, owner, registrationId) {
  if (!row || row.owner !== owner || row.registrationId !== registrationId || !exactId(row.merchantRowId)
      || !/^[A-Za-z0-9_-]{1,64}$/.test(row.requestId) || !Number.isInteger(row.rating) || row.rating < 1 || row.rating > 5
      || typeof row.content !== 'string' || row.content.trim().length < 2 || row.content.length > 1000) throw Error('评价恢复记录无法确认');
  return row;
}
module.exports.publicMerchant = publicMerchant;
module.exports.reviews = reviews;
module.exports.reviewIntent = reviewIntent;
