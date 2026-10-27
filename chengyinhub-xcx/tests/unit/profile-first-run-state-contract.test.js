const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const PROFILE_JS = path.join(ROOT, 'subpackageMember/components/cy/profile/index.js')
const PROFILE_WXML = path.join(ROOT, 'subpackageMember/components/cy/profile/index.wxml')
const PROFILE_WXSS = path.join(ROOT, 'subpackageMember/components/cy/profile/index.wxss')
const read = (file) => fs.readFileSync(file, 'utf8')

for (const scope of ['host-self', 'visitor-self', 'visitor-other']) {
  for (const initialShow of [false, true]) test(`个人页返回：${scope}初始show=${initialShow}，离开后首次返回刷新一次`, () => {
    const component = loadProfile()
    const previousWx = global.wx
    const previousGetApp = global.getApp
    global.getApp = () => app
    global.wx = { hideTabBar() {}, setNavigationBarColor() {}, setBackgroundColor() {} }
    const reads = []
    component._profileAttached = true
    component._profileShown = false
    component.data.hostSelf = scope === 'host-self'
    component.data.isSelf = scope !== 'visitor-other'
    component._syncProfileReducedMotion = () => {}
    component.syncViewerIdentity = () => {}
    component.refreshCoopPublisherAccess = () => {}
    component.computeIsSelf = () => component.data.isSelf
    component.refreshDirtyPosts = () => {}
    component.syncMerchantView = () => {}
    component.refreshAll = force => reads.push(['self', force])
    component.refreshPublicProfile = force => reads.push(['public', force])
    try {
      if (initialShow) componentDefinition.pageLifetimes.show.call(component)
      assert.deepEqual(reads, [], '首次进入沿用attached初始化，不重复读取')
      componentDefinition.pageLifetimes.hide.call(component)
      componentDefinition.pageLifetimes.show.call(component)
      assert.deepEqual(reads, [[scope === 'visitor-other' ? 'public' : 'self', true]])
    } finally { global.wx = previousWx; global.getApp = previousGetApp }
  })
}

test('个人页返回：身份变化仍优先重新初始化，不读取旧视角', () => {
  const component = loadProfile()
  component._profileAttached = true
  component.data.hostSelf = true
  component._syncProfileReducedMotion = () => {}
  component.syncViewerIdentity = () => {}
  component.refreshCoopPublisherAccess = () => {}
  component.computeIsSelf = () => false
  let initialized = 0
  component.initializeProfile = () => { initialized++ }
  component.refreshAll = () => { throw new Error('不得读取旧视角') }
  componentDefinition.pageLifetimes.hide.call(component)
  componentDefinition.pageLifetimes.show.call(component)
  assert.equal(initialized, 1)
})

test('排名文案与实际总积分榜一致，视觉和无障碍都不声称月份或城市筛选', () => {
  const wxml = read(PROFILE_WXML)
  assert.match(wxml, /aria-label="总积分排名 \{\{rankDisplay.no\}\},查看成长中心详情"/)
  assert.match(wxml, /class="pc-rank-title">总积分排名<\/text>/)
  assert.doesNotMatch(wxml, /本月排名/)
})

let componentDefinition
let requests

const app = {
    canUseCommunity: () => true, // Current-account server-open fixture.
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  getUserID: () => '42',
  getAuthorization: () => 'Bearer unit-fixture',
  getUserRole: () => 'player',
  getUserType: () => 1,
  getPageSize: () => 8,
  getTotalPage: (total, size) => Math.ceil(total / size),
  isDevEnv: () => false,
  setUserRole() {},
  sendRequest(options) { requests.push(options) },
}

function setByPath(target, key, value) {
  const parts = key.split('.')
  let cursor = target
  parts.slice(0, -1).forEach((part) => {
    if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {}
    cursor = cursor[part]
  })
  cursor[parts[parts.length - 1]] = value
}

function loadProfile() {
  const previous = { Component: global.Component, getApp: global.getApp, wx: global.wx }
  requests = []
  componentDefinition = null
  global.getApp = () => app
  global.wx = {
    getStorageSync: () => '',
    setStorageSync() {},
    removeStorageSync() {},
    hideTabBar() {},
    setNavigationBarColor() {},
    setBackgroundColor() {},
  }
  global.Component = (definition) => { componentDefinition = definition }
  delete require.cache[require.resolve(PROFILE_JS)]
  require('../helpers/community-page-seam.js').loadPage(PROFILE_JS)
  global.Component = previous.Component
  global.getApp = previous.getApp
  global.wx = previous.wx

  const component = Object.assign({}, componentDefinition.methods, {
    data: JSON.parse(JSON.stringify(componentDefinition.data)),
  })
  component.data.isSelf = true
  component.setData = (patch, callback) => {
    Object.entries(patch).forEach(([key, value]) => setByPath(component.data, key, value))
    if (callback) callback()
  }
  return component
}

const metricReads = [
  ['loadUserData', n => [{ code: 200, data: { role: 'player', balance: n, followNum: n, fansNum: n,
    topicNum: n, activityNum: n, likeNum: n, friendNum: n, nickname: `member-${n}` } }]],
  ['loadGrowthBundle', n => [{ code: 200, data: { level: n, totalCheckins: n, totalMileage: n, streakDays: n } },
    { code: 200, data: { growth: { levelNo: n, expValue: n }, points: n } }]],
  ['loadThemes', n => [{ code: 200, data: [{ activityId: n, topicId: n, name: `theme-${n}`, total: n, doneCount: n }] }]],
  ['loadPointsStat', n => [{ code: 200, data: { weekPoints: n, rankPercentage: `TOP${n}%`, userRank: 1, rankDelta: n } },
    weekExpResponse(n)]],
]

