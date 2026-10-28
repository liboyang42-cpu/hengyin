import request from '@/utils/request'

export function listRefund(query) {
  return request({
    url: '/business/refund/list',
    method: 'get',
    params: query
  })
}

export function addRefund(data) {
  return request({
    url: '/business/refund',
    method: 'post',
    data
  })
}

export function reviewRefund(data) {
  return request({
    url: '/business/refund/review',
    method: 'put',
    data
  })
}

// §6.1 退款失败人工兜底
export function listManualPayout(query) {
  return request({
    url: '/business/refund/manual/list',
    method: 'get',
    params: query
  })
}

// 处理前必须先领取 owner+lease fence:后端 /manual/handle 用 leaseToken 做 CAS,漏领必然失败。
export function claimManualPayout(data) {
  return request({
    url: '/business/refund/manual/claim',
    method: 'post',
    data
  })
}

// 放弃处理权:owner+token 双 fence,过期也能释放 —— 不释放这单会一直挂在别人领不到的租约里。
export function releaseManualPayout(data) {
  return request({
    url: '/business/refund/manual/release',
    method: 'post',
    data
  })
}

// 接管他人已过期的处理权:同样先由服务端问微信终态,再签发新 token,并把原处理人写进备注留痕。
export function takeoverManualPayout(data) {
  return request({
    url: '/business/refund/manual/takeover',
    method: 'post',
    data
  })
}

export function handleManualPayout(data) {
  return request({
    url: '/business/refund/manual/handle',
    method: 'post',
    data
  })
}

export function verifyManualPayout(data) {
  return request({
    url: '/business/refund/manual/verify',
    method: 'post',
    data
  })
}

export function refreshPayout(refundNo) {
  return request({
    url: '/business/refund/manual/refresh',
    method: 'get',
    params: { refundNo }
  })
}

// 活动取消时“部分已核销父订单”的独立人工退款责任队列。
// 它不复用 registration 退款申请，避免把整单现金误绑到任一子票并造成超退。
export function listActivityCancelRefundCases(query) {
  return request({
    url: '/business/refund/activity-cancel-cases/list',
    method: 'get',
    params: query
  })
}

export function getActivityCancelRefundCase(caseId) {
  return request({
    url: '/business/refund/activity-cancel-cases/' + caseId,
    method: 'get'
  })
}

export function assessActivityCancelRefundCase(data) {
  return request({
    url: '/business/refund/activity-cancel-cases/assess',
    method: 'post',
    data
  })
}

export function handleActivityCancelRefundCase(data) {
  return request({
    url: '/business/refund/activity-cancel-cases/handle',
    method: 'post',
    data
  })
}

export function uploadActivityCancelRefundEvidence(file, context) {
  const data = new FormData()
  data.append('file', file)
  data.append('caseId', context.caseId)
  data.append('expectedVersion', context.expectedVersion)
  data.append('requestId', context.requestId)
  return request({
    url: '/business/refund/activity-cancel-cases/evidence/upload',
    method: 'post',
    data,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function verifyActivityCancelRefundCase(data) {
  return request({
    url: '/business/refund/activity-cancel-cases/verify',
    method: 'post',
    data
  })
}

export function reworkActivityCancelRefundCase(data) {
  return request({
    url: '/business/refund/activity-cancel-cases/rework',
    method: 'post',
    data
  })
}

export function reopenActivityCancelRefundAssessment(data) {
  return request({
    url: '/business/refund/activity-cancel-cases/reopen-assessment',
    method: 'post',
    data
  })
}
