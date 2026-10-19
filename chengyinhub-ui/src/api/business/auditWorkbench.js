import request from '@/utils/request'

export function listAuditTasks(query, auditContext) {
  return request({
    auditContext,
    url: '/business/audit/list',
    method: 'get',
    params: query
  })
}

export function getAuditTask(id, auditContext) {
  return request({
    auditContext,
    url: '/business/audit/' + id,
    method: 'get'
  })
}

export function getAuditTaskDetail(id, auditContext) {
  return request({
    auditContext,
    url: '/business/audit/' + id + '/detail',
    method: 'get'
  })
}

// Read-only frozen-candidate projection. Local context never enters the request binding.
export function getCandidatePreview(id, binding, auditContext) {
  return request({
    auditContext,
    url: '/business/audit/' + id + '/candidate-preview',
    method: 'get',
    preserveBusinessError: true,
    params: { expectedTaskVersion: binding.expectedTaskVersion, snapshotId: binding.snapshotId,
      sourceConfigVersion: binding.sourceConfigVersion, contentHash: binding.contentHash }
  })
}

export function reviewAuditTask(data, auditContext) {
  return request({
    auditContext,
    url: '/business/audit/review',
    method: 'post',
    // 仅此审核入口需要区分冲突、参数错误与未知结果，以保留精确重试语义。
    preserveBusinessError: true,
    data: data
  })
}

export function submitTopicProjectReview(topicId, data, auditContext) {
  return request({
    auditContext,
    url: '/business/audit/topic/' + topicId + '/project-submit',
    method: 'post',
    data: data
  })
}

export function submitTopicSaleReview(topicId, auditContext) {
  return request({
    auditContext,
    url: '/business/audit/topic/' + topicId + '/sale-submit',
    method: 'post'
  })
}

export function reviewMediaAsset(data) {
  return request({ url: data.status === 1 ? '/business/mediaReview/approve' : '/business/mediaReview/reject', method: 'post', data })
}
