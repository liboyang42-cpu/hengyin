// P-05:报价被服务端拒绝(如非平台主题暂不支持在线支付)时,下单页说出真实原因并关掉付款,
// 不能带空签名建单换来「缺少报价签名」。
const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const PAGE_PATH = '../../pages/activity/baoming/baoming.js'
const WXML = fs.readFileSync(path.join(__dirname, '../../pages/activity/baoming/baoming.wxml'), 'utf8')
const REASON = '该产品暂不支持在线支付(平台正在办理支付资质,预计 2 个月内开放);如需报名请联系主办方'

let config
let requests
let tips

beforeEach(() => {
  config = null
  requests = []
  tips = []
  global.getApp = () => ({
    globalData: { user_id: 9, features: {} },
    getUserID: () => 9,
    isDevEnv: () => false,
    tips(m) { tips.push(m) },
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
  page.checkPaymentReadiness = () => {}
  page.clockNow = () => Date.parse('2026-11-02T00:00:00Z')
  Object.assign(page.data, {
    pageState: 'ready',
    activityId: 12,
    selectedTicket: { id: 34, price: 69 },
    selectedAddress: { id: 1 },
    hostShareChecked: true,
    userInfo: { realName: '张三', phone: '13800000000', point: 0 },
    useDiscount: false,
    totalAmount: 69,
    paymentReady: true,
  })
  return page
}

test('报价被拒:记下真实原因,付款按钮关闭', () => {
  const page = loadPage()
  page.refreshQuote()
  const quote = requests.find(r => r.url === '/api/registration/quote')
  quote.success({ code: 500, msg: REASON })

  assert.equal(page.data.quoteRefusal, REASON)
  assert.equal(page.data.submitError, REASON)
  assert.equal(page.data.canPay, false)
})

test('报价被拒后点付款:不建单,只重复真实原因', () => {
  const page = loadPage()
  page.refreshQuote()
  requests[0].success({ code: 500, msg: REASON })
  requests.length = 0

  page.handlePayment()

  assert.equal(requests.length, 0, '不得再发报价或建单请求')
  assert.deepEqual(tips, [REASON])
})

test('报价成功会清掉旧的拒绝原因', () => {
  const page = loadPage()
  page.refreshQuote()
  requests[0].success({ code: 500, msg: REASON })
  page.refreshQuote()
  requests[1].success({ code: 200, data: { payAmount: 69, quoteSign: 'sig' } })
  assert.equal(page.data.quoteRefusal, '')
  assert.equal(page.data.quoteReady, true)
})

test('按钮与提示条读同一个原因', () => {
  assert.match(WXML, /quoteRefusal \? '暂不可报名'/)
  assert.match(WXML, /title="\{\{quoteRefusal \? '暂不可报名' : '这次报名还没完成'\}\}"/)
})

test('P-31:勾选信息共享后按钮不再提示「请先勾选」', () => {
  assert.match(WXML, /\(hostShareConsentReady \|\| hostShareChecked\) \?/)
})
