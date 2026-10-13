import request from '@/utils/request'

// 查询游戏列表
export function listBetagame(query) {
  return request({
    url: '/business/betagame/list',
    method: 'get',
    params: query
  })
}

// 查询游戏详细
export function getBetagame(id) {
  return request({
    url: '/business/betagame/' + id,
    method: 'get'
  })
}

// 新增游戏
export function addBetagame(data) {
  return request({
    url: '/business/betagame',
    method: 'post',
    data: data
  })
}

// 修改游戏
export function updateBetagame(data) {
  return request({
    url: '/business/betagame',
    method: 'put',
    data: data
  })
}

// 删除游戏
export function delBetagame(id) {
  return request({
    url: '/business/betagame/' + id,
    method: 'delete'
  })
}

// 逻辑删除游戏（更新删除状态）
export function updateDelStatus(ids) {
  return request({
    url: '/business/betagame/update/delstatus',
    method: 'post',
    data: ids // 注意：这里使用 data 而不是 params
  })
}
