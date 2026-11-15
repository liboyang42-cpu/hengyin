// K1 俱乐部付费会员与会费:把 /api/club/membership/overview 的服务端事实转成页面状态。
// 能不能在线缴费只认服务端给的 duesCollection.state 和资金开关「门票」(tickets);端上不推断资金结果。
const STATUS_TEXT = {
  FREE: '普通成员',
  PENDING: '待生效',
  ACTIVE: '会员有效',
  GRACE: '宽限期',
  PAST_DUE: '已逾期',
  EXPIRED: '已到期',
  CANCELLED: '已取消',
  REFUNDED: '已退款',
};

const INVOICE_TEXT = {
  CREATED: '待支付',
  PAYING: '待支付',
  PAID: '已支付',
  CLOSED: '已关闭',
};

// 不能缴费时说给成员听的话。owner=true 时给主理人看「去开通收款」的说法。
function blockedText(state, owner) {
  if (state === 'NOT_BOUND') return owner ? '俱乐部还没开通收款账户，开通后成员才能在线缴费' : '俱乐部还没开通在线收款，请联系主理人';
  if (state === 'WAIT_FOR_PLATFORM') return '在线缴费即将开放';
  if (state === 'UNAVAILABLE') return owner ? '会费暂时不能在线收取，请联系平台' : '会费暂时不能在线支付，请联系主理人';
  return '';
}

function yuan(fen) {
  const n = Number(fen);
  if (!Number.isFinite(n) || n <= 0) return '';
  return (n / 100).toFixed(2).replace(/\.00$/, '');
}

function money(fen) {
  const y = yuan(fen);
  return y ? '¥' + y : '—';
}

function day(value) {
  if (!value) return '';
  const d = new Date(value);
  if (isNaN(d.getTime())) return '';
  const pad = (x) => (x < 10 ? '0' + x : '' + x);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
}

function benefits(raw) {
  if (Array.isArray(raw)) return raw.filter((b) => typeof b === 'string' && b.trim()).map((b) => b.trim());
  if (typeof raw !== 'string' || !raw.trim()) return [];
  try { return benefits(JSON.parse(raw)); } catch (e) { return []; }
}

function shapePlan(plan) {
  if (!plan || typeof plan !== 'object') return null;
  const id = Number(plan.id);
  if (!Number.isInteger(id) || id <= 0) return null;
  return {
    id,
    name: typeof plan.name === 'string' ? plan.name : '',
    priceText: money(plan.priceFen),
    durationText: Number(plan.durationDays) > 0 ? Number(plan.durationDays) + ' 天' : '',
    benefits: benefits(plan.benefitsJson || plan.benefits),
    refundText: plan.refundPolicy === 'MANUAL_REVIEW' ? '可申请退款审核' : '不退款',
    active: plan.status !== 'INACTIVE',
    raw: plan,
  };
}

// canPay:资金开关 tickets 打开、俱乐部开了会费、服务端说可以收(AVAILABLE 或 SPLIT)。
function shapeOverview(data, moneyOpen, owner) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
  const dues = data.duesCollection && typeof data.duesCollection === 'object' ? data.duesCollection : {};
  const duesState = typeof dues.state === 'string' ? dues.state : 'AVAILABLE';
  const collectible = duesState === 'AVAILABLE' || duesState === 'SPLIT';
  const enabled = data.clubMembershipEnabled === true;
  const status = typeof data.effectiveStatus === 'string' ? data.effectiveStatus : 'FREE';
  const term = data.term && typeof data.term === 'object' ? data.term : null;
  const plans = (Array.isArray(data.plans) ? data.plans : []).map(shapePlan).filter((p) => p && p.active);
  const invoices = (Array.isArray(data.invoices) ? data.invoices : []).map((inv) => ({
    id: Number(inv && inv.id),
    planName: inv && typeof inv.planNameSnapshot === 'string' ? inv.planNameSnapshot : '',
    amountText: money(inv && inv.amountFen),
    statusText: INVOICE_TEXT[inv && inv.status] || '处理中',
    resumable: !!inv && (inv.status === 'PAYING' || inv.status === 'CREATED'),
    version: inv && Number.isInteger(inv.version) ? inv.version : null,
  })).filter((inv) => Number.isInteger(inv.id) && inv.id > 0);
  let blocked = '';
  if (!moneyOpen) blocked = '在线缴费即将开放';
  else if (!collectible) blocked = blockedText(duesState, owner);
  return {
    enabled,
    statusText: STATUS_TEXT[status] || '普通成员',
    statusTone: data.hasEntitlement === true ? 'success' : 'neutral',
    untilText: term ? day(term.endsAt) : '',
    benefits: term ? benefits(term.benefitsSnapshot) : [],
    plans,
    invoices,
    canPay: moneyOpen && enabled && collectible,
    blockedText: blocked,
    renewLabel: data.hasEntitlement === true ? '续费' : '加入',
    duesState,
  };
}

