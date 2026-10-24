'use strict'
// SC-CL-SHARE:pages/club/detail 分享 → 接收方打开。
// 发送侧带票归因(sourceClubId/clubCode)已由 tandianri-b5-role-contracts / topic-share-card-contract 覆盖;
// 这里补「接收方往返」与「失效/无效链接的显式状态」。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

const ROOT = path.resolve(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

function loadDetail() {
  let definition
  const requests = []
  const toasts = []
  const nav = []
  const app = {
    globalData: {}, sendRequest(r) { requests.push(r) },
    getUserRole: () => 'player', getUserID: () => 7, getAvatar: () => '', getImgUrl: (v) => v,
  }
  vm.runInNewContext(read('pages/club/detail/index.js'), {
    getApp: () => app,
    Page(p) { definition = p },
    require(request) {
      if (request === '../../../utils/registration-seat.js') return require('../../utils/registration-seat.js')
      const modules = {
        '../../../utils/motion.js': require('../../utils/motion.js'),
        '../../../utils/media-preview.js': require('../../utils/media-preview.js'),  // 本线俱乐部详情图片预览(#293)
        '../../../utils/motion-preference.js': require('../../utils/motion-preference.js'),
        '../../../utils/scene-registry.js': { getScene: () => ({}) },
        '../../../utils/datetime': require('../../utils/datetime'),
        '../../../utils/mockData.js': { DEMO_NEARBY_CLUB_ID: -1 },
        '../utils/mockData.js': { DEMO_NEARBY_CLUB_ID: -1 },  // #307 后 mockData 迁到 pages/club/utils/
        '../utils/aiPlanToDraft.js': { aiPlanToDraft: () => ({}) },
        '../../../utils/group-code-session.js': { buildGroupCodeIssuePayload: () => ({}), listGroupCodeActivities: () => [] },
        '../../../utils/merchant-home-link.js': require('../../utils/merchant-home-link.js'),
        '../../../utils/ticket-source.js': require('../../utils/ticket-source.js'),
        '../../../utils/topic-share.js': require('../../utils/topic-share.js'),
        '../../../utils/response-shape.js': require('../../utils/response-shape.js'),
        '../../../utils/coop-invite-view.js': require('../../utils/coop-invite-view.js'),
        '../utils/club-event-calendar.js': require('../../pages/club/utils/club-event-calendar.js'),
        '../utils/edition-empty-reason.js': require('../../pages/club/utils/edition-empty-reason.js'),
        '../../../utils/feed-play-card.js': require('../../utils/feed-play-card.js'),
        '../../../utils/verification-scan.js': require('../../utils/verification-scan.js'),
        '../../../utils/write-action-workflow.js': require('../../utils/write-action-workflow.js'),
        '../../../utils/merchant-verification.js': require('../../utils/merchant-verification.js'),
        '../../../utils/danger-actions.js': require('../../utils/danger-actions.js'),
        // 移植分支的俱乐部详情多引的模块（置顶公告、合作回跳、发布者权限、通用弹层/提示）：按真实模块接入。
        '../../../utils/coop-invite-return.js': require('../../subpackageMember/utils/coop-invite-return.js'),
        '../../../utils/coop-publisher-access.js': require('../../subpackageMember/utils/coop-publisher-access.js'),
        '../../../utils/display-text.js': require('../../utils/display-text.js'),
        '../../../utils/loading.js': require('../../utils/loading.js'),
        '../../../utils/modal.js': require('../../utils/modal.js'),
        '../../../utils/roleGuard.js': require('../../utils/roleGuard.js'),
        '../../../utils/subscribe.js': require('../../utils/subscribe.js'),
        '../../../utils/toast.js': require('../../utils/toast.js'),
        '../utils/club-pinned-notice.js': require('../../pages/club/utils/club-pinned-notice.js'),
      }
      if (modules[request]) return modules[request]
      if (request.endsWith('/utils/session/login-reload.js')) return require('../../utils/session/login-reload.js')
      throw new Error('unexpected require: ' + request)
    },
    Set, Date, JSON,
    wx: {
      navigateTo() {}, showToast(o) { toasts.push(o && o.title) }, showModal() {}, showShareMenu() {},
      setStorageSync() {}, getStorageSync() { return '' }, pageScrollTo() {},
      navigateBack(o) { nav.push('back'); if (o && o.fail) o.fail() }, switchTab(o) { nav.push(o && o.url) },
    },
  }, { filename: 'pages/club/detail/index.js' })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb.call(this) },
  })
  return { page, requests, toasts, definition, nav }
}

function parseRoute(url) {
  const [p, q = ''] = url.split('?')
  const query = {}
  q.split('&').filter(Boolean).forEach((kv) => { const [k, v] = kv.split('='); query[k] = decodeURIComponent(v) })
  return { path: p, query }
}

