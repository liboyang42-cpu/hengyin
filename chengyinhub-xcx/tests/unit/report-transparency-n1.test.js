// CL2 / N1 举报透明化:系统消息「举报进度」→ 举报详情(含 cy-sheet 文字申诉)→ 我的举报。
// 2026-10-15 用户决定:阶段名 已提交/已受理/调查中/已处理/已关闭/申诉中;只做文字申诉;不做被举报方视图;不在「我的」放入口。
// 合同真源:ApiCommunityTrustController + CommunityTrustServiceImpl + CommunityReport*VO。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const xcx = path.resolve(__dirname, '../..')
const repo = path.resolve(xcx, '..')
const read = (p) => fs.readFileSync(path.join(xcx, p), 'utf8')
const readRepo = (p) => fs.readFileSync(path.join(repo, p), 'utf8')
const view = require('../../subpackageMember/report-common/view.js')

const CONTROLLER = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCommunityTrustController.java'
const SERVICE = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CommunityTrustServiceImpl.java'
const APPEAL_DTO = 'chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/CommunityReportAppealRequest.java'
const TRUST_IMPL = SERVICE
const POST_CTL = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCommunityPostController.java'

const notices = require('../../subpackageB/pages/im/list/report-notices.js')

// ---------- 纯函数映射 ----------

test('N1: 阶段枚举与后端 REPORT_STAGES 一致，中文名按用户决定，申诉只在 RESOLVED/CLOSED', () => {
  const src = readRepo(SERVICE)
  const m = src.match(/REPORT_STAGES = [\s\S]*?asList\(([\s\S]*?)\)\)\)/)
  assert.ok(m, '后端 REPORT_STAGES 未找到')
  const backend = m[1].match(/"([A-Z_]+)"/g).map((s) => s.replace(/"/g, '')).sort()
  assert.deepEqual(Object.keys(view.STAGES).sort(), backend)
  const labels = {}
  for (const k of backend) labels[k] = view.stageLabel(k)
  assert.deepEqual(labels, {
    APPEALED: '申诉中', CLOSED: '已关闭', INVESTIGATING: '调查中',
    RESOLVED: '已处理', SUBMITTED: '已提交', TRIAGED: '已受理',
  })
  assert.match(src, /STAGE_RESOLVED\.equals\(from\)\s*&& !CommunityReportCase\.STAGE_CLOSED\.equals\(from\)/)
  for (const s of backend) assert.equal(view.canAppeal(s), s === 'RESOLVED' || s === 'CLOSED', s)
  assert.equal(view.canAppeal('resolved'), true)
  assert.equal(view.canAppeal(''), false)
  assert.equal(view.stageLabel('WHATEVER'), '状态待同步')
})

test('N1: 时间整形——ISO 带时区按本地时区、RuoYi 文本格式原样、坏值为空', () => {
  assert.equal(view.timeText('2026-10-15T02:30:00.000+00:00', 480), '2026-10-15 10:30')
  assert.equal(view.timeText('2026-10-15T02:30:00.000+0000', 480), '2026-10-15 10:30')
  assert.equal(view.timeText('2026-10-15T18:30:00Z', 480), '2026-10-16 02:30')
  assert.equal(view.timeText('2026-10-15 09:05:00'), '2026-10-15 09:05')
  assert.equal(view.timeText(Date.UTC(2026, 9, 15, 0, 0), 480), '2026-10-15 08:00')
  assert.equal(view.timeText(null), '')
  assert.equal(view.timeText('not-a-date+00:00'), '')
})

