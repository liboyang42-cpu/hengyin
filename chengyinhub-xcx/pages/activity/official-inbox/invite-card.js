'use strict'

/* BL-06 协办/履约邀请卡片的展示与回读判定。
 * 只用 GET /api/official/v2/party-inbox 已有字段(selectPartyInboxForMember):
 * role / capacity / controlState / status。活动开始时间接口里没有,这里只展示服务时段,不编造。
 */

// 角色说明只复述角色名本身的含义,不承诺奖励、结算等政策。
const ROLE_NOTE = {
  ORGANIZER: '对整场活动负责，发布、暂停和恢复由主办操作。',
  CO_ORGANIZER: '与主办方共同承办本场活动，按下方职责范围履约。',
  FULFILLMENT_MERCHANT: '以商家身份在指定范围和时段内提供履约服务。',
  FULFILLMENT_CLUB: '以俱乐部身份在指定范围和时段内组织或协助履约。',
  SPONSOR: '作为支持方为本场活动提供支持。',
  PROMOTION_PARTNER: '作为推广伙伴协助本场活动的传播。',
}

function roleNote(role) {
  return ROLE_NOTE[role] || ''
}

function capacityText(capacity) {
  if (capacity === null || capacity === undefined || capacity === '') return ''
  const n = Number(capacity)
  return Number.isFinite(n) && n > 0 ? '承接名额 ' + Math.trunc(n) : ''
}

function pausedText(controlState) {
  return controlState === 'PAUSED' ? '本场活动已暂停，恢复前以主办通知为准。' : ''
}

/* 动作提交成功后的回读:接受 ⇒ 这一行应变为 ACCEPTED/ACTIVE;拒绝/退出 ⇒ 这一行应从收件箱消失
 * (列表只查 INVITED/ACCEPTED/ACTIVE)。与预期不符返回 true,由页面如实提示「尚未同步」。 */
function readbackMismatch(invites, expect) {
  if (!expect || expect.partyId == null) return false
  const row = (Array.isArray(invites) ? invites : []).find((item) => item && String(item.partyId) === String(expect.partyId))
  if (expect.action === 'accept') return !row || (row.status !== 'ACCEPTED' && row.status !== 'ACTIVE')
  return !!row
}

module.exports = { ROLE_NOTE, roleNote, capacityText, pausedText, readbackMismatch }
