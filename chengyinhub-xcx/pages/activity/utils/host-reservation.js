// §27.6 主持预约沿原场次报名:只按服务端 reservationState 显示,不自判已付或已确认。
// 服务端只在系列显式 hostReservationRequired:true 时投影 reservationState;
// 尚无条目时,同一份 waitlist/status 以「ELIGIBLE + 不允许加入候补 + NONE」表达「需先请求主持」
// (ClubWaitlistServiceImpl.myStatus:hostReservationRequired 时 joinAllowed=false)。
const STATE_TEXT = {
  REQUESTED: '主持申请中',
  ACCEPTED_AWAITING_PAYMENT: '主持已接受，待付款',
  CONFIRMED: '主持预约已确认',
  REJECTED: '主持未接受',
  EXPIRED: '主持预约已过期',
  CANCELLED: '主持预约已取消',
  PAYMENT_REVIEW_REQUIRED: '付款待处置',
  ORDER_REVIEW_REQUIRED: '订单待处置'
};

/**
 * @param status waitlist/status 回包(ClubWaitlistEntry 投影)
 * @param ticketSoldOut 票是否已售罄(售罄时走原候补卡,不在这里请求主持)
 */
function hostReservationView(status, ticketSoldOut) {
  const hidden = { show: false, required: false, text: '', canRequest: false, canCancel: false, awaitingPayment: false };
  if (!status || typeof status !== 'object') return hidden;
  const rs = status.reservationState;
  if (rs) {
    return {
      show: true,
      required: true,
      text: STATE_TEXT[rs] || '主持预约待确认',
      canRequest: rs === 'REJECTED' || rs === 'EXPIRED' || rs === 'CANCELLED',
      canCancel: rs === 'REQUESTED',
      awaitingPayment: rs === 'ACCEPTED_AWAITING_PAYMENT'
    };
  }
  const state = status.state || 'NONE';
  if (!ticketSoldOut && state === 'NONE' && status.eligibilityState === 'ELIGIBLE'
      && status.waitlistJoinAllowed === false) {
    return { show: true, required: true, text: '本场需先预约主持', canRequest: true, canCancel: false, awaitingPayment: false };
  }
  return hidden;
}

module.exports = { hostReservationView, STATE_TEXT };
