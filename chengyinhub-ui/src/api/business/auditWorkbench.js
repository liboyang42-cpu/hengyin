import request from '@/utils/request'

export function listAuditTasks(query) {
  return request({
    url: '/business/audit/list',
    method: 'get',
    params: query
  })
}

export function getAuditTask(id) {
  return request({
    url: '/business/audit/' + id,
    method: 'get'
  })
}

export function getAuditTaskDetail(id, requestId) {
  return request({
    url: '/business/audit/' + id + '/detail',
    method: 'get',
    params: requestId ? { requestId } : undefined
  })
}

// Preserve the review error before the shared interceptor reduces non-200 bodies to 'error'.
export function parseAuditReviewResponse(raw) {
  const body = typeof raw === 'string' ? JSON.parse(raw) : raw
  if (body && Number(body.code) !== 200) {
    const error = new Error(body.msg || '审核处理未确认')
    error.auditCode = Number(body.code)
    error.auditErrorCode = body.errorCode
    throw error
  }
  return body
}

export function reviewAuditTask(data) {
  return request({
    url: '/business/audit/review',
    transformResponse: [parseAuditReviewResponse],
    method: 'post',
    data: data
  })
}

export function submitTopicProjectReview(topicId, data) {
  return request({
    url: '/business/audit/topic/' + topicId + '/project-submit',
    method: 'post',
    data: data
  })
}

export function submitTopicSaleReview(topicId) {
  return request({
    url: '/business/audit/topic/' + topicId + '/sale-submit',
    method: 'post'
  })
}

export function reviewMediaAsset(data) {
  return request({ url: data.status === 1 ? '/business/mediaReview/approve' : '/business/mediaReview/reject', method: 'post', data })
}
