'use strict'

// 漫游每个真实定位点之后的地图刷新计划(C:U-091):镜头跟随 / 轨迹线 / marker 重发三条节流。
// 纯函数,不碰 this/setData;页面按返回值写回时间戳并 setData,单测可直接喂时间轴。
const CENTER_MS = 500
const LINE_MS = 1500
const MARKER_MS = 600
const HOLD_MS = 3000

const { ROUTE_COLORS } = require('./play-visual-tokens.js')

// 漫游实时轨迹线:主色取 play 路线 token 首色(白),描边为同色 40% 透明(alpha 66)。原生 map polyline 读不到 CSS 变量。
const TRACK_LINE_COLOR = ROUTE_COLORS[0]
const TRACK_LINE_STYLE = Object.freeze({ color: TRACK_LINE_COLOR, width: 3, borderColor: TRACK_LINE_COLOR + '66', borderWidth: 1 })

function trackPolyline(track) {
  return [Object.assign({
    points: (track || []).map((q) => ({ latitude: q.lat, longitude: q.lng })),
  }, TRACK_LINE_STYLE)]
}

/**
 * @param {object} s { now, player, track, zoomLocked, holdAt, centerAt, lineAt, markerAt }
 * @returns {{ patch: object, centerAt: number, lineAt: number, markerAt: number, syncMarkers: boolean }}
 */
function planMovePatch(s, opts) {
  const o = Object.assign({ centerMs: CENTER_MS, lineMs: LINE_MS, markerMs: MARKER_MS, holdMs: HOLD_MS }, opts)
  const now = s.now
  const patch = {}
  let centerAt = s.centerAt, lineAt = s.lineAt, markerAt = s.markerAt
  if (!s.zoomLocked && (!s.holdAt || now - s.holdAt > o.holdMs)) {
    if (!centerAt || now - centerAt >= o.centerMs) {
      centerAt = now
      patch.center = { lat: s.player.lat, lng: s.player.lng }
    }
  }
  if (!lineAt || now - lineAt >= o.lineMs) {
    lineAt = now
    if (s.track && s.track.length > 1) patch.polyline = trackPolyline(s.track)
  }
  const syncMarkers = !markerAt || now - markerAt >= o.markerMs
  if (syncMarkers) markerAt = now
  return { patch, centerAt, lineAt, markerAt, syncMarkers }
}

module.exports = { planMovePatch, trackPolyline, TRACK_LINE_STYLE, CENTER_MS, LINE_MS, MARKER_MS, HOLD_MS }
