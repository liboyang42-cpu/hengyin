// S3a 商家套餐(小程序侧):把 POST /api/merchant/plan 的服务端事实转成页面状态。
// 文案与 App 套餐页(Resources/MerchantPlans.xcstrings)一致;顺序同 App:本店当前套餐 → 各套餐 → 说明。
// 只有服务端说正在开售(salesState === 'OPEN',资金开关 merchantMoney 开)时才出现购买按钮;否则写「即将开放」。
const { yuan } = require('../../../utils/money-format.js');

const COPY = {
  title: '套餐',
  entryMeta: '当前套餐与各套餐包含的内容',
  currentTitle: '本店',
  currentPlan: '当前套餐',
  until: '有效期至',
  badgeCurrent: '本店当前套餐',
  proofDetail: '每周到店报告',
  included: '完整明细',
  basic: '汇总',
  nodeGames: '额外地图点位游戏',
  dealsOffers: '每月可上优惠图层的优惠',
  free: '免费',
  footer: '套餐内容以平台当前设置为准。',
  soon: '即将开放',
  soonDesc: '套餐购买即将开放，现在可以先看看各套餐包含的内容。',
  buy: '开通',
  ownerOnly: '只有店主可以开通套餐',
  ordered: '已提交开通申请',
  orderedDesc: '平台确认收款后为本店开通，订单号',
};
const CODES = ['FREE', 'BASIC', 'PRO', 'PER_VISIT'];

function count(value) {
  const n = Number(value);
  return Number.isInteger(n) && n >= 0 && n <= 100 ? n : 0;
}

function priceText(plan) {
  const visit = Number(plan.visitFeeFen);
  if (Number.isFinite(visit) && visit > 0) return '每次核验到店 ' + yuan(visit / 100);
  const monthly = Number(plan.monthlyPriceFen);
  if (!Number.isFinite(monthly) || monthly <= 0) return COPY.free;
  return yuan(monthly / 100) + ' / 月';
}

function shapePlan(raw, currentCode) {
  if (!raw || typeof raw !== 'object' || CODES.indexOf(raw.code) < 0) return null;
  const f = raw.features && typeof raw.features === 'object' ? raw.features : {};
  const monthly = Number(raw.monthlyPriceFen);
  const name = typeof raw.nameZh === 'string' && raw.nameZh.trim() ? raw.nameZh.trim() : raw.code;
  return {
    code: raw.code,
    name,
    priceText: priceText(raw),
    current: raw.code === currentCode,
    // 只有按月付费的套餐有自助开通;免费、按次计费的不卖
    buyable: Number.isFinite(monthly) && monthly > 0 && !(Number(raw.visitFeeFen) > 0),
    features: [
      { key: 'proofDetail', label: COPY.proofDetail, value: f.weeklyProofDetail === true ? COPY.included : COPY.basic },
      { key: 'nodeGames', label: COPY.nodeGames, value: String(count(f.extraNodeGames)) },
      { key: 'dealsOffers', label: COPY.dealsOffers, value: String(count(f.dealsOffersPerMonth)) },
    ],
  };
}

function dayText(value) {
  if (typeof value !== 'string') return '';
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(value);
  return m ? m[1] + '年' + Number(m[2]) + '月' + Number(m[3]) + '日' : '';
}

// 返回 null = 服务端回包不可信,页面给错误态。
function shapePage(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data) || !Array.isArray(data.plans)) return null;
  const current = data.current && typeof data.current === 'object' ? data.current : {};
  const currentCode = CODES.indexOf(current.code) >= 0 ? current.code : 'FREE';
  const plans = data.plans.map(p => shapePlan(p, currentCode)).filter(Boolean);
  const currentPlan = shapePlan(current.plan, currentCode);
  const salesOpen = data.salesState === 'OPEN' && data.salesOpen === true;
  return {
    plans,
    currentCode,
    currentName: currentPlan ? currentPlan.name : (plans.filter(p => p.current)[0] || {}).name || currentCode,
    periodEnd: dayText(current.periodEnd),
    salesOpen,
  };
}

// 购买幂等号:同一次点击重试用同一个号,服务端按 (店, 号) 去重。
function newRequestId(now, random) {
  const t = Number(now || Date.now()).toString(36);
  let r = '';
  const rnd = random || Math.random;
  while (r.length < 16) r += Math.floor(rnd() * 36).toString(36);
  return ('mp_' + t + '_' + r).slice(0, 64);
}

function shapeOrder(data) {
  if (!data || typeof data !== 'object' || typeof data.orderNo !== 'string' || !data.orderNo) return null;
  return { orderNo: data.orderNo, planCode: data.planCode, status: data.status };
}

module.exports = { COPY, shapePage, shapePlan, priceText, dayText, newRequestId, shapeOrder };
