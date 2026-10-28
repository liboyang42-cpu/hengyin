/* SC-UI-DATE-COUNT [P1] 代码层跨页对账:主题卡 / 创建向导 / 统计视图 / 场次页 / 选场次面板,
 * 喂同一份夹具(一个主题、四个节点、跨上海午夜的场次),各页自己的映射与格式化代码算出来的
 * 站数与中国日历日期必须一致,且在设备时区 UTC-5 / UTC+8 / UTC+9 下都一致。
 *
 * 时区在进程启动时就定了,所以每个时区起一个子进程(sc-ui-date-count-probe.js),同 V6b 的做法。
 * 期望值不经 utils/datetime.js:直接用 +08:00 偏移手算,避免拿被测工具给自己作证。
 *
 * 站数口径(后端单一真源):主题下 status=1 的节点 ——
 *   ClubCrmMapper.xml topic-manage-stats.nodeCount:`n.status = 1`;
 *   CmsTopicMapper.xml selectPublicNodeCountsByTopicIds(/api/club/topics 的 locationCount):`status = 1` + 商家节点过审闸。
 * 夹具里的四个节点:两个带玩法的普通站、一个不需要打卡的剧情站(templateId=0,非必需)、一个已下架站(status=0)。
 * 按口径应数 3 站。status=0 = 后台「是否显示=隐藏」(作者保存一律写 1)。
 * /api/topic/info-to-user 按章节查节点带 status=1(ApiTopicInfoHiddenNodeTest 钉住),回包里没有隐藏节点;
 * /api/topic/author-edit-detail 仍原样带着(作者保存是整体替换,少回一个 = 重存时删掉它;保存按旧 id 保留隐藏态),
 * 所以向导那份回包里有 104,向导自己只数 status=1。 */
'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const { execFileSync } = require('node:child_process')

const PROBE = path.resolve(__dirname, 'sc-ui-date-count-probe.js')
// 2026-10-19T15:45:00Z = 北京时间 10-19 23:45(周一);UTC-5 是 10-19 10:45,UTC+9 是 10-20 00:45。
const NOW = Date.parse('2026-10-19T15:45:00Z')
// Etc/GMT+5 = 固定 UTC-5(不随夏令时漂);Asia/Tokyo = UTC+9。
const ZONES = ['Etc/GMT+5', 'Asia/Shanghai', 'Asia/Tokyo']

const NODES = [
  { id: 101, chapterId: 11, name: '甲站', status: 1, templateId: 5, nodeTime: 20, address: '愚园路 68 号', longitude: '121.43', latitude: '31.22' },
  { id: 102, chapterId: 11, name: '乙站', status: 1, templateId: 6, nodeTime: 20, address: '愚园路 88 号', longitude: '121.44', latitude: '31.22' },
  { id: 103, chapterId: 12, name: '剧情站', status: 1, templateId: 0, nodeTime: 10, address: '', longitude: '', latitude: '' },
  { id: 104, chapterId: 12, name: '已下架站', status: 0, templateId: 7, nodeTime: 20, address: '江苏路 1 号', longitude: '121.43', latitude: '31.21' },
]
const LISTED = NODES.filter((n) => n.status === 1)
const STATION_COUNT = LISTED.length // 3
const CHAPTERS = [{ id: 11, name: '第一章' }, { id: 12, name: '第二章' }]

// 场次(中国时间):701 跨午夜进行中;702 午夜后开场;703 当晚已结束(NOW 之前)。
const SESSIONS = [
  { id: 701, name: '夜场', start: '2026-10-19 22:00:00', end: '2026-10-20 00:30:00' },
  { id: 702, name: '零点场', start: '2026-10-20 00:15:00', end: '2026-10-20 03:00:00' },
  { id: 703, name: '白天场', start: '2026-10-19 08:00:00', end: '2026-10-19 23:30:00' },
]
const TOPIC = { id: 88, clubId: 9, name: '跨夜城瘾', start: '2026-10-19 20:00:00', end: '2026-10-21 02:00:00' }

const nodeRow = (n) => Object.assign({ topicId: TOPIC.id, sortId: n.id }, n)
const chapterNodes = (chapterId) => NODES.filter((n) => n.chapterId === chapterId).map(nodeRow)
const listedChapterNodes = (chapterId) => LISTED.filter((n) => n.chapterId === chapterId).map(nodeRow)
const isoChina = (s) => s.replace(' ', 'T') + '.000+08:00'
const dateOnly = (s) => s.slice(0, 10)
const activitySummary = (s) => ({ id: s.id, name: s.name, startDate: s.start, endDate: s.end, addressName: '愚园路' })

