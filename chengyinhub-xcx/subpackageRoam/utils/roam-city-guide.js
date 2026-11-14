'use strict'

// 入口地图「本城推荐」:同一份目录(roam-city-catalog),不需要定位。
// 城市中心只用来判断「地图拖到了哪座城」,取各城市中心的近似 GCJ-02 坐标,不是地点坐标。
const catalog = require('./roam-city-catalog.js')
const { CATEGORY_LABELS } = require('../../utils/roam-poi-presentation.js')

const CENTERS = {
  SH: [31.2304, 121.4737], BJ: [39.9042, 116.4074], HZ: [30.2741, 120.1551], CD: [30.5728, 104.0668],
  XA: [34.3416, 108.9398], CQ: [29.563, 106.5516], SZ: [22.5431, 114.0579], GZ: [23.1291, 113.2644],
  WH: [30.5928, 114.3055], HK: [22.3193, 114.1694], XM: [24.4798, 118.0894],
}
const NEAR_CITY_M = 60000

function metres(a, b) {
  const rad = Math.PI / 180
  const dLat = (b[0] - a[0]) * rad
  const dLng = (b[1] - a[1]) * rad
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a[0] * rad) * Math.cos(b[0] * rad) * Math.sin(dLng / 2) ** 2
  return 12742000 * Math.asin(Math.min(1, Math.sqrt(h)))
}
/** 距 center 最近、且在 60 公里内的目录城市 id;没有返回 ''。 */
function nearestCity(center) {
  const lat = Number(center && (center.lat != null ? center.lat : center.latitude))
  const lng = Number(center && (center.lng != null ? center.lng : center.longitude))
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return ''
  let best = ''
  let bestD = NEAR_CITY_M
  Object.keys(CENTERS).forEach((id) => {
    const d = metres([lat, lng], CENTERS[id])
    if (d <= bestD) { best = id; bestD = d }
  })
  return best
}
/** 一座城的推荐条:{ city, name, count, items[] };没有这座城或没有地点返回 null。 */
function stripFor(cityId) {
  const city = catalog.cities.find((row) => row.id === cityId)
  if (!city || !city.count) return null
  const items = catalog.listPlaces(cityId, 'all').map((place) => ({
    id: place.id, name: place.name, catLabel: CATEGORY_LABELS[place.category] || '', address: place.address,
    reason: place.reason, sources: place.sources.slice(),
    // 目录点还没有坐标;有坐标的服务端行靠种子标签对上(roam:seed:v1:<id>)
    seed: 'roam:seed:v1:' + place.id,
  }))
  return { city: city.id, name: city.name, count: city.count, items }
}
/** 没有定位、没有可对上的城市时的默认:第一座有地点的目录城市。 */
function defaultCity() {
  const first = catalog.cities.find((row) => row.count > 0)
  return first ? first.id : ''
}
module.exports = { nearestCity, stripFor, defaultCity, CENTERS }
