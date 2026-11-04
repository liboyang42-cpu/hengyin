// 玩家订单状态的唯一读模型。退款终态只能取 refund_application.payout_status，不能由报名取消态猜测。
const { isFreeOrder } = require('./order-actual-pay');
const { toTimestamp, chinaParts } = require('./datetime');

function sourceOf(row) {
  return row && (row.cmsActivity || row.cmsTopic) || {};
}

// 主题日期是 date-only(yyyy-MM-dd):结束日当天整天都算,不能在 00:00 就判「已结束」。
function endOf(value) {
  const ts = toTimestamp(value);
  return typeof value === 'string' && /^\d{4}-\d{1,2}-\d{1,2}$/.test(value.trim()) ? ts + 86399999 : ts;
}

// 自玩通行证(purchase_kind=2;存量 NULL 按 主题单 + 无票种 + 有到期 认):没有核销、没有集合点,有效期只看 expiresAt。
function isSelfPlayPass(row) {
  const v = row || {};
  if (v.purchaseKind !== null && v.purchaseKind !== undefined && v.purchaseKind !== '') return Number(v.purchaseKind) === 2;
  return Number(v.ownerType) === 1 && !v.ticketId && !!v.expiresAt;
}

// 取值真源 = 后端 RefundApplication:status 0 待审核 / 1 批准 / 2 驳回;
// payout_status 建单即 5,批准后 6 派发中 → 0 微信已受理 → 1 到账 / 2 待人工 / 3 人工已退 / 4 复核完成。
// 不在 1–4 时只说真实走到的那一步,不给「预计 N 个工作日」这种没人兑现的承诺。
function payoutSummary(application) {
  if (!application) return null;
  const toCode = function (v) { return v === null || v === undefined || v === '' ? NaN : Number(v); };
  const payout = toCode(application.payoutStatus);
  if (payout === 1 || payout === 4) {
    return { key: 'refunded', text: '退款成功', refundText: '退款已原路退回' };
  }
  if (payout === 2) {
    return { key: 'refunding', text: '正在退款', refundText: '原路退款异常，平台正在人工处理' };
  }
  if (payout === 3) {
    return { key: 'refunding', text: '正在退款', refundText: '人工退款已处理，等待复核确认' };
  }
  const amount = application.refundAmount;
  if (amount !== undefined && amount !== null && !(Number(amount) > 0)) return null;   // 零元单没有钱可退,不给在途承诺
  const status = toCode(application.status);
  if (status === 2) return null;   // 驳回:不是退款中,由 summarizeOrderState 回落到订单真实状态
  if (status === 0) {
    return { key: 'refunding', text: '退款审核中', refundText: '退款申请已提交，等待平台审核' };
  }
  if (payout === 0) {
    return { key: 'refunding', text: '正在退款', refundText: '微信已受理原路退款，到账以微信退款通知为准' };
  }
  if (status === 1) {
    return { key: 'refunding', text: '正在退款', refundText: '退款已批准，正在提交原路退款' };
  }
  return { key: 'refunding', text: '正在退款', refundText: '退款进度更新中，请以微信退款通知为准' };
}

// U-033:未开始/进行中/已完成按服务端读时刻(row.serverNow,/api/registration/list|info|my-joined 下发)判,
// 手机时钟只在后端没给该字段时兜底 —— 手机时间被调过时标签不再跟着跑偏。
function orderClockNow(row, fallbackNow) {
  const serverNow = row && Number(row.serverNow);
  if (Number.isFinite(serverNow) && serverNow > 0) return serverNow;
  return typeof fallbackNow === 'number' ? fallbackNow : Date.now();
}

// 服务端 /api/registration/list|info 给 ③ 探索票下发的 exploreProgress;没有就返回 null,走原口径。
function exploreProgressOf(row) {
  const p = row && row.exploreProgress;
  if (!p || !(Number(p.required) > 0)) return null;
  return { redeemed: Number(p.redeemed) || 0, required: Number(p.required), active: p.active === true,
    completed: p.completed === true, ended: p.ended === true };
}

