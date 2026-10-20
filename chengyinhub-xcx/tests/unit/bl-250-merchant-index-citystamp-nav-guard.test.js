const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-250:商家工作台据点详情半屏「投一张」(openCityStamp)连点只跳一次、只收一次半屏;complete/onShow 后可再跳;与 BL-228/234 共用 this._navigating

const ROOT = path.resolve(__dirname, '../..')
const PAGE = 'pages/merchant/index/index.js'

function loadPage() {
  let definition
  const navs = []
  const base = { navigateTo: (o) => navs.push(o), getStorageSync: () => '' }
  const wx = new Proxy(base, { get(t, k) { return k in t ? t[k] : () => {} } })
  const app = new Proxy({ globalData: {}, sendRequest() { return { abort() {} } } }, {
    get(t, k) { return k in t ? t[k] : () => '' },
  })
  const pageDir = path.join(ROOT, 'pages/merchant/index')
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, PAGE), 'utf8'), {
    getApp: () => app,
    Page(page) { definition = page },
    require(request) { return require(path.resolve(pageDir, request)) },
    Set, Date, JSON, wx, setTimeout, clearTimeout,
  }, { filename: path.join(ROOT, PAGE) })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch) },
  })
  page.data.merchantAccess = Object.assign({}, page.data.merchantAccess, {
    canReadAftercare: true, canManageProjects: true, canReadVerifyRecords: true,
  })
  return { page, navs }
}

const URL = '/subpackageRoam/citystamp/index?kind=sign&place=' + encodeURIComponent('老街口')
const EVT = { detail: { place: '老街口' } }

function countCloses(page) {
  let closes = 0
  const orig = page.closeScene
  page.closeScene = function () { closes += 1; return orig.call(this) }
  return () => closes
}

test('BL-250:openCityStamp complete 未回调时连调两次只跳 1 次、只收 1 次半屏,complete 后可再跳', () => {
  const { page, navs } = loadPage()
  const closes = countCloses(page)
  page.openCityStamp(EVT)
  page.openCityStamp(EVT)
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, URL)
  assert.equal(closes(), 1)
  navs[0].complete()
  page.openCityStamp(EVT)
  assert.equal(navs.length, 2)
  assert.equal(closes(), 2)
})

test('BL-250:openCityStamp 与其他入口共用一把锁,onShow 兜底清锁', () => {
  const { page, navs } = loadPage()
  page.goFinance()
  page.openCityStamp(EVT)
  assert.equal(navs.length, 1)
  page._navigating = false // onShow 已有 this._navigating = false(BL-228)
  page.openCityStamp({})
  assert.equal(navs.length, 2)
  assert.equal(navs[1].url, '/subpackageRoam/citystamp/index?kind=sign&place=' + encodeURIComponent('这一站'))
})

test('BL-250:两处 openCityStamp 定义都先判锁再收半屏,跳转带 complete 清锁', () => {
  const source = fs.readFileSync(path.join(ROOT, PAGE), 'utf8')
  const defs = source.match(/openCityStamp\((?:e|event)\) \{[\s\S]*?\n  \},/g) || []
  assert.equal(defs.length, 2)
  for (const def of defs) {
    assert.match(def, /if \(this\._navigating\) return; this\.closeScene\(\);/)
    assert.match(def, /this\._navigating = true; wx\.navigateTo\(\{[^\n]*complete: \(\) => \{ this\._navigating = false; \}/)
  }
  assert.match(source, /onShow\(options\) \{[\s\S]{0,200}this\._navigating = false;/)
})
