const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-255:商家信息页抽屉(onJoinAct 白名单分派)与底部主按钮(onPrimaryTap)进来的跳转连点只跳一次;
// 与 BL-236/249 共用 this._navigating;complete/onShow 后可再跳;缺参/置灰提示路径不上锁

const ROOT = path.resolve(__dirname, '../..')

function loadPage(rel) {
  let definition
  const navs = []
  const tips = []
  const base = { navigateTo: (o) => navs.push(o), getStorageSync: () => '', getSystemInfoSync: () => ({}), getWindowInfo: () => ({}) }
  const wx = new Proxy(base, { get(t, k) { return k in t ? t[k] : () => {} } })
  const app = new Proxy({ globalData: {}, sendRequest() { return { abort() {} } }, tips: (m) => tips.push(m) }, {
    get(t, k) { return k in t ? t[k] : () => '' },
  })
  const pageDir = path.dirname(path.join(ROOT, rel))
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, rel), 'utf8'), {
    getApp: () => app,
    Page(page) { definition = page },
    Component() {},
    require(request) { return require(path.resolve(pageDir, request)) },
    Set, Map, Date, JSON, Promise, wx, setTimeout: () => 0, clearTimeout() {}, console,
  }, { filename: path.join(ROOT, rel) })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb() },
  })
  Object.assign(page.data, { topicId: 88, regId: 5, info: { name: '隔离主题', clubId: 9 } })
  return { page, navs, tips }
}

const INFO = 'pages/topic/merchantinfo/merchantinfo.js'

const CASES = [
  ['goTopicDetail', {}, '/pages/topic/index/index?id=88'],
  ['goInviteClub', {}, '/pages/merchant/relation/index?tab=club&topicId=88'],
  ['goAddStations', {}, '/pages/publish/fabu/index?id=88'],
  ['goInviteMerchant', {}, '/pages/coop/nearby/index?topicId=88'],
  ['goReceivedApplies', {}, '/pages/coop/list/index?tab=received'],
  ['goFinance', {}, '/subpackageA/pages/assetcenter/earnings/index?theme=merchant&scene=merchant-profit&topicId=88'],
  ['goLedger', {}, '/pages/merchant/ledger/index?view=redemptions'],
  ['goLedger', { operationScope: 'CLUB' }, '/pages/club/enroll/index?clubId=9&topicId=88'],
  ['goEditRegistration', { stateKey: 'pending' }, '/pages/topic/merchantapply/index?mode=1&id=5'],
  ['goSettlementReport', {}, '/pages/coop/settlement-detail/index?source=finance&topicId=88'],
  ['contactService', {}, '/pages/shezhi/shezhi'],
]

for (const [name, data, url] of CASES) {
  test(`BL-255:merchantinfo ${name}${data.operationScope ? '(' + data.operationScope + ')' : ''} complete 未回调时连调两次只跳 1 次,complete 后可再跳`, () => {
    const { page, navs } = loadPage(INFO)
    Object.assign(page.data, data)
    page[name]()
    page[name]()
    assert.equal(navs.length, 1)
    assert.ok(navs[0].url.startsWith(url), navs[0].url)
    navs[0].complete()
    page[name]()
    assert.equal(navs.length, 2)
  })
}

test('BL-255:经 onJoinAct 白名单与 onPrimaryTap 分派进来同样只跳一次,跨入口共用一把锁', () => {
  const { page, navs } = loadPage(INFO)
  page.onJoinAct({ detail: { act: 'goInviteMerchant', dataset: {} } })
  page.onJoinAct({ detail: { act: 'goInviteMerchant', dataset: {} } })
  page.onJoinAct({ detail: { act: 'goReceivedApplies', dataset: {} } })
  page.data.primaryAction = 'settlement'
  page.onPrimaryTap()
  assert.equal(navs.length, 1)
  page._navigating = false // onShow 末尾已有 this._navigating = false(BL-236),onShow 依赖全局 getApp 主题,这里只验清锁后的行为
  page.onPrimaryTap()
  assert.equal(navs.length, 2)
  assert.ok(navs[1].url.startsWith('/pages/coop/settlement-detail/index'))
})

test('BL-255:缺参/置灰提示路径不跳也不上锁;原有 fail/events 回调保留', () => {
  const { page, navs, tips } = loadPage(INFO)
  page.data.topicId = 0
  page.data.info = {}
  page.goAddStations()
  page.goSettlementReport()
  page.goTopicDetail()
  page.data.operationScope = 'CLUB'
  page.goLedger()
  page.data.stateKey = 'running'
  page.goEditRegistration()
  assert.equal(navs.length, 0)
  assert.equal(tips.length, 4)
  assert.ok(!page._navigating)
  page.data.topicId = 88
  page.data.stateKey = 'rejected'
  page.goAddStations()
  assert.equal(typeof navs[0].fail, 'function')
  navs[0].complete()
  page.goEditRegistration()
  assert.equal(typeof navs[1].events.registrationSaved, 'function')
})

test('BL-255:onShow 兜底清锁仍在', () => {
  const source = fs.readFileSync(path.join(ROOT, INFO), 'utf8')
  // 移植版:master 的 onShow 更长(主题/权限回读在前),清锁仍在 onShow 末尾;跨度放宽同 BL-249,断言不变
  assert.match(source, /onShow\(\) \{[\s\S]{0,800}?merchantTheme\.merchantPageShow\(\); this\._navigating = false;\s*\},/)
})
