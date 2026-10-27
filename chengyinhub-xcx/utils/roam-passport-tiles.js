'use strict'
// 2026-10-27 owner P-29:漫游护照三张方卡换成真实图片 ——
//   漫游记录 = 最近一次轨迹缩略图(本机 roam_sessions 的 track 画成 SVG)
//   集邮册   = 最新一张邮票(最近一次带照片的漫游的最后一张)
//   勋章墙   = 最新点亮的勋章(/api/medal/wall 里带图、获得时间最晚的一枚)
// 拿不到就返回空串,由页面退回业主的空态插画;不编图。

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
// SVG data URI 读不到 var();取值与 roam 页 play-elevated 底 / play-accent 绿一致
const THUMB_BG = '#1C1C1E'   /* ds-ok SVG 缩略图底色,拿不到 CSS 变量 */
const THUMB_LINE = '#C8F36A' /* ds-ok SVG 轨迹线色,拿不到 CSS 变量 */

// SVG 只含 ASCII,逐字节编码即可(小程序没有 btoa)
function asciiBase64(text) {
  let out = ''
  for (let i = 0; i < text.length; i += 3) {
    const a = text.charCodeAt(i), b = text.charCodeAt(i + 1), c = text.charCodeAt(i + 2)
    const n = (a << 16) | ((b || 0) << 8) | (c || 0)
    out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] + (i + 1 < text.length ? B64[(n >> 6) & 63] : '=') + (i + 2 < text.length ? B64[n & 63] : '=')
  }
  return out
}

// 本机存档按 unshift 新在前;仍按 ts 倒序排一次,不依赖数组顺序
function newestFirst(sessions) {
  return (Array.isArray(sessions) ? sessions : []).map((s, i) => ({ s, i }))
    .sort((a, b) => ((Number(b.s && b.s.ts) || 0) - (Number(a.s && a.s.ts) || 0)) || (a.i - b.i)).map((x) => x.s)
}

function trackPoints(track) {
  return (Array.isArray(track) ? track : []).filter((p) => p && Number.isFinite(Number(p.lat)) && Number.isFinite(Number(p.lng)))
    .map((p) => ({ lat: Number(p.lat), lng: Number(p.lng) }))
}

/** 最近一次有 ≥2 个点的轨迹 → 深底 + 亮绿折线的 SVG data URI */
function latestTrackThumb(sessions) {
  const row = newestFirst(sessions).find((s) => s && trackPoints(s.track).length >= 2)
  if (!row) return ''
  const pts = trackPoints(row.track)
  const lats = pts.map((p) => p.lat), lngs = pts.map((p) => p.lng)
  const minLat = Math.min.apply(null, lats), maxLat = Math.max.apply(null, lats)
  const minLng = Math.min.apply(null, lngs), maxLng = Math.max.apply(null, lngs)
  const kx = Math.cos(((minLat + maxLat) / 2) * Math.PI / 180)
  const w = Math.max((maxLng - minLng) * kx, 1e-6), h = Math.max(maxLat - minLat, 1e-6)
  const size = 100, pad = 16, scale = (size - pad * 2) / Math.max(w, h)
  const ox = (size - w * scale) / 2, oy = (size - h * scale) / 2
  const xy = pts.map((p) => ((p.lng - minLng) * kx * scale + ox).toFixed(1) + ',' + ((maxLat - p.lat) * scale + oy).toFixed(1))
  const end = xy[xy.length - 1].split(',')
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">'
    + '<rect width="100" height="100" fill="' + THUMB_BG + '"/>'
    + '<polyline points="' + xy.join(' ') + '" fill="none" stroke="' + THUMB_LINE + '" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>'
    + '<circle cx="' + end[0] + '" cy="' + end[1] + '" r="4" fill="' + THUMB_LINE + '"/></svg>'
  return 'data:image/svg+xml;base64,' + asciiBase64(svg)
}

/** 最新一张邮票:按时间倒序找第一条有照片的漫游,取它最后拍的那张 */
function latestStampImage(sessions) {
  const rows = newestFirst(sessions)
  for (let i = 0; i < rows.length; i += 1) {
    const photos = rows[i] && Array.isArray(rows[i].photos) ? rows[i].photos : []
    for (let j = photos.length - 1; j >= 0; j -= 1) {
      const photo = photos[j]
      const path = typeof photo === 'string' ? photo : (photo && typeof photo.path === 'string' ? photo.path : '')
      if (path) return path
    }
  }
  return ''
}

/** 最新点亮的勋章图:/api/medal/wall 的 medals[](已获得才在列表里),取带 medalImg 且 getTime 最晚的一枚 */
function latestBadgeImage(medals) {
  let best = null
  ;(Array.isArray(medals) ? medals : []).forEach((m) => {
    if (!m || typeof m.medalImg !== 'string' || !m.medalImg.trim()) return
    const t = Date.parse(String(m.getTime || '').replace(' ', 'T')) || 0
    if (!best || t > best.t) best = { t, img: m.medalImg.trim() }
  })
  return best ? best.img : ''
}

module.exports = { latestTrackThumb, latestStampImage, latestBadgeImage, asciiBase64 }
