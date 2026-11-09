// §17.1 / §17.2 / §17.3 / §17.4 / P6:通行证与礼品只读服务端事实。
// - 可买与否只看服务端:列表只来自 /api/marketing/product/list(listSellable),购买按钮只在
//   报价回 availablePayModes 非空时出现;现金关闭写 cashUnavailableReason,不自判。
// - 价格、期限、席位、覆盖作品都原样显示,不乘人数、不拼折扣。
// - 礼品:待领取时买家也不能玩;领取页按 reasonCode 分别说,不泄露他人订单。
const datetime = require('../../utils/datetime.js');

function ts(v) {
  const t = datetime.toTimestamp(v);
  return Number.isFinite(t) ? t : null;
}
function isNum(v) {
  return v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v));
}
function day(v) {
  const t = ts(v);
  return t === null ? '' : datetime.formatDayDots(t);
}

const KIND_TEXT = {
  1: '单关票', 2: '城市包', 3: '时限通票', 4: '固定期年卡',
  5: '团体票', 6: '学校团体票', 7: '礼品通行证', 8: '加座'
};
const PAY_MODE = { CASH: 1, POINTS: 2 };
const FULFILLMENT = { SELF: 1, GIFT: 2, EXTRA_SEAT: 3 };
const ROUTE = { SELF_PLAY: 1, TICKET: 2, EXTRA_SEAT: 3 };

/** 期限文案:固定期是固定有效期,不写订阅;首次使用型写「首次游玩起」。 */
function validityText(p) {
  if (!p) return '';
  const k = Number(p.validityKind);
  if (k === 1 && isNum(p.validDays)) return '购买后 ' + Number(p.validDays) + ' 天内有效';
  if (k === 2) {
    const from = day(p.fixedValidFrom);
    const to = day(p.fixedValidTo);
    if (from && to) return from + ' – ' + to;
    return to ? ('有效至 ' + to) : '';
  }
  if (k === 3 && isNum(p.validDays)) return '首次游玩起 ' + Number(p.validDays) + ' 天内有效';
  return '';
}

/** 价格只显示服务端给的单价;现金为空写积分价;都为空不显示价格(不写 0)。 */
function priceText(cash, points) {
  const parts = [];
  if (isNum(cash)) parts.push('¥' + Number(cash).toFixed(2));
  if (isNum(points)) parts.push(Number(points) + ' 积分');
  return parts.join(' / ');
}

function productCard(p) {
  if (!p || !p.productId) return null;
  const items = Array.isArray(p.items) ? p.items : [];
  return {
    id: p.productId,
    name: p.productName || '',
    kind: KIND_TEXT[p.productKind] || '',
    city: p.saleCity || '',
    validity: validityText(p),
    seats: isNum(p.seatCount) && Number(p.seatCount) > 1 ? (Number(p.seatCount) + ' 人') : '',
    price: priceText(p.cashPrice, p.pointsPrice),
    coverCount: items.length,
    topics: items.map((it) => it && it.topicTitle).filter(Boolean),
    giftAllowed: Number(p.giftAllowed) === 1 && Number(p.productKind) !== 7,
    // 礼品通行证(kind=7)只能走赠送履约
    giftOnly: Number(p.productKind) === 7,
    // §17.3 加座:独立增购分支未合入,只显示不可用原因,不报价、不收款
    isExtraSeat: Number(p.productKind) === 8 || Number(p.purchaseRoute) === ROUTE.EXTRA_SEAT
  };
}

