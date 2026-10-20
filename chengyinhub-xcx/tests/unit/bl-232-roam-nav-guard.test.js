// BL-232:漫游页其余跳转入口连点只跳一次,complete 后可再跳,onShow 兜底清锁。
// 移植分支:master 漫游起始页没有 intro 卡 / 官方路线入口,对应用例不移植。
const { test } = require('node:test')
const assert = require('node:assert/strict')

const PAGE_MODULE = '../../pages/roam/index.js'
// 移植分支的 req() 走 community-post-api,模块加载时就绑定 getApp(),所以请求桩挂在同一个 app 上
const LOAD_APP = { globalData: { features: {} }, getUserID: () => '1', sendRequest: o => LOAD_APP.onRequest(o), onRequest() {} }

function loadPage() {
  let def
  const prev = { Page: global.Page, getApp: global.getApp, getCurrentPages: global.getCurrentPages, wx: global.wx }
  try {
    global.Page = c => { def = c }
    global.getApp = () => LOAD_APP
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
  ['runnerOpenProfile', p => { p.data.runnerView = { memberId: 7 } }],
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
  LOAD_APP.onRequest = o => { posts.push(o.url); o.success(reply) }
  const prevApp = global.getApp
  global.getApp = () => LOAD_APP
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
})
