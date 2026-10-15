// CL2 / N1 举报透明化:我的举报、举报详情(举报人 / 被举报方投影)、文字申诉。
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
const SUBJECT_VO = 'chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/CommunityReportSubjectVO.java'

// ---------- 纯函数映射 ----------

test('N1: 阶段枚举与后端 REPORT_STAGES 完全一致，申诉只在 RESOLVED/CLOSED', () => {
  const src = readRepo(SERVICE)
  const m = src.match(/REPORT_STAGES = [\s\S]*?asList\(([\s\S]*?)\)\)\)/)
  assert.ok(m, '后端 REPORT_STAGES 未找到')
  const backend = m[1].match(/"([A-Z_]+)"/g).map((s) => s.replace(/"/g, '')).sort()
  assert.deepEqual(Object.keys(view.STAGES).sort(), backend)
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
  }, labels, 'mine', 480)
  assert.equal(row.id, '7')
  assert.equal(row.targetText, '用户举报')
  assert.equal(row.reasonText, '骚扰')
  assert.equal(row.stageText, '核查中')
  assert.equal(row.stageTone, 'warning')
  assert.equal(row.description, '具体事实')
  assert.equal(row.privacyText, '被举报方看不到你的身份和举报内容')
  assert.equal(row.etaText, '预计 2026-10-16 10:00 前给出结论')
  assert.equal(row.appealable, false)
  const noTax = view.normalizeReport({ id: 8, stage: 'RESOLVED', reasonCode: 'HARASSMENT', estimatedResolveAt: '2026-10-16 10:00:00' }, {}, 'mine')
  assert.equal(noTax.reasonText, '举报原因')
  assert.equal(noTax.etaText, '')
  assert.equal(noTax.appealable, true)
  assert.equal(noTax.targetText, '对象举报')
  assert.equal(view.normalizeReport({ id: 0, stage: 'SUBMITTED' }), null)
  assert.equal(view.normalizeReport({ id: 3 }), null)
  assert.equal(view.normalizeReport([]), null)
})

