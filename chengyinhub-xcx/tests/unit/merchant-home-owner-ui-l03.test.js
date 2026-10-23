// 2026-10-22 owner 裁决 L03(商家工作台):
//   (a) 同一账号回到页面时保留上次确认的岗位权限,收入区/快捷钮不消失再出现;换账号才 fail closed。
//   (b) 累计收入永远在位:首帧 ¥0.00,真值到了从当前显示值慢-快-慢滚过去,返回不归零,出错保留。
//   (c) 「我的消息」常驻:站内会话 + 收到的邀请 + 经营动态合并取前 3 条,新的在前。
const { beforeEach, afterEach, test, mock } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const PAGE_PATH = path.join(ROOT, 'pages/merchant/index/index.js')
const motion = require('../../utils/motion.js')
const { buildInboxPreview, eventTimestamp } = require('../../utils/inbox-preview.js')

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

global.wx = {
  getStorageSync: () => '',
  setStorageSync() {},
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
  mock.timers.enable({ apis: ['setTimeout'] })
})

afterEach(() => { mock.timers.reset() })

test('easeInOutCubic:两头慢中间快,端点与对称性正确;默认缓动不变', () => {
  const e = motion.easeInOutCubic
  assert.equal(e(0), 0)
  assert.equal(e(1), 1)
  assert.equal(e(0.5), 0.5)
  assert.ok(Math.abs(e(0.25) + e(0.75) - 1) < 1e-12, '关于中点对称')
  assert.ok(e(0.1) < 0.1 && e(0.9) > 0.9, '起步与收尾都比线性慢')
  const frames = motion.countUpFrames(0, 100, { duration: 1300, fps: 60, decimals: 2, ease: e })
  assert.equal(frames.length, 78)
  assert.equal(frames[frames.length - 1].value, 100)
  const step = (i) => frames[i].value - (i ? frames[i - 1].value : 0)
  assert.ok(step(39) > step(1) && step(39) > step(77), '中段每帧增量大于两端')
  // 未传 ease 的老调用方仍是 easeOutCubic
  assert.deepEqual(motion.countUpFrames(0, 10, { duration: 100, fps: 10 }).map((f) => f.value),
    motion.countUpFrames(0, 10, { duration: 100, fps: 10, ease: motion.easeOutCubic }).map((f) => f.value))
})

test('(b) 累计收入首帧 ¥0.00,真值到了缓入缓出滚到位;返回页面从上次显示值继续,不归零', () => {
  const page = loadPage()
  assert.equal(page.data.revenueShown, '0.00', '首帧就是 0.00,不是骨架或「暂未取到」')
  page.onShow()
  last('/api/merchant/access/me').success(accessReply())
  last('/api/merchant/dashboard').success({ code: 200, data: { revenue: 1000 } })
  assert.equal(page.data.dashboardData.revenue, '1000.00')
  assert.equal(page.data.revenueShown, '0.00', '还没起帧')

  mock.timers.tick(130)
  const early = Number(page.data.revenueShown)
  mock.timers.tick(520)
  const middle = Number(page.data.revenueShown)
  assert.ok(early > 0 && early < 100, '起步慢:10% 时间只走了一小段, got ' + early)
  assert.ok(Math.abs(middle - 500) < 30, '半程约在一半, got ' + middle)
  mock.timers.tick(650)
  assert.equal(page.data.revenueShown, '1000.00')

  // 返回页面:金额不归零,从 1000 滚到 1200
  page.onShow()
  assert.equal(page.data.revenueShown, '1000.00')
  last('/api/merchant/access/me').success(accessReply())
  last('/api/merchant/dashboard').success({ code: 200, data: { revenue: 1200 } })
  mock.timers.tick(20)
  const resumed = Number(page.data.revenueShown)
  assert.ok(resumed >= 1000 && resumed < 1010, '从上次显示值起滚, got ' + resumed)
  mock.timers.tick(1300)
  assert.equal(page.data.revenueShown, '1200.00')

  // 出错:保留上次显示值,只给小号内联错误
  page.onShow()
  last('/api/merchant/access/me').success(accessReply())
  const dash = last('/api/merchant/dashboard')
  dash.fail({ errMsg: 'timeout' })
  mock.timers.tick(2000)
  assert.equal(page.data.revenueShown, '1200.00')
  assert.ok(page.data.dashboardError)
})

