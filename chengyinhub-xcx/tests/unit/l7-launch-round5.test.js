// walk-fix 2026-11-03 Round 5(l7-launch):上线前检查修复。每条跑真代码或钉住绑定,并带负控。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

function mutated(source, from, to) {
  assert.ok(source.includes(from), '负控锚点失效: ' + from)
  return source.replace(from, to)
}

// 在 vm 里跑页面真源码;页面 require 走 Node 真模块。每次 mount 清掉相关模块缓存,免得单例(roleGuard)串状态。
function mount(rel, options) {
  const opts = options || {}
  const file = path.join(ROOT, rel)
  const requests = []
  const storage = Object.assign({}, opts.storage || {})
  const app = Object.assign({
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => '',
    getUserType: () => '',
    getUserRole: () => 'player',
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    getPageSize: () => 10,
    getImgUrl: (name) => '/images/' + name,
    getTotalPage: (total, size) => Math.ceil(Number(total || 0) / size),
    sendRequest(request) { requests.push(request); return { abort() {} } },
  }, opts.app || {})
  const wx = Object.assign({
    getStorageSync: (k) => storage[k] === undefined ? '' : storage[k],
    setStorageSync: (k, v) => { storage[k] = v },
    removeStorageSync: (k) => { delete storage[k] },
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
    getWindowInfo: () => ({ windowWidth: 375, statusBarHeight: 20 }),
    getMenuButtonBoundingClientRect: () => ({ left: 280, top: 24, bottom: 56, height: 32 }),
    showToast() {}, hideToast() {}, showLoading() {}, hideLoading() {}, showModal() {},
    navigateTo() {}, navigateBack() {}, switchTab() {}, reLaunch() {}, redirectTo() {},
    stopPullDownRefresh() {}, nextTick: (fn) => fn(),
  }, opts.wx || {})
  let definition
  const previous = { getApp: global.getApp, wx: global.wx, getCurrentPages: global.getCurrentPages }
  global.getApp = () => app
  global.wx = wx
  global.getCurrentPages = () => []
  for (const key of Object.keys(require.cache)) {
    if (key.startsWith(path.join(ROOT, 'utils')) || key.startsWith(path.join(ROOT, 'pages'))) delete require.cache[key]
  }
  vm.runInNewContext(opts.source || fs.readFileSync(file, 'utf8'), {
    Page: (value) => { definition = value }, Component: (value) => { definition = value },
    getApp: () => app, getCurrentPages: () => [], wx, require: createRequire(file), console, setTimeout, clearTimeout, Promise,
  }, { filename: file })
  const host = Object.assign({}, definition, definition.methods || {})
  host.data = JSON.parse(JSON.stringify(definition.data || {}))
  host.setData = function (patch, cb) {
    for (const k of Object.keys(patch)) {
      if (k.includes('.') || k.includes('[')) continue
      this.data[k] = patch[k]
    }
    if (typeof cb === 'function') cb()
  }
  host.selectComponent = () => null
  host.triggerEvent = () => {}
  host.createSelectorQuery = () => ({ select() { return this }, boundingClientRect() { return this }, selectViewport() { return this }, scrollOffset() { return this }, exec(cb) { cb && cb([]) } })
  return { host, requests, app, wx, storage, restore() { Object.assign(global, previous) } }
}

const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve() }

// ---------- #1 游客进 club/apply ----------

