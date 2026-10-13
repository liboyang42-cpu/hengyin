import request from '@/utils/request'

// 查询模板库列表
export function listTemplatelibrary(query) {
  return request({
    url: '/business/templatelibrary/list',
    method: 'get',
    params: query
  })
}

// 查询模板库详细
export function getTemplatelibrary(id) {
  return request({
    url: '/business/templatelibrary/' + id,
    method: 'get'
  })
}

// 新增模板库
export function addTemplatelibrary(data) {
  return request({
    url: '/business/templatelibrary',
    method: 'post',
    data: data
  })
}

// 修改模板库
export function updateTemplatelibrary(data) {
  return request({
    url: '/business/templatelibrary',
    method: 'put',
    data: data
  })
}

// 删除模板库
export function delTemplatelibrary(id) {
  return request({
    url: '/business/templatelibrary/' + id,
    method: 'delete'
  })
}
