'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const PM = require('../../utils/roam-place-map.js')
const media = require('../../utils/roam-place-media.js')
const metrics = require('../../utils/roam-history-metrics.js')
const { currentLocationConsent } = require('../../utils/roam-recovery.js')
const { routeGeo } = require('../../utils/roam-route-geo.js')
const snapshots = require('../../utils/map-snapshot.js')
const flush = async () => { for (let i = 0; i < 8; i++) await new Promise(resolve => setImmediate(resolve)) }

function mount(file, app = {}) {
  let definition
  global.getApp = () => app
  global.getCurrentPages = () => []
  global.wx = { getStorageSync() {}, setStorageSync() {}, showToast() {}, showModal() {},
    getWindowInfo: () => ({ windowWidth: 390, windowHeight: 844 }), getSystemInfoSync: () => ({}) }
  global.Page = value => { definition = value }
  global.Component = value => { definition = value }
  global.Behavior = value => value
  const resolved = require.resolve(file)
  delete require.cache[resolved]
  require(file)
  const vm = Object.assign({}, definition, definition.methods || {}, { data: JSON.parse(JSON.stringify(definition.data || {})) })
  vm.setData = (patch, done) => {
    Object.entries(patch).forEach(([key, value]) => {
      const parts = key.split('.'); let target = vm.data
      parts.slice(0, -1).forEach(part => { target = target[part] || (target[part] = {}) })
      target[parts.at(-1)] = value
    })
    if (done) done()
  }
  return vm
}

test('A1：roam JSON 投影与旧数组均可读，引用来源保留', () => {
  const row = { quotesJson: JSON.stringify([{ text: '真实引文', source: '报道', url: 'https://example.org/report' }]), galleryJson: '["a.jpg","b.jpg"]' }
  assert.equal(PM.reasonFor(row), '真实引文')
  assert.deepEqual(PM.galleryKeys(row), ['a.jpg', 'b.jpg'])
  assert.deepEqual(PM.quoteSources(row), ['https://example.org/report'])
  assert.equal(PM.reasonFor({ quotes: [{ text: '旧引用' }] }), '旧引用')
  assert.deepEqual(PM.galleryKeys({ galleryJson: '{bad' }), [])
})

test('A2：未确认许可拒绝封面和相册；确认的署名只保留作者及短许可名', () => {
  const row = { coverImg: 'poi/a.jpg', galleryJson: '["poi/b.jpg"]', photoCredit: '作者；仅测试参考，公开再发布许可未确认 · https://example.org' }
  assert.equal(PM.coverKey(row), '')
  assert.deepEqual(PM.galleryKeys(row), [])
  assert.equal(PM.credit(row), '')
  assert.equal(PM.credit({ photoCredit: '作者 · CC BY-SA 4.0 · https://example.org/licence\n作者 · CC BY-SA 4.0 · https://example.org/photo' }), '作者 · CC BY-SA 4.0')
})

test('A2：先确认同源清单再展示；错地点、缺清单或未确认许可均无照片', async () => {
  const row = { id: 41, coverImg: 'poi/a.jpg', tags: '["roam:seed:v1:test"]', galleryJson: '["poi/a.jpg"]' }
  const requests = []
  const pending = media.resolvePlaceMedia({ sendRequest: request => requests.push(request) }, [row])
  assert.equal(requests.length, 1)
  assert.equal(requests[0].auth, false)
  requests[0].success({ code: 200, data: { poiId: 41, coverKey: 'poi/a.jpg', credit: '仅测试参考，公开再发布许可未确认', images: [] } })
  const result = (await pending)[0]
  assert.equal(PM.coverKey(result), '')
  assert.deepEqual(PM.galleryKeys(result), [])
  assert.equal(PM.coverKey(media.applyManifest(row, { poiId: 42, coverKey: 'poi/a.jpg', credit: '作者 · CC0' })), '')
  assert.equal(PM.coverKey(media.applyManifest(row, null)), '')
  assert.equal(PM.coverKey(media.applyManifest(row, { poiId: 41, coverKey: 'poi/a.jpg', credit: '作者 · CC BY 4.0' })), 'poi/a.jpg')
})

