'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const { formatRoamMoment } = require('../../utils/roam-history-metrics.js')
const { normalizeRunners } = require('../../utils/roam-runners.js')
const { createReader } = require('../../utils/roam-passport-read.js')

function load(file) {
  let definition
  global.getApp = () => ({ getUserID: () => '7', globalData: {}, getImgUrl: value => value })
  global.wx = { getStorageSync() {}, nextTick: fn => fn(), getWindowInfo: () => ({}) }
  global.Page = global.Component = value => { definition = value }
  global.Behavior = value => value
  global.getCurrentPages = () => []
  const absolute = path.resolve(__dirname, '../..', file)
  delete require.cache[require.resolve(absolute)]
  require(absolute)
  const view = { ...definition, ...definition.methods, data: structuredClone(definition.data), triggerEvent() {},
    setData(patch, done) {
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.'); let target = this.data
        parts.slice(0, -1).forEach(part => { target = target[part] })
        target[parts.at(-1)] = value
      }
      if (done) done()
    } }
  for (const [key, prop] of Object.entries(definition.properties || {})) view.data[key] = prop.value
  return { view, definition }
}

test('出发定位在延迟开启持续定位后仍是首段锚点，恢复不跨暂停补里程', t => {
  const start = 1800000000000
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: start })
  const { view } = load('pages/roam/index.js')
  for (const name of ['_disposeFogRenderer', '_initWorld', '_observeLocationRevocation', '_syncPlayHeader', '_scheduleFootprintHint', '_syncGoal', '_mapLoadWatch', '_applyMovePatch', '_syncIdle', '_addReveal']) view[name] = () => {}
  view._buildOpeningEmojis = () => []
  view._track = []; view._reveals = []; view._dist = 0
  view._openRoamMap({ lat: 31.2, lng: 121.4, accuracy: 10 }, true)
  assert.deepEqual(view._sessionStartFix, { lat: 31.2, lng: 121.4, ts: start })
  global.wx.startLocationUpdate = req => req.success()
  global.wx.onLocationChange = () => {}
  view._startReal(true)
  assert.equal(view._realAnchor.ts, start)
  assert.deepEqual(view._track, [{ lat: 31.2, lng: 121.4 }])
  t.mock.timers.setTime(start + 60000)
  view.data.screen = 'map'
  view._realTick({ latitude: 31.2025, longitude: 121.4, accuracy: 10 })
  assert.ok(view._dist > 275 && view._dist < 280, '首个移动回调必须算上出发点至此的完整一段')
  assert.equal(view._track[0].lat, 31.2)
  assert.equal(view._track.at(-1).lat, 31.2025)
  global.wx.offLocationChange = () => {}
  view._stopReal()
  assert.equal(view._sessionStartFix, null)
  view._startReal(true)
  assert.equal(view._realAnchor, null, '已有轨迹的暂停恢复不连接旧起点')
  view._stopReal()
})

test('结算时刻统一为中国日期与24小时，服务端确认后使用服务器时刻', () => {
  assert.deepEqual(formatRoamMoment('2026-11-15T06:36:00+08:00'), { date: '11月15日', dateLine: '11月15日 · 06:36' })
  assert.equal(formatRoamMoment('2026-11-15T14:36:00+08:00').dateLine, '11月15日 · 14:36')
  assert.deepEqual(formatRoamMoment('bad'), { date: '', dateLine: '' })
})

