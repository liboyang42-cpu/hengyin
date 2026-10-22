'use strict'
// PR #69 V3 · CL-24(F-L5C-11/SC-PRIV-04, #69 64956c5f/115162d7):首页隐私弹窗只显示一句用途说明。
// 走真实宿主链:app.js onNeedPrivacyAuthorization → 当前页 pages/index.showPrivacyGate → cy-privacy-gate。
// 弹窗在真机上的样式/微信原生授权仍需真机确认(NEEDS_RUNTIME),这里只做可静态判定的部分。
// 移植到 master(lane H3):#293(d787467d/e724c869)把首屏用途说明再压成一句 pg__lead,
// 隐私页设置态改为分组列表、只有待决策态保留同一句 privacy-lead;断言按 master 现文案对齐,意图不变。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const SENTENCE = '我们只在你使用相关功能时处理必要信息。'

function loadIndexPage() {
  let config
  global.getApp = () => ({ globalData: {}, getUserID: () => '', getUserType: () => 0, getImgUrl: v => v, sendRequest() {} })
  global.Page = value => { config = value }
  global.wx = { getStorageSync: () => '', hideTabBar() {}, showToast() {}, showLoading() {}, hideLoading() {}, setNavigationBarColor() {} }
  const file = path.join(ROOT, 'pages/index/index.js')
  delete require.cache[file]
  require(file)
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)), route: 'pages/index/index' })
  page.setData = function (patch, done) { Object.assign(this.data, patch); if (done) done() }
  return page
}

function mountApp(currentPage) {
  let app, needPrivacy
  const navigations = []
  const wx = { getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
    onNeedPrivacyAuthorization: handler => { needPrivacy = handler }, navigateTo: o => navigations.push(o) }
  vm.runInNewContext(read('app.js'), { App: a => { app = a }, wx, console, setTimeout, clearTimeout,
    getCurrentPages: () => [currentPage],
    require: f => f === './utils/toast.js' ? Object.assign(() => {}, { hide() {} }) : require(path.join(ROOT, f)) })
  app.initPrivacyAuthorization()
  return { app, needPrivacy: () => needPrivacy, navigations }
}

function gateBodies() {
  return Array.from(read('components/cy/privacy-gate/index.wxml').matchAll(/<text class="pg__(?:lead|body)"[^>]*>([^<]*)<\/text>/g)).map(m => m[1])
}

test('首页需要隐私授权时由页内 cy-privacy-gate 弹出，不跳路由页', () => {
  const page = loadIndexPage()
  const host = mountApp(page)
  assert.equal(typeof host.needPrivacy(), 'function', 'app 必须注册 onNeedPrivacyAuthorization')
  assert.notEqual(page.data.privacyGateShow, true)
  host.needPrivacy()(() => {})
  assert.equal(page.data.privacyGateShow, true)
  assert.equal(host.navigations.length, 0)
  const binding = read('pages/index/index.wxml').match(/<cy-privacy-gate show="\{\{(\w+)\}\}"/)
  assert.ok(binding)
  assert.equal(binding[1], 'privacyGateShow', '首页挂载的闸必须绑定 showPrivacyGate 写入的字段')
  assert.equal(JSON.parse(read('pages/index/index.json')).usingComponents['cy-privacy-gate'], '/components/cy/privacy-gate/index')
  assert.match(read('components/cy/privacy-gate/index.wxml'), /<cy-scene-sheet\s+wx:if="\{\{show\}\}"/)
})

test('首页隐私弹窗正文只有一句用途说明，与隐私页待决策态一致', () => {
  const bodies = gateBodies()
  assert.deepEqual(bodies, [SENTENCE], '弹窗只能渲染一段用途正文')
  assert.equal((bodies[0].match(/[。！？]/g) || []).length, 1)
  assert.doesNotMatch(bodies[0], /DeepSeek|CosyVoice|锁屏|附近用户/, '长篇告知留在隐私指引里，不回到首屏弹窗')
  const privacy = Array.from(read('pages/privacy/index.wxml').matchAll(/<text class="privacy-(?:lead|body)"[^>]*>([^<]*)<\/text>/g)).map(m => m[1])
  assert.deepEqual(privacy, [SENTENCE])
  assert.match(read('components/cy/privacy-gate/index.wxml'), /catchtap="openPrivacyContract"[^>]*aria-label="查看用户隐私保护指引"[^>]*>《隐私保护指引》/)
})
