'use strict'
// /api/withdrawal/summary 的只读视图：当前提现渠道、服务端可提现额、同意版本与本人最近申请。
// 任何金额坏值都整体判失败，不把未知金额渲染成确定的 ¥0.00。

const CHANNELS = {
  CUSTOMER_SERVICE: {
    text: '平台客服人工处理',
    sub: '小程序内暂不开放自助提现表单；点「提现」添加客服微信，核对金额后线下处理',
  },
}

const STATUS = {
  0: { text: '待审核', variant: 'warn' },
  1: { text: '审核通过，待打款', variant: 'warn' },
  2: { text: '已驳回，金额已退回余额', variant: 'danger' },
  3: { text: '已打款', variant: 'success' },
}

function cents(value) {
  if (value === null || value === undefined || !/^\d+(\.\d{1,2})?$/.test(String(value))) return null
  const amount = Math.round(Number(value) * 100)
  return Number.isSafeInteger(amount) ? amount : null
}

function amountText(value) { return '¥' + (value / 100).toFixed(2) }

function versionText(value) {
  return typeof value === 'string' && value.trim() ? value.trim() : ''
}

function latestView(row) {
  if (row === null || row === undefined) return null
  const id = Number(row.id)
  const amount = cents(row.withdrawalAmount)
  if (!Number.isSafeInteger(id) || id <= 0 || amount === null) return undefined
  const status = STATUS[row.status] || { text: '状态待确认', variant: 'neutral' }
  const consentVersion = versionText(row.consentVersion)
  return {
    id,
    amountText: amountText(amount),
    statusText: status.text,
    statusVariant: status.variant,
    accountText: typeof row.bankAccountMask === 'string' && row.bankAccountMask ? row.bankAccountMask : '',
    consentText: consentVersion ? '申请时授权文本 ' + consentVersion : '申请时未绑定授权版本',
    timeText: typeof row.createTime === 'string' ? row.createTime.slice(0, 16) : '',
  }
}

function summaryView(response) {
  if (!response || ![200, '200'].includes(response.code)) return null
  const data = response.data
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null
  const channel = Object.prototype.hasOwnProperty.call(CHANNELS, data.channel) ? CHANNELS[data.channel] : null
  const available = cents(data.available)
  const frozen = cents(data.frozen)
  if (!channel || available === null || frozen === null) return null
  const latest = latestView(data.latest)
  if (latest === undefined) return null
  const consentVersion = versionText(data.consentVersion)
  return {
    channel: data.channel,
    channelText: channel.text,
    channelSub: channel.sub,
    legacyFormOpen: data.legacyFormOpen === true,
    available: available / 100,
    availableText: amountText(available),
    frozenText: frozen > 0 ? '另有 ' + amountText(frozen) + ' 暂不可提现' : '',
    frozenNote: frozen > 0 && typeof data.frozenNote === 'string' ? data.frozenNote : '',
    consentText: consentVersion
      ? '银行卡信息授权文本 ' + consentVersion + (data.consentAgreed === true ? ' · 已同意' : ' · 未同意')
      : '',
    latest,
  }
}

module.exports = { summaryView, STATUS }