test('#1 club/apply 游客:登录引导(no-permission + 登录),登录后重查资格;已登录读不到快照仍是「重新检查」', async () => {
  let user = ''
  const loginCalls = []
  const m = mount('pages/club/apply/index.js', { app: {
    getUserID: () => user,
    firstLogin: () => { loginCalls.push('first'); user = '7'; return Promise.resolve({ ok: true }) },
  } })
  try {
    m.host.startBootstrap()
    const role = m.requests.find((r) => r.url === '/api/role/info')
    role.fail({ code: 401 })
    assert.equal(m.host.data.bootstrapState, 'error')
    assert.equal(m.host.data.bootstrapErrorKind, 'no-permission')
    m.host.retryBootstrap()
    await flush()
    assert.deepEqual(loginCalls, ['first'])
    assert.equal(m.host.data.bootstrapState, 'checking-role', '登录后续上资格检查')
    assert.equal(m.requests.filter((r) => r.url === '/api/role/info').length, 2)
  } finally { m.restore() }

  const member = mount('pages/club/apply/index.js', { app: { getUserID: () => '7' } })
  try {
    member.host.startBootstrap()
    const role = member.requests.find((r) => r.url === '/api/role/info')
    role.fail({ code: 500 })
    assert.equal(member.host.data.bootstrapErrorKind, '')
  } finally { member.restore() }

  const wxml = read('pages/club/apply/index.wxml')
  assert.match(wxml, /kind="\{\{bootstrapErrorKind \|\| 'data'\}\}"[\s\S]*?primary="\{\{bootstrapErrorKind === 'no-permission' \? '登录' : '重新检查'\}\}" bind:primary="retryBootstrap"/)
  // 负控:去掉游客判定 → 游客回到不可能成功的「重新检查」
  const js = read('pages/club/apply/index.js')
  assert.ok(mutated(js, "bootstrapErrorKind: guest ? 'no-permission' : '',", "bootstrapErrorKind: '',"))
})

// ---------- #2 游客进发现页帖文流 ----------

test('#2 talent/list 游客帖文流失败给登录入口(与俱乐部列表同款);已登录失败仍是「动态加载失败」', async () => {
  for (const [userId, expectLogin] of [['', true], ['7', false]]) {
    let user = userId
    const loginCalls = []
    const m = mount('pages/talent/list/index.js', { app: {
      getUserID: () => user,
      firstLogin: () => { loginCalls.push('first'); user = '7'; return Promise.resolve({ ok: true }) },
    } })
    try {
      m.host.loadFeed()
      await flush()
      const feed = m.requests.find((r) => r.url === '/api/club/post/feed')
      assert.ok(feed, '应请求 /api/club/post/feed')
      feed.fail({ code: userId ? 500 : 401, msg: '服务异常' })
      await flush()
      assert.equal(m.host.data.feedState, 'error')
      assert.equal(m.host.data.feedLoginRequired, expectLogin)
      if (expectLogin) {
        m.host.loadClubHome = function () {}
        m.host.retryFeed()
        await flush()
        assert.deepEqual(loginCalls, ['first'])
        assert.equal(m.requests.filter((r) => r.url === '/api/club/post/feed').length, 2, '登录后重读帖文')
      }
    } finally { m.restore() }
  }
  const wxml = read('pages/talent/list/index.wxml')
  assert.match(wxml, /load-failure="\{\{!feedLoginRequired\}\}"[^>]*title="\{\{feedLoginRequired \? '登录后查看帖文' : '动态加载失败'\}\}"[^>]*retry="\{\{feedLoginRequired \? '登录' : '重试'\}\}" bind:retry="retryFeed"/)
})

// ---------- #4 屏上不出后端代码 ----------

function loadWxs(rel) {
  const module = { exports: {} }
  vm.runInNewContext(read(rel), { module })
  return module.exports
}

test('#4 display-label.wxs:偏好标签 / 候补 / 声音请求 / 签到状态都出中文,未知代码不回显原值', () => {
  const dl = loadWxs('utils/wxs/display-label.wxs')
  assert.equal(dl.prefTag('space_constraints'), '空间条件')
  assert.equal(dl.prefTag('social_mode'), '社交方式')
  assert.equal(dl.prefTag('arrival_window'), '到店时段')
  assert.equal(dl.prefTag('expression_mode'), '表达方式')
  assert.equal(dl.prefTag('private_profile'), '偏好')
  assert.equal(dl.waitlist('WAITING'), '候补排队中')
  assert.equal(dl.waitlist('OFFERED'), '名额已发放，待支付')
  assert.equal(dl.waitlist('CLAIMED'), '已生成报名订单')
  assert.equal(dl.waitlist('SOMETHING'), '候补中')
  assert.equal(dl.attendance('ISSUED'), '未签到')
  assert.equal(dl.attendance('CHECKED_IN'), '已签到')
  for (const v of ['X_CODE', undefined, '']) {
    for (const fn of ['prefTag', 'waitlist', 'voice', 'attendance']) assert.doesNotMatch(String(dl[fn](v)), /[A-Z_]{3,}|undefined/)
  }
  // 声音请求两张表一致(JS 文案与 WXML 展示同口径)
  const journal = require('../../utils/voice-enrollment-journal.js')
  for (const [state, label] of Object.entries(journal.STATE_LABELS)) assert.equal(dl.voice(state), label)
  assert.equal(journal.stateLabel('WHATEVER'), '状态待确认')
})