test('N1: 举报人投影整形——原因取 taxonomy、无 taxonomy 不露代码、终态不显示预计时间', () => {
  const labels = view.reasonLabelMap({ reasons: [{ code: 'harassment', label: '骚扰' }, { code: '', label: 'x' }] })
  assert.deepEqual(labels, { HARASSMENT: '骚扰' })
  const row = view.normalizeReport({
    id: 7, targetType: 'MEMBER', reasonCode: 'HARASSMENT', stage: 'INVESTIGATING',
    description: ' 具体事实 ', anonymityPolicy: 'SUBJECT_HIDDEN',
    estimatedResolveAt: '2026-10-16T02:00:00Z', createTime: '2026-10-15T02:00:00Z', decisionSummary: null,
  }, labels, 480)
  assert.equal(row.id, '7')
  assert.equal(row.targetText, '用户举报')
  assert.equal(row.reasonText, '骚扰')
  assert.equal(row.stageText, '调查中')
  assert.equal(row.stageTone, 'warning')
  assert.equal(row.description, '具体事实')
  assert.equal(row.privacyText, '被举报方看不到你的身份和举报内容')
  assert.equal(row.etaText, '预计 2026-10-16 10:00 前给出结论')
  assert.equal(row.appealable, false)
  const noTax = view.normalizeReport({ id: 8, stage: 'RESOLVED', reasonCode: 'HARASSMENT', estimatedResolveAt: '2026-10-16 10:00:00' }, {})
  assert.equal(noTax.reasonText, '举报原因')
  assert.equal(noTax.etaText, '')
  assert.equal(noTax.appealable, true)
  assert.equal(noTax.targetText, '对象举报')
  assert.equal(view.normalizeReport({ id: 0, stage: 'SUBMITTED' }), null)
  assert.equal(view.normalizeReport({ id: 3 }), null)
  assert.equal(view.normalizeReport([]), null)
})

test('N1: 列表整形整份校验 + 分栏 + 端上分页常量', () => {
  assert.equal(view.normalizeList(null), null)
  assert.equal(view.normalizeList([{ id: 1, stage: 'SUBMITTED' }, { id: 'x' }]), null)
  const rows = view.normalizeList([
    { id: 1, stage: 'SUBMITTED' }, { id: 2, stage: 'RESOLVED' }, { id: 3, stage: 'APPEALED' }, { id: 4, stage: 'CLOSED' },
  ], {})
  assert.deepEqual(view.filterRows(rows, 'all').map((r) => r.id), ['1', '2', '3', '4'])
  assert.deepEqual(view.filterRows(rows, 'active').map((r) => r.id), ['1', '3'])
  assert.deepEqual(view.filterRows(rows, 'decided').map((r) => r.id), ['2', '4'])
  assert.deepEqual(view.TABS.map((t) => t.key), ['all', 'active', 'decided'])
  assert.equal(view.PAGE_SIZE, 20)
})

test('N1: 时间线节点——事件标题、公开备注、未终结时末节点 doing', () => {
  const timeline = [
    { eventType: 'SUBMITTED', toStage: 'SUBMITTED', publicNote: '举报已受理', createTime: '2026-10-15T00:00:00Z' },
    { eventType: 'STAGE_CHANGED', fromStage: 'SUBMITTED', toStage: 'TRIAGED', publicNote: '', createTime: '2026-10-15T01:00:00Z' },
    { eventType: 'APPEALED', fromStage: 'RESOLVED', toStage: 'APPEALED', publicNote: '申诉已受理' },
  ]
  const nodes = view.timelineNodes(timeline, 'APPEALED', 480)
  assert.deepEqual(nodes.map((n) => n.title), ['举报已提交', '进入「已受理」', '已提交申诉'])
  assert.deepEqual(nodes.map((n) => n.status), ['done', 'done', 'doing'])
  assert.equal(nodes[0].time, '2026-10-15 08:00')
  assert.equal(nodes[0].desc, '举报已受理')
  assert.equal(view.timelineNodes(timeline.slice(0, 2), 'RESOLVED').pop().status, 'done')
  assert.deepEqual(view.timelineNodes(null, 'SUBMITTED'), [])
})

test('N1: 申诉校验、幂等键长度、回读确认', () => {
  assert.equal(view.validateAppealReason('  ').ok, false)
  assert.equal(view.validateAppealReason('x'.repeat(1001)).ok, false)
  assert.deepEqual(view.validateAppealReason(' 有异议 '), { ok: true, reason: '有异议', error: '' })
  const key = view.makeRequestId(42, 1760000000000, 'abcd1234')
  assert.ok(key.length >= 8 && key.length <= 96)
  assert.equal(key, 'rpa-42-' + (1760000000000).toString(36) + '-abcd1234')
  assert.equal(view.appealConfirmed(0, { stage: 'APPEALED', timeline: [] }), true)
  assert.equal(view.appealConfirmed(1, { stage: 'INVESTIGATING', timeline: [{ eventType: 'APPEALED' }, { eventType: 'APPEALED' }] }), true)
  assert.equal(view.appealConfirmed(1, { stage: 'INVESTIGATING', timeline: [{ eventType: 'APPEALED' }] }), false)
  assert.equal(view.appealConfirmed(0, null), false)
})

