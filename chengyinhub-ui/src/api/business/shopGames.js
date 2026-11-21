import request from '@/utils/request'

// S5 店铺小游戏:各类店铺的小游戏池、单店设置(店铺类型、指定小游戏、锁定)与小游戏上下架。
// 每次修改都要填写原因并留下记录。

export function getShopGameCatalog() {
  return request({ url: '/business/shop-games/catalog', method: 'get' })
}

export function listShopGameProfiles(query) {
  return request({ url: '/business/shop-games/profiles', method: 'get', params: query })
}

export function updateShopGameProfile(merchantId, data) {
  return request({ url: '/business/shop-games/profiles/' + merchantId, method: 'put', data })
}

export function setShopGameEnabled(key, data) {
  return request({ url: '/business/shop-games/games/' + key, method: 'put', data })
}
