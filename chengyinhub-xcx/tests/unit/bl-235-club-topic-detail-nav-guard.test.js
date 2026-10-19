'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')

// BL-235:主题章节详情 6 处跳转入口连点只跳一次;complete 回调或 onShow 后可再跳;缺参数时不跳也不上锁

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
  page.data.sessionPickRows = [{ id: 501 }]
  return { page, navs }
}

const tap = (dataset) => ({ currentTarget: { dataset } })

const ENTRIES = [
  ['goStory', () => [], '/pages/club/topic-story/index?topicId=88&clubId=9'],
  ['goEventOps', () => [], '/pages/club/event-ops/index?clubId=9&topicId=88'],
  ['goLedger', () => [], '/pages/club/enroll/index?clubId=9&topicId=88'],
  ['goEditTopic', () => [], '/pages/publish/fabu/index?id=88'],
  ['goChapterApplications', () => [], '/pages/topic/merchantinfo/merchantinfo?topicId=88&scope=CLUB'],
]

for (const [name, args, url] of ENTRIES) {
  test(`BL-235:${name} 连点只跳 1 次,complete 后可再跳`, () => {
    const { page, navs } = loadPage()
    page[name](...args())
    page[name](...args())
    assert.equal(navs.length, 1)
    assert.equal(navs[0].url, url)
    navs[0].complete()
    page[name](...args())
    assert.equal(navs.length, 2)
  })
}

test('BL-235:选场半屏选中后进活动运营,连点只跳 1 次', () => {
  const { page, navs } = loadPage()
  page.onSessionPick(tap({ id: 501 }))
  page.data.sessionPickRows = [{ id: 501 }]
  page.onSessionPick(tap({ id: 501 }))
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, '/pages/club/event-ops/index?clubId=9&topicId=88&activityId=501')
  navs[0].complete()
  page.data.sessionPickRows = [{ id: 501 }]
  page.onSessionPick(tap({ id: 501 }))
  assert.equal(navs.length, 2)
})

test('BL-235:不同入口共用一个标记,onShow 兜底清锁后可再跳(首展也清)', () => {
  const { page, navs } = loadPage()
  page.goStory()
  page.goLedger()
  assert.equal(navs.length, 1)
  page.onShow()
  page.goLedger()
  assert.equal(navs.length, 2)
  page.data.loadError = true
  page.onShow()
  page.goEditTopic()
  assert.equal(navs.length, 3)
})

test('BL-235:缺参数或无权限时不跳也不上锁', () => {
  const { page, navs } = loadPage()
  page.notReady = () => {} // 缺参提示走 toast,这里只看跳转
  page.data.canReviewChapterApplications = false
  page.goChapterApplications()
  page._clubId = null
  page.goEventOps()
  page.goLedger()
  page.onSessionPick(tap({ id: 501 }))
  page._topicId = null
  page.goStory()
  assert.equal(navs.length, 0)
  page._topicId = 88
  page.goStory()
  assert.equal(navs.length, 1)
})
