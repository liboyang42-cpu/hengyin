const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js') // node:vm + 沙箱里装真 utils/toast.js·loading.js

const ROOT = path.resolve(__dirname, '../..')
const DETAIL_JS = 'pages/club/detail/index.js'
const DETAIL_WXML = 'pages/club/detail/index.wxml'
const SETTINGS_JS = 'pages/shezhi/shezhi.js'
const WORKBENCH_JS = 'pages/club/workbench/index.js'
const read = (relativePath) => fs.readFileSync(path.join(ROOT, relativePath), 'utf8')

function loadDetail() {
  let definition
  const navigations = []
  const requests = []
  const scrolls = []
  const modals = []
  const app = {
    globalData: {},
    sendRequest(request) { requests.push(request) },
    getUserRole: () => 'member',
    getAvatar: () => '',
  }
  vm.runInNewContext(read(DETAIL_JS), {
    getApp: () => app,
    Page(page) { definition = page },
    require(request) {
      const modules = {
        '../../../utils/motion.js': require('../../utils/motion.js'),
        '../../../utils/motion-preference.js': require('../../utils/motion-preference.js'),
        '../../../utils/scene-registry.js': { getScene: () => ({}) },
        '../../../utils/datetime': require('../../utils/datetime'),
        '../utils/mockData.js': { DEMO_NEARBY_CLUB_ID: -1 },
        '../utils/aiPlanToDraft.js': { aiPlanToDraft: () => ({}) },
        '../../../utils/group-code-session.js': { buildGroupCodeIssuePayload: () => ({}), listGroupCodeActivities: () => [] },
        // 真实现,不是桩:商家卡的落点地址正是本页要保证的东西之一
        '../../../utils/merchant-home-link.js': require('../../utils/merchant-home-link.js'),
        '../../../utils/ticket-source.js': require('../../utils/ticket-source.js'),
        '../../../utils/topic-share.js': require('../../utils/topic-share.js'),
        '../../../utils/response-shape.js': require('../../utils/response-shape.js'),
        // 邀约/合作池整形与 coop/list 共用一份(2026-09-08 抽出);这里给真实现,
        // 因为「可对接的活动」那段就是要保证它按真整形渲染,桩会把问题遮住
        '../../../utils/coop-invite-view.js': require('../../utils/coop-invite-view.js'),
        '../utils/club-event-calendar.js': require('../../pages/club/utils/club-event-calendar.js'),
        '../utils/edition-empty-reason.js': require('../../pages/club/utils/edition-empty-reason.js'),
        // 帖文卡的 variant 判定与广场共用一份,俱乐部页 require 的就是它
        '../../../utils/feed-play-card.js': require('../../utils/feed-play-card.js'),
        // CU-C-60/CU-C-107:俱乐部页新增的三个 require(扫码路由表 / 核销写闸 / 正文摘要),
        // 都是纯模块,沙箱里给真实现。
        '../../../utils/verification-scan.js': require('../../utils/verification-scan.js'),
        '../../../utils/write-action-workflow.js': require('../../utils/write-action-workflow.js'),
        '../../../utils/merchant-verification.js': require('../../utils/merchant-verification.js'),
        '../../../utils/danger-actions.js': require('../../utils/danger-actions.js'),
      }
      if (modules[request]) return modules[request]
      // PR10 F-W1-12:页面新依赖登录引导判定(真模块,无副作用)
      if (request.endsWith('/utils/session/login-reload.js')) return require('../../utils/session/login-reload.js')
      // BL-20:俱乐部详情图片预览走 utils/media-preview(纯模块,给真实现)
      if (request === '../../../utils/media-preview.js') return require('../../utils/media-preview.js')
      if (request === '../utils/club-pinned-notice.js') return require('../../pages/club/utils/club-pinned-notice.js')
      throw new Error(`unexpected require: ${request}`)
    },
    Set,
    Date,
    JSON,
    wx: {
      navigateTo(options) { navigations.push(options.url); if (options.complete) options.complete() },
      pageScrollTo(options) { scrolls.push(options) },
      showToast() {},
      showModal(options) { modals.push(options) },
      showShareMenu() {},
      setStorageSync() {},
    },
  }, { filename: DETAIL_JS })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback.call(this) },
  })
  return { page, navigations, requests, scrolls, modals }
}

test('进入成员治理时收起设置，转让后返回不残留空白抽屉', () => {
  const { page, navigations } = loadDetail()
  Object.assign(page.data, {
    clubId: 7002, club: { isOwner: true }, settingsShow: true, settingsPanel: 'members',
  })
  page.goClubGovernance()
  assert.equal(page.data.settingsShow, false)
  assert.equal(page.data.settingsPanel, '')
  assert.deepEqual(navigations, ['/pages/club/governance/index?clubId=7002'])
  page.data.club.isOwner = false
  page.goClubGovernance()
  assert.equal(navigations.length, 1, '无权时仍不能进入治理')
})

