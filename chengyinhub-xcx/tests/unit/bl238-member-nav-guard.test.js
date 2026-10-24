'use strict'
// BL-238:会员分包活动详情组件(goTopic/goPlay/goUserInfo/goTemplateDetail/openSignup)、
// 券列表「新建」(GoAdd)、我的模板(goInfo)跳转入口加跳转中保护——连点只跳一次,
// complete 后可再跳;onShow / pageLifetimes.show 兜底清锁。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const app = {
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
    getApp: () => app, wx,
    require(id) {
      if (!id.startsWith('.')) return require(id)
      return require(path.resolve(path.dirname(abs), id))
    },
    Component(o) { def = o }, Page(o) { def = o }, Behavior: (c) => c,
  }
  // 被 require 的真工具模块(merchant-theme 等)在调用时读全局 wx,这里一并指向桩
  global.wx = wx; global.getApp = () => app
  vm.runInNewContext(fs.readFileSync(abs, 'utf8'), sandbox, { filename: abs })
  return def
}

function onceThenAgain(navs, tap, url, reset) {
  tap(); tap()
  assert.equal(navs.length, 1, '连点只跳一次')
  assert.equal(navs[0].url, url)
  navs[0].complete()
  tap()
  assert.equal(navs.length, 2, 'complete 后可再跳')
  tap()
  assert.equal(navs.length, 2, '未回调前仍锁住')
  reset()
  tap()
  assert.equal(navs.length, 3, '回前台保底清锁')
}

function mountDetail() {
  const navs = []
  const def = run('subpackageMember/components/cy/scene-play-activity-detail/index.js', navs)
  const inst = { data: JSON.parse(JSON.stringify(def.data || {})), setData(p) { Object.assign(this.data, p) }, triggerEvent() {} }
  Object.entries(def.methods).forEach(([k, fn]) => { inst[k] = fn.bind(inst) })
  const show = () => def.pageLifetimes.show.call(inst)
  return { inst, navs, show }
}

test('活动详情组件 主题路线连点只跳一次', () => {
  const { inst, navs, show } = mountDetail()
  inst.data.info = { topicId: 3 }
  onceThenAgain(navs, () => inst.goTopic(), '/pages/topic/index/index?id=3', show)
})

test('活动详情组件 去玩连点只跳一次', () => {
  const { inst, navs, show } = mountDetail()
  onceThenAgain(navs, () => inst.goPlay(), '/subpackageMember/signup/index', show)
})

test('活动详情组件 用户主页、模板详情连点只跳一次', () => {
  const a = mountDetail()
  const ev = { currentTarget: { dataset: { id: 9 } } }
  onceThenAgain(a.navs, () => a.inst.goUserInfo(ev), '/pages/userinfo/userinfo?userId=9', a.show)
  const b = mountDetail()
  onceThenAgain(b.navs, () => b.inst.goTemplateDetail(ev), '/pages/templatedetail/templatedetail?id=9&scope=my', b.show)
})

test('活动详情组件 跳转中「立即报名」不再进结算页', () => {
  const { inst, navs } = mountDetail()
  inst.data.canConfirmSignup = true
  inst._navigating = true
  inst.openSignup()
  assert.equal(navs.length, 0)
})

function mountPage(rel) {
  const navs = []
  const def = run(rel, navs)
  const page = Object.assign({}, def, { data: JSON.parse(JSON.stringify(def.data || {})) })
  page.setData = function (p) { Object.assign(this.data, p) }
  return { page, navs }
}

test('券列表 「新建」连点只跳一次;onShow 兜底清锁', () => {
  const { page, navs } = mountPage('subpackageMember/coupon/coupon.js')
  page.getList = () => {}; page.loadSummary = () => {}
  onceThenAgain(navs, () => page.GoAdd(), '/subpackageMember/couponInfo/couponInfo', () => page.onShow())
})

test('我的模板 封面连点只跳一次;onShow 兜底清锁', () => {
  const { page, navs } = mountPage('subpackageMember/mytemplate/mytemplate.js')
  page.data.list = [{ id: 4 }]
  const keep = Object.assign({}, page)
  Object.keys(page).forEach((k) => { if (typeof page[k] === 'function' && !['goInfo', 'onShow', 'setData'].includes(k)) page[k] = () => {} })
  const ev = { currentTarget: { dataset: { index: 0 } } }
  onceThenAgain(navs, () => page.goInfo(ev), '/pages/templatedetail/templatedetail?id=4&scope=my', () => keep.onShow.call(page))
})
