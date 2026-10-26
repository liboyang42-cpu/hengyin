import request from '@/utils/request'

// Quote unsafe integer tokens before JSON.parse; quoted evidence text stays untouched.
export function parseClaimResponse(raw) {
  if (typeof raw !== 'string') return raw
  return JSON.parse(raw.replace(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g, token => {
    return /^-?\d+$/.test(token) && !Number.isSafeInteger(Number(token)) ? `"${token}"` : token
  }))
}

export function claimId(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new Error('认领编号精度已丢失，请重新读取')
  const id = String(value)
  if (!/^[1-9]\d{0,18}$/.test(id) || (id.length === 19 && id > '9223372036854775807')) {
    throw new Error('认领编号无效，请重新读取')
  }
  return id
}

export function parseAuditResponse(raw) {
  const response = parseClaimResponse(raw)
  if (response && [400, 404, 409].includes(response.code)) {
    const error = new Error(response.msg || '审核请求被服务端拒绝')
    error.confirmedRejected = true
    throw error
  }
  return response
}

export function pendingMerchantClaims() {
  return request({
    url: '/business/merchant-claim/pending', method: 'get', params: { limit: 100 },
    transformResponse: [parseClaimResponse]
  })
}

export function auditMerchantClaim(data) {
  return request({
    url: '/business/merchant-claim/audit', method: 'post', data,
    transformResponse: [parseAuditResponse]
  })
}

export function auditMerchantClaimBatch(data) {
  return request({
    url: '/business/merchant-claim/audit-batch', method: 'post', data,
    transformResponse: [parseAuditResponse]
  })
}
