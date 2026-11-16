// 规则16 历史履约:nodes 返回的 historyNodes 只作手记/剧情只读回看,
// 不并入可玩 nodes(不参与 total/完成率/解锁/推荐/地图),也没有挑战/发奖入口。
const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')

const PLAY_PAGE = '../../pages/play/index.js'

let pageConfig
let requests

function setByPath(target, rawPath, value) {
  const parts = rawPath.split('.')
  let cursor = target
  for (let i = 0; i < parts.length - 1; i += 1) {
    if (cursor[parts[i]] == null) cursor[parts[i]] = {}
    cursor = cursor[parts[i]]
  }
  cursor[parts[parts.length - 1]] = value
}

beforeEach(() => {
  pageConfig = null
  requests = []
  global.getApp = () => ({
    globalData: { user_id: 9, features: {} },
    isDevEnv: () => false,
    sendRequest: (request) => requests.push(request),
    getUploadClient: () => ({ uploadAll: () => ({ destroy() {} }) }),
  })
  global.wx = {
    getStorageSync: () => '',
    setStorageSync() {},
    getWindowInfo: () => ({ statusBarHeight: 20 }),
    getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    showLoading() {},
    hideLoading() {},
    showToast() {},
    showModal: ({ success }) => success && success({ confirm: false }),
  }
  global.Page = (config) => { pageConfig = config }
})

function loadPlayPage() {
  delete require.cache[require.resolve(PLAY_PAGE)]
  require(PLAY_PAGE)
  const page = Object.assign({}, pageConfig, { data: JSON.parse(JSON.stringify(pageConfig.data)) })
  page.setData = function (patch, callback) {
    Object.keys(patch || {}).forEach((key) => setByPath(this.data, key, patch[key]))
    if (callback) callback()
  }
  return page
}

function pageWithHistory() {
  const page = loadPlayPage()
  page.data.mode = 1
  page.data.allDone = true
  page.data.total = 1
  page.data.doneCount = 1
  page.data.nodes = [{
    nodeId: 11, num: 1, sortId: 1, name: '在线站', description: '在线正文',
    done: true, doneAt: 100, imgUrl: 'https://cdn/online.png', fragmentText: '在线碎片',
  }]
  page._historyNodes = [{
    nodeId: 22, name: '已下架站', description: '历史正文', doneAt: 200,
    imgUrl: 'https://cdn/delisted.png', fragmentText: '历史碎片',
    history: true, done: true,
  }]
  page._routeState = { routeMode: 'LINEAR' }
  return page
}

test('historyNodes 只作只读手记卡续在末尾,不动可玩分母', () => {
  const page = pageWithHistory()

  page.buildJournal()

  const cards = page.data.journalCards
  assert.equal(cards.length, 2, '主线 1 张 + 历史 1 张')
  assert.equal(cards[0].nodeId, 11)
  const h = cards[1]
  assert.equal(h.key, 'history-22')
  assert.equal(h.nodeId, 22)
  assert.equal(h.history, true, '历史卡必须可识别')
  assert.equal(h.kvK, '已下架')
  assert.equal(h.kvT, '已下架站')
  assert.ok(!h.locked, '历史卡不是未解锁态')
  // 完成率分母保持当前可玩集合,不被历史站改变
  assert.equal(page.data.total, 1)
  assert.equal(page.data.doneCount, 1)
})

test('历史卡只走只读全文,不进入可玩查找', () => {
  const page = pageWithHistory()

  assert.equal(page._nodeById(22), undefined, '历史站不得被可玩查找命中')
  const history = page._historyNodeById(22)
  assert.ok(history)
  assert.equal(history.name, '已下架站')
})

test('无 historyNodes 时手记行为不变', () => {
  const page = loadPlayPage()
  page.data.mode = 1
  page.data.nodes = [{
    nodeId: 11, num: 1, sortId: 1, name: '在线站', description: '在线正文', done: false, doneAt: 0,
  }]
  page._routeState = { routeMode: 'LINEAR' }

  page.buildJournal()

  assert.equal(page.data.journalCards.length, 1)
  assert.equal(page.data.journalCards[0].nodeId, 11)
})