function beginMetricRead(component, method) {
  const start = requests.length
  const pending = component[method]()
  return { pending, requests: requests.slice(start) }
}

async function finishMetricRead(read, responses, outcome = 'success') {
  read.requests.forEach((request, i) => {
    if (outcome === 'network') request.fail({ errMsg: 'timeout' })
    else request.success(outcome === 'auth' ? { code: 401 }
      : outcome === 'malformed' ? { code: 200, data: null } : responses[i])
  })
  await read.pending
}

for (const [method, responses] of metricReads) {
  for (const outcome of ['success', 'auth', 'network', 'malformed']) test(`资料指标回包：${method}旧账号${outcome}不覆盖当前读取`, async () => {
    const original = { getUserID: app.getUserID, setUserRole: app.setUserRole }
    let memberId = '42'
    const roles = []
    app.getUserID = () => memberId
    app.setUserRole = role => roles.push(role)
    try {
      const component = loadProfile()
      const old = beginMetricRead(component, method)
      memberId = '43'
      const current = beginMetricRead(component, method)
      await finishMetricRead(current, responses(7))
      const snapshot = structuredClone(component.data)
      const currentRoles = [...roles]
      await finishMetricRead(old, responses(2), outcome)
      assert.deepEqual(component.data, snapshot)
      assert.deepEqual(roles, currentRoles)
    } finally { Object.assign(app, original) }
  })

  test(`资料指标回包：${method}同账号连续刷新保留最新结果`, async () => {
    const component = loadProfile()
    const old = beginMetricRead(component, method)
    const current = beginMetricRead(component, method)
    await finishMetricRead(current, responses(7))
    const snapshot = structuredClone(component.data)
    await finishMetricRead(old, responses(2))
    assert.deepEqual(component.data, snapshot)
  })

  for (const end of ['cleared-identity', 'detached']) test(`资料指标回包：${method}在${end}后不再接收结果`, async () => {
    const original = app.getUserID
    let memberId = '42'
    app.getUserID = () => memberId
    try {
      const component = loadProfile()
      component._profileAttached = true
      const read = beginMetricRead(component, method)
      if (end === 'cleared-identity') memberId = null
      else componentDefinition.lifetimes.detached.call(component)
      const snapshot = structuredClone(component.data)
      await finishMetricRead(read, responses(2))
      if (end === 'cleared-identity') {
        assert.equal(component.data.selfReadAccess, 'login')
        assert.deepEqual(component.data.userInfo, {})
        assert.equal(component.data.profilePoint, null)
        for (const field of ['themeProgress', 'orderPreviewList', 'projectPreviewList']) assert.deepEqual(component.data[field], [])
      } else assert.deepEqual(component.data, snapshot)
    } finally { app.getUserID = original }
  })

  test(`资料指标回包：${method}组件销毁后不发起请求`, async () => {
    const component = loadProfile()
    componentDefinition.lifetimes.detached.call(component)
    const read = beginMetricRead(component, method)
    await finishMetricRead(read, responses(2))
    assert.equal(read.requests.length, 0)
  })
}

for (const outcome of ['rows', 'empty', 'business-failure', 'network', 'malformed']) test(`订单预览旧账号${outcome}回包不覆盖新账号`, async () => {
  const originalGetUserID = app.getUserID
  let memberId = '42'
  app.getUserID = () => memberId
  try {
    const component = loadProfile()
    component._profileAttached = true
    const old = component.loadOrderPreview()
    const oldRequest = requests.at(-1)
    memberId = '43'
    const current = component.loadOrderPreview()
    requests.at(-1).success({ code: 200, data: { rows: [{ id: 99, topicName: '当前订单', registrationStatus: 2, paymentStatus: 2 }] } })
    await current
    if (outcome === 'network') oldRequest.fail({ errMsg: 'old timeout' })
    else oldRequest.success(outcome === 'business-failure' ? { code: 401 }
      : { code: 200, data: { rows: outcome === 'rows' ? [{ id: 88, registrationStatus: 2, paymentStatus: 2 }] : outcome === 'empty' ? [] : {} } })
    await old
    assert.deepEqual(component.data.orderPreviewList.map(row => row.id), [99])
    assert.equal(component.data.orderErr, false)
    assert.equal(component.data.orderAuthError, false)
  } finally { app.getUserID = originalGetUserID }
})

test('订单预览同账号连续刷新只接受最新请求', async () => {
  const component = loadProfile()
  const old = component.loadOrderPreview()
  const oldRequest = requests.at(-1)
  const current = component.loadOrderPreview()
  requests.at(-1).success({ code: 200, data: { rows: [{ id: 99, registrationStatus: 2, paymentStatus: 2 }] } })
  await current
  oldRequest.success({ code: 200, data: { rows: [{ id: 88, registrationStatus: 2, paymentStatus: 2 }] } })
  await old
  assert.deepEqual(component.data.orderPreviewList.map(row => row.id), [99])
})

