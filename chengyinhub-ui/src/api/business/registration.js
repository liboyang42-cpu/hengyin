import request from '@/utils/request'

// 查询活动/主题报名列表
export function listRegistration(query) {
  return request({
    url: '/business/registration/list',
    method: 'get',
    params: query
  })
}

// 查询活动/主题报名详细
export function getRegistration(id) {
  return request({
    url: '/business/registration/' + id,
    method: 'get'
  })
}

// 修改活动/主题报名
export function updateRegistration(data) {
  return request({
    url: '/business/registration',
    method: 'put',
    data: data
  })
}

// 删除活动/主题报名
export function delRegistration(id) {
  return request({
    url: '/business/registration/' + id,
    method: 'delete'
  })
}

// 某票的核销流水(含已撤销)—— 撤销入口的读回列表
export function listRegistrationRedemptions(id) {
  return request({
    url: '/business/registration/' + id + '/redemptions',
    method: 'get'
  })
}

// 撤销核销(平台后台;原因必填,留痕在服务端)
export function reverseRedemption(data) {
  return request({
    url: '/business/registration/redemption/reverse',
    method: 'post',
    data: data
  })
}

// 原父订单按章批量撤销；服务端仅按真实父单查找流水且整批事务回滚。
export function reverseParentOrderRedemptions(data) {
  return request({ url: '/business/registration/redemption/reverse-parent', method: 'post', data })
}

// 撤销活动票整单核销(无按章流水);原因必填,服务端锁单校验并留痕。
export function reverseWholeOrderVerification(data) {
  return request({ url: '/business/registration/verification/reverse', method: 'post', data })
}