test('重开自由探索按带时区的服务端完成时间排手记，而非节点配置顺序', () => {
  const page = loadPlayPage()
  page.data.mode = 2
  page.data.nodes = [
    page.normNode({ nodeId: 257, name: '第一章', done: true, doneAt: '2026-10-05T02:39:22.000+08:00' }, 0, 2),
    page.normNode({ nodeId: 258, name: '第二章', done: true, doneAt: '2026-10-04T18:49:36.000+08:00' }, 1, 2),
  ]
  page.buildJournal()
  assert.deepEqual(page.data.journalCards.map(n => n.nodeId), [258, 257])
  assert.equal(page.data.nodes[0].doneAt, Date.parse('2026-10-04T18:39:22.000Z'))
  assert.equal(page.normNode({ doneAt: '无效时间' }, 0, 2).doneAt, 0)
  assert.equal(page.normNode({ doneAt: 1791139162000 }, 0, 2).doneAt, 1791139162000)
})

test('完成页转手记后迟到的城市故事响应不能覆盖手记', async () => {
  const page = pageWithHistory()
  page.data.mode = 2
  page.loadEnding()
  page.data.showFinish = true
  page.openFinishJournal()
  requests[0].success({ code: 200, data: { opener: '旧响应', fragments: [] } })
  await Promise.resolve()
  assert.equal(page.data.showJournal, true)
  assert.equal(page.data.ending.show, false)
  assert.equal(page.data.showFinish, false)
})

test('关闭城市故事取消旧请求，新一轮请求仍能正常显示', async () => {
  const page = pageWithHistory()
  page.data.mode = 2
  page.loadEnding()
  page.closeEnding()
  requests[0].success({ code: 200, data: { opener: '已关闭', fragments: [] } })
  await Promise.resolve()
  assert.equal(page.data.ending.show, false)
  page.loadEnding()
  requests[1].success({ code: 200, data: { opener: '本次故事', fragments: [] } })
  await Promise.resolve()
  assert.equal(page.data.ending.show, true)
  assert.equal(page.data.ending.opener, '本次故事')
})


test('手记及共用半屏必须盖过故事层，仍低于场景宿主', () => {
  const css = require('node:fs').readFileSync(require('node:path').join(__dirname, '../../pages/play/index.wxss'), 'utf8')
  const levels = Object.fromEntries(['chfull', 'mask', 'sheet', 'play-scene-host'].map(name =>
    [name, Number(css.match(new RegExp('^\\.' + name + '\\s*\\{[^}]*z-index:\\s*(\\d+)', 'm'))[1])]))
  assert.ok(levels.mask > levels.chfull, '遮罩不能被全屏故事盖住')
  assert.ok(levels.sheet > levels.mask, '半屏必须在遮罩之上')
  assert.ok(levels.sheet < levels['play-scene-host'], '全局场景宿主仍在半屏之上')
})


function finishJournalFixture() {
  const app = {
    globalData: { user_id: 50, features: {} }, _loginEpoch: 0,
    getUserID: () => 50,
    sendRequest: request => requests.push(request),
  }
  global.getApp = () => app
  const page = pageWithHistory()
  Object.assign(page.data, {
    topicId: 9914601, activityId: 0, regId: 9917319, showFinish: true,
    showJournal: false, reducedMotion: true, finishScore: 12,
    finishDateLabel: '2026-11-16', finishSnap: 'original-route-image',
    finishResult: { status: 'UNKNOWN', earnedXp: null },
    finale: { show: false, info: { elapsed: '00:42' } },
    medalScreen: { show: false, items: [] },
  })
  page._routeState = { routeMode: 'LINEAR', sessionId: '111', version: 8 }
  page._finishArtworkEpoch = 7
  for (const name of ['openFinish', '_openFinale', '_triggerCelebration', '_endRunSession',
    'loadFinishResult', 'loadMilestone', 'loadStageEnd', 'loadNodes']) {
    page[name] = () => assert.fail('journal return must not call ' + name)
  }
  return { page, app }
}

function originalFinishFields(page) {
  return JSON.parse(JSON.stringify({
    score: page.data.finishScore, date: page.data.finishDateLabel,
    snap: page.data.finishSnap, result: page.data.finishResult,
    finale: page.data.finale, medals: page.data.medalScreen,
    route: page._routeState, total: page.data.total, done: page.data.doneCount,
  }))
}

test('finish→journal→close restores the same finish, including UNKNOWN reward state, without replay or reads', () => {
  const { page } = finishJournalFixture()
  const before = originalFinishFields(page)
  page.openFinishJournal()
  assert.equal(page.data.showFinish, false)
  assert.equal(page.data.showJournal, true)
  page.closeJournal()
  assert.equal(page.data.showJournal, false)
  assert.equal(page.data.showFinish, true)
  assert.deepEqual(originalFinishFields(page), before)
  assert.equal(requests.length, 0)
})

