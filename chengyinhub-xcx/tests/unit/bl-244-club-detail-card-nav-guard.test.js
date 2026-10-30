const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-244:俱乐部详情动态卡(玩法/主题/资料)、场次工具、导演台的跳转连点只跳一次;complete 或 onShow 后可再跳;
// 导演台请求在途时不重复发 /api/topic/info-to-user;无 sportTopicId/无权限时不跳也不上锁

const ROOT = path.resolve(__dirname, '../..')
const PAGE = 'pages/club/detail/index.js'

function loadPage() {
  let definition
  const navs = []
  const reqs = []
  const base = { navigateTo: (o) => navs.push(o), getStorageSync: () => '' }
  const wx = new Proxy(base, { get(t, k) { return k in t ? t[k] : () => {} } })
  const app = {
    globalData: {},
    sendRequest(o) { reqs.push(o); return { abort() {} } },
    getAvatar: () => '',
    getNickname: () => '',
    getUserID: () => '',
  }
  const real = {
    '../../../utils/motion.js': require('../../utils/motion.js'),
    '../../../utils/motion-preference.js': require('../../utils/motion-preference.js'),
    '../../../utils/scene-registry.js': { getScene: () => ({}) },
    '../../../utils/datetime': { toTimestamp: () => 0 },
    '../utils/mockData.js': { DEMO_NEARBY_CLUB_ID: -1 },
    '../utils/aiPlanToDraft.js': { aiPlanToDraft: () => ({}) },
    '../../../utils/group-code-session.js': { buildGroupCodeIssuePayload: () => ({}), listGroupCodeActivities: (list) => list || [] },
    '../utils/owner-action-guard.js': require('../../pages/club/utils/owner-action-guard.js'),
    '../utils/club-pinned-notice.js': require('../../pages/club/utils/club-pinned-notice.js'),
    '../../../utils/club-post-repost.js': require('../../utils/club-post-repost.js'),
    '../utils/club-event-calendar.js': require('../../pages/club/utils/club-event-calendar.js'),
    '../utils/edition-empty-reason.js': require('../../pages/club/utils/edition-empty-reason.js'),
    '../utils/club-pinned-notice.js': require('../../pages/club/utils/club-pinned-notice.js'),
    '../../../utils/club-post-repost.js': require('../../utils/club-post-repost.js'),
  }
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, PAGE), 'utf8'), {
    getApp: () => app,
    Page(page) { definition = page },
    require(request) {
      if (Object.prototype.hasOwnProperty.call(real, request)) return real[request]
      if (request.startsWith('../../../utils/')) return require(path.join(ROOT, 'utils', request.slice('../../../utils/'.length)))
      throw new Error(`unexpected require: ${request}`)
    },
    Set, Date, JSON, wx,
  }, { filename: path.join(ROOT, PAGE) })
  const page = Object.assign({}, definition, {
    data: Object.assign({}, definition.data),
    setData(patch) { Object.assign(this.data, patch) },
  })
  page.data.clubId = '9'
  page.data.club = { id: 9, name: '城瘾', isJoined: true, isOwner: true, memberId: 1 }
  page.data.canSeeMembers = true
  page.data.myMemberId = 1
  // 半屏选项:测试里直接选第一项
  page.openChoiceSheet = (title, hint, items, onPick) => onPick(items[0])
  page.data.posts = [{ sportTopicId: 11 }, { sportTopicId: 0 }]
  return { page, navs, reqs }
}

const tap = (dataset) => ({ currentTarget: { dataset } })
const card = (detail) => ({ detail })

const ENTRIES = [
  ['onPostReferencePlay', () => [card({ index: 0 })], '/pages/play/index?topicId=11'],
  ['onPostReference', () => [card({ index: 0 })], '/pages/topic/index/index?id=11'],
  ['onPostUser', () => [card({ memberId: 5 })], '/pages/userinfo/userinfo?userId=5'],
  ['chooseActivityTool', () => [21, '夜跑 · 20:00'], '/pages/club/event-ops/index?clubId=9&activityId=21'],
  ['navigateGameDirectorActivity', () => [21, 3], '/pages/club/topic-detail/index?topicId=3&clubId=9&activityId=21'],
]

