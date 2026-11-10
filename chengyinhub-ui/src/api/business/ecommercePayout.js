import request from '@/utils/request'

const base = '/business/ecommerce-payout'

export function getSwitches() {
  return request({ url: `${base}/switches`, method: 'get' })
}

export function listSubMerchants(params) {
  return request({ url: `${base}/sub-merchants`, method: 'get', params })
}

export function registerSubMerchant(params) {
  return request({ url: `${base}/sub-merchant/register`, method: 'post', params })
}

export function refreshSubMerchant(id) {
  return request({ url: `${base}/sub-merchant/${id}/refresh`, method: 'post' })
}

export function syncOnboarding() {
  return request({ url: `${base}/sub-merchant/sync`, method: 'post' })
}

export function listShareOrders(params) {
  return request({ url: `${base}/profit-share/orders`, method: 'get', params })
}

export function listShareLines(id) {
  return request({ url: `${base}/profit-share/orders/${id}/lines`, method: 'get' })
}

export function replanShareOrder(id) {
  return request({ url: `${base}/profit-share/orders/${id}/replan`, method: 'post' })
}

export function submitDueShares() {
  return request({ url: `${base}/profit-share/submit`, method: 'post' })
}

export function syncShares() {
  return request({ url: `${base}/profit-share/sync`, method: 'post' })
}

export function listReturns(params) {
  return request({ url: `${base}/profit-share/returns`, method: 'get', params })
}

export function retryReturn(id) {
  return request({ url: `${base}/profit-share/returns/${id}/retry`, method: 'post' })
}

export function syncReturns() {
  return request({ url: `${base}/profit-share/returns/sync`, method: 'post' })
}

export function listEcomRefunds(params) {
  return request({ url: `${base}/refunds`, method: 'get', params })
}
