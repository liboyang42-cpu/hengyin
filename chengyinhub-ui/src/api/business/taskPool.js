import request from '@/utils/request'

// 查询盲盒任务池列表
export function listTaskPools(query) {
  return request({
    url: '/business/node-task-pool/list',
    method: 'get',
    params: query
  })
}

// 查询任务池详情与条目
export function getTaskPool(id) {
  return request({
    url: '/business/node-task-pool/' + id,
    method: 'get'
  })
}

// 新增任务池
export function addTaskPool(data) {
  return request({
    url: '/business/node-task-pool',
    method: 'post',
    data: data
  })
}

// 修改任务池
export function updateTaskPool(data) {
  return request({
    url: '/business/node-task-pool',
    method: 'put',
    data: data
  })
}

// 删除任务池
export function delTaskPool(id) {
  return request({
    url: '/business/node-task-pool/' + id,
    method: 'delete'
  })
}

// 查询任务池条目
export function listTaskPoolItems(poolId) {
  return request({
    url: '/business/node-task-pool/' + poolId + '/items',
    method: 'get'
  })
}

// 保存任务池条目
export function saveTaskPoolItem(data) {
  return request({
    url: '/business/node-task-pool/items',
    method: 'post',
    data: data
  })
}

// 删除任务池条目
export function delTaskPoolItem(id) {
  return request({
    url: '/business/node-task-pool/items/' + id,
    method: 'delete'
  })
}

// 查询每日下发记录
export function listTaskDispatches(query) {
  return request({
    url: '/business/node-task-pool/dispatch/list',
    method: 'get',
    params: query
  })
}