test('#4 WXML 绑定走中文映射(play 偏好标签、候补名册、声音请求、签到、暂停原因)', () => {
  const play = read('pages/play/index.wxml')
  assert.match(play, /<wxs src="\.\.\/\.\.\/utils\/wxs\/display-label\.wxs" module="dl" \/>/)
  assert.doesNotMatch(play, /\{\{(?:item|preference\.pendingTag)\.tagCode\}\}/, 'tagCode 不能原样上屏')
  assert.equal((play.match(/dl\.prefTag\(/g) || []).length, 3)
  const ops = read('pages/club/event-ops/index.wxml')
  assert.match(ops, /\{\{dl\.waitlist\(item\.state\)\}\}/)
  assert.doesNotMatch(ops, /\{\{item\.state\}\}/)
  for (const f of ['pages/merchant/decor/ai-npc/index.wxml', 'pages/topic/components/cy/node-npc-form/index.wxml']) {
    assert.match(read(f), /原声音请求 · \{\{dl\.voice\(item\.state\)\}\}/)
  }
  assert.match(read('components/cy/event-attendance/index.wxml'), /\{\{dl\.attendance\(result\.data\.state\)\}\}/)
  assert.doesNotMatch(read('pages/merchant/game-node/index.wxml'), /pauseReasonCode/)
  // 每个用 dl. 的 wxml 都要引入模块,路径真实存在
  for (const f of ['pages/play/index.wxml', 'pages/club/event-ops/index.wxml', 'pages/merchant/decor/ai-npc/index.wxml',
    'pages/topic/components/cy/node-npc-form/index.wxml', 'components/cy/event-attendance/index.wxml']) {
    const src = read(f).match(/<wxs src="([^"]+display-label\.wxs)" module="dl" \/>/)
    assert.ok(src, f + ' 缺 dl 模块')
    assert.ok(fs.existsSync(path.join(ROOT, path.dirname(f), src[1])), f + ' dl 路径不存在')
  }
})

test('#4 JS 兜底不再回显原始代码:官方收件箱角色/状态、主题交易状态、声音请求结果、通用玩法运行状态', () => {
  const inbox = read('pages/activity/official-inbox/index.js')
  assert.match(inbox, /const role = ROLE\[item\.role\] \|\| '—';/)
  assert.match(inbox, /_status: STATUS\[item\.status\] \|\| '状态待确认',/)
  assert.doesNotMatch(inbox, /\|\| item\.(?:role|status) \|\|/)
  const trade = require('../../pages/play/utils/theme-trade.js')
  const src = read('pages/play/utils/theme-trade.js')
  assert.doesNotMatch(src, /\[row\.status\] \|\| row\.status/)
  assert.ok(trade)
  for (const f of ['pages/merchant/decor/ai-npc/index.js', 'pages/topic/components/cy/node-npc-form/index.js']) {
    assert.match(read(f), /'原请求状态：' \+ voiceJournal\.stateLabel\(value\.voiceIntentState\)/)
  }
  const director = read('pages/club/topic-detail/director.js')
  const fnSrc = director.match(/const GENERIC_OPS_STATUS[\s\S]*?\n}\n/)[0]
  const genericOpsStatusText = vm.runInNewContext(fnSrc + ';genericOpsStatusText')
  assert.equal(genericOpsStatusText({ operationStatus: 'PAUSED', authorityMode: 'MANUAL', authorityEpoch: 3 }), '已暂停 · 人工接管 · 权威 3')
  assert.doesNotMatch(genericOpsStatusText({ operationStatus: 'NEW_STATE', authorityMode: 'X' }), /[A-Z_]{2,}/)
  assert.match(director, /genericOpsStatus: this\._genericControl \? genericOpsStatusText\(this\._genericControl\.run\) : ''/)
})

