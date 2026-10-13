import request from '@/utils/request'

// 查询优惠券领取使用记录列表
export function listCouponhistory(query) {
  return request({
    url: '/business/couponhistory/list',
    method: 'get',
    params: query
  })
}

// 查询优惠券领取使用记录详细
export function getCouponhistory(id) {
  return request({
    url: '/business/couponhistory/' + id,
    method: 'get'
  })
}

// 撤销券核销(平台后台唯一纠错入口;原因必填,落不可变审计)
export function reverseCouponRedemption(id, data) {
  return request({
    url: '/business/couponhistory/' + id + '/reverse',
    method: 'post',
    data: data
  })
}

// 某领取记录的不可变审计流水(核销 + 撤销,含操作者与原因)
export function getCouponAuditTrail(id) {
  return request({
    url: '/business/couponhistory/' + id + '/audit-trail',
    method: 'get'
  })
}

// 新增优惠券领取使用记录
export function addCouponhistory(data) {
  return request({
    url: '/business/couponhistory',
    method: 'post',
    data: data
  })
}

// 修改优惠券领取使用记录
export function updateCouponhistory(data) {
  return request({
    url: '/business/couponhistory',
    method: 'put',
    data: data
  })
}

// 删除优惠券领取使用记录
export function delCouponhistory(id) {
  return request({
    url: '/business/couponhistory/' + id,
    method: 'delete'
  })
}
