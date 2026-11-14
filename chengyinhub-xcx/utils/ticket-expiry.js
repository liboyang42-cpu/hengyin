'use strict'
// V-02(2026-10-29 业主):入场码下方只在票 7 天内到期时写「N 天内过期」，当天写「今天过期」，其余什么都不写。
// 票夹背面与场景入场码组件共用这一条规则。
const { toTimestamp, chinaDayStart } = require('./datetime.js')
const DAY_MS = 86400000
const WINDOW_DAYS = 7

// 全部按中国时区的自然日算(utils/datetime.js),不随手机时区偏移
function toStamp(value) {
  if (value === null || value === undefined || value === '') return null
  if (typeof value === 'number') return Number.isFinite(value) ? (value < 100000000000 ? value * 1000 : value) : null
  const at = toTimestamp(value)
  return isNaN(at) ? null : at
}

// 还剩几个自然日(今天到期 = 0;已过期 / 没有到期日 = null)。票夹卡面「剩 N 天」与背面「N 天内过期」同一个数。
function passDaysLeft(until, now) {
  const at = toStamp(until)
  if (at === null) return null
  const nowMs = typeof now === 'number' ? now : Date.now()
  if (at < nowMs) return null
  // 到期落在零点(下一天 00:00)= 能玩的最后一天是前一天,与「可玩至 M 月 D 日」同一个日期,否则卡面比购买页多一天
  const last = chinaDayStart(at) === at ? at - 1 : at
  return Math.round((chinaDayStart(last) - chinaDayStart(nowMs)) / DAY_MS)
}

function passExpiryText(until, now) {
  const days = passDaysLeft(until, now)
  if (days === null) return ''
  if (days <= 0) return '今天过期'
  return days <= WINDOW_DAYS ? days + ' 天内过期' : ''
}

module.exports = { passExpiryText, passDaysLeft }
