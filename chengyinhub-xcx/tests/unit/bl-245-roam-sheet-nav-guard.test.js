// BL-245:漫游页经半屏组件(官方活动/集邮相机/投一张换一张)与「申请加入」进入的跳转连点只跳一次,
// complete 后可再跳,跨入口共用 this._navigating;缺 id 不跳也不上锁。
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
  page.closed = 0
  page.closeScene = function () { this.closed += 1 }
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
  ['openOfficialEvent', () => {}, { detail: { id: 42 } }, '/pages/activity/official-detail/index?id=42'],
  ['openStampCamera', () => {}, undefined, '/subpackageP3/pages/stamp-camera/index/index'],
  ['openCityStamp', () => {}, { detail: { place: '外滩' } }, '/subpackageRoam/citystamp/index?kind=sign&place=' + encodeURIComponent('外滩')],
  ['openEntryTeamAction', p => { p.data.entryTeam = { teamId: 5, canApply: true } }, undefined, '/subpackageRoam/nearby/index?teamId=5'],
]

for (const [name, setup, evt, url] of CASES) {
  test(`BL-245 ${name}:连点只跳一次,complete 后可再跳`, () => {
    const page = loadPage()
    const calls = withWx(() => {
      setup(page); page[name](evt)
      setup(page); page[name](evt)
    })
    assert.equal(calls.length, 1, '跳转未落地时第二次点击不应再跳')
    assert.equal(calls[0].url, url)
    calls[0].complete()
    const again = withWx(() => { setup(page); page[name](evt) })
    assert.equal(again.length, 1, 'complete 之后应能再次跳转')
  })
}

test('BL-245 相机/投一张换一张:跳转中再点不再收半屏', () => {
  const page = loadPage()
  withWx(() => { page.openStampCamera(); page.openStampCamera(); page.openCityStamp({ detail: {} }) })
  assert.equal(page.closed, 1)
})

test('BL-245 跨入口共用标记,onShow 兜底清锁后可再跳', () => {
  const page = loadPage()
  page._entryTeams = [{ teamId: 5, latitude: 31.2, longitude: 121.5 }]
  const calls = withWx(() => {
    page._openEntryTeamById(5)
    page.openOfficialEvent({ detail: { id: 1 } })
    page.openStampCamera()
  })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, '/subpackageRoam/nearby/index?teamId=5&lat=31.2&lng=121.5&radius=3000')
  page._navigating = false // onShow 第 1007 行的兜底清锁(BL-232 已钉位置)
  const again = withWx(() => { page.openOfficialEvent({ detail: { id: 1 } }) })
  assert.equal(again.length, 1)
})

test('BL-245 缺 id 不跳也不上锁', () => {
  const page = loadPage()
  const calls = withWx(() => {
    page.openOfficialEvent({ detail: {} })
    page.openOfficialEvent()
    page._openEntryTeamById('')
  })
  assert.equal(calls.length, 0)
  assert.ok(!page._navigating)
  const again = withWx(() => { page.openOfficialEvent({ detail: { id: 9 } }) })
  assert.equal(again.length, 1)
})

test('BL-245 onShow 兜底清锁仍在', () => {
  const src = require('node:fs').readFileSync(require('node:path').join(__dirname, PAGE_MODULE), 'utf8')
  const body = src.slice(src.indexOf('  onShow() {'), src.indexOf('  onShow() {') + 600)
  assert.match(body, /this\._navigating = false/)
})
