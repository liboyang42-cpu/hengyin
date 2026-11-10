// U07-01 / U07-02 / §17.5 / §17.6:结算页只读服务端报价。
// 本模块不算价、不推断资格:所有金额、优惠、交付条款都原样取自 /api/registration/quote
// 与 /api/marketing/promotions/catalog 的回包;拿不到的字段保持「不显示」,不补 0、不补默认条款。
const datetime = require('../../../utils/datetime.js');
const { formatMinuteDots } = datetime;
// datetime.toTimestamp 无效时返回 NaN;这里统一成 null,便于「缺字段 = 不显示」判断
function toTimestamp(v) {
  const t = datetime.toTimestamp(v);
  return Number.isFinite(t) ? t : null;
}

function isNum(v) {
  return v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v));
}

function yuan(v) {
  return isNum(v) ? Number(v).toFixed(2) : null;
}

// 2 = 普通票、3 = 探索票(PurchaseKind.derive);其余值只说明交付形态未知,不猜。
const PURCHASE_KIND_TEXT = { 1: '场次票', 2: '场次票', 3: '自由探索票' };
const BENEFICIARY_TEXT = { PAYER_SELF: '付款人本人' };

/**
 * U07-01:把 quote.delivery 变成「包含什么」行。
 * 多票混选时后端把 ticketName/teamSize/人数/审核/refundRule 置 null:此时整块只说「按票种分别说明」,
 * 不替整单挑一个票种条款。refundRule=null 时不出「可退」行。
 */
function deliveryRows(delivery) {
  if (!delivery || typeof delivery !== 'object') return { rows: [], perTicket: false };
  const rows = [];
  const perTicket = !delivery.ticketName;
  if (PURCHASE_KIND_TEXT[delivery.purchaseKind]) {
    rows.push({ key: 'kind', label: '票种类型', value: PURCHASE_KIND_TEXT[delivery.purchaseKind] });
  }
  if (perTicket) {
    rows.push({ key: 'perTicket', label: '包含内容', value: '按票种分别说明' });
  } else {
    const people = peopleText(delivery);
    if (people) rows.push({ key: 'people', label: '人数', value: people });
    if (delivery.needAudit === 1) rows.push({ key: 'audit', label: '报名审核', value: '需主办方审核' });
  }
  const window = serviceWindowText(delivery.serviceStartAt, delivery.serviceEndAt);
  if (window) rows.push({ key: 'window', label: '服务时间', value: window });
  if (BENEFICIARY_TEXT[delivery.beneficiaryKind]) {
    rows.push({ key: 'beneficiary', label: '使用人', value: BENEFICIARY_TEXT[delivery.beneficiaryKind] });
  }
  if (!perTicket && delivery.refundRule) {
    rows.push({ key: 'refundRule', label: '退款', value: String(delivery.refundRule) });
  }
  return { rows, perTicket };
}

function peopleText(d) {
  const min = isNum(d.minPeople) && Number(d.minPeople) > 0 ? Number(d.minPeople) : null;
  const max = isNum(d.maxPeople) && Number(d.maxPeople) > 0 ? Number(d.maxPeople) : null;
  const team = isNum(d.teamSize) && Number(d.teamSize) > 0 ? Number(d.teamSize) : null;
  if (min && max) return min === max ? (min + ' 人') : (min + '–' + max + ' 人');
  if (team) return team + ' 人一队';
  if (max) return '最多 ' + max + ' 人';
  if (min) return min + ' 人起';
  return '';
}

function serviceWindowText(start, end) {
  const s = toTimestamp(start);
  const e = toTimestamp(end);
  if (s === null && e === null) return '';
  if (s !== null && e !== null) return formatMinuteDots(s) + ' – ' + formatMinuteDots(e);
  return s !== null ? (formatMinuteDots(s) + ' 起') : ('至 ' + formatMinuteDots(e));
}

/**
 * U07-02:paymentChannels 只说明微信通道是否就绪。
 * - 字段缺失(旧 jar)→ unknown:沿用页面原有的支付就绪检查,不拦。
 * - 应付 > 0 且列表为空 → 不可付款(通道未就绪),按钮关闭并说原因。
 * - 小程序只能走 JSAPI;只有 APP 就绪也不能在小程序里付。
 */
function paymentChannelState(quote) {
  if (!quote || !Array.isArray(quote.paymentChannels)) return 'unknown';
  const pay = Number(quote.payAmount);
  if (!(pay > 0)) return 'free';
  return quote.paymentChannels.indexOf('JSAPI') >= 0 ? 'ready' : 'unavailable';
}

/**
 * §17.6 报价分项。payAmount 是唯一收款数字;listAmount 只在有促销减免时作划线原价。
 * 不用 listAmount - marketingDiscountAmount 自算应付。
 */
function marketingView(quote) {
  const empty = { show: false, listAmount: null, adjustments: [], coveredByPass: false, rewardCoupon: null };
  if (!quote || typeof quote !== 'object') return empty;
  const discount = Number(quote.marketingDiscountAmount);
  const adjustments = (Array.isArray(quote.marketingAdjustments) ? quote.marketingAdjustments : [])
    .filter((a) => a && isNum(a.amount) && Number(a.amount) !== 0)
    .map((a, i) => ({
      key: (a.kind || 'ADJ') + '-' + (a.code || i),
      label: a.label || '',
      // 后端 amount 为正数减免;展示统一带负号
      amount: yuan(Math.abs(Number(a.amount)))
    }))
    .filter((a) => a.label);
  const show = discount > 0 && adjustments.length > 0;
  return {
    show,
    listAmount: show ? yuan(quote.listAmount) : null,
    adjustments: show ? adjustments : [],
    coveredByPass: quote.coveredByPass === true,
    rewardCoupon: rewardCouponView(quote.completeRewardCoupon)
  };
}

