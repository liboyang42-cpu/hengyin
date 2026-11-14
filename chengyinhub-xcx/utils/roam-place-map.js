'use strict'

// 漫游地点(/api/roam/pois 行)的共用读法:入口地图、主地图、附近页都走这里,不各抄一份。
// 纯函数,不碰 wx / getApp。后端新字段(cover / gallery / quotes / photo_credit / node_level / truncated)
// 一律防御式读取:没有就保持原有行为。
const { readTags, presentationForPoi } = require('./roam-poi-presentation.js')

const SEED_PREFIX = 'roam:seed:v1:'
const MIN_RADIUS_M = 1000
const MAX_RADIUS_M = 20000
const MYSTERY_M = 1000

function num(v) {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}
function text(v) {
  if (v == null) return ''
  const s = String(v).trim()
  return s === 'null' ? '' : s
}

/** JSON 数组串优先;老数据里的逗号 / 空白分隔串也认。返回原样字符串数组(含 roam:* 保留标签)。 */
function parseTags(value) {
  const json = readTags(value)
  if (json.length) return json.map(text).filter(Boolean)
  if (typeof value !== 'string' || !value.trim() || /^\s*[[{]/.test(value)) return []
  return value.split(/[,，、;；|\s]+/).map(text).filter(Boolean)
}
/** 给玩家看的标签:去掉 roam:* 保留标签与重复项。 */
function visibleTags(value) {
  const seen = {}
  return parseTags(value).filter((tag) => {
    if (tag.indexOf('roam:') === 0 || seen[tag]) return false
    seen[tag] = 1
    return true
  })
}

function validLatLng(row) { return !!row && num(row.lat) != null && num(row.lng) != null }
/** 已上架的漫游点(服务端只回上架行):有 id、有坐标即可画;种子标签只决定有没有推荐理由。 */
function isRoamPlace(row) {
  return !!row && Number(row.id) > 0 && validLatLng(row) && (Number(row.type) === 1 || Number(row.type) === 2)
}
function hasSeedTag(row) { return !!row && parseTags(row.tags).some((tag) => tag.indexOf(SEED_PREFIX) === 0) }

/** pin / 圆点用的分类键:merchant / park / street / shop / landmark。 */
function categoryKey(row) {
  const p = presentationForPoi(row)
  if (p.cat === 'merchant') return 'merchant'
  if (p.cat === 'park') return 'park'
  const tags = parseTags(row && row.tags)
  if (tags.indexOf('roam:street') >= 0) return 'street'
  if (tags.indexOf('roam:shop') >= 0) return 'shop'
  return 'landmark'
}

function listOf(value) {
  if (Array.isArray(value)) return value
  if (typeof value !== 'string' || !value.trim()) return []
  try { const rows = JSON.parse(value); return Array.isArray(rows) ? rows : [] } catch (_) { return [] }
}
function coverKey(row) { return text(row && (row.cover || row.coverImg || row.cover_img)) }
function galleryKeys(row) {
  return listOf(row && row.gallery).map((item) => text(item && typeof item === 'object' ? (item.key || item.url) : item)).filter(Boolean).slice(0, 6)
}
function quoteList(row) {
  return listOf(row && row.quotes).map((q) => (typeof q === 'string' ? { text: text(q) } : { text: text(q && q.text), source: text(q && q.source) }))
    .filter((q) => q.text)
}
/** 推荐理由唯一受控来源:服务端 quotes 第一条,其次目录里核对过的 note.reason。绝不读非商家的 description。 */
function reasonFor(row, note) {
  const quote = quoteList(row)[0]
  if (quote) return quote.text
  return note && note.reason ? String(note.reason) : ''
}
function credit(row) { return text(row && (row.photoCredit || row.photo_credit)) }
function nodeLevel(row) { return Number((row && (row.nodeLevel != null ? row.nodeLevel : row.node_level)) || 1) || 1 }

/** /api/roam/pois 回包可能是数组,也可能(W1 之后)是带 truncated 的对象。 */
function unwrapPois(res) {
  const body = res && res.data
  const rows = Array.isArray(body) ? body : (body && [body.rows, body.list, body.pois, body.items].find(Array.isArray)) || null
  if (!rows) return null
  const flag = res.truncated === true || (body && !Array.isArray(body) && body.truncated === true)
  return { rows, truncated: !!flag }
}

const R = 6371000
function distanceM(a, b) {
  const rad = Math.PI / 180
  const dLat = (b.lat - a.lat) * rad
  const dLng = (b.lng - a.lng) * rad
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)))
}
function originOf(value) {
  const lat = num(value && (value.lat != null ? value.lat : value.latitude))
  const lng = num(value && (value.lng != null ? value.lng : value.longitude))
  return lat == null || lng == null ? null : { lat, lng }
}
/** 真实距离;没有玩家位置就返回 null(调用方不写距离,不写「距你很近」)。 */
function distanceFrom(origin, row) {
  const o = originOf(origin)
  if (!o || !validLatLng(row)) return null
  return distanceM(o, { lat: Number(row.lat), lng: Number(row.lng) })
}
function distanceText(meters) {
  const m = num(meters)
  if (m == null || m < 0) return ''
  return m < 1000 ? Math.max(1, Math.round(m)) + ' 米' : (m / 1000).toFixed(1) + ' 公里'
}
function gapText(meters) {
  const m = num(meters)
  return m == null || m <= 0 ? '' : '还差 ' + Math.max(1, Math.round(m)) + ' 米'
}

