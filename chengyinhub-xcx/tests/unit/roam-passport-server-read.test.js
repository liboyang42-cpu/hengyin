'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { createReader, merge } = require('../../utils/roam-passport-read.js')
let actor, requests, storage, component, pageConfig
const app = { getUserID: () => actor, getAuthorization: () => 'token-' + actor,
  globalData: { features: {} }, isDevEnv: () => false, sendRequest: request => requests.push(request) }
function setup() {
  actor = '7'; requests = []; storage = {}
  global.getApp = () => app
  global.Behavior = value => value
  global.Component = value => { component = value }
  global.Page = value => { pageConfig = value }
  global.wx = { getStorageSync: key => storage[key], setStorageSync: (key, value) => { storage[key] = value },
    getWindowInfo: () => ({ statusBarHeight: 20 }), getSystemInfoSync: () => ({ statusBarHeight: 20 }), showToast() {} }
}
function loadComponent(name) {
  const path = '../../components/cy/' + name + '/index.js'
  delete require.cache[require.resolve(path)]; require(path)
  const instance = { ...component.methods, data: JSON.parse(JSON.stringify(component.data)),
    setData(patch) { Object.assign(this.data, patch) }, triggerEvent(name, detail) { this.event = { name, detail } } }
  for (const [key, property] of Object.entries(component.properties || {})) instance.data[key] = property.value
  return instance
}
const fact = (sessionId, extra) => ({ sessionId, startedAt: 1700000000000, finishedAt: 1700000060000, distanceM: 1200, shops: 2, ...extra })
const respond = (request, data) => request.success({ code: 200, data })
const tick = () => new Promise(resolve => setImmediate(resolve))
test.beforeEach(setup)

test('冷启动空本机读取服务端历史，Long游标分页并进入原会话', async () => {
  const view = loadComponent('scene-roam-history')
  const pending = view._load()
  assert.equal(requests[0].url, '/api/roam/passport/history')
  respond(requests[0], { items: [fact('9007199254740995')], hasMore: true, nextBefore: '9007199254740995' })
  await pending
  assert.equal(view.data.list[0].sessionId, '9007199254740995')
  assert.equal(view.data.list[0].distanceValue, 1.2)
  const more = view.loadMore()
  assert.equal(requests[1].data.before, '9007199254740995')
  respond(requests[1], { items: [fact('9007199254740994')], hasMore: false, nextBefore: null })
  await more
  assert.equal(view.data.list.length, 2)
  view.openSession({ currentTarget: { dataset: { sessionId: '9007199254740994', ts: 1700000060000 } } })
  assert.equal(view.event.detail.params.ts, 'server:9007199254740994')
  assert.deepEqual(storage, {}, '只读历史不写本机会话，不产生打卡或领奖写入')
})

test('断网不是零历史，失败可重试且分页倒退保留已读记录', async () => {
  const view = loadComponent('scene-roam-history')
  let pending = view._load(); requests[0].fail(); await pending
  assert.equal(view.data.state, 'error'); assert.equal(view.data.remoteError, true)
  pending = view._load()
  respond(requests[1], { items: [fact('10')], hasMore: true, nextBefore: '10' }); await pending
  pending = view.loadMore()
  respond(requests[2], { items: [fact('10')], hasMore: false }); await pending
  assert.equal(view.data.remoteError, true); assert.equal(view.data.list.length, 1)
  pending = view._load(); requests[3].fail(); await pending
  assert.equal(view.data.list[0].sessionId, '10')
})

test('历史原会话详情从服务端回读，未知值不伪造零且不采集照片轨迹', async () => {
  const view = loadComponent('scene-roam-session')
  view.data.ts = 'server:9007199254740995'
  const pending = view._load()
  assert.equal(requests[0].data.sessionId, '9007199254740995')
  respond(requests[0], fact('9007199254740995', { shops: null, distanceM: null }))
  await pending
  assert.equal(view.data.state, 'ready')
  assert.equal(view.data.session.shopsText, '—')
  assert.equal(view.data.session.distanceText, '—')
  assert.deepEqual(view.data.session.photos, [])
  assert.deepEqual(view.data.session.track, [])
  view.share(); assert.equal(view.event, undefined)
})