test('N1: 系统消息整形——只取带 reportId 的 SYSTEM_GOVERNANCE 举报进度，同一举报留最新一条', () => {
  const items = [
    { id: 30, notification_type: 'SYSTEM_GOVERNANCE', payload_json: JSON.stringify({ reportId: 5, action: 'REPORT_STAGE_INVESTIGATING', publicStage: 'IN_REVIEW', publicNote: '平台已开始调查' }), read_at: null, create_time: '2026-10-15T02:00:00Z' },
    { id: 29, notification_type: 'SYSTEM_GOVERNANCE', payload_json: JSON.stringify({ reportId: 5, action: 'REPORT_RECEIVED', publicNote: '举报已受理' }), read_at: '2026-10-15T01:00:00Z' },
    { id: 28, notification_type: 'SYSTEM_GOVERNANCE', payload_json: JSON.stringify({ postId: 9, action: 'APPROVED' }) },
    { id: 27, notification_type: 'INTERACTION', payload_json: JSON.stringify({ reportId: 6, action: 'REPORT_RECEIVED' }) },
    { id: 26, notification_type: 'SYSTEM_GOVERNANCE', payload_json: '{bad json' },
    { id: 25, notification_type: 'SYSTEM_GOVERNANCE', payload_json: { reportId: 7, action: 'REPORT_RECEIVED', publicNote: '' }, read_at: '2026-10-14' },
  ]
  assert.deepEqual(notices.reportNoticeRows(items), [
    { key: '30', notificationId: '30', reportId: '5', title: '平台已开始调查', createTime: '2026-10-15T02:00:00Z', unread: true },
    { key: '25', notificationId: '25', reportId: '7', title: '举报进度有更新', createTime: '', unread: false },
  ])
  assert.equal(notices.reportNoticeRows(null), null)
  assert.deepEqual(notices.reportNoticeRows([]), [])
  assert.equal(notices.reportDetailUrl('5'), '/subpackageMember/report-detail/index?reportId=5')
  assert.equal(notices.reportDetailUrl('x'), '')
})

// ---------- 接口合同 ----------

test('N1 合同: 只调用控制器里真实存在的路径；系统消息字段与后端 enqueueReportProgress 一致', () => {
  const ctl = readRepo(CONTROLLER)
  assert.match(ctl, /@RequestMapping\("\/api\/community-trust"\)/)
  for (const [ann, p] of [['GetMapping', '/reports/mine'], ['GetMapping', '/reports/{reportId}'],
    ['PostMapping', '/reports/appeal'], ['GetMapping', '/reports/taxonomy']]) {
    assert.ok(ctl.includes('@' + ann + '("' + p + '")'), ann + ' ' + p)
  }
  const post = readRepo(POST_CTL)
  assert.match(post, /@RequestMapping\("\/api\/v1\/community"\)/)
  assert.match(post, /@GetMapping\("\/notifications"\)/)
  const impl = readRepo(TRUST_IMPL)
  for (const key of ['reportId', 'action', 'publicStage', 'publicNote']) assert.match(impl, new RegExp(`payload\\.put\\("${key}"`))
  assert.match(impl, /payload\.put\("notificationType", "SYSTEM_GOVERNANCE"\)/)
  assert.match(impl, /enqueueReportProgress\(row, "REPORT_RECEIVED"/)
  assert.match(impl, /"REPORT_STAGE_" \+ next/)

  const mine = read('subpackageMember/report-mine/index.js')
  assert.match(mine, /url: '\/api\/community-trust\/reports\/mine', method: 'GET'/)
  const detail = read('subpackageMember/report-detail/index.js')
  // 模板字面量:ui-integrity 识别为 /reports/:dynamic;ui-validity 的 U1 只扫引号字面量,避免把 '/reports/' + id 截成不存在的 /reports
  assert.equal((detail.match(/url: `\/api\/community-trust\/reports\/\$\{id\}`, method: 'GET'/g) || []).length, 2)
  assert.match(detail, /url: '\/api\/community-trust\/reports\/appeal', method: 'POST'/)
  assert.doesNotMatch(detail + mine, /\/subject|view=subject/, '被举报方视图按用户决定不做')
  const im = read('subpackageB/pages/im/list/index.js')
  assert.match(im, /url: '\/api\/v1\/community\/notifications',\s*method: 'GET'/)
})

test('N1 合同: 申诉请求体只含 DTO 字段，不发证据/URL/actorType', () => {
  const dto = readRepo(APPEAL_DTO)
  const fields = (dto.match(/private \w+ (\w+);/g) || []).map((s) => s.replace(/private \w+ (\w+);/, '$1')).sort()
  assert.deepEqual(fields, ['reason', 'reportId', 'requestId'])
  const detail = read('subpackageMember/report-detail/index.js')
  assert.match(detail, /JSON\.stringify\(\{ reportId: this\._reportId, reason: checked\.reason, requestId: this\._requestId \}\)/)
  assert.doesNotMatch(detail, /evidence|chooseMedia|chooseImage|uploadFile|actorType:/)
})

// ---------- 处理器行为(真实调用页面处理器) ----------

function loadPage(rel, responder) {
  const file = path.join(xcx, rel)
  const requests = []
  const nav = []
  let config = null
  const prev = { getApp: global.getApp, Page: global.Page, wx: global.wx, getCurrentPages: global.getCurrentPages }
  global.getApp = () => ({
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    sendRequest: (o) => { requests.push(o); responder(o) },
  })
  global.Page = (c) => { config = c }
  global.wx = {
    navigateTo: (o) => { nav.push(o.url); if (o.complete) o.complete() }, navigateBack: () => nav.push('back'), redirectTo: (o) => nav.push(o.url),
    switchTab: (o) => nav.push(o.url), stopPullDownRefresh: () => {}, showToast: () => {}, showModal: () => {},
    getSystemInfoSync: () => ({ windowWidth: 375 }),
  }
  global.getCurrentPages = () => [{}, {}]
  delete require.cache[file]
  require(file)
  const vm = Object.assign({}, config, { setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb() } })
  vm.data = JSON.parse(JSON.stringify(config.data))
  const restore = () => Object.assign(global, prev)
  return { vm, requests, nav, restore }
}

