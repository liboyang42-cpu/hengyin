'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const { footprintCount, readDurationSeconds, formatDistance } = require('../../utils/roam-history-metrics.js')
const { readPlace, readStripPlace } = require('../../utils/roam-place-detail.js')
const { DANGER_ACTIONS } = require('../../utils/danger-actions.js')

function component(file, app = { getUserID: () => '', globalData: {}, getImgUrl: v => v }) {
  let definition
  global.getApp = () => app
  global.wx = { getStorageSync() {}, nextTick: fn => fn() }
  global.Component = value => { definition = value }
  global.Page = value => { definition = value }
  global.getCurrentPages = () => []
  global.Behavior = value => value
  const absolute = path.resolve(__dirname, '../..', file)
  delete require.cache[require.resolve(absolute)]
  require(absolute)
  const instance = Object.assign({}, definition, definition.methods, { data: structuredClone(definition.data),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done() }, triggerEvent() {}, selectComponent() { return null } })
  return { definition, instance }
}
const place = { poiId: 9912406, name: '目录地点', lat: 31.2, lng: 121.4, tags: '["roam:park","roam:seed:v1:sh-place"]' }
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }

test('到达数只数同一份真实地点，探店数不代替足迹数', () => {
  const pois = [{ id: 1 }, { id: '1' }, { id: 2 }, { id: 3, demo: true }]
  assert.equal(footprintCount({ pois, shops: 99 }), 2)
  assert.equal(footprintCount({ places: pois, pois: [], shops: 0 }), 2)
  assert.equal(footprintCount({ places: [], pois }), 0)
  assert.equal(footprintCount(null), 0)
})

test('用时汇总接受数值字符串与旧 mm:ss，距离共用一位小数', () => {
  assert.equal(readDurationSeconds('420'), 420)
  assert.equal(readDurationSeconds(null, '07:00'), 420)
  assert.equal(readDurationSeconds('', 'bad'), null)
  assert.equal(readDurationSeconds(0, '07:00'), 0)
  assert.equal(formatDistance(0.38), '0.4')
  const { buildPlayHud } = require('../../utils/play-ui-contract.js')
  assert.equal(buildPlayHud({ mode: 'explore', distanceKm: 0.38 }).left.value, '0.4')
})

test('历史总用时与每张记录卡读取同一份时长', () => {
  const { instance } = component('components/cy/scene-roam-history/index.js')
  instance._sessions = [{ ts: 1770000000000, durSec: '420', distance: 0.38, shops: 0 },
    { ts: 1770000001000, time: '02:00', distance: 0, shops: 0 }]
  instance._sync()
  assert.equal(instance.data.list[1].time, '0:07')
  assert.equal(instance.data.summary.timeText, '0:09')
})

test('已确认地点卡移动后刷新距离，已完成图钉仍能点开', () => {
  const { instance } = component('pages/roam/index.js')
  const poi = { id: 42, _roamId: 42, cat: 'park', state: 'done', name: '公园', lat: 31, lng: 121 }
  instance._pois = [poi]; instance._player = { lat: 31, lng: 121 }
  instance.data.paceCard = instance._paceCardFor(poi)
  assert.equal(instance.data.paceCard.distText, '1 米')
  instance._player = { lat: 31.015, lng: 121 }
  instance._checkNear(instance._player)
  assert.match(instance.data.paceCard.distText, /公里/)
  assert.equal(instance.data.paceCard.inReach, false)
  let opened = null
  instance._openPlaceCard = value => { opened = value }
  instance.onMarkerTap({ detail: { markerId: 42 } })
  assert.equal(opened, poi)
})

test('地点标题更新当前栈，迟到的其他地点标题不覆盖', () => {
  const { instance } = component('subpackageMember/components/cy/scene-deep-link/index.js')
  const scene = { id: 'roam-poi-detail', params: { poiId: '42' }, title: '节点详情' }
  instance.data.sceneStack = [scene]; instance.data.sceneCurrent = { ...scene }
  instance.onPoiTitle({ detail: { poiId: '42', name: '公园' } })
  assert.equal(instance.data.sceneStack[0].title, '公园')
  instance.onPoiTitle({ detail: { poiId: '43', name: '别处' } })
  assert.equal(instance.data.sceneCurrent.title, '公园')
})

