// W10a #19 / #26:俱乐部详情的月历场次与「自由探索期次」空态原因。
//
// 真源:docs/delivery/release-closeout-20261022/private-groups.md(Lane G2)MISSING #19、#26。
// #19:月历按月读后端真实场次(日期 + updatedAt);拿到之前一律说「月历仅展示已加载主题」,不暗示完整排期。
// #26:期次为空时原因只来自服务端 reasonCode,前端不从空数组猜。
const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const CLUB_DETAIL_JS = path.join(ROOT, 'pages/club/detail/index.js')
const CLUB_DETAIL_WXML = path.join(ROOT, 'pages/club/detail/index.wxml')
const {
  LOADED_TOPICS_ONLY_NOTICE,
  buildMonthCalendar,
  calendarNotice,
  shapeCalendarSessions,
} = require('../../pages/club/utils/club-event-calendar.js')
const { editionEmptyCopy, shapeEditionEmptyReason } = require('../../pages/club/utils/edition-empty-reason.js')

let sandbox

beforeEach(() => {
  sandbox = { requests: [], navigations: [] }
  global.getApp = () => ({
    globalData: { user_id: 9, features: {} },
    isDevEnv: () => false,
    getUserID: () => 9,
    tips() {},
    sendRequest: (r) => sandbox.requests.push(r),
    getRequestErrorMessage: (res, fb) => (res && res.msg) || fb,
    getImgUrl: (p) => p,
  })
  global.wx = {
    getStorageSync: () => '',
    setStorageSync() {},
    removeStorageSync() {},
    getWindowInfo: () => ({ statusBarHeight: 20 }),
    getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    getMenuButtonBoundingClientRect: () => ({ top: 24, height: 32, bottom: 56 }),
    showLoading() {}, hideLoading() {}, showToast() {}, showModal() {},
    navigateTo: (o) => sandbox.navigations.push(o.url),
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
  }
})

function loadClubDetail() {
  const source = fs.readFileSync(CLUB_DETAIL_JS, 'utf8')
  let config = null
  const ctx = {
    Page: (c) => { config = c },
    getApp: global.getApp,
    wx: global.wx,
    Set, Map, Date, JSON, Math, Promise, setTimeout, clearTimeout, console,
    require: (request) => require(path.join(path.dirname(CLUB_DETAIL_JS), request)),
  }
  vm.createContext(ctx)
  vm.runInContext(source, ctx, { filename: 'club-detail.js' })
  assert.ok(config, 'club/detail Page() 载入失败')
  const page = Object.assign({}, config, {
    setData(patch) { Object.assign(this.data, patch) },
  })
  page.data = Object.assign({}, config.data)
  return page
}

function lastRequest(url) {
  const hits = sandbox.requests.filter((item) => item.url === url)
  return hits[hits.length - 1]
}

function sessionsPayload(overrides) {
  return Object.assign({
    clubId: 7,
    month: '2028-02',
    scope: 'PUBLIC',
    truncated: false,
    updatedAt: '2028-02-20 08:00:00',
    sessions: [
      { activityId: 101, topicId: 11, name: '夜走', date: '2028-02-29', state: 'PUBLISHED', updatedAt: '2028-02-20 08:00:00' },
      { activityId: 102, topicId: 11, name: '夜走', date: '2028-02-29', state: 'PUBLISHED', updatedAt: '2028-02-01 09:00:00' },
      { activityId: 103, topicId: 12, name: '晨跑', date: '2028-02-10', state: 'UNPUBLISHED', updatedAt: '2028-02-05 10:00:00' },
    ],
  }, overrides || {})
}

// ---------------------------------------------------------------------------
// #19 纯函数
// ---------------------------------------------------------------------------

test('#19 场次响应收成月历事件:同主题同日两场各占一格条目,状态写进名称,键不撞', () => {
  const shaped = shapeCalendarSessions(sessionsPayload(), 7, '2028-02')
  assert.ok(shaped)
  assert.equal(shaped.truncated, false)
  const calendar = buildMonthCalendar('2028-02', shaped.events)
  const leap = calendar.cells.find((cell) => cell.dateText === '2028-02-29')
  assert.deepEqual(leap.events.map((event) => event.calendarKey), ['session-101', 'session-102'])
  assert.deepEqual(leap.events.map((event) => event.id), [11, 11], '点进的是场次所属主题')
  const pending = calendar.cells.find((cell) => cell.dateText === '2028-02-10').events[0]
  assert.equal(pending.name, '晨跑（待审核）', '治理者看到的待审场次不能冒充已开售')
})

test('#19 错位或畸形响应整体拒收,月历宁可退回已加载主题也不画半张排期', () => {
  assert.equal(shapeCalendarSessions(sessionsPayload({ clubId: 8 }), 7, '2028-02'), null, '别的俱乐部')
  assert.equal(shapeCalendarSessions(sessionsPayload({ month: '2028-03' }), 7, '2028-02'), null, '切月后的旧响应')
  assert.equal(shapeCalendarSessions(sessionsPayload({ truncated: 'false' }), 7, '2028-02'), null)
  assert.equal(shapeCalendarSessions(sessionsPayload({ sessions: [{ activityId: 1, date: '2028-03-01' }] }), 7, '2028-02'), null,
    '场次日期必须落在请求月份内')
  assert.equal(shapeCalendarSessions(sessionsPayload({ sessions: [{ activityId: '1', date: '2028-02-01' }] }), 7, '2028-02'), null)
  assert.equal(shapeCalendarSessions(sessionsPayload({ sessions: [{ activityId: 1, date: '2028-02-30' }] }), 7, '2028-02'), null)
})

