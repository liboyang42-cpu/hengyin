'use strict'

// BL-242:个人主页头像「编辑资料」(经 onAvatarTap 转 goUserInfo)与他人主页「发消息」(先 /api/im/start 再跳聊天页)
// 连点只跳一次 / 只发一次请求;回包无 conversationId、网络失败、HTTP 异常三路都清锁并提示;complete 或 pageLifetimes.show 后可再跳。
const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const PROFILE = 'subpackageMember/components/cy/profile/index.js'

const navs = []
global.wx = {
  showToast() {}, hideToast() {}, showLoading() {}, hideLoading() {},
  getStorageSync() { return '' }, setStorageSync() {},
  stopPullDownRefresh() {}, startPullDownRefresh() {},
  navigateTo(o) { navs.push(o) }, navigateBack() {}, reLaunch() {}, switchTab() {},
  setNavigationBarTitle() {}, previewImage() {}, vibrateShort() {}, pageScrollTo() {},
  getSystemInfoSync() { return {} }, getAppBaseInfo() { return {} },
}

function mountProfile() {
  navs.length = 0
  const requests = []
  const tips = []
  let definition = null
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => '9',
    getUserRole: () => 'player',
    getUserType: () => 1,
    isDevEnv: () => false,
    getPageSize: () => 10,
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    sendRequest(request) { requests.push(request) },
    tips(msg) { tips.push(msg) },
  }
  global.getApp = () => app
  global.Component = (def) => { definition = def }
  delete require.cache[require.resolve(path.join(ROOT, PROFILE))]
  require(path.join(ROOT, PROFILE))
  const component = Object.assign({}, definition.methods, {
    data: JSON.parse(JSON.stringify(definition.data)),
  })
  component.setData = (patch) => { Object.assign(component.data, patch) }
  component.pageShow = definition.pageLifetimes.show
  component.requests = requests
  component.tips = tips
  component.chatRequests = () => requests.filter((r) => r.url === '/api/im/start')
  return component
}

for (const [view, merchant, url] of [['玩家', false, '/pages/gerenziliao/gerenziliao'], ['商家视角', true, '/pages/merchant/decor/index']]) {
  test(`BL-242:${view}本人主页连点头像只跳 1 次,complete 后可再跳`, () => {
    const c = mountProfile()
    Object.assign(c.data, { isSelf: true, isMerchantView: merchant })
    c.onAvatarTap(); c.onAvatarTap()
    assert.equal(navs.length, 1)
    assert.equal(navs[0].url, url)
    navs[0].complete()
    c.onAvatarTap()
    assert.equal(navs.length, 2)
  })

  test(`BL-242:${view}头像跳转后 pageLifetimes.show 兜底清锁`, () => {
    const c = mountProfile()
    Object.assign(c.data, { isSelf: true, isMerchantView: merchant })
    c._profileAttached = true
    c._profileShown = false
    c._syncProfileReducedMotion = () => {}
    c.onAvatarTap()
    try { c.pageShow.call(c) } catch (_) { /* 兜底清锁在 show 首行,后续刷新与本卡无关 */ }
    c.onAvatarTap()
    assert.equal(navs.length, 2)
  })
}

test('BL-242:他人主页头像不跳转也不上锁', () => {
  const c = mountProfile()
  c.data.isSelf = false
  c.onAvatarTap()
  assert.equal(navs.length, 0)
  c.goSetting()
  assert.equal(navs.length, 1)
})

function chatReady() {
  const c = mountProfile()
  Object.assign(c.data, { subjectIsMerchant: false, userId: 42, userInfo: { nickname: '阿城', avatar: 'a.png' } })
  return c
}

test('BL-242:请求未回时连点「发消息」只发 1 次 /api/im/start,成功只跳 1 次', () => {
  const c = chatReady()
  c.onStartChat(); c.onStartChat()
  assert.equal(c.chatRequests().length, 1)
  c.chatRequests()[0].success({ code: 200, data: { conversationId: 77 } })
  c.onStartChat()
  assert.equal(c.chatRequests().length, 1, '跳转未完成时仍不响应')
  assert.equal(navs.length, 1)
  assert.match(navs[0].url, /^\/subpackageB\/pages\/im\/chat\/index\?conversationId=77&/)
  navs[0].complete()
  c.onStartChat()
  assert.equal(c.chatRequests().length, 2, 'complete 后可再发起')
})

test('BL-242:回包无 conversationId 时清锁并提示', () => {
  const c = chatReady()
  c.onStartChat()
  c.chatRequests()[0].success({ code: 500, msg: '对方已关闭私信' })
  assert.deepEqual(c.tips, ['对方已关闭私信'])
  assert.equal(navs.length, 0)
  c.onStartChat()
  assert.equal(c.chatRequests().length, 2)
})

test('BL-242:网络失败时清锁并提示', () => {
  const c = chatReady()
  c.onStartChat()
  c.chatRequests()[0].fail()
  assert.deepEqual(c.tips, ['网络异常，请重试'])
  c.onStartChat()
  assert.equal(c.chatRequests().length, 2)
})

test('BL-242:HTTP 异常时清锁并提示(原来静默)', () => {
  const c = chatReady()
  c.onStartChat()
  const req = c.chatRequests()[0]
  assert.equal(typeof req.successStatusAbnormal, 'function')
  req.successStatusAbnormal({ statusCode: 502 })
  assert.deepEqual(c.tips, ['无法发起会话'])
  c.onStartChat()
  assert.equal(c.chatRequests().length, 2)
})

test('BL-242:缺 userId 或被看者是商家时不发请求也不上锁', () => {
  const c = mountProfile()
  Object.assign(c.data, { subjectIsMerchant: false, userId: '' })
  c.onStartChat()
  c.data.subjectIsMerchant = true
  c.data.userId = 42
  c.onStartChat()
  assert.equal(c.chatRequests().length, 0)
  c.goSetting()
  assert.equal(navs.length, 1)
})

test('BL-242:发消息请求在途时头像入口共用标记不叠页', () => {
  const c = chatReady()
  c.data.isSelf = true
  c.onStartChat()
  c.onAvatarTap()
  assert.equal(navs.length, 0)
})
