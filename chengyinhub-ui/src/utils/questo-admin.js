// Questo 后台只读/审核面的共用显示规则（§18 E/F、§19、§20、§27.4）。
// 原则：服务端事实决定显示；null/未知不写成 0；已记录/应计不写成已付或可提现。

function own(map, key) {
  return key !== null && key !== undefined && Object.prototype.hasOwnProperty.call(map, String(key))
}

function lookup(map, key, fallback) {
  return own(map, key) ? map[String(key)] : fallback
}

export function isKnownNumber(value) {
  if (value === null || value === undefined || value === '') return false
  if (typeof value === 'boolean' || Array.isArray(value) || typeof value === 'object') return false
  return Number.isFinite(Number(value))
}

/** 计数：null/未知显示 —，不显示 0。 */
export function countText(value) {
  return isKnownNumber(value) ? String(Number(value)) : '—'
}

/** 金额（元）：null/未知显示 —；带币种。 */
export function moneyText(value, currency) {
  if (!isKnownNumber(value)) return '—'
  const text = Number(value).toFixed(2)
  return currency && currency !== 'CNY' ? `${text} ${currency}` : `¥${text}`
}

/** 分转元，只做显示；null 保留为 —。 */
export function fenText(fen, currency) {
  if (!isKnownNumber(fen)) return '—'
  return moneyText(Number(fen) / 100, currency)
}

/** 比例：后端给 0~1 的小数。null 交给调用方决定（未成熟/无样本）。 */
export function rateText(value) {
  if (!isKnownNumber(value)) return null
  return `${(Number(value) * 100).toFixed(1)}%`
}

/** 业务拒绝：request.js rawBusinessError 透传的 AjaxResult。 */
export function businessError(err) {
  if (err && typeof err === 'object' && !(err instanceof Error)) {
    return {
      code: err.code,
      reasonCode: typeof err.reasonCode === 'string' ? err.reasonCode : null,
      message: typeof err.msg === 'string' && err.msg ? err.msg : '请求失败'
    }
  }
  return { code: null, reasonCode: null, message: err && err.message ? err.message : '请求失败' }
}

// ---------------------------------------------------------------- §18 视频悬赏

const CAMPAIGN_STATUS = { 0: '草稿', 1: '进行中', 2: '已暂停', 3: '已关闭' }
export function campaignStatusLabel(status) { return lookup(CAMPAIGN_STATUS, status, '状态待确认') }

const SUBMISSION_STATUS = { 0: '已提交', 1: '审核中', 2: '已通过', 3: '已驳回', 4: '已撤销', 5: '申诉中', 6: '已取消' }
export function submissionStatusLabel(status) { return lookup(SUBMISSION_STATUS, status, '状态待确认') }

/** 可审核：已提交 / 审核中 / 申诉中。 */
export function canReviewSubmission(status) { return [0, 1, 5].includes(Number(status)) && isKnownNumber(status) }
export function canRevokeSubmission(status) { return Number(status) === 2 && isKnownNumber(status) }

export function groupVerifyLabel(row) {
  if (!row) return '未核验'
  return row.verifiedGroupKey ? `已核验（${row.verifiedGroupKey}）` : '未核验'
}

/** 已通过但未核验原视频组：不显示应计金额。 */
export function accrualBlockedByGroup(row) {
  return !!row && Number(row.status) === 2 && !row.verifiedGroupKey
}

const PAYOUT_STATE = { 0: '已记录（未开放兑付）', 3: '已被替代', 4: '已撤销' }
/** 本域无兑付实现：1/2 等其它值只显示待确认，不显示待提现/已到账。 */
export function accrualPayoutLabel(state) { return lookup(PAYOUT_STATE, state, '状态待确认') }

export const ATTRIBUTION_TEXT = {
  UNBOUND: '未绑定推广来源，暂无确认数据',
  OWNER_NOT_SET: '该活动未设置归属作者，成交数据不可用'
}
/** 归因：只有 BOUND 且 confirmed 才显示数字；否则给原因，数字保持 null。 */
export function attributionView(result) {
  if (!result || typeof result !== 'object') return { confirmed: false, text: '暂无归因数据' }
  if (result.status === 'BOUND' && result.confirmed === true) {
    return {
      confirmed: true,
      attributedOrders: countText(result.attributedOrders),
      paidOrders: countText(result.paidOrders),
      paidAmount: moneyText(result.paidAmount, result.amountCurrency),
      refundedOrders: countText(result.refundedOrders),
      refundedAmount: moneyText(result.refundedAmount, result.amountCurrency)
    }
  }
  return { confirmed: false, text: lookup(ATTRIBUTION_TEXT, result.status, '成交数据不可用') }
}

