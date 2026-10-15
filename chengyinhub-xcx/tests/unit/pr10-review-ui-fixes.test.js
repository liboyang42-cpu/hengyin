// PR #10 DevTools 复查(2026-10-15)小程序侧修复:F-W5-11 / F-W5-10 / F-W1-12·F-R2U-01 / F-W5-08 / F-L3-09 / F-L5C-06 / 成长中心头像。
// 每条:行为(跑真代码)或展示绑定 + 负控(把修复改回去必须真红)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const { isLoginRequired } = require('../../utils/session/login-reload.js')

function rule(css, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matched = css.match(new RegExp('(?:^|\\n|\\})\\s*' + escaped + '\\s*\\{([^}]*)\\}'))
  assert.ok(matched, '缺少样式规则 ' + selector)
  return matched[1]
}

function mutated(source, from, to) {
  assert.ok(source.includes(from), '负控锚点失效: ' + from)
  return source.replace(from, to)
}

// 在 vm 里跑页面/组件真源码;页面 require 走 Node 真模块(相对页面文件解析)。
function mount(rel, options) {
  const opts = options || {}
  const file = path.join(ROOT, rel)
  const requests = []
  const app = Object.assign({
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => '',
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    getPageSize: () => 10,
    getImgUrl: (name) => '/images/' + name,
    getTotalPage: (total, size) => Math.ceil(Number(total || 0) / size),
    sendRequest(request) { requests.push(request); return { abort() {} } },
  }, opts.app || {})
  const wx = Object.assign({
    getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
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
  try {
    vm.runInNewContext(opts.source || fs.readFileSync(file, 'utf8'), {
      Page: (value) => { definition = value }, Component: (value) => { definition = value },
      getApp: () => app, getCurrentPages: () => [], wx, require: createRequire(file), console, setTimeout, clearTimeout, Promise,
    }, { filename: file })
  } finally { Object.assign(global, previous) }
  const host = Object.assign({}, definition, definition.methods || {})
  host.data = JSON.parse(JSON.stringify(definition.data || {}))
  host.setData = function (patch, cb) { Object.assign(this.data, patch); if (typeof cb === 'function') cb() }
  host.selectComponent = () => null
  host.triggerEvent = () => {}
  return { host, requests, app }
}

const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve() }

// ---------- F-W5-11 俱乐部管理 tab 快捷区:原生分享 button 不能撑成 184px ----------

test('F-W5-11 快捷区复位规则带 .quick-acts 祖先,压得过微信内置 wx-button:not([size=mini]) 的 184px', () => {
  const css = read('pages/club/detail/index.wxss')
  const reset = rule(css, '.quick-acts .quick-act')
  for (const decl of [/width:\s*100%/, /min-width:\s*0/, /margin:\s*0/, /padding:\s*0/, /line-height:\s*normal/, /background:\s*transparent/]) {
    assert.match(reset, decl)
  }
  assert.match(rule(css, '.quick-acts .quick-act::after'), /border:\s*0/)
  // 负控:裸 .quick-act 特异度 (0,1,0) 输给内置 (0,1,1),回退成裸类名必须判红
  assert.doesNotMatch(css, /(?:^|\n|\})\s*\.quick-act\s*\{/, '裸 .quick-act 复位会被内置 button 宽度吃掉')
  const wxml = read('pages/club/detail/index.wxml')
  const row = wxml.match(/<view class="quick-acts">([\s\S]*?)\n {6}<\/view>/)
  assert.ok(row, '缺少快捷区')
  assert.match(row[1], /<button class="quick-act" open-type="share"/, '分享格仍是原生分享按钮,且在 .quick-acts 里')
  assert.match(rule(css, '.quick-acts'), /repeat\(5, minmax\(0, 1fr\)\)/)
})

// ---------- F-W5-10 主题详情吸顶后返回箭头 ----------

test('F-W5-10 吸顶后最上层的 topbar 返回键取主文字色(cy-icon 吃 currentColor),不是原生 button 黑字', () => {
  const css = read('pages/topic/index/index.wxss')
  const solid = rule(css, '.topbar.solid .titBar .back button')
  assert.match(solid, /background:\s*var\(--cy-bg-page\)/)
  assert.match(solid, /color:\s*var\(--cy-color-text-primary\)/)
  // 层级证据:topbar 压在吸顶条上面,所以修 .sticky-back 不够,必须修这一枚
  assert.match(rule(css, '.topbar'), /z-index:\s*100/)
  assert.match(rule(css, '.sticky-tab-wrapper.sticky'), /z-index:\s*var\(--cy-z-sticky\)/)
  assert.match(read('style/tokens.wxss'), /--cy-z-sticky:\s*85;/)
  assert.match(read('pages/topic/index/index.wxml'), /<view wx:else class="topbar \{\{isTabSticky \? 'solid' : ''\}\}">/)
  assert.match(read('components/cy/icon/index.wxss'), /background-color:\s*currentColor/)
  // 负控:去掉前景色 → 判红
  const reverted = mutated(css, '  background: var(--cy-bg-page);\n  color: var(--cy-color-text-primary);\n}', '  background: var(--cy-bg-page);\n}')
  assert.doesNotMatch(rule(reverted, '.topbar.solid .titBar .back button'), /color:/)
})

// ---------- F-W1-12 / F-R2U-01 未登录 ≠ 加载失败 ----------

test('F-W1-12 isLoginRequired:401 / 业务 401 / 历史 code=2 / 无会话 = 未登录;已登录的 500 / 断网仍是加载失败', () => {
  const member = { getUserID: () => '7' }
  const guest = { getUserID: () => '' }
  assert.equal(isLoginRequired(member, { code: 401 }, 401), true)
  assert.equal(isLoginRequired(member, { code: '401' }), true)
  assert.equal(isLoginRequired(member, { code: 2 }, 2), true)
  assert.equal(isLoginRequired(member, { code: 500 }, 500), false)
  assert.equal(isLoginRequired(member, { errMsg: 'request:fail timeout' }), false)
  assert.equal(isLoginRequired(member, { code: 403 }, 403), false)
  assert.equal(isLoginRequired(guest, { errMsg: 'request:fail timeout' }), true)
  assert.equal(isLoginRequired({ getUserID: () => '0' }, { code: 500 }), true)
})

// 每页:登录引导 = cy-empty / cy-state-shell kind="no-permission" + 「登录」,且绑定的处理器走 retryLogin。
const GUIDES = [
  ['pages/club/detail/index', /<cy-empty wx:elif="\{\{loginRequired\}\}" kind="no-permission"[^>]*cta="登录" bind:cta="onLoginRetry"/, 'onLoginRetry'],
  ['pages/topic/index/index', /<cy-empty wx:elif="\{\{loginRequired\}\}" kind="no-permission"[^>]*cta="登录"[^>]*bind:cta="onLoginRetry"/, 'onLoginRetry'],
  ['subpackageMember/components/cy/scene-play-activity-detail/index', /wx:elif="\{\{state === 'login'\}\}" kind="no-permission"[^>]*cta="登录" bind:cta="loginRetry"/, 'loginRetry'],
  ['subpackageMember/signup/index', /wx:elif="\{\{walletState === 'login'\}\}">\s*<cy-state-shell[^>]*kind="no-permission"[^>]*primary="登录"[^>]*bind:primary="loginWallet"/, 'loginWallet'],
  ['subpackageB/pages/im/list/index', /wx:elif="\{\{error && loginRequired\}\}" kind="no-permission"[\s\S]*?cta="登录" bind:cta="loginConversations"/, 'loginConversations'],
  ['pages/mylike/mylike', /wx:if="\{\{errorMsg && loginRequired && ztList1\.length === 0\}\}" kind="no-permission"[^>]*cta="登录" bind:cta="loginFavorites"/, 'loginFavorites'],
  ['pages/team/detail/index', /<cy-state-shell wx:elif="\{\{error && !team\}\}" kind="\{\{errorKind\}\}"[\s\S]*?primary="\{\{errorAction\}\}"[\s\S]*?bind:primary="onErrorAction"/, 'onErrorAction'],
  ['pages/merchant/apply/index', /primary="\{\{bootstrapErrorKind === 'no-permission' \? '登录' : '重新检查'\}\}"[^>]*bind:primary="retryBootstrap"/, 'retryBootstrap'],
]

test('F-W1-12 八个页面都有登录引导(no-permission + 登录),处理器走 login-reload 的 retryLogin', () => {
  for (const [base, guide, handler] of GUIDES) {
    const wxml = read(base + '.wxml')
    const js = read(base + '.js')
    assert.match(wxml, guide, base + ' 缺登录引导')
    assert.match(js, /require\('[./]+utils\/session\/login-reload\.js'\)/, base + ' 未接 login-reload')
    const at = js.search(new RegExp('\\n\\s+' + handler + '(?:: function)?\\s*\\('))
    assert.ok(at > 0, base + ' 缺 ' + handler)
    assert.match(js.slice(at, at + 600), /retryLogin\(this, app/, base + ' ' + handler + ' 必须走 retryLogin')
  }
  // 普通加载失败仍留白(load-failure 不动)
  assert.match(read('pages/club/detail/index.wxml'), /<cy-error wx:if="\{\{notFound\}\}" load-failure="\{\{true\}\}" \/>/)
  assert.match(read('subpackageB/pages/im/list/index.wxml'), /<cy-error load-failure class="cy-fade-in" wx:elif="\{\{error\}\}"/)
})

test('F-W1-12 俱乐部详情:无会话读失败给登录引导,已登录的读失败仍按 notFound 留白;登录后重读', async () => {
  for (const [userId, expectLogin] of [['', true], ['7', false]]) {
    let user = userId
    const loginCalls = []
    const { host, requests } = mount('pages/club/detail/index.js', { app: {
      getUserID: () => user, getAvatar: () => '', getNickname: () => '', getUserRole: () => 'player',
      firstLogin: () => { loginCalls.push('first'); user = '7'; return Promise.resolve({ ok: true }) },
    } })
    host.data.clubId = 5
    host.loadDetail()
    const detail = requests.find((r) => r.url === '/api/club/detail')
    detail.fail({ errMsg: 'request:fail timeout' })
    assert.equal(host.data.loginRequired, expectLogin)
    assert.equal(host.data.notFound, !expectLogin)
    if (expectLogin) {
      host.loadAll = function () { requests.push({ url: 'reload' }) }
      host.onLoginRetry()
      await flush()
      assert.deepEqual(loginCalls, ['first'])
      assert.equal(host.data.loginRequired, false)
      assert.ok(requests.some((r) => r.url === 'reload'), '登录成功后重读俱乐部')
    }
  }
})

test('F-W1-12 主题页:401 落登录引导而不是 load-failure 留白;断网(已登录)仍是 load-failure', () => {
  for (const [userId, failArgs, expectLogin] of [['7', [{ code: 401 }, 401], true], ['', [{ errMsg: 'request:fail' }], true], ['7', [{ errMsg: 'request:fail' }], false]]) {
    const { host, requests } = mount('pages/topic/index/index.js', { app: { getUserID: () => userId } })
    host.data.id = 9
    host.getData()
    requests.find((r) => r.url === '/api/topic/info-to-user').fail(...failArgs)
    assert.equal(host.data.loadError, true)
    assert.equal(host.data.loginRequired, expectLogin)
  }
})

test('F-W1-12 活动详情:鉴权失败进 login 态(可见引导),普通失败仍是 error 留白', () => {
  for (const [userId, args, state] of [['7', [{ code: 401 }, 401], 'login'], ['', [{ code: 500 }, 500], 'login'], ['7', [{ code: 500 }, 500], 'error']]) {
    const { host, requests } = mount('subpackageMember/components/cy/scene-play-activity-detail/index.js', { app: { getUserID: () => userId } })
    host.data.activityId = '33'
    host.load('33')
    requests.find((r) => r.url === '/api/activity/info').fail(...args)
    assert.equal(host.data.state, state)
  }
})

test('F-W1-12 票夹:未登录读失败 → login(登录引导);已登录两路普通失败仍是 error;登录只重拉失败支线', async () => {
  for (const [userId, topicFail, activityFail, expected] of [
    ['', [{ errMsg: 'request:fail timeout' }], [{ errMsg: 'request:fail timeout' }], 'login'],
    ['7', [{ code: 401 }, 401], [{ errMsg: 'request:fail timeout' }], 'login'],
    ['7', [{ errMsg: 'request:fail timeout' }], [{ code: 500 }, 500], 'error'],
  ]) {
    let user = userId
    const { host, requests } = mount('subpackageMember/signup/index.js', { app: {
      getUserID: () => user,
      reLogin: () => { user = '8'; return Promise.resolve({ ok: true }) },
      firstLogin: () => { user = '8'; return Promise.resolve({ ok: true }) },
    } })
    host.onLoad({})
    const lists = requests.filter((r) => r.url === '/api/registration/list')
    assert.equal(lists.length, 2)
    lists[0].fail(...topicFail)
    lists[1].fail(...activityFail)
    assert.equal(host.data.walletState, expected)
    if (expected !== 'login') continue
    host.loginWallet()
    await flush()
    assert.equal(requests.filter((r) => r.url === '/api/registration/list').length, 4, '登录后两条失败支线各重拉一次')
  }
})

test('F-W1-12 私信列表:未登录失败 loginRequired=true,已登录断网 loginRequired=false', () => {
  for (const [userId, args, expected] of [['', [{ errMsg: 'request:fail' }], true], ['7', [{ code: 401 }, 401], true], ['7', [{ errMsg: 'request:fail' }], false]]) {
    const { host, requests } = mount('subpackageB/pages/im/list/index.js', { app: { getUserID: () => userId } })
    host.loadConversations(false)
    requests.find((r) => r.url === '/api/im/conversations').fail(...args)
    assert.equal(host.data.error, true)
    assert.equal(host.data.loginRequired, expected)
  }
})

test('F-W1-12 我的收藏:主题页签未登录失败给登录引导,已登录失败仍留白', () => {
  for (const [userId, args, expected] of [['', [{ errMsg: 'request:fail' }], true], ['7', [{ code: 401 }, 401], true], ['7', [{ code: 500 }, 500], false]]) {
    const { host, requests } = mount('pages/mylike/mylike.js', { app: { getUserID: () => userId, canUseCommunity: () => false } })
    host.setData({ activeFavoriteTab: 'topics' })
    host.loadLikeData()
    requests.find((r) => r.url === '/api/topic/like_list').fail(...args)
    assert.ok(host.data.errorMsg)
    assert.equal(host.data.loginRequired, expected)
  }
})

test('F-W1-12 队伍详情:重登失败的 401 / 无会话给「登录」按钮并走 retryLogin;已登录断网仍是 network', async () => {
  let user = ''
  const { host, requests } = mount('pages/team/detail/index.js', { app: {
    getUserID: () => user, firstLogin: () => { user = '7'; return Promise.resolve({ ok: true }) },
  } })
  host.onLoad({ teamId: '7' })
  host.onShow()
  requests[0].fail({ code: 401 }, 401)
  assert.equal(host.data.errorKind, 'no-permission')
  assert.equal(host.data.errorAction, '登录')
  host.onErrorAction()
  await flush()
  assert.equal(requests.length, 2, '登录成功后重读队伍')
  const member = mount('pages/team/detail/index.js', { app: { getUserID: () => '7' } })
  member.host.onLoad({ teamId: '7' })
  member.host.onShow()
  member.requests[0].fail({ errMsg: 'request:fail' })
  assert.equal(member.host.data.errorKind, 'network')
  assert.equal(member.host.data.errorAction, '重新加载')
})

test('F-W1-12 商家入驻:游客的引导按钮是「登录」并走 retryLogin,不是只会重撞的「重新检查」', () => {
  const wxml = read('pages/merchant/apply/index.wxml')
  assert.match(wxml, /title="\{\{bootstrapErrorKind === 'no-permission' \? '登录后申请商家入驻'/)
  const js = read('pages/merchant/apply/index.js')
  assert.match(js, /retryBootstrap\(\) \{\n {4}if \(this\.data\.bootstrapErrorKind === 'no-permission'\) \{\n {6}retryLogin\(this, app, function \(\) \{ roleGuard\.clear\(\); this\.startBootstrap\(\); \}\);/)
  // 负控:按钮写死「重新检查」必须判红
  const reverted = mutated(wxml, `primary="{{bootstrapErrorKind === 'no-permission' ? '登录' : '重新检查'}}"`, 'primary="重新检查"')
  assert.doesNotMatch(reverted, GUIDES.find((g) => g[0] === 'pages/merchant/apply/index')[1])
})

test('F-W1-12 商家口碑:缺商家参数是缺参说明(可返回),不再落 load-failure 留白', () => {
  const wxml = read('pages/merchant/reviews/index.wxml')
  assert.match(wxml, /<cy-empty wx:elif="\{\{pageState === 'missing'\}\}" kind="missing-param"[^>]*cta="返回" bind:cta="onNavBack" \/>/)
  assert.match(read('pages/merchant/reviews/index.js'), /pageState: 'missing', errorMessage: '商家信息不完整'/)
  // 管理态的未登录态本来就有登录出口(access-gate 登录 + 页内「重新登录」),保持不变
  assert.match(wxml, /pageState === 'login-required'[^>]*action="重新登录" bind:action="retryLogin"/)
})

// ---------- F-W5-08 主题管理页 ----------

test('F-W5-08(b) 「本场 N 人」只在已选定一场时出现', () => {
  const { host } = mount('pages/club/topic-detail/index.js', { app: { getUserID: () => '7' } })
  assert.equal(JSON.stringify(host.buildChips({}, { sessionHeadcount: 12 })), '[]', '没选定场次时不把全部场次之和叫「本场」')
  host._directorActivityId = 31
  assert.equal(JSON.stringify(host.buildChips({}, { sessionHeadcount: 12 })), '["本场 12 人"]')
})

test('F-W5-08(c) 滚过封面导航转实底,只在跨阈值时 setData', () => {
  const { host } = mount('pages/club/topic-detail/index.js', { app: { getUserID: () => '7' } })
  let writes = 0
  const setData = host.setData
  host.setData = function (patch) { writes += 1; setData.call(this, patch) }
  host.onPageScroll({ scrollTop: 40 })
  assert.equal(host.data.navScrolled, false)
  host.onPageScroll({ scrollTop: 600 })
  host.onPageScroll({ scrollTop: 620 })
  assert.equal(host.data.navScrolled, true)
  host.onPageScroll({ scrollTop: 10 })
  assert.equal(host.data.navScrolled, false)
  assert.equal(writes, 2)
  assert.match(read('pages/club/topic-detail/index.wxml'), /<cy-nav-bar overlay scrolled="\{\{navScrolled\}\}" custom-back bind:back="goBack" \/>/)
  // cy-nav-bar 的 scrolled 让 overlay 转 nav--solid(实底铺满状态栏 + 导航)
  assert.match(read('components/cy/nav-bar/index.wxml'), /\(overlay && !scrolled\) \? 'nav--overlay' : 'nav--solid'/)
})

// ---------- F-L3-09 拒绝原因多行框 ----------

test('F-L3-09 modal-host multiline:editable 时渲染多行 textarea,maxlength / validate 不变', () => {
  const { host } = mount('components/cy/modal-host/index.js')
  host.open({ title: '拒绝原因（选填）', editable: true, multiline: true, maxlength: 500 })
  assert.equal(host.data.multiline, true)
  assert.equal(host.data.maxlength, 500)
  host.open({ title: 'x', editable: false, multiline: true })
  assert.equal(host.data.multiline, false, '非编辑弹窗不出输入框')
  const wxml = read('components/cy/modal-host/index.wxml')
  assert.match(wxml, /<textarea wx:if="\{\{multiline\}\}" class="mh__input mh__input--multiline"[^>]*maxlength="\{\{maxlength > 0 \? maxlength : 140\}\}" bindinput="onInput"/)
  assert.match(wxml, /<input wx:else class="mh__input"/)
  assert.match(rule(read('components/cy/modal-host/index.wxss'), '.mh__input--multiline'), /height:\s*240rpx/)
  // 校验仍在 onConfirm 里拦:超长不关面板
  let settled = null
  host.open({ editable: true, multiline: true, maxlength: 500, validate: (v) => (v.length > 3 ? '太长' : ''), success: (r) => { settled = r } })
  host.onInput({ detail: { value: 'abcd' } })
  host.onConfirm()
  assert.equal(host.data.inputError, '太长')
  assert.equal(settled, null)
})

test('F-L3-09 入会申请拒绝原因用多行框,保留 500 字上限与校验', () => {
  const js = read('pages/club/join-requests/index.js')
  const block = js.slice(js.indexOf('askRejectReason(memberId) {'), js.indexOf('retryReview(e)'))
  assert.match(block, /editable: true,/)
  assert.match(block, /multiline: true,/)
  assert.match(block, /maxlength: 500,/)
  assert.match(block, /> 500 \? '拒绝原因不能超过500字'/)
})

// ---------- F-L5C-06 设置页「我的收藏」副标题跟社区开关 ----------

test('F-L5C-06 设置页「我的收藏」副标题跟 canUseCommunity(false):关时只说主题,开关晚到再补', async () => {
  let open = false
  let resolveFlags
  const flags = new Promise((resolve) => { resolveFlags = resolve })
  const { host } = mount('pages/shezhi/shezhi.js', { app: {
    getUserID: () => '7', canUseCommunity: () => open, loadFeatureFlags: () => flags,
  } })
  host.syncCommunityEntry()
  assert.equal(host.data.communityPostRead, false)
  open = true
  resolveFlags()
  await flush()
  assert.equal(host.data.communityPostRead, true)
  const wxml = read('pages/shezhi/shezhi.wxml')
  assert.match(wxml, /title="我的收藏" description="\{\{communityPostRead \? '收藏的帖文与主题' : '收藏的主题'\}\}"/)
  assert.doesNotMatch(wxml, /description="收藏的帖文与主题"/, '负控:写死「帖文」的副标题不能回来')
})

// ---------- 成长中心:无头像成员的头像位 ----------

test('成长中心:头像兜底用全站默认头像,页内静态图片资源都真实存在(不留空位)', () => {
  const wxml = read('subpackageP3/pages/growthcenter/index/index.wxml')
  assert.match(wxml, /<image class="gc-score-avatar" src="\{\{profile\.avatarUrl \|\| '\/images\/d_profile\.png'\}\}"/)
  const statics = wxml.replace(/<!--[\s\S]*?-->/g, '').match(/'?(\/images\/[^'"}\s]+\.(?:png|svg|jpg))/g) || []
  assert.ok(statics.length >= 1)
  for (const ref of statics) {
    const rel = ref.replace(/^'/, '')
    assert.ok(fs.existsSync(path.join(ROOT, rel)), '引用了不存在的图片 ' + rel)
  }
  assert.match(wxml, /<cy-icon class="gc-arrow-icon" name="chevron-right" size="32" \/>/)
  assert.match(wxml, /<cy-icon class="gc-star-icon" name="star" size="40" \/>/)
})
