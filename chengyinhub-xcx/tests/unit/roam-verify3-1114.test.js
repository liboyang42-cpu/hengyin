'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const { createMarkerIds } = require('../../utils/roam-marker-ids.js')
const { sessionDateParts } = require('../../utils/roam-history-metrics.js')

function load(file) {
  let config
  global.getApp = () => ({ getUserID: () => '7', globalData: {}, getImgUrl: value => value })
  global.wx = { getStorageSync() {}, nextTick: fn => fn() }
  global.Page = global.Component = value => { config = value }
  global.Behavior = value => value
  global.getCurrentPages = () => []
  const absolute = path.resolve(__dirname, '../..', file)
  delete require.cache[require.resolve(absolute)]
  require(absolute)
  const view = { ...config, ...config.methods, data: structuredClone(config.data),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done() }, triggerEvent() {} }
  for (const [name, prop] of Object.entries(config.properties || {})) view.data[name] = prop.value
  return { config, view }
}

test('原生标记分配隔离所有业务 ID 和类型，稳定且不按数值段猜类型', () => {
  const allocate = createMarkerIds(), ids = []
  const business = [1, 99, 99997, 100000, 700000, 800000, 960000, 9912401, 9912406, '9007199254740995']
  for (const kind of ['poi', 'runner', 'dot', 'player', 'status']) {
    for (const key of business) {
      const id = allocate(kind, key)
      assert.ok(Number.isSafeInteger(id) && id > 0 && id < 2147483647)
      assert.equal(allocate(kind, key), id)
      ids.push(id)
    }
  }
  assert.equal(new Set(ids).size, ids.length)
})

test('真实尺寸地点经过实际 marker 构建后可重开，未知图钉只提示走近，跑者独立', async () => {
  const { view } = load('pages/roam/index.js')
  view._pois = [{ id: 10612401, _roamId: 9912401, state: 'done', cat: 'park', name: '已到达', lat: 31.2, lng: 121.4 },
    { id: 10612406, _roamId: 9912406, state: 'fog', cat: 'park', name: '未知地点', lat: 31.201, lng: 121.4 }]
  view._player = { lat: 31.2, lng: 121.4 }
  view._runners = [{ memberId: '9912401', lat: 31.2, lng: 121.4 }]
  view._icons = { 'runner-9912401': 'avatar' }; view.data.mapReady = true; view.data.mapScale = 17
  view._genNpcIcons = () => Promise.resolve(false); view._searchPlaces = () => []
  view._syncMarkers()
  assert.equal(new Set(view.data.markers.map(m => m.id)).size, view.data.markers.length)
  const targets = [...view._markerTargets]
  const found = targets.find(([, m]) => m.kind === 'poi' && m.target._roamId === 9912401)
  const unknown = targets.find(([, m]) => m.kind === 'poi' && m.target._roamId === 9912406)
  assert.ok(found && unknown)
  let opened, runner
  view._openPlaceCard = poi => { opened = poi }
  view.openRunner = id => { runner = id }
  view.onMarkerTap({ detail: { markerId: found[0] } })
  assert.equal(opened._roamId, 9912401)
  opened = null
  const toasts = []
  global.wx.showToast = value => toasts.push(value.title)
  view.onMarkerTap({ detail: { markerId: unknown[0] } })
  assert.equal(opened, null)
  assert.match(toasts.join(), /走近点亮/)
  const avatar = targets.find(([, m]) => m.kind === 'runner')
  view.onMarkerTap({ detail: { markerId: avatar[0] } })
  assert.equal(runner, '9912401')
  view.onMarkerTap({ detail: { markerId: 10612401 } })
  assert.equal(opened, null, '旧业务 ID 不得误判到其他标记')
})

test('图钉小卡随移动刷新距离，关闭后不再更新', () => {
  const { view } = load('pages/roam/index.js')
  view._pois = []; view._player = { lat: 31.2, lng: 121.4 }
  view._placeCardPoi = { id: 10612401, lat: 31.2, lng: 121.4, name: '地点' }
  view.data.placeCard = view._placeCardFor(view._placeCardPoi)
  assert.equal(view.data.placeCard.distText, '1 米')
  view._player = { lat: 31.21, lng: 121.4 }; view._checkNear(view._player)
  assert.match(view.data.placeCard.distText, /公里/)
  view._syncMarkers = () => {}; view.closePlaceCard(); view._checkNear(view._player)
  assert.equal(view.data.placeCard, null)
})

test('运行读数从实际移动入口使用一位小数', () => {
  const { view } = load('pages/roam/index.js')
  view._dist = 1580; view._stats = { distance: '0.0' }; view._track = []
  view._syncHeading = view._updateHeading = view._checkNear = view._syncMarkers = () => {}
  const stats = []
  view._rollStat = (key, value) => stats.push([key, value])
  view._applyMovePatch({ lat: 31.2, lng: 121.4 })
  assert.ok(stats.some(([key, value]) => key === 'distance' && value === '1.6'))
})

test('已有定位权限的入口自动读新坐标，拒绝/后台不请求；自动失败不弹设置', () => {
  const { view } = load('pages/roam/index.js')
  view.data.screen = 'entry-map'; let locations = 0, settings = 0, granted = true
  global.wx.getSetting = req => req.success({ authSetting: { 'scope.userLocation': granted } })
  global.wx.getLocation = req => { locations++; req.fail(); req.complete() }
  global.wx.openSetting = () => { settings++ }
  view._syncRoamPermission(); assert.equal(locations, 1); assert.equal(settings, 0)
  granted = false; view._syncRoamPermission(); assert.equal(locations, 1)
  granted = true; view._hidden = true; view._syncRoamPermission(); assert.equal(locations, 1)
})

test('日期与开始时间同用服务端中国时区，跨日不混用设备时间或结束时间', () => {
  const record = { startedAt: '2026-11-15 04:47:00', ts: Date.parse('2026-11-15T17:00:00Z'), pois: [] }
  assert.deepEqual(sessionDateParts(record), { year: 2026, month: 11, day: 15, hours: 4, minutes: 47, weekday: 0 })
  const { view } = load('subpackageRoam/session/index.js')
  view._setSessionView(record)
  assert.equal(view.data.s.dateFull, '11月15日 周日')
  assert.equal(view.data.s.startTime, '04:47')
})

test('2077 早到 updated 不能撤掉最短占位，切样式和卸载取消旧定时器', t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'] })
  const { view, config } = load('components/cy/free-map/index.js')
  view._mergeLines = view._rebuild = () => {}
  view.data.tone = '2077'; config.properties.tone.observer.call(view, '2077', 'black')
  const update = { currentTarget: { dataset: { generation: 1 } }, detail: {} }
  t.mock.timers.tick(1000); view.onUpdated(update)
  t.mock.timers.tick(2499); assert.equal(view.data.mapLoaded, false)
  t.mock.timers.tick(1); assert.equal(view.data.mapLoaded, true)
  view.data.tone = 'black'; config.properties.tone.observer.call(view, 'black', '2077')
  view.data.tone = '2077'; config.properties.tone.observer.call(view, '2077', 'black')
  view.onUpdated(update); t.mock.timers.tick(5000); assert.equal(view.data.mapLoaded, false)
  view.data.tone = 'black'; config.properties.tone.observer.call(view, 'black', '2077')
  view.data.tone = '2077'; config.properties.tone.observer.call(view, '2077', 'black')
  view.onUpdated({ ...update, currentTarget: { dataset: { generation: 5 } } })
  config.lifetimes.detached.call(view); t.mock.timers.tick(5000)
  assert.equal(view.data.mapLoaded, false)
})