function loadSettings() {
  let definition
  const navigations = []
  const requests = []
  const app = {
    globalData: {},
    sendRequest(request) { requests.push(request) },
  }
  vm.runInNewContext(read(SETTINGS_JS), {
    getApp: () => app,
    Page(page) { definition = page },
    require(request) {
      const modules = {
        '../../utils/im-send-recovery.js': require('../../utils/im-send-recovery.js'),
        '../../utils/motion.js': require('../../utils/motion.js'),
        '../../utils/motion-preference.js': require('../../utils/motion-preference.js'),
        '../../utils/identity/identity-policy.js': { isMerchantView: () => false },
        '../../utils/roleGuard.js': { isClubLeader: () => true, clear() {} },
        '../../utils/merchant-theme.js': { merchantPageShow() {}, merchantPageRestore() {} },
        '../../utils/scene-registry.js': { getScene: () => ({}) },
        '../../utils/roam-player-memory.js': require('../../utils/roam-player-memory.js'),
        '../../utils/scene-stack.js': {
          pushScene: (_stack, next) => [next],
          popScene: () => [],
          currentScene: (stack) => stack[stack.length - 1] || null,
          exitDecision: () => 'allow',
        },
      }
      if (modules[request]) return modules[request]
      // PR10 F-W1-12:页面新依赖登录引导判定(真模块,无副作用)
      if (request.endsWith('/utils/session/login-reload.js')) return require('../../utils/session/login-reload.js')
      // BL-20:俱乐部详情图片预览走 utils/media-preview(纯模块,给真实现)
      if (request === '../../../utils/media-preview.js') return require('../../utils/media-preview.js')
      if (request === '../utils/club-pinned-notice.js') return require('../../pages/club/utils/club-pinned-notice.js')
      throw new Error(`unexpected require: ${request}`)
    },
    wx: {
      navigateTo({ url }) { navigations.push(url) },
      showLoading() {},
      hideLoading() {},
    },
  }, { filename: SETTINGS_JS })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch) },
  })
  return { page, navigations, requests }
}

function loadWorkbench(options) {
  let definition
  let redirect = ''
  vm.runInNewContext(read(WORKBENCH_JS), {
    getApp: () => ({ globalData: {} }),
    Page(page) { definition = page },
    encodeURIComponent,
    wx: { redirectTo({ url }) { redirect = url }, navigateBack() {}, switchTab() {} },
  }, { filename: WORKBENCH_JS })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch) },
  })
  page.onLoad(options || {})
  return redirect
}

function parseMiniRoute(route) {
  const parts = String(route).split('?')
  return {
    path: parts[0],
    query: Object.fromEntries(new URLSearchParams(parts[1] || '')),
  }
}

