// 商家信息纠错半屏的纯函数(BL-67;结构照 BL-32 起的共用举报半屏)。
// 合同(ApiCommunityTrustController / CommunityTrustServiceImpl#submitContribution):
//   GET  /api/community-trust/contributions/taxonomy → { contributionTypes[], fieldCodes[], issueTypes[], ... }
//   POST /api/community-trust/contributions/submit   MerchantContributionSubmitRequest
//        { merchantId, contributionType, fieldChanges, issueType, description, evidenceAssetIds, idempotencyKey }
//   本卡只做 ISSUE_REPORT(问题上报):issueType 取 taxonomy,description 必填 1–2000 字,
//   idempotencyKey 8–96 字符;本批后端拒绝附件,evidenceAssetIds 不传。
//   成功体为 MerchantContributionVO(含 id / reviewStatus)。
'use strict'

const DESCRIPTION_MAX = 2000
// 中文名只给后端 ISSUE_TYPES 里真有的码;taxonomy 里出现未知码时不展示,不臆造含义。
const ISSUE_LABELS = {
  CLOSED: '已停业或关门',
  ADDRESS: '地址不对',
  BUSINESS_TIME: '营业时间不对',
  CONTENT: '介绍内容有误',
  OTHER: '其他问题',
}
const REVIEW_STATUS_TEXT = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '未通过',
  NEEDS_EVIDENCE: '需补充说明',
  MERGED: '已更新',
}

function normalizeTaxonomy(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const types = Array.isArray(raw.contributionTypes) ? raw.contributionTypes : []
  if (types.indexOf('ISSUE_REPORT') < 0 || !Array.isArray(raw.issueTypes)) return null
  const issues = raw.issueTypes
    .filter((code) => typeof code === 'string' && Object.prototype.hasOwnProperty.call(ISSUE_LABELS, code))
    .map((code) => ({ code, label: ISSUE_LABELS[code] }))
  return issues.length ? { issues } : null
}

function validMerchantId(merchantId) {
  if (typeof merchantId !== 'number' && typeof merchantId !== 'string') return false
  if (typeof merchantId === 'string' && !/^[1-9]\d{0,15}$/.test(merchantId.trim())) return false
  const id = Number(merchantId)
  return Number.isSafeInteger(id) && id > 0
}

function checkDescription(text) {
  const value = String(text == null ? '' : text).trim()
  if (!value) return { ok: false, error: '请写一下哪里不对、正确的信息是什么' }
  if (value.length > DESCRIPTION_MAX) return { ok: false, error: '说明最多 ' + DESCRIPTION_MAX + ' 字' }
  return { ok: true, value }
}

function makeIdempotencyKey(merchantId, now, rand) {
  const t = (now == null ? Date.now() : now).toString(36)
  const r = (rand == null ? Math.random() : rand).toString(36).slice(2, 10) || '0'
  return ('mct-' + merchantId + '-' + t + '-' + r).slice(0, 96)
}

function buildPayload(opts) {
  return {
    merchantId: Number(opts.merchantId),
    contributionType: 'ISSUE_REPORT',
    issueType: opts.issueType,
    description: opts.description,
    idempotencyKey: opts.idempotencyKey,
  }
}

function receiptOf(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return { id: '', statusText: '' }
  const id = data.id == null || data.id === '' ? '' : String(data.id)
  if (!/^[1-9]\d{0,18}$/.test(id) || typeof data.id === 'number' && !Number.isSafeInteger(data.id)
      || !REVIEW_STATUS_TEXT[data.reviewStatus]) return { id: '', statusText: '' }
  return { id, statusText: REVIEW_STATUS_TEXT[data.reviewStatus] || '' }
}

function pendingKey(owner, merchantId) { return 'merchant_correction_pending_v1:' + owner + ':' + merchantId }
function loadPending(storage, owner, merchantId) {
  const value = storage.getStorageSync(pendingKey(owner, merchantId))
  if (!value) return null
  if (!value || String(value.merchantId) !== String(merchantId) || !value.idempotencyKey
      || !ISSUE_LABELS[value.issueType] || !checkDescription(value.description).ok) throw Error('上次的纠错内容读不出来，请重新填写')
  return JSON.parse(JSON.stringify(value))
}
function savePending(storage, owner, payload) {
  storage.setStorageSync(pendingKey(owner, payload.merchantId), JSON.parse(JSON.stringify(payload)))
  if (JSON.stringify(loadPending(storage, owner, payload.merchantId)) !== JSON.stringify(payload)) throw Error('纠错没能存到本机，请再试一次')
}
function clearPending(storage, owner, payload) {
  const saved = loadPending(storage, owner, payload.merchantId)
  if (!saved || saved.idempotencyKey !== payload.idempotencyKey) return false
  storage.removeStorageSync(pendingKey(owner, payload.merchantId))
  return !loadPending(storage, owner, payload.merchantId)
}

module.exports = {
  DESCRIPTION_MAX, ISSUE_LABELS, REVIEW_STATUS_TEXT,
  normalizeTaxonomy, validMerchantId, checkDescription, makeIdempotencyKey, buildPayload, receiptOf,
  loadPending, savePending, clearPending,
}