// 主理人设置页:收款状态 → 文案 + 下一步。
function shapeCollection(data, moneyOpen) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
  const state = typeof data.state === 'string' ? data.state : 'UNAVAILABLE';
  const ready = moneyOpen && (state === 'AVAILABLE' || state === 'SPLIT');
  return {
    state,
    ready,
    stateText: ready ? '可以在线收会费' : (moneyOpen ? (blockedText(state, true) || '会费暂时不能在线收取') : '在线收会费即将开放'),
    stateTone: ready ? 'success' : 'warning',
    needsBinding: state === 'NOT_BOUND',
  };
}

const ERROR_TEXT = {
  'CLUB.DUES_COLLECTION_UNAVAILABLE': '俱乐部会费暂时不能在线支付，请联系俱乐部主理人',
};

function errorText(msg, fallback) {
  if (typeof msg === 'string' && ERROR_TEXT[msg]) return ERROR_TEXT[msg];
  if (typeof msg === 'string' && msg && !/^[A-Z0-9_.]+$/.test(msg)) return msg;
  return fallback;
}

// 主理人编辑会员方案:表单 → 后端 plan/save 的请求体。不合法返回 { error }。
// 宽限期与逾期天数先按 0(到期即止),退出即结束权益;只开放名称、价格、天数、权益、能否申请退款与是否上架。
function planRequest(form, clubId, requestId) {
  const f = form || {};
  const name = typeof f.name === 'string' ? f.name.trim() : '';
  if (!name || name.length > 40) return { error: '方案名称需为 1-40 字' };
  const price = Math.round(Number(f.priceYuan) * 100);
  if (!Number.isFinite(price) || price < 1 || price > 100000000) return { error: '请填写正确的价格' };
  const days = Number(f.durationDays);
  if (!Number.isInteger(days) || days < 1 || days > 3650) return { error: '有效天数需为 1-3650 天' };
  const benefits = String(f.benefitsText || '').split('\n').map((b) => b.trim()).filter(Boolean);
  if (!benefits.length || benefits.length > 12 || benefits.some((b) => b.length > 80)) return { error: '权益需填写 1-12 项，每项 80 字以内' };
  const body = {
    clubId, name, priceFen: price, durationDays: days, benefits,
    graceDays: 0, pastDueDays: 0, exitPolicy: 'END_IMMEDIATELY',
    refundPolicy: f.refundReview ? 'MANUAL_REVIEW' : 'NON_REFUNDABLE',
    status: f.active === false ? 'INACTIVE' : 'ACTIVE',
    requestId,
  };
  if (Number.isInteger(f.id) && f.id > 0) { body.id = f.id; body.version = Number.isInteger(f.version) ? f.version : 0; }
  return { body };
}

// plans/manage 的一行 → 编辑表单初值。
function planForm(plan) {
  if (!plan || typeof plan !== 'object') return { name: '', priceYuan: '', durationDays: 365, benefitsText: '', refundReview: false, active: true };
  return {
    id: Number(plan.id),
    version: Number.isInteger(plan.version) ? plan.version : 0,
    name: typeof plan.name === 'string' ? plan.name : '',
    priceYuan: yuan(plan.priceFen),
    durationDays: Number(plan.durationDays) || 365,
    benefitsText: benefits(plan.benefitsJson || plan.benefits).join('\n'),
    refundReview: plan.refundPolicy === 'MANUAL_REVIEW',
    active: plan.status !== 'INACTIVE',
  };
}

module.exports = { shapeOverview, shapePlan, shapeCollection, planRequest, planForm, errorText, blockedText, yuan, STATUS_TEXT };
