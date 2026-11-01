// 走查 v2 R02(业主 10-30,原型 p6 home 2 号):商家首页「我的消息」叠层 —— 展开后每条 × = 标为已读,
// 「清空」= 全部标为已读,标完收成「都看完了」;会话走 /api/im/read,邀请/经营动态记在本机(按账号)。
// 页面桩照 merchant-home-owner-ui-l03.test.js,只把 storage 换成可读写。
const { beforeEach, afterEach, test, mock } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const PAGE_PATH = path.join(ROOT, 'pages/merchant/index/index.js')
const motion = require('../../utils/motion.js')
const { buildInboxPreview, eventTimestamp } = require('../../pages/merchant/utils/inbox-preview.js')

let pageConfig
let requests
let userId
let navigated

global.getApp = () => ({
  globalData: { statusBarHeight: 44, navBarHeight: 44 },
  getUserID: () => userId,
  getAuthorization: () => 'token',
  getSession: () => ({ clearSession() {} }),
  getSessionManager: () => ({ ensureSession: () => Promise.resolve({ ok: true }) }),
  getUserRole: () => '',
  getUserType: () => 1,
  setUserRole() {},
  setUserType() {},
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  sendRequest: (request) => { requests.push(request) },
})

let store
global.wx = {
  getStorageSync: (k) => (k in store ? store[k] : ''),
  setStorageSync: (k, v) => { store[k] = JSON.parse(JSON.stringify(v)) },
  getWindowInfo: () => ({ statusBarHeight: 44 }),
  getSystemInfoSync: () => ({ statusBarHeight: 44 }),
  getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
  showToast() {},
  showLoading() {},
  hideLoading() {},
  switchTab: (opts) => { navigated.push(opts.url) },
  navigateTo: (opts) => { navigated.push(opts.url); if (opts.complete) opts.complete() },
  stopPullDownRefresh() {},
  setNavigationBarColor() {},
  setBackgroundColor() {},
  setNavigationBarTitle() {},
}

global.Page = (config) => { pageConfig = config }

function loadPage() {
  delete require.cache[require.resolve(PAGE_PATH)]
  require(PAGE_PATH)
  const page = Object.assign({}, pageConfig)
  page.data = JSON.parse(JSON.stringify(pageConfig.data))
  page.setCount = 0
  page.setData = function (patch) { this.setCount += 1; Object.assign(this.data, patch) }
  return page
}

const OWNER_PERMISSIONS = [
  'merchant:basic:read', 'merchant:profile:write', 'merchant:project:manage',
  'merchant:finance:read', 'merchant:verify', 'merchant:verify:record:read', 'merchant:coop:manage',
]

function accessReply(id = 3) {
  return { code: 200, data: { active: true, merchant: { id, name: 'A店' }, roleCode: 'MERCHANT_OWNER', permissions: OWNER_PERMISSIONS } }
}

function last(url) {
  const list = requests.filter((r) => r.url === url)
  return list[list.length - 1]
}

beforeEach(() => {
  pageConfig = null
  requests = []
  navigated = []
  userId = 7
  store = {}
  mock.timers.enable({ apis: ['setTimeout'] })
})

afterEach(() => { mock.timers.reset() })


function settle(page, now) {
  const iso = (msAgo) => new Date(now - msAgo).toISOString()
  last('/api/im/conversations').success({ code: 200, data: [
    { conversationId: 13, type: 1, unread: 2, lastMsgAt: iso(5 * 60 * 1000), lastMsgText: '在吗', counterparty: { nickname: '小王' } },
    { conversationId: 11, type: 2, unread: 0, lastMsgAt: iso(60 * 60 * 1000), lastMsgText: '欢迎', counterparty: { nickname: '城瘾小助手' } },
  ] })
  last('/api/coop/list').success({ code: 200, data: { received: [
    { id: 21, status: 0, inviteType: 1, topicName: '老城夜跑', createTime: iso(2 * 60 * 1000), partner: { name: '夜跑俱乐部' } },
  ], sent: [] } })
  last('/api/merchant/events').success({ code: 200, data: [
    { type: 'income', label: '收入到账', content: '主题分润 +¥5', time: '01-01 00:00' },
  ] })
}

