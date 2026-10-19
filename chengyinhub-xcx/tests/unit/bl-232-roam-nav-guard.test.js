// BL-232:漫游页其余跳转入口连点只跳一次,complete 后可再跳,onShow 兜底清锁。
const { test } = require('node:test')
const assert = require('node:assert/strict')

const PAGE_MODULE = '../../pages/roam/index.js'

function loadPage() {
  let def
  const prev = { Page: global.Page, getApp: global.getApp, getCurrentPages: global.getCurrentPages, wx: global.wx }
  try {
    global.Page = c => { def = c }
    global.getApp = () => ({ globalData: { features: {} }, getUserID: () => '1' })
    global.getCurrentPages = () => []
    global.wx = { getStorageSync: () => undefined }
    delete require.cache[require.resolve(PAGE_MODULE)]
    require(PAGE_MODULE)
  } finally { Object.assign(global, prev) }
  const page = Object.assign({}, def, { data: JSON.parse(JSON.stringify(def.data)) })
  page.setData = function (patch, cb) { Object.assign(this.data, patch); if (cb) cb() }
  page.closeScene = () => {}
  return page
}

// 桩不回调 complete:模拟跳转还没落地时的第二次点击
function withWx(fn) {
  const calls = []
  const prevWx = global.wx
  global.wx = { navigateTo: o => calls.push(o), getStorageSync: () => undefined }
  try { fn(calls) } finally { global.wx = prevWx }
  return calls
}

const CASES = [
  ['openTopicPageFromView', p => { p.data.topicView = { topicId: 't1' } }],
  ['onCheckinStamp', p => { p.data.checkinView = { place: '外滩' } }],
  ['onCheckinSign', p => { p.data.checkinView = { place: '外滩' } }],
  ['onPassportTileTap', () => {}, { currentTarget: { dataset: { key: 'badge' } } }],
  ['onIntroCardTap', p => { p.data.introCards = [{ key: 'hangout' }] }, { currentTarget: { dataset: { idx: 0 } } }],
  ['onIntroCardTap', p => { p.data.introCards = [{ key: 'chapter', nav: '/pages/x/index' }] }, { currentTarget: { dataset: { idx: 0 } } }],
  ['runnerOpenProfile', p => { p.data.runnerView = { memberId: 7 } }],
  ['openOfficialRoutes', () => {}],
  ['goStart', p => { p.data.introCards = [{ key: 'hangout' }]; p.data.introCardIdx = 0 }],
  ['goStart', p => { p.data.introCards = [{ key: 'chapter', nav: '/pages/x/index' }]; p.data.introCardIdx = 0 }],
  ['openRoamPassport', () => {}],
  ['openBadge3d', p => { p.data.medal = { name: '外滩' } }],
  ['openEntryActivityDetail', p => { p.data.entryActivity = { id: 3 } }],
]

for (const [name, setup, evt] of CASES) {
  test(`BL-232 ${name}:连点只跳一次,complete 后可再跳`, () => {
    const page = loadPage()
    const calls = withWx(() => {
      setup(page); page[name](evt)
      setup(page); page[name](evt)
    })
    assert.equal(calls.length, 1, '跳转未落地时第二次点击不应再跳')
    assert.equal(typeof calls[0].complete, 'function')
    calls[0].complete()
    const again = withWx(() => { setup(page); page[name](evt) })
    assert.equal(again.length, 1, 'complete 之后应能再次跳转')
  })
}

test('BL-232 runnerSayHi:开会话在途与跳转中不重复开,开不起来时放开', async () => {
  const page = loadPage()
  page.data.runnerView = { memberId: 7, nickname: 'A' }
  const posts = []
  let reply = { code: '200', data: { conversationId: 'c1' } }
  const app = { globalData: { features: {} }, sendRequest: o => { posts.push(o.url); o.success(reply) } }
  const prevApp = global.getApp
  global.getApp = () => app
  const settle = () => new Promise(r => setImmediate(r))
  try {
    const calls = []
    const prevWx = global.wx
    global.wx = { navigateTo: o => calls.push(o), showToast() {}, getStorageSync: () => undefined }
    try {
      page.runnerSayHi(); page.runnerSayHi()
      await settle()
      assert.equal(posts.length, 1)
      assert.equal(calls.length, 1)
      calls[0].complete()
      reply = { code: '500', msg: '不行' }
      page.runnerSayHi()
      await settle()
      assert.equal(page._navigating, false, '开不起来会话时要放开锁')
      page.runnerSayHi()
      await settle()
      assert.equal(posts.length, 3)
    } finally { global.wx = prevWx }
  } finally { global.getApp = prevApp }
})

test('BL-232 onShow 兜底清锁,不挪动 onShow 开头已钉的语句', () => {
  const src = require('node:fs').readFileSync(require('node:path').join(__dirname, PAGE_MODULE), 'utf8')
  const body = src.slice(src.indexOf('  onShow() {'), src.indexOf('  onShow() {') + 600)
  assert.ok(body.indexOf('this._navigating = false') > body.indexOf('readPageStyle()'))
  assert.match(src, /goStart\(\) \{[\s\S]*?wx\.navigateTo\(\{ url: '\/subpackageRoam\/nearby\/index', complete/)
})
