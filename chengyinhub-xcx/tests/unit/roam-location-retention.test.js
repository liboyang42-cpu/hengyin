const test = require('node:test')
const assert = require('node:assert/strict')
const { THIRTY_DAYS, stripGeometry, pruneSessions } = require('../../utils/roam-location-retention')
const { createPlayerRoamMemory } = require('../../utils/roam-player-memory')
test('30-day boundary strips nested coordinates while preserving real statistics', () => {
  const now = 200 * THIRTY_DAYS
  const rows = pruneSessions([{ ts: now - THIRTY_DAYS, distance: 3, xpAwarded: 7, track: [{ lat: 1 }], nested: { lng: 2, shops: 4 } },
    { ts: now - THIRTY_DAYS + 1, track: [{ lat: 3 }] }], null, now)
  assert.deepEqual(rows[0], { ts: now - THIRTY_DAYS, distance: 3, xpAwarded: 7, nested: { shops: 4 }, locationExpired: true })
  assert.deepEqual(rows[1].track, [{ lat: 3 }])
})
test('unconfirmed recovery keeps its linked run until real server finish readback', () => {
  const row = { ts: 1, track: [{ lat: 1 }] }
  assert.deepEqual(pruneSessions([row], { archiveTs: 1 }, THIRTY_DAYS + 1), [row])
  assert.equal(pruneSessions([row], { archiveTs: 1, finishedAt: 1 }, THIRTY_DAYS + 1)[0].track, undefined)
  assert.deepEqual(stripGeometry({ totalXp: 5, photos: [{ latitude: 2, title: 'a' }] }), { totalXp: 5, photos: [{ title: 'a' }] })
})
test('actual player store persists cleanup and never silently succeeds on write failure', () => {
  const source = [{ ts: 1, distance: 2, track: [{ lat: 3 }] }]
  let saved
  const memory = createPlayerRoamMemory(8, { getStorageSync: k => k.endsWith(':sessions') ? source : null,
    setStorageSync: (k, v) => { saved = v } })
  assert.equal(memory.readSessionState().sessions[0].track, undefined)
  assert.equal(saved[0].distance, 2)
  const failing = createPlayerRoamMemory(9, { getStorageSync: k => k.endsWith(':sessions') ? source : null,
    setStorageSync: () => { throw Error('disk full') } })
  assert.deepEqual(failing.readSessionState(), { ok: false, sessions: [] })
})
test('expired global tile/reveal geometry and timestamp keys are actually removed, not renewed by reads', () => {
  const values = { 'roam_memory_v1:8:tiles': { tiles: { old: 1, legacy: 1, recent: 1 }, retentionAt: { old: 1, recent: Date.now() }, serverCursor: 7 },
    'roam_memory_v1:8:reveals': [{ lat: 1, lng: 2, retentionAt: 1 }, { lat: 3, lng: 4 }, { lat: 5, lng: 6, retentionAt: Date.now() }] }
  const memory = createPlayerRoamMemory(8, { getStorageSync: k => values[k], setStorageSync: (k,v) => { values[k] = v } })
  assert.deepEqual(memory.readTiles(), { tiles: { recent: 1 }, serverCursor: 7 })
  assert.deepEqual(Object.keys(values['roam_memory_v1:8:tiles'].retentionAt), ['recent'])
  assert.deepEqual(memory.readReveals(), [{ lat: 5, lng: 6 }])
  const before = values['roam_memory_v1:8:tiles'].retentionAt.recent
  memory.readTiles(); memory.writeTiles({ recent: 1 }, 7)
  assert.equal(values['roam_memory_v1:8:tiles'].retentionAt.recent, before)
})

test('unknown or impossible archive time strips geometry and an empty recovery object never grants an exception', () => {
  const now = Date.now()
  for (const row of [{ distance: 5, track: [{ lat: 1 }] }, { ts: 0, xpAwarded: 7, pois: [{ id: 3 }] },
    { ts: now + THIRTY_DAYS, distance: 8, track: [{ lng: 2 }] }]) {
    const clean = pruneSessions([row], {}, now)[0]
    assert.equal(clean.track, undefined)
    assert.equal(clean.pois, undefined)
    assert.equal(clean.distance, row.distance)
    assert.equal(clean.xpAwarded, row.xpAwarded)
    assert.equal(clean.locationExpired, true)
  }
})

test('recovery read failure cannot erase potentially active geometry; retry keeps active run', () => {
  const values = { 'roam_memory_v1:8:tiles': { tiles: { active: 1 }, retentionAt: { active: 1 }, serverCursor: 7 },
    'roam_memory_v1:8:sessions': [{ ts: 1, track: [{ lat: 3 }] }],
    'roam_memory_v1:8:recovery': { archiveTs: 1, allTiles: ['active'] } }
  let unavailable = true
  const memory = createPlayerRoamMemory(8, { getStorageSync: k => {
    if (unavailable && k.endsWith(':recovery')) throw Error('storage read failed')
    return values[k]
  }, setStorageSync: (k,v) => { values[k] = v } })
  const before = JSON.stringify(values)
  assert.throws(() => memory.readTiles(), /读取/)
  assert.deepEqual(memory.readSessionState(), { ok: false, sessions: [] })
  assert.equal(JSON.stringify(values), before)
  unavailable = false
  assert.deepEqual(memory.readTiles().tiles, { active: 1 })
  assert.deepEqual(memory.readSessionState().sessions[0].track, [{ lat: 3 }])
})
test('reveal read failure neither reports empty history nor overwrites it', () => {
  let written = false
  const memory = createPlayerRoamMemory(8, { getStorageSync: () => { throw Error('unavailable') },
    setStorageSync: () => { written = true } })
  assert.throws(() => memory.readReveals(), /读取/)
  assert.equal(memory.writeReveals([{ lat: 1, lng: 2 }]), false)
  assert.equal(written, false)
})
