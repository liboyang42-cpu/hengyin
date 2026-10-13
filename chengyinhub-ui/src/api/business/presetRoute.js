import request from '@/utils/request'

// 查询建议版城市路线列表
export function listPresetRoute(query) {
  return request({
    url: '/business/presetRoute/list',
    method: 'get',
    params: query
  })
}

// 查询建议版城市路线详细(含站点)
export function getPresetRoute(id) {
  return request({
    url: '/business/presetRoute/' + id,
    method: 'get'
  })
}

// 新增建议版城市路线
export function addPresetRoute(data) {
  return request({
    url: '/business/presetRoute',
    method: 'post',
    data: data
  })
}

// 修改建议版城市路线
export function updatePresetRoute(data) {
  return request({
    url: '/business/presetRoute',
    method: 'put',
    data: data
  })
}

// 删除建议版城市路线
export function delPresetRoute(id) {
  return request({
    url: '/business/presetRoute/' + id,
    method: 'delete'
  })
}