test('N1: 被举报方投影不出现描述字段，且后端 SubjectVO 类型上就没有举报人/描述/证据', () => {
  const row = view.normalizeReport({ id: 9, stage: 'CLOSED', description: '不该出现', targetType: 'MEMBER' }, {}, 'subject')
  assert.equal(row.description, undefined)
  assert.match(row.privacyText, /举报人身份与举报内容不会向你展示/)
  const vo = readRepo(SUBJECT_VO)
  assert.doesNotMatch(vo, /private .* (description|evidenceAssetIds|reporterMemberId);/)
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
  assert.deepEqual(nodes.map((n) => n.title), ['举报已提交', '进入「已分派」', '已提交申诉'])
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

// ---------- 接口合同 ----------

test('N1 合同: 页面只调用控制器里真实存在的 GET/POST 路径', () => {
  const ctl = readRepo(CONTROLLER)
  assert.match(ctl, /@RequestMapping\("\/api\/community-trust"\)/)
  for (const [ann, p] of [['GetMapping', '/reports/mine'], ['GetMapping', '/reports/{reportId}'],
    ['GetMapping', '/reports/{reportId}/subject'], ['PostMapping', '/reports/appeal'], ['GetMapping', '/reports/taxonomy']]) {
    assert.ok(ctl.includes('@' + ann + '("' + p + '")'), ann + ' ' + p)
  }
  const mine = read('subpackageMember/report-mine/index.js')
  assert.match(mine, /url: '\/api\/community-trust\/reports\/mine', method: 'GET'/)
  assert.match(mine, /url: '\/api\/community-trust\/reports\/taxonomy', method: 'GET'/)
  const detail = read('subpackageMember/report-detail/index.js')
  const subjectUrl = "'/api/community-trust/reports/' + id + '/subject' : '/api/community-trust/reports/' + id,"
  assert.ok(detail.includes("url: st.mode === 'subject' ? " + subjectUrl))
  assert.ok(read('subpackageMember/report-appeal/index.js').includes("url: mode === 'subject' ? " + subjectUrl))
  const appeal = read('subpackageMember/report-appeal/index.js')
  assert.match(appeal, /url: '\/api\/community-trust\/reports\/appeal', method: 'POST'/)
})

test('N1 合同: 申诉请求体只含 DTO 字段，不发证据/URL/actorType', () => {
  const dto = readRepo(APPEAL_DTO)
  const fields = (dto.match(/private \w+ (\w+);/g) || []).map((s) => s.replace(/private \w+ (\w+);/, '$1')).sort()
  assert.deepEqual(fields, ['reason', 'reportId', 'requestId'])
  const appeal = read('subpackageMember/report-appeal/index.js')
  assert.match(appeal, /JSON\.stringify\(\{ reportId: this\._reportId, reason, requestId: this\._requestId \}\)/)
  assert.doesNotMatch(appeal, /evidence|chooseMedia|chooseImage|uploadFile|actorType:/)
})

// ---------- 处理器行为(真实调用页面处理器) ----------

function loadPage(rel, responder) {
  const file = path.join(xcx, rel)
  const requests = []
  const nav = []
  let modalOpts = null
  let config = null
  const prev = { getApp: global.getApp, Page: global.Page, wx: global.wx, getCurrentPages: global.getCurrentPages }
  global.getApp = () => ({
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    sendRequest: (o) => { requests.push(o); responder(o) },
  })
  global.Page = (c) => { config = c }
  global.wx = {
    navigateTo: (o) => nav.push(o.url), navigateBack: () => nav.push('back'), redirectTo: (o) => nav.push(o.url),
    switchTab: (o) => nav.push(o.url), stopPullDownRefresh: () => {}, showToast: () => {}, showModal: (o) => { modalOpts = o },
  }
  global.getCurrentPages = () => [{}, {}]
  delete require.cache[file]
  require(file)
  const vm = Object.assign({}, config, { setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb() } })
  vm.data = JSON.parse(JSON.stringify(config.data))
  const restore = () => Object.assign(global, prev)
  return { vm, requests, nav, modal: () => modalOpts, restore }
}

test('N1 处理器: 详情 403 → 无权限态；被举报方走 /subject', () => {
  const h = loadPage('subpackageMember/report-detail/index.js', (o) => {
    if (o.url.endsWith('/taxonomy')) o.success({ code: 200, data: { reasons: [] } })
    else o.success({ code: 403, msg: '无权访问该记录' })
  })
  try {
    h.vm.onLoad({ reportId: '12', view: 'subject' })
    assert.equal(h.requests[0].url, '/api/community-trust/reports/12/subject')
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

test('N1 处理器: 申诉需确认；网络失败后回读确认成功；未确认时同键重试', () => {
  let appealCalls = 0
  let readCalls = 0
  let appealed = false
  const h = loadPage('subpackageMember/report-appeal/index.js', (o) => {
    if (o.url.endsWith('/appeal')) {
      appealCalls += 1
      if (appealCalls === 1) { o.fail({}); return }
      appealed = true
      o.fail({})
      return
    }
    readCalls += 1
    o.success({ code: 200, data: { id: 5, stage: appealed ? 'APPEALED' : 'RESOLVED', timeline: [] } })
  })
  try {
    h.vm.onLoad({ reportId: '5' })
    assert.equal(h.vm.data.state, 'form')
    h.vm.submit()
    assert.equal(h.vm.data.reasonError, '请写下申诉理由')
    h.vm.onReasonInput({ detail: { value: '处理结论与事实不符' } })
    h.vm.submit()
    assert.equal(appealCalls, 0, '确认前不得提交')
    h.modal().success({ confirm: true })
    assert.equal(appealCalls, 1)
    assert.equal(h.vm.data.phase, 'unknown', '网络失败且回读没看到申诉 → 未确认')
    const firstKey = JSON.parse(h.requests.filter((r) => r.url.endsWith('/appeal'))[0].data).requestId
    h.vm.onUnknownRetry()
    const secondKey = JSON.parse(h.requests.filter((r) => r.url.endsWith('/appeal'))[1].data).requestId
    assert.equal(secondKey, firstKey, '同一份说明重试复用幂等键')
    assert.equal(h.vm.data.phase, 'ok', '回读看到 APPEALED → 成功')
    assert.ok(readCalls >= 3)
  } finally { h.restore() }
})

test('N1 处理器: 业务 409 显示后端原因；未出结论的举报不展示申诉表单', () => {
  const h = loadPage('subpackageMember/report-appeal/index.js', (o) => {
    if (o.url.endsWith('/appeal')) o.success({ code: 409, msg: '当前举报阶段不允许申诉' })
    else o.success({ code: 200, data: { id: 6, stage: 'RESOLVED', timeline: [] } })
  })
  try {
    h.vm.onLoad({ reportId: '6' })
    h.vm.onReasonInput({ detail: { value: '有异议' } })
    h.vm.send('有异议')
    assert.equal(h.vm.data.phase, 'fail')
    assert.equal(h.vm.data.failText, '当前举报阶段不允许申诉')
  } finally { h.restore() }
  const h2 = loadPage('subpackageMember/report-appeal/index.js', (o) => {
    o.success({ code: 200, data: { id: 6, stage: 'INVESTIGATING', timeline: [] } })
  })
  try {
    h2.vm.onLoad({ reportId: '6' })
    assert.equal(h2.vm.data.state, 'closed')
  } finally { h2.restore() }
})

// ---------- 入口与注册 ----------

test('N1 入口: 三页注册在 subpackageMember，设置页有「我的举报」入口', () => {
  const app = JSON.parse(read('app.json'))
  const pages = app.subPackages.find((p) => p.root === 'subpackageMember').pages
  for (const p of ['report-mine/index', 'report-detail/index', 'report-appeal/index']) assert.ok(pages.includes(p), p)
  assert.match(read('pages/shezhi/shezhi.wxml'), /id="settings-reports"[^>]*title="我的举报"[^>]*bind:tap="goMyReports"/)
  assert.match(read('pages/shezhi/shezhi.js'), /goMyReports\(\) \{\s*wx\.navigateTo\(\{ url: '\/subpackageMember\/report-mine\/index' \}\)/)
  for (const p of ['report-mine', 'report-detail', 'report-appeal']) {
    const wxml = read('subpackageMember/' + p + '/index.wxml')
    assert.match(wxml, /<cy-state-shell/, p + ' 四态走 cy-state-shell')
    assert.doesNotMatch(read('subpackageMember/' + p + '/index.wxss'), /#[0-9a-fA-F]{3,6}\b/, p + ' 不硬编码颜色')
  }
})
