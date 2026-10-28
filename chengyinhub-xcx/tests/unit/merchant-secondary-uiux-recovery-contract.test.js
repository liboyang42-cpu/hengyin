'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
let pageConfig
let requests
let navigations
let toasts
let currentPagePath

global.getApp = () => ({
  ...(currentPagePath === 'subpackageA/pages/myproject/index.js' ? { getAuthorization: () => 'current-session' } : {}),
  globalData: { statusBarHeight: 44, navBarHeight: 44, menuButtonInfo: {} },
  getUserID: () => 7,
  getUserRole: () => 'merchant',
  getUserType: () => 2,
  setUserRole() {},
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  sendRequest: (request) => { requests.push(request) },
  tips(message) { toasts.push(message) },
})

global.wx = {
  getStorageSync: () => '',
  getSystemInfoSync: () => ({ statusBarHeight: 44, windowWidth: 375 }),
  getWindowInfo: () => ({ statusBarHeight: 44, windowWidth: 375 }),
  getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
  setNavigationBarColor() {},
  setBackgroundColor() {},
  hideTabBar() {},
  hideLoading() {},
  showLoading() {},
  showToast(options) { toasts.push(options.title) },
  navigateBack() {},
  navigateTo(options) { navigations.push(options.url) },
  redirectTo(options) { navigations.push(options.url) },
  switchTab(options) { navigations.push(options.url) },
  reLaunch(options) { navigations.push(options.url) },
  showModal() {},
}

global.Page = (config) => { pageConfig = config }

function loadPage(relativePath) {
  currentPagePath = relativePath
  pageConfig = null
  requests = []
  navigations = []
  toasts = []
  const absolute = path.join(ROOT, relativePath)
  delete require.cache[require.resolve(absolute)]
  require(absolute)
  const page = Object.assign({}, pageConfig)
  page.data = JSON.parse(JSON.stringify(pageConfig.data))
  page.setData = function (patch, done) {
    Object.entries(patch).forEach(([key, value]) => {
      const segments = key.split('.')
      let target = this.data
      for (let i = 0; i < segments.length - 1; i += 1) {
        target[segments[i]] = target[segments[i]] || {}
        target = target[segments[i]]
      }
      target[segments[segments.length - 1]] = value
    })
    if (done) done()
  }
  return page
}

function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
}

test('终价预览保留旧结果并只接受最新请求', () => {
  const page = loadPage('pages/topic/pricing/index.js')
  page.data.topicId = 8
  page.data.floor = 20
  page.data.floorText = '20.00'
  page.data.hasPreview = true
  page.preview()
  page.preview()

  assert.equal(page.data.floorText, '20.00', '静默刷新不得先清掉旧价格')
  assert.equal(requests.length, 2)
  requests[1].success({ code: 200, data: { priceMin: 30, cityReferencePrice: 40, cityReferenceSampleSize: 2 } })
  requests[1].complete()
  requests[0].success({ code: 200, data: { priceMin: 10 } })
  requests[0].complete()
  assert.equal(page.data.floorText, '30.00')
  assert.equal(page.data.loading, false)
})

test('终价确认失败保留可恢复的页内错误，且重复点击只发一次', () => {
  const page = loadPage('pages/topic/pricing/index.js')
  Object.assign(page.data, { topicId: 8, canConfirmPrice: true, finalPrice: 45 })
  page.confirmPrice()
  page.confirmPrice()
  assert.equal(requests.length, 1)
  assert.equal(page.data.saving, true)
  requests[0].fail({ msg: '网络断开' })
  requests[0].complete()
  assert.match(page.data.submitError, /网络/)
  assert.equal(page.data.saving, false)
})

test('我的项目刷新失败保留最后一次成功列表并显示局部恢复入口', () => {
  const page = loadPage('subpackageA/pages/myproject/index.js')
  page._seq = 1
  page._all = [{ id: 1, bizType: 'topic', ownerType: 'merchant', state: 'running' }]
  Object.assign(page.data, { loaded: true, projectState: 'ready', list: [{ id: 1 }] })
  page.loadProjects()
  requests[0].fail({ msg: '网络断开' })
  requests[0].complete()
  assert.deepEqual(page.data.list, [{ id: 1 }])
  assert.equal(page.data.projectState, 'ready')
  assert.match(page.data.projectErrorMsg, /网络断开/)

  const wxml = read('subpackageA/pages/myproject/index.wxml')
  assert.doesNotMatch(wxml, /<cy-inline-error\b[^>]*projectErrorMsg/)
  assert.match(read('subpackageA/pages/myproject/index.json'), /"cy-inline-error"/)
})