test('quick action 与四个真 tab 分离，管理仅 owner 可见且已选 tab 不回退', () => {
  const view = read(DETAIL_WXML)
  const script = read(DETAIL_JS)
  // 2026-08-26:分享从资料区那条整宽按钮收成导航栏图标。契约不变 ——
  // 分享仍是独立入口、不混进 tab;只是不再占一整行。
  // 2026-09-24 CU-C-61:分享改成原生 <button open-type="share">(view+bindtap 打不开转发面板)。
  // 2026-10-22 owner:分享从导航栏挪到俱乐部名字行右端(.profile-share),仍是原生 share 按钮。
  assert.match(view, /class="profile-share"[^>]*open-type="share"/)
  assert.doesNotMatch(view, /class="cover-nav-act"[^>]*open-type="share"/, '导航栏不再放分享')
  assert.doesNotMatch(view, /class="club-share"/, '分享不得再回到整宽按钮')
  assert.match(view, /<cy-tabs\b[^>]*class="club-view-tabs"[^>]*tabs="\{\{clubTabs\}\}"[^>]*active="\{\{activeTab\}\}"[^>]*bind:change="switchTab"/)
  assert.doesNotMatch(view, />群组<\/text>/)
  assert.doesNotMatch(script, /onGroupQuick\s*\(/)

  const { page } = loadDetail()
  assert.deepEqual(Array.from(page.tabsForClub({ isOwner: false }), (item) => item.key), ['posts', 'events', 'overview'])
  assert.deepEqual(Array.from(page.tabsForClub({ isOwner: true }), (item) => item.key), ['posts', 'events', 'overview', 'manage'])
  page.data.activeTab = 'events'
  page.data.club = { isOwner: false }
  page.switchTab({ currentTarget: { dataset: { tab: 'events' } } })
  assert.equal(page.data.activeTab, 'events', '已选 tab 必须幂等')
  page.switchTab({ currentTarget: { dataset: { tab: 'manage' } } })
  assert.equal(page.data.activeTab, 'events', '非 owner 不得通过事件绕过管理 tab 门禁')
  page.data.club.isOwner = true
  page.switchTab({ currentTarget: { dataset: { tab: 'manage' } } })
  assert.equal(page.data.activeTab, 'manage')
})

// 2026-10-22 owner:不论身份,每次进俱乐部都先看「概览」(原先 owner/member 默认帖子)。
test('进俱乐部一律先看概览(owner/member/访客/商家都一样)', () => {
  const { page } = loadDetail()
  page.data.isMerchantViewer = false
  assert.equal(page.defaultTabForClub({ isOwner: true, isJoined: true }), 'overview')
  assert.equal(page.defaultTabForClub({ isOwner: false, isJoined: true }), 'overview')
  assert.equal(page.defaultTabForClub({ isOwner: false, isJoined: false }), 'overview')
  page.data.isMerchantViewer = true
  assert.equal(page.defaultTabForClub({ isOwner: false, isJoined: false }), 'overview')
  assert.equal(page.defaultTabForClub({ isOwner: true, isJoined: true }), 'overview')
})

test('管理深链只在详情回读 owner 身份后生效，参数不能替非 owner 越权', () => {
  const owner = loadDetail()
  owner.page.onLoad({ id: '9', tab: 'manage' })
  assert.notEqual(owner.page.data.activeTab, 'manage', '服务端身份刷新前不得提前打开管理 tab')
  const ownerDetail = owner.requests.find((request) => request.url === '/api/club/detail')
  ownerDetail.success({ code: '200', data: { id: 9, isOwner: true, isJoined: true } })
  assert.equal(owner.page.data.activeTab, 'manage', '服务端确认 owner 后才接受管理意图')

  const guest = loadDetail()
  guest.page.onLoad({ id: '9', tab: 'manage' })
  const guestDetail = guest.requests.find((request) => request.url === '/api/club/detail')
  guestDetail.success({ code: '200', data: { id: 9, isOwner: false, isJoined: false } })
  assert.equal(guest.page.data.activeTab, 'overview', '非 owner 塞 tab 参数仍只能进入公开默认视图')

  const genericOwner = loadDetail()
  genericOwner.page.onLoad({ id: '9', owner: '1' })
  const genericDetail = genericOwner.requests.find((request) => request.url === '/api/club/detail')
  genericDetail.success({ code: '200', data: { id: 9, isOwner: true, isJoined: true } })
  assert.equal(genericOwner.page.data.activeTab, 'overview', '通用 owner 入口也先看概览(owner 2026-10-22)')
})

test('设置管理入口与旧 workbench 壳显式传递 manage 意图', () => {
  const settings = loadSettings()
  settings.page.pickClub()
  settings.requests[0].success({ code: '200', data: { owned: [{ id: 9 }] } })
  const manageEntry = parseMiniRoute(settings.navigations[0])
  assert.equal(manageEntry.path, '/pages/club/detail/index')
  assert.deepEqual(manageEntry.query, { owner: '1', tab: 'manage' })

  settings.page.data.isClubView = true
  settings.page.goEdit()
  const editEntry = parseMiniRoute(settings.navigations[1])
  assert.equal(editEntry.path, '/pages/club/detail/index')
  assert.deepEqual(editEntry.query, { owner: '1', tab: 'manage' })

  const withId = parseMiniRoute(loadWorkbench({ id: 'club/7' }))
  assert.equal(withId.path, '/pages/club/detail/index')
  assert.deepEqual(withId.query, { id: 'club/7', tab: 'manage' })
  const withoutId = parseMiniRoute(loadWorkbench({}))
  assert.deepEqual(withoutId.query, { owner: '1', tab: 'manage' })
})

test('俱乐部主理人从活动或管理项目进主办视图，其余身份仍进玩家视图', () => {
  const owner = loadDetail()
  owner.page.data.club = { isOwner: true, id: 9 }
  owner.page.goTopic({ currentTarget: { dataset: { id: 701 } } })
  owner.page.goManageTopic({ currentTarget: { dataset: { id: 702 } } })
  // P0(2026-09-05):主办视图必须带 clubId,活动详情页的管理入口全靠它
  assert.deepEqual(owner.navigations, [
    '/pages/club/topic-detail/index?topicId=701&clubId=9',
    '/pages/club/topic-detail/index?topicId=702&clubId=9',
  ])

  const member = loadDetail()
  member.page.data.club = { isOwner: false }
  member.page.goTopic({ currentTarget: { dataset: { id: 703 } } })
  assert.deepEqual(member.navigations, ['/pages/topic/index/index?id=703'])
})

test('owner primary 按发布、邀请、待回应、已接受四阶段从现有读模型派生', () => {
  const { page, navigations } = loadDetail()
  Object.assign(page.data, {
    clubId: 9,
    topicState: 'ready',
    ownerProjectState: 'ready',
    inviteFeedbackState: 'ready',
    projectRows: [],
    sentInvites: [],
  })

  page._topics = []
  page.updateOwnerStage()
  assert.deepEqual([page.data.ownerStage, page.data.ownerPrimaryLabel], ['publish', '发布主题'])

  page._topics = [{ id: 901 }]
  page.updateOwnerStage()
  assert.deepEqual([page.data.ownerStage, page.data.ownerPrimaryLabel], ['invite', '邀请商家'])

  page.data.sentInvites = [{ topicId: 901, status: 0 }]
  page.updateOwnerStage()
  assert.deepEqual([page.data.ownerStage, page.data.ownerPrimaryLabel], ['pending', '查看邀请进度'])

  page.data.projectRows = [{ clubId: 9, acceptStatus: 'merchantAccepted' }]
  page.updateOwnerStage()
  assert.deepEqual([page.data.ownerStage, page.data.ownerPrimaryLabel], ['accepted', '管理项目'])
  page.data.activeTab = 'posts'
  page.onOwnerPrimary()
  assert.equal(page.data.activeTab, 'manage', '发布方已接受仍进原管理 tab')
  assert.deepEqual(navigations, [], '发布方不得被执行俱乐部的场次入口换轨')

  page.data.inviteFeedbackState = 'error'
  page.updateOwnerStage()
  assert.equal(page.data.ownerStage, 'accepted', '已有 merchantAccepted 事实时不应被邀约列表故障降级')

  page.data.projectRows = [{ clubId: 9, acceptStatus: 'merchantOpen' }]
  page.data.inviteFeedbackState = 'ready'
  page.data.sentInvites = [{ topicId: 901, status: 2 }]
  page.updateOwnerStage()
  assert.equal(page.data.ownerStage, 'invite', '拒绝/过期不得伪装成可管理项目')
})

test('合作生效只认 /api/project/my 当前 clubId 的 merchantAccepted', () => {
  const { page, requests } = loadDetail()
  Object.assign(page.data, {
    clubId: 9,
    club: { id: 9, isOwner: true },
    topicState: 'ready',
    inviteFeedbackState: 'ready',
    sentInvites: [],
  })
  page._topics = [{ id: 901 }]
  page.loadOwnerProjects()
  assert.equal(requests[0].url, '/api/project/my')
  assert.equal(requests[0].data.type, 'all', 'project/my 的 type 是 projectType，不得误传 topic 导致空列表')
  requests[0].success({ code: '200', data: { rows: [
    { id: 901, bizType: 'topic', clubId: 9, acceptStatus: 'merchantAccepted' },
    { id: 902, bizType: 'topic', clubId: 10, acceptStatus: 'merchantAccepted' },
  ] } })
  assert.deepEqual(Array.from(page.data.projectRows, (item) => item.id), [901])
  assert.equal(page.data.ownerStage, 'accepted')
  assert.equal(page.data.ownerPrimaryLabel, '管理项目')
})

test('执行俱乐部接受承接后，主行动直接进入场次运营', () => {
  const { page, navigations } = loadDetail()
  Object.assign(page.data, {
    clubId: 7002,
    club: { id: 7002, isOwner: true },
    topicState: 'ready',
    ownerProjectState: 'ready',
    inviteFeedbackState: 'ready',
    messageState: 'ready',
    manageTopics: [{ id: 990028, name: '自由探索' }],
    projectRows: [],
    sentInvites: [],
    messages: [{ topicId: 990028, toType: 'club', toId: 7002, status: 1 }],
  })
  page._topics = page.data.manageTopics
  page.updateOwnerStage()
  assert.equal(page.data.ownerStage, 'accepted')
  assert.equal(page.data.ownerPrimaryLabel, '活动运营')
  // CU-C-89(用户裁决 B):这个阶段是俱乐部级的(任意一个项目被承接就算到),没有单一「该项目」,
  // 入口也只能打开全团场次清单 —— 文案不许再按单项目口吻写。
  assert.equal(page.data.ownerStageHint, '已承接项目，可查看全部项目的场次运营')
  assert.deepEqual(Array.from(page.data.leadTimelineNodes), [], '执行方不展示发布方的「商家已接受」时间线')
  assert.equal(page.data.leadTimelineSummary, '')

  page.onOwnerPrimary()
  assert.deepEqual(navigations, ['/pages/club/event-ops/index?clubId=7002'])
})

test('管理阶段失败后的 primary 会同时重拉主题、探店日期次、项目与邀约，不留死锁', () => {
  const { page, requests } = loadDetail()
  Object.assign(page.data, { clubId: 9, club: { id: 9, isOwner: true }, ownerStage: 'error' })
  page.onOwnerPrimary()
  assert.deepEqual(requests.map((request) => request.url), [
    '/api/club/topics',
    '/api/club-compensation/editions',
    '/api/project/my',
    '/api/coop/list',
  ])
})

test('待回应 primary 进管理 tab 后直达「我发出的邀约」', () => {
  const { page, scrolls } = loadDetail()
  Object.assign(page.data, { club: { isOwner: true }, ownerStage: 'pending', activeTab: 'posts' })
  page.onOwnerPrimary()
  assert.equal(page.data.activeTab, 'manage')
  assert.deepEqual(JSON.parse(JSON.stringify(scrolls)), [{ selector: '#manage-invite-section', duration: 250, offsetTop: 24 }])
  assert.match(read(DETAIL_WXML), /id="manage-invite-section"[\s\S]*?我发出的邀约/)
})

test('F-43：邀请商家 primary 展开现有商家区并在渲染后滚动定位', () => {
  const { page, scrolls } = loadDetail()
  Object.assign(page.data, {
    club: { isOwner: true },
    ownerStage: 'invite',
    activeTab: 'manage',
    openManageSection: '',
  })

  page.onOwnerPrimary()

  assert.equal(page.data.activeTab, 'manage')
  assert.equal(page.data.openManageSection, 'merchants')
  assert.deepEqual(JSON.parse(JSON.stringify(scrolls)), [
    { selector: '#manage-merchants-section', duration: 250, offsetTop: 24 },
  ])
  assert.match(read(DETAIL_WXML), /id="manage-merchants-section"[\s\S]*?可对接商家/)
})

// 2026-08-26 管理 tab 收敛:独立的「待带队场次」区块撤掉了 —— 它和上面的项目列表
// 是同一批数据的过滤视图,首屏出现两次。契约收敛成两条仍然要成立的事实:
//   ① 管理 tab 的项目行点进去仍是主办视图(不是玩家视图)
//   ② loadTopics 仍按 endDate 正确标 ended —— 这是「哪些还要带队」的判据来源
test('管理 tab 的项目行进主办视图，且 loadTopics 仍按 endDate 标记 ended', () => {
  const view = read(DETAIL_WXML)
  assert.match(view, /class="manage-project"[\s\S]*?bindtap="goManageTopic"/)
  // 守的是「管理 tab 里不再出现与项目列表重复的过滤视图」。2026-09-02 活动 tab 按 Figma
  // 282:622 / 282:660 分了「即将举行 / 已结束」两段、也叫 upcomingTopics —— 那是另一个 tab
  // 的分段渲染,不是这条契约要挡的重复视图,所以守卫收窄到管理 tab 段落。
  const manageTab = view.slice(view.indexOf('class="feed feed--plain manage-feed"'))
  assert.ok(manageTab, '找不到管理 tab 段落，守卫失去锚点')
  assert.doesNotMatch(manageTab, /upcomingTopics/, '待带队场次区块已撤，不应再有残留绑定')

  const { page, requests } = loadDetail()
  page.data.clubId = 9
  page.data.club = { id: 9, isOwner: true }
  page.loadTopics()
  requests[0].success({ code: '200', data: [
    { id: 901, startDate: '2099-01-01 10:00:00', endDate: '2099-01-01 12:00:00' },
    { id: 902, startDate: '2000-01-01 10:00:00', endDate: '2000-01-01 12:00:00' },
  ] })
  assert.deepEqual(Array.from(page._topics, (item) => [item.id, item.ended]), [[901, false], [902, true]])
})

test('俱乐部主题卡以 productType 识别自由探索，不要求旧 mode 字段', () => {
  const { page, requests } = loadDetail()
  page.data.clubId = 9
  page.loadTopics()
  requests[0].success({ code: '200', data: [
    { id: 990028, name: '自由探索', productType: 2 },
    { id: 990029, name: '现行字段优先', productType: 1, mode: 2 },
    { id: 990030, name: '旧数据兼容', mode: 2 },
  ] })
  assert.deepEqual(Array.from(page._topics, item => item.categoryText), [
    '自由探索', '城市定向', '自由探索',
  ])
})

test('从发布/邀请/主办视图返回会重拉 owner 数据，首展不与 onLoad 双请求', () => {
  const { page } = loadDetail()
  let reloads = 0
  page.loadAll = () => { reloads += 1 }
  Object.assign(page.data, { clubId: 9, club: { id: 9, isOwner: true } })
  page.onShow()
  assert.equal(reloads, 0, '首次 onShow 应交给 onLoad 请求')
  page.onShow()
  assert.equal(reloads, 1, '返页必须重拉 loadAll，进而刷新 topics/project/my/coop/list')
})

test('普通成员首展仍由 onLoad 单飞，返页只先回读详情关系，失权后清掉旧名单', () => {
  const { page, requests } = loadDetail()
  page.refreshCoopPublisherAccess = () => {}
  Object.assign(page.data, {
    clubId: 9,
    club: { id: 9, isOwner: false, isJoined: true, viewerIsAdmin: false },
    canSeeMembers: true,
    canUseManageTab: false,
    members: [{ memberId: 41 }, { memberId: 42 }],
    memberStrip: [{ key: 'member:41', memberId: 41 }],
    membersState: 'ready',
    membersExpanded: true,
  })

  page.onShow()
  assert.equal(requests.filter(request => request.url === '/api/club/detail').length, 0,
    '首次 onShow 仍交给 onLoad，不重复读取详情')

  page.onShow()
  const detail = requests.find(request => request.url === '/api/club/detail')
  assert.ok(detail, '普通成员返回必须刷新现有详情关系')
  assert.equal(requests.some(request => request.url === '/api/club/members'), false,
    '关系确认前不得先拉新名单')

  detail.success({ code: '200', data: { id: 9, isOwner: false, isJoined: false, viewerIsAdmin: false } })
  assert.equal(page.data.canSeeMembers, false)
  assert.deepEqual(Array.from(page.data.members), [])
  assert.deepEqual(Array.from(page.data.memberStrip), [])
  assert.equal(page.data.membersState, 'idle')
  assert.equal(page.data.membersExpanded, false)
  assert.equal(requests.some(request => request.url === '/api/club/members'), false,
    '失权结果不得继续读取成员名单')
})

test('普通成员关系成功回读时保留已有名单，直到新名单请求接管', () => {
  const { page, requests } = loadDetail()
  page.refreshCoopPublisherAccess = () => {}
  const members = [{ memberId: 41 }, { memberId: 42 }]
  const memberStrip = [{ key: 'member:41', memberId: 41 }]
  Object.assign(page.data, {
    clubId: 9,
    club: { id: 9, isOwner: false, isJoined: true, viewerIsAdmin: false },
    canSeeMembers: true,
    members,
    memberStrip,
    membersState: 'ready',
    membersExpanded: true,
  })
  let preserved
  page.loadMembers = () => {
    preserved = {
      members: page.data.members,
      memberStrip: page.data.memberStrip,
      membersState: page.data.membersState,
      membersExpanded: page.data.membersExpanded,
    }
  }
  page.loadClubAccess = () => {}

  page.refreshMemberRelationship()
  const detail = requests.find(request => request.url === '/api/club/detail')
  detail.success({ code: '200', data: { id: 9, isOwner: false, isJoined: true, viewerIsAdmin: false } })

  assert.equal(page.data.canSeeMembers, true)
  assert.equal(preserved.members, members, '仍有成员权限时不得先清掉已知名单')
  assert.equal(preserved.memberStrip, memberStrip, '仍有成员权限时不得先清掉成员头像条')
  assert.equal(preserved.membersState, 'ready')
  assert.equal(preserved.membersExpanded, true)
})

test('普通成员返页关系刷新失败保留已知状态，且不接收刷新前的迟到名单', () => {
  const { page, requests } = loadDetail()
  page.refreshCoopPublisherAccess = () => {}
  Object.assign(page.data, {
    clubId: 9,
    club: { id: 9, isOwner: false, isJoined: true, viewerIsAdmin: false },
    canSeeMembers: true,
    canUseManageTab: false,
  })

  page.loadMembers()
  const staleMembers = requests.find(request => request.url === '/api/club/members')
  Object.assign(page.data, {
    members: [{ memberId: 41 }],
    memberStrip: [{ key: 'member:41', memberId: 41 }],
    membersState: 'ready',
  })
  page._hasShown = true
  page.onShow()
  const detail = requests.find(request => request.url === '/api/club/detail')
  detail.fail({ errMsg: 'request:fail offline' })

  assert.equal(page.data.canSeeMembers, true, '网络未知不能伪装成成员失权')
  assert.equal(page.data.notFound, false, '后台刷新失败不能把已打开详情改成不存在')
  assert.deepEqual(Array.from(page.data.members, member => member.memberId), [41])

  staleMembers.success({ code: '200', data: [{ memberId: 99 }] })
  assert.deepEqual(Array.from(page.data.members, member => member.memberId), [41],
    '关系刷新开始前的迟到名单不得补进页面')
})

test('普通成员返页收到仅成员可见业务拒绝时立即清旧名单并撤销 access', () => {
  const { page, requests } = loadDetail()
  page.refreshCoopPublisherAccess = () => {}
  Object.assign(page.data, {
    clubId: 9,
    club: { id: 9, isOwner: false, isJoined: true, viewerIsAdmin: false },
    canSeeMembers: true,
    canReadMembers: true,
    canApproveMembers: true,
    canManageMembers: true,
    clubAccessState: 'ready',
  })

  page.loadMembers()
  const staleMembers = requests.find(request => request.url === '/api/club/members')
  Object.assign(page.data, {
    members: [{ memberId: 41 }],
    memberStrip: [{ key: 'member:41', memberId: 41 }],
    membersState: 'ready',
  })
  page._hasShown = true
  page.onShow()
  const detail = requests.find(request => request.url === '/api/club/detail')
  detail.success({ code: 500, msg: '这个俱乐部只对成员开放' })

  assert.equal(page.data.club, null)
  assert.equal(page.data.canSeeMembers, false)
  assert.deepEqual(Array.from(page.data.members), [])
  assert.deepEqual(Array.from(page.data.memberStrip), [])
  assert.equal(page.data.membersState, 'idle')
  assert.equal(page._clubAccessState, 'idle')
  assert.equal(page.data.canReadMembers, false)
  assert.equal(page.data.canApproveMembers, false)
  assert.equal(page.data.canManageMembers, false)
  assert.equal(requests.filter(request => request.url === '/api/club/members').length, 1,
    '业务拒绝不得再发成员名单请求')

  staleMembers.success({ code: '200', data: [{ memberId: 99 }] })
  assert.deepEqual(Array.from(page.data.members), [],
    '业务拒绝后，关系刷新前的迟到名单不得补回')
})

test('较早的详情关系响应迟到时不能覆盖较新的失权结果', () => {
  const { page, requests } = loadDetail()
  page.refreshCoopPublisherAccess = () => {}
  Object.assign(page.data, {
    clubId: 9,
    club: { id: 9, isOwner: false, isJoined: true, viewerIsAdmin: false },
    canSeeMembers: true,
    members: [{ memberId: 41 }],
    memberStrip: [{ key: 'member:41', memberId: 41 }],
    membersState: 'ready',
  })

  page.refreshMemberRelationship()
  page.refreshMemberRelationship()
  const details = requests.filter(request => request.url === '/api/club/detail')
  details[1].success({ code: '200', data: { id: 9, isOwner: false, isJoined: false, viewerIsAdmin: false } })
  details[0].success({ code: '200', data: { id: 9, isOwner: false, isJoined: true, viewerIsAdmin: false } })

  assert.equal(page.data.canSeeMembers, false)
  assert.equal(page.data.club.isJoined, false)
  assert.deepEqual(Array.from(page.data.members), [])
  assert.equal(requests.some(request => request.url === '/api/club/members'), false,
    '迟到的旧关系成功响应不得重新触发名单读取')
})

test('公开帖子的游客空态只限制发布，不得误写为不可查看', () => {
  const view = read(DETAIL_WXML)
  assert.match(view, /入会后可发布/)
  assert.doesNotMatch(view, /加入俱乐部后可查看与发布/)
})

// —— L06 拍板 2026-10-22 ——
// 2026-10-22 owner:每次进俱乐部都先看概览 —— 再次进入同一俱乐部也回到概览,不再沿用上次的 tab;中途同样不能闪出别的 tab。
test('L06(a):详情回来前不渲染任何 tab 内容；同一俱乐部再次进入也先看概览，不先闪帖子', () => {
  const { page, requests } = loadDetail()
  const initialData = JSON.parse(JSON.stringify(page.data))
  assert.equal(page.data.activeTab, '', '初值为空:不能先渲染「帖子」')
  const view = read(DETAIL_WXML)
  assert.match(view, /wx:if="\{\{activeTab === 'posts'\}\}"/, '帖子 tab 只在明确选中时渲染')
  Object.assign(page.data, { clubId: 31 })
  page.loadDetail()
  requests.find(r => r.url === '/api/club/detail').success({ code: '200', data: { id: 31, isOwner: false, isJoined: false, joinPolicy: 0 } })
  assert.equal(page.data.activeTab, 'overview', '访客默认概览')
  page.switchTab({ detail: { key: 'events' } })

  // 小程序里页面模块只执行一次,再次 navigateTo 是同一份定义的新实例 —— 这里同样复用同一个沙箱。
  const seen = []
  const again = Object.assign({}, page, {
    data: Object.assign(JSON.parse(JSON.stringify(initialData)), { clubId: 31 }),
    setData(patch, cb) { if ('activeTab' in patch) seen.push(patch.activeTab); Object.assign(this.data, patch); if (cb) cb.call(this) },
  })
  requests.length = 0
  again.loadDetail()
  requests.find(r => r.url === '/api/club/detail').success({ code: '200', data: { id: 31, isOwner: false, isJoined: false, joinPolicy: 0 } })
  assert.ok(seen.length > 0)
  assert.deepEqual(seen.filter(tab => tab !== 'overview'), [], '再次进入同一俱乐部直接落在概览,中途不出现别的 tab')
})

test('L06(c):公开加入团的访客能看成员名单与贡献榜，需审核团锁到通过审核', () => {
  const pub = loadDetail()
  Object.assign(pub.page.data, { clubId: 32 })
  pub.page.loadDetail()
  pub.requests.find(r => r.url === '/api/club/detail').success({ code: '200', data: { id: 32, isOwner: false, isJoined: false, joinPolicy: 0 } })
  assert.equal(pub.page.data.canSeeRoster, true)
  assert.equal(pub.page.data.canSeeMembers, false, '发帖/评论/举报仍要成员身份')
  assert.ok(pub.requests.some(r => r.url === '/api/club/members'), '公开团访客要拉成员名单')

  const gated = loadDetail()
  Object.assign(gated.page.data, { clubId: 33 })
  gated.page.loadDetail()
  gated.requests.find(r => r.url === '/api/club/detail').success({ code: '200', data: { id: 33, isOwner: false, isJoined: false, joinPolicy: 1, myJoinStatus: 0 } })
  assert.equal(gated.page.data.canSeeRoster, false)
  assert.equal(gated.requests.some(r => r.url === '/api/club/members'), false)

  const view = read(DETAIL_WXML)
  assert.match(view, /通过审核后可看成员名单/)
  assert.match(view, /通过审核后可看贡献榜/)
  assert.match(view, /<block wx:if="\{\{canSeeRoster\}\}">\s*<view class="ov-lb"/)
  assert.match(view, /class="member-report" wx:if="\{\{canSeeMembers && /, '访客看名单但不能逐人举报')
})

test('L06(c):需审核团申请留言必填、60 字内；公开团一键加入', () => {
  const { page, requests, modals } = loadDetail()
  Object.assign(page.data, { clubId: 34, club: { id: 34, joinPolicy: 1 } })
  page.onJoin()
  assert.equal(modals.length, 1)
  assert.equal(modals[0].editable, true)
  assert.doesNotMatch(modals[0].placeholderText, /选填/)
  assert.equal(typeof modals[0].validate, 'function')
  assert.equal(modals[0].validate('   '), '请先写一句申请留言')
  assert.equal(modals[0].validate('长'.repeat(61)), '申请留言最多 60 字')
  assert.equal(modals[0].validate('长'.repeat(60)), '')
  modals[0].success({ confirm: true, content: '  朋友推荐来的  ' })
  const join = requests.find(r => r.url === '/api/club/join')
  assert.equal(JSON.parse(join.data).joinMessage, '朋友推荐来的')

  const open = loadDetail()
  Object.assign(open.page.data, { clubId: 35, club: { id: 35, joinPolicy: 0 } })
  open.page.onJoin()
  assert.equal(open.modals.length, 0, '公开团不问留言')
  assert.ok(open.requests.some(r => r.url === '/api/club/join'))

  const view = read(DETAIL_WXML)
  assert.match(view, /aria-disabled="true">等待主理人审核<\/view>/)
})

test('L06(b/d/e):头部元信息；商家可加入、活动卡给「邀约承接」；俱乐部页无邀约浮条', () => {
  const { page, requests } = loadDetail()
  Object.assign(page.data, { clubId: 36 })
  page.loadDetail()
  requests.find(r => r.url === '/api/club/detail').success({ code: '200', data: { id: 36, clubType: '旅行组织', memberCount: 1, joinPolicy: 0 } })
  assert.equal(page.data.club.metaLine, '旅行组织 · 1 名成员')
  assert.equal(page.data.club.joinPolicyText + '加入', '公开加入')

  const view = read(DETAIL_WXML)
  // 2026-10-22 owner:俱乐部谁都能进来看,加入按钮改成底部悬浮;条件里仍不挡商家
  // 2026-10-22 owner:加入按钮只在「概览」出现,往下滑收起(join-float--hidden)
  assert.match(view, /<block wx:if="\{\{detailLoaded && activeTab === 'overview' && !club\.isJoined && !club\.isOwner[^"]*\}\}">\s*<view class="join-float-space"><\/view>\s*<view class="join-float \{\{joinHidden && !joinFx \? 'join-float--hidden' : ''\}\}">/, '商家不再被挡在加入按钮外')
  assert.doesNotMatch(view, /class="profile-cta"/, '头部不再放加入按钮')
  // 2026-10-27 M-24·M-34 owner:商家在活动卡上看到的是「邀约承接」(取代「商家不能报名」灰字),仍不给报名。
  assert.match(view, /wx:if="\{\{merchantUndertake\}\}" class="ev-undertake" catchtap="onMerchantUndertake"[^>]*>邀约承接</)
  assert.match(view, /upcomingTopics[^\n]*\n[^\n]*merchantUndertake: isMerchantViewer/)
  assert.doesNotMatch(view, /club-coop-floating|cy-coop-invite-sheet|goClubCoop/)
  assert.doesNotMatch(read('pages/club/detail/index.json'), /coop-invite-sheet/)
})

