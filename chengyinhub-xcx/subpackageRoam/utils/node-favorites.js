'use strict'
// 我收藏的据点(GET /api/city/nodes/favorites)的数据整形。
// 字段只取 CityNodeVO 已有的:poiId/name/coverImg/merchantName/merchantAddress/completed。
// 接口不带坐标参数,distance 恒为空,这里不展示距离。

function text(value) {
  return value == null ? '' : String(value).trim()
}

function normalizeFavoriteNode(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const poiId = text(raw.poiId)
  const name = text(raw.name)
  if (!poiId || !name) return null
  const meta = [text(raw.merchantName), text(raw.merchantAddress)].filter(Boolean)
  return {
    poiId,
    name,
    coverImg: text(raw.coverImg),
    metaText: meta.join(' · '),
    completed: raw.completed === true,
  }
}

function normalizeFavoriteNodes(list) {
  if (!Array.isArray(list)) return null
  const seen = {}
  return list.map(normalizeFavoriteNode).filter((n) => {
    if (!n || seen[n.poiId]) return false
    seen[n.poiId] = true
    return true
  })
}

module.exports = { normalizeFavoriteNode, normalizeFavoriteNodes }