test('打开卡片后玩家将进入搜索栏遮挡区即恢复跟随，关闭卡片立即回中', () => {
  const { view } = load('pages/roam/index.js')
  view._pinRegion = { southwest: { latitude: 31.19, longitude: 121.39 }, northeast: { latitude: 31.21, longitude: 121.41 } }
  view._win = { w: 390, h: 844, safeBottom: 34 }
  view.data.center = { lat: 31.2, lng: 121.4 }
  view._pinFocusId = 12; view._hold = Date.now() + 60000; view._dist = 0; view._stats = { distance: '0.0' }; view._track = []
  for (const name of ['_updateHeading', '_syncStats', '_checkNear', '_reportPresence', '_fetchRunners', '_maybeRefetchPois', '_syncMarkers']) view[name] = () => {}
  const point = { lat: 31.209, lng: 121.4 }
  assert.equal(view._playerOutsideSafeView(point), true)
  view._applyMovePatch(point)
  assert.deepEqual(view.data.center, point)
  assert.equal(view._hold, 0)
  assert.equal(view._pinFocusId, null)
  view._player = { lat: 31.201, lng: 121.4 }; view._hold = Date.now() + 60000
  view.closePlaceCard()
  assert.equal(view._hold, 0)
  assert.deepEqual(view.data.center, view._player)
})

test('真实样式生效信号提前撤占位，非样式、失败和旧代事件不能撤', () => {
  const { view, definition } = load('components/cy/free-map/index.js')
  view._mergeLines = view._rebuild = () => {}
  view.data.tone = '2077'; definition.properties.tone.observer.call(view, '2077', 'black')
  const event = (generation, ability, errCode) => ({ currentTarget: { dataset: { generation } }, detail: { ability, errCode } })
  for (const e of [event(0, 'layer-style', 0), event(1, 'auth', 0), event(1, 'layer-style', 1000)]) view.onStyleReady(e)
  assert.equal(view.data.mapLoaded, false)
  view.onStyleReady(event(1, 'layer-style', 0))
  assert.equal(view.data.mapLoaded, true)
})

test('官方目录无图地点有既有主题封面与公开分享，演示点仍不分享', () => {
  const { view } = load('pages/roam/index.js')
  const poi = { id: 8, _roamId: 9912406, name: '山阴路', lat: 31.2, lng: 121.4, tags: '["roam:seed:v1:sh-place"]' }
  const card = view._placeCardFor(poi)
  assert.equal(card.photo, '/images/home-route-city-placeholder.jpg')
  assert.equal(card.shareKind, 'poi'); assert.equal(card.shareId, '9912406')
  assert.equal(view._placeCardFor({ ...poi, demo: true }).shareKind, '')
})

test('退出后无本机记录时，历史从服务器读取并显示冻结地区名', async () => {
  const app = { getUserID: () => '7', sendRequest(req) {
    req.success({ code: 200, data: { items: [{ sessionId: '9', startedAt: 1800000000000, finishedAt: 1800000060000, zone: '上海', distanceM: 1240, shops: 0 }], hasMore: false } })
  } }
  const page = await createReader(app, {}).page(null)
  assert.equal(page.rows[0].zone, '上海')
  assert.equal(page.rows[0].distance, 1.24)
  const { view } = load('components/cy/scene-roam-history/index.js')
  view._sessions = page.rows; view._sync()
  assert.equal(view.data.list[0].title, '上海漫游')
})

test('附近全部为空时计数与空态使用同一节点口径', () => {
  const { view } = load('subpackageRoam/nearby/index.js')
  view._items = view._teams = view._pois = []; view._icons = {}; view._far = {}; view._nearbyTeamsLoaded = view._nearbyTopicsLoaded = true
  view._wantPins = () => {}; view._poiMarkers = () => []
  view._syncMarkers()
  assert.equal(view.data.headerText, '附近 · 0 个节点')
  assert.equal(view.data.listLoaded, true); assert.equal(view.data.listRows.length, 0)
})

test('漫游者缺失统计保留未知，真实零值保留且没有空百分号', () => {
  const [unknown, zero] = normalizeRunners([{ memberId: 7, lat: 31.2, lng: 121.4 }, { memberId: 8, lat: 31.2, lng: 121.4, explorePct: 0, shops: 0, elapsedSec: 0 }])
  assert.equal(unknown.explorePct, null); assert.equal(unknown.shops, null); assert.equal(unknown.elapsed, ''); assert.equal(unknown.since, '')
  assert.equal(zero.explorePct, 0); assert.equal(zero.shops, 0); assert.equal(zero.elapsed, '00:00')
})
