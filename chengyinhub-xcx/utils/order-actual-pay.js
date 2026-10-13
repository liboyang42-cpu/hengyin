// RUN-43:订单卡「实付款」的唯一装配口径(拍板 9-18:实付款只写现金)。
// 0 是合法现金实付,回落链必须逐段判 null,不能 ||。
function isSet(v) {
  return v !== null && v !== undefined
}

function resolveActualPaidAmount(item) {
  if (!item || typeof item !== 'object') return null
  if (isSet(item.wechatPaymentAmount)) return Number(item.wechatPaymentAmount)
  if (isSet(item.payableAmount)) return Number(item.payableAmount)
  if (isSet(item.totalAmount)) return Number(item.totalAmount)
  return null
}

// 现金实付为零也可能是积分抵扣；只有明确零价且无需付款的单才叫免费。
function isFreeOrder(item) {
  const zero = v => (typeof v === 'number' || typeof v === 'string' && v.trim() !== '') && Number(v) === 0
  return !!item && zero(item.totalAmount) && zero(item.payableAmount)
    && (item.pointPaymentAmount == null || zero(item.pointPaymentAmount))
    && (item.wechatPaymentAmount == null || zero(item.wechatPaymentAmount))
}

module.exports = { resolveActualPaidAmount, isFreeOrder }
