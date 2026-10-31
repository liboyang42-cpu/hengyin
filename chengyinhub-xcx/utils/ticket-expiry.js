'use strict'
// V-02(2026-10-29 业主):入场码下方只在票 7 天内到期时写「N 天内过期」，当天写「今天过期」，其余什么都不写。
// 票夹背面与场景入场码组件共用这一条规则。
const DAY_MS = 86400000
const WINDOW_DAYS = 7

function toDate(value) {
  if (value === null || value === undefined || value === '') return null
  if (typeof value === 'number') return Number.isFinite(value) ? new Date(value < 100000000000 ? value * 1000 : value) : null
  const date = new Date(String(value).replace(/-/g, '/'))
  return isNaN(date.getTime()) ? null : date
}

function dayStart(date) { return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime() }

// 还剩几个自然日(今天到期 = 0;已过期 / 没有到期日 = null)。票夹卡面「剩 N 天」与背面「N 天内过期」同一个数。
function passDaysLeft(until, now) {
  const date = toDate(until)
  if (!date) return null
  const today = new Date(typeof now === 'number' ? now : Date.now())
  if (date.getTime() < today.getTime()) return null
  return Math.round((dayStart(date) - dayStart(today)) / DAY_MS)
}

function passExpiryText(until, now) {
  const days = passDaysLeft(until, now)
  if (days === null) return ''
  if (days <= 0) return '今天过期'
  return days <= WINDOW_DAYS ? days + ' 天内过期' : ''
}

module.exports = { passExpiryText, passDaysLeft }