// 服务端 reasonCode → 人话。码不可改,文案可改;未知码统一「暂不可购买」,不解析中文。
const REASON_TEXT = {
  PAYMENT_OWNER_ROUTE_NOT_WIRED: '现金支付暂未开通',
  CASH_DISABLED: '现金支付暂未开通',
  PRODUCT_NOT_FOUND: '商品不存在或已下架',
  PRODUCT_NOT_ON_SALE: '商品当前未在售',
  SALE_NOT_STARTED: '商品尚未开售',
  SALE_ENDED: '商品已停售',
  PERIOD_ENDED: '商品有效期已结束',
  PRODUCT_HAS_NO_CONTENT: '商品未覆盖可玩内容',
  PAYMENT_UNAVAILABLE: '支付暂不可用',
  ORDER_NOT_PAYABLE: '订单当前不可支付',
  ORDER_STATE_CHANGED: '订单状态已变化，请刷新',
  REFUND_POLICY_NOT_DEFINED: '退款规则未配置，暂不可购买',
  CURRENCY_UNSUPPORTED: '该商品币种暂不支持',
  PAY_MODE_UNAVAILABLE: '该支付方式不可用',
  QUOTE_EXPIRED: '报价已过期，请重新确认',
  QUOTE_STALE: '商品价格或内容已变化，请重新确认',
  INSUFFICIENT_POINTS: '积分不足',
  POINTS_INSUFFICIENT: '积分不足',
  GIFT_NOT_ALLOWED: '该商品不支持赠送',
  USE_REGISTRATION_CHECKOUT: '该商品需在报名结算中购买',
  EXTRA_SEAT_REQUIRES_ORIGINAL_ORDER: '加座需从原订单发起，暂不可直接购买',
  REQUEST_CONFLICT: '同一请求内容不一致，请重新确认'
};
function reasonText(code) {
  return REASON_TEXT[code] || '暂不可购买';
}

/**
 * 报价 → 购买区。
 * - route=2 团体/学校:不出本域购买按钮,给原报名结算入口(checkout 指针)。
 * - route=3 加座:只显示不可用原因(§17.3 共享分支未合入,不先收款)。
 * - 其它:availablePayModes 里有什么出什么;现金关闭写原因。
 */
function purchaseView(quote) {
  const out = { state: 'unavailable', reason: '', payModes: [], total: '', checkout: null, expiresAt: null };
  if (!quote || typeof quote !== 'object') return out;
  const route = Number(quote.purchaseRoute);
  if (route === ROUTE.TICKET || quote.purchaseRouteReason === 'USE_REGISTRATION_CHECKOUT') {
    const c = quote.checkout && typeof quote.checkout === 'object' ? quote.checkout : null;
    out.state = c ? 'checkout' : 'unavailable';
    out.checkout = c;
    out.reason = c ? '' : reasonText('USE_REGISTRATION_CHECKOUT');
    return out;
  }
  if (route === ROUTE.EXTRA_SEAT || quote.purchaseRouteReason === 'EXTRA_SEAT_REQUIRES_ORIGINAL_ORDER') {
    out.reason = reasonText('EXTRA_SEAT_REQUIRES_ORIGINAL_ORDER');
    return out;
  }
  const modes = Array.isArray(quote.availablePayModes) ? quote.availablePayModes.map(Number) : [];
  if (modes.indexOf(PAY_MODE.POINTS) >= 0 && isNum(quote.totalPointsAmount)) {
    out.payModes.push({ mode: PAY_MODE.POINTS, label: Number(quote.totalPointsAmount) + ' 积分兑换' });
  }
  if (modes.indexOf(PAY_MODE.CASH) >= 0 && isNum(quote.totalCashAmount)) {
    out.payModes.push({ mode: PAY_MODE.CASH, label: '支付 ¥' + Number(quote.totalCashAmount).toFixed(2) });
  }
  out.expiresAt = ts(quote.quoteExpiresAt);
  if (out.payModes.length && quote.quoteSign && out.expiresAt !== null) {
    out.state = 'buyable';
    if (quote.cashUnavailableReason) out.reason = reasonText(quote.cashUnavailableReason);
    return out;
  }
  out.reason = quote.cashUnavailableReason ? reasonText(quote.cashUnavailableReason) : reasonText(null);
  return out;
}

/** 下单请求:只回传服务端报价;金额不由客户端提交。 */
function orderRequest(productId, quote, payMode, fulfillmentKind, requestId) {
  return {
    productId: productId,
    payMode: payMode,
    quantity: Number(quote && quote.quantity) || 1,
    fulfillmentKind: fulfillmentKind || FULFILLMENT.SELF,
    quoteSign: quote && quote.quoteSign,
    quoteExpiresAt: ts(quote && quote.quoteExpiresAt),
    requestId: requestId
  };
}

