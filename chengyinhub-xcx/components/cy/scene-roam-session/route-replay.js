'use strict'

const { limitTrack, normalizeTrack } = require('../../../utils/roam-track-simplify.js')

const WIDTH = 670
const HEIGHT = 520
const PADDING = 60
const REPLAY_FRAME_MS = 120
const REPLAY_MAX_FRAMES = 24
const REPLAY_MIN_MS = 1200
const REPLAY_MAX_MS = (REPLAY_MAX_FRAMES - 1) * REPLAY_FRAME_MS
const REPLAY_MS_PER_SEGMENT = 20

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

function replayFrame(segs, requestedCount) {
  const source = Array.isArray(segs) ? segs : []
  const count = Math.max(0, Math.min(source.length, Math.floor(Number(requestedCount) || 0)))
  return {
    visible: source.slice(0, count),
    progress: source.length ? Math.round(count / source.length * 100) : 0,
    done: source.length > 0 && count >= source.length,
  }
}

// U-093(2026-10-24 拍板):回放时长随路线长度平滑、单调增长。旧算法按「最多 24 帧、每帧固定
// 120ms」取步长,24 段 ≈ 2.76s 而 25 段因步长跳到 2 只剩 ≈ 1.44s。现在先定时长
// clamp(1.2s + 20ms × 段数, 1.2s, 2.76s),再由时长推帧数(每帧约 120ms,上限 23 步),
// 帧间隔 = 时长 / 步数。线条与匀速揭示不变;上限沿用旧算法的最长 2.76s(24 帧)。
// counts[0] 恒为 0(开场的空帧),之后逐步揭示到 total,所以 totalDurationMs = 步数 × 帧间隔。
function replayDurationMs(total) {
  if (!total) return 0
  return Math.min(REPLAY_MAX_MS, Math.max(REPLAY_MIN_MS, REPLAY_MIN_MS + REPLAY_MS_PER_SEGMENT * total))
}

function buildReplaySchedule(segmentCount) {
  const total = Math.max(0, Math.floor(Number(segmentCount) || 0))
  if (!total) return { counts: [], intervalMs: REPLAY_FRAME_MS, totalDurationMs: 0 }
  const durationMs = replayDurationMs(total)
  const steps = Math.min(total, REPLAY_MAX_FRAMES - 1, Math.max(1, Math.round(durationMs / REPLAY_FRAME_MS)))
  const counts = [0]
  for (let index = 1; index <= steps; index += 1) counts.push(Math.ceil(total * index / steps))
  return {
    counts,
    intervalMs: durationMs / steps,
    totalDurationMs: durationMs,
  }
}

module.exports = { buildReplaySchedule, buildRouteGeometry, replayFrame }