function clampRadius(m) { return Math.min(MAX_RADIUS_M, Math.max(MIN_RADIUS_M, Math.round(m))) }
/** 视口外接圆半径(米);region 取 regionchange 的 southwest / northeast。 */
function radiusFromRegion(region, fallback) {
  const sw = originOf(region && region.southwest)
  const ne = originOf(region && region.northeast)
  if (!sw || !ne) return clampRadius(fallback || 3000)
  return clampRadius(distanceM(sw, ne) / 2)
}
/** 还没有 region 时按缩放估:每像素米数 × 屏半对角。 */
function radiusFromScale(lat, scale, viewport) {
  const z = num(scale)
  const la = num(lat)
  if (z == null || la == null) return clampRadius(3000)
  const w = num(viewport && viewport.width) || 390
  const h = num(viewport && viewport.height) || 844
  const mPerPx = 156543.03392 * Math.cos(la * Math.PI / 180) / Math.pow(2, z)
  return clampRadius(Math.sqrt(w * w + h * h) / 2 * mPerPx)
}
/** 玩家 / 中心位移超过半径一半才重拉。 */
function shouldRefetch(last, now, radiusM) {
  const a = originOf(last)
  const b = originOf(now)
  if (!b) return false
  if (!a) return true
  return distanceM(a, b) > (Number(radiusM) || 3000) / 2
}
/** 按 id 去重合并:新结果覆盖旧的,离新中心超过 2 倍半径的旧点丢掉。 */
function mergePlaces(prev, next, center, radiusM) {
  const o = originOf(center)
  const keep = {}
  const out = []
  ;(next || []).forEach((row) => { if (row && row.id != null && !keep[row.id]) { keep[row.id] = 1; out.push(row) } })
  ;(prev || []).forEach((row) => {
    if (!row || row.id == null || keep[row.id]) return
    if (o && validLatLng(row) && distanceM(o, { lat: Number(row.lat), lng: Number(row.lng) }) > 2 * radiusM) return
    keep[row.id] = 1
    out.push(row)
  })
  return out
}

function regionBox(region) {
  const sw = originOf(region && region.southwest)
  const ne = originOf(region && region.northeast)
  if (!sw || !ne || ne.lat <= sw.lat || ne.lng <= sw.lng) return null
  return { s: sw.lat, w: sw.lng, n: ne.lat, e: ne.lng }
}
/** 视口裁剪:只留视口外扩 pad 比例内的点。 */
function cullToRegion(rows, region, pad) {
  const b = regionBox(region)
  if (!b) return rows || []
  const p = pad == null ? 0.25 : pad
  const dLat = (b.n - b.s) * p
  const dLng = (b.e - b.w) * p
  return (rows || []).filter((row) => {
    const la = Number(row.lat)
    const ln = Number(row.lng)
    return la >= b.s - dLat && la <= b.n + dLat && ln >= b.w - dLng && ln <= b.e + dLng
  })
}

const PIN_DIR = '/images/roam/pin/'
const CLUSTER_ICON = PIN_DIR + 'dot-cluster.png'
const CLUSTER_TAG = 8
const NAME_SCALE = 15
const CLUSTER_BELOW = 15
const DOT_BELOW = 12
const CELL_PX = 64

/** 缩放档:names 近景(带名字)/ pins 中景(分类图钉)/ cluster 远景(聚合 + 数量)。 */
function pinMode(scale) {
  const z = Number(scale)
  if (!(z > 0)) return 'pins'
  if (z >= NAME_SCALE) return 'names'
  return z >= CLUSTER_BELOW ? 'pins' : 'cluster'
}

function nameCallout(name, fontSize) {
  return { content: String(name || '').slice(0, 10), color: '#FFFFFF', bgColor: '#1C1C1EF2', fontSize: fontSize || 11, borderRadius: 10, padding: 6, display: 'ALWAYS' } /* ds-ok 原生 marker 气泡只吃字面色,与入口卡同色 */
}

