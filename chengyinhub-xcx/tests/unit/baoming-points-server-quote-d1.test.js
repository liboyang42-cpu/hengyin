// owner 2026-10-23 D1(审计 P0-01):积分汇率/单笔上限只在服务端(sys_config: points.deduct.*),
// 下单页不再自己算可抵扣额 —— 切换积分抵扣后先按票面价展示,随后由 /api/registration/quote 的权威数字覆盖。
const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const PAGE_PATH = '../../pages/activity/baoming/baoming.js'
const PAGE_SOURCE = fs.readFileSync(path.join(__dirname, PAGE_PATH), 'utf8')

let config
let requests

beforeEach(() => {
  config = null
  requests = []
  global.getApp = () => ({
    globalData: { user_id: 9, features: {} },
    getUserID: () => 9,
    isDevEnv: () => false,
    tips() {},
    goBack() {},
    sendRequest(options) { requests.push(options) },
    recordConsent: () => Promise.resolve(),
  })
  global.Page = value => { config = value }
  global.wx = {
    getStorageSync: () => '',
    setStorageSync() {},
    getWindowInfo: () => ({ statusBarHeight: 20 }),
    getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    showToast() {},
    showModal() {},
    showLoading() {},
    hideLoading() {},
  }
})

function loadPage() {
  delete require.cache[require.resolve(PAGE_PATH)]
  require(PAGE_PATH)
  const page = Object.assign({}, config)
  page.data = JSON.parse(JSON.stringify(config.data))
  page.setData = function (patch, callback) {
    Object.assign(this.data, patch)
    if (callback) callback()
  }
  page.refreshPaymentState = () => {}
  page.checkPaymentReadiness = () => {}
  Object.assign(page.data, {
    pageState: 'ready',
    activityId: 12,
    selectedTicket: { id: 34, price: 100 },
    userInfo: { point: 10000 },
    useDiscount: false,
    totalAmount: 100,
  })
  return page
}

test('源码里不再有端上积分汇率/上限常量', () => {
  assert.doesNotMatch(PAGE_SOURCE, /pointValue|maxDiscount/)
  assert.doesNotMatch(PAGE_SOURCE, /0\.02/)
  assert.doesNotMatch(PAGE_SOURCE, /userPoints \*/)
})

test('切换积分抵扣:本地不估算,只展示服务端报价的抵扣额与实付', () => {
  const page = loadPage()
  page.toggleDiscount()

  assert.equal(page.data.useDiscount, true)
  // 报价未回:按票面价、抵扣 0(不是本地按 0.02 算出的 50)
  assert.equal(page.data.discountAmount, 0)
  assert.equal(page.data.usedPoints, 0)
  assert.equal(page.data.totalAmount, 100)
  const quote = requests.find(r => r.url === '/api/registration/quote')
  assert.ok(quote, '必须向服务端要报价')
  assert.equal(JSON.parse(quote.data).isUsePoint, 1)

  // 服务端按 sys_config 算出的数字(例如 owner 把汇率改成 0.01、上限 30)原样上屏
  quote.success({ code: 200, data: { payAmount: 70, pointsDeductYuan: 30, pointsUsed: 3000, quoteSign: 's' } })
  assert.equal(page.data.discountAmount, 30)
  assert.equal(page.data.usedPoints, 3000)
  assert.equal(page.data.totalAmount, 70)
  assert.equal(page.data.quoteReady, true)
})

test('关闭积分抵扣同样回到票面价并重新报价', () => {
  const page = loadPage()
  Object.assign(page.data, { useDiscount: true, discountAmount: 50, usedPoints: 2500, totalAmount: 50 })
  page.toggleDiscount()
  assert.equal(page.data.useDiscount, false)
  assert.equal(page.data.discountAmount, 0)
  assert.equal(page.data.totalAmount, 100)
  const quote = requests.find(r => r.url === '/api/registration/quote')
  assert.equal(JSON.parse(quote.data).isUsePoint, 0)
})