// 2026-10-22 owner:「加入俱乐部」往下滑时收起,往上滑或回到顶部再出来;只在状态变化时 setData
test('加入俱乐部悬浮按钮:往下滑收起、往上滑/回顶再出现', () => {
  const { page } = loadDetail()
  let writes = 0
  const setData = page.setData
  page.setData = function (patch, cb) { writes++; return setData.call(this, patch, cb) }
  page.onPageScroll({ scrollTop: 20 })
  assert.equal(page.data.joinHidden, false, '顶部附近不收')
  page.onPageScroll({ scrollTop: 200 })
  assert.equal(page.data.joinHidden, true, '往下滑收起')
  const before = writes
  page.onPageScroll({ scrollTop: 400 })
  assert.equal(writes, before, '继续往下滑不重复 setData')
  page.onPageScroll({ scrollTop: 300 })
  assert.equal(page.data.joinHidden, false, '往上滑再出现')
  page.onPageScroll({ scrollTop: 500 })
  page.onPageScroll({ scrollTop: 10 })
  assert.equal(page.data.joinHidden, false, '回到顶部一定出现')
})

// 2026-10-22 业主:加入后弹「在这个俱乐部你可以做什么」,内容按本团真实设置拼;主理人可自定欢迎语
test('欢迎层按本团设置拼条目:发帖开关、商家不能报名、成员优先/成员价', () => {
  const { page } = loadDetail()
  const open = page.buildWelcomeItems({ id: 1, memberPostAllowed: 1, prioritySignupEnabled: 1, memberDiscountPrice: 39 })
  assert.equal(JSON.stringify(open.map(i => i.key)), JSON.stringify(['posts', 'events', 'overview']))
  assert.match(open[0].title, /发帖/)
  assert.match(open[1].sub, /成员优先报名、成员价 ¥39/)
  const closed = page.buildWelcomeItems({ id: 1, memberPostAllowed: 0 })
  assert.doesNotMatch(closed[0].title, /发帖/, '成员不能发帖的团不能说「发帖」')
  page.data.isMerchantViewer = true
  assert.match(page.buildWelcomeItems({ id: 1 })[1].sub, /商家身份不能报名/)
})