for (const end of ['cleared-identity', 'detached']) test(`订单预览在${end}后不接收旧回包`, async () => {
  const originalGetUserID = app.getUserID
  let memberId = '42'
  app.getUserID = () => memberId
  try {
    const component = loadProfile()
    component._profileAttached = true
    component.data.orderPreviewList = [{ id: 99 }]
    const pending = component.loadOrderPreview()
    if (end === 'cleared-identity') memberId = null
    else componentDefinition.lifetimes.detached.call(component)
    requests.at(-1).success({ code: 200, data: { rows: [{ id: 88, registrationStatus: 2, paymentStatus: 2 }] } })
    await pending
    assert.deepEqual(component.data.orderPreviewList.map(row => row.id), end === 'cleared-identity' ? [] : [99])
    if (end === 'cleared-identity') assert.equal(component.data.selfReadAccess, 'login')
  } finally { app.getUserID = originalGetUserID }
})

test('订单预览换号发起新读取时清除旧订单，当前失败保持未加载', async () => {
  const originalGetUserID = app.getUserID
  let memberId = '42'
  app.getUserID = () => memberId
  try {
    const component = loadProfile()
    const first = component.loadOrderPreview()
    requests.at(-1).success({ code: 200, data: { rows: [{ id: 88, registrationStatus: 2, paymentStatus: 2 }] } })
    await first
    memberId = '43'
    const current = component.loadOrderPreview()
    const startList = structuredClone(component.data.orderPreviewList)
    requests.at(-1).fail({ errMsg: 'current timeout' })
    await current
    assert.deepEqual(startList, [])
    assert.equal(component.data.orderErr, true)
  } finally { app.getUserID = originalGetUserID }
})

test('订单预览组件销毁后不发起新读取', async () => {
  const component = loadProfile()
  componentDefinition.lifetimes.detached.call(component)
  const pending = component.loadOrderPreview()
  if (requests.length) requests.at(-1).success({ code: 200, data: { rows: [] } })
  await pending
  assert.equal(requests.length, 0)
})

for (const outcome of ['rows', 'empty', 'business-failure', 'network', 'malformed']) test(`项目预览回包：旧账号${outcome}不覆盖当前项目或错误`, async () => {
  const original = { id: app.getUserID, tips: app.tips }
  let memberId = '42'
  const tips = []
  app.getUserID = () => memberId
  app.tips = message => tips.push(message)
  try {
    const component = loadProfile()
    const old = component.loadProjectPreview()
    const oldRequest = requests.at(-1)
    memberId = '43'
    const current = component.loadProjectPreview()
    requests.at(-1).success({ code: 200, data: { rows: [{ id: 99, name: '当前项目' }] } })
    await current
    if (outcome === 'network') oldRequest.fail({ errMsg: 'old timeout' })
    else oldRequest.success(outcome === 'business-failure' ? { code: 401 }
      : { code: 200, data: { rows: outcome === 'rows' ? [{ id: 88, registrationStatus: 2, paymentStatus: 2 }] : outcome === 'empty' ? [] : {} } })
    await old
    assert.deepEqual(component.data.projectPreviewList.map(row => row.id), [99])
    assert.equal(component.data.projectErr, false)
    assert.equal(component.data.projectAuthError, false)
    assert.deepEqual(tips, [])
  } finally { app.getUserID = original.id; app.tips = original.tips }
})

test('项目预览回包：同账号连续刷新只接收最新请求', async () => {
  const component = loadProfile()
  const old = component.loadProjectPreview()
  const oldRequest = requests.at(-1)
  const current = component.loadProjectPreview()
  requests.at(-1).success({ code: 200, data: { rows: [{ id: 99, registrationStatus: 2, paymentStatus: 2 }] } })
  await current
  oldRequest.success({ code: 200, data: { rows: [{ id: 88, registrationStatus: 2, paymentStatus: 2 }] } })
  await old
  assert.deepEqual(component.data.projectPreviewList.map(row => row.id), [99])
})

test('项目预览回包：旧分页首包不再向当前身份发下一页', async () => {
  const component = loadProfile()
  const old = component.loadProjectPreview()
  const oldRequest = requests.at(-1)
  const current = component.loadProjectPreview()
  requests.at(-1).success({ code: 200, data: { rows: [{ id: 99, registrationStatus: 2, paymentStatus: 2 }] } })
  await current
  oldRequest.success({ code: 200, data: { rows: Array.from({ length: 8 }, (_, id) => ({ id, startDate: '2020-01-01', endDate: '2020-02-01' })), total: 16 } })
  await flush()
  const requestCount = requests.length
  if (requestCount > 2) requests.at(-1).success({ code: 200, data: { rows: [] } })
  await old
  assert.equal(requestCount, 2)
  assert.deepEqual(component.data.projectPreviewList.map(row => row.id), [99])
})

test('项目预览回包：已发出的旧第二页不覆盖最新预览', async () => {
  const component = loadProfile()
  const old = component.loadProjectPreview()
  requests.at(-1).success({ code: 200, data: { rows: Array.from({ length: 8 }, (_, id) => ({ id, startDate: '2020-01-01', endDate: '2020-02-01' })), total: 16 } })
  await flush()
  const oldSecondPage = requests.at(-1)
  assert.equal(oldSecondPage.data.pageNum, 2)
  const current = component.loadProjectPreview()
  requests.at(-1).success({ code: 200, data: { rows: [{ id: 99, registrationStatus: 2, paymentStatus: 2 }] } })
  await current
  oldSecondPage.success({ code: 200, data: { rows: [{ id: 88, registrationStatus: 2, paymentStatus: 2 }] } })
  await old
  assert.deepEqual(component.data.projectPreviewList.map(row => row.id), [99])
})