test('N1 处理器: 详情 403 → 无权限态；坏参数不发请求', () => {
  const h = loadPage('subpackageMember/report-detail/index.js', (o) => {
    if (o.url.endsWith('/taxonomy')) o.success({ code: 200, data: { reasons: [] } })
    else o.success({ code: 403, msg: '无权访问该记录' })
  })
  try {
    h.vm.onLoad({ reportId: '12' })
    assert.equal(h.requests[0].url, '/api/community-trust/reports/12')
    assert.equal(h.vm.data.state, 'no-permission')
    const h2 = loadPage('subpackageMember/report-detail/index.js', () => {})
    h2.vm.onLoad({ reportId: 'abc' })
    assert.equal(h2.vm.data.state, 'missing-param')
    assert.equal(h2.requests.length, 0)
  } finally { h.restore() }
})

test('N1 处理器: 列表读到后分栏与「加载更多」只在端上展开', () => {
  const list = Array.from({ length: 25 }, (_, i) => ({ id: i + 1, stage: i % 2 ? 'RESOLVED' : 'SUBMITTED' }))
  const h = loadPage('subpackageMember/report-mine/index.js', (o) => {
    if (o.url.endsWith('/taxonomy')) o.fail({})
    else o.success({ code: 200, data: list })
  })
  try {
    h.vm.onLoad()
    assert.equal(h.vm.data.state, 'ready')
    assert.equal(h.vm.data.rows.length, 20)
    assert.equal(h.vm.data.hasMore, true)
    h.vm.loadMore()
    assert.equal(h.vm.data.rows.length, 25)
    h.vm.onTab({ detail: { key: 'decided' } })
    assert.equal(h.vm.data.rows.length, 12)
    assert.ok(h.vm.data.rows.every((r) => r.decided))
    assert.equal(h.requests.filter((r) => r.url.endsWith('/mine')).length, 1)
  } finally { h.restore() }
})

