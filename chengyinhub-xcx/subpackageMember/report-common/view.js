'use strict'
// 举报透明化(N1)纯函数:把 /api/community-trust/reports/* 的投影整形成页面数据。
// 字段与状态全部取自后端真值(ApiCommunityTrustController / CommunityTrustServiceImpl):
//   stage ∈ SUBMITTED → TRIAGED → INVESTIGATING → RESOLVED → CLOSED;RESOLVED/CLOSED 可申诉 → APPEALED → INVESTIGATING
//   publicStage 是后端给的粗粒度投影(RECEIVED / IN_REVIEW / DECIDED),这里以 stage 细分为准。
// /reports/mine 不分页、一次返回本人全部举报(create_time 倒序),分页在端上做。
const PAGE_SIZE = 20
const APPEAL_MAX = 1000 // community_report_appeal.reason VARCHAR(1000),服务端 requireText(…, 1000)

const STAGES = {
// 阶段中文名为 2026-10-15 用户确认口径(TASKS.md「用户决定」CL2)。
  SUBMITTED: { label: '已提交', tone: 'blue', hint: '平台已收到举报，等待受理' },
  TRIAGED: { label: '已受理', tone: 'blue', hint: '平台已受理，等待调查' },
  INVESTIGATING: { label: '调查中', tone: 'warning', hint: '平台正在调查相关事实' },
  RESOLVED: { label: '已处理', tone: 'done', hint: '平台已给出处理结论' },
  CLOSED: { label: '已关闭', tone: 'done', hint: '本次举报已关闭' },
  APPEALED: { label: '申诉中', tone: 'warning', hint: '申诉已受理，平台会重新调查' },
}
const APPEALABLE = { RESOLVED: 1, CLOSED: 1 }
const TERMINAL = { RESOLVED: 1, CLOSED: 1 }

const TARGETS = {
  MEMBER: '用户', MERCHANT: '商家', CLUB: '俱乐部', ACTIVITY: '活动',
  CONTENT: '内容', COMMENT: '评论', SAFETY_RATING: '安全评价',
}

const TABS = [
  { key: 'all', label: '全部' },
  { key: 'active', label: '处理中' },
  { key: 'decided', label: '已有结论' },
]

function text(value) {
  return value == null ? '' : String(value).trim()
}

function positiveId(value) {
  const n = Number(value)
  return Number.isSafeInteger(n) && n > 0 ? n : 0
}

function stageInfo(stage) {
  return STAGES[text(stage).toUpperCase()] || null
}

function stageLabel(stage) {
  const info = stageInfo(stage)
  return info ? info.label : '状态待同步'
}

function canAppeal(stage) {
  return !!APPEALABLE[text(stage).toUpperCase()]
}

function pad(n) { return n < 10 ? '0' + n : String(n) }

// 后端 Date 走 Spring Boot 默认 Jackson:ISO 带时区(…T…+00:00);也兼容 RuoYi 的 "yyyy-MM-dd HH:mm:ss" 与毫秒数。
// offsetMinutes 仅供单测固定时区;页面上用设备本地时区。
function timeText(value, offsetMinutes) {
  if (value === null || value === undefined || value === '') return ''
  let date = null
  if (typeof value === 'number') {
    date = new Date(value)
  } else {
    const raw = String(value).trim()
    if (/[zZ]$|[+-]\d{2}:?\d{2}$/.test(raw)) {
      date = new Date(raw.replace(/([+-]\d{2})(\d{2})$/, '$1:$2'))
    } else {
      const m = raw.match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})/)
      return m ? m[1] + ' ' + m[2] : raw
    }
  }
  if (!date || isNaN(date.getTime())) return ''
  if (typeof offsetMinutes === 'number') {
    const shifted = new Date(date.getTime() + offsetMinutes * 60000)
    return shifted.getUTCFullYear() + '-' + pad(shifted.getUTCMonth() + 1) + '-' + pad(shifted.getUTCDate())
      + ' ' + pad(shifted.getUTCHours()) + ':' + pad(shifted.getUTCMinutes())
  }
  return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
    + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes())
}

// taxonomy.reasons = [{code,label,…}];读不到时返回空表,页面用兜底文案,不显示原始代码
function reasonLabelMap(taxonomy) {
  const map = {}
  const list = taxonomy && Array.isArray(taxonomy.reasons) ? taxonomy.reasons : []
  for (let i = 0; i < list.length; i += 1) {
    const code = text(list[i] && list[i].code).toUpperCase()
    const label = text(list[i] && list[i].label)
    if (code && label) map[code] = label
  }
  return map
}

