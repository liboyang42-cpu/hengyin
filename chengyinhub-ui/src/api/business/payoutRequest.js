import request from '@/utils/request'

// R6b 提现审核队列:会员提现申请(状态可选 PENDING/PAYING/SENT/FAILED/REJECTED/CANCELLED)
export function listPayoutRequests(params) {
  return request({
    url: '/business/payout-request/list',
    method: 'get',
    params: params
  })
}

// 通过并打款(经既有微信商家转账;资金开关「提现」与转账通道都打开时才会受理,须填写说明)
export function approvePayoutRequest(id, note) {
  return request({
    url: '/business/payout-request/' + id + '/approve',
    method: 'post',
    data: { note: note }
  })
}

// 驳回(不动钱,须填写说明)
export function rejectPayoutRequest(id, note) {
  return request({
    url: '/business/payout-request/' + id + '/reject',
    method: 'post',
    data: { note: note }
  })
}