function summarizeOrderState(row, now) {
  const value = row || {};
  const refund = payoutSummary(value.refundApplication);
  // 钱已到账(payout 1/4)是终态事实，人工案件不能把「已退款」盖回「人工售后」。
  if (refund && refund.key === 'refunded') return refund;
  if (value.manualRefundCaseStatus) {
    return { key: 'manual_refund', text: '人工售后',
      refundText: value.refundInfo && value.refundInfo.reason || '请查看订单的人工处理进度' };
  }
  if (refund) return refund;

  // 主办取消是活动事实，不推导报名、支付或退款已经结束；真实售后进度仍优先。
  const activityCancelTime = value.cmsActivity && toTimestamp(value.cmsActivity.cancelTime);
  if (Number.isFinite(activityCancelTime) && activityCancelTime > 0) {
    return { key: 'cancelled', text: '活动已取消', refundText: '请查看订单的真实处理进展' };
  }

  const registrationStatus = Number(value.registrationStatus);
  if (registrationStatus === 1) {
    return { key: 'pending_payment', text: '待支付', refundText: '完成支付后可使用' };
  }
  if (registrationStatus === 3) {
    return { key: 'cancelled', text: '已取消', refundText: '订单已关闭' };
  }
  if (registrationStatus === 4) {   // 后端 CmsRegistration:4=已过期(closeExpired 关单),以前落到「订单状态更新中」
    return { key: 'expired', text: '已过期', refundText: '超时未支付，订单已关闭' };
  }
  if (registrationStatus !== 2) {
    return { key: 'unknown', text: '订单状态更新中', refundText: '' };
  }

  const free = isFreeOrder(value);
  const freeText = '免费报名，无需支付';
  const source = sourceOf(value);
  const current = typeof now === 'number' ? now : orderClockNow(value);
  const start = toTimestamp(source.startDate || source.startTime);
  // P-03:探索票核销一家只是「核销过」(退款闸照旧),必选章没核销完、场次没结束就还在进行中。
  // R11 p1 F-1:场次还没开始就是「未开始」,买完不等于进行中。
  const explore = exploreProgressOf(value);
  if (explore && explore.active) {
    const refundInfo = value.refundInfo || {};
    const early = !Number.isNaN(start) && current < start;
    return { key: early ? 'not_started' : 'in_progress', text: early ? '未开始' : '进行中',
      refundText: refundInfo.reason || (free ? freeText : ''),
      exploreProgress: explore, progressText: '已核销 ' + explore.redeemed + '/' + explore.required + ' 家' };
  }
  if (Number(value.verificationStatus) === 1) {
    return { key: 'completed', text: '已完成', refundText: free ? freeText : '已核销订单不可退款' };
  }
  // P-22:城市定向通关(服务端 topicCompleted,按本单之后的通关勋章判)即已完成,不再停在「待核销」
  if (value.topicCompleted === true) {
    const refundInfo = value.refundInfo || {};
    return { key: 'completed', text: '已完成', refundText: refundInfo.reason || (free ? freeText : '已通关'), topicCompleted: true };
  }

  const refundInfo = value.refundInfo || {};
  if (Number(value.paymentStatus) === 2 && !Number.isNaN(toTimestamp(value.firstSuccessfulScanAt))) {
    return { key: 'completed', text: '已完成', refundText: refundInfo.reason || (free ? freeText : ''), firstScanCompleted: true };
  }
  // R11 j3 F9:自玩通行证的状态是能不能玩(未开始/进行中/已结束),「不可退款」只在退款卡说一次。
  const pass = isSelfPlayPass(value);
  if (!pass && !free && refundInfo.refundable === false) {
    return { key: 'non_refundable', text: '不可退款', refundText: refundInfo.reason || '当前订单不可自助退款' };
  }

  const end = pass ? toTimestamp(value.expiresAt)
    : endOf(source.endDate || source.endTime || source.startDate || source.startTime);
  if (!Number.isNaN(end) && current > end) {
    return pass ? { key: 'completed', text: '已结束', refundText: '通行证已到期' }
      : { key: 'completed', text: '已完成', refundText: '活动已结束' };
  }
  if (!Number.isNaN(start) && current < start) {
    return { key: 'not_started', text: '未开始', refundText: free ? freeText : (refundInfo.reason || '可在退款截止前申请原路退款') };
  }
  return { key: 'in_progress', text: '进行中', refundText: free ? freeText : (refundInfo.reason || '可在退款截止前申请原路退款') };
}

// 完成态与退款资格分别读取真实扫码、核销及退款事实。
function canRequestOrderRefund(row) {
  const value = row || {};
  return !value.manualRefundCaseStatus && !payoutSummary(value.refundApplication)
    && Number(value.registrationStatus) === 2 && Number(value.paymentStatus) === 2
    && Number(value.verificationStatus) !== 1
    && !!value.refundInfo && value.refundInfo.refundable === true;
}

function formatRefundDeadline(value) {
  const date = chinaParts(value);
  if (!date) return '';
  const pad = function (number) { return String(number).padStart(2, '0'); };
  const day = date.year + '-' + pad(date.month) + '-' + pad(date.day);
  // date-only 值没有钟点,不补一个假的 00:00(R11 j3 F12)
  return typeof value === 'string' && /^\d{4}-\d{1,2}-\d{1,2}$/.test(value.trim()) ? day
    : day + ' ' + pad(date.hours) + ':' + pad(date.minutes);
}

module.exports = { summarizeOrderState, canRequestOrderRefund, formatRefundDeadline, orderClockNow, exploreProgressOf, isSelfPlayPass, endOf };
