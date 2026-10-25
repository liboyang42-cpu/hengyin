const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-197:俱乐部动态点赞/举报——业务失败与网络失败都要有提示;目标态点赞在返回前不重复发

const toasts = []

const ROOT = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
}

function applyDataPatch(target, patch) {
  Object.entries(patch).forEach(([key, value]) => {
    const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
    let cursor = target
    for (let i = 0; i < parts.length - 1; i += 1) cursor = cursor[parts[i]]
    cursor[parts[parts.length - 1]] = value
  })
}

function loadPage(relativePath, app, requireMap = {}) {
  let definition
  const filename = path.join(ROOT, relativePath)
  vm.runInNewContext(read(relativePath), {
    getApp: () => app,
    Page(page) { definition = page },
    require(request) {
      if (Object.prototype.hasOwnProperty.call(requireMap, request)) return requireMap[request]
      if (request === '../../../utils/response-shape.js') return require(path.join(ROOT, 'utils/response-shape.js'))
      // 邀约/合作池整形与 coop/list 共用一份(2026-09-08 抽出)。给真实现而不是桩:
      // 「可对接的活动」那段要保证的正是它按真整形渲染,桩会把问题遮住。
      if (request === '../../../utils/coop-invite-view.js') return require(path.join(ROOT, 'utils/coop-invite-view.js'))
      // 帖文卡的 variant 判定与广场共用一份,俱乐部页 require 的就是它
      if (request === '../../../utils/feed-play-card.js') return require(path.join(ROOT, 'utils/feed-play-card.js'))
      // CU-C-60/CU-C-107:俱乐部页新增的三个 require(扫码路由表 / 核销写闸 / 正文摘要),都是纯模块
      if (request === '../../../utils/verification-scan.js') return require(path.join(ROOT, 'utils/verification-scan.js'))
      if (request === '../../../utils/write-action-workflow.js') return require(path.join(ROOT, 'utils/write-action-workflow.js'))
      if (request === '../../../utils/merchant-verification.js') return require(path.join(ROOT, 'utils/merchant-verification.js'))
      if (request === '../../../utils/danger-actions.js') return require(path.join(ROOT, 'utils/danger-actions.js'))
      if (request === '../utils/owner-action-guard.js') return require(path.join(ROOT, 'pages/club/utils/owner-action-guard.js'))
      if (request === '../utils/club-pinned-notice.js') return require('../../pages/club/utils/club-pinned-notice.js')
      // BL-20:俱乐部详情图片预览走 utils/media-preview(纯模块,给真实现)
      if (request === '../../../utils/media-preview.js') return require('../../utils/media-preview.js')
      throw new Error(`unexpected require: ${request}`)
    },
    Set,
    Date,
    JSON,
    wx: {
      navigateBack() {},
      navigateTo() {},
      showModal(opts) { if (opts && typeof opts.success === 'function') opts.success({ confirm: true, cancel: false }) },
      showToast(opts) { toasts.push(opts && opts.title) },
      showActionSheet() {},
      setStorageSync() {},
    },
  }, { filename })
  const page = Object.assign({}, definition, {
    data: Object.assign({}, definition.data),
    setData(patch) { applyDataPatch(this.data, patch) },
  })
  return page
}

