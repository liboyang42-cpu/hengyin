/**
 * utils/merchant-claim.js —— 「认领已有门店」纯逻辑(提交体 / 校验 / 状态四态)
 *
 * 后端:POST /api/merchant/claims/submit (MerchantClaimDTO.SubmitRequest)
 *       GET  /api/merchant/claims/status (本人全部认领,status: PENDING | APPROVED | REJECTED,驳回原因 auditReason)
 * 四态:none(可提交) / pending 审核中 / approved 已通过 / rejected 已驳回(附原因,可重新提交)。
 */
const MAX_PROOFS = 10
const PROOF_TYPE = 'PHOTO'

function newRequestId(now, rand) {
  const t = Number.isFinite(now) ? now : Date.now()
  const r = Math.floor((typeof rand === 'number' ? rand : Math.random()) * 1e9)
  return 'mc-' + t.toString(36) + '-' + r.toString(36)
}

function text(v) { return typeof v === 'string' ? v.trim() : '' }

/** @returns {string} 空串表示通过,否则是给用户看的第一条问题 */
function validate(form) {
  const f = form || {}
  if (!(Number(f.merchantId) > 0)) return '请先选择要认领的门店'
  if (!text(f.contactName)) return '请填写联系人'
  if (text(f.contactName).length > 64) return '联系人不超过 64 字'
  if (!/^[0-9+\-\s]{6,32}$/.test(text(f.contactPhone))) return '请填写正确的联系电话'
  if (text(f.statement).length > 1000) return '补充说明不超过 1000 字'
  const proofs = (f.proofUrls || []).filter((u) => text(u))
  if (!proofs.length) return '请至少上传 1 张营业执照或门店实拍'
  if (proofs.length > MAX_PROOFS) return '证明材料最多 ' + MAX_PROOFS + ' 张'
  if (proofs.some((u) => text(u).length > 255)) return '证明图片地址过长，请重新上传'
  if (!text(f.requestId)) return '请求标识缺失，请重试'
  return ''
}

function buildSubmitBody(form) {
  return {
    merchantId: Number(form.merchantId),
    requestId: text(form.requestId),
    contactName: text(form.contactName),
    contactPhone: text(form.contactPhone),
    statement: text(form.statement),
    proofs: (form.proofUrls || []).filter((u) => text(u)).map((u) => ({ proofType: PROOF_TYPE, assetId: text(u) })),
  }
}

function pickLatest(rows) {
  return rows.slice().sort((a, b) => (Number(b.id) || 0) - (Number(a.id) || 0))[0]
}

/**
 * 把 status 接口的列表折成页面状态。approved > pending > 最近一条 rejected。
 * @returns {{state:'none'|'pending'|'approved'|'rejected', title:string, desc:string, reason:string, merchantId:(number|null)}}
 */
function describe(rows) {
  const list = Array.isArray(rows) ? rows.filter((r) => r && typeof r === 'object') : []
  const approved = list.filter((r) => r.status === 'APPROVED')
  if (approved.length) {
    const c = pickLatest(approved)
    return { state: 'approved', title: '认领已通过', desc: '门店已归到你的账号下，重新进入商家中心即可管理。', reason: '', merchantId: c.merchantId || null }
  }
  const pending = list.filter((r) => r.status === 'PENDING')
  if (pending.length) {
    const c = pickLatest(pending)
    return { state: 'pending', title: '认领审核中', desc: '审核团队会用你填的联系电话与你确认，请保持电话畅通。', reason: '', merchantId: c.merchantId || null }
  }
  const rejected = list.filter((r) => r.status === 'REJECTED')
  if (rejected.length) {
    const c = pickLatest(rejected)
    return { state: 'rejected', title: '认领未通过', desc: '可以按原因补充材料后重新提交。', reason: text(c.auditReason) || '审核未通过，未填写具体原因', merchantId: c.merchantId || null }
  }
  return { state: 'none', title: '', desc: '', reason: '', merchantId: null }
}

const ERROR_TEXT = {
  400: '填写内容不完整，请检查后重试',
  401: '请先登录',
  404: '账号信息未找到，请重新登录',
}

function submitErrorMessage(res) {
  const msg = res && typeof res.msg === 'string' ? res.msg.trim() : ''
  if (msg) return msg
  return ERROR_TEXT[Number(res && res.code)] || '提交失败，请稍后重试'
}

module.exports = { MAX_PROOFS, PROOF_TYPE, newRequestId, validate, buildSubmitBody, describe, submitErrorMessage }
