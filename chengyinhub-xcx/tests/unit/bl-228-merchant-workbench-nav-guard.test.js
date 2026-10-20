const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-228:商家工作台「核销记录」「开店前置项」连点只跳一次;complete 回调后可再跳;无权限/缺 path 不跳也不上锁

const ROOT = path.resolve(__dirname, '../..')
const PAGE = 'pages/merchant/index/index.js'

function loadPage() {
  let definition
  const navs = []
  const base = { navigateTo: (o) => navs.push(o), getStorageSync: () => '' }
  const wx = new Proxy(base, { get(t, k) { return k in t ? t[k] : () => {} } })
  const app = new Proxy({ globalData: {}, sendRequest() { return { abort() {} } } }, {
    get(t, k) { return k in t ? t[k] : () => '' },
  })
  const pageDir = path.join(ROOT, 'pages/merchant/index')
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
  page.data.merchantAccess = Object.assign({}, page.data.merchantAccess, { canReadVerifyRecords: true })
  page.data.prerequisites = [
    { key: 'shop', path: '/pages/merchant/shop/index' },
    { key: 'nopath', path: '' },
  ]
  return { page, navs }
}

const tapKey = (key) => ({ currentTarget: { dataset: { key } } })

test('BL-228:核销记录 complete 未回调时连调两次只跳 1 次,complete 后可再跳', () => {
  const { page, navs } = loadPage()
  page.goVerifyRecords()
  page.goVerifyRecords()
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, '/pages/merchant/verify-records/index')
  navs[0].complete()
  page.goVerifyRecords()
  assert.equal(navs.length, 2)
})

test('BL-228:开店前置项 complete 未回调时连调两次只跳 1 次,complete 后可再跳', () => {
  const { page, navs } = loadPage()
  page.goPrerequisite(tapKey('shop'))
  page.goPrerequisite(tapKey('shop'))
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, '/pages/merchant/shop/index')
  navs[0].complete()
  page.goPrerequisite(tapKey('shop'))
  assert.equal(navs.length, 2)
})

test('BL-228:两个入口共用跳转中标记,一个在途时另一个也不跳', () => {
  const { page, navs } = loadPage()
  page.goVerifyRecords()
  page.goPrerequisite(tapKey('shop'))
  assert.equal(navs.length, 1)
})

test('BL-228:无核销记录权限 / 缺 path / 未知 key 时不跳也不上锁', () => {
  const { page, navs } = loadPage()
  page.data.merchantAccess = Object.assign({}, page.data.merchantAccess, { canReadVerifyRecords: false })
  page.goVerifyRecords()
  page.goPrerequisite(tapKey('nopath'))
  page.goPrerequisite(tapKey('missing'))
  assert.equal(navs.length, 0)
  page.goPrerequisite(tapKey('shop'))
  assert.equal(navs.length, 1)
})