test('N1 处理器: 申诉在 sheet 内先确认再提交；网络失败后回读确认；未确认时同键重试', () => {
  let appealCalls = 0
  let appealed = false
  const h = loadPage('subpackageMember/report-detail/index.js', (o) => {
    if (o.url.endsWith('/taxonomy')) { o.success({ code: 200, data: { reasons: [] } }); return }
    if (o.url.endsWith('/appeal')) {
      appealCalls += 1
      if (appealCalls > 1) appealed = true
      o.fail({})
      return
    }
    o.success({ code: 200, data: { id: 5, stage: appealed ? 'APPEALED' : 'RESOLVED', timeline: [] } })
  })
  try {
    h.vm.onLoad({ reportId: '5' })
    assert.equal(h.vm.data.state, 'ready')
    assert.equal(h.vm.data.report.appealable, true)
    h.vm.openAppeal()
    assert.equal(h.vm.data.sheet, 'form')
    h.vm.toConfirm()
    assert.equal(h.vm.data.reasonError, '请写下申诉理由')
    assert.equal(h.vm.data.sheet, 'form')
    h.vm.onReasonInput({ detail: { value: '处理结论与事实不符' } })
    h.vm.submitAppeal()
    assert.equal(appealCalls, 0, '未确认不得提交')
    h.vm.toConfirm()
    assert.equal(h.vm.data.sheet, 'confirm')
    h.vm.submitAppeal()
    assert.equal(appealCalls, 1)
    assert.equal(h.vm.data.sheet, 'unknown', '网络失败且回读没看到申诉 → 未确认')
    const keys = () => h.requests.filter((r) => r.url.endsWith('/appeal')).map((r) => JSON.parse(r.data).requestId)
    h.vm.submitAppeal()
    assert.equal(keys()[1], keys()[0], '同一份说明重试复用幂等键')
    assert.equal(h.vm.data.sheet, 'ok', '回读看到 APPEALED → 成功')
    assert.equal(h.vm.data.report.stageText, '申诉中', '成功后页面以回读为准')
    assert.equal(h.vm.data.report.appealable, false)
  } finally { h.restore() }
})

test('N1 处理器: 业务 409 显示后端原因并回读；未出结论的举报不给申诉入口', () => {
  const h = loadPage('subpackageMember/report-detail/index.js', (o) => {
    if (o.url.endsWith('/taxonomy')) o.success({ code: 200, data: { reasons: [] } })
    else if (o.url.endsWith('/appeal')) o.success({ code: 409, msg: '当前举报阶段不允许申诉' })
    else o.success({ code: 200, data: { id: 6, stage: 'RESOLVED', timeline: [] } })
  })
  try {
    h.vm.onLoad({ reportId: '6' })
    h.vm.openAppeal()
    h.vm.onReasonInput({ detail: { value: '有异议' } })
    h.vm.toConfirm()
    h.vm.submitAppeal()
    assert.equal(h.vm.data.sheet, 'fail')
    assert.equal(h.vm.data.failText, '当前举报阶段不允许申诉')
    const before = h.requests.length
    h.vm.closeSheet()
    assert.equal(h.vm.data.sheet, '')
    assert.ok(h.requests.length > before, '明确失败后回读')
  } finally { h.restore() }
  const h2 = loadPage('subpackageMember/report-detail/index.js', (o) => {
    o.success({ code: 200, data: { id: 6, stage: 'INVESTIGATING', timeline: [] } })
  })
  try {
    h2.vm.onLoad({ reportId: '6' })
    h2.vm.openAppeal()
    assert.equal(h2.vm.data.sheet, '')
  } finally { h2.restore() }
})

test('N1 处理器: 系统 tab 读举报进度通知，点进举报详情；读取失败只在局部报错', () => {
  const h = loadPage('subpackageB/pages/im/list/index.js', (o) => {
    if (o.url === '/api/v1/community/notifications') {
      o.success({ code: 200, data: { items: [{ id: 3, notification_type: 'SYSTEM_GOVERNANCE', payload_json: JSON.stringify({ reportId: 41, action: 'REPORT_STAGE_RESOLVED', publicNote: '已处理' }), read_at: null, create_time: '2026-10-15 10:00:00' }] } })
    } else if (o.url === '/api/im/conversations') o.success({ code: 200, data: [] })
  })
  try {
    h.vm.fmtTime = () => '10:00'
    h.vm.loadReportNotices()
    assert.equal(h.vm.data.reportNotices.length, 1)
    assert.equal(h.vm.data.reportNotices[0].reportId, '41')
    assert.equal(h.vm.data.reportNoticeError, '')
    h.vm.openReportNotice({ currentTarget: { dataset: { reportId: '41' } } })
    assert.deepEqual(h.nav, ['/subpackageMember/report-detail/index?reportId=41'])
  } finally { h.restore() }
  const h2 = loadPage('subpackageB/pages/im/list/index.js', (o) => { o.fail({}) })
  try {
    h2.vm.fmtTime = () => ''
    h2.vm.loadReportNotices()
    assert.equal(h2.vm.data.reportNoticeError, '举报进度暂时没读到')
  } finally { h2.restore() }
})