for (const end of ['cleared-identity', 'detached']) test(`项目预览回包：${end}后忽略旧成功`, async () => {
  const original = app.getUserID
  let memberId = '42'
  app.getUserID = () => memberId
  try {
    const component = loadProfile()
    component._profileAttached = true
    component.data.projectPreviewList = [{ id: 99 }]
    const pending = component.loadProjectPreview()
    if (end === 'cleared-identity') memberId = null
    else componentDefinition.lifetimes.detached.call(component)
    requests.at(-1).success({ code: 200, data: { rows: [{ id: 88, registrationStatus: 2, paymentStatus: 2 }] } })
    await pending
    assert.deepEqual(component.data.projectPreviewList.map(row => row.id), end === 'cleared-identity' ? [] : [99])
    if (end === 'cleared-identity') assert.equal(component.data.selfReadAccess, 'login')
  } finally { app.getUserID = original }
})

test('项目预览回包：换号新读取先清除旧项目', async () => {
  const original = app.getUserID
  let memberId = '42'
  app.getUserID = () => memberId
  try {
    const component = loadProfile()
    const first = component.loadProjectPreview()
    requests.at(-1).success({ code: 200, data: { rows: [{ id: 88, registrationStatus: 2, paymentStatus: 2 }] } })
    await first
    memberId = '43'
    const current = component.loadProjectPreview()
    const startList = structuredClone(component.data.projectPreviewList)
    requests.at(-1).fail({ errMsg: 'current timeout' })
    await current
    assert.deepEqual(startList, [])
    assert.deepEqual(component.data.projectPreviewList, [])
    assert.equal(component.data.projectErr, true)
  } finally { app.getUserID = original }
})

test('项目预览回包：销毁后不发新读取', async () => {
  const component = loadProfile()
  componentDefinition.lifetimes.detached.call(component)
  const pending = component.loadProjectPreview()
  if (requests.length) requests.at(-1).success({ code: 200, data: { rows: [] } })
  await pending
  assert.equal(requests.length, 0)
})

for (const end of ['changed-member', 'same-member-refresh', 'cleared-identity', 'detached']) test(`项目待处理回包：${end}不覆盖最新角标`, async () => {
  const original = app.getUserID
  let memberId = '42'
  app.getUserID = () => memberId
  try {
    const component = loadProfile()
    component._profileAttached = true
    component.data.projectPendingCount = 2
    const old = component.loadProjectPending()
    const oldRequest = requests.at(-1)
    if (end === 'changed-member') memberId = '43'
    if (end === 'changed-member' || end === 'same-member-refresh') {
      const current = component.loadProjectPending()
      requests.at(-1).success({ code: 200, data: { projectSummary: { pending: 1, rejected: 1 } } })
      await current
    } else if (end === 'cleared-identity') memberId = null
    else componentDefinition.lifetimes.detached.call(component)
    oldRequest.success({ code: 200, data: { projectSummary: { pending: 5, rejected: 4 } } })
    await old
    assert.equal(component.data.projectPendingCount, end === 'cleared-identity' ? 0 : 2)
    if (end === 'cleared-identity') assert.equal(component.data.selfReadAccess, 'login')
  } finally { app.getUserID = original }
})

test('项目待处理回包：换号新读取先清除旧角标', async () => {
  const original = app.getUserID
  let memberId = '42'
  app.getUserID = () => memberId
  try {
    const component = loadProfile()
    const first = component.loadProjectPending()
    requests.at(-1).success({ code: 200, data: { projectSummary: { pending: 5, rejected: 4 } } })
    await first
    memberId = '43'
    const current = component.loadProjectPending()
    const startCount = component.data.projectPendingCount
    requests.at(-1).fail({ errMsg: 'current timeout' })
    await current
    assert.equal(startCount, 0)
    assert.equal(component.data.projectPendingCount, 0)
  } finally { app.getUserID = original }
})

test('项目待处理回包：销毁后不发新读取', async () => {
  const component = loadProfile()
  componentDefinition.lifetimes.detached.call(component)
  const pending = component.loadProjectPending()
  if (requests.length) requests.at(-1).success({ code: 200, data: { projectSummary: { pending: 0 } } })
  await pending
  assert.equal(requests.length, 0)
})

function weekExpResponse(score = 0, memberId = app.getUserID()) {
  return { code: 200, data: { metric: 'exp', period: 'week', list: [], me: { memberId, score, rank: null } } }
}

function respond(url, response) {
  const request = requests.find((item) => item.url === url)
  assert.ok(request, `缺少请求 ${url}`)
  if (response === 'fail') request.fail({ errMsg: 'network error' })
  else request.success(response)
}

async function flush() {
  await new Promise((resolve) => setImmediate(resolve))
}

// 2026-10-27 owner 真机走查 P-53:我的页「成为主理人」入口删除(设置页「成为俱乐部主理人」pickClub 承接同一分流),goBecomeTalent 及其用例随之移除。

