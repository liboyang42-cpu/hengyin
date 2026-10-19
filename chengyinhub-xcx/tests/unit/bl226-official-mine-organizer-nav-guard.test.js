'use strict'
// BL-226:我发布的活动「主办控制台」连点只跳一次;complete 回调或 onShow 后可再次打开;缺 id 不跳。
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const URL = '/pages/activity/official-organizer/index?id=7'

function boot() {
  const navs = []
  global.getApp = () => ({
    globalData: {},
    getImgUrl: (u) => u,
    getUserRole: () => '',
    sendRequest: () => ({ abort() {} }),
  })
  global.getCurrentPages = () => [{}, {}]
  const base = { navigateTo: (o) => navs.push(o), navigateBack() {}, switchTab() {}, stopPullDownRefresh() {}, getStorageSync: () => '' }
  global.wx = new Proxy(base, { get(t, k) { return k in t ? t[k] : () => {} } })
  const file = path.join(__dirname, '..', '..', 'pages/activity/official-mine/index.js')
  let def
  global.Page = (c) => { def = c }
  delete require.cache[require.resolve(file)]
  require(file)
  const page = Object.assign({}, def)
  page.data = JSON.parse(JSON.stringify(def.data))
  page.setData = function (patch, cb) { Object.assign(this.data, patch); if (cb) cb() }
  page.syncViewerTheme = () => {} // 主题同步与本卡无关
  page.fetch = () => {}
  return { page, navs }
}

const tap = (id) => ({ currentTarget: { dataset: { id } } })

test('BL-226:complete 未回调时连点两次只跳 1 次', () => {
  const { page, navs } = boot()
  page.openOrganizer(tap(7))
  page.openOrganizer(tap(7))
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, URL)
})

test('BL-226:complete 回调后可再次打开', () => {
  const { page, navs } = boot()
  page.openOrganizer(tap(7))
  navs[0].complete()
  page.openOrganizer(tap(7))
  assert.equal(navs.length, 2)
})

test('BL-226:onShow 后可再次打开', () => {
  const { page, navs } = boot()
  page.openOrganizer(tap(7))
  page.onShow()
  page.openOrganizer(tap(7))
  assert.equal(navs.length, 2)
})

test('BL-226:缺 id 不跳也不上锁', () => {
  const { page, navs } = boot()
  page.openOrganizer(tap(undefined))
  page.openOrganizer({})
  assert.equal(navs.length, 0)
  page.openOrganizer(tap(7))
  assert.equal(navs.length, 1)
})