test('本城推荐超出附近范围时按种子找真实 ID，再读完整公开详情', async () => {
  const calls = []
  const app = { sendRequest(req) { calls.push(req); req.success({ code: 200, data: req.url.endsWith('/pois')
    ? [{ ...place, id: place.poiId, type: 1 }] : place }) } }
  const row = await readStripPlace(app, { seed: 'roam:seed:v1:sh-place' }, [], [31.2, 121.4])
  assert.equal(row.id, 9912406)
  assert.deepEqual(calls.map(r => r.url), ['/api/roam/pois', '/api/city/places/9912406'])
  assert.ok(calls.every(r => r.method === 'GET'))
  await assert.rejects(readPlace({ sendRequest(req) { req.success({ code: 200, data: { ...place, poiId: 1 } }) } }, 9912406))
})

test('目录不存在时不按同名点猜 ID，也不伪造无坐标文字卡', async () => {
  const calls = []
  await assert.rejects(readStripPlace({ sendRequest(req) { calls.push(req); req.success({ code: 200, data: [{ ...place, id: 2, type: 1 }] }) } },
    { seed: 'roam:seed:v1:missing' }, [], [31.2, 121.4]))
  assert.equal(calls.length, 1)
})

test('poi-detail 只在节点 410 后尝试目录，不把拒权当目录', async () => {
  const calls = [], events = []
  const app = { getUserID: () => '', globalData: {}, getImgUrl: v => v,
    sendRequest(req) { calls.push(req); req.success({ code: req.url.includes('/nodes/') ? 410 : 200, data: place }) } }
  const { instance } = component('subpackageMember/components/cy/scene-roam-poi-detail/index.js', app)
  instance.data.poiId = '9912406'; instance.data.merchantId = ''
  instance.triggerEvent = (name, detail) => events.push({ name, detail })
  instance._load(); await flush()
  assert.equal(instance.data.state, 'ready')
  assert.equal(instance.data.isCatalog, true)
  assert.equal(instance.data.hasLocation, true)
  assert.equal(instance.data.node.name, place.name)
  assert.ok(events.some(e => e.name === 'titlechange' && e.detail.name === place.name))
  assert.equal(calls.length, 2)
  calls.length = 0
  app.sendRequest = req => { calls.push(req); req.successStatusAbnormal({ code: 403 }) }
  instance._load(); await flush()
  assert.equal(instance.data.state, 'error')
  assert.equal(calls.length, 1)
})

test('2077 重新挂原生地图，旧 updated 无法撤掉新占位', () => {
  const { definition, instance } = component('components/cy/free-map/index.js')
  instance._mergeLines = instance._rebuild = () => {}
  instance.data.mapLoaded = true
  definition.properties.tone.observer.call(instance, '2077', 'black')
  assert.equal(instance.data.mapInstances.length, 1)
  assert.equal(instance.data.mapLoaded, false)
  instance.onUpdated({ currentTarget: { dataset: { generation: 0 } }, detail: {} })
  assert.equal(instance.data.mapLoaded, false)
  instance.onUpdated({ currentTarget: { dataset: { generation: 1 } }, detail: {} })
  assert.equal(instance.data.mapLoaded, true)
})

test('照片未通过只保留一个可重拍反馈，不触发通关', () => {
  const { instance } = component('pages/play/components/playkit-qa/index.js')
  instance.data.mode = 'pick'; instance.data.optionList = [{ id: 'a' }]
  let wins = 0, continued = 0
  instance.selectComponent = () => ({ win() { wins++ } })
  instance.triggerEvent = name => { if (name === 'continue') continued++ }
  instance.settle(true, { index: 0, pendingEvidence: true, feedback: '答案正确，请重新拍摄照片' })
  assert.equal(instance.data.ctaDisabled, false)
  assert.equal(instance.data.ctaLabel, '重拍')
  instance.onCta()
  assert.equal(continued, 1)
  assert.equal(wins, 0)
})

test('危险确认的中文文案拒绝半角标点', () => {
  const invalid = text => /[\u3400-\u9fff]/.test(text) && /[,?!:;]/.test(text)
  assert.equal(invalid('此操作不可撤销,结束后不能继续'), true)
  const walk = value => {
    if (typeof value === 'string') assert.equal(invalid(value), false, value)
    else if (value && typeof value === 'object') Object.values(value).forEach(walk)
  }
  walk(DANGER_ACTIONS)
})
