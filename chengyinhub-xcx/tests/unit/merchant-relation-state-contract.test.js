const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.join(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
}

function createPage(overrides) {
  const requests = []
  const toasts = []
  const navigations = []
  let sessionClears = 0
  let stopped = 0
  let config
  const state = Object.assign({ memberId: 'merchant-a', sessionResult: { ok: true } }, overrides || {})
  global.getApp = () => ({
    getImgUrl(value) { return value ? `https://img.example/${value}` : '' },
    getRequestErrorMessage(_res, fallback) { return fallback },
    getUserID() { return state.memberId },
    getSession() { return { clearSession() { sessionClears += 1 } } },
    firstLogin() { state.firstLogins = (state.firstLogins || 0) + 1; state.memberId = 'merchant-a'; return Promise.resolve({ ok: true }) },
    getSessionManager() {
      return {
        ensureSession() {
          state.managerCalls = (state.managerCalls || 0) + 1
          if (state.sessionResult.ok && !state.memberId) state.memberId = 'merchant-a'
          return Promise.resolve(state.sessionResult)
        },
      }
    },
    sendRequest(options) { requests.push(options) },
  })
  global.wx = {
    getSystemInfoSync() { return { statusBarHeight: 24 } },
    navigateTo(options) { navigations.push({ method: 'navigateTo', url: options.url }) },
    switchTab(options) { navigations.push({ method: 'switchTab', url: options.url }) },
    reLaunch(options) { navigations.push({ method: 'reLaunch', url: options.url }) },
    showToast(options) { toasts.push(options) },
    stopPullDownRefresh() { stopped += 1 },
    setNavigationBarColor() {},
  }
  global.Page = value => { config = value }
  const absolute = path.join(ROOT, 'pages/merchant/relation/index.js')
  delete require.cache[require.resolve(absolute)]
  require(absolute)
  config.data = JSON.parse(JSON.stringify(config.data))
  config.setData = patch => Object.assign(config.data, patch)
  return {
    page: config,
    requests,
    toasts,
    navigations,
    state,
    sessionClears: () => sessionClears,
    stopped: () => stopped,
  }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

function relationHome(overrides) {
  return Object.assign({
    code: 200,
    data: {
      stats: { merchantCount: 0, clubCount: 0, pendingCoopCount: 0 },
      relations: [],
      discovery: { merchants: [], clubs: [] },
    },
  }, overrides)
}

function assertCardProjection(view) {
  // 俱乐部卡整条 item 传入(coverUrl/logoUrl 已在页面投影好),不得退回读原始字段
  assert.match(view, /<cy-club-card club="\{\{item\}\}"/)
}

test('合作页把后端公开封面与头像字段投影成卡片真实资源', () => {
  const { page, requests } = createPage()
  page.onLoad({ tab: 'club' })
  requests[0].success(relationHome({
    code: '200',
    data: {
      stats: { merchantCount: 0, clubCount: 0, pendingCoopCount: 0 },
      relations: [],
      discovery: {
        merchants: [{ id: 2, memberId: 20, name: '邻店' }],
        clubs: [{ id: 3, name: '夜行俱乐部', cover: 'club-cover.jpg', logo: 'club-logo.png', businessStatus: 9, locationVerified: 'unknown', distance: -20 }],
      },
    },
  }))

  assert.equal(page.data.merchants, undefined, '2026-10-22:商家目录已移除')
  assert.equal(page.data.clubs[0].displayStatus, 'none')
  assert.equal(page.data.clubs[0].displayLocationVerified, null)
  assert.equal(page.data.clubs[0].distanceText, '')
  assert.equal(page.data.clubs[0].coverUrl, 'https://img.example/club-cover.jpg')
  assert.equal(page.data.clubs[0].logoUrl, 'https://img.example/club-logo.png')

  const view = read('pages/merchant/relation/index.wxml')
  assertCardProjection(view)
  assert.doesNotMatch(view, /d_logo|d_banner|default|placeholder/i)
})

test('负控：卡片回退消费未转换字段时封面投影门禁会变红', () => {
  const broken = read('pages/merchant/relation/index.wxml')
    .replace('<cy-club-card club="{{item}}"', '<cy-club-card club="{{item.raw}}"')
  assert.throws(() => assertCardProjection(broken))
})

test('合作页非空脏列表显示分区错误，不能伪装成成功空态', () => {
  const { page, requests } = createPage()
  page.onLoad({ tab: 'club' })
  requests[0].success(relationHome({
    code: 200,
    data: {
      stats: { merchantCount: 0, clubCount: 0, pendingCoopCount: 0 },
      relations: [],
      discovery: {
        merchants: [],
        clubs: [{ id: 3, name: '有效俱乐部' }, { id: 4, name: { raw: true } }],
      },
    },
  }))

  assert.equal(page.data.pageState, 'ready')
  assert.equal(page.data.clubs.length, 1)
  assert.equal(page.data.clubError, '部分俱乐部资料暂无法显示')
  assert.match(read('pages/merchant/relation/index.wxml'), /clubError[\s\S]*bind:action="loadRelationHome"/)
  assert.match(read('pages/merchant/relation/index.wxml'), /wx:elif="\{\{!clubError\}\}"[^>]*title="暂无可合作俱乐部"/)
})

test('合作页刷新失败保留上次内容并提供可见重试', () => {
  const { page, requests } = createPage()
  page.onLoad({ tab: 'club' })
  requests[0].success(relationHome({
    data: {
      stats: { merchantCount: 0, clubCount: 0, pendingCoopCount: 0 },
      relations: [],
      discovery: { merchants: [], clubs: [{ id: 2, name: '邻店俱乐部' }] },
    },
  }))
  requests[0].complete()

  page.loadRelationHome()
  requests[1].fail({ errMsg: 'network fail' })
  requests[1].complete()

  assert.equal(page.data.pageState, 'ready')
  assert.equal(page.data.clubs[0].displayName, '邻店俱乐部')
  assert.doesNotMatch(read('pages/merchant/relation/index.wxml'), /<cy-inline-error[^>]*staleError/)
})

test('合作页初次异常进入整页错误，请求静默且防重复', () => {
  const { page, requests } = createPage()
  page.onLoad({ tab: 'club' })
  page.loadRelationHome()

  assert.equal(requests.length, 1)
  assert.equal(requests[0].hideLoading, true)
  assert.equal(requests[0].silentError, true)
  assert.doesNotThrow(() => requests[0].success(undefined))
  requests[0].complete()

  assert.equal(page.data.pageState, 'error')
  assert.equal(page.data.error, '合作数据加载失败')
  assert.deepEqual(page.data.clubs, [])
})

test('合作页 401 静默重登后自动重载，403 静默回会员中心', async () => {
  const { page, requests, navigations, state } = createPage()
  page.onLoad({ tab: 'club' })
  assert.equal(requests.length, 1)

  state.memberId = ''
  requests[0].fail({ code: 401, msg: '登录状态失效' }, 401)
  requests[0].complete()
  await flush()
  assert.equal(page.data.pageState, 'loading', '重登期间保持普通加载态，不摆整屏去登录')
  const retried = requests[1]
  assert.ok(retried, '重登成功后必须自动重拉合作数据')
  retried.success(relationHome())
  retried.complete()
  assert.equal(page.data.pageState, 'ready')

  page.loadRelationHome()
  requests[2].fail({ code: 403, msg: '商家身份无效或权限不足' }, 403)
  assert.deepEqual(navigations.at(-1), { method: 'switchTab', url: '/pages/member/index/index' },
    '无商家身份深链误入必须静默回会员中心，不弹整屏页')

  const view = read('pages/merchant/relation/index.wxml')
  assertNoLoginGate(view)
  assert.match(view, /<cy-inline-error[^>]*wx:elif="\{\{pageState === 'error'\}\}"[\s\S]{0,200}?bind:action="retryRelation"/)
})

function assertNoLoginGate(view) {
  assert.doesNotMatch(view, /登录已失效|去登录|goLogin|goWorkbench/, '整屏登录/无权限闸必须删除')
  assert.match(view, /<cy-inline-error[^>]*wx:elif="\{\{pageState === 'error'\}\}"[\s\S]{0,200}?bind:action="retryRelation"/)
}

test('负控:把未登录整屏 cy-empty 加回合作页必须判红', () => {
  const view = read('pages/merchant/relation/index.wxml')
  const mutated = view.replace(
    /<cy-inline-error load-failure class="rel-inline-error" wx:elif="\{\{pageState === 'error'\}\}"/,
    '<cy-empty wx:elif="{{pageState === \'anonymous\'}}" title="登录已失效" cta="去登录" bind:cta="goLogin" />\n    <cy-inline-error load-failure class="rel-inline-error" wx:elif="{{pageState === \'error\'}}"',
  )
  assert.notEqual(mutated, view, '负控锚点失效')
  assert.throws(() => assertNoLoginGate(mutated), /整屏登录\/无权限闸必须删除/)
})

// 2026-10-24 owner 规则(app.js:142-144)取代旧「合作页未登录先静默登录」:游客不在打开页面时建号(j6 F1)。
test('合作页游客落「登录后查看合作」,不静默登录、不发请求;点「登录」后自动加载', async () => {
  const guest = createPage({ memberId: '' })
  guest.page.onLoad({ tab: 'club' })
  await flush()
  assert.equal(guest.page.data.guestLogin, true)
  assert.equal(guest.state.managerCalls || 0, 0, '游客打开页面不得走 wx.login → /api/login/code')
  assert.equal(guest.requests.length, 0)

  guest.page.onGuestLogin()
  await flush()
  assert.equal(guest.state.firstLogins, 1)
  assert.equal(guest.page.data.guestLogin, false)
  assert.equal(guest.requests.length, 1, '登录成功后必须自动加载')
})

test('合作页异常回调与缺少可访问主体的商家卡都不会落成空态', () => {
  const { page, requests } = createPage()
  page.onLoad({ tab: 'club' })
  requests[0].successStatusAbnormal({ msg: 'upstream abnormal' })
  requests[0].complete()
  assert.equal(page.data.pageState, 'error')

  page.loadRelationHome()
  requests[1].success(relationHome({
    data: {
      stats: { merchantCount: 0, clubCount: 0, pendingCoopCount: 0 },
      relations: [],
      discovery: { merchants: [], clubs: [{ id: 2, name: { raw: true } }] },
    },
  }))
  assert.equal(page.data.clubs.length, 0)
  assert.equal(page.data.clubError, '部分俱乐部资料暂无法显示')
})

test('合作页下拉刷新收口刷新态，卸载后迟到响应无效', () => {
  const { page, requests, stopped } = createPage()
  page.onLoad({ tab: 'club' })
  requests[0].success(relationHome())
  requests[0].complete()

  page.onPullDownRefresh()
  assert.equal(page.data.refreshing, true)
  page.onUnload()
  requests[1].success(relationHome({
    data: {
      stats: { merchantCount: 0, clubCount: 0, pendingCoopCount: 0 },
      relations: [],
      discovery: { merchants: [], clubs: [{ id: 9, name: '迟到俱乐部' }] },
    },
  }))
  requests[1].complete()

  assert.deepEqual(page.data.clubs, [])
  assert.equal(stopped(), 1)
  const config = JSON.parse(read('pages/merchant/relation/index.json'))
  assert.equal(config.enablePullDownRefresh, true)
})

// CU-M-97:合作 Tab 俱乐部卡按钮写「发起合作」,实点却只进俱乐部主页,要再点一次才到表单。
// 现约定:整卡正文 = 看资料(goClub → 俱乐部主页),按钮 = 直达 coop/invite type=1 表单。
test('俱乐部卡所有入口先去主页，保留主题上下文', () => {
  const { page, navigations } = createPage()
  page.data.topicId = '990062'
  page.data.topicName = '预制人生'
  page.goClub({ currentTarget: { dataset: { id: 7 } } })
  assert.match(navigations.at(-1).url, /pages\/club\/detail\/index\?id=7&topicId=990062/)
  assert.match(read('pages/merchant/relation/index.wxml'), /bind:action="goClub"/)
})

// 2026-10-22 owner:合作 tab = 帖子 | 俱乐部,帖子默认;商家不看其他商家
test('发现页默认帖子 tab,拉 /api/club/post/feed 全站流;骨架超过 400ms 才出', async () => {
  const { page, requests } = createPage()
  page.onLoad({})
  assert.deepEqual(page.data.tabs.map((t) => t.label), ['帖文', '俱乐部'])
  assert.equal(page.data.activeTab, 'feed')
  await flush()
  const feedReq = requests.find((r) => r.url === '/api/club/post/feed')
  assert.ok(feedReq, '帖子流请求')
  assert.equal(JSON.parse(feedReq.data).scope, 'all', 'W2-17:发现看全站帖文,不再只看合作过的俱乐部')
  assert.equal(page.data.feedState, 'loading')
  assert.equal(page.data.feedSlow, false, '加载初期不显示骨架,避免闪屏')
  feedReq.success({ code: 200, data: { hasMore: false, rows: [{ id: 5, clubId: 3, content: '夜跑', nickname: '甲', images: 'a.jpg;b.jpg', createTime: '2026-10-21 10:00:00' }] } })
  await flush()
  assert.equal(page.data.feedState, 'ready')
  assert.equal(page.data.feed[0].picList.length, 2)
  assert.equal(page.data.feed[0].memberNickname, '甲')
})

test('帖子为空/失败状态;刷新失败保留旧内容;切 tab 不重复拉取', async () => {
  const { page, requests } = createPage()
  page.onLoad({})
  await flush()
  const feedReq = () => requests.filter((r) => r.url === '/api/club/post/feed')
  feedReq()[0].success({ code: 200, data: { hasMore: false, rows: [] } })
  await flush()
  assert.equal(page.data.feedState, 'empty')
  assert.equal(page.data.feedEmptyTitle, '还没有帖文')
  page.onTabChange({ detail: { key: 'club' } })
  page.onTabChange({ detail: { key: 'feed' } })
  assert.equal(feedReq().length, 1, '回到帖子 tab 不重拉,保留已有内容')
  page.loadFeed()
  await flush()
  feedReq()[1].success({ code: 500 })
  await flush()
  assert.equal(page.data.feedState, 'stale-error')
})

test('W2-17:与玩家发现页同一套合流翻页(按 beforeId),翻页失败不出错误;帖文卡可点赞、评论', async () => {
  const { page, requests } = createPage()
  page.onLoad({})
  await flush()
  const feedReq = () => requests.filter((r) => r.url === '/api/club/post/feed')
  // 首屏一页 20 条:不足 20 条时合流器会接着按 beforeId 补拉(与玩家发现页同一个 createFeedMerger)
  const first = Array.from({ length: 20 }, (_, i) => ({ id: 100 - i, clubId: 3, content: 'p' + i, createTime: '2026-11-01 10:' + String(59 - i).padStart(2, '0') + ':00' }))
  feedReq()[0].success({ code: 200, data: { hasMore: true, rows: first } })
  await flush()
  assert.equal(page.data.feed.length, 20)
  assert.equal(feedReq().length, 1, '首屏够 20 条不再补拉')
  page.onReachBottom()
  await flush()
  const more = JSON.parse(feedReq()[1].data)
  assert.equal(more.scope, 'all')
  assert.equal(more.beforeId, 81)
  feedReq()[1].success({ code: 500 })
  await flush()
  assert.equal(page.data.feedState, 'ready', '翻页失败保留已有帖文')
  assert.equal(page.data.feed.length, 20)
  page.onReachBottom()
  await flush()
  assert.equal(JSON.parse(feedReq()[2].data).beforeId, 81, '失败那一路游标不动,下次触底重试')
  feedReq()[2].success({ code: 200, data: { hasMore: false, rows: [{ id: 3, clubId: 4, content: 'c', createTime: '2026-10-31 10:00:00' }] } })
  await flush()
  assert.deepEqual(page.data.feed.slice(-2).map((p) => p.id), [81, 3])
  page.onReachBottom()
  await flush()
  assert.equal(feedReq().length, 3, '没有更多时不再请求')
  const view = read('pages/merchant/relation/index.wxml')
  assert.match(view, /title="发现"/)
  assert.match(view, /interactive-actions[\s\S]*bind:comment="onPostTap" bind:like="onLikePost"/)
})

test('W2-17:首屏不足一页时合流器按 beforeId 补拉,合成一条', async () => {
  const { page, requests } = createPage()
  page.onLoad({})
  await flush()
  const feedReq = () => requests.filter((r) => r.url === '/api/club/post/feed')
  feedReq()[0].success({ code: 200, data: { hasMore: true, rows: [{ id: 9, clubId: 3, content: 'a', createTime: '2026-11-01 10:00:00' }, { id: 7, clubId: 3, content: 'b', createTime: '2026-11-01 09:00:00' }] } })
  await flush()
  assert.equal(JSON.parse(feedReq()[1].data).beforeId, 7)
  feedReq()[1].success({ code: 200, data: { hasMore: false, rows: [{ id: 3, clubId: 4, content: 'c', createTime: '2026-10-31 10:00:00' }] } })
  await flush()
  assert.deepEqual(page.data.feed.map((p) => p.id), [9, 7, 3])
  assert.equal(page.data.feedState, 'ready')
})

// r2 a6-w2-17(业主「点赞是红色的 这里也应该是正常的帖文样式啊 字体大小 等等 也可以发的 商家也可以发的」)
test('r2 a6-w2-17:商家发现与玩家发现页同一张卡;浅色卡面已赞仍是红心', () => {
  const view = read('pages/merchant/relation/index.wxml')
  const talent = read('pages/talent/list/index.wxml')
  const attrs = (src) => (src.match(/<cy-post-card[^>]*>/) || [''])[0]
  for (const a of ['interactive-actions', 'interactive-user', 'interactive-club', 'show-follow', 'show-share', 'bind:like="onLikePost"', 'bind:follow="onFollowPost"', 'bind:user="onPostUser"']) {
    assert.ok(attrs(talent).includes(a), 'talent 有 ' + a)
    assert.ok(attrs(view).includes(a), '商家发现也有 ' + a)
  }
  const css = read('components/cy/post-card/index.wxss')
  const lightAction = css.indexOf('.post-card--light .post-card__action {')
  const lightLike = css.indexOf('.post-card--light .post-card__action--like.is-on { color: var(--cy-color-status-danger); }')
  assert.ok(lightAction > 0 && lightLike > lightAction, '已赞红心排在浅色覆盖之后,不被压成黑')
})

test('r2 a6-w2-17:社区读开关开着时合入社区帖文;写开关开着才出发帖入口,发完回读', () => {
  const view = read('pages/merchant/relation/index.wxml')
  assert.match(view, /<view class="post-composer-collapsed" wx:if="\{\{canCompose\}\}" bindtap="openComposer"/)
  assert.match(view, /<cy-post-compose show="\{\{composerOpen\}\}" bind:close="closeComposer" bind:published="onComposePublished" \/>/)
  const json = JSON.parse(read('pages/merchant/relation/index.json'))
  assert.equal(json.usingComponents['cy-post-compose'], '/pages/square/components/cy/post-compose/index')
  assert.equal(json.componentPlaceholder['cy-post-compose'], 'view', '跨分包组件要占位')
  const js = read('pages/merchant/relation/index.js')
  assert.match(js, /canCompose: typeof app\.canUseCommunity === 'function' && app\.canUseCommunity\(true\)/)
  assert.match(js, /app\.loadFeatureFlags\(\)/, '开关没读到先读一次,与玩家发现页同一道闸')
  assert.match(js, /app\.canUseCommunity\(false\)[\s\S]*url: '\/api\/creativesquare\/list'[\s\S]*normalizeCommunityPost/)
  assert.match(js, /onComposePublished\(\) \{\s*this\.setData\(\{ composerOpen: false \}\);\s*this\.loadFeed\(\);/)
  const { page } = createPage()
  page.onLoad({})
  assert.equal(page.data.canCompose, false, '测试 app 没有社区开关 ⇒ 不出入口')
  page.openComposer()
  assert.notEqual(page.data.composerOpen, true, '写开关关着不开发帖弹窗')
  page.data.canCompose = true
  page.openComposer()
  assert.equal(page.data.composerOpen, true)
  page.onPostTap({ detail: { index: 0 } })
})

test('合作页模板不含商家目录入口', () => {
  const view = read('pages/merchant/relation/index.wxml')
  assert.doesNotMatch(view, /cy-merchant-card|goMerchant|merchants/)
  assert.match(view, /<cy-post-card/)
})

test('l-misc:商家本人所在俱乐部(/api/club/my)的帖文出转发,别的俱乐部帖文与社区帖文不出;读失败不出', async () => {
  const { page, requests } = createPage()
  page.onLoad({})
  await flush()
  const feedReq = requests.find((r) => r.url === '/api/club/post/feed')
  const myReq = requests.find((r) => r.url === '/api/club/my')
  assert.ok(myReq, '成员关系从服务端读')
  feedReq.success({ code: 200, data: { hasMore: false, rows: [
    { id: 5, clubId: 3, content: '我在的俱乐部', createTime: '2026-10-21 10:00:00' },
    { id: 6, clubId: 4, content: '别的俱乐部', createTime: '2026-10-21 09:00:00' },
  ] } })
  await flush()
  assert.equal(page.data.feed[0].canRepost, false, '成员关系没回来之前不出转发')
  myReq.success({ code: 200, data: { owned: [], joined: [{ id: 3 }] } })
  assert.equal(page.data.feed.find((r) => r.id === 5).canRepost, true)
  assert.equal(page.data.feed.find((r) => r.id === 6).canRepost, false)
  page.onRepostPost({ detail: { postId: 6 } })
  assert.equal(requests.filter((r) => r.url === '/api/club/post/repost').length, 0, '非本人俱乐部的帖文点不出转发请求')
  page.onRepostPost({ detail: { postId: 5 } })
  assert.equal(requests.filter((r) => r.url === '/api/club/post/repost').length, 1)

  const failed = createPage()
  failed.page.onLoad({})
  await flush()
  failed.requests.find((r) => r.url === '/api/club/post/feed').success({ code: 200, data: { hasMore: false, rows: [{ id: 7, clubId: 3, createTime: '2026-10-21 10:00:00' }] } })
  await flush()
  failed.requests.find((r) => r.url === '/api/club/my').success({ code: 500 })
  assert.equal(failed.page.data.feed[0].canRepost, false)
})