test('finish journal return waits for the existing280ms out animation', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const { page } = finishJournalFixture()
  page.data.reducedMotion = false
  page.openFinishJournal()
  page.closeJournal()
  assert.equal(page.data.journalOut, true)
  assert.equal(page.data.showJournal, true)
  assert.equal(page.data.showFinish, false)
  t.mock.timers.tick(279)
  assert.equal(page.data.showFinish, false)
  t.mock.timers.tick(1)
  assert.equal(page.data.showJournal, false)
  assert.equal(page.data.journalOut, false)
  assert.equal(page.data.showFinish, true)
  assert.equal(requests.length, 0)
})

test('ordinary tools journal close never restores a consumed finish origin', () => {
  const { page } = finishJournalFixture()
  page.openFinishJournal(); page.closeJournal()
  assert.equal(page.data.showFinish, true)
  page._hideFinish()
  page.setData({ sessionToolsOpen: true })
  page.openJournalFromTools()
  assert.equal(page.data.sessionToolsOpen, false)
  page.closeJournal()
  assert.equal(page.data.showFinish, false)
  assert.equal(page.data.showJournal, false)
  assert.equal(requests.length, 0)
})

test('late journal close cannot restore another owner, login generation, route, finish epoch or unloaded page', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const invalidations = [
    ({ app }) => { app.getUserID = () => 51 },
    ({ app }) => { app._loginEpoch += 1 },
    ({ page }) => { page.data.topicId = 9914701 },
    ({ page }) => { page._routeState.sessionId = '112' },
    ({ page }) => { page._finishArtworkEpoch += 1 },
    ({ page }) => { page._playDisposed = true },
  ]
  for (const invalidate of invalidations) {
    const fixture = finishJournalFixture()
    fixture.page.data.reducedMotion = false
    fixture.page.openFinishJournal(); fixture.page.closeJournal()
    invalidate(fixture)
    t.mock.timers.tick(280)
    assert.equal(fixture.page.data.showFinish, false)
  }
  assert.equal(requests.length, 0)
})

test('unknown original owner or route cannot arm a finish return', () => {
  for (const unknown of ['owner', 'route']) {
    const { page, app } = finishJournalFixture()
    if (unknown === 'owner') app.getUserID = () => ''
    else page._routeState.sessionId = ''
    page.openFinishJournal(); page.closeJournal()
    assert.equal(page.data.showFinish, false)
    assert.equal(page.data.showJournal, false)
  }
})

test('old journal out timer cannot unmount a newly reopened tools journal or return it to finish', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const { page } = finishJournalFixture()
  page.data.reducedMotion = false
  page.openFinishJournal(); page.closeJournal()
  t.mock.timers.tick(100)
  page.openJournalFromTools(); page.closeJournal()
  t.mock.timers.tick(180)
  assert.equal(page.data.showJournal, true, 'new journal still owns its own out animation')
  assert.equal(page.data.showFinish, false)
  t.mock.timers.tick(100)
  assert.equal(page.data.showJournal, false)
  assert.equal(page.data.showFinish, false)
})

test('finish journal return leaves missing immutable history empty rather than filling from live author text', () => {
  const { page } = finishJournalFixture()
  page._historyReadAttempted = true
  page._historyNodes = []
  page.data.nodes[0].description = 'CURRENT AUTHOR TEXT MUST NOT FILL OLD HISTORY'
  page.openFinishJournal()
  assert.deepEqual(page.data.journalCards[0].paras, [''])
  page.closeJournal()
  assert.deepEqual(page.data.journalCards[0].paras, [''])
})


test('double finish-journal taps before hide callback keep one original return context', () => {
  const { page } = finishJournalFixture()
  const set = page.setData
  const hidden = []
  page.setData = function (patch, callback) {
    if (patch.showFinish === false && callback) {
      set.call(this, patch)
      hidden.push(callback)
    } else set.call(this, patch, callback)
  }
  page.openFinishJournal()
  page.openFinishJournal()
  assert.equal(hidden.length, 1, 'hidden finish cannot start a second handoff')
  hidden.shift()()
  assert.equal(page.data.showJournal, true)
  page.openFinishJournal()
  page.closeJournal()
  assert.equal(page.data.showFinish, true)
  assert.equal(requests.length, 0)
})
