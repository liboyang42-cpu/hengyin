'use strict'
// PR #69 V3 · CL-25(F-L5C-10/11, F-W1-07):勾选、拒绝、登录失败、重提时同意记录都可用。
// 已有覆盖:usage-consent-app.test.js(勾选/拒绝/写失败同 UUID 重试)、
// community-gate-detail-favorites.test.js「隐私闸:本机已记下同意、仅留痕失败时关闭弹窗…」。
// 本文件补两条链:① 同意后登录失败 → 本机选择保留、不写假记录、登录成功后补记两份协议;
// ② 报名页单独同意(activity_host_data_sharing)记录失败不建单，重提成功才建单。
const test = require('node:test')
const assert = require('node:assert/strict')
const { createUsageConsent, VERSIONS } = require('../../utils/compliance/usage-consent.js')

function storage() {
  const map = new Map()
  return { map, getStorageSync: k => map.get(k), setStorageSync: (k, v) => map.set(k, v), removeStorageSync: k => map.delete(k) }
}

function harness() {
  const wx = storage()
  const state = { owner: '', token: '', loginOk: false, native: [], records: [], gates: 0 }
  const consent = createUsageConsent({
    wx, openGate: () => { state.gates++ },
    resolveNative: r => state.native.push(r),
    getUserID: () => state.owner, getAuthorization: () => state.token,
    login: async () => {
      if (!state.loginOk) return { ok: false, reason: 'network' }
      state.owner = '7'; state.token = 'real-token'; return { ok: true }
    },
    record: async input => {
      state.records.push(input)
      const pending = wx.getStorageSync('cy_usage_pending_v1:' + state.owner)
      const requestId = pending.pending[input.docType].requestId
      return { requestId, response: { code: 200, data: Object.assign({}, input, { requestId, docVersion: VERSIONS[input.docType] }) } }
    },
  })
  return { consent, state, wx }
}

test('未勾选服务协议:不结算微信授权、不登录、不写同意', async () => {
  const h = harness()
  await assert.rejects(h.consent.agree({ detail: { buttonId: 'agree' } }, false), /勾选/)
  assert.equal(h.state.native.length, 0)
  assert.equal(h.consent.granted(), false)
  assert.equal(h.state.records.length, 0)
})

test('同意后登录失败:如实失败、不写记录，但保留本机同意；登录成功后补记两份协议', async () => {
  const h = harness()
  await assert.rejects(h.consent.agree({ detail: { buttonId: 'agree' } }, true), /登录未完成/)
  assert.deepEqual(h.state.native.map(r => r.event), ['agree'])
  assert.equal(h.consent.granted(), true, '隐私闸据此关闭并提示「联网后会自动补记」')
  assert.equal(h.state.records.length, 0, '登录失败不得写出无主或假用户的同意记录')
  assert.equal(h.state.owner, '', '不得注入假用户')

  await h.consent.syncPending()
  assert.equal(h.state.records.length, 0, '仍是游客时不补传')

  h.state.loginOk = true
  await h.consent.agree({ detail: { buttonId: 'agree' } }, true)
  assert.deepEqual(h.state.records.map(r => [r.docType, r.scene, r.eventType]),
    [['privacy_policy', 'app_launch', 'AGREE'], ['user_service_agreement', 'app_launch', 'AGREE']])
  assert.deepEqual(h.wx.getStorageSync('cy_usage_pending_v1:7').pending, {}, '补记完成后不留待补传')
})

test('拒绝后清除本机同意，等待中的登录收到拒绝且不写记录', async () => {
  const h = harness()
  const waiting = h.consent.ensure()
  assert.equal(h.state.gates, 1)
  h.consent.refuse()
  await assert.rejects(waiting, /USAGE_CONSENT_DECLINED/)
  assert.equal(h.consent.granted(), false)
  assert.equal(h.state.records.length, 0)
})

// ---------- 报名页:单独同意先落库再建单 ----------
const PAGE_PATH = '../../pages/activity/baoming/baoming.js'

function loadSignupPage(recordConsent) {
  let config
  const tips = []
  global.getApp = () => ({ globalData: { user_id: 9, features: {} }, getUserID: () => 9, isDevEnv: () => false,
    tips: m => tips.push(m), goBack() {}, sendRequest() {}, recordConsent })
  global.Page = value => { config = value }
  global.wx = { getStorageSync: () => '', setStorageSync() {}, getWindowInfo: () => ({ statusBarHeight: 20 }),
    getSystemInfoSync: () => ({ statusBarHeight: 20 }), showToast() {}, showModal() {}, showLoading() {}, hideLoading() {} }
  delete require.cache[require.resolve(PAGE_PATH)]
  require(PAGE_PATH)
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) })
  page.setData = function (patch, done) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.'); let cursor = this.data
      parts.slice(0, -1).forEach(p => { cursor = cursor[p] = cursor[p] || {} })
      cursor[parts.at(-1)] = value
    }
    if (done) done()
  }
  Object.assign(page.data, { pageState: 'ready', activityId: 12, activityInfo: { id: 12 },
    selectedTicket: { id: 34, price: 88 }, selectedAddress: { id: 7, fullName: '林一', mobilePhone: '13800138000' },
    userInfo: { realName: '林一', phone: '13800138000', email: '', point: 0 },
    hostShareChecked: false, totalAmount: 88, paymentReady: true, canPay: true })
  page._quoteSign = 'quote-signed'
  page._signupSubscriptionRequested = true
  const orders = []
  page._workflow = { submit(payload) { orders.push(payload); return true } }
  return { page, tips, orders }
}

test('报名页:未勾选不记同意不建单；同意记录失败保留错误不建单；重提成功后才建单', async () => {
  const writes = []
  let fail = true
  const h = loadSignupPage(input => { writes.push(input); return fail ? Promise.reject(new Error('offline')) : Promise.resolve() })

  h.page.handlePayment()
  assert.equal(writes.length, 0)
  assert.equal(h.orders.length, 0)
  assert.equal(h.tips.at(-1), '请同意向主办方提供报名信息')

  h.page.data.hostShareChecked = true
  h.page.handlePayment()
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(writes, [{ docType: 'activity_host_data_sharing', scene: 'activity_signup', eventType: 'AGREE' }])
  assert.equal(h.orders.length, 0, '同意未落库不得建单')
  assert.equal(h.page.data.submitError, '同意记录未保存，请检查网络后重试')
  assert.equal(h.page.data.isPaying, false, '失败后必须能重提')
  assert.equal(h.page.data.hostShareConsentReady, false)

  fail = false
  h.page.handlePayment()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(writes.length, 2)
  assert.equal(h.page.data.hostShareConsentReady, true)
  assert.equal(h.orders.length, 1, '同意落库后才建单，且只建一次')
  assert.equal(h.page.data.submitError, '')
})