// ---------- #5 灰色「立即报名」 ----------

function loadDetail(tickets) {
  const scrolls = []
  const m = mount('subpackageMember/components/cy/scene-play-activity-detail/index.js', {
    app: { getUserID: () => '7', getUserType: () => 1 },
    wx: { pageScrollTo: (opts) => scrolls.push(opts) },
  })
  m.host.data.activityId = '5'
  m.host._activityReadIdentity = () => '7'
  m.host._activityReadCurrent = () => true
  m.host.createSelectorQuery = () => {
    const q = { select() { return q }, boundingClientRect() { return q }, selectViewport() { return q }, scrollOffset() { return q },
      exec(cb) { cb([{ top: 600 }, { scrollTop: 100 }]) } }
    return q
  }
  m.host.load('5')
  const req = m.requests.find((r) => r.url === '/api/activity/info')
  req.success({ code: 200, data: { id: 5, name: '城市夜跑', isSignUp: 0, memberId: 9, omsTicketList: tickets,
    collaboratorsList: [], sysCategoryList: [], registrationList: [], memberTemplateList: [], commentList: [] } })
  return Object.assign(m, { scrolls })
}

test('#5 只有一张可报名的票:自动选中,「立即报名」可点', () => {
  const m = loadDetail([{ id: 11, name: '标准票', price: 0, remainingInventory: 3 }, { id: 12, name: '早鸟', price: 0, remainingInventory: 0 }])
  try {
    assert.equal(m.host.data.state, 'ready')
    assert.equal(m.host.data.selectedTicketId, '11')
    assert.equal(m.host.data.canConfirmSignup, true)
    assert.equal(m.host.data.noTicketText, '')
  } finally { m.restore() }
})

test('#5 多张票未选:点灰按钮滚到票种并标红线;选票后红线消失', () => {
  const m = loadDetail([{ id: 11, name: 'A', price: 0, remainingInventory: 3 }, { id: 12, name: 'B', price: 0 }])
  try {
    assert.equal(m.host.data.selectedTicketId, '')
    assert.equal(m.host.data.canConfirmSignup, false)
    m.host.onSignupDisabledTap()
    assert.equal(m.host.data.ticketsAttention, true)
    assert.equal(m.scrolls.length, 1)
    assert.equal(m.scrolls[0].scrollTop, 580)
    m.host.selectTicket({ currentTarget: { dataset: { id: '12' } } })
    assert.equal(m.host.data.ticketsAttention, false)
    assert.equal(m.host.data.canConfirmSignup, true)
  } finally { m.restore() }
})

test('#5 没有可报名的票:按钮直接说明,不滚动不标红', () => {
  for (const [tickets, text] of [[[], '暂未开放票种'], [[{ id: 1, name: 'A', price: 0, remainingInventory: 0 }], '暂无可报名票种']]) {
    const m = loadDetail(tickets)
    try {
      assert.equal(m.host.data.noTicketText, text)
      m.host.onSignupDisabledTap()
      assert.equal(m.host.data.ticketsAttention, false)
      assert.equal(m.scrolls.length, 0)
    } finally { m.restore() }
  }
  const wxml = read('subpackageMember/components/cy/scene-play-activity-detail/index.wxml')
  assert.match(wxml, /disabled="\{\{!canConfirmSignup\}\}" bindtap="openSignup" bind:disabledtap="onSignupDisabledTap">\{\{noTicketText \|\| '立即报名'\}\}<\/cy-btn>/)
  assert.match(wxml, /class="ticket-list \{\{ticketsAttention \? 'ticket-list--attention' : ''\}\}"/)
  assert.match(read('subpackageMember/components/cy/scene-play-activity-detail/index.wxss'), /\.ticket-list--attention \.ticket:not\(\.ticket--disabled\) \{ border-color: var\(--cy-color-status-danger\); \}/)
})

// ---------- #6 活动详情游客可浏览,报名前登录 ----------

