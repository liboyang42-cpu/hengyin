'use strict'
// BL-69:商家公开页「收藏据点」。只认 GET /api/city/nodes/merchant/{merchantId} 与
// POST /api/city/nodes/{id}/favorite 的真值字段(CityNodeVO.poiId/favorited、切换返回 favorited)。

function isPlainObject(value) {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

/** 在架节点 → { poiId, name, favorited };无节点/坏数据返回 null(整块不展示)。 */
function shapeMerchantNode(data) {
  if (!isPlainObject(data)) return null
  const poiId = Number(data.poiId)
  if (!Number.isInteger(poiId) || poiId <= 0) return null
  return {
    poiId: poiId,
    name: typeof data.name === 'string' ? data.name : '',
    favorited: data.favorited === true
  }
}

/** 切换接口回读:只有 code 200 且 favorited 为布尔才算成功,否则返回 null。 */
function readFavoriteToggle(res) {
  if (!res || (res.code !== 200 && res.code !== '200')) return null
  const data = res.data
  if (!isPlainObject(data) || typeof data.favorited !== 'boolean') return null
  return data.favorited
}

module.exports = { shapeMerchantNode, readFavoriteToggle }
