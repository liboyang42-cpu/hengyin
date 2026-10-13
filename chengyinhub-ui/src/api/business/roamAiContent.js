import request from '@/utils/request'

// 漫游无形陪伴内容(CmsRoamAiContentController)。后端不分页,list 直接回裸数组。
export function listRoamAiContent(params) {
  return request({
    url: '/business/roamAiContent/list',
    method: 'get',
    params: params
  })
}

export function getRoamAiContent(id) {
  return request({
    url: '/business/roamAiContent/' + id,
    method: 'get'
  })
}

export function addRoamAiContent(data) {
  return request({
    url: '/business/roamAiContent',
    method: 'post',
    data: data
  })
}

// 只送 contentText / enabled:类型、key、poiId、审核态由后端把守,前端不得借编辑体改写。
export function updateRoamAiContent(data) {
  return request({
    url: '/business/roamAiContent',
    method: 'put',
    data: data
  })
}

// 审核态的唯一写入口。
export function auditRoamAiContent(id, status) {
  return request({
    url: '/business/roamAiContent/audit/' + id + '/' + status,
    method: 'put'
  })
}

export function delRoamAiContent(id) {
  return request({
    url: '/business/roamAiContent/' + id,
    method: 'delete'
  })
}
