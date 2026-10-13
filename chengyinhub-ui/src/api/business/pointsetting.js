import request from '@/utils/request'

// 查询积分规则与分润设置列表
export function listSetting(query) {
  return request({
    url: '/business/pointsetting/list',
    method: 'get',
    params: query
  })
}

// 查询积分规则与分润设置详细
export function getSetting(id) {
  return request({
    url: '/business/pointsetting/' + id,
    method: 'get'
  })
}

// 新增积分规则与分润设置
export function addSetting(data) {
  return request({
    url: '/business/pointsetting',
    method: 'post',
    data: data
  })
}

// 修改积分规则与分润设置
export function updateSetting(data) {
  return request({
    url: '/business/pointsetting',
    method: 'put',
    data: data
  })
}

// 删除积分规则与分润设置
export function delSetting(id) {
  return request({
    url: '/business/pointsetting/' + id,
    method: 'delete'
  })
}
