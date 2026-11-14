// 共享审核状态机（ReviewStateMachine）在后台的显示规则：外部游戏与工坊专业化共用。
import { businessError } from '@/utils/questo-admin'

export const REVIEW_STATUS = {
  PENDING: { label: '待审', tag: 'warning' },
  ESCALATED: { label: '已升级', tag: 'danger' },
  APPROVED: { label: '已通过', tag: 'success' },
  REJECTED: { label: '已驳回', tag: 'info' },
  CANCELLED: { label: '已取消', tag: 'info' }
}

export function reviewStatusLabel(status) {
  return (REVIEW_STATUS[status] && REVIEW_STATUS[status].label) || status || '—'
}

export function reviewStatusTag(status) {
  return (REVIEW_STATUS[status] && REVIEW_STATUS[status].tag) || 'info'
}

/** 只有 PENDING / ESCALATED 可以通过或驳回；只有 PENDING 可以升级。 */
export function canDecide(status) { return status === 'PENDING' || status === 'ESCALATED' }
export function canEscalate(status) { return status === 'PENDING' }

const ACTOR = { AUTHOR: '作者', ADMIN: '审核员', SYSTEM: '系统' }
export function actorLabel(type) { return ACTOR[type] || type || '—' }

const REASON = { REVIEW_TIMEOUT: '审核超时自动升级', SUBMITTED: '提交', CANCELLED_BY_AUTHOR: '作者取消', CANCELLED_BEFORE_SUBMIT: '提交前取消' }
export function eventReasonText(reason) { return REASON[reason] || reason || '' }

export const REASON_MAX = 500

/** 这些路由在拒绝时返回 HTTP 状态 == code，并带 errorCode；request 以原始包体 reject。 */
export function unwrap(res) {
  if (res && typeof res === 'object' && Number(res.code) === 200) return res.data
  throw res
}

export function reviewErrorText(err) {
  const e = businessError(err)
  const code = Number(e.code)
  const errorCode = err && typeof err === 'object' ? err.errorCode : null
  if (code === 403) return '当前角色没有审核权限'
  if (code === 404) return '记录不存在或已被删除'
  if (code === 409 || errorCode === 'INVALID_TRANSITION') return '状态已变化，请刷新后再处理'
  if (code === 400 && errorCode) return `${e.message}（${errorCode}）`
  return e.message
}

export function timeText(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export function bytesText(n) {
  if (n === null || n === undefined || n === '') return '—'
  const v = Number(n)
  if (!Number.isFinite(v)) return '—'
  if (v < 1024) return `${v} B`
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`
  return `${(v / 1024 / 1024).toFixed(2)} MB`
}
