import request from '@/utils/request'

// 查询优惠券列表
export function listCoupon(query) {
  return request({
    url: '/business/coupon/list',
    method: 'get',
    params: query
  })
}

// 查询优惠券详细
export function getCoupon(id) {
  return request({
    url: '/business/coupon/' + id,
    method: 'get'
  })
}

export function getCouponInfo(id) {
  return getCoupon(id)
}

// 新增优惠券
export function addCoupon(data) {
  return request({
    url: '/business/coupon',
    method: 'post',
    data: data
  })
}

// 修改优惠券
export function updateCoupon(data) {
  return request({
    url: '/business/coupon',
    method: 'put',
    data: data
  })
}

// 删除优惠券
export function delCoupon(id) {
  return request({
    url: '/business/coupon/' + id,
    method: 'delete'
  })
}

// F0572 规则变更影响预览(只读):新领取 vs 已领取、字段差异、版本冲突
export function previewCouponRule(data) {
  return request({
    url: '/business/coupon/rule-impact',
    method: 'post',
    data: data
  })
}

// F0572 停发:只拦新领取,已领取的券不变
export function stopIssueCoupon(id, data) {
  return request({
    url: '/business/coupon/' + id + '/stop-issue',
    method: 'post',
    data: data
  })
}

// F0572 平台失效:独立原因必填,回包含受影响持券人
export function invalidateCoupon(id, data) {
  return request({
    url: '/business/coupon/' + id + '/invalidate',
    method: 'post',
    data: data
  })
}

export function listCouponRuleVersions(id) {
  return request({
    url: '/business/coupon/' + id + '/rule-versions',
    method: 'get'
  })
}

export function getCouponAffectedHolders(id) {
  return request({
    url: '/business/coupon/' + id + '/affected-holders',
    method: 'get'
  })
}