test('A3/b2：结算关闭到达层，统计实际到达地点并使用城市名', () => {
  const page = mount('../../pages/roam/index.js', { globalData: {}, getUserID: () => '11' })
  page._stopClock = page._stopReal = page._triggerCelebration = page._postRoamFinish = () => {}
  page._saveSession = finish => { page.saved = finish }
  page._stats = { explorePct: 6, time: '0:08' }
  page._pois = [{ id: 41, name: '甲馆', state: 'done' }, { id: 42, name: '乙馆', state: 'done' }, { id: 43, name: '未到', state: 'fog' }]
  page._foundRoamPois = { 41: 1, 42: 1 }
  page.data.entryCityStrip = { name: '上海' }
  page.data.screen = 'map'; page.data.paceCard = { show: true }; page.data.placeCard = { name: '乙馆' }
  page._arrive()
  assert.equal(page.data.screen, 'arrive')
  assert.equal(page.data.paceCard.show, false)
  assert.equal(page.data.placeCard, null)
  assert.deepEqual(page.data.finish.places.map(p => p.name), ['甲馆', '乙馆'])
  assert.equal(page.data.finish.zone, '上海')
  assert.equal(page.data.finish.medal, undefined, '到达不冒充领取勋章')
})

test('b1：五米距离已在范围内，挑战使用同一地点的真实ID', () => {
  const app = { globalData: {}, getImgUrl: key => key }
  const page = mount('../../pages/roam/index.js', app)
  page._player = { lat: 31.2, lng: 121.4 }
  page.data.paceCard = page._paceCardFor({ id: 80041, _roamId: 41, isCityNode: true, name: '甲馆', lat: 31.20004, lng: 121.4 })
  assert.equal(page.data.paceCard.inReach, true)
  page.onPlaceNote({ detail: { tags: '', reason: '目录引文', sourceList: ['https://example.org/source'] } })
  page.togglePaceSources()
  const refreshed = page._paceCardFor({ id: 80041, name: '甲馆', lat: 31.20004, lng: 121.4 })
  assert.equal(refreshed.reason, '目录引文')
  assert.deepEqual(refreshed.sourceList, ['https://example.org/source'])
  assert.equal(refreshed.sourcesOpen, true)
  let destination
  wx.navigateTo = options => { destination = options.url }
  page.startPaceChallenge()
  assert.equal(destination, '/pages/play/poi-challenge/index?poiId=41')
  assert.equal(page.data.paceCard.show, false)
})

test('b3：拉取以玩家为圆心，拖动视口不能改变发现半径的圆心', async () => {
  const requests = []
  const app = { globalData: {}, getUserID: () => '11', sendRequest: request => { requests.push(request); request.success({ code: 200, data: [] }) } }
  const page = mount('../../pages/roam/index.js', app)
  page._player = { lat: 31.2, lng: 121.4 }
  page._roamReadScope = () => () => true
  page._syncGoal = () => {}; page._pois = []
  await page._fetchRoamPois({ lat: 39.9, lng: 116.4 })
  const query = requests.find(r => r.url === '/api/roam/pois').data
  assert.equal(Number(query.lat), 31.2)
  assert.equal(Number(query.lng), 121.4)
  assert.ok(Number(query.radius) >= 1000)
  assert.equal(PM.isMysteryVisible({ lat: 31.2026, lng: 121.4 }, page._player), true)
  assert.equal(PM.isMysteryVisible({ lat: 31.22, lng: 121.4 }, page._player), false)
})

test('b4：仅当前版本的明确同意可复用，撤回、旧版和未知都不放行', () => {
  const row = { docType: 'privacy_policy', scene: 'roam_location', eventType: 'AGREE', docVersion: 'v2.1', requestId: 'receipt-1' }
  assert.equal(currentLocationConsent({ code: 200, data: row }, 'v2.1'), true)
  for (const patch of [{ eventType: 'REVOKE' }, { docVersion: 'old' }, { requestId: '' }, { scene: 'home_city_location' }])
    assert.equal(currentLocationConsent({ code: 200, data: { ...row, ...patch } }, 'v2.1'), false)
  assert.equal(currentLocationConsent({ code: 500, data: row }, 'v2.1'), false)
})

