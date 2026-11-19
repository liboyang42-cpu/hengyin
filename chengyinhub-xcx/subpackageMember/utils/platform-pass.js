// l-pass 通行证(平台会员):页面只读服务端事实生成视图;支付编排在这里,页面只管展示。
// - 渠道:安卓 / iOS 上设备支持且服务端可用的虚拟支付(XPAY)才出购买;不支持就给原因,绝不退回普通 JSAPI。Windows / 鸿蒙 / 开发者工具等未验证端不出购买。
// - 「已开通」只认服务端订单状态 PAID;requestVirtualPayment 的成功回调不算已付。
'use strict'

function isNum(v) {
  return v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v))
}

function yuanFen(fen) {
  if (!isNum(fen)) return ''
  const y = Number(fen) / 100
  return '¥' + (Number.isInteger(y) ? String(y) : y.toFixed(2))
}

// OR-026:通行证付款/退款状态统一词表,页面与订单行都从这里取字,不各写各的。
const WORDS = Object.freeze({
  PENDING: '待付款',
  RESUME: '继续付款',
  CONFIRMING: '付款结果确认中',
  PAY_CANCELLED: '已取消本次付款',
  REFUND_REVIEW: '退款审核中',
  REFUNDING: '退款处理中',
  REFUNDED: '已退款',
  REFUND_REJECTED: '退款申请未通过',
  STATE_UNKNOWN: '状态确认中',
  CLOSED: '订单已不可支付',
})

// OR-018:商品名统一;订单名按 planCode 取套餐名。
const PLAN_NAME = Object.freeze({ MONTH: '城瘾30天通行证' })
const PASS_NAME = '城瘾30天通行证'

function planName(code, validDays) {
  if (PLAN_NAME[code]) return PLAN_NAME[code]
  return '城瘾通行证' + (isNum(validDays) ? ' · ' + Number(validDays) + ' 天' : '')
}

function pad(n) { return n < 10 ? '0' + n : String(n) }

function dateText(v) {
  if (v === null || v === undefined || v === '') return ''
  const d = new Date(typeof v === 'string' && /^\d{4}-\d{2}-\d{2} /.test(v) ? v.replace(' ', 'T') + '+08:00' : v)
  if (isNaN(d.getTime())) return ''
  return d.getFullYear() + '.' + pad(d.getMonth() + 1) + '.' + pad(d.getDate())
}

/** 平台:ios / android / devtools / windows / mac …;读不到按 unknown。 */
function platformOf(wxApi) {
  try {
    const info = wxApi && wxApi.getDeviceInfo ? wxApi.getDeviceInfo() : (wxApi && wxApi.getSystemInfoSync ? wxApi.getSystemInfoSync() : null)
    return String((info && info.platform) || 'unknown').toLowerCase()
  } catch (e) {
    return 'unknown'
  }
}

/** 只有 ios / android 且虚拟支付「设备支持 + 服务端可用」才返回 XPAY;其余一律 null(不退回 JSAPI)。 */
function pickChannel(platform, channels, canVirtual) {
  return channelState(platform, channels, canVirtual).channel
}

/** { channel: 'XPAY'|null, reason: 不能开通时给用户看的一句话 } */
function channelState(platform, channels, canVirtual) {
  if (platform !== 'ios' && platform !== 'android') return { channel: null, reason: '当前设备暂不支持开通' }
  if (canVirtual !== true) return { channel: null, reason: '当前微信版本暂不支持开通，请升级后再试' }
  const x = (channels || {}).xpay
  if (!x || x.supported === false || x.available !== true) return { channel: null, reason: '暂未开放开通' }
  return { channel: 'XPAY', reason: '' }
}

function planCard(p, selected) {
  if (!p || !p.planCode || !isNum(p.priceFen)) return null
  const code = String(p.planCode)
  let line = ''
  if (code === 'MONTH') {
    line = '30天·不自动续费'
  } else {
    line = (p.title || '') + (isNum(p.validDays) ? ' · ' + Number(p.validDays) + ' 天' : '')
  }
  // OR-047 按定稿:月卡价标「首发价 ¥39」
  const price = yuanFen(p.priceFen)
  return { code, price: code === 'MONTH' ? '首发价 ' + price : price, line, on: code === selected }
}

