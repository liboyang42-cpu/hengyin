'use strict'
// P-38:隐私闸开着(或使用同意未给)时,首页不叠「首页城市定位」弹窗;同意后由 onPrivacyGateSettled 重新发起。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
let config, requests, modals, app, granted
global.getApp = () => app
global.Page = value => { config = value }
beforeEach(() => {
  requests = []; modals = []; granted = true
  global.wx = { getStorageSync: () => '', setStorageSync() {}, hideTabBar() {}, getLocation() {},
    showModal: value => modals.push(value), showLoading() {}, hideLoading() {}, showToast() {}, setNavigationBarColor() {} }
  app = { globalData: { authorization: 'normal-session' }, getUserID: () => '1001', getUserType: () => 0, getImgUrl: v => v,
    getUsageConsent: () => ({ granted: () => granted }),
    sendRequest: r => { requests.push(r); return { abort() {} } }, recordConsent: () => Promise.resolve() }
  delete require.cache[require.resolve('../../utils/loading.js')]
  delete require.cache[require.resolve('../../pages/index/index.js')]
  require('../../pages/index/index.js')
})
function page() {
  const p = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) })
  p.setData = (patch, done) => { Object.assign(p.data, patch); if (done) done() }
  return p
}
function askLatest(p) {
  p.getHomeCityLocation()
  requests.find(x => x.url === '/api/compliance/consents/latest').success({ code: 200, data: null })
}
test('隐私闸开着时不弹城市定位', () => {
  const p = page(); p.data.privacyGateShow = true
  askLatest(p)
  assert.equal(modals.length, 0)
})
test('使用同意未给时不弹城市定位', () => {
  const p = page(); granted = false
  askLatest(p)
  assert.equal(modals.length, 0)
})
test('隐私闸关闭且已同意时照常询问', () => {
  const p = page()
  askLatest(p)
  assert.equal(modals.length, 1)
})