/**
 * 地点 → marker。viewport 裁剪后,近景给分类图钉 + 名字,远景按屏幕格聚合并写数量。
 * opts: { scale, region, viewport, tag(单点 marker id 尾号), fontSize, limit, minCluster(起聚个数,默认 2), soloDots(远景单点也画成圆点) }
 * 返回 { markers, clusters } —— clusters[i] = { index, lat, lng, count },marker id = (index+1)*10+8。
 */
function buildPins(places, opts) {
  const o = opts || {}
  const tag = o.tag || 6
  const mode = pinMode(o.scale)
  const rows = cullToRegion((places || []).filter(isRoamPlace), o.region, 0.2)
  const limit = o.limit || 120
  const markers = []
  const clusters = []
  const single = (row, withName, dot) => {
    const cat = categoryKey(row)
    const m = {
      id: Number(row.id) * 10 + tag, latitude: Number(row.lat), longitude: Number(row.lng),
      width: dot ? 14 : 28, height: dot ? 14 : 34, anchor: dot ? { x: 0.5, y: 0.5 } : { x: 0.5, y: 1 }, zIndex: 4,
      iconPath: PIN_DIR + (dot ? 'dot-' : 'pin-') + cat + '.png',
    }
    if (withName && text(row.name)) m.callout = nameCallout(row.name, o.fontSize)
    markers.push(m)
  }
  if (mode === 'cluster' && o.region && regionBox(o.region)) {
    const b = regionBox(o.region)
    const w = num(o.viewport && o.viewport.width) || 390
    const h = num(o.viewport && o.viewport.height) || 844
    const cells = {}
    rows.forEach((row) => {
      const gx = Math.floor((Number(row.lng) - b.w) / (b.e - b.w) * w / CELL_PX)
      const gy = Math.floor((b.n - Number(row.lat)) / (b.n - b.s) * h / CELL_PX)
      const key = gx + ':' + gy
      ;(cells[key] = cells[key] || []).push(row)
    })
    Object.keys(cells).forEach((key) => {
      const group = cells[key]
      if (group.length < (o.minCluster || 2)) { group.forEach((row) => single(row, false, !!o.soloDots || Number(o.scale) < DOT_BELOW)); return }
      const lat = group.reduce((s, r) => s + Number(r.lat), 0) / group.length
      const lng = group.reduce((s, r) => s + Number(r.lng), 0) / group.length
      const index = clusters.length
      clusters.push({ index, lat, lng, count: group.length })
      markers.push({
        id: (index + 1) * 10 + CLUSTER_TAG, latitude: lat, longitude: lng, width: 30, height: 30, anchor: { x: 0.5, y: 0.5 }, zIndex: 5,
        iconPath: CLUSTER_ICON,
        callout: { content: String(group.length), color: '#FFFFFF', bgColor: '#1C1C1EF2', fontSize: o.fontSize || 12, borderRadius: 12, padding: 5, display: 'ALWAYS', textAlign: 'center' }, /* ds-ok 原生 marker 气泡 */
      })
    })
    return { markers, clusters }
  }
  const center = originOf(o.center)
  const ordered = center ? rows.slice().sort((a, c) => distanceM(center, { lat: Number(a.lat), lng: Number(a.lng) }) - distanceM(center, { lat: Number(c.lat), lng: Number(c.lng) })) : rows
  ordered.slice(0, limit).forEach((row, i) => single(row, mode === 'names' && i < 24, false))
  return { markers, clusters }
}
function parsePinMarker(markerId, tag) {
  const n = Number(markerId)
  if (!(n > 0)) return null
  const t = n % 10
  const id = Math.floor(n / 10)
  if (!(id > 0)) return null
  if (t === CLUSTER_TAG) return { kind: 'cluster', index: id - 1 }
  if (t === (tag || 6)) return { kind: 'place', id }
  return null
}
/** 点聚合:缩进两档到聚合中心。 */
function zoomForCluster(scale) { return Math.min(20, Math.max(Number(scale) || 14, CLUSTER_BELOW - 1) + 2) }

/** 主地图「未发现」剪影:离玩家(没有就用中心)不超过 MYSTERY_M 才画。 */
function isMysteryVisible(row, origin) {
  const d = distanceFrom(origin, row)
  return d != null && d <= MYSTERY_M
}

module.exports = {
  SEED_PREFIX, MIN_RADIUS_M, MAX_RADIUS_M, MYSTERY_M, PIN_DIR, CLUSTER_TAG,
  parseTags, visibleTags, isRoamPlace, hasSeedTag, categoryKey, coverKey, galleryKeys, quoteList, reasonFor, credit, nodeLevel,
  unwrapPois, distanceM, distanceFrom, distanceText, gapText, radiusFromRegion, radiusFromScale, shouldRefetch, mergePlaces,
  cullToRegion, pinMode, buildPins, parsePinMarker, zoomForCluster, isMysteryVisible,
}
