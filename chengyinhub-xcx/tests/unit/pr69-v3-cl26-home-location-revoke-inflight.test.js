'use strict'
// PR #69 V3 · CL-26(F-L5C-14, #69 2126b4f0/8534daba):首页同意后自动选城；
// 定位请求在途时到达的撤回不能让定位重新生效。
// 用真实 pages/index + 真实 utils/compliance/consent-client 串起来，只替换网络与 wx 出口。
// 单模块的守卫已由 consent-client.test.js「首页同意先读取基线，旧同意响应不能越过本机撤回」与
// home-location-scope-epoch.test.js「隐藏首页立即收loading，迟到GPS不得继续逆地理请求」覆盖。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const { createConsentClient } = require('../../utils/compliance/consent-client.js')

let config, requests, locations, modals, client, app
global.getApp = () => app
global.Page = value => { config = value }
global.wx = {
  getStorageSync: () => '', hideTabBar() {}, getLocation: value => locations.push(value),
  showModal: value => modals.push(value), showLoading() {}, hideLoading() {}, showToast() {}, setNavigationBarColor() {},
}

beforeEach(() => {
  requests = []; locations = []; modals = []
  const send = request => { requests.push(request); return { abort() {} } }
  client = createConsentClient({ getUserID: () => '1001', sendRequest: send })
  app = { globalData: { authorization: 'normal-session' }, getUserID: () => '1001', getUserType: () => 0, getImgUrl: v => v,
    sendRequest: send, recordConsent: input => client.record(input) }
  wx.getLocation = value => locations.push(value)
  delete require.cache[require.resolve('../../utils/loading.js')]
  delete require.cache[require.resolve('../../pages/index/index.js')]
  require('../../pages/index/index.js')
})

function page() {
  const value = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) })
  value.setData = (patch, done) => { Object.assign(value.data, patch); if (done) done() }
  return value
}
const flush = async () => { for (let i = 0; i < 10; i++) await new Promise(resolve => setImmediate(resolve)) }
const unanswered = url => requests.filter(r => r.url === url && !r._answered)
const pendingWrite = eventType => unanswered('/api/compliance/consents').find(r => JSON.parse(r.data).eventType === eventType)
function answer(request, body) { request._answered = true; request.success(body) }
function accept(request) {
  const data = JSON.parse(request.data)
  answer(request, { code: 200, data: Object.assign({}, data, { docVersion: 'v2.1' }) })
  return data
}

test('同意写入在途时撤回：迟到的同意成功回执不能触发精确定位', async () => {
  const p = page()
  p.getHomeCityLocation()
  answer(unanswered('/api/compliance/consents/latest')[0], { code: 200, data: null })
  assert.equal(modals.length, 1)
  modals[0].success({ confirm: true })
  await flush()
  answer(unanswered('/api/compliance/consents/latest')[0], { code: 200, data: null })
  await flush()
  const agreeWrite = pendingWrite('AGREE')
  assert.equal(JSON.parse(agreeWrite.data).eventType, 'AGREE')

  // 用户在隐私设置里关闭首页定位(同一 client，与 pages/privacy、privacy-sheet 一致)
  const revoke = client.record({ docType: 'privacy_policy', scene: 'home_city_location', eventType: 'REVOKE' })
  accept(pendingWrite('REVOKE'))
  await revoke

  accept(agreeWrite) // 服务端对旧 AGREE 的成功回执迟到
  await flush()
  assert.equal(locations.length, 0, '撤回之后不得再调用 wx.getLocation')
  assert.equal(requests.some(r => r.url === '/api/map/reverse-geocode'), false)
  assert.equal(p.data.city, '')
})

test('GPS 在途时离开首页撤回：迟到坐标不逆地理，回到首页读到撤回也不再定位或弹窗', async () => {
  const p = page()
  p.data.isAppReady = true
  p.getUserData = () => {}; p.loadContinueExplore = () => {}
  p.getHomeCityLocation()
  answer(unanswered('/api/compliance/consents/latest')[0], { code: 200, data: {
    docType: 'privacy_policy', scene: 'home_city_location', docVersion: 'v2.1', eventType: 'AGREE', requestId: 'agree-1' } })
  assert.equal(locations.length, 1, '已同意时自动取位置')

  p.onHide() // 进入隐私设置
  const revoke = client.record({ docType: 'privacy_policy', scene: 'home_city_location', eventType: 'REVOKE' })
  const revoked = accept(pendingWrite('REVOKE'))
  await revoke

  locations[0].success({ latitude: 31.23, longitude: 121.47 })
  assert.equal(requests.some(r => r.url === '/api/map/reverse-geocode'), false)

  p.onShow()
  await new Promise(resolve => setTimeout(resolve, 150))
  const reread = unanswered('/api/compliance/consents/latest')
  assert.equal(reread.length, 1, '回到首页按当前账号重新读取同意')
  answer(reread[0], { code: 200, data: Object.assign({}, revoked, { docVersion: 'v2.1' }) })
  await flush()
  assert.equal(locations.length, 1, '读到撤回后不再发起定位')
  assert.equal(modals.length, 0, '撤回后也不自动弹同意')
  assert.equal(p.data.city, '')
  p.onUnload()
})
