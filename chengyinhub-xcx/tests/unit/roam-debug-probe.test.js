'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { roamDebugSnapshot, attachRoamDebugProbes } = require('../../utils/roam-debug-probe.js')

function fakePage() {
  return {
    data: { fogPolys: [1, 2], nearbyPois: [{ name: 'A' }, { name: 'B' }] },
    _fogMode: 'screen', _reveals: [{}, {}], _historyReveals: [{}],
    _pois: [
      { id: 1, cat: 'merchant', name: 'A', state: 'done', nodeId: 9, lat: 31.23, lng: 121.47 },
      { id: 'r1', cat: 'merchant', name: 'A', state: 'done', _roamId: 3, lat: 31.23, lng: 121.47 },
      { id: 2, cat: 'merchant', name: 'B', state: 'seen', lat: 31.24, lng: 121.47 },
    ],
    _player: { lat: 1, lng: 2 },
    _shopBadgeThreshold() { return 3 },
    setData(patch) { Object.assign(this.data, patch) },
  }
}

test('U-126 roamDebugSnapshot: 只读快照,字段与原 _dbg 一致', () => {
  const snap = roamDebugSnapshot(fakePage())
  assert.deepEqual(Object.keys(snap), ['fogMode', 'fogFlushes', 'fogCv', 'fogErr', 'fogPolys', 'fogUpdates', 'reveals', 'history',
    'pois', 'poisReal', 'poisRoam', 'poisSeen', 'hasPlayer', 'nearbyCards', 'nearbyNames', 'shopsDedup', 'shopsRaw', 'stamp',
    'shopBadgeThreshold', 'shopBadgeCfgLoaded', 'shopMedalFromServer'])
  assert.equal(snap.fogMode, 'screen')
  assert.equal(snap.fogPolys, 2)
  assert.equal(snap.shopsDedup, 1)
  assert.equal(snap.shopsRaw, 2)
  assert.deepEqual(snap.stamp, { done: 1, total: 2 })
  assert.equal(snap.nearbyNames, 'A|B')
})

test('U-126 attachRoamDebugProbes: 非开发版不挂载任何探针', () => {
  const page = fakePage()
  assert.equal(attachRoamDebugProbes(page, false), false)
  assert.equal(page._dbg, undefined)
  assert.equal(page._probeClearFog, undefined)
  assert.equal(page._probeFogPng, undefined)
})

test('U-126 attachRoamDebugProbes: 开发版挂载后行为与原页面方法一致', () => {
  const page = fakePage()
  assert.equal(attachRoamDebugProbes(page, true), true)
  assert.equal(page._dbg().reveals, 2)
  page._probeClearFog()
  assert.deepEqual(page.data.fogPolys, [])
  assert.equal(page._probeFogPng(), null)
  page._fogCv = { toDataURL: () => 'data:image/png;base64,QUJD' }
  assert.equal(page._probeFogPng(), 'QUJD')
})