// 每个接口按它自己的线上形状给(@JsonFormat:CmsTopic 日期是 yyyy-MM-dd,TopicInfoVO / 场次 VO 是 yyyy-MM-dd HH:mm:ss,
// event-ops 的 occurrenceAt 是 Map 里的 Date,走 Asia/Shanghai 的 ISO 偏移串)。
const WIRE = {
  clubTopic: { id: TOPIC.id, clubId: TOPIC.clubId, name: TOPIC.name, productType: 1, startDate: dateOnly(TOPIC.start),
    endDate: dateOnly(TOPIC.end), locationCount: STATION_COUNT, signupCount: 5, totalMileage: 3.2, addressName: '愚园路' },
  topicInfo: { id: TOPIC.id, status: 1, isOwner: 1, name: TOPIC.name, productType: 1, startDate: TOPIC.start, endDate: TOPIC.end,
    totalChapterCount: CHAPTERS.length, locationCount: STATION_COUNT,
    templateCount: LISTED.filter((n) => n.templateId > 0).length,
    chaptersList: CHAPTERS.map((c) => ({ id: c.id, name: c.name, nodes: listedChapterNodes(c.id) })),
    activityList: SESSIONS.map(activitySummary) },
  manageStats: { canDirect: false, canManageSessions: true, canViewVerify: false, nodeCount: STATION_COUNT },
  editDetail: { topic: { id: TOPIC.id, name: TOPIC.name, productType: 1, startDate: dateOnly(TOPIC.start), endDate: dateOnly(TOPIC.end) },
    chapters: CHAPTERS.map((c) => ({ id: c.id, topicId: TOPIC.id, name: c.name, cmsTopicNodeList: chapterNodes(c.id) })),
    tickets: [], collaboratorIds: [], editScope: 'FULL' },
  // 选场次面板只拿「已上架、未结束」的场次(宿主传 info.activityList,后端已滤掉结束的)
  pickerActivities: SESSIONS.filter((s) => Date.parse(isoChina(s.end)) > NOW).map(activitySummary),
  occurrences: SESSIONS.map((s, i) => ({ occurrenceId: i + 1, activityId: s.id, occurrenceAt: isoChina(s.start),
    status: 'SCHEDULED', signupCount: 2, editable: true })),
  activityInfos: SESSIONS.slice(0, 2).map((s) => ({ id: s.id, name: s.name, isSignUp: 0, state: 'notStarted', stateText: '报名中',
    startDate: s.start, endDate: s.end,
    omsTicketList: [{ id: s.id * 10, name: '成人票', price: 10, remainingInventory: 5, startTime: s.start, endTime: s.end }] })),
}

// —— 独立期望:+08:00 手算中国日历,不经 utils/datetime.js ——
const WEEK = ['日', '一', '二', '三', '四', '五', '六']
function china(s) {
  const d = new Date(Date.parse(isoChina(s)) + 8 * 3600 * 1000)
  const pad = (n) => String(n).padStart(2, '0')
  return { m: d.getUTCMonth() + 1, d: d.getUTCDate(), w: WEEK[d.getUTCDay()], hm: pad(d.getUTCHours()) + ':' + pad(d.getUTCMinutes()),
    ymd: d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate()) }
}
const UPCOMING = SESSIONS.filter((s) => Date.parse(isoChina(s.end)) > NOW).map((s) => s.id)
const TOPIC_START = china(TOPIC.start)
const TOPIC_END = china(TOPIC.end)

function runIn(zone) {
  const fixture = { now: NOW, topicId: TOPIC.id, clubId: TOPIC.clubId, wire: WIRE }
  const raw = execFileSync(process.execPath, [PROBE, JSON.stringify(fixture)],
    { env: Object.assign({}, process.env, { TZ: zone }), encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 })
  const out = JSON.parse(raw)
  for (const [k, v] of Object.entries(out)) {
    if (v && typeof v === 'object' && v.error) assert.fail(zone + ' 探针 ' + k + ' 跑挂了:' + v.error)
  }
  return out
}

let cache = null
function allZones() {
  if (!cache) cache = ZONES.map((zone) => ({ zone, out: runIn(zone) }))
  return cache
}

