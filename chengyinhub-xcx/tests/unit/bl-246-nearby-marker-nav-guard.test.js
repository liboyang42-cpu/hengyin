'use strict'
// BL-246:附近页地图点——活动点、本地没有行的主题点——连点只跳一次,complete/onShow 后可再跳;
// 有本地主题行时照旧开半屏、不上锁;队伍点开半屏不跳页。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')

function setByPath(target, raw, value) {
  const parts = raw.replace(/\[(\d+)\]/g, '.$1').split('.')
  let cur = target
  for (let i = 0; i < parts.length - 1; i += 1) { if (cur[parts[i]] == null) cur[parts[i]] = {}; cur = cur[parts[i]] }
  cur[parts[parts.length - 1]] = value
}

const PAGE = '../../subpackageRoam/nearby/index.js'
let config
let navs
// 真实 app 恒有 getUserID/getAuthorization;W8 起 onShow 用它们核对队伍写作用域(游客 = 空身份)
global.getApp = () => ({ getUserID: () => '', getAuthorization: () => '', globalData: {}, getImgUrl: (u) => u || '', sendRequest: () => {} })
global.wx = {
  getWindowInfo: () => ({ screenHeight: 844, safeArea: { bottom: 810 } }),
  getMenuButtonBoundingClientRect: () => ({ bottom: 88 }),
  getLocation: () => {},
  navigateTo: (o) => { navs.push(o) },
  showToast: () => {},
  showModal: () => {},
  createSelectorQuery: () => ({ select: () => ({ fields: () => ({ exec: () => {} }) }) }),
}
global.Page = (c) => { config = c }

function loadPage() {
  navs = []
  delete require.cache[require.resolve(PAGE)]
  require(PAGE)
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) })
  page.setData = function (patch, cb) { Object.keys(patch).forEach((k) => setByPath(this.data, k, patch[k])); if (cb) cb() }
  page.refresh = () => {}
  return page
}

function assertOnceThenAgain(page, tap, url) {
  tap(); tap()
  assert.equal(navs.length, 1, '连点只跳一次')
  assert.equal(navs[0].url, url)
  navs[0].complete()
  tap()
  assert.equal(navs.length, 2, 'complete 后可再跳')
}

const marker = (id) => ({ detail: { markerId: id } })

test('BL-246 活动点连点只跳一次,complete 后可再跳', () => {
  const page = loadPage()
  assertOnceThenAgain(page, () => page.onMarkerTap(marker(112)), '/pages/activity/detail/index?id=11')
})

test('BL-246 本地没有行的主题点连点只跳一次,complete 后可再跳', () => {
  const page = loadPage()
  page._items = []
  assertOnceThenAgain(page, () => page.onMarkerTap(marker(53)), '/pages/topic/index/index?id=5')
})

test('BL-246 活动点与主题点共用标记,onShow 兜底清锁后可再跳', () => {
  const page = loadPage()
  page._items = []
  page.onMarkerTap(marker(112))
  page.onMarkerTap(marker(53))
  page.openTopicCard(5)
  assert.equal(navs.length, 1)
  page.onShow()
  page.onMarkerTap(marker(53))
  assert.equal(navs.length, 2)
  assert.equal(navs[1].url, '/pages/topic/index/index?id=5')
})

test('BL-246 有本地主题行时开点位卡(v13)、不跳也不上锁', () => {
  const page = loadPage()
  page._items = [{ id: 5, kind: 'topic', productType: 2 }]
  page.onMarkerTap(marker(53))
  assert.equal(navs.length, 0)
  assert.ok(!page._navigating)
  assert.equal(page.data.poiCard && page.data.poiCard.id, 5)
  assert.equal(page.data.sheet, 'poi')
  page.onMarkerTap(marker(112))
  assert.equal(navs.length, 1)
})
