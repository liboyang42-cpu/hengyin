import request from '@/utils/request'

// S3a 商家套餐与抽成:平台抽成按产品类型设置、商家套餐、套餐订单与开通、到店计费。
// 收费类操作(开通付费套餐、确认收款、到店计费入账与月结)在资金开关「商家收款」关闭时由后端拒绝。

export function listTakeRates() {
  return request({ url: '/business/merchant-plans/take-rates', method: 'get' })
}

export function setTakeRate(data) {
  return request({ url: '/business/merchant-plans/take-rates', method: 'put', data })
}

export function getMerchantPlans() {
  return request({ url: '/business/merchant-plans', method: 'get' })
}

export function updateMerchantPlan(code, data) {
  return request({ url: '/business/merchant-plans/' + code, method: 'put', data })
}

export function listPlanOrders(query) {
  return request({ url: '/business/merchant-plans/orders', method: 'get', params: query })
}

export function listPlanSubscriptions(query) {
  return request({ url: '/business/merchant-plans/subscriptions', method: 'get', params: query })
}

export function confirmPlanOrderPaid(orderNo, data) {
  return request({ url: '/business/merchant-plans/orders/' + orderNo + '/confirm-paid', method: 'post', data })
}

export function grantMerchantPlan(data) {
  return request({ url: '/business/merchant-plans/grant', method: 'post', data })
}

export function listVisitCharges(query) {
  return request({ url: '/business/merchant-plans/visit-charges', method: 'get', params: query })
}

export function listVisitInvoices(query) {
  return request({ url: '/business/merchant-plans/visit-invoices', method: 'get', params: query })
}

export function accrueVisitCharges(day) {
  return request({ url: '/business/merchant-plans/visit-charges/accrue', method: 'post', params: { day } })
}

export function issueVisitInvoices(data) {
  return request({ url: '/business/merchant-plans/visit-invoices/issue', method: 'post', data })
}
