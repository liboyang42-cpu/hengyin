const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-196:俱乐部动态评论区——加载失败不能显示成「还没有评论」;快速切换动态时旧回包不得覆盖当前展开的那条

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
      if (request === '../../../utils/registration-seat.js') return require('../../utils/registration-seat.js')
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
      showModal() {},
      showToast() {},
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
    '../utils/edition-empty-reason.js': require('../../pages/club/utils/edition-empty-reason.js'),
  })
  page.data.clubId = '9'
  page.data.myMemberId = '7'
  return { page, requests }
}

function commentRequests(requests) {
  return requests.filter((request) => request.url === '/api/club/post/comment/list')
}

test('评论业务失败与网络失败写 commentsError,不出空态;成功且为空才出空态', () => {
  const biz = createDetailHarness()
  biz.page.onToggleComments({ detail: { postId: 11 }, currentTarget: { dataset: {} } })
  commentRequests(biz.requests)[0].success({ code: '500', msg: '动态不存在' })
  assert.equal(biz.page.data.commentsError, '动态不存在')
  assert.equal(biz.page.data.commentsLoaded, false)

  const net = createDetailHarness()
  net.page.onToggleComments({ detail: { postId: 11 }, currentTarget: { dataset: {} } })
  commentRequests(net.requests)[0].fail()
  assert.equal(net.page.data.commentsError, '网络异常，请重试')
  assert.equal(net.page.data.commentsLoaded, false)

  const empty = createDetailHarness()
  empty.page.onToggleComments({ detail: { postId: 11 }, currentTarget: { dataset: {} } })
  commentRequests(empty.requests)[0].success({ code: '200', data: [] })
  assert.equal(empty.page.data.commentsError, '')
  assert.equal(empty.page.data.commentsLoaded, true)
  assert.equal(empty.page.data.comments.length, 0)

  const view = read('pages/club/detail/index.wxml')
  assert.match(view, /<cy-error wx:if="\{\{commentsError\}\}"[^>]*bind:retry="retryLoadComments"/)
  assert.match(view, /<cy-empty wx:elif="\{\{commentsLoaded && !comments\.length\}\}"[^>]*还没有评论/)
})

test('重试重新拉当前展开动态的评论', () => {
  const h = createDetailHarness()
  h.page.onToggleComments({ detail: { postId: 11 }, currentTarget: { dataset: {} } })
  commentRequests(h.requests)[0].fail()
  h.page.retryLoadComments()
  const reqs = commentRequests(h.requests)
  assert.equal(reqs.length, 2)
  assert.equal(JSON.parse(reqs[1].data).postId, 11)
  reqs[1].success({ code: '200', data: [{ id: 1, content: '好', createTime: '2026-10-17T10:00:00' }] })
  assert.equal(h.page.data.commentsError, '')
  assert.equal(h.page.data.comments.length, 1)
})

test('切换动态后旧回包被丢弃,不写到正在展开的动态下', () => {
  const h = createDetailHarness()
  h.page.onToggleComments({ detail: { postId: 11 }, currentTarget: { dataset: {} } })
  h.page.onToggleComments({ detail: { postId: 22 }, currentTarget: { dataset: {} } })
  const [first, second] = commentRequests(h.requests)
  second.success({ code: '200', data: [{ id: 2, content: 'B 的评论' }] })
  first.success({ code: '200', data: [{ id: 1, content: 'A 的评论' }] })
  assert.equal(h.page.data.openCommentPost, 22)
  assert.deepEqual(h.page.data.comments.map((c) => c.id), [2])
  first.fail()
  assert.equal(h.page.data.commentsError, '')
})
