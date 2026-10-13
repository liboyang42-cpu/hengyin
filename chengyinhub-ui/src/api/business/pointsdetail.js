import request from '@/utils/request'

// 查询用户积分明细列表
export function listDetail(query) {
  return request({
    url: '/business/pointsdetail/list',
    method: 'get',
    params: query
  })
}

// 查询用户积分明细详细
export function getDetail(id) {
  return request({
    url: '/business/pointsdetail/' + id,
    method: 'get'
  })
}

// 新增用户积分明细
export function addDetail(data) {
  return request({
    url: '/business/pointsdetail',
    method: 'post',
    data: data
  })
}

// 修改用户积分明细
export function updateDetail(data) {
  return request({
    url: '/business/pointsdetail',
    method: 'put',
    data: data
  })
}

// 删除用户积分明细
export function delDetail(id) {
  return request({
    url: '/business/pointsdetail/' + id,
    method: 'delete'
  })
}
