const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-229:客户页「触达记录」连点只跳一次;complete 回调或 onShow 后可再跳;canCampaigns 为 false 不跳也不上锁

const ROOT = path.resolve(__dirname, '../..')
const PAGE = 'pages/merchant/customer/index.js'
const URL = '/pages/merchant/crm-campaigns/index'

function loadPage() {
  let definition
  const navs = []
  const base = { navigateTo: (o) => navs.push(o), getStorageSync: () => '' }
  const wx = new Proxy(base, { get(t, k) { return k in t ? t[k] : () => {} } })
  const app = new Proxy({ globalData: {}, sendRequest() { return { abort() {} } } }, {
    get(t, k) { return k in t ? t[k] : () => '' },
  })
  global.getApp = () => app
  global.wx = wx
  const pageDir = path.join(ROOT, 'pages/merchant/customer')
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, PAGE), 'utf8'), {
    getApp: () => app,
    Page(page) { definition = page },
    require(request) { return require(path.resolve(pageDir, request)) },
    Set, Date, JSON, wx, setTimeout, clearTimeout,
  }, { filename: path.join(ROOT, PAGE) })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch) },
  })
  page.data.canCampaigns = true
  page.data.toolsOpen = true
  return { page, navs }
}

test('BL-229:complete 未回调时连调两次只跳 1 次,并收起工具半屏', () => {
  const { page, navs } = loadPage()
  page.goCampaigns()
  page.goCampaigns()
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, URL)
  assert.equal(page.data.toolsOpen, false)
})

test('BL-229:complete 回调后可再跳', () => {
  const { page, navs } = loadPage()
  page.goCampaigns()
  navs[0].complete()
  page.goCampaigns()
  assert.equal(navs.length, 2)
})

test('BL-229:onShow 兜底清锁后可再跳', () => {
  const { page, navs } = loadPage()
  page.goCampaigns()
  page.onShow()
  page.goCampaigns()
  assert.equal(navs.length, 2)
})

test('BL-229:canCampaigns 为 false 时不跳也不上锁', () => {
  const { page, navs } = loadPage()
  page.data.canCampaigns = false
  page.goCampaigns()
  assert.equal(navs.length, 0)
  assert.equal(page.data.toolsOpen, true)
  page.data.canCampaigns = true
  page.goCampaigns()
  assert.equal(navs.length, 1)
})