// 各页文案 → 统一的 { 月, 日, 时分 }(只做解析,不做任何时区换算)
const md = (m, d) => m + '-' + d
function parseCnMonthDay(text) { const m = /(\d{1,2})月(\d{1,2})日/.exec(text || ''); return m ? md(+m[1], +m[2]) : null }
function parseHm(text) { const m = /(\d{2}):(\d{2})/.exec(text || ''); return m ? m[1] + ':' + m[2] : null }

test('SC-UI-DATE-COUNT 探针在三个设备时区都真的跑在对应时区', () => {
  const offsets = allZones().map((z) => z.out.offsetMin)
  assert.deepEqual(offsets, [-300, 480, 540])
})

test('SC-UI-DATE-COUNT 站数:主题卡 / 主题页「包含」/ 统计视图 读同一口径(status=1 节点数),三个时区一致', () => {
  for (const { zone, out } of allZones()) {
    assert.equal(out.topicCard.stationText, String(STATION_COUNT), zone + ' 主题卡「节点」')
    assert.ok(out.topicPage.contentIncludes.includes(STATION_COUNT + ' 个节点'), zone + ' 主题页「包含」:' + JSON.stringify(out.topicPage.contentIncludes))
    assert.equal(out.topicDetail.structureText, CHAPTERS.length + ' 章 · ' + STATION_COUNT + ' 站', zone + ' 统计视图结构行')
  }
})

test('SC-UI-DATE-COUNT 主题起止日期:主题卡 / 主题页 / 向导 / 统计视图 / 场次管理下拉 都是同一对中国日历日', () => {
  const startMd = md(TOPIC_START.m, TOPIC_START.d)
  const endMd = md(TOPIC_END.m, TOPIC_END.d)
  for (const { zone, out } of allZones()) {
    const seen = {
      topicCardDate: out.topicCard.dateText,
      topicCardTime: parseCnMonthDay(out.topicCard.timeText),
      topicCardWeek: (/周(.)/.exec(out.topicCard.timeText) || [])[1],
      manageDate: out.topicCard.manageDateText,
      topicPageOpen: out.topicPage.openCell,
      wizardStart: out.wizard.startDateTime, wizardEnd: out.wizard.endDateTime,
      detailRange: out.topicDetail.dateRange,
      eventOpsLabel: out.eventOps.dropdownLabel,
    }
    assert.deepEqual(seen, {
      topicCardDate: TOPIC_START.ymd,
      topicCardTime: startMd,
      topicCardWeek: TOPIC_START.w,
      manageDate: TOPIC_START.ymd,
      topicPageOpen: TOPIC_START.m + '.' + TOPIC_START.d + '-' + TOPIC_END.m + '.' + TOPIC_END.d,
      wizardStart: TOPIC_START.ymd, wizardEnd: TOPIC_END.ymd,
      detailRange: TOPIC_START.m + '月' + TOPIC_START.d + '日 – ' + TOPIC_END.m + '月' + TOPIC_END.d + '日',
      eventOpsLabel: TOPIC.name + ' · ' + TOPIC_START.m + '月' + TOPIC_START.d + '日–' + TOPIC_END.m + '月' + TOPIC_END.d + '日 · 自有',
    }, zone)
    // 2026-10-27 P-14 owner:俱乐部活动 tab 删掉月历(原 calendarDays / calendarMonth 两项随之撤走),
    // 标签文案「进行中 / 招募中」改为「已开始 / 未开始」;判定时刻不变。
    assert.equal(out.topicCard.statusText, '已开始', zone + ' 主题卡状态按同一时刻判')
    assert.equal(out.topicCard.upcomingCount, 1, zone)
  }
})