/** 年卡按天比月卡便宜才挂「最划算」。 */
function bestCode(plans) {
  let best = null
  let bestRate = Infinity
  ;(plans || []).forEach((p) => {
    if (!p || !isNum(p.priceFen) || !isNum(p.validDays) || Number(p.validDays) <= 0) return
    const rate = Number(p.priceFen) / Number(p.validDays)
    if (rate < bestRate) { bestRate = rate; best = String(p.planCode) }
  })
  return (plans || []).length > 1 ? best : null
}

/**
 * 第一版只卖月卡(业主 2026-11-13):其他套餐即使服务端返回也不出。
 * @param {object} o overview(GET /api/platform-pass) · selected 选中套餐 · platform · canVirtual 本机是否支持虚拟支付
 */
function passView(o, selected, platform, canVirtual) {
  const ov = o || {}
  if (ov.enabled !== true) return { enabled: false }
  const plans = (Array.isArray(ov.plans) ? ov.plans : []).filter((p) => p && p.planCode === 'MONTH')
  const sel = plans.length ? 'MONTH' : ''
  const best = bestCode(plans)
  const cards = plans.map((p) => planCard(p, sel)).filter(Boolean).map((c) => Object.assign(c, { best: c.code === best }))
  const holding = ov.holding || {}
  const active = holding.active === true
  const ch = channelState(platform, ov.channels, canVirtual)
  const channel = ch.channel
  const monthly = plans[0]
  const priceText = monthly ? yuanFen(monthly.priceFen) : ''
  const count = isNum(ov.libraryCount) ? Number(ov.libraryCount) : null
  // D-112:卡面字段全部来自 holding(后端 PlatformPassService.putCardFacts),缺一项就不画那一项
  const total = isNum(holding.daysTotal) && Number(holding.daysTotal) > 0 ? Number(holding.daysTotal) : 0
  const left = total && isNum(holding.daysRemaining) ? Math.min(total, Math.max(0, Number(holding.daysRemaining))) : null
  const card = active ? {
    planLabel: holding.planLabel === '年卡' || holding.planLabel === '月卡' ? holding.planLabel : '',
    progress: left === null ? null : { left, total, pct: Math.round(left / total * 100), text: '剩余 ' + left + ' 天 / 共 ' + total + ' 天' },
    badge: holding.badge === 'EXPIRING' ? '即将到期' : '',
    teammate: holding.teammateBenefit === true ? '同行可用' : ''
  } : { planLabel: '', progress: null, badge: '', teammate: '' }
  return {
    enabled: true,
    card,
    count,
    plans: cards,
    selected: sel,
    active,
    // OR-047:有效期优先用服务端 holding.validToText,缺省回落本地格式化
    untilText: active ? (typeof holding.validToText === 'string' && holding.validToText.trim() ? holding.validToText.trim() : dateText(holding.coverageEnd || holding.validTo)) : '',
    channel,
    buyLabel: !cards.length ? '暂未开售' : (!channel ? ch.reason : (active ? '续买30天 ' : '开通通行证 ') + priceText),
    buyOff: !cards.length || !channel,
    facts: factRows(ov, count, monthly)
  }
}

/** OR-024:退款页须知 —— 只要续费与退款两条,服务端 terms 有就用服务端文本。 */
function termRows(ov) {
  const t = (ov && ov.terms) || {}
  const txt = (v) => (typeof v === 'string' && v.trim() ? v.trim() : '')
  return [{ k: '续费', v: txt(t.renewal) || '不自动续费' }, { k: '退款规则', v: txt(t.refund) || '退款为整单' }]
}

