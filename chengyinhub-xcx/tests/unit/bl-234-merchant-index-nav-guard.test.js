const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-234:商家工作台其余 9 个跳转入口连点只跳一次;complete 回调后可再跳;与 BL-228 共用 this._navigating

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
  page.data.merchantAccess = Object.assign({}, page.data.merchantAccess, {
    canReadAftercare: true, canManageProjects: true, canReadVerifyRecords: true,
  })
  return { page, navs }
}

const CASES = [
  ['goAftercare', undefined, '/pages/merchant/aftercare/index'],
  ['goApplicationProgress', undefined, '/pages/merchant/apply/index'],
  ['goPredictInbox', undefined, '/pages/merchant/predict/index'],
  ['goEvent', { currentTarget: { dataset: { type: 'income' } } }, '/pages/merchant/ledger/index?view=settlement'],
  ['goFinance', undefined, '/subpackageA/pages/assetcenter/earnings/index?theme=merchant'], // 2026-10-28 M-32:财务先进账户收益浅色版
  ['goBrandManage', undefined, '/pages/merchant/decor/index'],
  ['goCustomers', undefined, '/pages/merchant/customer/index'],
  ['goProjectMessages', { currentTarget: { dataset: { ownertype: 1, ownerid: 7 } } }, '/pages/merchant/ledger/index?view=messages&ownerType=1&ownerId=7'],
  ['goPublishedProjects', undefined, '/subpackageA/pages/myproject/index?scope=MERCHANT'],
]

for (const [name, evt, url] of CASES) {
  test(`BL-234:${name} complete 未回调时连调两次只跳 1 次,complete 后可再跳`, () => {
    const { page, navs } = loadPage()
    page[name](evt)
    page[name](evt)
    assert.equal(navs.length, 1)
    assert.equal(navs[0].url, url)
    navs[0].complete()
    page[name](evt)
    assert.equal(navs.length, 2)
  })
}

test('BL-234:无权限或缺参不跳也不上锁', () => {
  const { page, navs } = loadPage()
  page.data.merchantAccess = Object.assign({}, page.data.merchantAccess, { canReadAftercare: false, canManageProjects: false })
  page.goAftercare()
  page.goPredictInbox()
  page.goProjectMessages({ currentTarget: { dataset: { id: 7, ownertype: 1 } } })
  assert.equal(navs.length, 0)
  page.goFinance()
  assert.equal(navs.length, 1)
})

test('BL-234:不同入口共用一把锁,onShow 兜底清锁', () => {
  const { page, navs } = loadPage()
  page.goFinance()
  page.goCustomers()
  assert.equal(navs.length, 1)
  page._navigating = false // onShow 首行已有 this._navigating = false(BL-228)
  page.goCustomers()
  assert.equal(navs.length, 2)
  const src = fs.readFileSync(path.join(ROOT, PAGE), 'utf8')
  assert.match(src, /onShow\(options\) \{\n\s*merchantTheme\.merchantPageShow\(\); this\._navigating = false;/)
})
