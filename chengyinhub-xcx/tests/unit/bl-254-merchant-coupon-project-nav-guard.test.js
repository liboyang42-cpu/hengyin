const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-254:商家工作台「更多」菜单「管理优惠券」与项目卡(非活动)连点只跳一次;complete/onShow 后可再跳;与同页其它入口共用 this._navigating;topicId 无效不跳不上锁

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

const COUPON = '/subpackageMember/coupon/coupon?scope=MERCHANT'
const PROJECT = '/pages/topic/merchantinfo/merchantinfo?topicId=42&scope=MERCHANT'
const MORE = (action) => ({ currentTarget: { dataset: { action } } })
const CARD = (ds) => ({ currentTarget: { dataset: ds } })

test('BL-254:onMoreItem(coupon) complete 未回调时连调两次只跳 1 次,complete 后可再跳', () => {
  const { page, navs } = loadPage()
  page.onMoreItem(MORE('coupon'))
  page.onMoreItem(MORE('coupon'))
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, COUPON)
  navs[0].complete()
  page.onMoreItem(MORE('coupon'))
  assert.equal(navs.length, 2)
})

test('BL-254:openProjectCard(非活动) complete 未回调时连调两次只跳 1 次,complete 后可再跳', () => {
  const { page, navs } = loadPage()
  page.openProjectCard(CARD({ biztype: 'topic', topicid: '42' }))
  page.openProjectCard(CARD({ biztype: 'topic', topicid: '42' }))
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, PROJECT)
  navs[0].complete()
  page.openProjectCard(CARD({ biztype: 'topic', topicid: '42' }))
  assert.equal(navs.length, 2)
})

test('BL-254:两处与同页其它入口共用一把锁,onShow 兜底清锁', () => {
  const { page, navs } = loadPage()
  page.goFinance()
  page.goManageCoupon()
  page.openProjectCard(CARD({ biztype: 'topic', topicid: '42' }))
  assert.equal(navs.length, 1)
  page._navigating = false // onShow 已有 this._navigating = false(BL-228)
  page.goManageCoupon()
  page.openProjectCard(CARD({ biztype: 'topic', topicid: '42' }))
  assert.equal(navs.length, 2)
  assert.equal(navs[1].url, COUPON)
})

test('BL-254:topicId 无效时不跳也不上锁', () => {
  const { page, navs } = loadPage()
  page.openProjectCard(CARD({ biztype: 'topic', topicid: '' }))
  page.goHostProject(CARD({ topicid: '-1' }))
  assert.equal(navs.length, 0)
  assert.notEqual(page._navigating, true)
  page.goManageCoupon()
  assert.equal(navs.length, 1)
})

test('BL-254:两处跳转带 complete 清锁,onShow 仍兜底清锁', () => {
  const source = fs.readFileSync(path.join(ROOT, PAGE), 'utf8')
  assert.match(source, /goManageCoupon\(\) \{\n    if \(this\._navigating\) return; this\._navigating = true; wx\.navigateTo\(\{[^\n]*complete: \(\) => \{ this\._navigating = false; \}/)
  assert.match(source, /goHostProject\(e\) \{[\s\S]{0,200}topicId <= 0 \|\| this\._navigating\) return;[^\n]*\n    this\._navigating = true; wx\.navigateTo\(\{[^\n]*complete: \(\) => \{ this\._navigating = false; \}/)
  assert.match(source, /onShow\(options\) \{[\s\S]{0,200}this\._navigating = false;/)
})
