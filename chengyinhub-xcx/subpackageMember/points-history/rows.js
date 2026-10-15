'use strict'
// 积分明细(POST /api/points/list)行整形与客户端分页。
// 后端该接口不分页、一次返回全部未删除明细(id 倒序),所以分页在端上做。
const PAGE_SIZE = 20

function toInt(value) {
  if (value === null || value === undefined || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? Math.trunc(n) : null
}

function timeText(value) {
  const text = value == null ? '' : String(value).trim()
  const m = text.match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})/)
  return m ? `${m[1]} ${m[2]}` : text
}

function normalizeRow(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const change = toInt(raw.changePoints)
  if (change === null) return null
  // changeType 1=收入 2=支出;数值本身若已带负号则以数值为准
  const delta = raw.changeType != null && Number(raw.changeType) === 2 ? -Math.abs(change) : change
  const after = toInt(raw.afterPoints)
  return {
    id: raw.id == null ? '' : String(raw.id),
    reason: (raw.changeReason == null ? '' : String(raw.changeReason).trim()) || '积分变动',
    delta,
    deltaText: delta > 0 ? `+${delta}` : String(delta),
    income: delta >= 0,
    afterText: after === null ? '' : `余额 ${after}`,
    timeText: timeText(raw.createTime),
  }
}

// 任何一行不合法都当整份数据不可信(返回 null → 页面显示失败态),不静默丢行造成对不上账
function normalizeRows(list) {
  if (!Array.isArray(list)) return null
  const rows = []
  for (let i = 0; i < list.length; i += 1) {
    const row = normalizeRow(list[i])
    if (!row) return null
    rows.push(row)
  }
  return rows
}

module.exports = { PAGE_SIZE, normalizeRows, timeText }