function createDetailHarness() {
  const requests = []
  const app = {
    globalData: {},
    getUserID: () => '7',
    sendRequest(request) { requests.push(request) },
  }
  const page = loadPage('pages/club/detail/index.js', app, {
    // 真件不是桩:开团成功的震动降级(减动效不震)正是本轮要保证的行为
    '../../../utils/motion.js': require('../../utils/motion.js'),
    '../../../utils/motion-preference.js': require('../../utils/motion-preference.js'),
    '../../../utils/scene-registry.js': { getScene: () => ({}) },
    '../../../utils/datetime': { toTimestamp: () => 0 },
    '../utils/mockData.js': { DEMO_NEARBY_CLUB_ID: -1 },
    '../utils/aiPlanToDraft.js': { aiPlanToDraft: () => ({}) },
    '../../../utils/group-code-session.js': {
      buildGroupCodeIssuePayload: () => ({}),
      listGroupCodeActivities: () => [],
    },
    '../../../utils/merchant-home-link.js': require('../../utils/merchant-home-link.js'),
    '../../../utils/ticket-source.js': require('../../utils/ticket-source.js'),
    '../../../utils/topic-share.js': require('../../utils/topic-share.js'),
    '../utils/club-event-calendar.js': require('../../pages/club/utils/club-event-calendar.js'),
    '../utils/edition-empty-reason.js': require('../../pages/club/utils/edition-empty-reason.js'),
  })
  page.data.clubId = '9'
  page.data.myMemberId = '7'
  return { page, requests, appRef: app }
}

function byUrl(requests, url) {
  return requests.filter((request) => request.url === url)
}

const likeEvent = (id) => ({ detail: { postId: id }, currentTarget: { dataset: {} } })

test('点赞业务失败提示服务端文案,网络失败定点回读当前状态,成功保持原加载行为', () => {
  const h = createDetailHarness()
  toasts.length = 0
  h.page.onLikePost(likeEvent(5))
  byUrl(h.requests, '/api/club/post/like')[0].success({ code: '500', msg: '请先登录' })
  assert.equal(toasts.pop(), '请先登录')

  h.page.onLikePost(likeEvent(5))
  byUrl(h.requests, '/api/club/post/like')[1].success({ code: '500' })
  assert.equal(toasts.pop(), '点赞没成功')

  // 断网=结果未知:不再提示「请重试」,而是回读 like-state 核对(详见下方 club-like-unknown 用例)
  h.page.onLikePost(likeEvent(5))
  byUrl(h.requests, '/api/club/post/like')[2].fail()
  assert.equal(toasts.pop(), '点赞结果未确认，正在确认')
  byUrl(h.requests, '/api/club/post/like-state')[0].success({ code: '200', data: { id: '5', clubId: '9', isLiked: false, likeCount: 0 } })
  assert.equal(toasts.pop(), '当前未点赞，可再次操作')

  h.page.onLikePost(likeEvent(5))
  byUrl(h.requests, '/api/club/post/like')[3].success({ code: '200', data: { liked: true } })
  assert.equal(byUrl(h.requests, '/api/club/post/list').length, 1)
})

test('同一条动态返回前重复点赞只发一次,不同动态互不影响', () => {
  const h = createDetailHarness()
  h.page.onLikePost(likeEvent(5))
  h.page.onLikePost(likeEvent(5))
  h.page.onLikePost(likeEvent(6))
  const likes = byUrl(h.requests, '/api/club/post/like')
  assert.deepEqual(likes.map((r) => JSON.parse(r.data).postId), [5, 6])
  likes[0].success({ code: '200' })
  h.page.onLikePost(likeEvent(5))
  assert.equal(byUrl(h.requests, '/api/club/post/like').length, 3)
})

test('举报动态与举报评论网络失败有提示,成功沿用服务端 msg', () => {
  const h = createDetailHarness()
  toasts.length = 0
  h.page.onReportPost({ currentTarget: { dataset: { id: 5 } } })
  byUrl(h.requests, '/api/club/post/report')[0].fail()
  assert.equal(toasts.pop(), '网络异常，请重试')

  h.page.onReportComment({ currentTarget: { dataset: { id: 8 } } })
  const report = byUrl(h.requests, '/api/club/post/comment/report')[0]
  report.fail()
  assert.equal(toasts.pop(), '网络异常，请重试')
  report.success({ code: '200', msg: '已举报,平台将尽快处理' })
  assert.equal(toasts.pop(), '已举报,平台将尽快处理')
})