test('(b) 帧内金额不变就不 setData;同值重载不起任何帧', () => {
  const page = loadPage()
  page.onShow()
  last('/api/merchant/access/me').success(accessReply())
  last('/api/merchant/dashboard').success({ code: 200, data: { revenue: 0.05 } })
  const before = page.setCount
  mock.timers.tick(1400)
  assert.equal(page.data.revenueShown, '0.05')
  assert.ok(page.setCount - before <= 5, '只有 0.01..0.05 五个不同显示值才写, got ' + (page.setCount - before))
})

test('(a) 同一账号 onShow:重新确认期间保留上次确认的权限,页面不变形', () => {
  const page = loadPage()
  page.onShow()
  last('/api/merchant/access/me').success(accessReply())
  assert.equal(page.data.merchantAccess.canReadFinance, true)
  page.data.projectCards = [{ key: 'k' }]

  page.onShow()
  assert.equal(page.data.merchantAccess.active, true, 'access/me 回来之前不得先 fail closed')
  assert.equal(page.data.merchantAccess.canVerify, true, '扫码按钮不消失')
  assert.equal(page.data.merchantAccess.canReadFinance, true, '收入区不消失')
  assert.deepEqual(page.data.projectCards, [{ key: 'k' }], '同主体不清数据')
  last('/api/merchant/access/me').success(accessReply())
  assert.equal(page.data.merchantAccess.canReadFinance, true)
})

test('(a) 换账号:立即 fail closed,换主体清数据并把收入归零', () => {
  const page = loadPage()
  page.onShow()
  last('/api/merchant/access/me').success(accessReply(3))
  last('/api/merchant/dashboard').success({ code: 200, data: { revenue: 88 } })
  mock.timers.tick(1400)
  assert.equal(page.data.revenueShown, '88.00')

  userId = 8
  page.onShow()
  assert.equal(page.data.merchantAccess.active, false, '换账号不得沿用上一账号的岗位权限')
  last('/api/merchant/access/me').success(accessReply(4))
  assert.equal(page.data.revenueShown, '0.00', '新主体不能看到上一主体的金额')
})

test('(a) 确认失去商家身份才清:access/me 说 inactive 时撤掉权限', () => {
  const page = loadPage()
  page.onShow()
  last('/api/merchant/access/me').success(accessReply())
  page.onShow()
  last('/api/merchant/access/me').success({ code: 200, data: { active: false, applicationState: 'NONE', permissions: [] } })
  assert.equal(page.data.merchantAccess.active, false)
  assert.deepEqual(navigated, ['/pages/member/index/index'])
})

