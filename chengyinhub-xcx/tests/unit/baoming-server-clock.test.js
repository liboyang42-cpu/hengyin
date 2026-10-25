'use strict'
// U-168(2026-10-24 拍板):报名页置灰场次/票种/候补窗口改按服务器时刻,与建单闸
// (RegistrationOrderCreateGateImpl)同一把钟;缺 serverNow 才退回手机时钟。
// 负控:把 baoming.js 里的 clockNow() 改回 Date.now() → 「手机慢/手机快」两条用例红。
const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')

const BAOMING_PAGE = '../../pages/activity/baoming/baoming.js'
const HOUR = 3600 * 1000

let pageConfig
let requests

global.getApp = () => ({
  globalData: { user_id: 9, features: {} },
  isDevEnv: () => false,
  getUserID: () => 9,
  tips() {},
  sendRequest: (request) => { requests.push(request) },
  recordConsent: () => {
    const settled = { then(resolve) { resolve(); return settled }, catch() { return settled } }
    return settled
  },
})

global.wx = {
  getStorageSync: () => '',
  setStorageSync() {},
  getWindowInfo: () => ({ statusBarHeight: 20 }),
  getSystemInfoSync: () => ({ statusBarHeight: 20 }),
  showLoading() {},
  hideLoading() {},
  showToast() {},
  showModal() {},
  requestSubscribeMessage() {},
}

global.Page = (config) => { pageConfig = config }

beforeEach(() => {
  pageConfig = null
  requests = []
})

function setByPath(target, path, value) {
  const parts = path.split('.')
  let cursor = target
  parts.slice(0, -1).forEach((part) => {
    if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {}
    cursor = cursor[part]
  })
  cursor[parts[parts.length - 1]] = value
}

function loadPage() {
  delete require.cache[require.resolve(BAOMING_PAGE)]
  delete require.cache[require.resolve('../../utils/loading.js')]
  require(BAOMING_PAGE)
  const page = Object.assign({}, pageConfig, { data: JSON.parse(JSON.stringify(pageConfig.data)) })
  page.setData = (patch, callback) => {
    Object.entries(patch).forEach(([path, value]) => setByPath(page.data, path, value))
    if (callback) callback()
  }
  return page
}

const iso = (ms) => new Date(ms).toISOString()

function loadWith(tickets, serverNow) {
  const page = loadPage()
  page.data.activityId = 12
  page.getActivityInfo()
  const data = { id: 12, name: '夜行城市', startDate: '2026-08-01 19:00:00', omsTicketList: tickets }
  if (serverNow !== undefined) data.serverNow = serverNow
  requests[0].success({ code: '200', data })
  return page
}

test('手机慢于服务器:手机看场次还没开始,按服务器时刻已开始 ⇒ 置灰', () => {
  const phone = Date.now()
  const ticket = { id: 34, name: '标准票', price: 88, startTime: iso(phone + HOUR), endTime: iso(phone + 3 * HOUR) }
  const page = loadWith([ticket], phone + 2 * HOUR)
  assert.equal(page.data.signupState, 'closed', '置灰必须跟服务器时刻走')
  assert.ok(Math.abs(page.clockNow() - (Date.now() + 2 * HOUR)) < 5000)
})

test('手机快于服务器:手机看已开始,按服务器时刻仍可报 ⇒ 不置灰', () => {
  const phone = Date.now()
  const ticket = { id: 34, name: '标准票', price: 88, startTime: iso(phone - HOUR), endTime: iso(phone + 3 * HOUR) }
  const page = loadWith([ticket], phone - 2 * HOUR)
  assert.equal(page.data.signupState, 'available')
})

test('换票弹层按服务器时刻过滤已开始场次', () => {
  const phone = Date.now()
  const early = { id: 1, name: 'A', price: 10, startTime: iso(phone + HOUR), endTime: iso(phone + 5 * HOUR) }
  const late = { id: 2, name: 'B', price: 10, startTime: iso(phone + 4 * HOUR), endTime: iso(phone + 6 * HOUR) }
  const later = { id: 3, name: 'C', price: 10, startTime: iso(phone + 5 * HOUR), endTime: iso(phone + 7 * HOUR) }
  const page = loadWith([early, late, later], phone + 2 * HOUR)
  assert.equal(page.data.selectedTicket.id, 2, '默认落在按服务器时刻还能卖的第一场')
  page.openTicketSheet()
  assert.deepEqual(page._ticketSheetList.map((t) => t.id), [2, 3])
})

test('候补名额到期按服务器时刻判:手机看还剩 10 分钟,服务器已过 ⇒ 立即收回', () => {
  const phone = Date.now()
  const page = loadWith([{ id: 34, name: '标准票', price: 88 }], phone + 30 * 60 * 1000)
  let expired = 0
  page.expireWaitlistOfferInView = () => { expired += 1 }
  page.data.waitlistState = 'OFFERED'
  page._waitlistOfferExpiresAt = iso(phone + 10 * 60 * 1000)
  try {
    page.scheduleWaitlistOfferExpiry()
    assert.equal(expired, 1)
  } finally {
    clearTimeout(page._waitlistOfferTimer)
  }
})

test('缺 serverNow / 非法值时退回手机时钟', () => {
  const phone = Date.now()
  const ticket = { id: 34, name: '标准票', price: 88, startTime: iso(phone + HOUR), endTime: iso(phone + 3 * HOUR) }
  for (const serverNow of [undefined, null, '', 'abc', 0, -5]) {
    requests = []
    const page = loadWith([ticket], serverNow)
    assert.equal(page.data.signupState, 'available', 'serverNow=' + String(serverNow))
    assert.ok(Math.abs(page.clockNow() - Date.now()) < 1000)
  }
})