test('BL-31 处理器: 点开未读举报进度先跳转再标记已读，成功后回读未读状态；失败局部提示不挡查看', () => {
  let readAt = null
  let readOk = true
  const h = loadPage('subpackageB/pages/im/list/index.js', (o) => {
    if (o.url === '/api/v1/community/notifications') {
      o.success({ code: 200, data: { items: [{ id: 3, notification_type: 'SYSTEM_GOVERNANCE', payload_json: JSON.stringify({ reportId: 41, action: 'REPORT_STAGE_RESOLVED', publicNote: '已处理' }), read_at: readAt, create_time: '2026-10-15 10:00:00' }] } })
    } else if (o.url === '/api/v1/community/notifications/3/read') {
      if (!readOk) { o.fail({}); return }
      readAt = '2026-10-15 10:05:00'
      o.success({ code: 200 })
    }
  })
  try {
    h.vm.fmtTime = () => '10:00'
    h.vm.loadReportNotices()
    const row = h.vm.data.reportNotices[0]
    assert.equal(row.notificationId, '3')
    assert.equal(row.unread, true)
    h.vm.openReportNotice({ currentTarget: { dataset: { reportId: row.reportId, noticeId: row.notificationId, unread: row.unread } } })
    assert.deepEqual(h.nav, ['/subpackageMember/report-detail/index?reportId=41'], '先跳转，不等已读结果')
    const read = h.requests.find((r) => r.url === '/api/v1/community/notifications/3/read')
    assert.ok(read, '发出已读请求')
    assert.equal(read.method, 'POST')
    const rid = JSON.parse(read.data).requestId
    assert.ok(rid.length >= 8 && rid.length <= 96, 'requestId 8–96 字符')
    assert.equal(h.vm.data.reportNotices[0].unread, false, '红点以回读 read_at 为准')
    const before = h.requests.length
    h.vm.openReportNotice({ currentTarget: { dataset: { reportId: '41', noticeId: '3', unread: false } } })
    assert.equal(h.requests.length, before, '已读的不再发')
    readOk = false
    readAt = null
    h.vm.loadReportNotices()
    h.vm.openReportNotice({ currentTarget: { dataset: { reportId: '41', noticeId: '3', unread: true } } })
    assert.equal(h.nav.length, 3, '失败不挡查看')
    assert.equal(h.vm.data.reportNoticeError, '举报进度已读状态没更新')
    assert.equal(h.vm.data.reportNotices[0].unread, true, '失败不假装已读')
  } finally { h.restore() }
  assert.equal(notices.makeReadRequestId('3', 1760000000000, 'abcd1234'), 'ntr-3-' + (1760000000000).toString(36) + '-abcd1234')
})

test('BL-52: 申诉成功回执——回执属本举报且阶段可识别才给摘要，缺回执不编造', () => {
  assert.equal(view.appealReceiptText({ reportId: 5, stage: 'APPEALED', publicStage: 'IN_REVIEW', version: 3 }, 5), '举报编号 5 · 当前阶段：申诉中')
  assert.equal(view.appealReceiptText({ reportId: 6, stage: 'APPEALED' }, 5), '', '不是本举报的回执')
  assert.equal(view.appealReceiptText({ reportId: 5, stage: 'WHATEVER' }, 5), '', '未知阶段')
  assert.equal(view.appealReceiptText(null, 5), '')
  assert.equal(view.appealReceiptText('x', 5), '')
})

