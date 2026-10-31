const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-231:俱乐部详情其余 23 处跳转入口连点只跳一次;complete 回调或 onShow 后可再跳;前置条件不满足时不跳也不上锁

const ROOT = path.resolve(__dirname, '../..')
const PAGE = 'pages/club/detail/index.js'

function loadPage() {
  let definition
  const navs = []
  const base = { navigateTo: (o) => navs.push(o), getStorageSync: () => '' }
  const wx = new Proxy(base, { get(t, k) { return k in t ? t[k] : () => {} } })
  const app = {
    globalData: {},
    sendRequest() { return { abort() {} } },
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
    '../../../utils/group-code-session.js': { buildGroupCodeIssuePayload: () => ({}), listGroupCodeActivities: () => [] },
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
  return { page, navs }
}

const tap = (dataset) => ({ currentTarget: { dataset } })

const ENTRIES = [
  ['goCoopList', () => [], '/pages/coop/list/index'],
  ['goCooperation', () => [], '/pages/coop/list/index?tab=sent'],
  ['goCoopSent', () => [], '/pages/coop/list/index?tab=sent'],
  ['goCustomers', () => [], '/pages/club/customers/index?clubId=9'],
  ['goEnroll', () => [], '/pages/club/enroll/index?clubId=9&clubName=' + encodeURIComponent('城瘾')],
  ['goEventOps', () => [], '/pages/club/event-ops/index?clubId=9'],
  ['goClubFinance', () => [], '/pages/club/settlement/index?clubId=9'],
  ['goClubNotify', () => [], '/pages/club/notify/index?clubId=9'],
  ['goMerchantProfile', () => [tap({ memberid: 5 })], '/pages/userinfo/userinfo?userId=5&tab=about'],
  ['goJoinRequests', () => [], '/pages/club/join-requests/index?clubId=9'],
  ['goClubRoles', () => [], '/pages/club/roles/index?clubId=9'],
  ['goMemberRoles', () => [tap({ memberId: 7 })], '/pages/club/roles/index?clubId=9&memberId=7'],
  ['goClubGovernance', () => [], '/pages/club/governance/index?clubId=9'],
  ['goMemberGovernance', () => [tap({ memberId: 7 })], '/pages/club/governance/index?clubId=9&memberId=7'],
  ['reportClubMember', () => [tap({ memberId: 7, nickname: '小明' })], '/pages/club/governance/index?clubId=9&mode=report&targetMemberId=7&targetName=' + encodeURIComponent('小明')],
  ['reportClub', () => [], '/pages/club/governance/index?clubId=9&mode=report&targetType=CLUB&targetId=9&targetName=' + encodeURIComponent('城瘾')],
  ['goClubAppeal', () => [], '/pages/club/governance/index?clubId=9&mode=appeal'],
  ['onCreateTeam', () => [], '/pages/publish/fabu/index?clubId=9&mode=1'],
  ['onOpenClubSession', () => [], '/pages/club/event-ops/index?clubId=9&recurrence=ONCE'],
  ['goTopic', () => [tap({ id: 3 })], '/pages/club/topic-detail/index?topicId=3&clubId=9'],
  ['goManageTopic', () => [tap({ id: 3 })], '/pages/club/topic-detail/index?topicId=3&clubId=9'],
]

for (const [name, args, url] of ENTRIES) {
  test(`BL-231:${name} 连点只跳 1 次,complete 后可再跳`, () => {
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

test('BL-231:成员行「公开资料」与「客户档案」两个落点都有跳转中保护', () => {
  const { page, navs } = loadPage()
  page.canReadCustomerProfile = () => false
  page.goMemberProfile(tap({ memberId: 7 }))
  page.goMemberProfile(tap({ memberId: 7 }))
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, '/pages/userinfo/userinfo?userId=7')
  navs[0].complete()
  page.canReadCustomerProfile = () => true
  page.openChoiceSheet = (title, hint, items, onPick) => onPick(items[1])
  page.goMemberProfile(tap({ memberId: 7 }))
  page.goMemberProfile(tap({ memberId: 7 }))
  assert.equal(navs.length, 2)
  assert.equal(navs[1].url, '/pages/club/customer-detail/index?clubId=9&memberId=7')
})

test('BL-231:不同入口共用一个标记,onShow 兜底清锁后可再跳', () => {
  const { page, navs } = loadPage()
  page.goClubRoles()
  page.goClubNotify()
  page.goClubBadges()
  assert.equal(navs.length, 1)
  page.onShow()
  page.goClubNotify()
  assert.equal(navs.length, 2)
})

test('BL-231:前置条件不满足时不跳也不上锁', () => {
  const { page, navs } = loadPage()
  page.data.club = { id: 9, isJoined: true, isOwner: false, memberId: 1 }
  page.goClubFinance()
  page.goClubNotify()
  page.goClubRoles()
  page.goMemberRoles(tap({ memberId: 1 }))
  page.goTopic(tap({}))
  page.goMerchantProfile(tap({}))
  assert.equal(navs.length, 0)
  page.goClubAppeal()
  assert.equal(navs.length, 1)
})
