'use strict'
// 第十轮 f3 j6 F12:游客打开「附近商家」,后端 401 原文「登录状态已失效，请重新登录」对从没登录过的人是错话。
// 游客落「登录后查看 · 登录」;已登录的人 401 与定位被拒仍走原来的权限态。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')

function loadNearby(userId) {
  let definition
  const requests = []
  const locationCalls = []
  const app = {
    _userId: userId,
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID() { return this._userId },
    getUserRole() { return '' },
    getUserType() { return 1 },
    sendRequest(options) { requests.push(options); return { abort() {} } },
    getRequestErrorMessage(value, fallback) { return (value && value.msg) || fallback },
    firstLogin() { this._userId = '9009'; return Promise.resolve({ ok: true }) },
  }
  const file = path.join(ROOT, 'pages/coop/nearby/index.js')
  const sandbox = {
    Page(value) { definition = value }, getApp() { return app }, console, Promise, Date, Number, String, Object, Array, Math, JSON,
    setTimeout() { return 1 }, clearTimeout() {},
    wx: { getLocation(o) { locationCalls.push(o) }, stopPullDownRefresh() {}, showToast() {}, openSetting() {}, navigateBack() {} },
    require(id) {
      if (id.includes('merchant-theme')) return { merchantPageShow() {}, merchantPageRestore() {} }
      return require(path.resolve(path.dirname(file), id))
    },
  }
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), sandbox, { filename: file })
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) })
  page.setData = function (patch) { Object.assign(this.data, patch) }
  return { page, app, requests, locationCalls }
}

test('游客 401 落「登录后查看」,不显示「登录状态已失效」;点登录后重新定位', async () => {
  const { page, locationCalls } = loadNearby('')
  page.onLoad({})
  page._setFailure({ code: 401, msg: '登录状态已失效，请重新登录' }, false)
  assert.equal(page.data.pageState, 'guest')
  assert.equal(page.data.errorText, '')
  const before = locationCalls.length
  page.loginToView()
  await new Promise(r => setImmediate(r))
  assert.equal(locationCalls.length, before + 1)
})

test('游客定位被拒仍是去设置;已登录的 401 仍是权限态', () => {
  const guest = loadNearby('')
  guest.page.onLoad({})
  guest.locationCalls[0].fail({ errMsg: 'getLocation:fail auth deny' })
  assert.equal(guest.page.data.pageState, 'permission')

  const member = loadNearby('9009')
  member.page.onLoad({})
  member.page._setFailure({ code: 401, msg: '登录状态已失效，请重新登录' }, false)
  assert.equal(member.page.data.pageState, 'permission')
})

test('wxml 有游客态与登录入口', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/coop/nearby/index.wxml'), 'utf8')
  assert.match(wxml, /wx:elif="\{\{pageState === 'guest'\}\}"[^>]*title="登录后查看" cta="登录" bind:cta="loginToView"/)
})