test('整页分享 → 接收方打开:路径落到同一 clubId 的详情并按该 id 读取', () => {
  const sender = loadDetail()
  sender.page.data.clubId = 9
  sender.page.data.club = { id: 9, name: '夜行' }
  const share = sender.page.onShareAppMessage({})
  const route = parseRoute(share.path)
  assert.equal(route.path, '/pages/club/detail/index')
  assert.deepEqual(route.query, { id: '9' })

  const receiver = loadDetail()
  receiver.page.onLoad(route.query)
  const req = receiver.requests.find((r) => r.url === '/api/club/detail')
  assert.ok(req, '接收方必须按分享里的 id 读取详情')
  assert.deepEqual(JSON.parse(req.data), { id: 9 })
})

test('帖文分享 → 接收方打开:clubId 与 postId 都被接收方取到', () => {
  const sender = loadDetail()
  sender.page.data.clubId = 9
  sender.page.data.club = { id: 9, name: '夜行' }
  const share = sender.page.onShareAppMessage({ target: { dataset: { postId: 55 } } })
  const route = parseRoute(share.path)
  assert.deepEqual(route.query, { id: '9', postId: '55' })

  const receiver = loadDetail()
  receiver.page.onLoad(route.query)
  assert.equal(receiver.page._requestedPostId, '55')
  assert.deepEqual(JSON.parse(receiver.requests.find((r) => r.url === '/api/club/detail').data), { id: 9 })
})

test('项目卡带票分享:sourceClubId 取本页 clubId,clubCode 与期次一致', () => {
  const sender = loadDetail()
  sender.page.data.clubId = 9
  sender.page.data.club = { id: 9, name: '夜行', isOwner: true }
  sender.page.data.shareEditions = [{ topicId: 77, topicName: '第一期', executingClubId: 9 }]
  const route = parseRoute(sender.page.onShareAppMessage({ target: { dataset: { topicId: 77 } } }).path)
  assert.equal(route.path, '/pages/topic/index/index')
  assert.deepEqual(route.query, { id: '77', sourceClubId: '9', clubCode: 'club-9-t77' })
})

test('无 id 的链接:不发 id:null 请求,并给出「缺少俱乐部」提示', () => {
  const h = loadDetail()
  h.page.onLoad({})
  assert.equal(h.requests.filter((r) => r.url === '/api/club/detail').length, 0)
  assert.equal(h.page.data.notFound, true)
})

// ---- SC-CL-SHARE(已修):失效/无效链接给显式状态;网络失败仍走既有 load-failure(F-W1-12) ----
test('期望:失效/无效分享链接显示明确状态(如「俱乐部不存在或已解散」+ 返回入口)', () => {
  const h = loadDetail()
  h.page.onLoad({ id: '404404' })
  h.requests.find((r) => r.url === '/api/club/detail').success({ code: '500', msg: '俱乐部不存在或已解散' })
  // 业务拒绝不再落 notFound 留白,服务端原因被承载出来
  assert.equal(h.page.data.notFound, false)
  assert.equal(h.page.data.club, null)
  assert.equal(h.page.data.clubGoneText, '俱乐部不存在或已解散')
  const wxml = read('pages/club/detail/index.wxml')
  const gone = wxml.match(/<cy-error wx:elif="\{\{clubGoneText\}\}"[^>]*\/>/)
  assert.ok(gone, '失效态必须渲染成有文案的 cy-error')
  assert.doesNotMatch(gone[0], /load-failure/, '失效态不能是空白占位')
  assert.match(gone[0], /title="\{\{clubGoneText\}\}"/)
  // 返回入口 = 导航栏常驻返回(goBack;分享直达页栈只有一页时 switchTab 回发现页),失效态不另加按钮
  assert.match(gone[0], /sub="[^"]*返回[^"]*"/, '失效态要指明怎么返回')
  assert.match(wxml, /<cy-nav-bar custom-back bind:back="goBack" wx:if="\{\{!\(groupCodeVisible && groupCodeState !== 'selecting'\)\}\}">/)
  h.page.goBack()
  assert.deepEqual(h.nav, ['back', '/pages/talent/list/index'], '分享直达(无上一页)也必须能回去')
  // 200 但没有 data:同样给明确的不存在态
  const empty = loadDetail()
  empty.page.onLoad({ id: '404405' })
  empty.requests.find((r) => r.url === '/api/club/detail').success({ code: '200', data: null })
  assert.equal(empty.page.data.clubGoneText, '俱乐部不存在或已解散')
  // 网络失败不是失效:仍走 notFound(既有 load-failure 分支),不冒充「不存在」
  const offline = loadDetail()
  offline.page.onLoad({ id: '9' })
  offline.requests.find((r) => r.url === '/api/club/detail').fail({ errMsg: 'request:fail offline' })
  assert.equal(offline.page.data.notFound, true)
  assert.equal(offline.page.data.clubGoneText, '')
})

test('[现状记录] 俱乐部详情未实现 onShareTimeline,朋友圈分享不可用', () => {
  const h = loadDetail()
  assert.equal(typeof h.definition.onShareAppMessage, 'function')
  assert.equal(typeof h.definition.onShareTimeline, 'undefined')
})
