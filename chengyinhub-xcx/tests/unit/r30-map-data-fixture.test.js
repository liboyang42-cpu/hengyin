'use strict'

// Synthetic coordinates and transport/SDK boundaries only; datum, GPS and native tiles remain UNKNOWN.
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const memoryStorage = require('../helpers/memory-storage.js')
const ROAM = '../../pages/roam/index.js'
const RECORD = '../../components/cy/scene-roam-session/index.js'
const CARD = '../../subpackageRoam/components/roam-place-card/index.js'
const TRACK = [[31.2, 121.4], [31.202, 121.4], [31.202, 121.403], [31.201, 121.401]]
const tick = () => new Promise(setImmediate)

function fixture() {
  const requests = [], opened = [], storage = memoryStorage()
  let owner = '7', token = 'synthetic-token', definition
  const app = { getUserID: () => owner, getAuthorization: () => token,
    globalData: { features: {} }, isDevEnv: () => false, sendRequest: request => requests.push(request) }
  global.getApp = () => app
  global.wx = { ...storage, getWindowInfo: () => ({ statusBarHeight: 20 }),
    getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    createSelectorQuery: () => ({ select() { return this }, fields() { return this }, exec(callback) { callback([]) } }),
    openLocation: options => opened.push(options), showToast() {} }
  global.Behavior = value => value
  global.Page = global.Component = value => { definition = value }
  function load(relative, source) {
    const absolute = require.resolve(relative)
    if (source) {
      const mod = new Module(absolute, module)
      mod.filename = absolute
      mod.paths = Module._nodeModulePaths(path.dirname(absolute))
      mod._compile(source, absolute)
    } else {
      delete require.cache[absolute]
      require(absolute)
    }
    const instance = { ...(definition.methods || definition), data: structuredClone(definition.data),
      setData(patch, callback) {
        for (const [key, value] of Object.entries(patch)) {
          const parts = key.split('.')
          let target = this.data
          for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {})
          target[parts.at(-1)] = value
        }
        callback?.()
      } }
    for (const [key, property] of Object.entries(definition.properties || {})) instance.data[key] = property.value
    instance.data.reducedMotion = true
    if (relative === ROAM) instance._pois = []
    return instance
  }
  return { requests, opened, storage, load, changeOwner() { owner = '8' }, rotateToken() { token += '-new' } }
}

const poi = (id, lat, lng) => ({ id, lat, lng, type: 1, name: 'synthetic POI ' + id, address: 'synthetic address', found: false })
const fact = (sessionId, track = TRACK) => ({ sessionId, startedAt: 1700000000000,
  finishedAt: 1700000065000, distanceM: 1234, shops: null, track })
function respond(request, data) { request.success({ code: 200, data }) }
async function fetchPois(f, page, rows) {
  const pending = page._fetchRoamPois({ lat: 31.2, lng: 121.4 })
  await tick()
  const request = f.requests.at(-1)
  assert.equal(request.url, '/api/roam/pois')
  assert.deepEqual(request.data, { lat: '31.2', lng: '121.4', radius: '3000' })
  respond(request, rows)
  await pending
  await tick()
}
async function readRecord(f, view, id, row) {
  view.data.sessionId = id
  const pending = view._load()
  const request = f.requests.at(-1)
  assert.equal(request.url, '/api/roam/passport/record')
  assert.deepEqual(request.data, { sessionId: id })
  respond(request, row)
  await pending
}
function assertNoRoute(view) {
  assert.deepEqual(view.data.routeSegs, [])
  assert.deepEqual(view.data.routePoiDots, [])
  assert.deepEqual(view.data.replaySegs, [])
  assert.equal(view.data.replayProgress, 0)
}

test('synthetic POI fetch → real Page card → SDK navigation keeps selected returned axes and identity', async () => {
  const f = fixture(), page = f.load(ROAM), card = f.load(CARD)
  const rows = [poi(32, '31.203', '121.409'), poi(11, '30.66', '104.06')]
  const before = structuredClone(rows)
  await fetchPois(f, page, rows)
  assert.deepEqual(page._pois.map(p => p._roamId), [32, 11])
  card.data.place = page._placeCardFor(page._pois[1])
  card.onNavigate()
  assert.deepEqual(f.opened, [{ latitude: 30.66, longitude: 104.06,
    name: rows[1].name, address: rows[1].address, scale: 17 }])
  assert.deepEqual(rows, before, 'fetch and normalization must preserve the response')
  assert.ok(f.requests.every(r => r.method === 'GET'))
})

test('synthetic malformed POI coordinates never become map/navigation points; valid zero survives', async () => {
  const f = fixture(), page = f.load(ROAM)
  const bad = [[null, 121], ['', 121], [NaN, 121], [91, 121], [31, 181], ['31oops', 121], [true, 121]]
  const rows = bad.map(([lat, lng], i) => poi(i + 1, lat, lng)).concat([poi(90, 0, 0)])
  const before = structuredClone(rows)
  await fetchPois(f, page, rows)
  assert.deepEqual(page._pois.map(p => p._roamId), [90])
  assert.deepEqual(rows, before)
  const card = f.load(CARD)
  card.data.place = page._placeCardFor(page._pois[0]); card.onNavigate()
  assert.equal(f.opened[0].latitude, 0)
  assert.equal(f.opened[0].longitude, 0)
})

test('synthetic SDK navigation rejects malformed direct card coordinates without coercing them to zero', () => {
  const f = fixture(), card = f.load(CARD)
  for (const [lat, lng] of [[null, 121], ['', 121], [NaN, 121], [Infinity, 121],
    [91, 121], [-91, 121], [31, 181], [31, -181], ['31oops', 121], [true, 121], [[], 121]]) {
    card.data.place = { lat, lng }; card.onNavigate()
  }
  assert.deepEqual(f.opened, [])
})