test('R02 × = 标为已读:会话调 /api/im/read,下一条补上;重进页面不再露出', () => {
  const now = Date.now()
  let page = loadPage()
  page.onShow()
  last('/api/merchant/access/me').success(accessReply())
  settle(page, now)
  assert.deepEqual(page.data.inboxItems.map((i) => i.key), ['invite-21', 'conv-13', 'conv-11'])
  assert.deepEqual(page.data.inboxItems.map((i) => i.unread), [true, true, false], '只读过的会话不画未读点')

  page.openInboxItem({ currentTarget: { dataset: { key: 'invite-21' } } })
  assert.equal(page.data.msgOpen, true)
  page.dismissInboxItem({ currentTarget: { dataset: { key: 'conv-13' } } })
  const read = requests.filter((r) => r.url === '/api/im/read')
  assert.deepEqual(read.map((r) => r.data), [{ conversation_id: 13 }])
  assert.deepEqual(page.data.inboxItems.map((i) => i.key), ['invite-21', 'conv-11', 'event-0'], '后面的补上来')
  assert.deepEqual(navigated, [], '× 不跳转')

  page.dismissInboxItem({ currentTarget: { dataset: { key: 'invite-21' } } })
  assert.equal(requests.filter((r) => r.url === '/api/im/read').length, 1, '邀请没有已读接口,只记本机')

  page = loadPage()
  page.onShow()
  last('/api/merchant/access/me').success(accessReply())
  settle(page, now)
  assert.deepEqual(page.data.inboxItems.map((i) => i.key), ['conv-11', 'event-0'])
  assert.equal(page.data.inboxAllRead, false)
})

test('R02 清空 = 全部标为已读,收成「都看完了」;同一会话来新消息重新露出', () => {
  const now = Date.now()
  let page = loadPage()
  page.onShow()
  last('/api/merchant/access/me').success(accessReply())
  settle(page, now)
  page.openInboxItem({ currentTarget: { dataset: { key: 'invite-21' } } })
  page.clearInbox()
  assert.deepEqual(page.data.inboxItems, [])
  assert.equal(page.data.inboxAllRead, true)
  assert.equal(page.data.msgOpen, false)
  assert.deepEqual(requests.filter((r) => r.url === '/api/im/read').map((r) => r.data), [{ conversation_id: 13 }], '只给有未读的会话发已读')

  page = loadPage()
  page.onShow()
  last('/api/merchant/access/me').success(accessReply())
  settle(page, now)
  assert.equal(page.data.inboxAllRead, true, '重进仍是都看完了')

  page = loadPage()
  page.onShow()
  last('/api/merchant/access/me').success(accessReply())
  last('/api/im/conversations').success({ code: 200, data: [
    { conversationId: 13, type: 1, unread: 1, lastMsgAt: new Date(now).toISOString(), lastMsgText: '新消息', counterparty: { nickname: '小王' } },
  ] })
  last('/api/coop/list').success({ code: 200, data: { received: [], sent: [] } })
  last('/api/merchant/events').success({ code: 200, data: [] })
  assert.deepEqual(page.data.inboxItems.map((i) => i.key), ['conv-13'])
  assert.equal(page.data.inboxAllRead, false)
})

test('R02 换账号不共用已读记录;没有任何消息仍是「还没有消息」而不是「都看完了」', () => {
  const now = Date.now()
  let page = loadPage()
  page.onShow()
  last('/api/merchant/access/me').success(accessReply())
  settle(page, now)
  page.clearInbox()
  userId = 8
  page = loadPage()
  page.onShow()
  last('/api/merchant/access/me').success(accessReply())
  settle(page, now)
  assert.equal(page.data.inboxItems.length, 3)

  page = loadPage()
  page.onShow()
  last('/api/merchant/access/me').success(accessReply())
  last('/api/im/conversations').success({ code: 200, data: [] })
  last('/api/coop/list').success({ code: 200, data: { received: [], sent: [] } })
  last('/api/merchant/events').success({ code: 200, data: [] })
  assert.deepEqual(page.data.inboxItems, [])
  assert.equal(page.data.inboxAllRead, false)
})
