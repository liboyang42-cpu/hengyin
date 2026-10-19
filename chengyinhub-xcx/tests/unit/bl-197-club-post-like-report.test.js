const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-197:俱乐部动态点赞/举报——业务失败与网络失败都要有提示;切换式点赞在返回前不重复发

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
      if (request === '../../../utils/danger-actions.js') return require(path.join(ROOT, 'utils/danger-actions.js'))
      if (request === '../utils/owner-action-guard.js') return require(path.join(ROOT, 'pages/club/utils/owner-action-guard.js'))
      if (request === '../../../utils/club-pinned-notice.js') return require('../../utils/club-pinned-notice.js')
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
  })
  page.data.clubId = '9'
  page.data.myMemberId = '7'
  return { page, requests }
}

function byUrl(requests, url) {
  return requests.filter((request) => request.url === url)
}

const likeEvent = (id) => ({ detail: { postId: id }, currentTarget: { dataset: {} } })

test('点赞业务失败提示服务端文案,网络失败提示「网络异常，请重试」,成功只回读', () => {
  const h = createDetailHarness()
  toasts.length = 0
  h.page.onLikePost(likeEvent(5))
  byUrl(h.requests, '/api/club/post/like')[0].success({ code: '500', msg: '请先登录' })
  assert.equal(toasts.pop(), '请先登录')

  h.page.onLikePost(likeEvent(5))
  byUrl(h.requests, '/api/club/post/like')[1].success({ code: '500' })
  assert.equal(toasts.pop(), '点赞没成功')

  h.page.onLikePost(likeEvent(5))
  byUrl(h.requests, '/api/club/post/like')[2].fail()
  assert.equal(toasts.pop(), '网络异常，请重试')

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