test('进行中报名成功返回空结果时清除旧报名，读取失败仍保留已有数据', async () => {
  const component = loadProfile()
  component.data.joinInfo = { id: 18 }
  const failed = component.loadJoinData()
  respond('/api/registration/join_info', { code: 500, msg: '读取失败' })
  await failed
  assert.equal(component.data.joinInfo.id, 18)
  requests = []
  const empty = component.loadJoinData()
  respond('/api/registration/join_info', { code: 200 })
  await empty
  assert.equal(component.data.joinInfo.id, 0)
})

function successSources(overrides) {
  const values = Object.assign({
    user: {
      followNum: 0,
      fansNum: 0,
      topicNum: 0,
      activityNum: 0,
      likeNum: 0,
      point: 0,
    },
    play: { level: 1, totalCheckins: 0, totalMileage: 0, streakDays: 0 },
    center: { growth: { levelNo: 1, expValue: 0 }, points: 0 },
    completed: [],
    points: { weekPoints: 0, rankPercentage: 'TOP0%', rankDelta: 0 },
    weekExp: 0,
  }, overrides)
  return {
    '/api/user/info': { code: 200, data: values.user },
    '/api/play/growth': { code: 200, data: values.play },
    '/api/growth/center': { code: 200, data: values.center },
    '/api/play/my-completed': { code: 200, data: values.completed },
    '/api/user/points/statistics': { code: 200, data: values.points },
    '/api/growth/leaderboard': weekExpResponse(values.weekExp),
  }
}

async function loadMetricSources(component, sources) {
  component.resetProfileMetricState()
  const pending = [
    component.loadUserData(),
    component.loadGrowthBundle(),
    component.loadThemes(),
    component.loadPointsStat(),
  ]
  Object.entries(sources).forEach(([url, response]) => respond(url, response))
  await Promise.all(pending)
  await flush()
}

test('首访：所有来源成功且从未参与时进入引导态，不把空数据渲染成一屏零值', async () => {
  const component = loadProfile()
  await loadMetricSources(component, successSources())

  assert.equal(component.data.profileMetricState, 'empty')
  // 2026-09-18 UI-04 用户拍板:数字统计没取到显示 0,不再是「—」。
  // 空态判定不受影响(它看的是 userSignals 原值,不是这个展示兜底)。
  assert.equal(component.data.friendNum, 0, '字段缺失按 UI-04 保底成 0')
})

test('真实零：有过节点参与的老用户即使里程、EXP、周积分都为零，仍展示真实指标', async () => {
  const component = loadProfile()
  await loadMetricSources(component, successSources({
    play: { level: 1, totalCheckins: 2, totalMileage: 0, streakDays: 0 },
  }))

  assert.equal(component.data.profileMetricState, 'ready')
  assert.equal(component.data.identityCard.km, '0.0')
  assert.equal(component.data.exploreValue, 0)
  assert.equal(component.data.exploreStat.weekExp, 0)
  assert.equal(component.data.followNum, 0)
  assert.equal(component.data.fansNum, 0)
})

test('历史参与：没有节点打卡但已有核销通关记录时，不得误判为首访', async () => {
  const component = loadProfile()
  await loadMetricSources(component, successSources({
    completed: [{ activityId: 9, topicId: 3, doneCount: 0, completed: true }],
  }))

  assert.equal(component.data.profileMetricState, 'ready')
})

test('非零资料：已有关注或发布记录时直接展示，不用探索空态遮住真实资料', async () => {
  const component = loadProfile()
  await loadMetricSources(component, successSources({
    user: {
      followNum: 1,
      fansNum: 0,
      topicNum: 0,
      activityNum: 0,
      likeNum: 0,
      point: 0,
    },
  }))

  assert.equal(component.data.profileMetricState, 'ready')
  assert.equal(component.data.followNum, 1)
  assert.equal(component.data.fansNum, 0)
})

test('请求失败：参与判据这次没取到时进入错误态，不能降级成首访或真实零', async () => {
  const component = loadProfile()
  const sources = successSources()
  sources['/api/play/growth'] = 'fail'
  await loadMetricSources(component, sources)

  assert.equal(component.data.profileMetricState, 'error')
})

async function resumeWithNormalLogin(component) {
  const saved = { login: app.reLogin, wx: global.wx, refresh: component.refreshAll, banner: component.loadBanner, merchant: component.syncMerchantView }
  let logins = 0
  app.reLogin = () => { logins++; return Promise.resolve({ ok: true }) }
  global.wx = { hideTabBar() {}, setNavigationBarColor() {}, setBackgroundColor() {} }
  component.refreshAll = () => Promise.resolve()
  component.loadBanner = () => Promise.resolve()
  component.syncMerchantView = () => {}
  try {
    await component.loginToReadProfile()
    assert.equal(logins, 1)
    assert.equal(component.data.selfReadAccess, '')
  } finally {
    app.reLogin = saved.login; global.wx = saved.wx
    component.refreshAll = saved.refresh; component.loadBanner = saved.banner; component.syncMerchantView = saved.merchant
  }
}

test('HTTP401和历史鉴权码2必须正常登录后才恢复指标读取', async () => {
  for (const code of [401, 2]) {
    const component = loadProfile()
    component.resetProfileMetricState()
    const pending = component.loadUserData()
    requests.find((r) => r.url === '/api/user/info').fail({ code: 500 }, code)
    await pending
    assert.equal(component.data.selfReadAccess, 'login')
    await resumeWithNormalLogin(component)
    requests = []
    await loadMetricSources(component, successSources())
    assert.equal(component.data.profileMetricAuthError, false)
    assert.equal(component.data.profileMetricState, 'empty')
  }
})

