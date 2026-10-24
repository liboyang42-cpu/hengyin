'use strict'
// BL-252:我的项目页 主题空态「去发布」(onEmptyCta → goCreate)、操作半屏「编辑」(onOpsPick edit → editActivity)、
// 「收到的申请」(onOpsPick received → goReceivedApplies)加跳转中保护——连点只跳一次,complete 后可再跳;
// 与 goCreateActivity 共用标记;onShow 兜底清锁;已开卖活动只提示、不跳不上锁。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')

const PAGE = '../../subpackageA/pages/myproject/index.js'
let navs = []
let toasts = []
let pageConfig = null

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: () => {}, getUserID: () => 1, getAuthorization: () => 'tk-1', getToken: () => 'tk-1',
  getRequestErrorMessage: (res, fallback) => fallback,
})
global.wx = {
  getStorageSync: () => undefined, setStorageSync: () => {}, removeStorageSync: () => {},
  showToast: (o) => { toasts.push(o && o.title) }, showLoading: () => {}, hideLoading: () => {}, showModal: () => {},
  navigateTo: (o) => { navs.push(o) },
  getSystemInfoSync: () => ({ windowWidth: 375 }), nextTick: (f) => f(),
  setNavigationBarColor: () => {}, setNavigationBarTitle: () => {},
}
global.Page = (cfg) => { pageConfig = cfg }

beforeEach(() => {
  navs = []
  toasts = []
  delete require.cache[require.resolve('../../utils/roleGuard.js')]
  delete require.cache[require.resolve(PAGE)]
  require(PAGE)
})

function makePage() {
  const inst = Object.assign({}, pageConfig)
  inst.data = JSON.parse(JSON.stringify(pageConfig.data))
  inst.setData = function (patch) { Object.assign(inst.data, patch) }
  return inst
}

// onShow 里其余刷新与本用例无关,只验证它会兜底清锁
function show(page) {
  const keep = page.onShow
  Object.keys(page).forEach((k) => { if (typeof page[k] === 'function' && !['onShow', 'setData'].includes(k)) page[k] = () => {} })
  try { keep.call(page) } catch (e) { /* 页内刷新依赖桩外环境,清锁语句在其之前 */ }
}

function onceThenAgain(page, tap, url) {
  tap(); tap()
  assert.equal(navs.length, 1, '连点只跳一次')
  assert.equal(navs[0].url, url)
  navs[0].complete()
  tap()
  assert.equal(navs.length, 2, 'complete 后可再跳')
  tap()
  assert.equal(navs.length, 2, '未回调前仍锁住')
  const fresh = Object.assign({}, page)
  show(fresh)
  assert.equal(fresh._navigating, false, 'onShow 保底清锁')
}

const pick = (k) => ({ currentTarget: { dataset: { k } } })
const opsSheet = { show: true, id: 6, biz: 'activity', online: true, title: '夜游' }

test('主题标签空态「去发布」连点只跳一次', () => {
  const page = makePage()
  page.data.typeTab = 'topic'
  onceThenAgain(page, () => page.onEmptyCta(), '/pages/publish/fabu/index')
})

test('操作半屏「编辑」连点只跳一次', () => {
  const page = makePage()
  onceThenAgain(page, () => { page.data.opsSheet = Object.assign({}, opsSheet); page.onOpsPick(pick('edit')) },
    '/pages/publish/activity/index?id=6')
})

test('操作半屏「收到的申请」连点只跳一次', () => {
  const page = makePage()
  onceThenAgain(page, () => { page.data.opsSheet = Object.assign({}, opsSheet); page.onOpsPick(pick('received')) },
    '/pages/coop/list/index?tab=received')
})

test('三处与 goCreateActivity 共用一个标记', () => {
  const page = makePage()
  page.data.typeTab = 'topic'
  page.goCreateActivity()
  page.onEmptyCta()
  page.data.opsSheet = Object.assign({}, opsSheet)
  page.onOpsPick(pick('edit'))
  page.data.opsSheet = Object.assign({}, opsSheet)
  page.onOpsPick(pick('received'))
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, '/pages/publish/activity/index')
})

test('已开卖活动只提示、不跳也不上锁', () => {
  const page = makePage()
  page.editActivity({ currentTarget: { dataset: { item: { id: 6, bizType: 'activity', _sold: true } } } })
  assert.equal(navs.length, 0)
  assert.deepEqual(toasts, ['已有人买票，不能再改这场活动'])
  assert.ok(!page._navigating)
  page.editActivity({ currentTarget: { dataset: { item: { id: 6, bizType: 'activity', signupCount: 0 } } } })
  assert.equal(navs.length, 1)
})
