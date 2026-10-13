import request from '@/utils/request'

// 查询配额列表
export function listPermission() {
  return request({
    url: '/business/userpermission/list',
    method: 'get'
  })
}

// 修改配额
export function updatePermission(data) {
  return request({
    url: '/business/userpermission',
    method: 'put',
    data: data
  })
}