// UNKNOWN 只读当前状态快照，严格核对原帖和俱乐部；不能断言原写请求从未执行。
test('点赞结果未知:锁不放,再点只回读不切换;回读原帖当前状态后才放行', () => {
  const h = createDetailHarness()
  toasts.length = 0
  h.page.data.posts = [{ id: 5, isLiked: false, likeCount: 2 }]
  h.page.onLikePost(likeEvent(5))
  byUrl(h.requests, '/api/club/post/like')[0].fail()
  assert.equal(toasts.pop(), '点赞结果未确认，正在确认')
  const reads = () => byUrl(h.requests, '/api/club/post/like-state')
  assert.equal(reads().length, 1)
  assert.deepEqual(JSON.parse(reads()[0].data), { id: '5', clubId: '9' })

  // 回读在途:再点既不切换也不重复回读
  h.page.onLikePost(likeEvent(5))
  assert.equal(byUrl(h.requests, '/api/club/post/like').length, 1)
  assert.equal(reads().length, 1)

  // 回读失败:仍未知,锁不放
  reads()[0].fail()
  assert.equal(toasts.pop(), '点赞结果未确认，请稍后再点确认')
  h.page.onLikePost(likeEvent(5))
  assert.equal(byUrl(h.requests, '/api/club/post/like').length, 1)
  assert.equal(reads().length, 2)

  // 非严格布尔(如未登录时后端不标、字符串 "true")不算确认
  reads()[1].success({ code: '200', data: { id: '5', clubId: '9', isLiked: 'true', likeCount: 3 } })
  assert.equal(toasts.pop(), '点赞结果未确认，请稍后再点确认')
  h.page.onLikePost(likeEvent(5))
  assert.equal(byUrl(h.requests, '/api/club/post/like').length, 1)
  // 帖子不在回读结果里也不算确认
  reads()[2].success({ code: '200', data: { id: '6', clubId: '9', isLiked: true } })
  h.page.onLikePost(likeEvent(5))
  assert.equal(byUrl(h.requests, '/api/club/post/like').length, 1)

  // 严格 true → 当前已赞,回写这一条并放锁
  reads()[3].success({ code: '200', data: { id: '5', clubId: '9', isLiked: true, likeCount: 3 } })
  assert.equal(toasts.pop(), '当前已点赞')
  assert.equal(h.page.data.posts[0].isLiked, true)
  assert.equal(h.page.data.posts[0].likeCount, 3)
  h.page.onLikePost(likeEvent(5))
  assert.equal(byUrl(h.requests, '/api/club/post/like').length, 2)
})

test('目标意图取自发起时的 isLiked:已赞时未知回读到 false 只说明当前未赞', () => {
  const h = createDetailHarness()
  toasts.length = 0
  h.page.data.posts = [{ id: 5, isLiked: true, likeCount: 3 }]
  h.page.onLikePost(likeEvent(5))
  byUrl(h.requests, '/api/club/post/like')[0].fail()
  byUrl(h.requests, '/api/club/post/like-state')[0].success({ code: '200', data: { id: '5', clubId: '9', isLiked: false, likeCount: 2 } })
  assert.equal(toasts.pop(), '当前未点赞，可再次操作')
  assert.equal(h.page.data.posts[0].isLiked, false)

  h.page.onLikePost(likeEvent(5))
  byUrl(h.requests, '/api/club/post/like')[1].fail()
  byUrl(h.requests, '/api/club/post/like-state')[1].success({ code: '200', data: { id: '5', clubId: '9', isLiked: false, likeCount: 2 } })
  assert.equal(toasts.pop(), '当前未点赞，可再次操作')
})

