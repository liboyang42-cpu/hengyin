'use strict'
// BL-239:聊天页(onRouteTap/onCardTap/onCardBtn)与会话列表(goPredictInbox/openChat)
// 跳转入口加跳转中保护——连点只跳一次,complete 后可再跳;onShow 兜底清锁。
const { test } = require('node:test')
const assert = require('node:assert/strict')

function load(rel) {
  let def
  const navs = []
  global.getApp = () => ({
    globalData: {}, sendRequest: () => {}, tips() {},
    getUserID: () => 1, getRequestErrorMessage: (r, f) => f,
  })
  global.wx = { getStorageSync: () => '', navigateTo: o => navs.push(o) }
  global.Page = c => { def = c }
  const mod = require.resolve(rel)
  delete require.cache[mod]
  require(mod)
  const page = Object.assign({}, def)
  page.data = JSON.parse(JSON.stringify(def.data))
  page.setData = function (patch) { Object.assign(this.data, patch) }
  page.startPoll = () => {}
  page.refreshView = () => {}
  page.loadConversations = () => {}
  page.loadMerchantEntry = () => {}
  return { page, navs }
}

function onceThenAgain(page, navs, tap, url) {
  tap(); tap()
  assert.equal(navs.length, 1, '连点只跳一次')
  assert.equal(navs[0].url, url)
  navs[0].complete()
  tap()
  assert.equal(navs.length, 2, 'complete 后可再跳')
  tap()
  assert.equal(navs.length, 2, '未回调前仍锁住')
  page.onShow()
  tap()
  assert.equal(navs.length, 3, 'onShow 兜底清锁')
}

const CHAT = '../../subpackageB/pages/im/chat/index.js'
const LIST = '../../subpackageB/pages/im/list/index.js'

test('聊天页 路线卡连点只跳一次', () => {
  const { page, navs } = load(CHAT)
  page.data.topicCache = {}
  const ev = { currentTarget: { dataset: { topicid: 3 } } }
  onceThenAgain(page, navs, () => page.onRouteTap(ev), '/pages/topic/index/index?id=3')
})

test('聊天页 卡片「查看详情」连点只跳一次', () => {
  const { page, navs } = load(CHAT)
  page.data.msgs = []
  const ev = { currentTarget: { dataset: { url: '/pages/activity/detail/index?id=9' } } }
  onceThenAgain(page, navs, () => page.onCardTap(ev), '/pages/activity/detail/index?id=9')
})

test('聊天页 卡片可配置按钮连点只跳一次', () => {
  const { page, navs } = load(CHAT)
  const ev = { currentTarget: { dataset: { action: '/pages/coupon/index' } } }
  onceThenAgain(page, navs, () => page.onCardBtn(ev), '/pages/coupon/index')
})

test('会话列表 竞猜待公布入口连点只跳一次', () => {
  const { page, navs } = load(LIST)
  onceThenAgain(page, navs, () => page.goPredictInbox(), '/pages/merchant/predict/index')
})

test('会话列表 打开会话连点只跳一次', () => {
  const { page, navs } = load(LIST)
  const ev = { currentTarget: { dataset: { id: 12, name: '阿May', avatar: '', type: 1 } } }
  onceThenAgain(page, navs, () => page.openChat(ev),
    '/subpackageB/pages/im/chat/index?conversationId=12&name=' + encodeURIComponent('阿May') + '&avatar=&type=1')
})