/**
 * 订单状态:支付成功(SDK)不等于已付,只认服务端 status。
 * fulfillmentState=2 = 已付但等待首次游玩激活。
 */
function orderStateText(order) {
  if (!order) return '';
  const s = order.statusLabel;
  if (s === 'PAID') {
    if (Number(order.fulfillmentState) === 2) return '已购买 · 首次游玩时开始计算有效期';
    if (Number(order.fulfillmentKind) === FULFILLMENT.GIFT) return '已购买 · 礼品';
    return '已购买';
  }
  if (s === 'PENDING_CASH') {
    const blocked = order.payment && order.payment.blockedReason;
    return blocked ? ('待支付 · ' + reasonText(blocked)) : '待支付';
  }
  if (s === 'CANCELLED') return '已取消';
  if (s === 'REFUNDED') return '已退款';
  return '状态待确认';
}

function orderRow(order, nameById) {
  if (!order || !order.orderNo) return null;
  const amount = Number(order.payMode) === PAY_MODE.POINTS
    ? (isNum(order.pointsAmount) ? Number(order.pointsAmount) + ' 积分' : '')
    : (isNum(order.cashAmount) ? '¥' + Number(order.cashAmount).toFixed(2) : '');
  return {
    key: order.orderNo,
    name: (nameById && nameById[order.productId]) || '通行证订单',
    state: orderStateText(order),
    amount,
    time: day(order.paidAt || order.createdAt)
  };
}

const GIFT_STATE = { 0: '待领取', 1: '已领取', 2: '已过期', 3: '已撤销' };
function giftRow(gift, side) {
  if (!gift || gift.giftId === undefined || gift.giftId === null) return null;
  const status = Number(gift.status);
  let state = GIFT_STATE[status] || '状态待确认';
  if (side === 'bought' && status === 0) state = '待领取 · 领取前你和对方都不能游玩';
  if (side === 'bought' && status === 3) state = '已撤销 · 退回给你';
  return {
    key: side + '-' + gift.giftId,
    state,
    expires: status === 0 && day(gift.claimExpiresAt) ? ('领取截止 ' + day(gift.claimExpiresAt)) : '',
    time: day(gift.claimedAt || gift.createdAt)
  };
}

// §17.2 领取失败按原因分别说,不泄露他人订单
const CLAIM_REASON_TEXT = {
  TOKEN_REQUIRED: '缺少领取码',
  GIFT_TOKEN_INVALID: '领取码无效',
  GIFT_CLAIM_EXPIRED: '礼品领取码已过期',
  GIFT_NOT_FOUND: '礼品不存在',
  GIFT_NOT_FOR_YOU: '这份礼品不是给你的',
  GIFT_ALREADY_CLAIMED: '礼品已被领取',
  GIFT_UNAVAILABLE: '礼品当前不可领取',
  GIFT_SOURCE_UNLINKED: '礼品当前不可领取',
  GIFT_SOURCE_MISSING: '礼品当前不可领取',
  GIFT_SOURCE_OWNER_MISMATCH: '礼品当前不可领取',
  GIFT_SOURCE_SCOPE_INVALID: '礼品当前不可领取',
  GIFT_SOURCE_INACTIVE: '礼品当前不可领取',
  GIFT_SOURCE_EXPIRED: '礼品已过期',
  GIFT_PRODUCT_MISSING: '礼品当前不可领取',
  GIFT_TOPIC_NOT_COVERED: '礼品当前不可领取',
  GIFT_SOURCE_ALREADY_USED: '礼品已开始使用，不能领取'
};
function claimReasonText(code) {
  return CLAIM_REASON_TEXT[code] || '礼品当前不可领取';
}

module.exports = {
  KIND_TEXT, PAY_MODE, FULFILLMENT, ROUTE,
  validityText, priceText, productCard, purchaseView, orderRequest,
  orderStateText, orderRow, giftRow, reasonText, claimReasonText
};
