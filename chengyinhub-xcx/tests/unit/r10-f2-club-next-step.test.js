const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

// 第十轮 f2 第 1 项(j2 F-05 / F-14):开了商家承接的俱乐部主题,「现在要做」按生命周期走
// 招商 → 定价 → 开一场;开一场只在有可售主题时出现,且不和主按钮重复;项目卡不把主题开始日当场次。

const ROOT = path.resolve(__dirname, '../..')

function loadPage() {
  const src = fs.readFileSync(path.join(ROOT, 'pages/club/detail/index.js'), 'utf8')
  let captured = null
  const navigations = []
  const sandbox = {
    Page(options) { captured = options },
    Component() {},
    getApp: () => ({ globalData: {}, getUserID: () => 1, getUserRole: () => 'player', sendRequest() {} }),
    require: (id) => {
      if (id.startsWith('.')) { try { return require(path.join(ROOT, 'pages/club/detail', id)) } catch (e) { return {} } }
      return {}
    },
    wx: { showToast() {}, navigateTo(o) { navigations.push(o.url); o.complete && o.complete() }, switchTab() {}, navigateBack() {}, pageScrollTo() {} },
    module: { exports: {} }, exports: {}, console,
    Date, Set, Map, Number, String, Array, Object, Boolean, JSON, Math, isNaN, parseInt, parseFloat,
  }
  sandbox.global = sandbox
  vm.createContext(sandbox)
  vm.runInContext(src, sandbox, { filename: 'club-detail.js' })
  const page = Object.assign({}, captured)
  page.data = JSON.parse(JSON.stringify(captured.data))
  page.setData = function (patch, cb) { Object.assign(page.data, patch); cb && cb() }
  page.navigations = navigations
  return page
}

function owner(page, topics) {
  Object.assign(page.data, {
    club: { id: 1, isOwner: true, viewerIsAdmin: false }, clubId: 1, topicState: 'ready',
    ownerProjectState: 'ready', inviteFeedbackState: 'ready', sentInvites: [], projectRows: [], messages: [],
  })
  page._topics = topics
  page.updateOwnerStage()
}

test('立项待审的城市定向主题:不说开一场,也不露开一场', () => {
  const page = loadPage()
  owner(page, [{ id: 11, name: '喷泉报时', productType: 1, lifecycle: 7 }])
  assert.equal(page.data.ownerStage, 'review')
  assert.equal(page.data.ownerCanOpenSession, false)
})

test('招募中 → 去招商,主按钮打开该主题的招商层', () => {
  const page = loadPage()
  owner(page, [{ id: 11, name: '喷泉报时', productType: 1, lifecycle: 1 }])
  assert.deepEqual([page.data.ownerStage, page.data.ownerPrimaryLabel, page.data.ownerStageHint], ['recruit', '去招商', '喷泉报时'])
  page.onOwnerPrimary()
  assert.deepEqual(page.navigations, ['/pages/topic/merchantinfo/merchantinfo?topicId=11&scope=CLUB&open=merchant'])
})

test('定价中 → 确认终价,主按钮打开定价页', () => {
  const page = loadPage()
  owner(page, [{ id: 11, name: '喷泉报时', productType: 1, lifecycle: 2 }])
  assert.deepEqual([page.data.ownerStage, page.data.ownerPrimaryLabel], ['price', '确认终价'])
  page.onOwnerPrimary()
  assert.deepEqual(page.navigations, ['/pages/topic/pricing/index?topicId=11'])
})

test('已开售 → 开一场只出现一次(主按钮),不再并排两个开一场', () => {
  const page = loadPage()
  owner(page, [{ id: 11, productType: 1, lifecycle: 3 }])
  assert.equal(page.data.ownerStage, 'run')
  assert.equal(page.data.ownerCanOpenSession, false, '主按钮已是开一场,次按钮不再重复')
  owner(page, [{ id: 11, productType: 1, lifecycle: 3 }, { id: 12, productType: 1, lifecycle: 1 }])
  assert.equal(page.data.ownerStage, 'recruit')
  assert.equal(page.data.ownerCanOpenSession, true, '另有可售主题时次按钮仍可开一场')
})

test('项目卡:没有场次写「还没有场次」,不把主题开始日当成场次', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/club/detail/index.wxml'), 'utf8')
  assert.match(wxml, /\{\{item\.nextSessionStart \? item\.timeText : '还没有场次'\}\}/)
  assert.match(wxml, /club\.isOwner && ownerCanOpenSession[^>]*bindtap="onOpenClubSession"/)
})

test('俱乐部主题页头部与「更多」阶段同一口径,定价中可直接去确认终价', () => {
  const js = fs.readFileSync(path.join(ROOT, 'pages/club/topic-detail/index.js'), 'utf8')
  assert.match(js, /2: '定价中 · 未开售'/)
  assert.doesNotMatch(js, /'未开售 · 待开售审核'/)
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/club/topic-detail/index.wxml'), 'utf8')
  assert.match(wxml, /setting\.canManage && lifecycle === 2[^>]*bindtap="goPricing"/)
})

test('商家已接受但主题还在招募中:仍是去招商,截止后进定价,不跳成管理项目', () => {
  const page = loadPage()
  owner(page, [{ id: 11, name: '喷泉报时', productType: 1, lifecycle: 1 }])
  page.data.projectRows = [{ clubId: 1, acceptStatus: 'merchantAccepted' }]
  page.updateOwnerStage()
  assert.equal(page.data.ownerStage, 'recruit')
  page._topics = [{ id: 11, name: '喷泉报时', productType: 1, lifecycle: 2 }]
  page.updateOwnerStage()
  assert.equal(page.data.ownerStage, 'price')
})
