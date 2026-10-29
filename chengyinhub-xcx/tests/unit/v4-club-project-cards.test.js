'use strict'
// SC-UI-PROJECT-CARDS:pages/club/detail 的项目卡(ev-card → goTopic、manage-project → goManageTopic、
// 活动导演台 → goGameDirector、场次工具 → openActivityTools)。
// 验收:每张卡按身份去对页面并带对参数;未加入/未登录用户永远进不了管理面。
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
  const navigations = []
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
        '../../../utils/group-code-session.js': require('../../utils/group-code-session.js'),
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
      navigateTo(o) { navigations.push(o.url); if (o.complete) o.complete() }, showToast() {}, showModal() {}, showShareMenu() {},
      setStorageSync() {}, getStorageSync() { return '' }, pageScrollTo() {},
    },
  }, { filename: 'pages/club/detail/index.js' })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb.call(this) },
  })
  page.refreshCoopPublisherAccess = () => {}
  return { page, requests, navigations }
}

const tap = (dataset) => ({ currentTarget: { dataset } })
const MANAGE_URL = /\/pages\/club\/(topic-detail|event-ops|roles|notify)\//

function tapAllCards(h) {
  h.page.goTopic(tap({ id: 701 }))
  h.page.goManageTopic(tap({ id: 702 }))
  h.page.goGameDirector(tap({ topicId: 703, gameModuleEnabled: true }))
  h.page.openActivityTools(tap({ id: 704 }))
}

test('未加入的访客点每张项目卡:只进玩家报名页,零管理路由、零管理读取', () => {
  const h = loadDetail()
  h.page.data.clubId = 9
  h.page.data.club = { id: 9, isOwner: false, isJoined: false, viewerIsAdmin: false }
  tapAllCards(h)
  assert.deepEqual(h.navigations, ['/pages/topic/index/index?id=701'])
  assert.equal(h.requests.length, 0, '访客不得触发 /api/topic/info-to-user 等管理读取')
})

test('详情尚未加载(未登录/失败)时点卡:不进任何管理面', () => {
  const h = loadDetail()
  h.page.data.clubId = 9
  h.page.data.club = null
  h.page.data.canManageActivities = true // 残留权限也不得放行
  tapAllCards(h)
  assert.ok(h.navigations.every((u) => !MANAGE_URL.test(u)), h.navigations.join(','))
  assert.equal(h.requests.length, 0)
})

test('上一身份残留的 canManageActivities:新详情判定为访客后撤权,卡片回落玩家页', () => {
  const h = loadDetail()
  h.page.loadClubAccess = () => {}
  Object.assign(h.page.data, { clubId: 9, canManageActivities: true, canOperateEvents: true, hasEventScope: true })
  h.page.loadDetail()
  h.requests.find((r) => r.url === '/api/club/detail')
    .success({ code: '200', data: { id: 9, isOwner: false, isJoined: false } })
  assert.equal(h.page.data.canManageActivities, false)
  h.requests.length = 0
  tapAllCards(h)
  assert.deepEqual(h.navigations, ['/pages/topic/index/index?id=701'])
  assert.equal(h.requests.length, 0)
})

test('主理人:活动卡与管理项目卡进主办视图并带 clubId;导演台带 topicId+clubId+activityId', () => {
  const h = loadDetail()
  h.page.data.clubId = 9
  h.page.data.club = { id: 9, isOwner: true }
  h.page.goTopic(tap({ id: 701 }))
  h.page.goManageTopic(tap({ id: 702 }))
  h.page.goGameDirector(tap({ topicId: 703, gameModuleEnabled: true }))
  const req = h.requests.find((r) => r.url === '/api/topic/info-to-user')
  assert.deepEqual(JSON.parse(JSON.stringify(req.data)), { id: 703 })
  req.success({ code: '200', data: { activityList: [{ id: 88, name: '场次' }] } })
  assert.deepEqual(h.navigations, [
    '/pages/club/topic-detail/index?topicId=701&clubId=9',
    '/pages/club/topic-detail/index?topicId=702&clubId=9',
    '/pages/club/topic-detail/index?topicId=703&clubId=9&activityId=88',
  ])
})

test('管理卡与导演台在 wxml 中只渲染在有管理身份的分支里', () => {
  const wxml = read('pages/club/detail/index.wxml')
  assert.match(wxml, /class="ev-card" data-id="\{\{item\.id\}\}" bindtap="goTopic"/)
  assert.match(wxml, /wx:if="\{\{club\.isOwner && item\.id && item\.gameModuleEnabled\}\}"[^>]*catchtap="goGameDirector"/)
  assert.match(wxml, /wx:if="\{\{club\.isOwner \|\| club\.viewerIsAdmin \|\| canManageActivities \|\| canOperateEvents \|\| hasEventScope\}\}" class="manage-project-tools"/)
})
