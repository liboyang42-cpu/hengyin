'use strict'
// BL-70:漫游商家横滑卡的「谁在这儿打过卡」头像堆。
// 只消费 POST /api/roam/shop/visitors(ApiRoamController#shopVisitors)的真值:
// 每个来源一行 { sourceId, avatars[], total };total=0 不展示,失败不阻塞卡片。

// 后端一次最多认 20 个来源(VISITORS_SOURCE_MAX),每张卡最多 5 张脸(VISITORS_AVATAR_MAX)。
const SOURCE_MAX = 20
const FACE_MAX = 5

function positiveId(value) {
  const n = Number(value)
  return Number.isInteger(n) && n > 0 ? n : 0
}

/** 与 _apiShopVisit 同一口径:roam 据点(_roamId)=1,报名商家(regId)=2。 */
function visitorSourceOf(poi) {
  if (!poi) return null
  const roamId = positiveId(poi._roamId)
  if (roamId) return { sourceType: 1, sourceId: roamId }
  const regId = positiveId(poi.regId)
  if (regId) return { sourceType: 2, sourceId: regId }
  return null
}

function sourceKey(sourceType, sourceId) {
  return sourceType + ':' + sourceId
}

/** 按 sourceType 分组成请求体;去重、每组不超过后端上限。 */
function buildVisitorRequests(rows) {
  const groups = {}
  ;(Array.isArray(rows) ? rows : []).forEach((row) => {
    const type = row && row.sourceType
    const id = positiveId(row && row.sourceId)
    if ((type !== 1 && type !== 2) || !id) return
    const list = groups[type] || (groups[type] = [])
    if (list.indexOf(id) < 0 && list.length < SOURCE_MAX) list.push(id)
  })
  return Object.keys(groups).map((type) => ({ sourceType: Number(type), sourceIds: groups[type].join(',') }))
}

/** 回包 → { 'type:id': { faces, total, label } };total<=0 或坏行不进表。 */
function shapeVisitorRows(sourceType, data) {
  const out = {}
  if (!Array.isArray(data)) return out
  data.forEach((row) => {
    if (!row || typeof row !== 'object') return
    const id = positiveId(row.sourceId)
    const total = Number(row.total)
    if (!id || !Number.isInteger(total) || total <= 0) return
    const faces = (Array.isArray(row.avatars) ? row.avatars : [])
      .filter((a) => typeof a === 'string' && /^https?:\/\//.test(a.trim()))
      .map((a) => a.trim())
      .slice(0, FACE_MAX)
    out[sourceKey(sourceType, id)] = { faces: faces, total: total, label: total + ' 人打过卡' }
  })
  return out
}

module.exports = { visitorSourceOf, sourceKey, buildVisitorRequests, shapeVisitorRows, SOURCE_MAX, FACE_MAX }
