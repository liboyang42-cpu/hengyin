'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('../helpers/ui-sandbox-vm.js')
const { createRequire } = require('node:module')

// BL-248:商家装修页无店铺空态「去商家入驻」(goApply)、活动详情门卡「去加入俱乐部」(goGateClub)连点只跳一次;
// complete / onShow(组件 pageLifetimes.show)后可再跳;门卡没有 gateClubId 时只关掉、不跳也不上锁

const nodeVm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const PAGE = 'pages/merchant/decor/index.js'

function loadPage() {
  const absolutePath = path.join(ROOT, PAGE)
  const navs = []
  let definition
  const app = {
    globalData: { navBarHeight: 44 },
    sendRequest() {},
    getRequestErrorMessage(error, fallback) { return (error && error.msg) || fallback },
    chooseImage() {},
  }
  const wx = {
    getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    navigateTo(options) { navs.push(options) },
    showToast() {},
    setNavigationBarColor() {},
    setBackgroundColor() {},
  }
  const localRequire = createRequire(absolutePath)
  vm.runInNewContext(fs.readFileSync(absolutePath, 'utf8'), {
    Array, Date, JSON, Math, Number, Object, RegExp, String,
    clearTimeout, setTimeout,
    getApp: () => app,
    getCurrentPages: () => [{}, {}],
    Page(config) { definition = config },
    require(request) {
      if (request.endsWith('merchant-theme.js')) return { merchantPageShow() {}, merchantPageRestore() {} }
      return localRequire(request)
    },
    wx,
  }, { filename: absolutePath })
  const page = Object.assign({}, definition)
  page.data = JSON.parse(JSON.stringify(definition.data))
  page.setData = function (patch) { Object.assign(this.data, patch) }
  page.data.m = { memberId: 66 }
  return { page, navs }
}


const memberApp = {
  globalData: {}, sendRequest() {}, getUserID: () => 7, getUserType: () => 1, getUserRole: () => '',
  getImgUrl: (u) => u || '', tips() {},
}
function run(rel, navs, exportName) {
  let def
  const abs = path.join(ROOT, rel)
  const base = { navigateTo: (o) => navs.push(o), getStorageSync: () => '', getSystemInfoSync: () => ({}) }
  const wx = new Proxy(base, { get: (t, k) => (k in t ? t[k] : () => {}) })
  const sandbox = {
    Date, Math, Promise, String, Number, Array, Object, JSON, console, setTimeout, clearTimeout,
    getApp: () => memberApp, wx,
    require(id) {
      if (!id.startsWith('.')) return require(id)
      return require(path.resolve(path.dirname(abs), id))
    },
    Component(o) { def = o }, Page(o) { def = o }, Behavior: (c) => c,
  }
  // 被 require 的真工具模块(merchant-theme 等)在调用时读全局 wx,这里一并指向桩
  global.wx = wx; global.getApp = () => memberApp
  nodeVm.runInNewContext(fs.readFileSync(abs, 'utf8'), sandbox, { filename: abs })
  return def
}


function mountDetail() {
  const navs = []
  const def = run('subpackageMember/components/cy/scene-play-activity-detail/index.js', navs)
  const inst = { data: JSON.parse(JSON.stringify(def.data || {})), setData(p) { Object.assign(this.data, p) }, triggerEvent() {} }
  Object.entries(def.methods).forEach(([k, fn]) => { inst[k] = fn.bind(inst) })
  const show = () => def.pageLifetimes.show.call(inst)
  return { inst, navs, show }
}

test('BL-248:商家装修页 goApply complete 未回调时连调两次只跳 1 次,complete/onShow 后可再跳', () => {
  const { page, navs } = loadPage()
  page.goApply()
  page.goApply()
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, '/pages/merchant/apply/index')
  navs[0].complete()
  page.goApply()
  assert.equal(navs.length, 2)
  page.goAiNpc()
  assert.equal(navs.length, 2, '与其他入口共用 this._navigating')
  page.load = () => {}; page.loadPerks = () => {}
  page.onShow()
  page.goApply()
  assert.equal(navs.length, 3)
})

test('BL-248:活动详情门卡 goGateClub 有 gateClubId 时连调两次只跳 1 次,complete/pageLifetimes.show 后可再跳', () => {
  const { inst, navs, show } = mountDetail()
  let closes = 0
  inst.close = () => { closes += 1 }
  inst.data.gateClubId = 77
  inst.goGateClub()
  inst.goGateClub()
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, '/pages/club/detail/index?id=77')
  assert.equal(closes, 0, '有俱乐部出口时锁住也不改成关掉')
  navs[0].complete()
  inst.goGateClub()
  assert.equal(navs.length, 2)
  inst.goGateClub()
  assert.equal(navs.length, 2)
  show()
  inst.goGateClub()
  assert.equal(navs.length, 3)
})

test('BL-248:门卡没有 gateClubId 时照旧关掉,不跳也不上锁', () => {
  const { inst, navs } = mountDetail()
  let closes = 0
  inst.close = () => { closes += 1 }
  inst.data.gateClubId = 0
  inst.goGateClub()
  inst.goGateClub()
  assert.equal(navs.length, 0)
  assert.equal(closes, 2)
  assert.ok(!inst._navigating)
  inst.data.gateClubId = 5
  inst.goGateClub()
  assert.equal(navs.length, 1)
})