test('BL-52 处理器: 申诉成功时 ok 半屏展示后端回执；网络失败靠回读确认时不显示回执行', () => {
  let appealed = false
  let mode = 'ok'
  const h = loadPage('subpackageMember/report-detail/index.js', (o) => {
    if (o.url.endsWith('/taxonomy')) { o.success({ code: 200, data: { reasons: [] } }); return }
    if (o.url.endsWith('/appeal')) {
      appealed = true
      if (mode === 'ok') o.success({ code: 200, data: { reportId: 5, stage: 'APPEALED', publicStage: 'IN_REVIEW', version: 4 } })
      else o.fail({})
      return
    }
    o.success({ code: 200, data: { id: 5, stage: appealed ? 'APPEALED' : 'RESOLVED', timeline: appealed ? [{ eventType: 'APPEALED' }] : [] } })
  })
  try {
    h.vm.onLoad({ reportId: '5' })
    h.vm.openAppeal()
    h.vm.onReasonInput({ detail: { value: '处理结论与事实不符' } })
    h.vm.toConfirm()
    h.vm.submitAppeal()
    assert.equal(h.vm.data.sheet, 'ok')
    assert.equal(h.vm.data.receiptText, '举报编号 5 · 当前阶段：申诉中')
  } finally { h.restore() }
  appealed = false
  mode = 'fail'
  const h2 = loadPage('subpackageMember/report-detail/index.js', (o) => {
    if (o.url.endsWith('/taxonomy')) { o.success({ code: 200, data: { reasons: [] } }); return }
    if (o.url.endsWith('/appeal')) { appealed = true; o.fail({}); return }
    o.success({ code: 200, data: { id: 5, stage: appealed ? 'APPEALED' : 'RESOLVED', timeline: appealed ? [{ eventType: 'APPEALED' }] : [] } })
  })
  try {
    h2.vm.onLoad({ reportId: '5' })
    h2.vm.openAppeal()
    h2.vm.onReasonInput({ detail: { value: '处理结论与事实不符' } })
    h2.vm.toConfirm()
    h2.vm.submitAppeal()
    assert.equal(h2.vm.data.sheet, 'ok', '回读确认成功')
    assert.equal(h2.vm.data.receiptText, '', '没有回执就不显示回执行')
  } finally { h2.restore() }
})

// ---------- 入口与注册 ----------

test('N1 入口: 只从系统消息进入；不在设置页放入口；两页注册在 subpackageMember', () => {
  const app = JSON.parse(read('app.json'))
  const pages = app.subPackages.find((p) => p.root === 'subpackageMember').pages
  for (const p of ['report-mine/index', 'report-detail/index']) assert.ok(pages.includes(p), p)
  assert.ok(!pages.includes('report-appeal/index'), '申诉改为详情页内 cy-sheet')
  assert.doesNotMatch(read('pages/shezhi/shezhi.wxml'), /我的举报|report-mine/)
  const im = read('subpackageB/pages/im/list/index.wxml')
  assert.match(im, /activeTab === 'sys' && \(reportNotices\.length \|\| reportNoticeError\)/)
  assert.match(im, /bindtap="openReportNotice"/)
  assert.match(read('subpackageMember/report-detail/index.wxml'), /<cy-sheet show="\{\{!!sheet\}\}"/)
  for (const p of ['report-mine', 'report-detail']) {
    assert.match(read('subpackageMember/' + p + '/index.wxml'), /<cy-state-shell/, p + ' 四态走 cy-state-shell')
    assert.doesNotMatch(read('subpackageMember/' + p + '/index.wxss'), /#[0-9a-fA-F]{3,6}\b/, p + ' 不硬编码颜色')
  }
})

test('BL-212 处理器: 举报进度通知连点只跳一层详情、只发一次已读；complete 后可再次打开', () => {
  const h = loadPage('subpackageB/pages/im/list/index.js', () => {})
  try {
    const pending = []
    global.wx.navigateTo = (o) => { h.nav.push(o.url); pending.push(o) }
    const tap = { currentTarget: { dataset: { reportId: '41', noticeId: '3', unread: true } } }
    h.vm.openReportNotice(tap)
    h.vm.openReportNotice(tap)
    assert.deepEqual(h.nav, ['/subpackageMember/report-detail/index?reportId=41'], '连点只跳 1 次')
    assert.equal(h.requests.filter((r) => r.url === '/api/v1/community/notifications/3/read').length, 1, '只发 1 个已读请求')
    pending[0].complete()
    h.vm.openReportNotice(tap)
    assert.equal(h.nav.length, 2, 'complete 后可再次打开')
    h.vm.onShow()
    h.vm.openReportNotice(tap)
    assert.equal(h.nav.length, 3, 'onShow 后可再次打开')
  } finally { h.restore() }
})
