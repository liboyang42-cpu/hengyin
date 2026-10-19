import request from '@/utils/request'

// F0535 订单支付事实：积分/现金分开；现金只以渠道查询回执为准
export function getOrderPaymentFacts(id) {
  return request({
    url: '/business/order/' + id + '/payment-facts',
    method: 'get'
  })
}

// F0535 向原支付渠道查询一次并落回执（只读渠道，不改订单与资金）
export function queryOrderPaymentFacts(id) {
  return request({
    url: '/business/order/' + id + '/payment-facts/query',
    method: 'post'
  })
}