test('b4：点击继续时已有当前同意，定位一次且不再创建同意事件', async () => {
  let recorded = 0, located = 0, modals = 0
  const app = { globalData: {}, getUserID: () => '11', recordConsent: () => { recorded++; return Promise.resolve() },
    sendRequest: request => request.success({ code: 200, data: { docType: 'privacy_policy', scene: 'roam_location', eventType: 'AGREE', docVersion: 'v2.1', requestId: 'receipt-1' } }) }
  const page = mount('../../pages/roam/index.js', app)
  page._openRoamMap = () => {}; page._observeLocationRevocation = () => {}; page.markRoamIntroSeen = () => {}
  wx.getLocation = options => { located++; options.success({ latitude: 31.2, longitude: 121.4 }) }
  wx.showModal = () => { modals++ }
  page.goStart()
  await flush()
  assert.equal(located, 1)
  assert.equal(recorded, 0)
  assert.equal(modals, 0)
})

test('b5：照片拒绝不把已验证答案标错，也不能进入勋章完成态', () => {
  const page = mount('../../pages/play/poi-challenge/index.js', {})
  let verdict
  page.selectComponent = () => ({ settle: (...args) => { verdict = args } })
  page._submit = (_answer, done) => done(false, '照片未上传成功或不属于当前账号，请重新拍摄上传')
  page.data.step = 'quiz'
  page.onPick({ detail: { optionId: 'C', index: 2 } })
  assert.equal(verdict[0], true)
  assert.equal(verdict[1].pendingEvidence, true)
  assert.equal(page.data.step, 'quiz')
  assert.match(page.data.photoError, /重新拍摄/)
  page.retakePhoto()
  assert.equal(page.data.step, 'photo')
  assert.equal(page.data.photoError, '')
  const kit = mount('../../pages/play/components/playkit-qa/index.js', {})
  kit.data.mode = 'pick'; kit.data.optionList = ['A', 'B', 'C'].map(id => ({ id }))
  let wins = 0
  kit.selectComponent = () => ({ win() { wins++ } })
  kit.settle(true, verdict[1])
  assert.equal(kit.data.optionList[2].state, 'is-right')
  assert.equal(wins, 0)
  assert.equal(kit.data.ctaDisabled, true)
})

test('b6：同一标签观察值只回传一次，避免父层回写造成递归更新', () => {
  const component = mount('../../subpackageRoam/components/roam-place-note/index.js')
  let events = 0
  component.triggerEvent = () => { events++ }
  const observe = component.properties.tags.observer
  observe.call(component, '["roam:seed:v1:sh-test"]')
  observe.call(component, '["roam:seed:v1:sh-test"]')
  observe.call(component, '["roam:seed:v1:bj-test"]')
  assert.equal(events, 2)
})

test('c4：统一公里一位小数与小时分钟，真实零不与未知混淆', () => {
  assert.equal(metrics.formatDistance(1.567), '1.6')
  assert.equal(metrics.formatDistance(0), '0.0')
  assert.equal(metrics.formatDistance(null), '—')
  assert.equal(metrics.formatDuration(503), '0:08')
  assert.equal(metrics.formatDuration(3750), '1:02')
  assert.equal(metrics.formatDuration(null, '08:23'), '0:08')
  assert.equal(metrics.formatStartTime('2026-11-14 09:23:00'), '09:23')
  for (const absent of [null, undefined, '', 0, 'bad']) assert.equal(metrics.formatStartTime(absent), '')
})

test('A4：截图整图缩入卡框，绘制回退足迹也在带留白的框内', () => {
  let imageArgs
  snapshots.drawContain({ drawImage: (...args) => { imageArgs = args } }, { width: 300, height: 600 }, 10, 20, 400, 300)
  assert.deepEqual(imageArgs.slice(1), [135, 20, 150, 300])
  const points = []
  const ctx = { beginPath() {}, moveTo: (x, y) => points.push({ x, y }), lineTo: (x, y) => points.push({ x, y }), stroke() {} }
  const geo = routeGeo([{ lat: 31, lng: 121 }, { lat: 31.1, lng: 121.2 }], [])
  assert.equal(snapshots.drawRoute(ctx, geo, 10, 20, 400, 300), true)
  assert.ok(points.length >= 2)
  points.forEach(p => { assert.ok(p.x > 10 && p.x < 410); assert.ok(p.y > 20 && p.y < 320) })
  assert.equal(snapshots.drawRoute(ctx, null, 10, 20, 400, 300), false)
})
