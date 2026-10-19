import request from '@/utils/request'

// F0575 平台兑换记录：三类来源三个端点，分开分页，不做同名合并

export function listCouponRedemptions(query) {
  return request({
    url: '/business/redemption-records/coupon',
    method: 'get',
    params: query
  })
}

export function listPointsMallRedemptions(query) {
  return request({
    url: '/business/redemption-records/points-order',
    method: 'get',
    params: query
  })
}

export function listGiftRedemptions(query) {
  return request({
    url: '/business/redemption-records/gift',
    method: 'get',
    params: query
  })
}
