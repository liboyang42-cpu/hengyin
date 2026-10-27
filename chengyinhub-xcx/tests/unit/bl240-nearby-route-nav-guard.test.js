'use strict'
// BL-240:附近页(subpackageRoam/nearby)与路线内容组件(components/cy/scene-route-content)
// 的跳转入口加跳转中保护——连点只跳一次,complete 后可再跳;onShow / pageLifetimes.show 兜底清锁。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')

function setByPath(target, raw, value) {
  const parts = raw.replace(/\[(\d+)\]/g, '.$1').split('.')
  let cur = target
  for (let i = 0; i < parts.length - 1; i += 1) { if (cur[parts[i]] == null) cur[parts[i]] = {}; cur = cur[parts[i]] }
  cur[parts[parts.length - 1]] = value
}

// ── 附近页 ──
const PAGE = '../../subpackageRoam/nearby/index.js'
let config
let navs
// 真实 app 恒有 getUserID/getAuthorization;W8 起 onShow 用它们核对队伍写作用域(游客 = 空身份)
global.getApp = () => ({ getUserID: () => '', getAuthorization: () => '', globalData: {}, getImgUrl: (u) => u || '', sendRequest: () => {} })
global.wx = {
  getWindowInfo: () => ({ screenHeight: 844, safeArea: { bottom: 810 } }),
  getMenuButtonBoundingClientRect: () => ({ bottom: 88 }),
  getLocation: () => {},
  navigateTo: (o) => { navs.push(o) },
  showToast: () => {},
  showModal: () => {},
  createSelectorQuery: () => ({ select: () => ({ fields: () => ({ exec: () => {} }) }) }),
}
global.Page = (c) => { config = c }

function loadPage() {
  navs = []
  delete require.cache[require.resolve(PAGE)]
  require(PAGE)
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) })
  page.setData = function (patch, cb) { Object.keys(patch).forEach((k) => setByPath(this.data, k, patch[k])); if (cb) cb() }
  page.refresh = () => {}
  return page
}

function assertOnceThenAgain(page, tap, url) {
  tap(); tap()
  assert.equal(navs.length, 1, '连点只跳一次')
  assert.equal(navs[0].url, url)
  navs[0].complete()
  tap()
  assert.equal(navs.length, 2, 'complete 后可再跳')
}

test('附近页 队伍卡「进入队伍」连点只跳一次,complete 后可再跳', () => {
  const page = loadPage()
  page.data.current = { teamId: 7, card: { primary: { action: 'enter' } } }
  assertOnceThenAgain(page, () => page.onCardPrimary(), '/pages/team/detail/index?teamId=7')
})

test('附近页 「去买这场的票」连点只跳一次', () => {
  const page = loadPage()
  page.data.current = { teamId: 7, activityId: 11, card: { primary: { action: 'buy' } } }
  assertOnceThenAgain(page, () => page.onCardPrimary(), '/pages/activity/detail/index?id=11')
})

test('附近页 我的队伍行「进入」连点只跳一次', () => {
  const page = loadPage()
  page.data.myRows = [{ teamId: 9, action: { act: 'enter' } }]
  const ev = { currentTarget: { dataset: { index: 0 } } }
  assertOnceThenAgain(page, () => page.onMyRowAction(ev), '/pages/team/detail/index?teamId=9')
})

test('附近页 主题半屏「去看看」连点只跳一次;onShow 兜底清锁', () => {
  const page = loadPage()
  page.data.topic = { id: 3 }
  assertOnceThenAgain(page, () => page.openTopicPage(), '/pages/topic/index/index?id=3')
  page.openTopicPage()
  assert.equal(navs.length, 2, '第二次跳转未回调前仍锁住')
  page.onShow()
  page.openTopicPage()
  assert.equal(navs.length, 3, 'onShow 清锁后可再跳')
})

// ── 路线内容组件 ──
function mountComponent() {
  let def
  const cnavs = []
  const stub = new Proxy({}, { get: () => () => {} })
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'components/cy/scene-route-content/index.js'), 'utf8'), {
    Component: (d) => { def = d },
    getApp: () => ({ globalData: {}, sendRequest: () => {} }),
    require: (r) => {
      if (r.includes('publisher-identity')) return { CONSENT_TEXT: '', ALREADY_REGISTERED_HINT: '' }
      return stub
    },
    wx: { navigateTo: (o) => cnavs.push(o) },
    console,
  })
  const comp = Object.assign({}, def.methods, { data: { sceneId: 'settings-likes', items: [{ id: 5 }] } })
  comp.setData = function (patch) { Object.assign(this.data, patch) }
  return { def, comp, cnavs }
}

test('路线内容组件 点赞列表项连点只跳一次;complete 与 pageLifetimes.show 都能清锁', () => {
  const { def, comp, cnavs } = mountComponent()
  const ev = { currentTarget: { dataset: { index: 0 } } }
  comp.onItemTap(ev); comp.onItemTap(ev)
  assert.equal(cnavs.length, 1, '连点只跳一次')
  assert.equal(cnavs[0].url, '/pages/topic/index/index?id=5')
  cnavs[0].complete()
  comp.onItemTap(ev)
  assert.equal(cnavs.length, 2, 'complete 后可再跳')
  comp.onItemTap(ev)
  assert.equal(cnavs.length, 2)
  def.pageLifetimes.show.call(comp)
  comp.onItemTap(ev)
  assert.equal(cnavs.length, 3, 'pageLifetimes.show 清锁后可再跳')
})
