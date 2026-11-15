import request from '@/utils/request'

// R9 BE-13：拓展人激活赏金审核队列。审核通过会入账，资金开关关闭时后端拒绝。
export function listReferralBounties(query) {
  return request({ url: '/business/referralbounty/list', method: 'get', params: query, rawBusinessError: true })
}

export function getReferralBountyMoneySwitch() {
  return request({ url: '/business/referralbounty/money-switch', method: 'get', rawBusinessError: true })
}

export function approveReferralBounty(id, note) {
  return request({ url: '/business/referralbounty/' + id + '/approve', method: 'post', data: { note }, rawBusinessError: true })
}

export function rejectReferralBounty(id, note) {
  return request({ url: '/business/referralbounty/' + id + '/reject', method: 'post', data: { note }, rawBusinessError: true })
}