test('订单、项目、推文和勋章分别区分登录与网络失败，成功重试清除登录提示', async () => {
  const cases = [
    ['loadOrderPreview', '/api/registration/list', 'order', { rows: [] }],
    ['loadProjectPreview', '/api/topic/list', 'project', { rows: [], total: 0 }],
    ['getCreativesquareList', '/api/creativesquare/list', 'feed', { rows: [], total: 0 }],
    ['loadMedals', '/api/medal/wall', 'medal', { medals: [], count: 0 }],
  ]
  for (const [method, url, section, data] of cases) {
    const component = loadProfile()
    for (const status of [401, 500]) {
      requests = []
      const pending = component[method]()
      requests.find((r) => r.url === url).fail({ code: status }, status)
      await pending
      await flush()
      if (status === 401) {
        assert.equal(component.data.selfReadAccess, 'login')
        await resumeWithNormalLogin(component)
      } else {
        assert.equal(component.data[section + 'Err'], true)
        assert.equal(component.data[section + 'AuthError'], false)
      }
    }
    requests = []
    const pending = component[method]()
    respond(url, { code: 200, data })
    await pending
    await flush()
    assert.equal(component.data[section + 'AuthError'], false)
    assert.equal(component.data[section + 'Err'], false)
  }
})

test('字段缺失：成功响应缺少 totalCheckins 时仍是错误态，不能把缺失字段补成零', async () => {
  const component = loadProfile()
  await loadMetricSources(component, successSources({
    play: { level: 1, totalMileage: 0, streakDays: 0 },
  }))

  assert.equal(component.data.profileMetricState, 'error')
})

test('字段缺失：积分响应只有 weekPoints 时进入错误态，不能把缺失排名标成 ready', async () => {
  const component = loadProfile()
  await loadMetricSources(component, successSources({
    points: { weekPoints: 0 },
  }))

  assert.equal(component.data.profileMetricState, 'error')
})