test('未知锁按 owner+俱乐部隔离:换号后旧回读丢弃,新账号可直接点赞', () => {
  const h = createDetailHarness()
  let uid = '7'
  h.page.data.posts = [{ id: 5, isLiked: false }]
  const like = byUrl.bind(null, h.requests)
  // 页面加载时已捕获 app;改 getUserID 即可模拟换号
  h.appRef.getUserID = () => uid
  h.page.onLikePost(likeEvent(5))
  like('/api/club/post/like')[0].fail()
  // A 的未知锁还在,换成 B
  uid = '8'
  h.page.onLikePost(likeEvent(5))
  assert.equal(like('/api/club/post/like').length, 2)
  // A 的迟到回读不能改 B 的页面
  toasts.length = 0
  like('/api/club/post/like-state')[0].success({ code: '200', data: { id: '5', clubId: '9', isLiked: true, likeCount: 9 } })
  assert.equal(h.page.data.posts[0].isLiked, false)
  assert.equal(toasts.length, 0)
  // 换俱乐部同理:另一个俱乐部的同 id 帖子不受旧锁影响
  uid = '7'
  h.page.data.clubId = '10'
  h.page.onLikePost(likeEvent(5))
  assert.equal(like('/api/club/post/like').length, 3)
})

// P1-023:点赞带上发起时的目标态,服务端按目标态幂等
test('点赞请求带目标态 liked:未赞→true,已赞→false', () => {
  const h = createDetailHarness()
  h.page.data.posts = [{ id: 5, isLiked: false }, { id: 6, isLiked: true }]
  h.page.onLikePost(likeEvent(5))
  h.page.onLikePost(likeEvent(6))
  assert.deepEqual(byUrl(h.requests, '/api/club/post/like').map((r) => JSON.parse(r.data)),
    [{ postId: 5, liked: true }, { postId: 6, liked: false }])
})

// P0-82:动态流分页——首页带 limit,滚到底按最小非置顶 id 续页、去重追加;整页回读后迟到的续页丢弃
test('动态流分页:首页 limit=20,满页才续页,续页带 beforeId 且不重复追加', () => {
  const h = createDetailHarness()
  h.page.data.activeTab = 'posts'
  const page = (from, to) => { const out = []; for (let id = from; id >= to; id -= 1) out.push({ id, authorMemberId: 1, content: 'p' + id }); return out }
  h.page.loadPosts()
  const lists = () => byUrl(h.requests, '/api/club/post/list')
  assert.deepEqual(JSON.parse(lists()[0].data), { id: '9', clubId: '9', limit: 20 })
  lists()[0].success({ code: '200', data: [{ id: 99, isPinned: 1, type: 2, authorMemberId: 1, content: '置顶' }].concat(page(50, 31)) })
  assert.equal(h.page.data.posts.length, 21)
  assert.equal(h.page._postsHasMore, true)

  h.page.onReachBottom()
  h.page.onReachBottom()
  assert.equal(lists().length, 2, '续页在途时不重复发')
  assert.deepEqual(JSON.parse(lists()[1].data), { id: '9', clubId: '9', limit: 20, beforeId: 31 })
  lists()[1].success({ code: '200', data: [{ id: 31, authorMemberId: 1, content: 'dup' }].concat(page(30, 25)) })
  assert.equal(h.page.data.posts.length, 27, '与已加载重复的帖子不再追加')
  assert.equal(h.page._postsHasMore, false, '不满一页即到底')
  h.page.onReachBottom()
  assert.equal(lists().length, 2)

  // 回读保留已加载深度(26 条非置顶,上限 50)
  h.page.loadPosts()
  assert.equal(JSON.parse(lists()[2].data).limit, 26)
})

test('动态流分页:续页在途时整页回读,迟到的续页结果丢弃', () => {
  const h = createDetailHarness()
  h.page.data.activeTab = 'posts'
  const page = (from, to) => { const out = []; for (let id = from; id >= to; id -= 1) out.push({ id, authorMemberId: 1, content: 'p' + id }); return out }
  h.page.loadPosts()
  const lists = () => byUrl(h.requests, '/api/club/post/list')
  lists()[0].success({ code: '200', data: page(40, 21) })
  h.page.onReachBottom()
  h.page.loadPosts()
  lists()[2].success({ code: '200', data: page(41, 22) })
  lists()[1].success({ code: '200', data: page(20, 1) })
  assert.equal(h.page.data.posts.length, 20)
  assert.equal(h.page.data.posts[0].id, 41)
})

