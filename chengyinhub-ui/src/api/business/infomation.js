import request from '@/utils/request'

// 查询玩法说明列表
export function listInfomation(query) {
  return request({
    url: '/business/infomation/list',
    method: 'get',
    params: query
  })
}

// 查询玩法说明详细
export function getInfomation(id) {
  return request({
    url: '/business/infomation/' + id,
    method: 'get'
  })
}

// 新增玩法说明
export function addInfomation(data) {
  return request({
    url: '/business/infomation',
    method: 'post',
    data: data
  })
}

// 修改玩法说明
export function updateInfomation(data) {
  return request({
    url: '/business/infomation',
    method: 'put',
    data: data
  })
}
