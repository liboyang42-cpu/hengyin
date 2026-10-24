'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')

// BL-251:主题章节详情「去结算」(底部主键 ended 态)与「团码」(快捷入口 groupcode)连点只跳一次;
// 与 goEditTopic 共用标记;complete 回调或 onShow 后可再跳;缺 q 时不跳也不上锁

const ROOT = path.resolve(__dirname, '../..')
const PAGE = 'pages/club/topic-detail/index.js'

function loadPage() {
  const navs = []
  let definition = null
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    sendRequest() {},
    getUserID: () => 9008,
    getUserRole: () => 'CLUB',
    getUserType: () => 1,
  }
  const base = {
    navigateTo: (o) => navs.push(o),
    getStorageSync: () => '',
    getWindowInfo: () => ({ windowWidth: 375, windowHeight: 667 }),
    getSystemInfoSync: () => ({}),
  }
  const wx = new Proxy(base, { get(t, k) { return k in t ? t[k] : () => {} } })
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, PAGE), 'utf8'), {
    getApp: () => app,
    Page: (config) => { definition = config },
    wx,
    console,
    Date,
    setTimeout,
    clearTimeout,
    getCurrentPages: () => [],
    require: (id) => require(path.resolve(ROOT, path.dirname(PAGE), id)),
  }, { filename: PAGE })
  const page = Object.assign({}, definition, {
    data: Object.assign({}, definition.data),
    setData(patch) { Object.assign(this.data, patch) },
  })
  page._topicId = 88
  page._clubId = 9
  page.data.canReviewChapterApplications = true
  page.data.primary = { disabled: false, text: '去结算' }
  page.data.statusKey = 'ended'
  return { page, navs }
}

const tap = (dataset) => ({ currentTarget: { dataset } })

test('BL-251:底部主键「去结算」连点只跳 1 次,complete 后可再跳', () => {
  const { page, navs } = loadPage()
  page.onPrimary()
  page.onPrimary()
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, '/pages/club/settlement/index?clubId=9')
  navs[0].complete()
  page.onPrimary()
  assert.equal(navs.length, 2)
})

test('BL-251:快捷入口「团码」连点只跳 1 次,complete 后可再跳', () => {
  const { page, navs } = loadPage()
  page.onQuickAction(tap({ key: 'groupcode' }))
  page.onQuickAction(tap({ key: 'groupcode' }))
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, '/pages/club/group-code/index?topicId=88')
  navs[0].complete()
  page._directorActivityId = 501
  page.onQuickAction(tap({ key: 'groupcode' }))
  assert.equal(navs.length, 2)
  assert.equal(navs[1].url, '/pages/club/group-code/index?activityId=501')
})

test('BL-251:与 goEditTopic 共用一个标记,onShow 兜底清锁后可再跳', () => {
  const { page, navs } = loadPage()
  page.goEditTopic()
  page.onPrimary()
  page.onQuickAction(tap({ key: 'groupcode' }))
  assert.equal(navs.length, 1)
  page.onShow()
  page.onPrimary()
  assert.equal(navs.length, 2)
  page.goGroupCode()
  assert.equal(navs.length, 2)
  page.onShow()
  page.goGroupCode()
  assert.equal(navs.length, 3)
})

test('BL-251:团码缺 q 时照旧提示、不跳也不上锁', () => {
  const { page, navs } = loadPage()
  const tips = []
  page.notReady = (label) => tips.push(label)
  page._topicId = null
  page._directorActivityId = null
  page.goGroupCode()
  assert.equal(navs.length, 0)
  assert.deepEqual(tips, ['出示团码'])
  page._topicId = 88
  page.goGroupCode()
  assert.equal(navs.length, 1)
})