test('SC-UI-DATE-COUNT 跨午夜场次:统计视图 / 选场次面板 / 场次管理 / 场次详情 同一组场次、同一中国日期与时刻', () => {
  for (const { zone, out } of allZones()) {
    // 同一组场次:统计视图「接下来」与选场次面板都只列未结束的,且顺序一致
    assert.deepEqual(out.topicDetail.sessions.map((s) => s.id), UPCOMING, zone + ' 统计视图场次集合')
    assert.deepEqual(out.sessionPicker.days.map((s) => s.id), UPCOMING, zone + ' 选场次面板场次集合')
    assert.deepEqual(out.eventOps.occurrences.map((s) => s.activityId), SESSIONS.map((s) => s.id), zone + ' 场次管理列全部场次')

    for (const s of SESSIONS) {
      const want = china(s.start)
      const wantMd = md(want.m, want.d)
      const views = {}
      const occ = out.eventOps.occurrences.filter((r) => r.activityId === s.id)[0]
      views.eventOps = { md: parseCnMonthDay(occ.dateText), hm: parseHm(occ.dateText), w: (/周(.)/.exec(occ.dateText) || [])[1] }
      if (UPCOMING.includes(s.id)) {
        const row = out.topicDetail.sessions.filter((r) => r.id === s.id)[0]
        views.topicDetail = { md: parseCnMonthDay(row.whenText), hm: parseHm(row.whenText), w: want.w }
        const day = out.sessionPicker.days.filter((r) => r.id === s.id)[0]
        const [pm, pd] = day.day.split('.').map(Number)
        views.picker = { md: md(pm, pd), hm: parseHm(day.detailText), w: day.week.slice(1) }
        assert.equal(day.whenText, want.ymd.slice(0, 4) + '年' + want.m + '月' + want.d + '日(周' + want.w + ') · ' + want.hm + ' 集合', zone + ' 选场次面板单日文案')
      }
      const detail = out.sessionDetail[s.id]
      if (detail) {
        assert.equal(detail.state, 'ready', zone)
        const wantEnd = china(s.end)
        assert.equal(detail.dateText, '活动时间 · ' + want.m + '月' + want.d + '日 ' + want.hm + ' - ' + wantEnd.m + '月' + wantEnd.d + '日 ' + wantEnd.hm, zone + ' 场次详情 ' + s.id)
        assert.equal(detail.ticketDateText, '场次时间 ' + want.m + '月' + want.d + '日 ' + want.hm + ' - ' + wantEnd.m + '月' + wantEnd.d + '日 ' + wantEnd.hm, zone + ' 场次详情票种 ' + s.id)
        views.sessionDetail = { md: parseCnMonthDay(detail.dateText), hm: parseHm(detail.dateText), w: want.w }
      }
      for (const [page, v] of Object.entries(views)) {
        assert.deepEqual(v, { md: wantMd, hm: want.hm, w: want.w }, zone + ' 场次 ' + s.id + ' 在 ' + page)
      }
    }
  }
})

test('SC-UI-DATE-COUNT 非必需站(剧情站,无玩法)在每个计数页都算 1 站,已下架站在服务端口径页都不算', () => {
  // 剧情站 status=1 → 进 STATION_COUNT;下架站 status=0 → 不进。服务端口径页显示的正是这个数(上一条已钉),
  // 这里只钉夹具本身没有被改成「数据凑巧一致」:去掉剧情站或算上下架站都会变成 2 / 4。
  assert.equal(LISTED.some((n) => n.templateId === 0), true)
  assert.equal(NODES.some((n) => n.status === 0), true)
  assert.equal(STATION_COUNT, 3)
})

// SC-UI-DATE-COUNT 主题页章节行:info-to-user 只下发 status=1 节点之后,章节「N 个节点」与「包含」/主题卡同口径。
test('SC-UI-DATE-COUNT 主题页章节行「N 个节点」只数上架节点,合计与服务端站数一致', () => {
  for (const { zone, out } of allZones()) {
    const chapterSum = out.topicPage.chapterMetaPlace.reduce((sum, t) => sum + (Number((/^(\d+)/.exec(t || '') || [])[1]) || 0), 0)
    assert.equal(chapterSum, STATION_COUNT, zone)
    assert.deepEqual(out.topicPage.chapterMetaPlace, CHAPTERS.map((c) => LISTED.filter((n) => n.chapterId === c.id).length + ' 个节点'), zone)
  }
})

/* 创建/发布向导 pages/publish/fabu 读 edit-detail,回包带隐藏节点 104。作者保存按旧 id 保留隐藏态
 *   (TopicUpdateByOwnerTest#fullRebuild_keepsBackOfficeHiddenNodeHidden...),所以发布确认页「节点」格、
 *   「共 N 个节点」摘要、「已放置 N 个节点」只数 status=1,与其它页同为 3,也就是保存后上架的站数。 */
test('SC-UI-DATE-COUNT 向导站数与服务端站数口径一致(含后台隐藏节点时)', () => {
  for (const { zone, out } of allZones()) {
    assert.deepEqual({
      wizardPreview: out.wizard.previewStationValue,
      wizardSummary: (/(\d+) 个节点/.exec(out.wizard.summaryLine) || [])[1],
      wizardPassed: out.wizard.passedStationLabel,
    }, {
      wizardPreview: String(STATION_COUNT),
      wizardSummary: String(STATION_COUNT),
      wizardPassed: '已放置 ' + STATION_COUNT + ' 个节点',
    }, zone)
  }
})
