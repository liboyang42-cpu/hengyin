'use strict'
// 漫游页 ↔ 跟随官方路线的接线层。pages/roam/index.js 只调这里的函数,不在页面里堆逻辑。
// 状态挂在 page._followRoute(startFollow 产物),HUD 视图模型写 data.followRoute。
const fr = require('../../utils/roam-follow-route.js')
const cyToast = require('../../utils/toast.js')

const MAP_SCREENS = ['opening', 'map']

function toast(page, title) {
  try { cyToast(title) } catch (e) { /* 提示失败不影响叠加 */ }
}

/** HUD + 折线 + 针一起刷。轨迹线由页面自己的 _flushTrackLine 补回(它已经会拼上路线)。 */
function render(page) {
  const state = page._followRoute
  page.setData({ followRoute: fr.hudModel(state, page._player), polyline: fr.routePolyline(state) })
  if (typeof page._flushTrackLine === 'function') page._flushTrackLine()
  if (typeof page._syncMarkers === 'function' && page.data.mapReady) page._syncMarkers()
}

/**
 * onShow 调:消费一次交接。
 * - 已在漫游地图上:直接叠到当前这局;
 * - 还在入口:记下计划并弹「开始漫游」半屏(带定位用途说明),由玩家点开始,照常走 goStart;
 * - 结算页:只记下,下一次开局时画。
 */
function consume(page, app) {
  const plan = fr.consumeHandoff(wx, fr.currentOwner(app))
  if (!plan) return false
  page._followRoute = fr.startFollow(plan)
  const screen = page.data.screen
  if (MAP_SCREENS.indexOf(screen) >= 0) {
    render(page)
    toast(page, '已在地图上标出线路站点')
    return true
  }
  page.setData({ followRoute: fr.hudModel(page._followRoute, null) })
  if (screen === 'entry-map' && typeof page.openEntryRoamSheet === 'function') page.openEntryRoamSheet()
  return true
}

/** _openRoamMap 之后调:新开的一局把路线画上。没有跟随计划时什么都不做。 */
function onSessionOpened(page) {
  if (page._followRoute) render(page)
}

/** 轨迹线条目 + 路线折线。没有跟随时原样返回。 */
function withRoute(page, lines) {
  return page._followRoute ? lines.concat(fr.routePolyline(page._followRoute)) : lines
}

function markers(page) {
  return fr.routeMarkers(page._followRoute)
}

/** 每次玩家位置推进时调;只有到站或距离文案变化才 setData。 */
function onMove(page, player) {
  const prev = page._followRoute
  if (!prev) return
  const { state, changed } = fr.advance(prev, player)
  page._followRoute = state
  const hud = fr.hudModel(state, player)
  const cur = page.data.followRoute
  if (changed || !cur || cur.distText !== hud.distText || cur.nextText !== hud.nextText) page.setData({ followRoute: hud })
  if (changed) {
    toast(page, hud.finished ? '线路站点已全部到达' : '已到达一个站点')
    if (typeof page._syncMarkers === 'function') page._syncMarkers()
  }
}

/** 点站点针:提示站名与大概距离(不导航、不打卡)。返回 true = 已处理。 */
function tapMarker(page, markerId) {
  if (!fr.isFollowMarkerId(markerId) || !page._followRoute) return false
  const station = page._followRoute.plan.stations[markerId - fr.FOLLOW_MK_BASE]
  if (!station) return true
  const p = page._player
  const d = p ? fr.distM(p, station) : NaN
  toast(page, `第${station.order}站 ${station.name}` + (Number.isFinite(d) ? `，约 ${d < 1000 ? Math.round(d) + ' 米' : (d / 1000).toFixed(1) + ' 公里'}` : ''))
  return true
}

/** 退出跟随:清 HUD/折线/针,漫游本身继续。silent=true 用于结算、换号等被动清理。 */
function exit(page, silent) {
  if (!page._followRoute && !page.data.followRoute) return
  page._followRoute = null
  page.setData({ followRoute: null, polyline: [] })
  if (typeof page._flushTrackLine === 'function') page._flushTrackLine()
  if (typeof page._syncMarkers === 'function' && page.data.mapReady) page._syncMarkers()
  if (!silent) toast(page, '已退出跟随，继续自由漫游')
}

module.exports = { consume, onSessionOpened, withRoute, markers, onMove, tapMarker, exit }
