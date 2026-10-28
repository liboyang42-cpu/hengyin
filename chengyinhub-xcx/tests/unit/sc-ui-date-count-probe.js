/* SC-UI-DATE-COUNT 子进程探针(不是测试文件,由 sc-ui-date-count-cross-page.test.js 按时区各起一次)。
 * 时区在进程启动时就定了,所以每个 TZ 一个子进程:把同一份夹具喂给各页自己的映射/格式化代码,
 * 只 stub wx 与请求层,把各页「真的会 setData 出去的」站数与日期原样写到 stdout(JSON)。
 * 夹具与期望值都在测试文件里;这里只负责跑页面、读页面。 */
'use strict'
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const fixture = JSON.parse(process.argv[2])
const NOW = fixture.now

// 页面里既有 Date.now() 也有 new Date():两条都钉到夹具时刻。
const RealDate = Date
class FixedDate extends RealDate {
  constructor(...args) { if (args.length === 0) super(NOW); else super(...args) }
  static now() { return NOW }
}
global.Date = FixedDate

let routes = {}
const requests = []
const app = {
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  getUserID: () => 7, getUserRole: () => 'club', getUserType: () => 1, getImgUrl: (u) => u,
  getAuthorization: () => 'token', getToken: () => 'token', isDevEnv: () => false,
  getNickname: () => '主理人', getAvatar: () => '', getUserInfo: () => null,
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  chooseImage() {}, tips() {},
  sendRequest(req) {
    requests.push(req)
    const handler = routes[req.url]
    if (handler) handler(req)
  },
}
global.getApp = () => app
global.getCurrentPages = () => [{}]
global.wx = {
  showToast() {}, showModal() {}, showLoading() {}, hideLoading() {}, stopPullDownRefresh() {},
  navigateTo() {}, redirectTo() {}, switchTab() {}, navigateBack() {}, pageScrollTo() {}, scanCode() {},
  getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
  setNavigationBarColor() {}, setBackgroundColor() {}, nextTick: (f) => f(),
  getSystemInfoSync: () => ({ windowWidth: 375 }), getWindowInfo: () => ({ windowWidth: 375 }),
  createMapContext: () => ({ getCenterLocation() {} }), createSelectorQuery: () => ({ select() { return this }, boundingClientRect() { return this }, exec() {} }),
}

function ok(data) { return { code: 200, data: JSON.parse(JSON.stringify(data)) } }

function mountPage(rel) {
  let config = null
  global.Page = (c) => { config = c }
  const abs = path.join(ROOT, rel)
  delete require.cache[abs]
  require(abs)
  const vm = Object.assign({}, config)
  vm.data = JSON.parse(JSON.stringify(config.data))
  // 同时支持 'a.b[0].c' 路径键(fabu 用)与顶层键
  vm.setData = function (patch, cb) {
    Object.keys(patch).forEach((k) => {
      const parts = k.replace(/\[(\d+)\]/g, '.$1').split('.')
      let o = vm.data
      for (let i = 0; i < parts.length - 1; i++) {
        if (o[parts[i]] == null || typeof o[parts[i]] !== 'object') o[parts[i]] = {}
        o = o[parts[i]]
      }
      o[parts[parts.length - 1]] = patch[k]
    })
    if (typeof cb === 'function') cb()
  }
  vm.selectComponent = () => ({ open() { return true }, busyOn() {}, done() {}, failed() {}, close() {} })
  return vm
}

function mountComponent(rel) {
  let def = null
  global.Component = (c) => { def = c }
  const abs = path.join(ROOT, rel)
  delete require.cache[abs]
  require(abs)
  const data = Object.assign({}, def.data)
  for (const [k, spec] of Object.entries(def.properties || {})) data[k] = spec.value
  const inst = Object.assign({ data }, def.methods, {
    setData(patch, cb) { Object.assign(this.data, patch); if (typeof cb === 'function') cb() },
    triggerEvent() {},
  })
  return { def, inst }
}

const out = { tz: process.env.TZ, offsetMin: -new RealDate(NOW).getTimezoneOffset() }
const W = fixture.wire

function guard(name, fn) {
  try { out[name] = fn() } catch (e) { out[name] = { error: String(e && e.stack || e) } }
}

// ① 主题卡(俱乐部主页「活动」列表 + 管理列表):/api/club/topics → decorateTopicCard
//    (2026-10-27 P-14:活动日历已删,不再读 eventCalendar*)
guard('topicCard', () => {
  routes = { '/api/club/topics': (r) => r.success(ok([W.clubTopic])) }
  const vm = mountPage('pages/club/detail/index.js')
  vm.data.club = { id: fixture.clubId, isOwner: true }
  vm.data.clubId = fixture.clubId
  vm.loadTopics()
  const card = vm.data.upcomingTopics[0] || {}
  const manage = vm.data.manageTopics[0] || {}
  return {
    upcomingCount: vm.data.upcomingTopics.length,
    stationText: card.stationText, timeText: card.timeText, dateText: card.dateText, statusText: card.statusText,
    manageDateText: manage.dateText,
  }
})

