import request from '@/utils/request'

// R6c 工坊内购商品:模板发布版本 ↔ App Store 商品 ID、价格、币种
export function listModCommerceOffers() {
  return request({
    url: '/business/mod-commerce/offers',
    method: 'get'
  })
}

// 新建商品(默认下架)。data: { versionId, market, productId, priceMinor }
export function createModCommerceOffer(data) {
  return request({
    url: '/business/mod-commerce/offers',
    method: 'post',
    data: data
  })
}

// 上架或下架
export function setModCommerceOfferEnabled(offerId, enabled) {
  return request({
    url: '/business/mod-commerce/offers/' + encodeURIComponent(offerId) + '/enabled',
    method: 'put',
    data: { enabled }
  })
}