// 举报人本人投影(GET /reports/{id} 或 /reports/mine 的行)。被举报方视图按用户决定不做。
function normalizeReport(raw, reasonLabels, offsetMinutes) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const id = positiveId(raw.id)
  const stage = text(raw.stage).toUpperCase()
  if (!id || !stage) return null
  const info = stageInfo(stage)
  const labels = reasonLabels || {}
  const reasonCode = text(raw.reasonCode).toUpperCase()
  const targetType = text(raw.targetType).toUpperCase()
  const eta = timeText(raw.estimatedResolveAt, offsetMinutes)
  const row = {
    id: String(id),
    stage,
    stageText: info ? info.label : '状态待同步',
    stageHint: info ? info.hint : '状态暂时无法识别，请稍后刷新',
    stageTone: info ? info.tone : 'mono',
    targetText: (TARGETS[targetType] || '对象') + '举报',
    reasonText: labels[reasonCode] || '举报原因',
    createText: timeText(raw.createTime, offsetMinutes),
    etaText: eta && !TERMINAL[stage] ? '预计 ' + eta + ' 前给出结论' : '',
    decisionSummary: text(raw.decisionSummary),
    appealable: canAppeal(stage),
    appealing: stage === 'APPEALED',
    decided: !!TERMINAL[stage],
  }
  row.description = text(raw.description)
  row.privacyText = text(raw.anonymityPolicy) === 'SUBJECT_HIDDEN'
    ? '被举报方看不到你的身份和举报内容' : ''
  return row
}

// 任何一行不合法都当整份数据不可信(返回 null → 失败态),不静默丢行
function normalizeList(list, reasonLabels, offsetMinutes) {
  if (!Array.isArray(list)) return null
  const rows = []
  for (let i = 0; i < list.length; i += 1) {
    const row = normalizeReport(list[i], reasonLabels, offsetMinutes)
    if (!row) return null
    rows.push(row)
  }
  return rows
}

function filterRows(rows, tabKey) {
  const all = Array.isArray(rows) ? rows : []
  if (tabKey === 'active') return all.filter(function (r) { return !r.decided })
  if (tabKey === 'decided') return all.filter(function (r) { return r.decided })
  return all.slice()
}

function eventTitle(item) {
  const type = text(item && item.eventType).toUpperCase()
  if (type === 'SUBMITTED') return '举报已提交'
  if (type === 'APPEALED') return '已提交申诉'
  const to = text(item && item.toStage)
  return to ? '进入「' + stageLabel(to) + '」' : '进度更新'
}

// cy-timeline nodes:[{title,time,desc,status}];已终结时最后一步为 done,否则为 doing
function timelineNodes(timeline, currentStage, offsetMinutes) {
  const list = Array.isArray(timeline) ? timeline : []
  const terminal = !!TERMINAL[text(currentStage).toUpperCase()]
  return list.map(function (item, index) {
    const last = index === list.length - 1
    return {
      title: eventTitle(item),
      time: timeText(item && item.createTime, offsetMinutes),
      desc: text(item && item.publicNote),
      status: last && !terminal ? 'doing' : 'done',
    }
  })
}

function appealEventCount(timeline) {
  const list = Array.isArray(timeline) ? timeline : []
  let n = 0
  for (let i = 0; i < list.length; i += 1) {
    if (text(list[i] && list[i].eventType).toUpperCase() === 'APPEALED') n += 1
  }
  return n
}

// 服务端回读确认:申诉事件数比提交前多,或阶段已是 APPEALED(平台尚未接手前)
function appealConfirmed(beforeCount, after) {
  if (!after || typeof after !== 'object') return false
  return appealEventCount(after.timeline) > (Number(beforeCount) || 0)
    || text(after.stage).toUpperCase() === 'APPEALED'
}

function validateAppealReason(value) {
  const reason = text(value)
  if (!reason) return { ok: false, reason, error: '请写下申诉理由' }
  if (reason.length > APPEAL_MAX) return { ok: false, reason, error: '申诉说明最多 ' + APPEAL_MAX + ' 字' }
  return { ok: true, reason, error: '' }
}

// BL-52:申诉成功回执(CommunityReportAppealReceiptVO:reportId/stage/publicStage/version)。
// 只在回执确属本举报且阶段可识别时给一行;回执缺失(如网络失败后靠回读确认)就不显示,不编造。
function appealReceiptText(receipt, reportId) {
  if (!receipt || typeof receipt !== 'object') return ''
  const id = positiveId(receipt.reportId)
  if (!id || id !== positiveId(reportId)) return ''
  const info = stageInfo(receipt.stage)
  return info ? '举报编号 ' + id + ' · 当前阶段：' + info.label : ''
}

// 幂等键:服务端要求 8–96 字符;同一份说明重试时复用,改了说明再换新键
function makeRequestId(reportId, nowMs, randomPart) {
  const id = positiveId(reportId)
  const now = Number(nowMs) || Date.now()
  const rand = text(randomPart) || Math.random().toString(36).slice(2, 10)
  return ('rpa-' + id + '-' + now.toString(36) + '-' + rand).slice(0, 96)
}

module.exports = {
  PAGE_SIZE, APPEAL_MAX, STAGES, TARGETS, TABS,
  stageLabel, canAppeal, timeText, reasonLabelMap, normalizeReport, normalizeList,
  filterRows, timelineNodes, appealEventCount, appealConfirmed, validateAppealReason, makeRequestId,
  appealReceiptText,
  positiveId,
}
