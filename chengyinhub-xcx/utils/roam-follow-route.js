'use strict'
// 漫游「跟随官方路线」(G1)。纯前端:
//   1) 路线详情页把已整形的站点写进一次性交接(switchTab 带不了 query);
//   2) 漫游 tab onShow 消费一次 → 照常走 goStart 开局 → 地图叠加路线折线 + 站点针 + HUD。
// 进度只在本机按 GPS 距离估算,不写服务端、不参与结算/打卡/奖励;退出跟随只清叠加,不结束漫游。
// 路线数据来源:GET /api/preset/route/info(见 subpackageRoam/utils/official-route.js 的整形)。
const { MAP_AVATAR_COLORS, ROUTE_BORDER_COLOR } = require('./play-visual-tokens.js')

const HANDOFF_SLOT = 'cy_roam_follow_route_handoff_v1'
const HANDOFF_TTL_MS = 5 * 60 * 1000
const MAX_STATIONS = 30
// 「到站」只做本机提示的距离阈值(米)。与打卡半径无关,打卡仍以 /api/roam/shop/visit 为准。
const REACH_M = 60
// marker id 段:错开 PLAYER_MK(99)、STATUS(99997)、商家(9000+i)、POI(700000+)、runner(800000+i)。
const FOLLOW_MK_BASE = 960000

function text(value) {
  return value == null ? '' : String(value).trim()
}

function validPoint(lat, lng) {
  return typeof lat === 'number' && typeof lng === 'number' && Number.isFinite(lat) && Number.isFinite(lng)
    && Math.abs(lat) <= 90 && Math.abs(lng) <= 180
}

/**
 * 从已整形的路线详情(normalizeRouteDetail 的产物)建跟随计划。
 * 只保留带坐标的站点;一个都没有 → supported:false,并给出降级原因,调用方不得开跟随。
 */
function buildFollowPlan(route) {
  if (!route || typeof route !== 'object') return { supported: false, reason: '线路信息不完整，暂时不能跟随' }
  const routeId = text(route.id)
  const name = text(route.name)
  if (!/^\d{1,18}$/.test(routeId) || !name) return { supported: false, reason: '线路信息不完整，暂时不能跟随' }
  const nodes = Array.isArray(route.nodes) ? route.nodes : []
  const stations = nodes
    .filter((n) => n && validPoint(n.lat, n.lng) && text(n.name))
    .slice(0, MAX_STATIONS)
    .map((n, i) => ({ order: i + 1, name: text(n.name), lat: n.lat, lng: n.lng }))
  if (!stations.length) {
    return { supported: false, reason: nodes.length ? '这条线路的站点还没有标注位置，暂时不能在地图上跟随' : '这条线路还没有站点，暂时不能跟随' }
  }
  return { supported: true, routeId, name, stations, skipped: nodes.length - stations.length }
}

function currentOwner(app) {
  try { return app && typeof app.getUserID === 'function' ? String(app.getUserID() || '') : '' } catch (e) { return '' }
}

/** 写交接。返回 false 表示写入失败(存储满/被禁),调用方应提示而不是跳过去开一局没路线的漫游。 */
function writeHandoff(storage, plan, owner, now) {
  if (!plan || !plan.supported) return false
  const payload = {
    v: 1,
    owner: String(owner || ''),
    expiresAt: (now == null ? Date.now() : now) + HANDOFF_TTL_MS,
    routeId: plan.routeId,
    name: plan.name,
    skipped: plan.skipped || 0,
    stations: plan.stations,
  }
  try { storage.setStorageSync(HANDOFF_SLOT, payload); return true } catch (e) { return false }
}

/**
 * 读并**立即删除**交接(只消费一次,过期/坏数据/换账号一律丢弃)。
 * owner 两侧都有值且不一致才丢:详情页未登录时 owner 为空,漫游开局前会补登录。
 */
function consumeHandoff(storage, owner, now) {
  let raw = null
  try { raw = storage.getStorageSync(HANDOFF_SLOT) } catch (e) { raw = null }
  if (raw === '' || raw == null) return null
  try { storage.removeStorageSync(HANDOFF_SLOT) } catch (e) { /* 删不掉也只按过期判,不重复消费靠 expiresAt */ }
  if (!raw || typeof raw !== 'object' || raw.v !== 1) return null
  const t = now == null ? Date.now() : now
  if (!(typeof raw.expiresAt === 'number' && raw.expiresAt > t && raw.expiresAt - t <= HANDOFF_TTL_MS)) return null
  const cur = String(owner || '')
  if (raw.owner && cur && raw.owner !== cur) return null
  const plan = buildFollowPlan({
    id: raw.routeId,
    name: raw.name,
    nodes: Array.isArray(raw.stations) ? raw.stations : [],
  })
  if (!plan.supported) return null
  plan.skipped = Math.max(0, Number(raw.skipped) || 0)
  return plan
}

