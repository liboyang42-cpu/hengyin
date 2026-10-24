'use strict'

// 「M月D日」日期标签(C:U-109):漫游结算屏、历史卡片、会话详情原先各自内联拼接。
// 按设备本地时区取月日(与原内联写法一致);非法日期返回调用方给的兜底文案,兜底文案随场景不同,不在这里统一。
const WEEK = ['日', '一', '二', '三', '四', '五', '六']

function validDate(d) {
  return d instanceof Date && !Number.isNaN(d.getTime())
}

function monthDay(d, fallback) {
  return validDate(d) ? (d.getMonth() + 1) + '月' + d.getDate() + '日' : (fallback === undefined ? '' : fallback)
}

function monthDayWeek(d, fallback) {
  return validDate(d) ? monthDay(d) + ' 周' + WEEK[d.getDay()] : (fallback === undefined ? '' : fallback)
}

module.exports = { monthDay, monthDayWeek, WEEK }
