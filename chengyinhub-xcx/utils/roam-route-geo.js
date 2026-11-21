'use strict'

const { limitTrack, normalizeTrack } = require('./roam-track-simplify.js')

// V3-40:漫游记录卡的真实地图数据(原生 map 的 include-points / polyline / circles)。
// 只用这次自己记下的轨迹与途经点;不足两个有效点返回 null,由卡片退回示意图,不编造位置。
const MAX_POINTS = 300
const LINE = '#8C88FF' /* ds-ok P-7 轨迹亮紫,与示意图同色 */
const INK = '#15171C' /* ds-ok P-7 深色地图卡底 */
const WHITE = '#FFFFFF' /* ds-ok P-7 起终点白 */
const TONES = ['#8C88FF', '#B7BCC8', '#8C91A0', '#FFFFFF'] /* ds-ok P-7 店铺四色 */

function valid(p) {
  return p && Number.isFinite(Number(p.lat)) && Number.isFinite(Number(p.lng))
    && Math.abs(Number(p.lat)) <= 90 && Math.abs(Number(p.lng)) <= 180
}
function toPoint(p) { return { latitude: Number(p.lat), longitude: Number(p.lng) } }
function thin(points) {
  if (points.length <= MAX_POINTS) return points
  const step = (points.length - 1) / (MAX_POINTS - 1)
  const out = []
  for (let i = 0; i < MAX_POINTS; i++) out.push(points[Math.round(i * step)])
  return out
}
function spanMeters(points) {
  let a = 90; let b = -90; let c = 180; let d = -180
  points.forEach((p) => { a = Math.min(a, p.latitude); b = Math.max(b, p.latitude); c = Math.min(c, p.longitude); d = Math.max(d, p.longitude) })
  const kx = Math.cos(((a + b) / 2) * Math.PI / 180)
  return Math.max((b - a) * 111320, (d - c) * 111320 * kx)
}
// 先按范围给一个能框住全程的缩放级(卡片地图约 300px 宽,四周留白),include-points 渲染后再精确框住
function fitScale(span, lat) {
  const mpp = Math.max(1, span) * 1.6 / 300
  const z = Math.floor(Math.log2(156543 * Math.cos(lat * Math.PI / 180) / mpp))
  return Math.max(3, Math.min(18, z))
}
function routeGeo(track, pois) {
  const pts = thin((Array.isArray(track) ? track : []).filter(valid).map(toPoint))
  if (pts.length < 2) return null
  const stops = (Array.isArray(pois) ? pois : []).filter(valid).map(toPoint)
  const all = pts.concat(stops)
  const span = spanMeters(all)
  // 圆点按米给半径:跟轨迹范围成比例,卡片上看起来大小一致
  const r = Math.max(6, Math.round(span * 0.018))
  const start = pts[0]; const end = pts[pts.length - 1]
  const circles = stops.map((p, i) => Object.assign({ radius: r, fillColor: TONES[i % 4], color: INK, strokeWidth: 2 }, p))
    .concat([
      Object.assign({ radius: r, fillColor: INK, color: WHITE, strokeWidth: 3 }, start),
      Object.assign({ radius: r, fillColor: WHITE, color: INK, strokeWidth: 2 }, end),
    ])
  let a = 90; let b = -90; let c = 180; let d = -180
  all.forEach((p) => { a = Math.min(a, p.latitude); b = Math.max(b, p.latitude); c = Math.min(c, p.longitude); d = Math.max(d, p.longitude) })
  return {
    center: { latitude: (a + b) / 2, longitude: (c + d) / 2 },
    scale: fitScale(span, (a + b) / 2),
    include: all.concat([
      { latitude: Math.max(-90, a - Math.max(0.0001, (b - a) * 0.12)), longitude: Math.max(-180, c - Math.max(0.0001, (d - c) * 0.12)) },
      { latitude: Math.min(90, b + Math.max(0.0001, (b - a) * 0.12)), longitude: Math.min(180, d + Math.max(0.0001, (d - c) * 0.12)) },
    ]),
    polyline: [{ points: pts, color: LINE, width: 5, borderColor: INK, borderWidth: 1 }],
    circles,
  }
}

const WIDTH = 670
const HEIGHT = 520
const PADDING = 60

function buildRouteGeometry(session) {
  const track = limitTrack(session && session.track)
  if (track.length < 2) return { segs: [], poiDots: [] }

  const kx = Math.cos(track[0].lat * Math.PI / 180)
  const project = (point) => ({ x: point.lng * kx, y: -point.lat })
  const trackPoints = track.map(project)
  const poiPoints = normalizeTrack(session && session.pois).map(project)
  const all = trackPoints.concat(poiPoints)
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  all.forEach((point) => {
    minX = Math.min(minX, point.x)
    minY = Math.min(minY, point.y)
    maxX = Math.max(maxX, point.x)
    maxY = Math.max(maxY, point.y)
  })
  const scale = Math.min(
    (WIDTH - PADDING * 2) / Math.max(1e-9, maxX - minX),
    (HEIGHT - PADDING * 2) / Math.max(1e-9, maxY - minY),
  )
  const offsetX = (WIDTH - (maxX - minX) * scale) / 2 - minX * scale
  const offsetY = (HEIGHT - (maxY - minY) * scale) / 2 - minY * scale
  const x = (point) => point.x * scale + offsetX
  const y = (point) => point.y * scale + offsetY
  const segs = []
  for (let index = 1; index < trackPoints.length; index += 1) {
    const fromX = x(trackPoints[index - 1])
    const fromY = y(trackPoints[index - 1])
    const toX = x(trackPoints[index])
    const toY = y(trackPoints[index])
    const length = Math.hypot(toX - fromX, toY - fromY)
    if (length < 2) continue
    segs.push({
      i: index,
      x: Math.round(fromX),
      y: Math.round(fromY),
      len: Math.round(length) + 2,
      deg: Math.round(Math.atan2(toY - fromY, toX - fromX) * 180 / Math.PI),
    })
  }
  const poiDots = poiPoints.map((point, index) => ({
    i: index,
    x: Math.round(x(point)),
    y: Math.round(y(point)),
    tone: index % 4,
  }))
  return { segs, poiDots }
}


module.exports = { routeGeo, fitScale, MAX_POINTS, buildRouteGeometry }