// ② 主题页(玩家侧详情 + 选场次面板):/api/topic/info-to-user 成功体里的同一串映射
guard('topicPage', () => {
  routes = {}
  const vm = mountPage('pages/topic/index/index.js')
  const data = JSON.parse(JSON.stringify(W.topicInfo))
  // 与 index.js 成功回调同一顺序:showStartDate/showEndDate → processChaptersList → contentIncludes → statBar
  data.showStartDate = vm.formatDate(data.startDate)
  data.showEndDate = vm.formatDate(data.endDate)
  data.chaptersList = vm.processChaptersList(data.chaptersList)
  data.contentIncludes = vm.buildContentIncludes(data)
  const { buildTopicStatBar } = require(path.join(ROOT, 'pages/topic/utils/topic-detail-facts.js'))
  const open = buildTopicStatBar(data).filter((c) => c.key === 'open')[0]
  return {
    showStartDate: data.showStartDate, showEndDate: data.showEndDate, openCell: open ? open.value : '',
    contentIncludes: data.contentIncludes,
    chapterMetaPlace: data.chaptersList.map((c) => c.metaPlace),
  }
})

guard('sessionPicker', () => {
  routes = {}
  const { def, inst } = mountComponent('pages/topic/components/cy/session-picker/index.js')
  inst.pickActivity = function (id) { this.data.pickedActivityId = id }
  def.observers['show, activities'].call(inst, true, JSON.parse(JSON.stringify(W.pickerActivities)))
  return { days: inst.data.days.map((d) => ({ id: d.id, week: d.week, day: d.day, whenText: d.whenText, detailText: d.detailText })) }
})

// ③ 创建/发布向导(编辑既有主题):/api/topic/edit-detail → applyEditingTopic → 发布确认页
guard('wizard', () => {
  routes = {}
  const vm = mountPage('pages/publish/fabu/index.js')
  vm.data.editingTopicId = String(fixture.topicId) // 与 onLoad(?id=) 进编辑态一致
  vm.applyEditingTopic(JSON.parse(JSON.stringify(W.editDetail)))
  const preview = vm._buildPublishPreview()
  const station = preview.stats.filter((s) => s.k === '节点')[0]
  const passed = vm._buildPublishPassed(true).map((i) => i.label).filter((l) => /个节点$/.test(l))[0] || ''
  return {
    startDateTime: vm.data.startDateTime, endDateTime: vm.data.endDateTime,
    previewStationValue: station ? station.v : '',
    summaryLine: vm._buildPublishSummary()[0],
    passedStationLabel: passed,
  }
})

// ④ 统计/管理视图 pages/club/topic-detail:topic-manage-stats(nodeCount)+ info-to-user
guard('topicDetail', () => {
  routes = {}
  global.Page = undefined
  const vm = mountPage('pages/club/topic-detail/index.js')
  vm.loadProjection = function () {}
  vm.startDirector = function () {}
  vm._topicId = fixture.topicId
  vm._clubId = fixture.clubId
  vm._manageStats = JSON.parse(JSON.stringify(W.manageStats))
  vm.applyDetail(JSON.parse(JSON.stringify(W.topicInfo)))
  return {
    dateRange: vm.data.topic.dateRange,
    structureText: vm.data.topic.structureText,
    sessions: vm.data.sessions.map((s) => ({ id: s.id, whenText: s.whenText })),
  }
})

// ⑤ 场次页:场次管理(event-ops 主题下拉 + 系列场次清单)与场次详情(scene-play-activity-detail)
guard('eventOps', () => {
  routes = {
    '/api/club/event-ops/topics': (r) => r.success(ok([W.clubTopic])),
    '/api/club/event-ops/series/occurrences': (r) => r.success(ok(W.occurrences)),
  }
  const vm = mountPage('pages/club/event-ops/index.js')
  vm.data.clubId = fixture.clubId
  vm.loadTopics()
  vm.toggleSeriesDates({ currentTarget: { dataset: { seriesId: 31 } } })
  return {
    dropdownLabel: (vm.data.topics[0] || {}).dropdownLabel,
    occurrences: vm.data.occurrenceRows.map((r) => ({ activityId: r.activityId, dateText: r.dateText })),
  }
})

guard('sessionDetail', () => {
  const rows = {}
  for (const info of W.activityInfos) {
    routes = { '/api/activity/info': (r) => r.success(ok(info)) }
    const { inst } = mountComponent('subpackageMember/components/cy/scene-play-activity-detail/index.js')
    inst.data.activityId = String(info.id)
    inst.load(String(info.id))
    rows[info.id] = { state: inst.data.state, dateText: inst.data.dateText, ticketDateText: (inst.data.tickets[0] || {})._dateText || '' }
  }
  return rows
})

process.stdout.write(JSON.stringify(out))
