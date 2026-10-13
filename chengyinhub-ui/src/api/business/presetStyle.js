import request from '@/utils/request'

// 查询建议版主题风格列表
export function listPresetStyle(query) {
  return request({
    url: '/business/presetStyle/list',
    method: 'get',
    params: query
  })
}

// 查询建议版主题风格详细
export function getPresetStyle(id) {
  return request({
    url: '/business/presetStyle/' + id,
    method: 'get'
  })
}

// 新增建议版主题风格
export function addPresetStyle(data) {
  return request({
    url: '/business/presetStyle',
    method: 'post',
    data: data
  })
}

// 修改建议版主题风格
export function updatePresetStyle(data) {
  return request({
    url: '/business/presetStyle',
    method: 'put',
    data: data
  })
}

// 删除建议版主题风格
export function delPresetStyle(id) {
  return request({
    url: '/business/presetStyle/' + id,
    method: 'delete'
  })
}
