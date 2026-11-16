'use strict'

// MPP-7:「在小程序里走过去」—— 城市定向赶路时留在故事里看步行路线(与 App 的 OrientationWalkingView 同一口径)。
// 纯函数,不碰 wx / getApp:后端 /api/play/walk-route 回 {distanceM, durationS, polyline:[[lat,lng],...]}(GCJ-02),
// 这里只负责画线、算剩余、判断偏离重算和失败文案。到站 ≤50 米由页面原有的自动到达接手。

const OFF_ROUTE_M = 40
const OFF_ROUTE_FIXES = 3
const MAX_REPLANS = 2
const ARRIVE_M = 50
const WALK_M_PER_MIN = 75

function finite(v) { return typeof v === 'number' && Number.isFinite(v) }
function point(p) {
  if (Array.isArray(p) && p.length === 2 && finite(Number(p[0])) && finite(Number(p[1]))) return [Number(p[0]), Number(p[1])]
  return null
}
/** 后端回包 → { line:[[lat,lng]...], distanceM, durationS };不合格返回 null(调用方退回导航按钮)。 */
function readRoute(data) {
  const raw = data && Array.isArray(data.polyline) ? data.polyline : []
  const line = raw.map(point).filter(Boolean)
  if (line.length < 2 || line.length !== raw.length) return null
  return { line, distanceM: Math.max(0, Number(data.distanceM) || 0), durationS: Math.max(0, Number(data.durationS) || 0) }
}

const R = 6371000
function metres(a, b) {
  const rad = Math.PI / 180
  const dLat = (b[0] - a[0]) * rad
  const dLng = (b[1] - a[1]) * rad
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a[0] * rad) * Math.cos(b[0] * rad) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)))
}
/** 点到折线的最短距离(米),小范围内用等距投影近似。 */
function metresToLine(fix, line) {
  if (!fix || !line || !line.length) return Infinity
  if (line.length === 1) return metres(fix, line[0])
  const kx = 111320 * Math.cos(fix[0] * Math.PI / 180)
  const ky = 110540
  let best = Infinity
  for (let i = 0; i + 1 < line.length; i++) {
    const ax = (line[i][1] - fix[1]) * kx, ay = (line[i][0] - fix[0]) * ky
    const bx = (line[i + 1][1] - fix[1]) * kx, by = (line[i + 1][0] - fix[0]) * ky
    const dx = bx - ax, dy = by - ay
    const len = dx * dx + dy * dy
    const t = len ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len)) : 0
    const px = ax + t * dx, py = ay + t * dy
    best = Math.min(best, Math.sqrt(px * px + py * py))
  }
  return best
}
/** 沿线剩余距离:离玩家最近的折点之后的路程 + 玩家到那个折点。 */
function remainingAlong(fix, line) {
  if (!fix || !line || line.length < 2) return null
  let nearest = 0, nearestD = Infinity
  line.forEach((p, i) => { const d = metres(fix, p); if (d < nearestD) { nearestD = d; nearest = i } })
  let rest = nearestD
  for (let i = nearest; i + 1 < line.length; i++) rest += metres(line[i], line[i + 1])
  return Math.round(rest)
}
/** 「还有 X 米 · 约 N 分钟」;一公里以上写公里。 */
function remainingText(distanceM) {
  const m = Number(distanceM)
  if (!finite(m) || m < 0) return ''
  const dist = m < 1000 ? Math.max(1, Math.round(m)) + ' 米' : (m / 1000).toFixed(1) + ' 公里'
  const minutes = Math.max(1, Math.round(m / WALK_M_PER_MIN))
  return '还有 ' + dist + ' · 约 ' + minutes + ' 分钟'
}

function createState() { return { offFixes: 0, replans: 0 } }
/**
 * 每次定位:偏离路线 >40 米连续 3 次就重算一次,一段路最多重算 2 次;离站 ≤50 米交回页面的自动到达。
 * 返回 { state, action: 'arrive' | 'replan' | 'keep' }。
 */
function onFix(state, fix, line, target) {
  const s = Object.assign(createState(), state || {})
  if (target && fix && metres(fix, target) <= ARRIVE_M) return { state: s, action: 'arrive' }
  if (metresToLine(fix, line) > OFF_ROUTE_M) s.offFixes += 1
  else s.offFixes = 0
  if (s.offFixes >= OFF_ROUTE_FIXES && s.replans < MAX_REPLANS) {
    return { state: { offFixes: 0, replans: s.replans + 1 }, action: 'replan' }
  }
  return { state: s, action: 'keep' }
}

/** 失败时的说明(都退回「导航过去」按钮,不留死路)。 */
const ERROR_COPY = {
  NO_LOCATION: '没拿到你的位置，可以用「导航过去」',
  WEAK_GPS: '定位信号弱，可以用「导航过去」',
  NETWORK: '网络不太好，可以用「导航过去」',
  ROUTE_TOO_FAR: '离这一站还远，可以用「导航过去」',
  ROUTE_NOT_PLAYING: '开局后才能看步行路线',
}
function errorText(code) {
  return ERROR_COPY[code] || '暂时没有步行路线，可以用「导航过去」'
}
/** 请求失败的回包 → 文案代码。 */
function failureCode(res) {
  if (!res || res.netFail) return 'NETWORK'
  const code = String(res.errorCode || (res.data && res.data.errorCode) || '')
  return ERROR_COPY[code] ? code : 'ROUTE_UNAVAILABLE'
}
/** free-map 的 map-polyline 线条;颜色由调用方传(页面现有的路线色板)。 */
function mapLine(line, color, borderColor) {
  return {
    walkGuide: true,
    points: line.map((p) => ({ latitude: p[0], longitude: p[1] })),
    color, width: 8, borderColor, borderWidth: 2, dottedLine: false, arrowLine: true,
  }
}

module.exports = {
  OFF_ROUTE_M, OFF_ROUTE_FIXES, MAX_REPLANS, ARRIVE_M,
  readRoute, metres, metresToLine, remainingAlong, remainingText, createState, onFix, errorText, failureCode, mapLine,
}
