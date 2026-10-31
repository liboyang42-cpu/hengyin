const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-227:俱乐部详情「俱乐部勋章」连点只跳一次;complete 回调或 onShow 后可再跳;非成员/缺 clubId 不跳也不上锁

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
  page.data.club = { id: 9, isJoined: true, isOwner: false }
  return { page, navs }
}

const URL = '/pages/club/badges/index?clubId=9'

test('BL-227:complete 未回调时连调两次只跳 1 次', () => {
  const { page, navs } = loadPage()
  page.goClubBadges()
  page.goClubBadges()
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, URL)
})

test('BL-227:complete 回调后可再跳', () => {
  const { page, navs } = loadPage()
  page.goClubBadges()
  navs[0].complete()
  page.goClubBadges()
  assert.equal(navs.length, 2)
})

test('BL-227:onShow 兜底清锁后可再跳', () => {
  const { page, navs } = loadPage()
  page.goClubBadges()
  page.onShow()
  page.goClubBadges()
  assert.equal(navs.length, 2)
})

test('BL-227:非成员或缺 clubId 时不跳也不上锁', () => {
  const { page, navs } = loadPage()
  page.data.club = { id: 9, isJoined: false, isOwner: false }
  page.goClubBadges()
  page.data.club = { id: 9, isJoined: true, isOwner: false }
  page.data.clubId = ''
  page.goClubBadges()
  assert.equal(navs.length, 0)
  page.data.clubId = '9'
  page.goClubBadges()
  assert.equal(navs.length, 1)
})
