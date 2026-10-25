'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const {
  buildReplaySchedule,
  buildRouteGeometry,
  replayFrame,
} = require('../../components/cy/scene-roam-session/route-replay.js')

test('真实轨迹被归一化为可逐段回放的路线，POI 保留在同一坐标系', () => {
  const route = buildRouteGeometry({
    track: [
      { lat: 31.2301, lng: 121.4731 },
      { lat: 31.2306, lng: 121.4740 },
      { lat: 31.2312, lng: 121.4752 },
    ],
    pois: [{ lat: 31.2306, lng: 121.4740 }],
  })

  assert.equal(route.segs.length, 2)
  assert.equal(route.poiDots.length, 1)
  assert.ok(route.segs.every((segment) => segment.len > 0))
  assert.ok(route.poiDots[0].x >= 0 && route.poiDots[0].x <= 670)
  assert.ok(route.poiDots[0].y >= 0 && route.poiDots[0].y <= 520)
})

test('回放帧只揭示已走到的路线，并给出真实进度', () => {
  const segs = [{ i: 1 }, { i: 2 }, { i: 3 }, { i: 4 }]
  assert.deepEqual(replayFrame(segs, 0), { visible: [], progress: 0, done: false })
  assert.deepEqual(replayFrame(segs, 2), { visible: segs.slice(0, 2), progress: 50, done: false })
  assert.deepEqual(replayFrame(segs, 99), { visible: segs, progress: 100, done: true })
})

test('长轨迹回放限制在 24 帧和 3 秒内，不逐段递增长数组', () => {
  const schedule = buildReplaySchedule(1000)
  assert.ok(schedule.counts.length <= 24)
  assert.equal(schedule.counts.at(-1), 1000)
  assert.ok(schedule.totalDurationMs <= 3000)
  assert.equal(schedule.counts.every((count, index, list) => index === 0 || count > list[index - 1]), true)

  const component = fs.readFileSync(path.resolve(__dirname, '../../components/cy/scene-roam-session/index.js'), 'utf8')
  assert.match(component, /buildReplaySchedule\(this\.data\.routeSegs\.length\)/)
  assert.doesNotMatch(component, /count\s*\+=\s*1/)
})

test('轨迹不足两点时不绘制假路线', () => {
  assert.deepEqual(buildRouteGeometry({ track: [{ lat: 31, lng: 121 }], pois: [] }), { segs: [], poiDots: [] })
  assert.deepEqual(buildRouteGeometry({ track: [], pois: [] }), { segs: [], poiDots: [] })
  assert.deepEqual(buildRouteGeometry({
    track: [{ lat: null, lng: null }, { lat: 31.23, lng: 121.47 }],
    pois: [],
  }), { segs: [], poiDots: [] }, '缺失坐标不能被 Number(null) 冒充为 (0,0)')
  assert.deepEqual(buildRouteGeometry({
    track: [{ lat: '', lng: '' }, { lat: 31.23, lng: 121.47 }],
    pois: [],
  }), { segs: [], poiDots: [] }, '空坐标不能被 Number(空串) 冒充为 (0,0)')
})

test('U-093:回放时长随段数单调不减,且夹在 1.2s 与上限之间', () => {
  const MIN_MS = 1200
  const MAX_MS = 2760
  let previous = 0
  for (let segments = 1; segments <= 500; segments += 1) {
    const schedule = buildReplaySchedule(segments)
    const duration = schedule.totalDurationMs
    assert.ok(duration >= previous, segments + ' 段的回放(' + duration + 'ms)不得比 ' + (segments - 1) + ' 段(' + previous + 'ms)短')
    assert.ok(duration >= MIN_MS && duration <= MAX_MS, segments + ' 段时长越界:' + duration)
    const steps = schedule.counts.length - 1
    assert.ok(Math.abs(steps * schedule.intervalMs - duration) < 1e-6, '帧数 × 间隔必须等于报出的时长')
    assert.equal(schedule.counts[0], 0)
    assert.equal(schedule.counts.at(-1), segments)
    assert.ok(schedule.counts.length <= 24)
    assert.ok(schedule.counts.every((count, index, list) => index === 0 || count > list[index - 1]))
    previous = duration
  }
  assert.equal(buildReplaySchedule(1).totalDurationMs, 1220)
  assert.equal(buildReplaySchedule(500).totalDurationMs, MAX_MS)
  // 旧算法的断崖:24 段 2.76s → 25 段 1.44s。
  assert.ok(buildReplaySchedule(25).totalDurationMs >= buildReplaySchedule(24).totalDurationMs)
  assert.deepEqual(buildReplaySchedule(0), { counts: [], intervalMs: 120, totalDurationMs: 0 })
})
