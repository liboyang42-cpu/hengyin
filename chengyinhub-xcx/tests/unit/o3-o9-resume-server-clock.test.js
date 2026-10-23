'use strict'

// Doc 2 · O-03 / O-09:跨设备续玩、切后台/来电、手机时钟被改。
// 真源是服务端时钟:暂停快照的新旧由 /api/play/run-session 回传的服务端 savedAt 决定,
// 手机时钟拨快/拨慢、切后台期间改时间,都不能改变「谁新谁旧」与已用时。
const test = require('node:test')
const assert = require('node:assert/strict')
const runSession = require('../../pages/play/utils/play-run-session.js')

const PLAY_PAGE = '../../pages/play/index.js'
const ONE_YEAR = 365 * 24 * 3600 * 1000
let pageConfig
let requests
let storage
let nowMs

global.getApp = () => ({
  globalData: { user_id: 9, features: {} },
  isDevEnv: () => false,
  getUserID: () => '9',
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  sendRequest: (request) => { requests.push(request) },
})
global.wx = {
  getStorageSync: (key) => (key in storage ? storage[key] : ''),
  setStorageSync: (key, value) => { storage[key] = JSON.parse(JSON.stringify(value)) },
  removeStorageSync: (key) => { delete storage[key] },
  showToast() {},
  showModal() {},
  nextTick: (callback) => callback(),
  getWindowInfo: () => ({ statusBarHeight: 20 }),
  getSystemInfoSync: () => ({ statusBarHeight: 20 }),
  offLocationChange() {},
  stopLocationUpdate() {},
}
global.Page = (config) => { pageConfig = config }

const realSetInterval = global.setInterval
const realClearInterval = global.clearInterval
const realDateNow = Date.now
global.setInterval = () => 1
global.clearInterval = () => {}
Date.now = () => nowMs
test.after(() => {
  global.setInterval = realSetInterval
  global.clearInterval = realClearInterval
  Date.now = realDateNow
})

function setByPath(target, key, value) {
  const parts = key.split('.')
  let cursor = target
  for (let i = 0; i < parts.length - 1; i += 1) {
    if (!cursor[parts[i]] || typeof cursor[parts[i]] !== 'object') cursor[parts[i]] = {}
    cursor = cursor[parts[i]]
  }
  cursor[parts[parts.length - 1]] = value
}

function loadPage() {
  delete require.cache[require.resolve(PLAY_PAGE)]
  require(PLAY_PAGE)
  const page = Object.assign({}, pageConfig, { data: JSON.parse(JSON.stringify(pageConfig.data)) })
  page.setData = (patch, callback) => {
    Object.entries(patch).forEach(([key, value]) => setByPath(page.data, key, value))
    if (callback) callback()
  }
  return page
}

const flush = () => new Promise((resolve) => setImmediate(resolve))
const NODES = [{ nodeId: 1, name: '第一站', latitude: 31.21, longitude: 121.46, done: false }]
const find = (url, method) => requests.filter((r) => r.url === url && (!method || r.method === method))
const KEY = 'play_paused_run_v2:m9:t31'

async function loadNodes(page) {
  page.loadData(true)
  find('/api/play/nodes').pop().success({ code: 200, data: { registered: true, topicId: 31, mode: 1, nodes: NODES } })
  await flush()
}

function runningPage() {
  const page = loadPage()
  page.data.topicId = '31'
  page.data.total = 1
  page._runStarted = true
  page._ensureSessionClock()
  page._sessionClock.start()
  return page
}

test.beforeEach(() => {
  pageConfig = null
  requests = []
  storage = {}
  nowMs = 0
})

test('O-09 设备 B 时钟拨快一年:暂停快照改记服务端时刻,设备 A 后来结束时 B 不还魂', async () => {
  nowMs = 1000 + ONE_YEAR // 设备 B 的手机时钟
  const deviceB = runningPage()
  nowMs += 600 * 1000
  deviceB.onHide()
  await flush()
  const save = find('/api/play/run-session/save', 'POST').pop()
  assert.equal(save.data.savedAt, nowMs, '请求仍带本机时刻(老服务端兼容)')
  assert.equal(storage[KEY].savedAt, nowMs)
  save.success({ code: 200, data: { savedAt: 60000 } })
  await flush()
  assert.deepEqual(storage[KEY], { state: 'paused', elapsedSeconds: 600, savedAt: 60000 },
    '服务端确认后本机快照改用服务端时刻,与另一台设备同钟可比')

  // 设备 A(时钟正常)随后结束这一局:服务端墓碑时刻 61000。B 重进必须认这块墓碑。
  requests = []
  const reopenedB = loadPage()
  reopenedB.data.topicId = '31'
  await loadNodes(reopenedB)
  assert.equal(reopenedB.data.runState, 'paused', '本机快照先同步恢复')
  find('/api/play/run-session', 'GET').pop().success({ code: 200, data: { runState: 'ENDED', elapsedSeconds: 0, savedAt: 61000 } })
  await flush()
  assert.equal(reopenedB.data.runState, 'idle', '另一台设备已结束:拨快的手机时钟不能让已结束的局还魂')
  assert.equal(storage[KEY], undefined)
})

