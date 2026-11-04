const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const PAGE_PATH = '../../pages/activity/baoming/baoming.js'
const PAGE_DIR = path.join(__dirname, '../../pages/activity/baoming')

let config
let latestHandlers
let tips
let goBackCalls
let requests

beforeEach(() => {
  config = null
  latestHandlers = null
  tips = []
  goBackCalls = 0
  requests = []

  global.getApp = () => ({
    globalData: { user_id: 9, features: {} },
    getUserID: () => 9,
    isDevEnv: () => false,
    tips: message => tips.push(message),
    goBack: () => { goBackCalls += 1 },
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

function setByPath(target, dataPath, value) {
  const parts = dataPath.split('.')
  let cursor = target
  parts.slice(0, -1).forEach((part) => {
    if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {}
    cursor = cursor[part]
  })
  cursor[parts[parts.length - 1]] = value
}

function loadReadyPage() {
  delete require.cache[require.resolve(PAGE_PATH)]
  require(PAGE_PATH)
  const page = Object.assign({}, config)
  page.data = JSON.parse(JSON.stringify(config.data))
  page.setData = function (patch, callback) {
    Object.entries(patch).forEach(([dataPath, value]) => setByPath(this.data, dataPath, value))
    if (callback) callback()
  }
  Object.assign(page.data, {
    pageState: 'ready',
    activityId: 12,
    activityInfo: { id: 12 },
    selectedTicket: { id: 34, price: 88 },
    selectedAddress: { id: 7, fullName: '林一', mobilePhone: '13800138000' },
    userInfo: { realName: '林一', phone: '13800138000', email: '', point: 0 },
    hostShareChecked: true,
    hostShareConsentReady: true,
    totalAmount: 88,
    paymentReady: true,
    canPay: true,
  })
  page._hostShareConsentReady = true
  page._quoteSign = 'quote-signed'
  page._signupSubscriptionRequested = true
  page._workflow = {
    submit(_payload, handlers) {
      latestHandlers = handlers
      return true
    },
  }
  return page
}

test('报名或支付失败会持久显示，重新提交及关键选择变化会清除旧错误', () => {
  const page = loadReadyPage()

  page.data.submitError = '上一次失败'
  page.handlePayment()
  assert.equal(page.data.submitError, '', '开始一次真实重试时应先清掉旧错误')

  latestHandlers.onOrderFail({ msg: '报名名额刚刚已满' })
  assert.equal(page.data.submitError, '报名名额刚刚已满')
  assert.equal(tips.at(-1), '报名名额刚刚已满', 'toast 保留为即时反馈')

  page.selectAddress({ currentTarget: { dataset: {
    item: { id: 8, fullName: '周二', mobilePhone: '13900139000' },
  } } })
  assert.equal(page.data.submitError, '', '改选参与人后旧提交错误应失效')

  page.data.submitError = '旧支付错误'
  page.toggleHostShare()
  assert.equal(page.data.submitError, '', '改变单独同意后旧提交错误应失效')

  page.data.isPaying = false
  page.data.hostShareChecked = true
  page.data.hostShareConsentReady = true
  page.data.orderInfo = { registrationId: 99 }
  page._hostShareConsentReady = true
  page.handlePayment()
  latestHandlers.onPayFail({ errMsg: '支付服务暂不可用' })
  assert.equal(page.data.submitError, '支付服务暂不可用')
  /* 2026-09-05:失败不再额外弹 toast(toast + 页内错误 + 结果面板 = 同一句说三遍)。
     页内 submitError 仍是持久出口(上一行),面板负责把这一刻讲清楚。 */
  assert.equal(page.data.resultSheet.kind, 'fail')
  assert.equal(page.data.resultSheet.why, '支付服务暂不可用', '面板必须带上失败原因')
  assert.equal(goBackCalls, 0, '支付失败应留在结算页展示错误；未支付订单仍由取消接口释放')
  assert.equal(requests.at(-1).url, '/api/registration/cancel')
})

test('提交错误在固定动作区使用可访问 inline error，并为扩展高度留出正文空间', () => {
  const wxml = fs.readFileSync(path.join(PAGE_DIR, 'baoming.wxml'), 'utf8')
  const wxss = fs.readFileSync(path.join(PAGE_DIR, 'baoming.wxss'), 'utf8')
  const barStart = wxml.indexOf('class="bmbottom"')
  const errorStart = wxml.indexOf('<cy-inline-error class="submit-inline-error"')
  const payButton = wxml.indexOf('<cy-btn variant="primary"', errorStart)

  assert.ok(barStart >= 0 && errorStart > barStart && payButton > errorStart,
    '持久错误必须位于付款按钮所在的固定动作区，并出现在按钮之前')
  assert.match(wxml,
    /<cy-inline-error class="submit-inline-error"[^>]*wx:if="\{\{submitError\}\}"[^>]*title="[^"]*这次报名还没完成[^"]*"[^>]*sub="\{\{submitError\}\}"[^>]*action="[^"]*重新提交[^"]*"[^>]*bind:action="handlePayment"/s)
  assert.match(wxml,
    /class="bmbottom-spacer"[\s\S]*?--baoming-action-extra: \{\{submitError \? 'calc\(var\(--cy-btn-h\) \+ var\(--cy-space-2\)\)' : '0rpx'\}\}/)
  assert.match(wxml,
    /class="bmbottom"[\s\S]*?--baoming-action-extra: \{\{submitError \? 'calc\(var\(--cy-btn-h\) \+ var\(--cy-space-2\)\)' : '0rpx'\}\}/)
  assert.match(wxss,
    /\.bmbottom\s*,\s*\.bmbottom-spacer\s*\{[^}]*var\(--baoming-action-extra, 0rpx\)[^}]*env\(safe-area-inset-bottom\)/s,
    '错误出现时动作栏和正文占位必须使用同一高度算式')
})

test('报名与支付事实携带Checkout原registrationId及稳定原事件键', () => {
  const analytics = require('../../utils/analytics')
  const original = analytics.track
  const events = []
  analytics.track = (name, payload) => events.push({ name, payload })
  try {
    const page = loadReadyPage()
    page.finishSignup = () => {}
    page.handlePayment()
    latestHandlers.onFreeSuccess({ registrationId: 991, payableAmount: 0 })
    latestHandlers.onFreeSuccess({ registrationId: 991, payableAmount: 0 })
    latestHandlers.onPaySuccess({ registrationId: 991, payableAmount: 88 })
    assert.equal(events[0].payload.properties.registrationId, 991)
    assert.equal(events[0].payload.eventKey, 'signup_submit_registration_991')
    assert.equal(events[1].payload.eventKey, events[0].payload.eventKey)
    assert.equal(events[2].payload.properties.registrationId, 991)
    assert.equal(events[2].payload.eventKey, 'payment_success_registration_991')
  } finally {
    analytics.track = original
  }
})

// 第十轮 f1 item 8(j6 F5 / r1 F3):参与人列表先回包、/api/user/info 后回包时,资料不能把已选参与人的
// 姓名冲掉 —— 否则第一次付款必误报「参与人信息需要包含姓名」。错误仍写在参与人一栏下方(红字,kind=error)。
test('资料晚于参与人回包:已选参与人的姓名/手机保留,付款不误报缺姓名', () => {
  const page = loadReadyPage()
  page.data.userInfo = {}
  page.updateUserInfoFromAddress(page.data.selectedAddress)
  page.getUserInfo()
  const info = requests.find((r) => r.url === '/api/user/info')
  info.success({ code: 200, data: { id: 9, name: '城瘾用户', nickname: '城瘾用户', point: 50 } })
  assert.equal(page.data.userInfo.realName, '林一')
  assert.equal(page.data.userInfo.phone, '13800138000')
  page.handlePayment()
  assert.equal(page.data.participantError, '', '已选了有姓名的参与人,不得报「需要包含姓名」')
  assert.ok(latestHandlers, '校验通过后必须进入下单')
  const wxml = fs.readFileSync(path.join(PAGE_DIR, 'baoming.wxml'), 'utf8')
  assert.match(wxml, /<cy-inline-error class="participant-err" wx:if="\{\{pageState === 'ready' && participantError\}\}" kind="error"/)
})
