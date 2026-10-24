'use strict'

// 漫游路线回放几何(C:U-117):把轨迹(经纬度)等比投影进一个画框并切成可旋转的线段。
// 会话详情回放组件(scene-roam-session/route-replay)与漫游页分享卡(_buildArchFrom)共用。
// 两处画框/边距/最小线段/是否把 POI 计入包围盒各不相同,一律走参数,不在这里统一取值 —— 统一就是视觉变更。
// 投影:以 origin 为原点的等距柱状投影,x 东、y 南(屏幕向下),单位米;x/y 用同一 scale,路线不会被拉伸。
const { m2lat, m2lng } = require('./roam-geo.js')

function projector(origin) {
  return (p) => ({ x: (p.lng - origin.lng) / m2lng(1, origin.lat), y: -(p.lat - origin.lat) / m2lat(1) })
}

/**
 * @param {Array<{lat,lng}>} track 已归一化的轨迹(≥2 点)
 * @param {Array<{lat,lng}>} pois 已归一化的 POI(可空)
 * @param {object} box { width, height, padTop, padBottom, padX, origin?, project?, poisInBounds?, minSpan?, minSegLen? }
 *   project 缺省为以 origin(缺省 track[0])为原点的米制投影;minSpan 与 project 同单位。
 * @returns {{ segs, poiPoints, endPoint, scale }} poiPoints/endPoint 为画框内坐标(未取整)
 */
function fitRoute(track, pois, box) {
  const project = box.project || projector(box.origin || track[0])
  const trackPoints = track.map(project)
  const poiProjected = (pois || []).map(project)
  const bounded = box.poisInBounds ? trackPoints.concat(poiProjected) : trackPoints
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  bounded.forEach((p) => {
    minX = Math.min(minX, p.x); minY = Math.min(minY, p.y)
    maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y)
  })
  const minSpan = box.minSpan == null ? 1 : box.minSpan
  const innerW = box.width - box.padX * 2
  const innerH = box.height - box.padTop - box.padBottom
  const scale = Math.min(innerW / Math.max(minSpan, maxX - minX), innerH / Math.max(minSpan, maxY - minY))
  const offsetX = (box.width - (maxX - minX) * scale) / 2 - minX * scale
  const offsetY = box.padTop + (innerH - (maxY - minY) * scale) / 2 - minY * scale
  const at = (p) => ({ x: p.x * scale + offsetX, y: p.y * scale + offsetY })
  const minSegLen = box.minSegLen || 0
  const segs = []
  for (let i = 1; i < trackPoints.length; i++) {
    const a = at(trackPoints[i - 1]), b = at(trackPoints[i])
    const len = Math.hypot(b.x - a.x, b.y - a.y)
    if (len === 0 || len < minSegLen) continue
    segs.push({
      i, x: Math.round(a.x), y: Math.round(a.y), len: Math.round(len) + 2,
      deg: Math.round(Math.atan2(b.y - a.y, b.x - a.x) * 180 / Math.PI),
    })
  }
  return { segs, poiPoints: poiProjected.map(at), endPoint: at(trackPoints[trackPoints.length - 1]), scale }
}

/** 漫游页分享卡 / 历史分享的拱形路线(原 pages/roam _buildArchFrom 的画框:576×520,上 100 下 50 左右 60,POI 不计入包围盒)。 */
function buildShareArch(points, pois, center) {
  if (!Array.isArray(points) || points.length < 2) return { archSegs: [], archPois: [], archEnd: {} }
  const listed = (Array.isArray(pois) ? pois : []).filter((p) => p && p.lat != null && p.lng != null)
  const fit = fitRoute(points, listed, {
    width: 576, height: 520, padTop: 100, padBottom: 50, padX: 60, origin: center || points[0],
  })
  return {
    archSegs: fit.segs,
    archPois: fit.poiPoints.map((m, i) => ({ i, x: Math.round(m.x), y: Math.round(m.y), color: listed[i].color })),
    archEnd: { x: Math.round(fit.endPoint.x), y: Math.round(fit.endPoint.y) },
  }
}

module.exports = { fitRoute, projector, buildShareArch }
