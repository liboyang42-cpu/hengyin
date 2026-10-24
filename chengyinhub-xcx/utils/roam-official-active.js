// BL-41:漫游护照「官方活动」大卡读 GET /api/official/active-for-roam(报名中/进行中且绑定漫游的官方活动)。
// 只用接口已有字段 id/title/coverImg;不区分报名中/进行中的文案,统一「可参与」。
'use strict'

function activeRoamEventCard(list) {
  if (!Array.isArray(list)) return null
  const rows = list.filter((e) => e && e.id != null && e.id !== '' && typeof e.title === 'string' && e.title.trim())
  if (!rows.length) return null
  const first = rows[0]
  const title = first.title.trim()
  return {
    sub: rows.length > 1 ? '可参与：' + title + ' 等 ' + rows.length + ' 场' : '可参与：' + title,
    cover: typeof first.coverImg === 'string' ? first.coverImg.trim() : '',
    // 只有一场时卡片直达详情;多场仍进官方活动列表
    eventId: rows.length === 1 ? String(first.id) : '',
  }
}

function applyEventCard(plays, card) {
  if (!Array.isArray(plays) || !card) return plays
  return plays.map((p) => (p && p.key === 'event'
    ? Object.assign({}, p, { sub: card.sub, cover: card.cover || p.cover })
    : p))
}

module.exports = { activeRoamEventCard, applyEventCard }
