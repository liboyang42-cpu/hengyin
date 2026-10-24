'use strict'
// BL-225:活动详情「打开主办控制台」连点只跳一次;complete 回调或 onShow 后可再次打开;无入口时不跳。
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const URL = '/pages/activity/official-organizer/index?id=7'

function boot() {
  const navs = []
  global.getApp = () => ({
    globalData: {},
    getImgUrl: (u) => u,
    sendRequest: () => ({ abort() {} }),
  })
  global.getCurrentPages = () => [{}, {}]
  const base = { navigateTo: (o) => navs.push(o), navigateBack() {}, switchTab() {}, stopPullDownRefresh() {}, getStorageSync: () => '' }
  global.wx = new Proxy(base, { get(t, k) { return k in t ? t[k] : () => {} } })
  const file = path.join(__dirname, '..', '..', 'pages/activity/official-detail/index.js')
  let def
  global.Page = (c) => { def = c }
  delete require.cache[require.resolve(file)]
  require(file)
  const page = Object.assign({}, def)
  page.data = JSON.parse(JSON.stringify(def.data))
  page.data.id = 7
  page.data.organizerEntry = true
  page.setData = function (patch, cb) { Object.assign(this.data, patch); if (cb) cb() }
  return { page, navs }
}

test('BL-225:complete 未回调时连点两次只跳 1 次', () => {
  const { page, navs } = boot()
  page.openOrganizer()
  page.openOrganizer()
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, URL)
})

test('BL-225:complete 回调后可再次打开', () => {
  const { page, navs } = boot()
  page.openOrganizer()
  navs[0].complete()
  page.openOrganizer()
  assert.equal(navs.length, 2)
})

test('BL-225:onShow 后可再次打开', () => {
  const { page, navs } = boot()
  page.openOrganizer()
  page.onShow()
  page.openOrganizer()
  assert.equal(navs.length, 2)
})

test('BL-225:organizerEntry 为 false 时不跳也不上锁', () => {
  const { page, navs } = boot()
  page.data.organizerEntry = false
  page.openOrganizer()
  assert.equal(navs.length, 0)
  page.data.organizerEntry = true
  page.openOrganizer()
  assert.equal(navs.length, 1)
})
