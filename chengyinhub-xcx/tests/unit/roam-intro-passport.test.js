const { test } = require('node:test')
const assert = require('node:assert/strict')

const PAGE_MODULE = '../../pages/roam/index.js'

function setByPath(target, key, value) {
  const parts = key.split('.')
  let cursor = target
  parts.slice(0, -1).forEach(part => {
    cursor[part] = cursor[part] || {}
    cursor = cursor[part]
  })
  cursor[parts.at(-1)] = value
}

function loadRoamPage(sessions, options) {
  let definition
  const config = options || {}
  const storage = config.storage || { 'roam_memory_v1:101:sessions': sessions }
  const wxMock = { getStorageSync: key => storage[key] }
  // 切到护照 tab 时页面还会读可参与的漫游官方活动(BL-41,失败静默);夹具只记录不回包
  const summary = config.summary || { trips: sessions.length, shops: sessions.reduce((n, r) => n + (r.shops || 0), 0),
    distanceM: sessions.reduce((n, r) => n + Number(r.distance || 0) * 1000, 0), days: new Set(sessions.map(r => r.date).filter(Boolean)).size }
  const appMock = { globalData: { features: {} }, getUserID: () => config.playerId || '101',
    sendRequest(request) { if (request.url === '/api/roam/passport/summary') request.success({ code: 200, data: summary }) } }
  const previous = { Page: global.Page, getApp: global.getApp, getCurrentPages: global.getCurrentPages, wx: global.wx }
  try {
    global.Page = config => { definition = config }
    global.getApp = () => appMock
    global.getCurrentPages = () => []
    global.wx = wxMock
    delete require.cache[require.resolve(PAGE_MODULE)]
    require(PAGE_MODULE)
  } finally {
    global.Page = previous.Page
    global.getApp = previous.getApp
    global.getCurrentPages = previous.getCurrentPages
    global.wx = previous.wx
  }

  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) })
  page.setData = function (patch) {
    Object.entries(patch).forEach(([key, value]) => setByPath(this.data, key, value))
  }
  return { page, wxMock, appMock }
}

// 2026-08-08 用户裁决改版:护照 tab 分两层 —— 顶部四项读数、上排方卡=我的档案
// (漫游记录/集邮册/勋章墙)、下排大卡=去玩什么(官方活动/发现店铺)。
// 旧的「旅程卡截断 12 张 + passport 瓦片」随「探索护照」场景一起下线,契约跟着改。
test('起始页护照:读数与档案卡都统计全部历史,不跟着任何截断走', async () => {
  const sessions = Array.from({ length: 13 }, (_, index) => ({
    ts: index + 1,
    date: `08-${String((index % 28) + 1).padStart(2, '0')}`,
    distance: '1.0',
    shops: 1,
    photos: [{ path: `${index}.jpg` }],
  }))
  const { page, wxMock, appMock } = loadRoamPage(sessions)
  const previousWx = global.wx
  const previousGetApp = global.getApp
  try {
    global.wx = wxMock
    global.getApp = () => appMock
    await page._loadIntroPassport()
  } finally {
    global.wx = previousWx
    global.getApp = previousGetApp
  }

  const stat = key => page.data.passport.stats.find(item => item.k === key).v
  assert.equal(stat('漫游'), 13, '漫游次数统计全部历史')
  assert.equal(stat('探店'), 13, '探店数统计全部历史')
  assert.equal(stat('公里'), '13.0', '里程统计全部历史')

  const tile = key => page.data.passport.tiles.find(item => item.key === key)
  assert.equal(tile('history').value, '13 次已完成旅程')
  assert.equal(tile('stamp').value, '13 张本机邮票')
  assert.ok(tile('badge'), '勋章墙常驻')
  // 2026-10-14 用户确认的漫游原型:探索目标、时光胶囊随漫游上线,接在三张档案卡之后
  assert.deepEqual(page.data.passport.tiles.map(item => item.key), ['history', 'stamp', 'badge', 'goals', 'capsule'], '上排:记录/集邮/勋章 + 探索目标/时光胶囊')
  assert.ok(page.data.passport.tiles.filter(item => item.isNew).every(item => !/\d/.test(item.value)), '新方卡不摆未读取的数字')

  const plays = page.data.passport.plays.map(item => item.key)
  assert.deepEqual(plays, ['event', 'discover'], '下排大卡只放官方活动与发现店铺')
})

test('negative control: 读数改回跟着旅程卡截断走必须判红', async () => {
  const sessions = Array.from({ length: 13 }, (_, index) => ({ ts: index + 1, date: '08-01', distance: '1.0', shops: 1, photos: [{ path: `${index}.jpg` }] }))
  const { page, wxMock, appMock } = loadRoamPage(sessions)
  const previousWx = global.wx
  const previousGetApp = global.getApp
  try {
    global.wx = wxMock
    global.getApp = () => appMock
    await page._loadIntroPassport()
  } finally {
    global.wx = previousWx
    global.getApp = previousGetApp
  }
  const shops = page.data.passport.stats.find(item => item.k === '探店').v
  assert.notEqual(shops, 12, '一旦读数被 slice(0,12) 之类截断，这里会变 12 并判红')
})