for (const [name, args, url] of ENTRIES) {
  test(`BL-244:${name} 连点只跳 1 次,complete 后可再跳`, () => {
    const { page, navs } = loadPage()
    page[name](...args())
    page[name](...args())
    assert.equal(navs.length, 1)
    assert.equal(navs[0].url, url)
    navs[0].complete()
    page[name](...args())
    assert.equal(navs.length, 2)
  })
}

test('BL-244:场次工具只有一个时直接跳也有保护,onShow 兜底清锁', () => {
  const { page, navs } = loadPage()
  page.data.club = { id: 9, isJoined: true, isOwner: false, viewerIsAdmin: false, memberId: 1 }
  page.data.canCheckInEvents = true
  page.chooseActivityTool(21, '')
  page.chooseActivityTool(21, '')
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, '/pages/club/event-ops/index?clubId=9&activityId=21')
  page.onShow()
  page.chooseActivityTool(21, '')
  assert.equal(navs.length, 2)
})

test('BL-244:导演台请求在途时连点只发一次请求,fail/complete 后可再点', () => {
  const { page, reqs } = loadPage()
  const e = tap({ topicId: 3, gameModuleEnabled: true })
  page.goGameDirector(e)
  page.goGameDirector(e)
  assert.equal(reqs.length, 1)
  assert.equal(reqs[0].url, '/api/topic/info-to-user')
  reqs[0].fail()
  reqs[0].complete()
  page.goGameDirector(e)
  assert.equal(reqs.length, 2)
})

test('BL-244:导演台一场直跳与多场选场都只跳一次', () => {
  const { page, navs, reqs } = loadPage()
  const e = tap({ topicId: 3, gameModuleEnabled: true })
  page.goGameDirector(e)
  reqs[0].success({ code: 200, data: { activityList: [{ id: 21 }] } })
  reqs[0].complete()
  page.goGameDirector(e)
  reqs[1].success({ code: 200, data: { activityList: [{ id: 21 }] } })
  reqs[1].complete()
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, '/pages/club/topic-detail/index?topicId=3&clubId=9&activityId=21')
  navs[0].complete()
  page.goGameDirector(e)
  reqs[2].success({ code: 200, data: { activityList: [{ id: 21 }, { id: 22 }] } })
  reqs[2].complete()
  assert.equal(page.data.directorActivityPickerShow, true)
  page.data.directorActivityPickerShow = true
  const pick = tap({ id: 22 })
  page.onDirectorActivitySelect(pick)
  page.setData({ directorActivityOptions: [{ id: 21 }, { id: 22 }] })
  page.onDirectorActivitySelect(pick)
  assert.equal(navs.length, 2)
  assert.equal(navs[1].url, '/pages/club/topic-detail/index?topicId=3&clubId=9&activityId=22')
})

test('BL-244:无 sportTopicId/无权限时不跳也不上锁', () => {
  const { page, navs, reqs } = loadPage()
  let toggled = 0
  page.onToggleComments = () => { toggled += 1 }
  page.onPostReferencePlay(card({ index: 1 }))
  page.onPostReference(card({ index: 1 }))
  page.onPostUser(card({}))
  assert.equal(toggled, 2)
  page.data.club = { id: 9, isJoined: true, isOwner: false, memberId: 1 }
  page.goGameDirector(tap({ topicId: 3, gameModuleEnabled: true }))
  page.navigateGameDirectorActivity(21, 3)
  page.chooseActivityTool(21, '')
  assert.equal(navs.length, 0)
  assert.equal(reqs.length, 0)
  page.onPostUser(card({ memberId: 5 }))
  assert.equal(navs.length, 1)
})