test('#6 游客点「立即报名」先走写前登录(signup),登录后续上;已登录直接进结算', () => {
  const resumes = []
  const navigations = []
  const m = mount('subpackageMember/components/cy/scene-play-activity-detail/index.js', {
    app: { getUserID: () => '', getUserType: () => 1,
      ensureLoginFor: (reason, resume) => { resumes.push(reason); return false } },
    wx: { navigateTo: (o) => navigations.push(o.url) },
  })
  try {
    m.host.data.activityId = '5'
    m.host.setData({ info: { memberId: 9, isSignUp: 0 }, tickets: [{ _id: '11', id: 11, _available: true }], selectedTicketId: '11', canConfirmSignup: true })
    m.host.openSignup()
    assert.deepEqual(resumes, ['signup'])
    assert.deepEqual(navigations, [])
  } finally { m.restore() }
  const member = mount('subpackageMember/components/cy/scene-play-activity-detail/index.js', {
    app: { getUserID: () => '7', getUserType: () => 1, ensureLoginFor: () => true },
    wx: { navigateTo: (o) => navigations.push(o.url) },
  })
  try {
    member.host.data.activityId = '5'
    member.host.setData({ info: { memberId: 9, isSignUp: 0 }, tickets: [{ _id: '11', id: 11, _available: true }], selectedTicketId: '11', canConfirmSignup: true })
    member.host.openSignup()
    assert.deepEqual(navigations, ['/pages/activity/baoming/baoming?activityId=5&ticketId=11'])
  } finally { member.restore() }
})

// ---------- #9 本局线索入口 ----------

test('#9 「更多」里有本局线索入口(仅剧情会话投影启用时),点开即打开原线索半屏', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /<view class="drow" wx:if="\{\{gameModule\.enabled\}\}" bindtap="openGameModuleFromTools"[^>]*aria-label="本局线索"[^>]*>.*<text class="dt">本局线索<\/text>/)
  const js = read('pages/play/index.js')
  const fn = js.match(/\n  openGameModuleFromTools\(\) \{([^\n]*)\},/)
  assert.ok(fn, '缺 openGameModuleFromTools')
  const calls = []
  const host = { setData(patch) { calls.push(patch) }, openGameModule() { calls.push('open') } }
  vm.runInNewContext('(function(){' + fn[1] + '})', {}).call(host)
  assert.deepEqual(JSON.parse(JSON.stringify(calls)), [{ sessionToolsOpen: false }, 'open'])
  // ROUTE 会话不启用投影 → 入口不出现
  assert.match(js, /result\.data\.player\.playMode === 'ROUTE'\) \{[\s\S]{0,300}?this\.setData\(\{ 'gameModule\.loading': false, 'gameModule\.enabled': false,/)
})

test('#9 线索半屏节点状态与队友角色不出原始代码', () => {
  const dl = loadWxs('utils/wxs/display-label.wxs')
  const NODE_STATES = ['LOCKED', 'AVAILABLE', 'ARRIVED', 'IN_PROGRESS', 'AWAITING_VERIFICATION', 'COMPLETED', 'FALLBACK_COMPLETED',
    'INVITED', 'ACCEPTED', 'READY', 'ACTIVE', 'PAUSED', 'CLOSED']
  for (const s of NODE_STATES) assert.doesNotMatch(dl.nodeStatus(s), /[A-Z_]{2,}|待确认/, s)
  assert.equal(dl.nodeStatus('WHAT'), '状态待确认')
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /<text class="gmod__status" wx:else>\{\{dl\.nodeStatus\(item\.status\)\}\}<\/text>/)
  assert.doesNotMatch(wxml, /\{\{item\.roleCode\}\}|\{\{item\.fallback\.planCode\}\}/)
  const { normalizePlayerProjection } = require('../../pages/play/utils/player-game-module.js')
  const p = normalizePlayerProjection({ perspective: 'PLAYER', sessionId: 1, activityId: 2, revision: 1, status: 'RUNNING',
    player: { role: { code: 'detective', name: '侦探' }, teamActions: [
      { memberId: 3, displayName: '甲', roleCode: 'detective', status: 'CONFIRMED' },
      { memberId: 4, displayName: '乙', roleCode: 'witness_2', status: 'JOINED' }] } })
  assert.equal(p.teamActions[0].roleName, '侦探')
  assert.equal(p.teamActions[1].roleName, undefined)
})
