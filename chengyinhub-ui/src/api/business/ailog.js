import request from '@/utils/request'

// AI 生成日志列表
export function listAiLog(query) {
  return request({
    url: '/business/ailog/list',
    method: 'get',
    params: query
  })
}

// AI 生成日志详情
export function getAiLog(id) {
  return request({
    url: '/business/ailog/' + id,
    method: 'get'
  })
}

// 按 traceId 下钻组题候选快照(可能为 null)
export function getAiLogSnapshot(traceId) {
  return request({
    url: '/business/ailog/snapshot/' + traceId,
    method: 'get'
  })
}
