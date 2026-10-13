import request from '@/utils/request'

// 查询主题/活动票务列表
export function listTicket(query) {
  return request({
    url: '/business/ticket/list',
    method: 'get',
    params: query
  })
}

// 查询主题/活动票务详细
export function getTicket(id) {
  return request({
    url: '/business/ticket/' + id,
    method: 'get'
  })
}

// 新增主题/活动票务
export function addTicket(data) {
  return request({
    url: '/business/ticket',
    method: 'post',
    data: data
  })
}

// 修改主题/活动票务
export function updateTicket(data) {
  return request({
    url: '/business/ticket',
    method: 'put',
    data: data
  })
}

// 删除主题/活动票务
export function delTicket(id) {
  return request({
    url: '/business/ticket/' + id,
    method: 'delete'
  })
}
