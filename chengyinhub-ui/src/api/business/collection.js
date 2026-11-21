import request from '@/utils/request'

// S2 收款模式:是否生效、各类卖家的收款方式、收付通开关
export function getCollectionOverview() {
  return request({ url: '/business/collection/overview', method: 'get' })
}

// 每个商家/俱乐部的收款状态(子商户号是否开通)
export function listCollectionSellers(params) {
  return request({ url: '/business/collection/sellers', method: 'get', params })
}

// 分账台账:每笔分账收款订单、平台应得/已得/已回退、已退款
export function listCollectionLedger(params) {
  return request({ url: '/business/collection/ledger', method: 'get', params })
}

// 失败队列:转人工的分账单、失败的分账回退
export function listCollectionFailures(limit) {
  return request({ url: '/business/collection/failures', method: 'get', params: { limit: limit || 50 } })
}

// 因卖家没有收款账户而拒绝的购买
export function listCollectionRefusals(limit) {
  return request({ url: '/business/collection/refusals', method: 'get', params: { limit: limit || 50 } })
}

// 重新计划一笔转人工的分账单(平台应得按下单时冻结的费率重算)
export function replanCollectionOrder(registrationNo) {
  return request({ url: '/business/collection/orders/' + encodeURIComponent(registrationNo) + '/replan', method: 'post' })
}

// 重试一笔失败的分账回退(退款在等它)
export function retryCollectionReturn(id) {
  return request({ url: '/business/collection/returns/' + id + '/retry', method: 'post' })
}

// 立即计划到期的分账单(定时任务每次也会做)
export function planDueCollection() {
  return request({ url: '/business/collection/plan-due', method: 'post' })
}
