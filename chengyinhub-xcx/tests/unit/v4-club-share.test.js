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
  const app = {
    globalData: {}, sendRequest(r) { requests.push(r) },
    getUserRole: () => 'player', getUserID: () => 7, getAvatar: () => '', getImgUrl: (v) => v,
  }
  vm.runInNewContext(read('pages/club/detail/index.js'), {
    getApp: () => app,
    Page(p) { definition = p },
    require(request) {
      const modules = {
        '../../../utils/motion.js': require('../../utils/motion.js'),
        '../../../utils/motion-preference.js': require('../../utils/motion-preference.js'),
        '../../../utils/scene-registry.js': { getScene: () => ({}) },
        '../../../utils/datetime': require('../../utils/datetime'),
        '../../../utils/mockData.js': { DEMO_NEARBY_CLUB_ID: -1 },
        '../utils/aiPlanToDraft.js': { aiPlanToDraft: () => ({}) },
        '../../../utils/group-code-session.js': { buildGroupCodeIssuePayload: () => ({}), listGroupCodeActivities: () => [] },
        '../../../utils/merchant-home-link.js': require('../../utils/merchant-home-link.js'),
        '../../../utils/ticket-source.js': require('../../utils/ticket-source.js'),
        '../../../utils/topic-share.js': require('../../utils/topic-share.js'),
        '../../../utils/response-shape.js': require('../../utils/response-shape.js'),
        '../../../utils/coop-invite-view.js': require('../../utils/coop-invite-view.js'),
        '../utils/club-event-calendar.js': require('../../pages/club/utils/club-event-calendar.js'),
        '../../../utils/feed-play-card.js': require('../../utils/feed-play-card.js'),
        '../../../utils/verification-scan.js': require('../../utils/verification-scan.js'),
        '../../../utils/write-action-workflow.js': require('../../utils/write-action-workflow.js'),
        '../../../utils/danger-actions.js': require('../../utils/danger-actions.js'),
      }
      if (modules[request]) return modules[request]
      if (request.endsWith('/utils/session/login-reload.js')) return require('../../utils/session/login-reload.js')
      throw new Error('unexpected require: ' + request)
    },
    Set, Date, JSON,
    wx: {
      navigateTo() {}, showToast(o) { toasts.push(o && o.title) }, showModal() {}, showShareMenu() {},
      setStorageSync() {}, getStorageSync() { return '' }, pageScrollTo() {},
    },
  }, { filename: 'pages/club/detail/index.js' })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb.call(this) },
  })
  return { page, requests, toasts, definition }
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

// ---- 以下记录当前行为(DEFECT):失效/无效链接没有显式状态 ----
test('[DEFECT 记录] 失效/已解散俱乐部链接:业务拒绝与网络失败同落 notFound,而 notFound 渲染为无文案的空白占位', () => {
  const h = loadDetail()
  h.page.onLoad({ id: '404404' })
  h.requests.find((r) => r.url === '/api/club/detail').success({ code: '500', msg: '俱乐部不存在或已解散' })
  assert.equal(h.page.data.notFound, true)
  assert.equal(h.page.data.club, null)
  // 后端给出的原因被丢弃,页面上没有任何字段承载它
  assert.ok(!JSON.stringify(h.page.data).includes('已解散'), '当前实现丢弃了失效原因(记录现状)')
  // wxml:notFound → <cy-error load-failure="{{true}}">,组件在 loadFailure 下只渲染 aria-hidden 空白 veil
  assert.match(read('pages/club/detail/index.wxml'), /<cy-error wx:if="\{\{notFound\}\}" load-failure="\{\{true\}\}"\s*\/>/)
  assert.match(read('components/cy/error/index.wxml'), /<view wx:if="\{\{loadFailure\}\}" aria-hidden="true" class="cy-error-veil/)
})

test('期望:失效/无效分享链接显示明确状态(如「俱乐部不存在或已解散」+ 返回入口)', { todo: 'DEFECT:pages/club/detail/index.wxml:24 notFound 一律用 load-failure 空白占位' }, () => {
  const h = loadDetail()
  h.page.onLoad({ id: '404404' })
  h.requests.find((r) => r.url === '/api/club/detail').success({ code: '500', msg: '俱乐部不存在或已解散' })
  assert.doesNotMatch(read('pages/club/detail/index.wxml'), /<cy-error wx:if="\{\{notFound\}\}" load-failure="\{\{true\}\}"\s*\/>/)
})

test('[现状记录] 俱乐部详情未实现 onShareTimeline,朋友圈分享不可用', () => {
  const h = loadDetail()
  assert.equal(typeof h.definition.onShareAppMessage, 'function')
  assert.equal(typeof h.definition.onShareTimeline, 'undefined')
})
