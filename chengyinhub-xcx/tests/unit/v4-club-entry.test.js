'use strict'
// SC-CL-ENTRY:俱乐部 tab(pages/talent/list,app.json tabBar「俱乐部」)→ 俱乐部详情(pages/club/detail)。
// 验收:合法空结果进 empty;首载失败进 error,不冒充 empty;失败也不冒充「还没有俱乐部」。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

const ROOT = path.resolve(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

function setPath(target, dotted, value) {
  const parts = dotted.split('.')
  let cursor = target
  parts.slice(0, -1).forEach((part) => { cursor = cursor[part] || (cursor[part] = {}) })
  cursor[parts.at(-1)] = value
}

function loadList(userId) {
  const requests = []
  const navigations = []
  let definition
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44, menuButtonInfo: { left: 300 } },
    sendRequest(options) { requests.push(options) },
    getRequestErrorMessage(res, fallback) { return (res && res.msg) || fallback },
    getUserID() { return userId === undefined ? 7 : userId },
    getUserRole() { return 'player' },
    getUserType() { return 1 },
    getImgUrl(v) { return v || '' },
  }
  global.getApp = () => app
  global.Page = (page) => { definition = page }
  global.wx = {
    getStorageSync() { return '' },
    showToast() {}, showModal() {}, stopPullDownRefresh() {},
    navigateTo(o) { navigations.push(o.url) },
  }
  const modulePath = path.join(ROOT, 'pages/talent/list/index.js')
  delete require.cache[require.resolve(modulePath)]
  require(modulePath)
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, cb) { Object.entries(patch).forEach(([k, v]) => setPath(this.data, k, v)); if (cb) cb.call(this) },
  })
  return { page, requests, navigations }
}

// 依据 wxml 的分支顺序判断用户看到的是哪种状态(skeleton / error / empty / list)
function visibleClubState(d) {
  if (!d.clubsLoaded && !d.clubsError) return 'skeleton'
  if (d.clubsError && d.clubsState !== 'stale-error') return 'error'
  if (!d.clubsLoaded) return 'blank'
  const any = d.myClubs.length || d.joinedClubs.length || d.nearbyClubs.length
  return any ? 'list' : 'empty'
}

test('俱乐部 tab 首载:业务失败 / 网络失败 / 非法载荷都落 error,绝不显示「还没有俱乐部」', () => {
  const cases = [
    (r) => r.success({ code: '500', msg: '服务繁忙' }),
    (r) => r.success({ code: '200', data: { owned: [], joined: {}, nearby: [], leaderStatus: {} } }),
    (r) => r.fail({ errMsg: 'request:fail offline' }),
  ]
  for (const respond of cases) {
    const { page, requests } = loadList()
    page.loadClubHome()
    assert.equal(requests[0].url, '/api/club/home')
    assert.equal(visibleClubState(page.data), 'skeleton')
    respond(requests[0])
    assert.equal(page.data.clubsState, 'error')
    assert.equal(visibleClubState(page.data), 'error', '加载失败不得被渲染成空态')
  }
})

test('俱乐部 tab 首载:合法空结果落 empty,而不是错误', () => {
  const { page, requests } = loadList()
  page.loadClubHome()
  requests[0].success({ code: '200', data: { owned: [], joined: [], nearby: [], leaderStatus: { isClubLeader: false } } })
  assert.equal(page.data.clubsState, 'empty')
  assert.equal(page.data.clubsError, false)
  assert.equal(visibleClubState(page.data), 'empty', '合法空列表不得被渲染成加载失败')
})

test('俱乐部卡按 id 进详情;wxml 分支顺序保证 error 先于 empty', () => {
  const { page, requests, navigations } = loadList()
  page.loadClubHome()
  requests[0].success({ code: '200', data: { owned: [{ id: 1, name: 'A' }], joined: [{ id: 2, name: 'B' }], nearby: [], leaderStatus: {} } })
  assert.equal(visibleClubState(page.data), 'list')
  page.goClubDetail({ currentTarget: { dataset: { id: 2 } } })
  assert.deepEqual(navigations, ['/pages/club/detail/index?id=2'])
  const wxml = read('pages/talent/list/index.wxml')
  const errIdx = wxml.indexOf("wx:elif=\"{{clubsError && clubsState !== 'stale-error'}}\"")
  const emptyIdx = wxml.indexOf('title="还没有俱乐部"')
  assert.ok(errIdx > 0 && emptyIdx > errIdx, 'error 分支必须在 empty 之前，且 empty 只在 clubsLoaded 区块内')
})

function loadDetail() {
  let definition
  const navigations = []
  const requests = []
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
        '../../../utils/club-post-repost.js': require('../../utils/club-post-repost.js'),
      }
      if (modules[request]) return modules[request]
      if (request.endsWith('/utils/session/login-reload.js')) return require('../../utils/session/login-reload.js')
      throw new Error('unexpected require: ' + request)
    },
    Set, Date, JSON,
    wx: { navigateTo(o) { navigations.push(o.url) }, showToast() {}, showModal() {}, showShareMenu() {}, setStorageSync() {}, getStorageSync() { return '' }, pageScrollTo() {} },
  }, { filename: 'pages/club/detail/index.js' })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb.call(this) },
  })
  return { page, requests, navigations }
}

test('俱乐部详情首载网络失败进 notFound 失败分支(不是空态、不是登录引导);成功则渲染俱乐部', () => {
  const failed = loadDetail()
  failed.page.onLoad({ id: '9' })
  const req = failed.requests.find((r) => r.url === '/api/club/detail')
  assert.deepEqual(JSON.parse(req.data), { id: 9 })
  req.fail({ errMsg: 'request:fail offline' })
  req.complete && req.complete()
  assert.equal(failed.page.data.notFound, true)
  assert.equal(failed.page.data.loginRequired, false)
  assert.equal(failed.page.data.club, null)

  const ok = loadDetail()
  ok.page.onLoad({ id: '9' })
  const r2 = ok.requests.find((r) => r.url === '/api/club/detail')
  r2.success({ code: '200', data: { id: 9, name: '夜行', isOwner: false, isJoined: false } })
  assert.equal(ok.page.data.notFound, false)
  assert.equal(ok.page.data.club.id, 9)
  assert.equal(ok.page.data.canSeeMembers, false, '访客不得获得成员可见权')
  // wxml:notFound 分支先于 skeleton / club,不会掉进任何 cy-empty
  const wxml = read('pages/club/detail/index.wxml')
  assert.match(wxml, /<cy-error wx:if="\{\{notFound\}\}" load-failure="\{\{true\}\}"\s*\/>\s*<!--[^>]*-->\s*<cy-empty wx:elif="\{\{loginRequired\}\}"/)
})
