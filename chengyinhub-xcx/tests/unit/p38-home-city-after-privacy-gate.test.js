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
  assert.equal(modals.length, 0, 'x-fix6 L2-1:不再是居中弹窗')
  assert.equal(p.data.locationPrompt.confirmText, '同意定位')
  assert.equal(p.data.locationPrompt.cancelText, '暂不定位')
})

const { VERSIONS } = require('../../utils/compliance/usage-consent.js')
// P-38 第三轮:隐私闸一并征得首页城市定位同意 —— 同意后直接记录定位同意并调 wx.getLocation,不再弹第二个弹窗
test('隐私闸同意(含定位)后不弹「首页城市定位」,直接记录同意并定位', async () => {
  const consents = []; let located = 0
  app.recordConsent = (body) => { consents.push(body); return Promise.resolve({ requestId: 'r1', response: { code: 200, data: { requestId: 'r1', docType: 'privacy_policy', docVersion: VERSIONS.privacy_policy, scene: 'home_city_location', eventType: 'AGREE' } } }) }
  global.wx.getLocation = () => { located++ }
  const p = page()
  p.loadPageData = () => {}
  p.onPrivacyGateSettled({ detail: { event: 'agree', location: true } })
  requests.find(x => x.url === '/api/compliance/consents/latest').success({ code: 200, data: null })
  await new Promise(r => setImmediate(r))
  assert.equal(modals.length, 0)
  assert.deepEqual(consents, [{ docType: 'privacy_policy', scene: 'home_city_location', eventType: 'AGREE' }])
  assert.equal(located, 1)
})
test('隐私闸未声明定位(其他页)时同意后仍单独询问', () => {
  const p = page()
  p.loadPageData = () => {}
  p.onPrivacyGateSettled({ detail: { event: 'agree' } })
  requests.find(x => x.url === '/api/compliance/consents/latest').success({ code: 200, data: null })
  assert.equal(modals.length, 0)
  assert.ok(p.data.locationPrompt)
})
test('之前撤回过定位同意时,隐私闸同意也不定位', () => {
  let located = 0
  global.wx.getLocation = () => { located++ }
  const p = page()
  p.loadPageData = () => {}
  p.onPrivacyGateSettled({ detail: { event: 'agree', location: true } })
  requests.find(x => x.url === '/api/compliance/consents/latest').success({ code: 200, data: { docType: 'privacy_policy', scene: 'home_city_location', eventType: 'REVOKE' } })
  assert.equal(modals.length, 0)
  assert.equal(located, 0)
})
test('首页的隐私闸声明 location,弹窗文字写明定位用途,同意事件带回 location', () => {
  const fs = require('fs'); const path = require('path')
  const home = fs.readFileSync(path.join(__dirname, '../../pages/index/index.wxml'), 'utf8')
  assert.match(home, /<cy-privacy-gate show="\{\{privacyGateShow\}\}" location bind:settled="onPrivacyGateSettled" \/>/)
  const gate = fs.readFileSync(path.join(__dirname, '../../components/cy/privacy-gate/index.wxml'), 'utf8')
  assert.match(gate, /<text class="pg__lead">我们只在你使用相关功能时处理必要信息。<text wx:if="\{\{location\}\}">同意后将使用你的精确位置确定首页城市，不用于附近排序。<\/text><\/text>/)
  const js = fs.readFileSync(path.join(__dirname, '../../components/cy/privacy-gate/index.js'), 'utf8')
  assert.match(js, /if \(this\.data\.location\) detail\.location = true;/)
})

test('x-fix6 L2-1:卡片「暂不定位」收起并记本机拒绝,不记同意、不定位', () => {
  let located = 0; global.wx.getLocation = () => { located++ }
  const consents = []; app.recordConsent = (b) => { consents.push(b); return Promise.resolve() }
  const p = page(); askLatest(p)
  p.onLocationPromptDecline()
  assert.equal(p.data.locationPrompt, null)
  assert.equal(consents.length, 0); assert.equal(located, 0)
  assert.ok(p.data.city)
})
test('x-fix6 L2-1:卡片「同意定位」收起并记录同意', async () => {
  const consents = []; app.recordConsent = (b) => { consents.push(b); return Promise.resolve() }
  const p = page(); askLatest(p)
  p.onLocationPromptAgree()
  await new Promise((r) => setImmediate(r))
  assert.equal(p.data.locationPrompt, null)
  assert.equal(consents.length, 1)
  assert.equal(consents[0].scene, 'home_city_location')
})
test('x-fix6 L2-1:feed 顶部卡片两颗按钮在 wxml 里', () => {
  const wxml = require('fs').readFileSync(require('path').join(__dirname, '../../pages/index/index.wxml'), 'utf8')
  assert.match(wxml, /class="v3-locprompt" wx:if="\{\{locationPrompt\}\}"/)
  assert.match(wxml, /bindtap="onLocationPromptDecline"/)
  assert.match(wxml, /bindtap="onLocationPromptAgree"/)
  assert.ok(wxml.indexOf('v3-locprompt') < wxml.indexOf('id="sec-reco"'))
})