const stateUrl = '/api/club/post/like-state'
const stateReply = (id, clubId = '9', isLiked = true, likeCount = 3) =>
  ({ code: 200, data: { id: String(id), clubId, isLiked, likeCount } })
function unknownLike(h, id) {
  h.page.onLikePost(likeEvent(id))
  const writes = byUrl(h.requests, '/api/club/post/like')
  writes[writes.length - 1].fail()
  const reads = byUrl(h.requests, stateUrl)
  return reads[reads.length - 1]
}

test('首条/51条以后/80条置顶之外的原帖都只定点回读，不加载或扫描列表', () => {
  for (const pinned of [0, 1]) {
    const h = createDetailHarness()
    h.page.data.posts = Array.from({ length: 81 }, (_, index) =>
      ({ id: String(1000 - index), clubId: '9', isPinned: pinned, isLiked: false }))
    const read = unknownLike(h, '920')
    assert.deepEqual(JSON.parse(read.data), { id: '920', clubId: '9' })
    read.success(stateReply('920', '9', true, null))
    assert.equal(h.page.data.posts[80].isLiked, true)
    assert.equal(h.page.data.posts[80].likeCount, null, 'null计数保持真实')
    assert.equal(h.page.data.posts[0].isLiked, false, '不更新邻帖')
    assert.equal(byUrl(h.requests, '/api/club/post/list').length, 0)
    assert.equal(byUrl(h.requests, '/api/club/post/like').length, 1, '回读不自动POST')
  }
})

test('合法Long全范围无加1/Number损精；不安全number及非法ID不发送', () => {
  for (const id of ['1', '9007199254740993', '9223372036854775807']) {
    const h = createDetailHarness()
    h.page.data.clubId = '9223372036854775807'
    h.page.data.posts = [{ id, isLiked: false }]
    const read = unknownLike(h, id)
    assert.deepEqual(JSON.parse(read.data), { id, clubId: '9223372036854775807' })
    assert.equal(JSON.parse(h.requests[0].data).postId, id)
    read.success(stateReply(id, h.page.data.clubId))
    assert.equal(h.page.data.posts[0].isLiked, true)
  }
  for (const id of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1, '9223372036854775808', '1e3', '']) {
    const h = createDetailHarness()
    h.page.onLikePost(likeEvent(id))
    assert.equal(h.requests.length, 0)
  }
})

test('不存在/404/拒权/邻帖/跨club/非布尔/畸形均保持未知，下次只查原帖', () => {
  const bad = [
    { code: 404 }, { code: 403 }, { code: 200, data: null }, { code: 200, data: [] },
    stateReply('6'), stateReply('5', '10'), stateReply('5', '9', 'true'),
    stateReply('5', '9', 1), { code: 200, data: { id: '5', isLiked: true } },
    stateReply('9223372036854775808'),
    stateReply('5', '9', true, '3'), stateReply('5', '9', true, 1.5),
  ]
  for (const reply of bad) {
    const h = createDetailHarness()
    h.page.data.posts = [{ id: 5, isLiked: false, likeCount: 2 }]
    unknownLike(h, 5).success(reply)
    h.page.onLikePost(likeEvent(5))
    assert.equal(byUrl(h.requests, stateUrl).length, 2)
    assert.equal(byUrl(h.requests, '/api/club/post/like').length, 1)
    assert.equal(h.page.data.posts[0].isLiked, false)
    assert.equal(h.page.data.posts[0].likeCount, 2)
  }
  const h = createDetailHarness()
  unknownLike(h, 5).successStatusAbnormal({ code: 404 })
  h.page.onLikePost(likeEvent(5))
  assert.equal(byUrl(h.requests, stateUrl).length, 2)
})