test('O-09 负控:服务端没回 savedAt(老服务端)时本机快照不被改写', async () => {
  nowMs = 5000
  const page = runningPage()
  nowMs = 47000
  page.onHide()
  await flush()
  find('/api/play/run-session/save', 'POST').pop().success({ code: 200 })
  await flush()
  assert.deepEqual(storage[KEY], { state: 'paused', elapsedSeconds: 42, savedAt: 47000 })
})

test('O-09 服务端回包时本机已写了更新的一份(继续玩又暂停):不拿旧回包覆盖', async () => {
  const wxStub = { getStorageSync: (k) => storage[k], setStorageSync: (k, v) => { storage[k] = v } }
  storage[KEY] = { state: 'paused', elapsedSeconds: 90, savedAt: 9000 }
  const request = () => Promise.resolve({ code: 200, data: { savedAt: 70000 } })
  assert.equal(await runSession.saveServerPausedRun(request, { memberId: '9', topicId: '31' }, 42, 4000, wxStub), true)
  assert.deepEqual(storage[KEY], { state: 'paused', elapsedSeconds: 90, savedAt: 9000 }, '用时不同 = 不是这次写的,保持本机')
})

test('O-09 设备时钟拨慢 + 断网暂停:本机后写的快照仍比已采纳的服务端时刻新,重进不被旧用时覆盖', async () => {
  storage[KEY] = { state: 'paused', elapsedSeconds: 300, savedAt: 80000 } // 上次已采纳的服务端时刻
  nowMs = 20000 // 手机时钟比服务端慢
  assert.equal(runSession.writePausedRun(global.wx, { memberId: '9', topicId: '31' }, 420, nowMs), true)
  assert.deepEqual(storage[KEY], { state: 'paused', elapsedSeconds: 420, savedAt: 80001 })

  const page = loadPage()
  page.data.topicId = '31'
  await loadNodes(page)
  find('/api/play/run-session', 'GET').pop().success({ code: 200, data: { runState: 'PAUSED', elapsedSeconds: 300, savedAt: 80000 } })
  await flush()
  assert.equal(page._sessionClock.elapsedSeconds(), 420, '断网期间多走的 2 分钟不能被服务端旧快照吃掉')
})

test('O-09 切后台/来电:onHide 冻结用时,后台期间改手机时间(拨快一天)回来后用时不变、不偷跑', async () => {
  nowMs = 1000
  const page = runningPage()
  nowMs = 31000
  page.onHide()
  await flush()
  assert.equal(page._sessionClock.isPaused(), true, '切后台即暂停')
  assert.equal(page._sessionClock.elapsedSeconds(), 30)

  nowMs += 24 * 3600 * 1000 // 后台期间系统时间被改
  assert.equal(page._sessionClock.elapsedSeconds(), 30, '后台期间的时钟变化不计入用时')
  assert.equal(storage[KEY].elapsedSeconds, 30, '落盘的是冻结时的用时')

  page.onPlayHudToggle() // 玩家回来点继续
  nowMs += 5000
  assert.equal(page._sessionClock.elapsedSeconds(), 35, '只从继续那一刻起算')
})

test('O-03 设备 A 暂停后设备 B 继续:B 恢复出的用时就是服务端那份,B 再暂停写回的是续上的用时', async () => {
  nowMs = 100000 // 设备 B 的手机时钟,与服务端无关
  const deviceB = loadPage()
  deviceB.data.topicId = '31'
  deviceB.data.total = 1
  await loadNodes(deviceB)
  find('/api/play/run-session', 'GET').pop().success({ code: 200, data: { runState: 'PAUSED', elapsedSeconds: 600, savedAt: 50000 } })
  await flush()
  assert.equal(deviceB.data.runState, 'paused')
  assert.equal(deviceB._sessionClock.elapsedSeconds(), 600)

  deviceB.onPlayHudToggle()
  nowMs += 120 * 1000
  deviceB.onHide()
  await flush()
  const save = find('/api/play/run-session/save', 'POST').pop()
  assert.equal(save.data.elapsedSeconds, 720, 'B 接着 A 的用时继续算')
  save.success({ code: 200, data: { savedAt: 52000 } })
  await flush()
  assert.deepEqual(storage[KEY], { state: 'paused', elapsedSeconds: 720, savedAt: 52000 })
})
