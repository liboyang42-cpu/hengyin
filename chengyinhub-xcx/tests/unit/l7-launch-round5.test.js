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
