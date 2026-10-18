'use strict'
// BL-184 我的商家纠错:把 /api/community-trust/contributions/* 的 MerchantContributionVO 整形成页面数据。
// 字段与状态全部取自后端真值(MerchantContributionVO / CommunityTrustServiceImpl):
//   reviewStatus ∈ PENDING / APPROVED / REJECTED / NEEDS_EVIDENCE / MERGED
//   补证只在 canSupplement=true 且状态为 NEEDS_EVIDENCE / REJECTED 时可用;成功后服务端把状态改回 PENDING、version+1。
//   rewardStatus ∈ PENDING_REVIEW / PROVISIONAL / CREDITED / VOIDED / NOT_CREDITED(来自 getContributionTaxonomy)。
// /contributions/mine 不分页、一次返回本人全部纠错,分页在端上做。VO 没有商家名,页面不编造。
const model = require('../components/cy/merchant-correction-sheet/model.js')
const report = require('../report-common/view.js')

const PAGE_SIZE = 20
const SUPPLEMENT_MAX = model.DESCRIPTION_MAX // 服务端 requireText(description, 2000)

const STATUS = {
  PENDING: { tone: 'blue', hint: '平台正在审核，审核后才会更新商家信息' },
  NEEDS_EVIDENCE: { tone: 'warning', hint: '平台需要你补充说明后再审核' },
  APPROVED: { tone: 'green', hint: '审核已通过，等待更新到商家信息' },
  MERGED: { tone: 'green', hint: '商家信息已按你的纠错更新' },
  REJECTED: { tone: 'mono', hint: '审核没有通过' },
}
const DECIDED = { APPROVED: 1, MERGED: 1, REJECTED: 1 }
const SUPPLEMENTABLE = { NEEDS_EVIDENCE: 1, REJECTED: 1 }

const REWARD_TEXT = {
  PENDING_REVIEW: '奖励在审核通过后结算',
  PROVISIONAL: '奖励待确认',
  CREDITED: '奖励已到账',
  VOIDED: '奖励已作废',
  NOT_CREDITED: '本次纠错没有奖励',
}

const TABS = [
  { key: 'all', label: '全部' },
  { key: 'active', label: '审核中' },
  { key: 'decided', label: '已有结论' },
]

function text(value) {
  return value == null ? '' : String(value).trim()
}

function titleOf(raw) {
  const type = text(raw.contributionType).toUpperCase()
  if (type === 'INFO_UPDATE') return '商家信息更新'
  return model.ISSUE_LABELS[text(raw.issueType).toUpperCase()] || '商家信息纠错'
}

function rewardText(raw) {
  const status = text(raw.rewardStatus).toUpperCase()
  const base = REWARD_TEXT[status] || ''
  const points = Number(raw.rewardPoints)
  if (base && (status === 'CREDITED' || status === 'PROVISIONAL') && Number.isSafeInteger(points) && points > 0) {
    return base + '：' + points + ' 积分'
  }
  return base
}

function normalizeContribution(raw, offsetMinutes) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const id = report.positiveId(raw.id)
  const status = text(raw.reviewStatus).toUpperCase()
  if (!id || !status) return null
  const info = STATUS[status]
  const version = Number(raw.version)
  return {
    id: String(id),
    status,
    statusText: model.REVIEW_STATUS_TEXT[status] || '状态待同步',
    statusTone: info ? info.tone : 'mono',
    statusHint: info ? info.hint : '状态暂时无法识别，请稍后刷新',
    title: titleOf(raw),
    createText: report.timeText(raw.createTime, offsetMinutes),
    reviewedText: report.timeText(raw.reviewedAt, offsetMinutes),
    mergedText: report.timeText(raw.mergedAt, offsetMinutes),
    description: text(raw.description),
    reviewReason: text(raw.reviewReason),
    rewardText: rewardText(raw),
    version: Number.isSafeInteger(version) && version >= 0 ? version : null,
    decided: !!DECIDED[status],
    canSupplement: raw.canSupplement === true && !!SUPPLEMENTABLE[status]
      && Number.isSafeInteger(version) && version >= 0,
  }
}

// 任何一行不合法都当整份数据不可信(返回 null → 失败态),不静默丢行
function normalizeList(list, offsetMinutes) {
  if (!Array.isArray(list)) return null
  const rows = []
  for (let i = 0; i < list.length; i += 1) {
    const row = normalizeContribution(list[i], offsetMinutes)
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

function validateSupplement(value) {
  const description = text(value)
  if (!description) return { ok: false, description, error: '请写下补充说明' }
  if (description.length > SUPPLEMENT_MAX) return { ok: false, description, error: '补充说明最多 ' + SUPPLEMENT_MAX + ' 字' }
  return { ok: true, description, error: '' }
}

// MerchantContributionSupplementRequest;本批后端拒绝附件,evidenceAssetIds 不传
function buildSupplement(row, description, requestId) {
  return {
    contributionId: Number(row.id),
    expectedVersion: row.version,
    description,
    requestId,
  }
}

// 回读确认:版本比提交前大,且已不再可补证(服务端成功后置 PENDING、canSupplement=false)
function supplementConfirmed(beforeVersion, after) {
  if (!after || typeof after !== 'object') return false
  const v = Number(after.version)
  return Number.isSafeInteger(v) && v > (Number(beforeVersion) || 0) && after.canSupplement !== true
}

// 幂等键:服务端要求 8–96 字符;同一份说明重试时复用,改了说明再换新键
function makeRequestId(contributionId, nowMs, randomPart) {
  const id = report.positiveId(contributionId)
  const now = Number(nowMs) || Date.now()
  const rand = text(randomPart) || Math.random().toString(36).slice(2, 10)
  return ('mcs-' + id + '-' + now.toString(36) + '-' + rand).slice(0, 96)
}

module.exports = {
  PAGE_SIZE, SUPPLEMENT_MAX, TABS, STATUS, REWARD_TEXT,
  normalizeContribution, normalizeList, filterRows, validateSupplement, buildSupplement,
  supplementConfirmed, makeRequestId,
  positiveId: report.positiveId,
}
