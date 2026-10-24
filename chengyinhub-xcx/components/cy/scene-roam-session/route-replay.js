'use strict'

const { limitTrack, normalizeTrack } = require('../../../utils/roam-track-simplify.js')
const { fitRoute } = require('../../../utils/roam-route-geometry.js')

const WIDTH = 670
const HEIGHT = 520
const PADDING = 60
const REPLAY_FRAME_MS = 120
const REPLAY_MAX_FRAMES = 24

// 画框与取舍沿用原值(670×520、四周 60、POI 计入包围盒、<2px 线段丢弃);几何本体见 utils/roam-route-geometry(C:U-117)。
function buildRouteGeometry(session) {
  const track = limitTrack(session && session.track)
  if (track.length < 2) return { segs: [], poiDots: [] }

  const kx = Math.cos(track[0].lat * Math.PI / 180)
  const fit = fitRoute(track, normalizeTrack(session && session.pois), {
    width: WIDTH, height: HEIGHT, padTop: PADDING, padBottom: PADDING, padX: PADDING,
    project: (point) => ({ x: point.lng * kx, y: -point.lat }),
    poisInBounds: true, minSpan: 1e-9, minSegLen: 2,
  })
  const poiDots = fit.poiPoints.map((point, index) => ({
    i: index,
    x: Math.round(point.x),
    y: Math.round(point.y),
    tone: index % 4,
  }))
  return { segs: fit.segs, poiDots }
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

function buildReplaySchedule(segmentCount) {
  const total = Math.max(0, Math.floor(Number(segmentCount) || 0))
  if (!total) return { counts: [], intervalMs: REPLAY_FRAME_MS, totalDurationMs: 0 }
  const frameCount = Math.min(total, REPLAY_MAX_FRAMES)
  const step = Math.ceil(total / frameCount)
  const counts = []
  for (let count = step; count < total; count += step) counts.push(count)
  counts.push(total)
  return {
    counts,
    intervalMs: REPLAY_FRAME_MS,
    totalDurationMs: Math.max(0, counts.length - 1) * REPLAY_FRAME_MS,
  }
}

module.exports = { buildReplaySchedule, buildRouteGeometry, replayFrame }