/** 购买前须知:全部来自服务端(overview.terms + 套餐天数 + 可玩主题数),缺哪项就不出哪项。 */
function factRows(ov, count, plan) {
  const t = ov.terms || {}
  const txt = (v) => (typeof v === 'string' && v.trim() ? v.trim() : '')
  const rows = []
  const cover = count !== null ? count + ' 个主题' : txt(t.coverage)
  if (cover) rows.push({ k: '可玩主题', v: cover })
  if (txt(t.seat)) rows.push({ k: '适用范围', v: txt(t.seat) })
  const period = txt(t.period) || (plan && isNum(plan.validDays) ? Number(plan.validDays) + ' 天' : '')
  if (period) rows.push({ k: '有效期', v: period })
  // OR-024:须知固定出「不自动续费、退款为整单」;服务端 terms 下发后以服务端文本为准
  return rows.concat(termRows(ov))
}

const ORDER_STATE = { PENDING: WORDS.PENDING, PAID: '已开通', CANCELLED: '已取消', REFUNDING: WORDS.REFUNDING, REFUNDED: WORDS.REFUNDED }
const REFUND_STATE = { PENDING: WORDS.REFUND_REVIEW, APPROVED: WORDS.REFUNDING, REJECTED: WORDS.REFUND_REJECTED }

/** 订单状态只来自服务端;读不到的状态不编,写「状态确认中」。 */
function orderStateText(order) {
  const st = order && order.status
  return ORDER_STATE[st] || WORDS.STATE_UNKNOWN
}

/** 退款进度:服务端 order.refund.reviewStatus(NONE = 没有申请);没有记录返回空。 */
function refundText(order) {
  const r = order && order.refund
  if (!r || !r.reviewStatus || r.reviewStatus === 'NONE' || order.status === 'REFUNDED') return ''
  return REFUND_STATE[r.reviewStatus] || WORDS.STATE_UNKNOWN
}

/** OR-048:已退款给金额与时间;未通过给原因与复核路径。字段来自服务端 order.refund。 */
function refundDetail(order) {
  const r = (order && order.refund) || {}
  if (isNum(r.refundedFen) && (order.status === 'REFUNDED' || r.reviewStatus === 'APPROVED')) {
    return { kind: 'refunded', amount: yuanFen(r.refundedFen), time: dateText(r.decidedTime) }
  }
  if (r.reviewStatus === 'REJECTED') {
    const reason = typeof r.decision === 'string' ? r.decision.trim() : ''
    return { kind: 'rejected', reason, time: dateText(r.decidedTime) }
  }
  return null
}

/** 通行证订单行(我的订单 / 订单详情共用)。 */
function orderRow(o) {
  if (!o || !o.orderNo) return null
  return {
    key: String(o.orderNo),
    name: planName(o.planCode, o.validDays),
    // OR-017:副标题写套餐与日期,订单号只在详情页「复制订单号」
    sub: [isNum(o.validDays) ? Number(o.validDays) + '天' : '', dateText(o.paidTime || o.createdTime)].filter(Boolean).join(' · '),
    refundDetail: refundDetail(o),
    state: orderStateText(o),
    refund: refundText(o),
    amount: yuanFen(o.priceFen),
    time: dateText(o.paidTime || o.createdTime),
    status: String(o.status || ''),
    canResume: o.status === 'PENDING',
    canCancel: o.status === 'PENDING',
    canRefund: o.status === 'PAID' && (!o.refund || !o.refund.reviewStatus || o.refund.reviewStatus === 'NONE' || o.refund.reviewStatus === 'REJECTED'),
    validTo: o.status === 'PAID' ? dateText(o.validTo) : ''
  }
}

function libraryRow(r) {
  if (!r || !isNum(r.topicId) || !r.name) return null
  const type = Number(r.productType) === 2 ? '自由探索' : (Number(r.productType) === 1 ? '城市定向' : '')
  const data = []
  if (type) data.push(type)
  if (isNum(r.totalTime) && Number(r.totalTime) > 0) data.push('约 ' + Math.max(1, Math.round(Number(r.totalTime) / 60)) + ' 分钟')
  const tags = []
  if (type) tags.push(type)
  if (r.addressName) tags.push(String(r.addressName))
  return { id: String(r.topicId), name: String(r.name), cover: r.imgUrl || '', data: data.join(' · '), tags }
}

module.exports = { WORDS, PASS_NAME, planName, refundDetail, termRows, channelState, orderRow, orderStateText, refundText, pickChannel, passView, planCard, bestCode, libraryRow, dateText, yuanFen, platformOf }
