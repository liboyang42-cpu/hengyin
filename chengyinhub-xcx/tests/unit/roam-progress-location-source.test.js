'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { planMovePatch, trackPolyline, CENTER_MS, LINE_MS, MARKER_MS } = require('../../utils/roam-progress.js')
const { createRoamLocationSource } = require('../../utils/roam-location-source.js')

const P = { lat: 31.23, lng: 121.47 }
const TRACK = [{ lat: 31.23, lng: 121.47 }, { lat: 31.231, lng: 121.471 }]

test('U-091 planMovePatch: 首个点三条节流全部放行', () => {
  const r = planMovePatch({ now: 10000, player: P, track: TRACK })
  assert.deepEqual(r.patch.center, P)
  assert.deepEqual(r.patch.polyline, trackPolyline(TRACK))
  assert.equal(r.syncMarkers, true)
  assert.deepEqual([r.centerAt, r.lineAt, r.markerAt], [10000, 10000, 10000])
})

test('U-091 planMovePatch: 节流窗口内不重复下发,窗口一到再放行', () => {
  const s = { now: 10000 + CENTER_MS - 1, player: P, track: TRACK, centerAt: 10000, lineAt: 10000, markerAt: 10000 }
  const inside = planMovePatch(s)
  assert.deepEqual(inside.patch, {})
  assert.equal(inside.syncMarkers, false)
  const center = planMovePatch(Object.assign({}, s, { now: 10000 + CENTER_MS }))
  assert.ok(center.patch.center && !center.patch.polyline)
  const marker = planMovePatch(Object.assign({}, s, { now: 10000 + MARKER_MS }))
  assert.equal(marker.syncMarkers, true)
  const line = planMovePatch(Object.assign({}, s, { now: 10000 + LINE_MS }))
  assert.ok(line.patch.polyline)
})

test('U-091 planMovePatch: 缩放动画锁 / 手势 hold 3s 内镜头不跟随;单点轨迹不画线', () => {
  assert.equal(planMovePatch({ now: 9000, player: P, track: TRACK, zoomLocked: true }).patch.center, undefined)
  assert.equal(planMovePatch({ now: 9000, player: P, track: TRACK, holdAt: 7000 }).patch.center, undefined)
  assert.ok(planMovePatch({ now: 9000, player: P, track: TRACK, holdAt: 5999 }).patch.center)
  const single = planMovePatch({ now: 9000, player: P, track: [P] })
  assert.equal(single.patch.polyline, undefined)
  assert.equal(single.lineAt, 9000, '单点也要占用节流窗,与页面原行为一致')
})

function fakes() {
  const log = []
  const listeners = new Set()
  const tracker = {
    grant: true,
    acquire(owner, cb) { log.push('acquire:' + owner); this.grant ? cb.success() : cb.fail({ errMsg: 'deny' }) },
    release(owner) { log.push('release:' + owner) },
  }
  const wx = {
    onLocationChange(fn) { listeners.add(fn) },
    offLocationChange(fn) { listeners.delete(fn) },
    emit(loc) { listeners.forEach((fn) => fn(loc)) },
  }
  return { log, listeners, tracker, wx }
}

test('U-091 location source: 注入假定位源可回放定位点,stop 退订并释放', () => {
  const f = fakes()
  const src = createRoamLocationSource({ tracker: f.tracker, wx: f.wx })
  const got = []
  let ok = 0
  src.start((loc) => got.push(loc), { success: () => ok++ })
  f.wx.emit({ latitude: 1, longitude: 2 })
  f.wx.emit({ latitude: 3, longitude: 4 })
  assert.equal(ok, 1)
  assert.equal(got.length, 2)
  assert.equal(src.listening, true)
  src.stop()
  f.wx.emit({ latitude: 5, longitude: 6 })
  assert.equal(got.length, 2, 'stop 后不能再收到点')
  assert.equal(f.listeners.size, 0)
  assert.deepEqual(f.log, ['acquire:roam', 'release:roam'])
})

test('U-091 location source: 申请失败不订阅;重复 start 不叠加监听', () => {
  const f = fakes()
  const src = createRoamLocationSource({ tracker: f.tracker, wx: f.wx })
  f.tracker.grant = false
  let failed = 0
  src.start(() => {}, { fail: () => failed++ })
  assert.equal(failed, 1)
  assert.equal(f.listeners.size, 0)
  f.tracker.grant = true
  src.start(() => {})
  src.start(() => {})
  assert.equal(f.listeners.size, 1)
})
