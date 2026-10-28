'use strict'
// BL-41 漫游护照「官方活动」大卡显示可参与的漫游官方活动(/api/official/active-for-roam)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')
const { activeRoamEventCard, applyEventCard } = require('../../utils/roam-official-active.js')

test('BL-41: 只用 id/title/coverImg，空/坏数据不改卡片', () => {
  assert.equal(activeRoamEventCard(null), null)
  assert.equal(activeRoamEventCard({}), null)
  assert.equal(activeRoamEventCard([{ id: 1, title: ' ' }, { title: 'x' }]), null)
  assert.deepEqual(activeRoamEventCard([{ id: 7, title: '外滩夜游', coverImg: 'https://a/b.jpg' }]),
    { sub: '可参与：外滩夜游', cover: 'https://a/b.jpg', eventId: '7' })
  assert.deepEqual(activeRoamEventCard([{ id: 7, title: 'A', coverImg: null }, { id: 8, title: 'B' }]),
    { sub: '可参与：A 等 2 场', cover: '', eventId: '' })
})

test('BL-41: 只改 event 大卡，无封面保留原值', () => {
  const plays = [{ key: 'event', title: '官方活动', sub: '限时任务与奖励', cover: '' }, { key: 'discover', sub: 'x', cover: '' }]
  const next = applyEventCard(plays, { sub: '可参与：A', cover: '', eventId: '' })
  assert.deepEqual(next[0], { key: 'event', title: '官方活动', sub: '可参与：A', cover: '' })
  assert.equal(next[1], plays[1])
  assert.equal(applyEventCard(plays, null), plays)
})

test('BL-41: 页面接线——护照 tab 拉取、失败静默、单场直达详情', () => {
  const js = read('pages/roam/index.js')
  assert.match(js, /if \(introTab === 'passport'\) \{ this\._loadIntroPassport\(\); this\._loadActiveRoamEvents\(\); \}/)
  assert.match(js, /req\('\/api\/official\/active-for-roam', 'GET', \{\}, \{ silentError: true \}\)/)
  // W8 起晚回包还要核对读作用域(换号/卸载后丢弃),令牌与失败静默两道闸不变
  assert.match(js, /if \(!current\(\) \|\| this\._unloaded \|\| this\._activeRoamToken !== token \|\| !apiOk\(res\)\) return;/)
  // 2026-10-27 owner P-29:默认不摆「暂未开放」灰卡;有可参与活动时才把官方活动大卡插到最前再套用卡面
  assert.match(js, /this\.setData\(\{ 'passport\.plays': applyEventCard\(plays, card\) \}\);/)
  // 有可参与的漫游官方活动时直达详情;没有可参与活动时不出这张卡(P-29)
  assert.match(js, /if \(key === 'event' && this\._activeRoamEventId\) \{ if \(this\._eventNavigating\) return; this\._eventNavigating = true; wx\.navigateTo\(\{ url: '\/pages\/activity\/official-detail\/index\?id=' \+ this\._activeRoamEventId, complete: \(\) => \{ this\._eventNavigating = false; \} \}\); return; \}/)
  // onShow 兜底清锁(返回时 complete 万一没回调也不会锁死入口)
  assert.match(js, /onShow\(\)\s*\{[\s\S]{0,600}?this\._syncPresencePrivacy\(\); this\._eventNavigating = false;/)
})

function loadRoamPage() {
  let definition
  const wxMock = { getStorageSync: () => undefined }
  const appMock = { globalData: { features: {} }, getUserID: () => '101', sendRequest() {} }
  const previous = { Page: global.Page, getApp: global.getApp, getCurrentPages: global.getCurrentPages, wx: global.wx }
  const PAGE_MODULE = '../../pages/roam/index.js'
  try {
    global.Page = (config) => { definition = config }
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
  page.setData = function (patch) { Object.assign(this.data, patch) }
  return { page, wxMock }
}

test('BL-41: 官方活动直达详情连点只跳一次,complete 回调后可再跳', () => {
  const { page, wxMock } = loadRoamPage()
  const navs = []
  let complete = null
  wxMock.navigateTo = (o) => { navs.push(o.url); complete = o.complete }
  const oldWx = global.wx
  try {
    global.wx = wxMock
    page._activeRoamEventId = '7'
    const tap = { currentTarget: { dataset: { key: 'event' } } }
    page.onPassportPlayTap(tap)
    page.onPassportPlayTap(tap)
    assert.deepEqual(navs, ['/pages/activity/official-detail/index?id=7'])
    complete()
    page.onPassportPlayTap(tap)
    assert.equal(navs.length, 2)
  } finally { global.wx = oldWx }
})