test('加入成功:按钮先变绿打勾,动效后才弹欢迎层;待审只回执不弹', () => {
  const { page, requests, modals } = loadDetail()
  Object.assign(page.data, { clubId: 36, club: { id: 36, name: '城市乐居人', joinPolicy: 0 }, reducedMotion: true })
  const fx = []
  const setData = page.setData
  page.setData = function (patch, cb) {
    if ('joinFx' in patch) fx.push(patch.joinFx)
    if (patch.welcomeShow) fx.push('welcome')
    return setData.call(this, patch, cb)
  }
  page.onJoin()
  assert.equal(page.data.joinFx, 'busy')
  page.onJoin()
  assert.equal(requests.filter(r => r.url === '/api/club/join').length, 1, '请求中不重复提交')
  requests.find(r => r.url === '/api/club/join').success({ code: '200', data: { state: 'joined' } })
  // 沙箱里没有 setTimeout,动效后的那一步立即执行;顺序必须是 请求中 → 变绿打勾 → 弹欢迎层 → 复位
  assert.equal(JSON.stringify(fx), JSON.stringify(['busy', 'done', 'welcome', '']), '成功先变绿打勾,再弹欢迎层')
  assert.equal(page.data.welcomeShow, true)

  const pending = loadDetail()
  Object.assign(pending.page.data, { clubId: 37, club: { id: 37, joinPolicy: 1 } })
  pending.page.submitJoin('想一起夜行')
  pending.requests.find(r => r.url === '/api/club/join').success({ code: '200', data: { state: 'pending' } })
  assert.equal(pending.page.data.joinFx, '')
  assert.equal(pending.page.data.welcomeShow, false, '待审不弹欢迎层')
  assert.ok(pending.modals.some(m => /申请已提交/.test(m.title)))
  void modals
})

