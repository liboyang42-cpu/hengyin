'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { simplifyTrack, DEFAULT_MAX_POINTS, MAX_POINTS_LIMIT } = require('../../utils/roam-track-simplify.js')

// 锯齿轨迹：每个点都是显著转折，简化只能靠点数预算截断。
function zigzag(n) {
  const points = []
  for (let i = 0; i < n; i++) points.push({ lat: 31.2 + i * 0.0005, lng: 121.4 + (i % 2) * 0.001 })
  return points
}

test('U-086: maxPoints 超过硬上限时被钳到 MAX_POINTS_LIMIT', () => {
  const track = zigzag(MAX_POINTS_LIMIT * 3)
  const out = simplifyTrack(track, { maxPoints: 1e9, epsilonMeters: 0 })
  assert.equal(out.length, MAX_POINTS_LIMIT)
  assert.deepEqual(out[0], track[0])
  assert.deepEqual(out[out.length - 1], track[track.length - 1])
})

test('U-086: 上限以内的 maxPoints 与默认值行为不变', () => {
  const track = zigzag(600)
  assert.equal(simplifyTrack(track, { maxPoints: 50, epsilonMeters: 0 }).length, 50)
  assert.equal(simplifyTrack(track, { epsilonMeters: 0 }).length, DEFAULT_MAX_POINTS)
  assert.ok(DEFAULT_MAX_POINTS <= MAX_POINTS_LIMIT)
})