// 2026-10-28:模板页货架移进 pages/templatedetail/shelf(业主定稿原型 v21),原来挂在
// pages/template 上的五条在新位置重钉;行为级用例见 template-shelf-redesign-20261028.test.js。
test('模板货架刷新失败保留旧货架，错误只在从没加载成功时整块显示', () => {
  const js = read('pages/templatedetail/shelf/index.js')
  assert.match(js, /\} else if \(!this\.data\.topicLoaded\) \{\s*this\.setData\(\{ topicError:/)
  assert.match(js, /if \(seq !== this\._gameSeq \|\| this\.data\.gameLoaded\) return;\s*this\.setData\(\{ gameError:/)
  assert.doesNotMatch(read('pages/templatedetail/shelf/index.wxml'), /<cy-inline-error\b/)
})

test('标签切换不发请求，因此不存在标签与货架错位的窗口', () => {
  const js = read('pages/templatedetail/shelf/index.js')
  const body = js.slice(js.indexOf('    pickTag(e) {'), js.indexOf('    onRailScroll(e) {'))
  assert.ok(body.length > 0)
  assert.doesNotMatch(body, /request\(|load(Topics|Games)\(/, '切标签不许再发网络请求')
})

test('模板货架回到页面时静默刷新两个 tab', () => {
  const js = read('pages/templatedetail/shelf/index.js')
  assert.match(js, /pageLifetimes: \{[\s\S]*?show\(\) \{\s*if \(this\.data\.topicLoaded\) this\.loadTopics\(\);\s*if \(this\.data\.gameLoaded\) this\.loadGames\(\);/)
})

test('模板货架不用首字、字符星星或 CSS 渐变冒充图形资产，缺图给明确失败态', () => {
  const js = read('pages/templatedetail/shelf/index.js')
  const wxml = read('pages/templatedetail/shelf/index.wxml')
  assert.doesNotMatch(js, /ICON_COLORS|_initial:|_c1:|_c2:/)
  assert.doesNotMatch(wxml, /\{\{\s*item\._initial\s*\}\}/)
  assert.doesNotMatch(wxml, /★★★★★/)
  assert.doesNotMatch(wxml, /linear-gradient/)
  assert.doesNotMatch(wxml, /hot-divider|class="hot-/)
  assert.match(wxml, /class="lc__img cv-err"[\s\S]*?封面暂不可用/)
})

test('模板货架加载、失败与重试走设计系统组件；复制失败后入口立即可再点', () => {
  const wxml = read('pages/templatedetail/shelf/index.wxml')
  assert.match(wxml, /<cy-skeleton wx:if="\{\{ !topicLoaded && !topicError \}\}"/)
  assert.match(wxml, /<cy-error wx:elif="\{\{ topicError && !topicLoaded \}\}"[^>]*bindretry="retry"/,
    '列表加载失败必须有可见重试入口')
  assert.doesNotMatch(wxml + read('pages/template/index.wxml'), /circleThemes/)
  const preview = read('pages/templatedetail/topic-preview/index.js')
  assert.match(preview, /fail\(\) \{\s*that\._using = false;/, '复制失败要放行下一次')
})

test('模板上下架重复点击只发一次', () => {
  // 2026-10-28:整包复制挪到主题预览页(topic-preview),失败放行见上一条。
  const project = loadPage('subpackageA/pages/myproject/index.js')
  const event = { currentTarget: { dataset: { id: 9, pub: 0 } } }
  project.tplToggle(event)
  project.tplToggle(event)
  assert.equal(requests.length, 1)
  // 广场状态独立于个人发布状态，按钮由广场投影及审核资格决定。
  assert.match(read('subpackageA/pages/myproject/index.wxml'),
    /<view wx:if="\{\{item\._canToggleMarket\}\}" class="mp-op mp-op-toggle" catchtap="tplToggle"/)

})

test('提现页的资金提交门闩随建单链路一起退役,只留客服弹窗', () => {
  // 2026-09-15 收款模型定稿 §3:平台不打款,本页「确认提现」不再建提现单。
  // 原来这两条钉的是「submitting 门闩要暴露给按钮 / 同步异常也要释放门闩」——
  // 门闩守的那次资金提交已不存在,留着就是守一个假事实。改成反向断言:
  // saveData 必须只弹平台客服微信,不得再出现门闩置位与建单/预检调用。
  const js = read('subpackageMember/tixian/tixian.js')
  const body = js.slice(js.indexOf('saveData: function()'), js.indexOf('getUserData: function()'))
  const stripped = body.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '')
  assert.match(stripped, /withdrawCs\.showWithdrawCsPopup\(\)/, '「确认提现」必须弹平台客服微信')
  assert.doesNotMatch(stripped, /submitBankWithdrawal|withdrawal\/create/, '不得再建提现单')
  assert.doesNotMatch(stripped, /_submitting\s*=\s*true|submitting:\s*true/, '资金门闩已随建单链路退役')
  // 2026-09-16:表单本身也已退役(页面只留客服入口)——门闩与输入控件一起从 wxml 撤除。
  assert.doesNotMatch(read('subpackageMember/tixian/tixian.wxml'), /canSubmit|placeholder-class="tx-placeholder"/,
    '银行卡表单已退役，页面不该再有提交门闩或银行卡输入控件')
})

test('设置页查询俱乐部失败不再假装用户未申请，并用真实图标替代字符箭头', () => {
  const page = loadPage('pages/shezhi/shezhi.js')
  page.closeScene = () => {}
  page.pickClub()
  requests[0].fail({ msg: '网络断开' })
  assert.deepEqual(navigations, [])
  assert.match(toasts[0], /加载失败|网络/)

  const wxml = read('pages/shezhi/shezhi.wxml')
  assert.doesNotMatch(wxml, />›</)
  assert.match(wxml, /<cy-icon[^>]*name="arrow-right"/)
  assert.match(read('pages/shezhi/shezhi.json'), /"cy-icon"/)
})
