import request from '@/utils/request'

// R1 城市集市:地图优惠开启(逐张)、推广曝光位待处理订单(只读)、未入驻门店销售线索(只读)

export function listMarketplaceDeals() {
  return request({ url: '/business/marketplace/deals', method: 'get' })
}

export function setMarketplaceDeal(couponId, enabled) {
  return request({ url: '/business/marketplace/deals/' + couponId, method: 'put', params: { enabled } })
}

export function removeMarketplaceDeal(couponId) {
  return request({ url: '/business/marketplace/deals/' + couponId, method: 'delete' })
}

export function listPromotionSlotPurchases() {
  return request({ url: '/business/marketplace/promotion-slot/purchases', method: 'get' })
}

export function listMarketplaceLeads(query) {
  return request({ url: '/business/marketplace/leads', method: 'get', params: query })
}
