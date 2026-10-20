'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('../helpers/ui-sandbox-vm.js')
const { createRequire } = require('node:module')

// BL-233:商家装修页 9 处跳转入口连点只跳一次;complete 回调或 onShow 后可再跳;缺资料时不跳也不上锁

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

const tap = (dataset) => ({ currentTarget: { dataset } })

const ENTRIES = [
  ['goSection', () => [tap({ view: 'basic' })], '/pages/merchant/decor/index?view=basic'],
  ['goAiNpc', () => [], '/pages/merchant/decor/ai-npc/index'],
  ['goCoopSetting', () => [], '/pages/merchant/decor/coop-setting/index'],
  ['goGallery', () => [], '/pages/merchant/decor/gallery/index'],
  ['goPerks', () => [], '/pages/merchant/decor/perks/index'],
  ['goReviews', () => [], '/pages/merchant/reviews/index?mode=manage'],
  ['goPublishActivity', () => [], '/pages/publish/activity/index?scope=MERCHANT'],
  ['goCityNode', () => [], '/pages/merchant/citynode/index'],
  ['goPreview', () => [], null],
]

for (const [name, args, url] of ENTRIES) {
  test(`BL-233:${name} 连点只跳 1 次,complete 后可再跳`, () => {
    const { page, navs } = loadPage()
    page[name](...args())
    page[name](...args())
    assert.equal(navs.length, 1)
    if (url) assert.equal(navs[0].url, url)
    else assert.match(navs[0].url, /userId=66.*&preview=1$/)
    navs[0].complete()
    page[name](...args())
    assert.equal(navs.length, 2)
  })
}

test('BL-233:不同入口共用一个标记,onShow 兜底清锁后可再跳', () => {
  const { page, navs } = loadPage()
  page.goGallery()
  page.goPerks()
  assert.equal(navs.length, 1)
  page.onShow()
  page.goPerks()
  assert.equal(navs.length, 2)
  assert.equal(navs[1].url, '/pages/merchant/decor/perks/index')
})

test('BL-233:资料未加载时「看公开主页」不跳也不上锁', () => {
  const { page, navs } = loadPage()
  page.data.m = {}
  page.goPreview()
  assert.equal(navs.length, 0)
  page.goGallery()
  assert.equal(navs.length, 1)
})
