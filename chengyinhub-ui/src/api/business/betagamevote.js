import request from '@/utils/request'

// 查询游戏调查投票列表
export function listBetagamevote(query) {
  return request({
    url: '/business/betagamevote/list',
    method: 'get',
    params: query
  })
}

// 查询游戏调查投票详细
export function getBetagamevote(id) {
  return request({
    url: '/business/betagamevote/' + id,
    method: 'get'
  })
}

// 新增游戏调查投票
export function addBetagamevote(data) {
  return request({
    url: '/business/betagamevote',
    method: 'post',
    data: data
  })
}

// 修改游戏调查投票
export function updateBetagamevote(data) {
  return request({
    url: '/business/betagamevote',
    method: 'put',
    data: data
  })
}

// 删除游戏调查投票
export function delBetagamevote(id) {
  return request({
    url: '/business/betagamevote/' + id,
    method: 'delete'
  })
}