test('护照只统计当前玩家的漫游记录，不认领旧共享缓存或其他玩家记录', async () => {
  const ownSessions = [{ ts: 20201, distance: '2.0', shops: 2, photos: [] }]
  const { page, wxMock, appMock } = loadRoamPage([], {
    playerId: '202', summary: { trips: 1, shops: 2, distanceM: 2000, days: 1 },
    storage: {
      roam_sessions: Array.from({ length: 9 }, (_, index) => ({ ts: index + 1, shops: 9 })),
      'roam_memory_v1:101:sessions': [{ ts: 10101, distance: '1.0', shops: 1 }],
      'roam_memory_v1:202:sessions': ownSessions,
    },
  })
  const previousWx = global.wx
  const previousGetApp = global.getApp
  try {
    global.wx = wxMock
    global.getApp = () => appMock
    await page._loadIntroPassport()
  } finally {
    global.wx = previousWx
    global.getApp = previousGetApp
  }

  const stat = key => page.data.passport.stats.find(item => item.k === key).v
  assert.equal(stat('漫游'), 1)
  assert.equal(stat('探店'), 2)
  assert.equal(stat('公里'), '2.0')
})

test('三个首页卡分别开始漫游、打开地图和直接打开我的队伍', () => {
  const { page, wxMock } = loadRoamPage([])
  const navigations = []
  wxMock.navigateTo = options => { navigations.push(options.url); if (options.complete) options.complete() } // 入口有跳转中保护,桩回调 complete 才放下一跳
  let starts = 0
  page.goStart = () => { starts += 1 }
  const oldWx = global.wx
  try {
    global.wx = wxMock
    page.goStart()
    page.openNearbyMap()
    page.openMyTeams()
    assert.equal(starts, 1)
    // 我的队伍改为独立页面(带队/已通过/审核中/未通过 + 附近招募)
    assert.deepEqual(navigations, ['/subpackageRoam/nearby/index', '/subpackageRoam/my-teams/index'])
  } finally { global.wx = oldWx }
})

test('空护照显示真实零值，切换菜单会刷新当前玩家记录', async () => {
  const { page, wxMock, appMock } = loadRoamPage([])
  const oldWx = global.wx
  const oldApp = global.getApp
  try {
    global.wx = wxMock
    global.getApp = () => appMock
    page.switchIntroTab({ detail: { key: 'passport' } })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(page.data.introTab, 'passport')
    assert.deepEqual(page.data.passport.stats.map(stat => stat.v), [0, 0, 0, '0.0'])
    page.switchIntroTab({ detail: { key: 'start' } })
    assert.equal(page.data.introTab, 'start')
  } finally { global.wx = oldWx; global.getApp = oldApp }
})

test('passport server failure is retryable; unavailable local photos never replace server facts with zero', async () => {
  const { page, wxMock, appMock } = loadRoamPage([{ ts: Date.now(), distance: 2, shops: 1 }])
  const old = { wx: global.wx, getApp: global.getApp }
  global.wx = wxMock; global.getApp = () => appMock
  try {
    await page._loadIntroPassport()
    assert.equal(page.data.passport.stats[0].v, 1)
    const send = appMock.sendRequest
    appMock.sendRequest = request => request.fail()
    await page._loadIntroPassport()
    assert.equal(page.data.passportError, true)
    assert.deepEqual(page.data.passport.stats, [])
    appMock.sendRequest = send
    wxMock.getStorageSync = () => { throw Error('unavailable') }
    await page.retryPassport()
    assert.equal(page.data.passportError, false)
    assert.equal(page.data.passport.stats[0].v, 1)
    assert.equal(page.data.passport.tiles.find(row => row.key === 'stamp').value, '本机邮票暂不可读')
    appMock.getUserID = () => '202'
    appMock.sendRequest = request => request.fail()
    await page._loadIntroPassport()
    assert.equal(page.data.passportError, true)
    assert.deepEqual(page.data.passport.stats, [])
  } finally { Object.assign(global, old) }
})

test('passport retry is rendered and stale event read cannot repopulate another account', async () => {
  const { page, wxMock, appMock } = loadRoamPage([])
  const old = { wx: global.wx, getApp: global.getApp }
  const requests = []
  appMock.sendRequest = r => requests.push(r)
  global.wx = wxMock; global.getApp = () => appMock
  try {
    const pending = page._loadIntroPassport(); page._loadActiveRoamEvents()
    const before = JSON.stringify(page.data.passport)
    appMock.getUserID = () => '202'
    requests.find(r => r.url === '/api/official/active-for-roam').success({ code: 200, data: [{ id: 88, title: '旧账号活动' }] })
    requests.find(r => r.url === '/api/roam/passport/summary').success({ code: 200, data: { trips: 0, shops: 0, distanceM: 0, days: 0 } })
    await pending
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(JSON.stringify(page.data.passport), before)
    assert.equal(page._activeRoamEventId, '')
    const wxml = require('node:fs').readFileSync(require('node:path').resolve(__dirname, '../../pages/roam/index.wxml'), 'utf8')
    assert.match(wxml, /wx:elif="\{\{passportError\}\}"[^>]+bind:retry="retryPassport"/)
  } finally { Object.assign(global, old) }
})


test('清空足迹前必须保存撤销恢复标记，落盘失败保留本机记录供重试', async () => {
  const { page, wxMock, appMock } = loadRoamPage([])
  let clears = 0, writes = 0, requests = 0
  page._roamMemory = { playerId: '101', clearLocal() { clears++; return true } }
  wxMock.setStorageSync = () => { writes++; throw new Error('disk full') }
  wxMock.showToast = () => {}
  appMock.sendRequest = options => { requests++; options.success({ code: 200 }) }
  const previous = { wx: global.wx, getApp: global.getApp }
  global.wx = wxMock; global.getApp = () => appMock
  try {
    assert.equal(await page.confirmClearRoamLocal(), false)
    assert.equal(writes, 1)
    assert.equal(clears, 0)
    assert.equal(requests, 0)
  } finally { global.wx = previous.wx; global.getApp = previous.getApp }
})
