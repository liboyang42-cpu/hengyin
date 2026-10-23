'use strict'
// BL-256(2026-10-20 收尾扫描):BL-230–255 之后按「组件事件 / 半屏选择 / 菜单转调」再扫一遍本线文件,
// 补上仍会连点叠页的 8 处入口。改法同 BL-227:共用 this._navigating,跳转前置真,complete 解锁,onShow / pageLifetimes.show 兜底清锁。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')
const LOCK = 'if \\(this\\._navigating\\) return; this\\._navigating = true; wx\\.navigateTo\\(\\{ url: '

test('BL-256: 8 处入口都先判跳转锁,complete 里解锁', () => {
  const cases = [
    ['pages/roam/index.js', new RegExp("openNearbyMap\\(\\) \\{ " + LOCK + "'\\/subpackageRoam\\/nearby\\/index', complete: ")],
    ['pages/roam/index.js', new RegExp("openMyTeams\\(\\) \\{ " + LOCK + "'\\/subpackageRoam\\/my-teams\\/index', complete: ")],
    ['pages/roam/index.js', /const key = e && e\.detail && e\.detail\.key; if \(this\._navigating\) return;[\s\S]{0,700}exploration-export\/index', complete: /],
    ['subpackageRoam/nearby/index.js', new RegExp("openMyTeamsPage\\(\\) \\{ " + LOCK + "'\\/subpackageRoam\\/my-teams\\/index', complete: ")],
    ['pages/topic/merchantinfo/merchantinfo.js', /openStation\(activityId, focus\) \{[\s\S]{0,300}?if \(this\._navigating\) return; this\._navigating = true; wx\.navigateTo\(\{[\s\S]{0,200}?complete: \(\) => \{ this\._navigating = false; \}/],
    ['components/cy/scene-route-content/index.js', new RegExp(LOCK + "'\\/pages\\/club\\/governance\\/index\\?clubId=' \\+ clubId, complete: ")],
    ['pages/club/edit/index.js', new RegExp(LOCK + "'\\/pages\\/club\\/governance\\/index\\?clubId=' \\+ this\\.data\\.clubId, complete: ")],
    // 2026-10-22 owner 裁决(L05 交接):更多→协作邀请改指站内收件箱 im/list(会话 + 收到的邀请)
    ['pages/merchant/index/index.js', new RegExp("goMessages\\(\\) \\{\\s*" + LOCK + "'\\/subpackageB\\/pages\\/im\\/list\\/index', complete: ")],
  ]
  for (const [file, re] of cases) assert.match(read(file), re, file)
})

test('BL-256: 兜底清锁都在(返回本页后入口不会锁死)', () => {
  assert.match(read('pages/club/edit/index.js'), /onShow\(\) \{ this\._navigating = false; \}/)
  assert.match(read('components/cy/scene-route-content/index.js'), /pageLifetimes: \{ show\(\) \{ this\._navigating = false \} \}/)
  assert.match(read('pages/roam/index.js'), /onShow\(\)\s*\{[\s\S]{0,700}?this\._navigating = false;/)
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

test('BL-256: 漫游页附近地图 / 我的队伍 / 工具入口连点只跳一次,complete 后可再跳', () => {
  const { page, wxMock } = loadRoamPage()
  const navs = []
  let complete = null
  wxMock.navigateTo = (o) => { navs.push(o.url); complete = o.complete }
  const oldWx = global.wx
  try {
    global.wx = wxMock
    page.openNearbyMap(); page.openNearbyMap(); page.openMyTeams()
    page.openRoamTool({ detail: { key: 'recap' } })
    assert.deepEqual(navs, ['/subpackageRoam/nearby/index'])
    complete()
    page.openRoamTool({ detail: { key: 'recap' } })
    assert.deepEqual(navs, ['/subpackageRoam/nearby/index', '/subpackageTools/monthly-recap/index'])
    complete()
    page.openRoamTool({ detail: { key: 'unknown' } })
    page.openMyTeams()
    assert.equal(navs.at(-1), '/subpackageRoam/my-teams/index')
  } finally { global.wx = oldWx }
})