test('主理人保存入会欢迎语:走开放设置接口,回读值才落到页面', () => {
  const { page, requests } = loadDetail()
  Object.assign(page.data, { clubId: 38, club: { id: 38, isOwner: true, welcomeNote: '' } })
  page.saveWelcomeNote('欢迎！周六晚上一起夜行')
  const req = requests.find(r => r.url === '/api/club/open-settings/welcome-note')
  assert.ok(req)
  assert.equal(JSON.parse(req.data).welcomeNote, '欢迎！周六晚上一起夜行')
  assert.equal(page.data.openSettingSaving, 'welcomeNote')
  req.success({ code: 200, data: { welcomeNote: '欢迎！周六晚上一起夜行' } })
  // 桩 setData 不展开路径键('club.welcomeNote' 原样落在 data 上),真机会展开;两处都认
  const noteOf = () => page.data['club.welcomeNote'] !== undefined ? page.data['club.welcomeNote'] : page.data.club.welcomeNote
  assert.equal(noteOf(), '欢迎！周六晚上一起夜行')
  assert.equal(page.data.openSettingSaving, '')

  page.saveWelcomeNote('x')
  requests[requests.length - 1].success({ code: 500, msg: '内容含违规信息' })
  assert.equal(page.data.openSettingErrorKey, 'welcomeNote')
  assert.equal(noteOf(), '欢迎！周六晚上一起夜行', '失败不改页面上的值')
  const view = read(DETAIL_WXML)
  assert.match(view, /wx:if="\{\{club\.welcomeNote\}\}"[\s\S]{0,200}\{\{club\.welcomeNote\}\}/, '欢迎层最上面显示主理人欢迎语')
})