const EVIDENCE_TYPE = { 1: '人工核验录入', 2: '平台导出报表' }
export function evidenceTypeLabel(type) { return lookup(EVIDENCE_TYPE, type, '未知来源') }

const PLATFORM = { tiktok: 'TikTok', reels: 'Instagram Reels', youtube: 'YouTube' }
export function platformLabel(value) { return lookup(PLATFORM, value, value || '—') }

// ---------------------------------------------------------------- §18 主持小费

const TIP_STATUS = { 0: '已创建', 1: '待支付', 2: '已支付', 3: '已取消', 4: '已退款', 5: '收款未开通' }
export function tipStatusLabel(status) { return lookup(TIP_STATUS, status, '状态待确认') }
const FEE_BEARER = { 0: '主持承担', 1: '平台承担' }
export function feeBearerLabel(value) { return lookup(FEE_BEARER, value, '—') }

// ---------------------------------------------------------------- §20.1 落地译文审校

const TRANSLATION_STATE = { PENDING: '待审校', APPROVED: '已审校' }
export function translationStateLabel(state) { return lookup(TRANSLATION_STATE, state, '状态待确认') }

export function eligibilityLabel(value) {
  if (value === true || value === 1) return '接待'
  if (value === false || value === 0) return '不接待'
  return '未声明'
}

// ---------------------------------------------------------------- §20.2 渠道兑换码

const VERIFICATION_STATUS = { 0: '待验证', 1: '已验证', 2: '已拒绝', 3: '已撤销' }
export function verificationStatusLabel(status) { return lookup(VERIFICATION_STATUS, status, '状态待确认') }

const VOUCHER_STATUS = { 0: '可兑换', 1: '已兑换', 2: '已撤销', 3: '已过期' }
/** 撤销待渠道退款确认时不显示撤销终态。 */
export function voucherStatusLabel(voucher) {
  if (!voucher) return '状态待确认'
  if (voucher.revokePending === true) return '待渠道退款确认'
  return lookup(VOUCHER_STATUS, voucher.status, '状态待确认')
}

const SUBJECT_MODE = { 1: '绑定会员', 2: '不记名' }
export function subjectModeLabel(mode) { return lookup(SUBJECT_MODE, mode, '—') }

const ACCOUNT_STATUS = { 0: '停用', 1: '启用' }
export function accountStatusLabel(status) { return lookup(ACCOUNT_STATUS, status, '状态待确认') }

/** 明文码只在首次签发响应出现一次；重放/不可用时不提供取码。 */
export function issuedCode(voucher) {
  if (!voucher || voucher.replayed === true || voucher.codeAvailable === false) return null
  return typeof voucher.code === 'string' && voucher.code ? voucher.code : null
}

// ---------------------------------------------------------------- §27.4 复报

/** V2 旧口径：mature=false 时留存率显示未成熟；retainedMembers=null 显示 —。 */
export function v2RetentionText(report, rateKey) {
  if (!report || !report.cohort) return '—'
  if (report.mature !== true) return '未成熟'
  const text = rateText(report.cohort[rateKey])
  return text === null ? '—' : text
}

/** repeat_signup_v1：分母只看 matureMembers；零成熟样本的 null 显示未成熟/无可计算样本。 */
export function repeatSignupRateText(group) {
  if (!group) return '—'
  if (!isKnownNumber(group.matureMembers) || Number(group.matureMembers) === 0) return '未成熟/无可计算样本'
  const text = rateText(group.retentionRate)
  return text === null ? '—' : text
}

// ---------------------------------------------------------------- §19 营销分配

const ALLOCATION_STATUS = { 1: '已入账 · 待可结算', 2: '可结算', 3: '已打款', 4: '部分退款已冲回', 5: '已全额冲回' }
export function allocationStatusLabel(status) { return lookup(ALLOCATION_STATUS, status, '状态待确认') }

const LINE_ROLE = { author: '作者', referrer: '推荐人', platform: '平台' }
export function lineRoleLabel(role) { return lookup(LINE_ROLE, role, role || '—') }
export function lineEntryLabel(entryType) { return Number(entryType) === 2 ? '退款冲回' : '应计' }