test('#19 说明文案:只有拿到真实场次才说按场次;其余一律「月历仅展示已加载主题」', () => {
  assert.equal(LOADED_TOPICS_ONLY_NOTICE, '月历仅展示已加载主题')
  for (const state of ['idle', 'loading', 'error']) {
    assert.ok(calendarNotice(state, null).startsWith('月历仅展示已加载主题'), state)
  }
  const shaped = shapeCalendarSessions(sessionsPayload(), 7, '2028-02')
  assert.equal(calendarNotice('ready', shaped), '按本月场次显示 · 更新于 02-20 08:00')
  const truncated = shapeCalendarSessions(sessionsPayload({ truncated: true }), 7, '2028-02')
  assert.equal(calendarNotice('ready', truncated), '本月场次较多，仅显示前 3 场', '截断时不得说成完整排期')
})

// ---------------------------------------------------------------------------
// #19 页面行为:2026-10-27 P-14 owner(真机走查 p90/p107)删掉了俱乐部活动 tab 的月历,
// 页面上的「进入月历 / 切月 / 场次失败重试 / 月历 WXML」四条用例随功能一并撤走;
// 上面的纯函数用例(utils/club-event-calendar.js)保留。删除契约见 club-event-calendar.test.js。
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// #26 期次空态原因
// ---------------------------------------------------------------------------

test('#26 原因码 → 文案:三种具体空态;拿不到原因只说「还没有」,不猜', () => {
  assert.equal(editionEmptyCopy('ready', 'NOT_UNDERTAKEN').title, '本俱乐部还没有承接自由探索期次')
  assert.equal(editionEmptyCopy('ready', 'UNDERTAKEN_NOT_ON_SALE').title, '已承接的期次还没有开售')
  assert.equal(editionEmptyCopy('ready', 'NO_PERMISSION').title, '仅主理人或管理员可查看期次')
  for (const [state, code] of [['idle', ''], ['loading', ''], ['error', ''], ['ready', 'SOMETHING_NEW']]) {
    const copy = editionEmptyCopy(state, code)
    assert.equal(copy.title, '还没有可分享的自由探索期次', `${state}/${code}`)
    assert.doesNotMatch(copy.sub, /承接|开售|权限/, `${state}/${code} 不得猜原因`)
  }
  assert.equal(shapeEditionEmptyReason({ clubId: 8, reasonCode: 'NOT_UNDERTAKEN' }, 7), null, '别家俱乐部的回包不采用')
  assert.equal(shapeEditionEmptyReason({ clubId: 7, reasonCode: 'NOT_UNDERTAKEN' }, 7), 'NOT_UNDERTAKEN')
})

test('#26 期次为空才去要原因,并显示服务端给的具体空态', () => {
  const page = loadClubDetail()
  page.data.clubId = 7
  page.data.club = { id: 7, isOwner: true }
  page.loadShareEditions()
  lastRequest('/api/club-compensation/editions').success({ code: '200', data: [] })

  const req = lastRequest('/api/club-compensation/editions/empty-reason')
  assert.ok(req, '空数组时必须向服务端要原因')
  assert.deepEqual(JSON.parse(req.data), { clubId: 7 })
  assert.equal(page.data.shareEditionEmptySub, '正在确认原因…')

  req.success({ code: 200, data: { clubId: 7, reasonCode: 'UNDERTAKEN_NOT_ON_SALE' } })
  assert.equal(page.data.shareEditionEmptySub, '')
  assert.equal(page.data.shareEditionEmptyTitle, '已承接的期次还没有开售')
})

test('#26 有期次时不请求原因;原因失败显示中性空态 + 重新加载', () => {
  const page = loadClubDetail()
  page.data.clubId = 7
  page.data.club = { id: 7, isOwner: true }
  page.loadShareEditions()
  lastRequest('/api/club-compensation/editions')
    .success({ code: '200', data: [{ topicId: 77, topicName: '第一期', executingClubId: 7 }] })
  assert.equal(lastRequest('/api/club-compensation/editions/empty-reason'), undefined)

  sandbox.requests = []
  page.loadShareEditions()
  lastRequest('/api/club-compensation/editions').success({ code: '200', data: [] })
  lastRequest('/api/club-compensation/editions/empty-reason').fail()
  assert.equal(page.data.shareEditionEmptySub, '暂时无法确认原因')
  assert.equal(page.data.shareEditionEmptyTitle, '还没有可分享的自由探索期次')
  assert.equal(page.data.shareEditionEmptyRetry, true)

  const wxml = fs.readFileSync(CLUB_DETAIL_WXML, 'utf8')
  assert.match(wxml, /title="\{\{shareEditionEmptyTitle\}\}" sub="\{\{shareEditionEmptySub\}\}"/)
  assert.doesNotMatch(wxml, /期次开售并绑定本俱乐部后会出现在这里/, '通用空态不得先替服务端给出原因')
})
