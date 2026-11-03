'use strict'

// Presentation is separate from the server's reward/ownership type.
// Reserved tags are written by the reviewed catalog importer, never inferred from names.
const CATEGORY_LABELS = Object.freeze({
  all: '全部', street: '宝藏街道', shop: '推荐店铺', park: '公园绿地', landmark: '文化地标',
})
function readTags(value) {
  if (Array.isArray(value)) return value
  if (typeof value !== 'string') return []
  try { const tags = JSON.parse(value); return Array.isArray(tags) ? tags : [] } catch (_) { return [] }
}
function presentationForPoi(row) {
  const poi = row || {}
  if (Number(poi.type) === 2) {
    const linked = /^[1-9]\d*$/.test(String(poi.merchantId || ''))
    return { cat: 'merchant', icon: 'poi-shop', catLabel: linked ? '合作商家 · 探店' : '商家据点' }
  }
  const tags = readTags(poi.tags)
  if (tags.includes('roam:park')) return { cat: 'park', icon: 'poi-park', catLabel: CATEGORY_LABELS.park }
  if (tags.includes('roam:street')) return { cat: 'landmark', icon: 'poi-landmark', catLabel: CATEGORY_LABELS.street }
  // A recommended shop remains an official discovery point (type=1), outside shop visits/coupons.
  if (tags.includes('roam:shop')) return { cat: 'landmark', icon: 'poi-shop', catLabel: CATEGORY_LABELS.shop }
  return { cat: 'landmark', icon: 'poi-landmark', catLabel: CATEGORY_LABELS.landmark }
}
module.exports = { CATEGORY_LABELS, readTags, presentationForPoi }
