// 共用举报半屏的纯函数(BL-32 起,BL-33..36 复用)。
// 合同(ApiCommunityTrustController / CommunityTrustServiceImpl#submitReport):
//   GET  /api/community-trust/reports/taxonomy → { policyVersion, targetTypes[], reasons[{code,label,priority,firstResponseMinutes,emergency}] }
//   POST /api/community-trust/reports/submit   { targetType, targetId, reasonCode, policyVersion, description, requestId }
//   description 必填 1–2000 字;requestId 8–96 字符,同键同内容重放返回同一条举报;
//   本批只做纯文字(evidenceAssetIds 不传)。成功体为 CommunityReportVO(含 id)。
'use strict'

const DESCRIPTION_MAX = 2000
const TARGET_TYPES = ['MEMBER', 'MERCHANT', 'CLUB', 'ACTIVITY']

function normalizeTaxonomy(raw) {
  if (!raw || typeof raw !== 'object') return null
  const policyVersion = typeof raw.policyVersion === 'string' ? raw.policyVersion.trim() : ''
  if (!policyVersion || !Array.isArray(raw.reasons)) return null
  const reasons = raw.reasons
    .filter((r) => r && typeof r.code === 'string' && r.code && typeof r.label === 'string' && r.label)
    .map((r) => ({
      code: r.code,
      label: r.label,
      firstResponseMinutes: Number.isFinite(Number(r.firstResponseMinutes)) && Number(r.firstResponseMinutes) > 0 ? Number(r.firstResponseMinutes) : 0,
      emergency: r.emergency === true,
    }))
  if (!reasons.length) return null
  const targetTypes = Array.isArray(raw.targetTypes) ? raw.targetTypes : []
  const emergencyGuidanceCode = typeof raw.emergencyGuidanceCode === 'string' ? raw.emergencyGuidanceCode : ''
  return { policyVersion, reasons, targetTypes, emergencyGuidanceCode }
}

// BL-51:原因说明只来自 taxonomy 自带字段 —— firstResponseMinutes(首次响应时效目标)与 emergency。
function responseText(minutes) {
  const m = Number(minutes)
  if (!Number.isFinite(m) || m <= 0) return ''
  let span
  if (m < 60) span = Math.round(m) + ' 分钟'
  else if (m <= 48 * 60 && m % 60 === 0) span = (m / 60) + ' 小时'
  else if (m < 48 * 60) span = Math.round(m / 60) + ' 小时'
  else span = Math.round(m / 1440) + ' 天'
  return '平台目标在约 ' + span + '内首次响应'
}

const GUIDANCE = {
  CONTACT_LOCAL_EMERGENCY_SERVICES: '如果你或他人正处于危险中，请先拨打 110 等当地紧急电话，再提交举报。',
}

function reasonNote(reason, emergencyGuidanceCode) {
  if (!reason) return { response: '', emergency: '' }
  return {
    response: responseText(reason.firstResponseMinutes),
    emergency: reason.emergency ? (GUIDANCE[emergencyGuidanceCode] || '') : '',
  }
}

function validTarget(targetType, targetId) {
  const id = Number(targetId)
  return TARGET_TYPES.indexOf(targetType) >= 0 && Number.isSafeInteger(id) && id > 0
}

function checkDescription(text) {
  const value = String(text == null ? '' : text).trim()
  if (!value) return { ok: false, error: '请写一下具体发生了什么' }
  if (value.length > DESCRIPTION_MAX) return { ok: false, error: '说明最多 ' + DESCRIPTION_MAX + ' 字' }
  return { ok: true, value }
}

function makeRequestId(targetType, targetId, now, rand) {
  const t = (now == null ? Date.now() : now).toString(36)
  const r = (rand == null ? Math.random() : rand).toString(36).slice(2, 10) || '0'
  return ('rpt-' + String(targetType).toLowerCase() + '-' + targetId + '-' + t + '-' + r).slice(0, 96)
}

function buildPayload(opts) {
  return {
    targetType: opts.targetType,
    targetId: Number(opts.targetId),
    reasonCode: opts.reasonCode,
    policyVersion: opts.policyVersion,
    description: opts.description,
    requestId: opts.requestId,
  }
}

function receiptId(data) {
  const id = data && data.id
  return id == null || id === '' ? '' : String(id)
}

module.exports = { DESCRIPTION_MAX, normalizeTaxonomy, responseText, reasonNote, validTarget, checkDescription, makeRequestId, buildPayload, receiptId }
