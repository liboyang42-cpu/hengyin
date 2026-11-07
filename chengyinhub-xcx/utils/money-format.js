'use strict'
// 金额显示的唯一入口(打磨第 3 轮 §4-9 / §4-20):
//   · 一律两位小数(「¥0」与「¥0.00」不再并存)
//   · 负数写成「−¥15.44」(U+2212 减号在 ¥ 前),不再出现「¥-15.44」
//   · signed:正数带「+」(收入明细行用),零不带符号
// 读不出数字返回 null,由调用方决定显示「待定」「—」还是不显示。

const MINUS = '−'

function amount(value) {
  if (value === null || value === undefined || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

// 两位小数的数字串(保留负号),给需要原值的地方(排序、颜色判断)
function fixed2(value) {
  const n = amount(value)
  return n === null ? null : n.toFixed(2)
}

function yuan(value, options) {
  const n = amount(value)
  if (n === null) return null
  const text = Math.abs(n).toFixed(2)
  if (Number(text) === 0) return '¥' + text
  if (n < 0) return MINUS + '¥' + text
  return (options && options.signed ? '+' : '') + '¥' + text
}

module.exports = { MINUS, amount, fixed2, yuan }
