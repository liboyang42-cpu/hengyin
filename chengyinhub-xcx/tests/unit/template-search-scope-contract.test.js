'use strict'

// CU-M-64 模板页搜索入口只搜模板。
//   病灶:入口写着「搜索节点玩法与主题」,点了却跳 /pages/search2 —— 那里搜的是主题/活动/
//         俱乐部/商家四类,没有「节点玩法」这一类;按承诺搜的人走错范围且搜不到。
//   修法:入口改成页内搜模板 —— 玩法模板走服务端 keyword(/api/template/list 早有该参数),
//         主题模板列表接口没有 keyword,按数据量在客户端按名称硬筛。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const PAGE_JS = path.join(ROOT, 'pages/template/index.js')
const TOPIC_URL = '/api/template/topic-template/list'
const GAME_URL = '/api/template/list'

function mountTemplatePage() {
  const requests = []
  let definition
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44, menuButtonInfo: {} },
    getUserRole: () => 'player',
    getUserType: () => 0,
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    sendRequest(options) { requests.push(options) },
  }
  global.getApp = () => app
  global.wx = {
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    showToast() {},
    navigateTo() {},
    showModal(options) { options.success({ confirm: false }) },
  }
  global.Page = (config) => { definition = config }
  delete require.cache[require.resolve(PAGE_JS)]
  require(PAGE_JS)
  const page = Object.assign({}, definition)
  page.data = JSON.parse(JSON.stringify(definition.data))
  page.setData = function (patch) { Object.assign(this.data, patch) }
  return { page, requests }
}

function lastRequest(h, url) {
  return h.requests.filter((r) => r.url === url).at(-1)
}

const TWO_TOPICS = [
  { id: 11, name: '主题A', templateStatus: 'VERIFIED' },
  { id: 12, name: '主题B', templateStatus: 'VERIFIED' },
]

// 2026-10-22 owner 裁决推翻了 CU-M-64 的「入口只搜模板、页内弹窗」:入口改为打开统一搜索
// (pages/search2),模板(主题模板/玩法模板)作为结果类型出现在那里;页内 startTemplateSearch 保留。
test('2026-10-22 入口打开统一搜索页,不再弹「搜索模板」输入框', () => {
  const h = mountTemplatePage()
  const navs = []
  const modals = []
  global.wx.navigateTo = (options) => navs.push(options.url)
  global.wx.showModal = (options) => modals.push(options)
  h.page.goSearch()
  assert.deepEqual(navs, ['/pages/search2/index'])
  assert.equal(modals.length, 0)
  const wxml = read('pages/template/index.wxml')
  assert.match(wxml, /bindtap="goSearch"/)
  assert.match(wxml, /搜索主题与玩法模板/)
  assert.doesNotMatch(wxml, /cta="刷新试试"/, '空态不再放「刷新试试」')
})

test('CU-M-64 玩法模板:关键词随请求发给服务端,回来即屏上所见', () => {
  const h = mountTemplatePage()
  h.page.data.tab = 'game'
  h.page.startTemplateSearch('暗号')
  assert.equal(h.page.data.searchKeyword, '暗号')

  const game = lastRequest(h, GAME_URL)
  assert.ok(game, '搜索必须重取玩法模板')
  assert.equal(game.data.keyword, '暗号')
  game.success({ code: '200', data: { rows: [{ id: 1, title: '今晚的暗号', players: '2人' }] } })
  assert.equal(h.page.data.banner._title, '今晚的暗号')
})

test('CU-M-64 主题模板:接口没有 keyword,按名称客户端硬筛,清掉搜索即恢复全量', () => {
  const h = mountTemplatePage()
  h.page.data.tab = 'topic'
  h.page.getTopicTemplates()
  lastRequest(h, TOPIC_URL).success({ code: '200', data: TWO_TOPICS })
  assert.equal(h.page.data.banner._title, '主题A')
  assert.equal(h.page.data.topList.length, 1)

  h.page.startTemplateSearch('主题B')
  const topic = lastRequest(h, TOPIC_URL)
  assert.equal(topic.data.keyword, undefined, '主题模板列表接口没有 keyword,不许装样子发过去')
  topic.success({ code: '200', data: TWO_TOPICS })
  assert.equal(h.page.data.banner._title, '主题B', '搜索是硬筛:没命中的卡不得留在屏上')
  assert.deepEqual(h.page.data.topList, [])
  assert.match(h.page.data.topTitle, /搜索「主题B」/)

  h.page.clearTemplateSearch()
  assert.equal(h.page.data.searchKeyword, '')
  lastRequest(h, TOPIC_URL).success({ code: '200', data: TWO_TOPICS })
  assert.equal(h.page.data.banner._title, '主题A', '清掉搜索要回到全量,不能被上次的过滤结果粘住')
  assert.equal(h.page.data.topList.length, 1)
})

test('CU-M-64 搜不到时不冒充「这个品类还没有玩法」,给的是搜索空态', () => {
  const h = mountTemplatePage()
  h.page.data.tab = 'topic'
  h.page.getTopicTemplates()
  lastRequest(h, TOPIC_URL).success({ code: '200', data: TWO_TOPICS })

  h.page.startTemplateSearch('不存在的模板')
  lastRequest(h, TOPIC_URL).success({ code: '200', data: TWO_TOPICS })
  assert.equal(h.page.data.topList.length, 0)
  assert.equal(h.page.data.emptyTitle, '没有找到相关模板')

  const wxml = read('pages/template/index.wxml')
  assert.match(wxml, /searchKeyword[\s\S]*?clearTemplateSearch/, '屏上必须能看到在搜什么、能清掉')
})