/** 完局奖励券只读展示:null 整块不显示,不写门槛、不参与应付。 */
function rewardCouponView(coupon) {
  if (!coupon || typeof coupon !== 'object') return null;
  const name = coupon.couponName || coupon.name || coupon.title;
  if (!name) return null;
  return { name: String(name) };
}

/**
 * 下单 409 分两类,都要重新报价,不当网络错误重发同一请求:
 * - 促销指纹不一致 → 'marketing'
 * - 其它价格冲突 → 'price'
 */
const MARKETING_CONFLICT_MSG = '优惠已变化或与本单不一致';
function classifyCreateConflict(res) {
  if (!res || !(res.code == 409)) return null;
  const msg = String(res.msg || '');
  return msg.indexOf(MARKETING_CONFLICT_MSG) >= 0 ? 'marketing' : 'price';
}
const CONFLICT_TEXT = {
  price: '价格已更新，请重新确认',
  marketing: '优惠已变化或与本单不一致，请重新报价后再提交'
};

/**
 * 报价签名变了 ⇒ 下单动作号必须换新(同一请求原样重发才复用 requestId)。
 * 已经建出订单(有 registrationId)时不换:那张单由原 requestId 继续重放/支付。
 */
function shouldRotateRequestId(boundSign, newSign, hasOrder) {
  if (hasOrder) return false;
  if (!boundSign) return false;
  return String(boundSign) !== String(newSign || '');
}

// §17.5 reasonCode 文案:码不可改,文案可改;不解析后端中文。
const PROMO_REASON_TEXT = {
  TRIGGER_FACT_MISSING: '通关后解锁',
  TRIGGER_WINDOW_EXPIRED: '优惠已过期',
  ALREADY_USED: '已使用过',
  MEMBER_LIMIT_REACHED: '已达使用次数上限',
  BELOW_MIN_AMOUNT: '未满使用门槛',
  STACK_GROUP_TAKEN: '已享受更优优惠，本项不叠加',
  EXCLUSIVE_ALREADY_APPLIED: '已享受更优优惠，本项不叠加',
  OFFER_MEMBER_LIMIT: '已达购买次数上限',
  OFFER_ITEMS_MISMATCH: '该优惠与当前票数不匹配',
  OFFER_WINDOW_CLOSED: '优惠已结束'
};

function remainText(endsAt, serverNow) {
  const end = toTimestamp(endsAt);
  const now = toTimestamp(serverNow);
  if (end === null || now === null) return '';
  const ms = end - now;
  if (ms <= 0) return '';
  const hours = Math.floor(ms / 3600000);
  if (hours >= 48) return '还剩 ' + Math.floor(hours / 24) + ' 天';
  if (hours >= 1) return '还剩 ' + hours + ' 小时';
  return '还剩 ' + Math.max(1, Math.ceil(ms / 60000)) + ' 分钟';
}

/**
 * §17.5 促销目录:offers/promotions 都空 → 整块不渲染(不写「暂无优惠」);
 * 剩余时间只用 windowEndsAt - serverNow,不用本机时钟。
 */
function catalogView(data) {
  const out = { show: false, offers: [], promotions: [], tiers: [] };
  if (!data || typeof data !== 'object') return out;
  const serverNow = data.serverNow;
  out.offers = (Array.isArray(data.offers) ? data.offers : []).filter(Boolean).map((o) => ({
    key: 'offer-' + (o.offerCode || o.name),
    name: o.name || '',
    saveText: o.available && isNum(o.differenceAmount) && Number(o.differenceAmount) > 0
      ? ('省 ¥' + yuan(o.differenceAmount)) : '',
    available: o.available === true,
    reason: o.available === true ? '' : (PROMO_REASON_TEXT[o.reasonCode] || '暂不可用')
  })).filter((o) => o.name);
  out.promotions = (Array.isArray(data.promotions) ? data.promotions : []).filter(Boolean).map((p) => ({
    key: 'promo-' + (p.promoCode || p.name),
    name: p.name || '',
    eligible: p.eligible === true,
    remain: p.eligible === true ? remainText(p.windowEndsAt, serverNow) : '',
    reason: p.eligible === true ? '' : (PROMO_REASON_TEXT[p.reasonCode] || '暂不可用')
  })).filter((p) => p.name);
  out.tiers = (Array.isArray(data.tiers) ? data.tiers : []).filter((t) => t && isNum(t.unitPrice)).map((t) => ({
    key: 'tier-' + (t.tierCode || t.id),
    range: tierRange(t.minQuantity, t.maxQuantity),
    unitPrice: yuan(t.unitPrice)
  })).filter((t) => t.range);
  out.show = out.offers.length > 0 || out.promotions.length > 0 || out.tiers.length > 0;
  return out;
}

function tierRange(min, max) {
  const lo = isNum(min) ? Number(min) : null;
  const hi = isNum(max) ? Number(max) : null;
  if (lo && hi) return lo === hi ? (lo + ' 人') : (lo + '–' + hi + ' 人');
  if (lo) return lo + ' 人起';
  return '';
}

module.exports = {
  deliveryRows,
  paymentChannelState,
  marketingView,
  classifyCreateConflict,
  CONFLICT_TEXT,
  shouldRotateRequestId,
  catalogView,
  PROMO_REASON_TEXT,
  remainText
};
