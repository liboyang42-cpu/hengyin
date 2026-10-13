import request from '@/utils/request'

export function listWorlds(query) {
  return request({
    url: '/business/world/list',
    method: 'get',
    params: query
  })
}

export function saveWorld(data) {
  return request({
    url: '/business/world',
    method: 'post',
    data
  })
}
