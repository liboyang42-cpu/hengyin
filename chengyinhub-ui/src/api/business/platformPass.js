import request from '@/utils/request'

// 通行证订单与退款复核（后端权限点 business:platformPass:refund，沿用既有，不新增）
export function listPassOrders(params) {
  return request({ url: '/business/platformPass/orders', method: 'get', params })
}

export function reviewPassRefund(orderNo, params) {
  return request({ url: `/business/platformPass/orders/${encodeURIComponent(orderNo)}/review`, method: 'post', params })
}

export function refreshPassRefund(orderNo) {
  return request({ url: `/business/platformPass/orders/${encodeURIComponent(orderNo)}/refund/refresh`, method: 'post' })
}