test('详情404与读取失败分开，切号迟到结果不进入新账号', async () => {
  const view = loadComponent('scene-roam-session'); view.data.sessionId = '10'
  let pending = view._load(); requests[0].success({ code: 404 }); await pending
  assert.equal(view.data.state, 'empty')
  pending = view._load(); requests[1].fail(); await pending
  assert.equal(view.data.state, 'error')
  pending = view._load(); actor = '8'; respond(requests[2], fact('10')); await pending
  assert.notEqual(view.data.state, 'ready')
  assert.deepEqual(view.data.session, { pois: [] })
})

test('旧本机记录兼容且同会话不重复，服务器统计优先，照片仍仅本机', () => {
  const local = [{ ts: 11, sessionId: '10', distance: 999, photos: [{ path: 'local', persisted: true }], track: [] }, { ts: 12, distance: 2 }]
  const remote = [{ sessionId: '10', ts: 100, distance: 1, shops: 0, photos: [], track: [], pois: [], serverFact: true }]
  const rows = merge(local, remote)
  assert.equal(rows.length, 2); assert.equal(rows[0].distance, 1); assert.equal(rows[0].ts, 11)
  assert.equal(rows[0].photos[0].path, 'local'); assert.equal(rows[1].serverFact, undefined)
})

test('护照真实Page只用服务端汇总，本机坏存储不阻断；失败保留错误而非零', async () => {
  delete require.cache[require.resolve('../../pages/roam/index.js')]; require('../../pages/roam/index.js')
  const view = { ...pageConfig, data: JSON.parse(JSON.stringify(pageConfig.data)),
    setData(patch) { Object.assign(this.data, patch) }, _loadActiveRoamEvents() {} }
  global.wx.getStorageSync = () => { throw new Error('storage unavailable') }
  let pending = view._loadIntroPassport()
  assert.equal(view.data.passportLoading, true)
  respond(requests[0], { trips: 51, distanceM: 12300, shops: null, days: 5 }); await pending
  assert.deepEqual(view.data.passport.stats.map(item => item.v), [51, '—', 5, '12.3'])
  pending = view.retryPassport(); requests[1].fail(); await pending
  assert.equal(view.data.passportError, true); assert.deepEqual(view.data.passport.stats, [])
})

test('离页dispose、错会话回包、非法游标均不接受，无请求写入', async () => {
  const reader = createReader(app, wx)
  let pending = reader.record('10'); respond(requests[0], fact('11')); await assert.rejects(pending)
  await assert.rejects(reader.page('9007199254740993x'))
  pending = reader.page(); reader.dispose(); respond(requests[1], { items: [], hasMore: false }); await assert.rejects(pending)
  assert.ok(requests.every(request => request.method === 'GET'))
})

test('原本人详情迟到响应不能覆盖随后打开的朋友分享快照', async () => {
  const view = loadComponent('scene-roam-session'); view.data.sessionId = '10'
  const pending = view._load()
  view.data.snapshot = { ts: 1700000060000, distance: 2, photos: [], track: [], pois: [], zone: '朋友分享' }
  view._load()
  respond(requests[0], fact('10'))
  await pending
  assert.equal(view.data.session.zone, '朋友分享')
  assert.equal(view.data.session.serverFact, undefined)
})

test('损坏的本机补充轨迹不使服务端原记录变成不可读，也不覆盖本机原像', async () => {
  storage['roam_memory_v1:7:sessions'] = [{ ts: Date.now(), finishedAt: Date.now(), sessionId: '10', track: [{ lat: 'bad', lng: 121 }], photos: [] }]
  const reader = createReader(app, wx)
  const pending = reader.record('10'); respond(requests[0], fact('10')); const row = await pending
  assert.equal(row.distance, 1.2); assert.deepEqual(row.track, []); assert.equal(row.localSupplement, false)
  assert.equal(storage['roam_memory_v1:7:sessions'][0].track[0].lat, 'bad')
})
