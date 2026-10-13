import request from '@/utils/request'

// 查询参数设置列表
export function listSetting(query) {
  return request({
    url: '/business/setting/list',
    method: 'get',
    params: query
  })
}

// 查询参数设置详细
export function getSetting(id) {
  return request({
    url: '/business/setting/' + id,
    method: 'get'
  })
}

// 新增参数设置
export function addSetting(data) {
  return request({
    url: '/business/setting',
    method: 'post',
    data: data
  })
}

// 修改参数设置
export function updateSetting(data) {
  return request({
    url: '/business/setting',
    method: 'put',
    data: data
  })
}

// 删除参数设置
export function delSetting(id) {
  return request({
    url: '/business/setting/' + id,
    method: 'delete'
  })
}