test('(c) 我的消息:三路落定后一次合并,新的在前取 3 条;落定前不冒充空态', () => {
  const page = loadPage()
  page.onShow()
  last('/api/merchant/access/me').success(accessReply())
  assert.equal(page.data.inboxLoaded, false)
  const now = Date.now()
  const iso = (msAgo) => new Date(now - msAgo).toISOString()
  last('/api/im/conversations').success({ code: 200, data: [
    { conversationId: 11, type: 2, lastMsgAt: iso(60 * 60 * 1000), lastMsgText: '欢迎来到城瘾', counterparty: { nickname: '城瘾小助手' } },
    { conversationId: 12, type: 4, lastMsgAt: iso(1000), lastMsgText: '群消息' },
    { conversationId: 13, type: 1, lastMsgAt: iso(5 * 60 * 1000), lastMsgText: '在吗', counterparty: { nickname: '小王', avatar: '/a.png' } },
  ] })
  assert.equal(page.data.inboxLoaded, false, '还有来源在途,不先渲半份')
  last('/api/coop/list').success({ code: 200, data: { received: [
    { id: 21, status: 0, inviteType: 1, topicName: '老城夜跑', createTime: iso(2 * 60 * 1000), partner: { name: '夜跑俱乐部' } },
  ], sent: [] } })
  last('/api/merchant/events').success({ code: 200, data: [
    { type: 'income', label: '收入到账', content: '主题分润 +¥5', time: '01-01 00:00' },
  ] })
  assert.equal(page.data.inboxLoaded, true)
  assert.deepEqual(page.data.inboxItems.map((i) => i.kind), ['invite', 'conversation', 'conversation'])
  assert.equal(page.data.inboxItems[1].title, '小王')
  assert.equal(page.data.inboxItems[2].title, '城瘾小助手')
  assert.equal(page.data.inboxItems.some((i) => i.conversationId === 12), false, '群聊不露出')

  // 2026-10-23 业主:多条消息先叠放,第一次点只展开、不跳转;展开后再点才进详情
  assert.equal(page.data.msgOpen, false)
  page.openInboxItem({ currentTarget: { dataset: { key: page.data.inboxItems[0].key } } })
  assert.equal(page.data.msgOpen, true, '叠放时点首张 = 展开')
  assert.deepEqual(navigated, [], '展开这一下不能跳走')
  page.openInboxItem({ currentTarget: { dataset: { key: page.data.inboxItems[0].key } } })
  page.openInboxItem({ currentTarget: { dataset: { key: page.data.inboxItems[1].key } } })
  page.goInbox()
  assert.deepEqual(navigated, [
    '/pages/coop/invite-detail/index?inviteId=21&box=received',
    '/subpackageB/pages/im/chat/index?conversationId=13&name=%E5%B0%8F%E7%8E%8B&avatar=%2Fa.png&type=1',
    '/subpackageB/pages/im/list/index',
  ])

  // 返回页面:新一轮落定前旧列表留在屏上
  page.onShow()
  last('/api/merchant/access/me').success(accessReply())
  assert.equal(page.data.inboxItems.length, 3)
  last('/api/im/conversations').fail({ errMsg: 'timeout' })
  last('/api/coop/list').fail({ errMsg: 'timeout' })
  last('/api/merchant/events').fail({ errMsg: 'timeout' })
  assert.equal(page.data.inboxItems.length, 3, '全部失败保留上次确认的列表')
  assert.equal(page.data.inboxError, false)
})

test('(c) 没有消息时落「还没有消息」空态;没有财务/核销/协作权限也照常读会话', () => {
  const page = loadPage()
  page.onShow()
  last('/api/merchant/access/me').success({ code: 200, data: { active: true, merchant: { id: 3 }, roleCode: 'MERCHANT_CHECKIN', permissions: ['merchant:basic:read', 'merchant:verify'] } })
  assert.equal(requests.some((r) => r.url === '/api/coop/list'), false, '没有 COOP_MANAGE 不撞协作接口')
  last('/api/im/conversations').success({ code: 200, data: [] })
  assert.equal(page.data.inboxLoaded, true)
  assert.deepEqual(page.data.inboxItems, [])
  assert.equal(page.data.inboxError, false)
})

test('inbox-preview:经营动态补年份跨年回退,取不到时间的排最后', () => {
  const now = Date.parse('2026-01-02T10:00:00+08:00')
  assert.equal(eventTimestamp('01-02 09:00', now), Date.parse('2026-01-02T09:00:00+08:00'))
  assert.equal(eventTimestamp('12-31 23:00', now), Date.parse('2025-12-31T23:00:00+08:00'), '未来的 12 月退回去年')
  assert.ok(Number.isNaN(eventTimestamp('坏数据', now)))
  const items = buildInboxPreview({
    events: [{ type: 'verify', label: '核销', content: '完成一笔到店核销', time: '??' },
      { type: 'income', label: '收入到账', content: '+¥1', time: '01-02 09:00' }],
  }, { now, limit: 3 })
  assert.deepEqual(items.map((i) => i.type), ['income', 'verify'])
  assert.equal(items[0].time, '09:00')
})
