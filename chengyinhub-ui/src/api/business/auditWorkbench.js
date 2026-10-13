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

export function getAuditTaskDetail(id) {
  return request({
    url: '/business/audit/' + id + '/detail',
    method: 'get'
  })
}

export function reviewAuditTask(data) {
  return request({
    url: '/business/audit/review',
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
