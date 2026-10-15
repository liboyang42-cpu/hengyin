const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const PROFILE_JS = path.join(ROOT, 'subpackageMember/components/cy/profile/index.js')
const PROFILE_WXML = path.join(ROOT, 'subpackageMember/components/cy/profile/index.wxml')
const PROFILE_WXSS = path.join(ROOT, 'subpackageMember/components/cy/profile/index.wxss')
const read = (file) => fs.readFileSync(file, 'utf8')

let componentDefinition
let requests

const app = {
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  getUserID: () => '42',
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
  require(PROFILE_JS)
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

test('主理人资格查询失败留在原页，成功重试按归属跳转', async () => {
  const previous = { wx: global.wx, getApp: global.getApp, getCurrentPages: global.getCurrentPages, message: app.getRequestErrorMessage }
  const { safeUserMessage } = require('../../utils/transport/safe-user-message.js')
  const cases = [
    [r => r.fail({ errMsg: 'request:fail timeout' }), null],
    [r => r.fail({ errMsg: 'request:fail net::ERR_INTERNET_DISCONNECTED' }), null],
    [r => r.success({ code: 500, msg: '查询失败' }), null],
    [r => r.fail({}, 401), null],
    [r => r.success({ code: 200, data: { owned: [] } }), '/pages/club/apply/index'],
    [r => r.success({ code: 200, data: { owned: [{ id: 7002 }] } }), '/pages/club/detail/index?id=7002'],
  ]
  try {
    app.getRequestErrorMessage = (res, fallback) => safeUserMessage(res, fallback)
    global.getApp = () => app
    global.getCurrentPages = () => []
    for (const [reply, destination] of cases) {
      const component = loadProfile()
      const navigation = [], messages = []
      let shown = 0, hidden = 0
      global.wx = { getStorageSync: () => '', showLoading() { shown++ }, hideLoading() { hidden++ },
        showToast({ title }) { messages.push(title) }, navigateTo({ url }) { navigation.push(url) } }
      component.goBecomeTalent()
      reply(requests.find(r => r.url === '/api/club/my'))
      await flush()
      assert.deepEqual(navigation, destination ? [destination] : [])
      assert.equal(shown, 1)
      assert.equal(hidden, 1)
      if (!destination) assert.ok(messages.length > 0)
    }
  } finally {
    global.wx = previous.wx
    global.getApp = previous.getApp
    global.getCurrentPages = previous.getCurrentPages
    app.getRequestErrorMessage = previous.message
  }
})

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
  assert.equal(component.data.friendNum, 0, '字段缺失按 UI-04 兜底成 0')
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

test('HTTP401和历史鉴权码2保留登录失败原因，指标成功重读后清除', async () => {
  for (const code of [401, 2]) {
    const component = loadProfile()
    component.resetProfileMetricState()
    const pending = component.loadUserData()
    requests.find((r) => r.url === '/api/user/info').fail({ code: 500 }, code)
    await pending
    assert.equal(component.data.profileMetricAuthError, true)
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
    ['getCreativesquareList', '/api/v1/community/me/posts', 'feed', { items: [], hasMore: false, nextCursor: null }],
    ['loadMedals', '/api/medal/wall', 'medal', { medals: [], count: 0 }],
  ]
  for (const [method, url, section, data] of cases) {
    const component = loadProfile()
    if (section === 'feed') {
      global.wx = { getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {} }
      const initialize = component.initializeProfile, syncMotion = component._syncProfileReducedMotion
      component.initializeProfile = () => {}
      component._syncProfileReducedMotion = () => {}
      componentDefinition.lifetimes.attached.call(component)
      component.initializeProfile = initialize
      component._syncProfileReducedMotion = syncMotion
    }
    for (const status of [401, 500]) {
      requests = []
      const pending = component[method]()
      await flush()
      requests.find((r) => r.url === url).fail({ code: status }, status)
      await pending
      await flush()
      assert.equal(component.data[section + 'Err'], true)
      assert.equal(component.data[section + 'AuthError'], status === 401)
    }
    requests = []
    const pending = component[method]()
    await flush()
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
  assert.match(wxml, /<cy-error wx:elif="\{\{orderErr\}\}"[\s\S]*bind:retry="reloadOrders"/)
  assert.match(wxml, /<cy-error wx:if="\{\{projectErr\}\}"[\s\S]*bind:retry="reloadProjects"/)
  assert.match(wxml, /wx:elif="\{\{orderLoaded && !orderErr\}\}" title="暂无订单"/)
  assert.match(wxml, /class="pc-achievement-metrics" wx:if="\{\{profileMetricState === 'ready'\}\}"/)
  assert.match(wxml, /aria-label="城瘾积分 \{\{profilePoint\}\}"[\s\S]*class="pc-gs-num">\{\{profilePoint\}\}/)
  assert.match(wxml, /class="pc-rank-v">\{\{exploreStat\.shops \|\| 0\}\}家店/)
  assert.match(wxml, /wx:elif="\{\{profileMetricState === 'empty'\}\}"[^>]*title="成长记录从第一次探索开始"/)
  // UI-04(2026-09-18):这三个数字位就是要走 || 0 兜底;仍不许把 userInfo 的原值直接兜底
  // (userInfo 真源走 metricNumber,缺字段的兜底只发生在展示字段上)。
  assert.doesNotMatch(wxml, /class="pc-stat-n">\{\{userInfo\.(?:followNum|fansNum)\s*\|\|\s*0\}\}/)
  assert.doesNotMatch(wxml, /class="pc-gs-num">\{\{userInfo\.point\s*\|\|\s*0\}\}/)
  // 点击高度的保证随按钮一起搬到 cy-error 组件里(.cy-error-retry 的 height 就是 --cy-btn-h)。
  assert.doesNotMatch(wxss, /\.pc-metric-retry\s*\{/, '自绘重试按钮的样式应随标签一起删除,别留孤儿')
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
  assert.equal(component.data.orderLoaded, false)
  let pending = component.loadOrderPreview()
  respond('/api/registration/list', 'fail')
  await pending
  assert.equal(component.data.orderLoaded, false)
  assert.equal(component.data.orderErr, true)
  requests = []
  pending = component.loadOrderPreview()
  respond('/api/registration/list', { code: 200, data: { rows: [] } })
  await pending
  assert.equal(component.data.orderLoaded, true)
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
  ['登录失效', { code: 401 }],
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
  assert.equal(component.data.profileMetricAuthError, name === '登录失效')
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

test('本周EXP：发起账号变化后旧积分与EXP回包均不覆盖当前快照', async () => {
  const component = loadProfile()
  const originalGetUserID = app.getUserID
  const snapshot = structuredClone(component.data)
  const pending = component.loadPointsStat()
  try {
    app.getUserID = () => '43'
    respond('/api/user/points/statistics', { code: 200, data: { weekPoints: 7, rankPercentage: 'TOP20%', userRank: 1 } })
    respond('/api/growth/leaderboard', weekExpResponse(13, '42'))
    await pending
    assert.deepEqual(component.data, snapshot)
  } finally {
    app.getUserID = originalGetUserID
  }
})
