'use strict'

const { buildRouteGeometry } = require('../../../utils/roam-route-geo.js')

const REPLAY_FRAME_MS = 120
const REPLAY_MAX_FRAMES = 24
const REPLAY_MIN_MS = 1200
const REPLAY_MAX_MS = (REPLAY_MAX_FRAMES - 1) * REPLAY_FRAME_MS
const REPLAY_MS_PER_SEGMENT = 20


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
