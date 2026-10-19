const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-243:广场列表/帖子详情经 cy-post-card 组件事件或头像浮层进入的跳转,连点只跳一次;
// complete 回调或 onShow 后可再跳;各入口共用一个跳转中标记;缺 id/缺 dataId 时不跳也不上锁

const ROOT = path.resolve(__dirname, '../..')

function load(file) {
  let definition
  const navs = []
  const base = { navigateTo: (o) => navs.push(o), getStorageSync: () => '', getSystemInfoSync: () => ({ statusBarHeight: 20, windowWidth: 375 }) }
  const wx = new Proxy(base, { get(t, k) { return k in t ? t[k] : () => {} } })
  const app = new Proxy({ globalData: {}, sendRequest() { return { abort() {} } } }, {
    get(t, k) { return k in t ? t[k] : () => '' },
  })
  global.getApp = () => app
  global.wx = wx
  const dir = path.dirname(path.join(ROOT, file))
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, file), 'utf8'), {
    getApp: () => app,
    getCurrentPages: () => [{}, {}],
    Page(page) { definition = page },
    Component(c) { definition = Object.assign({}, c.methods, { data: c.data || {} }) },
    Behavior: (o) => o,
    require(request) { return require(path.resolve(dir, request)) },
    Set, Map, Date, JSON, Math, Number, String, Object, Array, Promise, wx, setTimeout, clearTimeout, console,
  }, { filename: path.join(ROOT, file) })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data || {})),
    setData(patch) { Object.assign(this.data, patch) },
  })
  return { page, navs }
}

const LIST = 'pages/square/list/index.js'
const DETAIL = 'pages/square/detail/index.js'
const ROW = { id: 31, memberId: 8, dataId: 6, dataType: 1, sportTopicId: 12 }
const listPrep = (page) => { page.data.list = [Object.assign({}, ROW)] }
const detailPrep = (page) => { page.data.info = { id: 31, memberId: 8, dataId: 6, dataType: 2, sportTopicId: 12 } }
const ev = { detail: { index: 0 } }

const CASES = [
  { name: '列表 动态卡 bind:detail', file: LIST, prep: listPrep, url: '/pages/square/detail/index?id=31', tap: (p) => p.jumpDetail(ev) },
  { name: '列表 动态卡 bind:comment', file: LIST, prep: listPrep, url: '/pages/square/detail/index?comment=1&id=31', tap: (p) => p.jumpDetail2(ev) },
  { name: '列表 玩法卡 看详情', file: LIST, prep: listPrep, url: '/pages/topic/index/index?id=12', tap: (p) => p.goPlayTopicDetail(ev) },
  { name: '列表 玩法卡 开始玩', file: LIST, prep: listPrep, url: '/pages/play/index?activityId=6', tap: (p) => p.goPlay(ev) },
  { name: '列表 头像无锚点', file: LIST, prep: listPrep, url: '/pages/userinfo/userinfo?userId=8', tap: (p) => p.onAvatarTap({ detail: { memberId: 8 } }) },
  { name: '列表 头像浮层 访问主页', file: LIST, prep: (p) => { listPrep(p); p._avatarPop = { memberId: 8, index: 0 } }, url: '/pages/userinfo/userinfo?userId=8', tap: (p) => p.onAvatarPopPick({ detail: { key: 'profile' } }) },
  { name: '详情 看详情', file: DETAIL, prep: detailPrep, url: '/pages/topic/index/index?id=12', tap: (p) => p.goSportTopic() },
  { name: '详情 开始玩', file: DETAIL, prep: detailPrep, url: '/pages/play/index?topicId=6', tap: (p) => p.goPlay() },
  { name: '详情 头像浮层 访问主页', file: DETAIL, prep: detailPrep, url: '/pages/userinfo/userinfo?userId=8', tap: (p) => p.onAvatarPopPick({ detail: { key: 'profile' } }) },
]

for (const c of CASES) {
  const mount = () => { const r = load(c.file); c.prep(r.page); return r }

  test(`BL-243:${c.name} complete 未回调时连点两次只跳 1 次`, () => {
    const { page, navs } = mount()
    c.tap(page)
    c.tap(page)
    assert.equal(navs.length, 1)
    assert.equal(navs[0].url, c.url)
  })

  test(`BL-243:${c.name} complete 回调后可再跳`, () => {
    const { page, navs } = mount()
    c.tap(page)
    navs[0].complete()
    c.tap(page)
    assert.equal(navs.length, 2)
  })

  test(`BL-243:${c.name} onShow 兜底清锁后可再跳`, () => {
    const { page, navs } = mount()
    c.tap(page)
    try { page.onShow() } catch (_) { /* 兜底清锁写在 onShow 首行,后续刷新与本卡无关 */ }
    c.tap(page)
    assert.equal(navs.length, 2)
  })
}

test('BL-243:列表跨入口共用标记,看详情在途时点开始玩/动态卡/头像都不跳', () => {
  const { page, navs } = load(LIST)
  listPrep(page)
  page.goPlayTopicDetail(ev)
  page.goPlay(ev); page.jumpDetail(ev); page.jumpDetail2(ev); page.goUserInfo(8)
  assert.equal(navs.length, 1)
})

test('BL-243:详情跨入口共用标记', () => {
  const { page, navs } = load(DETAIL)
  detailPrep(page)
  page.goPlay()
  page.goSportTopic(); page.goUserInfo()
  assert.equal(navs.length, 1)
})

test('BL-243:列表缺行/缺 id/缺 dataId 时不跳也不上锁', () => {
  const { page, navs } = load(LIST)
  page.data.list = [{ memberId: 8 }]
  page.jumpDetail({ detail: { index: 3 } })
  page.jumpDetail2({ detail: { index: 3 } })
  page.jumpDetail(ev)
  page.goPlay(ev)
  page.goUserInfo(undefined)
  assert.equal(navs.length, 0)
  page.data.list = [Object.assign({}, ROW, { sportTopicId: 0 })]
  page.goPlayTopicDetail(ev) // 无 sportTopicId 转 jumpDetail,由 jumpDetail 自己判锁
  page.goPlayTopicDetail(ev)
  assert.deepEqual(navs.map((n) => n.url), ['/pages/square/detail/index?id=31'])
})

test('BL-243:详情缺 memberId/sportTopicId/dataId 时不跳也不上锁', () => {
  const { page, navs } = load(DETAIL)
  page.data.info = { id: 31 }
  page.goUserInfo(); page.goSportTopic(); page.goPlay()
  assert.equal(navs.length, 0)
  detailPrep(page)
  page.goPlay()
  assert.equal(navs.length, 1)
})

test('BL-243:头像跳转失败仍提示,complete 清锁', () => {
  const { page, navs } = load(DETAIL)
  detailPrep(page)
  page.goUserInfo()
  assert.equal(typeof navs[0].fail, 'function')
  navs[0].complete()
  page.goUserInfo()
  assert.equal(navs.length, 2)
})