test('并发定点读互相独立，重复点击单飞，旧读重复回执不清新reading/pending', () => {
  const h = createDetailHarness()
  h.page.data.posts = [{ id: 5, isLiked: false }, { id: 6, isLiked: false }]
  const old = unknownLike(h, 5)
  const other = unknownLike(h, 6)
  h.page.onLikePost(likeEvent(5))
  assert.equal(byUrl(h.requests, stateUrl).length, 2)
  old.fail()
  h.page.onLikePost(likeEvent(5))
  const current = byUrl(h.requests, stateUrl)[2]
  const entry = h.page._likingPosts['7|9|5']
  const reading = entry.reading
  old.fail()
  old.success(stateReply('5'))
  assert.equal(h.page._likingPosts['7|9|5'], entry)
  assert.equal(entry.reading, reading)
  h.page.onLikePost(likeEvent(5))
  assert.equal(byUrl(h.requests, stateUrl).length, 3)
  other.success(stateReply('6'))
  assert.equal(h.page.data.posts[1].isLiked, true)
  assert.equal(h.page.data.posts[0].isLiked, false)
  current.success(stateReply('5'))
  assert.equal(h.page.data.posts[0].isLiked, true)
  h.page.onLikePost(likeEvent(5))
  const fresh = h.page._likingPosts['7|9|5']
  current.fail()
  current.success(stateReply('5', '9', false))
  assert.equal(h.page._likingPosts['7|9|5'], fresh)
})

test('发送前拒绝旧owner/club上下文；切换往返后迟到回包不解新锁', () => {
  for (const change of ['owner', 'club', 'detail']) {
    const h = createDetailHarness()
    let owner = '7'
    h.appRef.getUserID = () => owner
    h.page.data.posts = [{ id: 5, isLiked: false }]
    const old = unknownLike(h, 5)
    const key = '7|9|5'
    old.fail()
    if (change === 'owner') owner = '8'
    if (change === 'club') h.page.data.clubId = '10'
    if (change === 'detail') h.page._detailRequestSeq = 1
    h.page.readbackPostLike(key)
    assert.equal(byUrl(h.requests, stateUrl).length, 1, '旧key不发查询')
    h.page.onLikePost(likeEvent(6)) // 观察并建立新的上下文，原scope永久失效。
    owner = '7'; h.page.data.clubId = '9'
    if (change === 'detail') h.page._detailRequestSeq = 2
    h.page.onLikePost(likeEvent(5))
    const fresh = byUrl(h.requests, stateUrl)[1]
    const entry = h.page._likingPosts[key]
    const reading = entry.reading
    old.success(stateReply('5'))
    old.fail()
    assert.equal(h.page._likingPosts[key], entry)
    assert.equal(entry.reading, reading)
    assert.equal(h.page.data.posts[0].isLiked, false)
    fresh.success(stateReply('5'))
    assert.equal(h.page.data.posts[0].isLiked, true)
  }
})

test('旧POST重复回执及卸载回包不加载列表或清新pending', () => {
  const h = createDetailHarness()
  h.page.data.posts = [{ id: 5, isLiked: false }]
  const read = unknownLike(h, 5)
  const oldPost = byUrl(h.requests, '/api/club/post/like')[0]
  read.success(stateReply('5'))
  h.page.onLikePost(likeEvent(5))
  const fresh = h.page._likingPosts['7|9|5']
  oldPost.success({ code: 200 })
  oldPost.fail()
  assert.equal(h.page._likingPosts['7|9|5'], fresh)
  assert.equal(byUrl(h.requests, '/api/club/post/list').length, 0)
  byUrl(h.requests, '/api/club/post/like')[1].fail()
  const pending = h.page._likingPosts['7|9|5']
  const latest = byUrl(h.requests, stateUrl)[1]
  h.page._sceneGuardDestroyed = true
  latest.success(stateReply('5', '9', false))
  latest.fail()
  h.page.readbackPostLike('7|9|5')
  assert.equal(h.page._likingPosts['7|9|5'], pending)
  assert.equal(byUrl(h.requests, stateUrl).length, 2)
  assert.equal(h.page.data.posts[0].isLiked, true)
})
