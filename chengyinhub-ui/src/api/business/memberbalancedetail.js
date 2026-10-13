import request from '@/utils/request'

// 查询用户余额明细列表
export function listMemberbalancedetail(query) {
  return request({
    url: '/business/memberbalancedetail/list',
    method: 'get',
    params: query
  })
}

// 查询用户余额明细详细
export function getMemberbalancedetail(id) {
  return request({
    url: '/business/memberbalancedetail/' + id,
    method: 'get'
  })
}

// 新增用户余额明细
export function addMemberbalancedetail(data) {
  return request({
    url: '/business/memberbalancedetail',
    method: 'post',
    data: data
  })
}

// 修改用户余额明细
export function updateMemberbalancedetail(data) {
  return request({
    url: '/business/memberbalancedetail',
    method: 'put',
    data: data
  })
}

// 删除用户余额明细
export function delMemberbalancedetail(id) {
  return request({
    url: '/business/memberbalancedetail/' + id,
    method: 'delete'
  })
}