function assertFirstRunMarkup(wxml, wxss) {
  assert.match(wxml, /class="pc-gamer-streak" wx:if="\{\{isSelf && profileMetricState === 'ready'\}\}">连续探索：\{\{streakDays \|\| 0\}\}天/)
  assert.match(wxml, /class="pc-stats">/)
  assert.doesNotMatch(wxml, /探索数据没加载出来|订单没加载出来/)
  assert.match(wxml, /<cy-error load-failure wx:elif="\{\{orderErr\}\}"[\s\S]*bind:retry="reloadOrders"/)
  assert.match(wxml, /<cy-error load-failure wx:if="\{\{projectErr\}\}"[\s\S]*bind:retry="reloadProjects"/)
  // 2026-10-22 owner:没有未完成订单时整段「我的订单」不显示(不再摆「暂无订单」空态)
  assert.doesNotMatch(wxml, /title="暂无订单"/)
  // 2026-10-27 owner 真机走查 M-25:商家视角没有订单段,条件前加 !isMerchantView;玩家侧口径不变
  assert.match(wxml, /<block wx:if="\{\{!isMerchantView && \(orderPreviewList\.length \|\| orderErr\)\}\}">/)
  assert.match(wxml, /class="pc-achievement-metrics" wx:if="\{\{profileMetricState === 'ready'\}\}"/)
  assert.match(wxml, /aria-label="城瘾积分 \{\{profilePoint\}\}"[\s\S]*class="pc-gs-num">\{\{profilePoint\}\}/)
  assert.match(wxml, /class="pc-rank-v">\{\{exploreStat\.shops \|\| 0\}\}家店/)
  assert.match(wxml, /wx:elif="\{\{profileMetricState === 'empty'\}\}"[^>]*title="成长记录从第一次探索开始"/)
  // UI-04(2026-09-18):这三个数字位就是要走 || 0 兜底;仍不许把 userInfo 的原值直接兜底
  // (userInfo 真源走 metricNumber,缺字段的兜底只发生在展示字段上)。
  assert.doesNotMatch(wxml, /class="pc-stat-n">\{\{userInfo\.(?:followNum|fansNum)\s*\|\|\s*0\}\}/)
  assert.doesNotMatch(wxml, /class="pc-gs-num">\{\{userInfo\.point\s*\|\|\s*0\}\}/)
  // 点击高度的保证随按钮一起搬到 cy-error 组件里(.cy-error-retry 的 height 就是 --cy-btn-h)。
  assert.doesNotMatch(wxss, /\.pc-metric-retry\s*\{/, '自绘重试按钮的样式应随标签一起删除，别留孤儿')
  const errWxss = fs.readFileSync(path.join(ROOT, 'components/cy/error/index.wxss'), 'utf8')
  assert.match(errWxss, /\.cy-error-retry\s*\{[^}]*height:\s*var\(--cy-btn-h\)/s,
    'cy-error 的重试按钮必须保持 --cy-btn-h 的可点高度')
}

test('界面契约：主页统计始终展示，订单仅在成功加载后展示空态', () => {
  assertFirstRunMarkup(read(PROFILE_WXML), read(PROFILE_WXSS))
})

test('负控：隐藏未加载统计时，契约必须变红', () => {
  const wxml = read(PROFILE_WXML)
  const mutated = wxml.replace(
    'class="pc-stats">',
    'class="pc-stats" wx:if="{{false}}">',
  )
  assert.notEqual(mutated, wxml, '负控锚点失效：没有找到首访统计闸')
  assert.throws(() => assertFirstRunMarkup(mutated, read(PROFILE_WXSS)), assert.AssertionError)
})

test('负控：把 ready 分支的真实 0 改成占位符时，契约必须精准变红', () => {
  const wxml = read(PROFILE_WXML)
  const mutated = wxml.replaceAll('{{profilePoint}}', "{{profilePoint || '—'}}")
  assert.notEqual(mutated, wxml, '负控锚点失效：没有找到 ready 分支积分')
  assert.throws(() => assertFirstRunMarkup(mutated, read(PROFILE_WXSS)), assert.AssertionError)
})

test('订单预览失败保持未加载，重试成功才允许显示真实空态，再次失败保留失败标记', async () => {
  const component = loadProfile()
  let pending = component.loadOrderPreview()
  respond('/api/registration/list', 'fail')
  await pending
  assert.equal(component.data.orderErr, true)
  requests = []
  pending = component.loadOrderPreview()
  respond('/api/registration/list', { code: 200, data: { rows: [] } })
  await pending
  assert.equal(component.data.orderErr, false)
  assert.deepEqual(component.data.orderPreviewList, [])
  requests = []
  pending = component.loadOrderPreview()
  respond('/api/registration/list', 'fail')
  await pending
  assert.equal(component.data.orderErr, true)
})

test('开发版真实空订单和空项目保持为空，不用演示卡替代本人数据', async () => {
  const originalIsDevEnv = app.isDevEnv
  app.isDevEnv = () => true
  try {
    const component = loadProfile()
    const orders = component.loadOrderPreview()
    respond('/api/registration/list', { code: 200, data: { rows: [], total: 0 } })
    await orders
    assert.deepEqual(component.data.orderPreviewList, [])
    requests = []
    const projects = component.loadProjectPreview()
    respond('/api/topic/list', { code: 200, data: { rows: [], total: 0 } })
    await projects
    assert.deepEqual(component.data.projectPreviewList, [])
  } finally {
    app.isDevEnv = originalIsDevEnv
  }
})


test('资料排名：后端无排名哨兵只显示占位，不把它当 TOP0% 或排名上升', async () => {
  for (const rank of [
    { rankPercentage: 'TOP0%', userRank: 0, totalUsers: 12 },
    { rankPercentage: 'TOP0%' },
    { rankPercentage: 'TOP20%', userRank: 0 },
  ]) {
    const component = loadProfile()
    component.data.profilePoint = 50
    const pending = component.loadPointsStat()
    respond('/api/user/points/statistics', { code: 200, data: Object.assign({ weekPoints: 0, rankDelta: 3 }, rank) })
    respond('/api/growth/leaderboard', weekExpResponse(0))
    await pending
    assert.deepEqual(component.data.rankDisplay, { no: '—', delta: '' })
    assert.equal(component.data.pointsStat.rankPercentage, rank.rankPercentage)
    assert.equal(component.data.exploreStat.weekExp, 0)
    assert.equal(component.data.profilePoint, 50)
    assert.equal(component._profileMetricSourceStates.points, 'ready')
  }
})

test('资料排名：真实 TOP20 和 TOP100 原样保留，旧契约缺 userRank 不误降为空态', async () => {
  for (const rank of [
    { rankPercentage: 'TOP20%', userRank: 1 },
    { rankPercentage: 'TOP100%', userRank: 12 },
    { rankPercentage: 'TOP20%' },
  ]) {
    const component = loadProfile()
    const pending = component.loadPointsStat()
    respond('/api/user/points/statistics', { code: 200, data: Object.assign({ weekPoints: 7, rankDelta: 2 }, rank) })
    respond('/api/growth/leaderboard', weekExpResponse(13))
    await pending
    assert.deepEqual(component.data.rankDisplay, { no: rank.rankPercentage, delta: '上升 2' })
    assert.equal(component.data.exploreStat.weekExp, 13, '本周经验来自经验周榜，不是周积分')
  }
})

test('资料排名：重复正常刷新稳定，网络或业务失败保留最近有效排名快照', async () => {
  const component = loadProfile()
  for (let i = 0; i < 2; i++) {
    requests = []
    const pending = component.loadPointsStat()
    respond('/api/user/points/statistics', { code: 200, data: { weekPoints: 9, rankPercentage: 'TOP20%', userRank: 1, rankDelta: 2 } })
    respond('/api/growth/leaderboard', weekExpResponse(11))
    await pending
    assert.deepEqual(component.data.rankDisplay, { no: 'TOP20%', delta: '上升 2' })
  }
  for (const response of ['fail', { code: 500, msg: '读取失败' },
    { code: 200, data: { weekPoints: null, rankPercentage: 'TOP0%', userRank: 0 } }]) {
    requests = []
    const pending = component.loadPointsStat()
    respond('/api/user/points/statistics', response)
    respond('/api/growth/leaderboard', weekExpResponse(11))
    await pending
    assert.deepEqual(component.data.rankDisplay, { no: 'TOP20%', delta: '上升 2' })
    assert.equal(component.data.exploreStat.weekExp, 11)
    assert.equal(component._profileMetricSourceStates.points, 'error')
  }
})

test('本周EXP：积分7与EXP13分别展示，累计积分50不改写', async () => {
  const component = loadProfile()
  component.data.profilePoint = 50
  const pending = component.loadPointsStat()
  respond('/api/user/points/statistics', { code: 200, data: { weekPoints: 7, rankPercentage: 'TOP20%', userRank: 1 } })
  respond('/api/growth/leaderboard', weekExpResponse(13))
  await pending
  assert.equal(component.data.pointsStat.weekPoints, 7)
  assert.equal(component.data.exploreStat.weekExp, 13)
  assert.equal(component.data.profilePoint, 50)
  const request = requests.find(request => request.url === '/api/growth/leaderboard')
  assert.equal(request.method, 'POST')
  assert.deepEqual(request.data, { metric: 'exp', period: 'week', limit: 1 })
})

for (const [name, response] of [
  ['网络失败', 'fail'],
  ['业务错误', { code: 500 }],
  ['缺少本人', { code: 200, data: { metric: 'exp', period: 'week', me: null } }],
  ['错误身份', weekExpResponse(99, '43')],
  ['错误指标', { code: 200, data: Object.assign({}, weekExpResponse(99).data, { metric: 'point' }) }],
  ['错误周期', { code: 200, data: Object.assign({}, weekExpResponse(99).data, { period: 'total' }) }],
  ['缺少分数', { code: 200, data: { metric: 'exp', period: 'week', me: { memberId: '42' } } }],
  ['空分数', weekExpResponse(null)],
  ['负分数', weekExpResponse(-1)],
  ['非安全分数', weekExpResponse(Number.MAX_SAFE_INTEGER + 1)],
  ['小数', weekExpResponse(1.5)],
  ['字符串分数', weekExpResponse('13')],
  ['布尔分数', weekExpResponse(false)],
]) test(`本周EXP：${name}保留EXP快照且积分独立成功`, async () => {
  const component = loadProfile()
  component.data.exploreStat.weekExp = 13
  const pending = component.loadPointsStat()
  respond('/api/user/points/statistics', { code: 200, data: { weekPoints: 7, rankPercentage: 'TOP20%', userRank: 1 } })
  respond('/api/growth/leaderboard', response)
  await pending
  assert.equal(component.data.pointsStat.weekPoints, 7)
  assert.equal(component.data.rankDisplay.no, 'TOP20%')
  assert.equal(component.data.exploreStat.weekExp, 13)
  assert.equal(component._profileMetricSourceStates.points, 'error')
  assert.equal(component.data.profileMetricAuthError, false)
})

test('本周EXP：经验周榜登录失效按本人读取守卫进入登录态，不保留旧积分或EXP', async () => {
  const component = loadProfile()
  component.data.exploreStat.weekExp = 13
  const pending = component.loadPointsStat()
  respond('/api/user/points/statistics', { code: 200, data: { weekPoints: 7, rankPercentage: 'TOP20%', userRank: 1 } })
  respond('/api/growth/leaderboard', { code: 401 })
  await pending
  assert.equal(component.data.selfReadAccess, 'login')
  assert.notEqual(component.data.pointsStat && component.data.pointsStat.weekPoints, 7)
})

test('本周EXP：首次缺失进入指标错误态，不声称真实0', async () => {
  const component = loadProfile()
  const sources = successSources({ play: { level: 1, totalCheckins: 1, totalMileage: 0, streakDays: 0 } })
  sources['/api/growth/leaderboard'] = { code: 200, data: { metric: 'exp', period: 'week', me: { memberId: '42' } } }
  await loadMetricSources(component, sources)
  assert.equal(component.data.profileMetricState, 'error')
  assert.equal(component.data.pointsStat.weekPoints, 0)
})

test('本周EXP：真实0且无排名有效，不拿榜首或积分替代', async () => {
  const component = loadProfile()
  const reply = weekExpResponse()
  reply.data.list = [{ memberId: '43', score: 99, rank: 1 }]
  const pending = component.loadPointsStat()
  respond('/api/user/points/statistics', { code: 200, data: { weekPoints: 7, rankPercentage: 'TOP20%', userRank: 1 } })
  respond('/api/growth/leaderboard', reply)
  await pending
  assert.equal(component.data.exploreStat.weekExp, 0)
  assert.equal(component.data.pointsStat.weekPoints, 7)
  assert.equal(component._profileMetricSourceStates.points, 'ready')
})

test('本周EXP：积分失败保留积分快照，EXP独立成功', async () => {
  const component = loadProfile()
  component.data.pointsStat = { weekPoints: 7, rankPercentage: 'TOP20%' }
  const pending = component.loadPointsStat()
  respond('/api/user/points/statistics', 'fail')
  respond('/api/growth/leaderboard', weekExpResponse(13))
  await pending
  assert.deepEqual(component.data.pointsStat, { weekPoints: 7, rankPercentage: 'TOP20%' })
  assert.equal(component.data.exploreStat.weekExp, 13)
  assert.equal(component._profileMetricSourceStates.points, 'error')
})

test('本周EXP：他人视角不发本人指标请求', async () => {
  const component = loadProfile()
  component.data.isSelf = false
  await component.loadPointsStat()
  assert.equal(requests.length, 0)
})
