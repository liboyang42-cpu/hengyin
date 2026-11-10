import request from '@/utils/request'

// §19 营销分配只读：不调用推进、应计、冲销等写端点。
const raw = { rawBusinessError: true }

export function getAllocationByOrder(orderType, orderId) {
  return request({ url: '/business/revenue/allocation/' + orderType + '/' + orderId, method: 'get', ...raw })
}
export function listAllocationsByAuthor(authorMemberId) {
  return request({ url: '/business/revenue/allocation/author/' + authorMemberId, method: 'get', ...raw })
}
export function listPostingTasks(orderId) {
  return request({ url: '/business/revenue/allocation/' + orderId + '/tasks', method: 'get', ...raw })
}
