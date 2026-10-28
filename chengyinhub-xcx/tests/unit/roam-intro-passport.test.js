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
  const appMock = { globalData: { features: {} }, getUserID: () => config.playerId || '101', sendRequest() {} }
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
test('起始页护照:读数与档案卡都统计全部历史,不跟着任何截断走', () => {
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
    page._loadIntroPassport()
  } finally {
    global.wx = previousWx
    global.getApp = previousGetApp
  }

  const stat = key => page.data.passport.stats.find(item => item.k === key).v
  assert.equal(stat('漫游'), 13, '漫游次数统计全部历史')
  assert.equal(stat('探店'), 13, '探店数统计全部历史')
  assert.equal(stat('公里'), '13.0', '里程统计全部历史')

  const tile = key => page.data.passport.tiles.find(item => item.key === key)
  assert.equal(tile('history').value, '13 次旅程')
  assert.equal(tile('stamp').value, '13 张邮票')
  assert.ok(tile('badge'), '勋章墙常驻')
  // 2026-10-14 用户确认的漫游原型:探索目标、时光胶囊随漫游上线,接在三张档案卡之后
  assert.deepEqual(page.data.passport.tiles.map(item => item.key), ['history', 'stamp', 'badge', 'goals', 'capsule'], '上排:记录/集邮/勋章 + 探索目标/时光胶囊')
  assert.ok(page.data.passport.tiles.filter(item => item.isNew).every(item => !/\d/.test(item.value)), '新方卡不摆未读取的数字')

  const plays = page.data.passport.plays.map(item => item.key)
  // 2026-10-27 owner P-29 去掉「暂未开放」灰卡;2026-10-28 p78 业主「保留官方活动」⇒ 官方活动卡常驻
  //(没有单场活动时点进漫游任务页),不再写「暂未开放」
  assert.deepEqual(plays, ['event', 'discover'], '下排是官方活动 + 发现店铺')
  assert.ok(!/暂未开放/.test(JSON.stringify(page.data.passport.plays)), '不写暂未开放')
  // P-29:档案三卡带真实图片字段(无图时退回空态插画),不再用大号通用图标
  assert.equal(tile('stamp').img, '12.jpg', '集邮册方卡 = 最新一张邮票')
  // 2026-10-28 p78:无图时退回各自的线描插图(roam/tile-*.svg),不再是通用空态插画
  assert.deepEqual(['history', 'stamp', 'badge'].map((k) => tile(k).art), ['/images/roam/tile-route.svg', '/images/roam/tile-stamp.svg', '/images/roam/tile-medal.svg'])
})

test('negative control: 读数改回跟着旅程卡截断走必须判红', () => {
  const sessions = Array.from({ length: 13 }, (_, index) => ({ ts: index + 1, date: '08-01', distance: '1.0', shops: 1, photos: [{ path: `${index}.jpg` }] }))
  const { page, wxMock, appMock } = loadRoamPage(sessions)
  const previousWx = global.wx
  const previousGetApp = global.getApp
  try {
    global.wx = wxMock
    global.getApp = () => appMock
    page._loadIntroPassport()
  } finally {
    global.wx = previousWx
    global.getApp = previousGetApp
  }
  const shops = page.data.passport.stats.find(item => item.k === '探店').v
  assert.notEqual(shops, 12, '一旦读数被 slice(0,12) 之类截断，这里会变 12 并判红')
})

test('护照只统计当前玩家的漫游记录，不认领旧共享缓存或其他玩家记录', () => {
  const ownSessions = [{ ts: 20201, distance: '2.0', shops: 2, photos: [] }]
  const { page, wxMock, appMock } = loadRoamPage([], {
    playerId: '202',
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
    page._loadIntroPassport()
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

test('空护照显示真实零值，切换菜单会刷新当前玩家记录', () => {
  const { page, wxMock, appMock } = loadRoamPage([])
  const oldWx = global.wx
  const oldApp = global.getApp
  try {
    global.wx = wxMock
    global.getApp = () => appMock
    page.switchIntroTab({ detail: { key: 'passport' } })
    assert.equal(page.data.introTab, 'passport')
    assert.deepEqual(page.data.passport.stats.map(stat => stat.v), [0, 0, 0, '0.0'])
    page.switchIntroTab({ detail: { key: 'start' } })
    assert.equal(page.data.introTab, 'start')
  } finally { global.wx = oldWx; global.getApp = oldApp }
})
