'use strict'
// 官方路线(GET /api/preset/route/list、/route/info)的数据整形。
// 字段单位以库注释为准:totalDistance=公里,totalTime/nodeTime=分钟。

function text(value) {
  return value == null ? '' : String(value).trim()
}

function positive(value) {
  const n = Number(value)
  return Number.isFinite(n) && n > 0 ? n : 0
}

function distanceText(km) {
  const n = positive(km)
  if (!n) return ''
  return `${Number.isInteger(n) ? n : n.toFixed(1)} km`
}

function durationText(minutes) {
  const n = Math.round(positive(minutes))
  if (!n) return ''
  if (n < 60) return `${n} 分钟`
  const h = Math.floor(n / 60)
  const m = n % 60
  return m ? `${h} 小时 ${m} 分` : `${h} 小时`
}

function normalizeRoute(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const id = text(raw.id)
  const name = text(raw.name)
  if (!id || !name) return null
  const meta = [text(raw.city), distanceText(raw.totalDistance), durationText(raw.totalTime)].filter(Boolean)
  return {
    id,
    name,
    city: text(raw.city),
    previewImg: text(raw.previewImg),
    metaText: meta.join(' · '),
  }
}

function normalizeRouteList(list) {
  if (!Array.isArray(list)) return null
  return list.map(normalizeRoute).filter(Boolean)
}

function normalizeNode(raw, index) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const name = text(raw.name)
  if (!name) return null
  const lat = Number(raw.latitude)
  const lng = Number(raw.longitude)
  const hasPoint = text(raw.latitude) !== '' && text(raw.longitude) !== ''
    && Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180
  return {
    key: text(raw.id) || `n${index}`,
    order: index + 1,
    name,
    address: text(raw.address),
    imgUrl: text(raw.imgUrl),
    stayText: durationText(raw.nodeTime),
    lat: hasPoint ? lat : null,
    lng: hasPoint ? lng : null,
  }
}

function normalizeRouteDetail(raw) {
  const base = normalizeRoute(raw)
  if (!base) return null
  const nodes = (Array.isArray(raw.nodes) ? raw.nodes : [])
    .map(normalizeNode).filter(Boolean)
    .map((node, i) => Object.assign(node, { order: i + 1 }))
  return Object.assign(base, { nodes })
}

module.exports = { distanceText, durationText, normalizeRoute, normalizeRouteList, normalizeRouteDetail }
