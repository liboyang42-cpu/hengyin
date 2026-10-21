'use strict'
// BL-237:我的项目页 项目卡(onCardTap 三个分支)/「发布单场活动」(goCreateActivity)/模板卡(tplTap)
// 跳转入口加跳转中保护——连点只跳一次,complete 后可再跳;onShow 兜底清锁。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')

const PAGE = '../../subpackageA/pages/myproject/index.js'
let navs = []
let pageConfig = null

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: () => {}, getUserID: () => 1, getAuthorization: () => 'tk-1', getToken: () => 'tk-1',
  getRequestErrorMessage: (res, fallback) => fallback,
})
global.wx = {
  getStorageSync: () => undefined, setStorageSync: () => {}, removeStorageSync: () => {},
  showToast: () => {}, showLoading: () => {}, hideLoading: () => {}, showModal: () => {},
  navigateTo: (o) => { navs.push(o) },
  getSystemInfoSync: () => ({ windowWidth: 375 }), nextTick: (f) => f(),
  setNavigationBarColor: () => {}, setNavigationBarTitle: () => {},
}
global.Page = (cfg) => { pageConfig = cfg }

beforeEach(() => {
  navs = []
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
  assert.equal(fresh._navigating, false, 'onShow 兜底清锁')
}

const card = (item) => ({ currentTarget: { dataset: { item } } })

test('项目卡 草稿主题进编辑器连点只跳一次', () => {
  const page = makePage()
  const ev = card({ bizType: 'topic', state: 'draft', id: 5 })
  onceThenAgain(page, () => page.onCardTap(ev), '/pages/publish/fabu/index?id=5')
})

test('项目卡 待审核活动进编辑器连点只跳一次', () => {
  const page = makePage()
  const ev = card({ bizType: 'activity', state: 'pending', _canEdit: true, id: 6 })
  onceThenAgain(page, () => page.onCardTap(ev), '/pages/publish/activity/index?id=6')
})

test('项目卡 已发布主题进项目主页连点只跳一次', () => {
  const page = makePage()
  const ev = card({ bizType: 'topic', state: 'online', id: 7 })
  onceThenAgain(page, () => page.onCardTap(ev), '/pages/topic/merchantinfo/merchantinfo?topicId=7')
})

test('「发布单场活动」连点只跳一次', () => {
  const page = makePage()
  onceThenAgain(page, () => page.goCreateActivity(), '/pages/publish/activity/index')
})

test('模板卡连点只跳一次', () => {
  const page = makePage()
  const ev = { currentTarget: { dataset: { id: 8 } } }
  onceThenAgain(page, () => page.tplTap(ev), '/pages/templatedetail/templatedetail?id=8&scope=my')
})