function distM(a, b) {
  const R = 6371000
  const rad = Math.PI / 180
  const dLat = (b.lat - a.lat) * rad
  const dLng = (b.lng - a.lng) * rad
  const s = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(s)))
}

function distanceText(m) {
  if (!Number.isFinite(m)) return ''
  if (m < 1000) return `${Math.max(1, Math.round(m))} 米`
  return `${(m / 1000).toFixed(1)} 公里`
}

/** 新的跟随状态。reached 按站序记录本机判定已到达的站。 */
function startFollow(plan) {
  if (!plan || !plan.supported) return null
  return { plan, reached: plan.stations.map(() => false) }
}

/**
 * 玩家移动后推进:到达半径内的站标为已到(可跳站,走到哪站算哪站)。
 * 返回 { state, changed }。state 不可变更新,changed=false 时调用方不必 setData。
 */
function advance(state, player) {
  if (!state || !player || !validPoint(player.lat, player.lng)) return { state, changed: false }
  let changed = false
  const reached = state.reached.map((done, i) => {
    if (done) return true
    if (distM(player, state.plan.stations[i]) <= REACH_M) { changed = true; return true }
    return false
  })
  return { state: changed ? { plan: state.plan, reached } : state, changed }
}

/** HUD 视图模型(wxml 只读这一份)。player 可空:没定位时只显示站序,不显示距离。 */
function hudModel(state, player) {
  if (!state) return null
  const { plan, reached } = state
  const total = plan.stations.length
  const done = reached.filter(Boolean).length
  const nextIdx = reached.indexOf(false)
  const next = nextIdx >= 0 ? plan.stations[nextIdx] : null
  const dist = next && player && validPoint(player.lat, player.lng) ? distanceText(distM(player, next)) : ''
  return {
    name: plan.name,
    progress: `${done}/${total}`,
    total,
    finished: !next,
    nextText: next ? `下一站 · 第${next.order}站 ${next.name}` : '全部站点已到达',
    distText: dist ? `约 ${dist}` : '',
    skippedText: plan.skipped ? `${plan.skipped} 个站点未标注位置，未画在地图上` : '',
    a11y: `跟随官方线路 ${plan.name}，已到 ${done} 站，共 ${total} 站。${next ? '下一站 ' + next.name : '全部站点已到达'}${dist ? '，约 ' + dist : ''}`,
  }
}

/** 路线折线(追加在轨迹线之后,轨迹仍在最上层语义上不受影响)。少于 2 站不画线。 */
function routePolyline(state) {
  if (!state || state.plan.stations.length < 2) return []
  return [{
    points: state.plan.stations.map((s) => ({ latitude: s.lat, longitude: s.lng })),
    color: MAP_AVATAR_COLORS.cityRoute,
    width: 4,
    dottedLine: true,
    borderColor: ROUTE_BORDER_COLOR,
    borderWidth: 1,
  }]
}

/** 站点针。不带 iconPath = 微信默认针;label 写站序,已到的站降成 dim 色。 */
function routeMarkers(state) {
  if (!state) return []
  return state.plan.stations.map((s, i) => ({
    id: FOLLOW_MK_BASE + i,
    latitude: s.lat,
    longitude: s.lng,
    width: 26,
    height: 34,
    zIndex: 800,
    label: {
      content: `${s.order} ${s.name}`,
      color: MAP_AVATAR_COLORS.cream,
      bgColor: state.reached[i] ? MAP_AVATAR_COLORS.dim : MAP_AVATAR_COLORS.panel,
      fontSize: 11,
      borderRadius: 8,
      padding: 4,
      anchorX: -20,
      anchorY: 0,
    },
  }))
}

function isFollowMarkerId(id) {
  return typeof id === 'number' && id >= FOLLOW_MK_BASE && id < FOLLOW_MK_BASE + MAX_STATIONS
}

module.exports = {
  HANDOFF_SLOT,
  HANDOFF_TTL_MS,
  REACH_M,
  FOLLOW_MK_BASE,
  MAX_STATIONS,
  buildFollowPlan,
  currentOwner,
  writeHandoff,
  consumeHandoff,
  startFollow,
  advance,
  hudModel,
  routePolyline,
  routeMarkers,
  isFollowMarkerId,
  distM,
}