test('synthetic overlapping POI fetches and account change cannot supply a stale navigation target', async () => {
  const f = fixture(), page = f.load(ROAM), card = f.load(CARD)
  const old = page._fetchRoamPois({ lat: 31, lng: 121 }); await tick()
  const oldRequest = f.requests[0]
  await fetchPois(f, page, [poi(2, 30.66, 104.06)])
  respond(oldRequest, [poi(1, 31, 121)]); await old
  card.data.place = page._placeCardFor(page._pois[0]); card.onNavigate()
  assert.equal(f.opened[0].name, 'synthetic POI 2')
  const switched = page._fetchRoamPois({ lat: 31, lng: 121 }); await tick()
  f.changeOwner(); respond(f.requests.at(-1), [poi(3, 31, 121)]); await switched
  assert.deepEqual(page._pois.map(p => p._roamId), [2])
})

test('synthetic record fetch → normalization → replay retains actual point order and server metrics', async () => {
  const f = fixture(), view = f.load(RECORD), row = fact('9007199254740993')
  const before = structuredClone(row)
  await readRecord(f, view, row.sessionId, row)
  assert.equal(view.data.state, 'ready')
  assert.deepEqual(view.data.session.track, [
    { lat: 31.2, lng: 121.4 }, { lat: 31.202, lng: 121.4 },
    { lat: 31.202, lng: 121.403 }, { lat: 31.201, lng: 121.401 },
  ])
  assert.equal(view.data.session.distance, 1.234)
  assert.equal(view.data.session.time, '0:01')
  assert.equal(view.data.session.shopsText, '—')
  assert.equal(view.data.routeSegs.length, 3)
  assert.deepEqual(view.data.routeSegs.map(s => s.deg), [-90, 0, 150], 'north, east, then southwest; no sorting or axis swap')
  assert.equal(view.data.session.coordinateSystem, undefined, 'absent datum stays unknown')
  assert.deepEqual(row, before)
})

test('synthetic missing, empty, malformed and single-point remote tracks never borrow an earlier route', async () => {
  const f = fixture(), view = f.load(RECORD)
  const cases = [undefined, null, [], [[31, 121]], [[31, 121], [NaN, 121]],
    [[31, 121], [91, 121]], [[31, 121], [31, 181]], [[31, 121], [null, 121]],
    [[31, 121], ['31', 121]], { lat: 31, lng: 121 }]
  for (const track of cases) {
    await readRecord(f, view, '10', fact('10'))
    assert.ok(view.data.routeSegs.length)
    await readRecord(f, view, '11', { ...fact('11'), track })
    assert.equal(view.data.state, 'ready')
    assertNoRoute(view)
  }
})

test('synthetic record switch clears old route while pending and after network/404/invalid identity', async () => {
  const f = fixture(), view = f.load(RECORD)
  for (const failure of ['network', '404', 'identity']) {
    await readRecord(f, view, '10', fact('10'))
    view.data.sessionId = '11'
    const pending = view._load(), request = f.requests.at(-1)
    assert.equal(view.data.state, 'loading')
    assertNoRoute(view)
    if (failure === 'network') request.fail()
    else if (failure === '404') request.success({ code: 404 })
    else respond(request, fact('12'))
    await pending
    assert.equal(view.data.state, failure === '404' ? 'empty' : 'error')
    assertNoRoute(view)
    view.startReplay()
    assertNoRoute(view)
  }
})

test('synthetic record request/account/token invalidation cannot restore prior route arrays', async () => {
  for (const invalidation of ['request', 'account', 'token']) {
    const f = fixture(), view = f.load(RECORD)
    await readRecord(f, view, '10', fact('10'))
    view.data.sessionId = '11'
    const old = view._load(), oldRequest = f.requests.at(-1)
    if (invalidation === 'request') await readRecord(f, view, '12', fact('12', []))
    else if (invalidation === 'account') f.changeOwner()
    else f.rotateToken()
    respond(oldRequest, fact('11')); await old
    assertNoRoute(view)
    assert.notEqual(view.data.session.sessionId, '11')
  }
})

test('synthetic invalid local snapshot clears previously loaded replay instead of leaving forged route', async () => {
  const f = fixture(), view = f.load(RECORD)
  await readRecord(f, view, '10', fact('10'))
  view.data.snapshot = { ts: 1700000065000, track: [{ lat: 91, lng: 121 }], pois: [] }
  view._load()
  assert.equal(view.data.state, 'error')
  assertNoRoute(view)
})

test('negative control: swapping actual Page POI axes in memory is caught at SDK navigation', async () => {
  const f = fixture()
  const source = fs.readFileSync(require.resolve(ROAM), 'utf8')
  const mutant = source.replace(/lat: (?:parseFloat|Number)\(n.lat\), lng: (?:parseFloat|Number)\(n.lng\)/,
    'lat: Number(n.lng), lng: Number(n.lat)')
  assert.notEqual(mutant, source, 'mutation must alter production source')
  const page = f.load(ROAM, mutant), card = f.load(CARD)
  await fetchPois(f, page, [poi(2, 30.66, 104.06)])
  card.data.place = page._placeCardFor(page._pois[0]); card.onNavigate()
  assert.throws(() => assert.deepEqual(f.opened, [{ latitude: 30.66, longitude: 104.06,
    name: 'synthetic POI 2', address: 'synthetic address', scale: 17 }]), assert.AssertionError)
})
