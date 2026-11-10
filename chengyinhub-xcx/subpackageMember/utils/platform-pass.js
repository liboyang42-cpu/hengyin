// l-pass 通行证(平台会员):页面只读服务端事实生成视图;支付编排在这里,页面只管展示。
// - 渠道:iOS 只能走小程序虚拟支付(XPAY);安卓 / 开发者工具走 JSAPI。服务端说不可用的渠道一律不出按钮。
// - 「已开通」只认服务端订单状态 PAID;wx.requestPayment / requestVirtualPayment 的成功回调不算已付。
'use strict'

function isNum(v) {
  return v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v))
}

function yuanFen(fen) {
  if (!isNum(fen)) return ''
  const y = Number(fen) / 100
  return '¥' + (Number.isInteger(y) ? String(y) : y.toFixed(2))
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

/** iOS → XPAY;其他 → JSAPI。服务端渠道不可用返回 null(按钮置灰)。 */
function pickChannel(platform, channels) {
  const c = channels || {}
  const avail = (k) => !!(c[k] && c[k].available === true)
  if (platform === 'ios') return avail('xpay') ? 'XPAY' : null
  return avail('jsapi') ? 'JSAPI' : null
}

function planCard(p, selected) {
  if (!p || !p.planCode || !isNum(p.priceFen)) return null
  const code = String(p.planCode)
  let line = ''
  if (code === 'YEAR' && isNum(p.validDays) && Number(p.validDays) >= 360) {
    line = '年卡 · 每月不到 ' + yuanFen(Math.ceil(Number(p.priceFen) / 12 / 100) * 100)
  } else if (code === 'MONTH') {
    line = '月卡 · 随时停'
  } else {
    line = (p.title || '') + (isNum(p.validDays) ? ' · ' + Number(p.validDays) + ' 天' : '')
  }
  return { code, price: yuanFen(p.priceFen), line, on: code === selected }
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
 * @param {object} o overview(GET /api/platform-pass) · selected 选中套餐 · platform
 */
function passView(o, selected, platform) {
  const ov = o || {}
  if (ov.enabled !== true) return { enabled: false }
  const plans = Array.isArray(ov.plans) ? ov.plans : []
  const sel = selected && plans.some((p) => p && p.planCode === selected) ? selected
    : (plans.some((p) => p && p.planCode === 'YEAR') ? 'YEAR' : (plans[0] && plans[0].planCode) || '')
  const best = bestCode(plans)
  const cards = plans.map((p) => planCard(p, sel)).filter(Boolean).map((c) => Object.assign(c, { best: c.code === best }))
  const holding = ov.holding || {}
  const active = holding.active === true
  const channel = pickChannel(platform, ov.channels)
  const count = isNum(ov.libraryCount) ? Number(ov.libraryCount) : null
  return {
    enabled: true,
    count,
    plans: cards,
    selected: sel,
    active,
    untilText: active ? dateText(holding.coverageEnd || holding.validTo) : '',
    channel,
    buyLabel: !cards.length ? '暂未开售' : (!channel ? '当前设备暂不可开通' : (active ? '续期通行证' : '开通通行证')),
    buyOff: !cards.length || !channel,
    terms: cards.length ? '开通后期间内可玩主题价格为 ¥0 · 不自动续费' : ''
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

module.exports = { pickChannel, passView, planCard, bestCode, libraryRow, dateText, yuanFen, platformOf }
