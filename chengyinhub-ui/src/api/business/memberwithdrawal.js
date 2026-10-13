import request from '@/utils/request'

// 查询用户提现申请列表
export function listMemberwithdrawal(query) {
  return request({
    url: '/business/memberwithdrawal/list',
    method: 'get',
    params: query
  })
}

// 查询用户提现申请详细
export function getMemberwithdrawal(id) {
  return request({
    url: '/business/memberwithdrawal/' + id,
    method: 'get'
  })
}

// 新增用户提现申请
export function addMemberwithdrawal(data) {
  return request({
    url: '/business/memberwithdrawal',
    method: 'post',
    data: data
  })
}

// 修改用户提现申请
export function updateMemberwithdrawal(data) {
  return request({
    url: '/business/memberwithdrawal',
    method: 'put',
    data: data
  })
}

// 审核用户提现申请
export function reviewMemberwithdrawal(data) {
  return request({
    url: '/business/memberwithdrawal/review',
    method: 'put',
    data: data
  })
}

// 查看提现真实收款资料(解密,财务打款用;走审计,权限同审核)
export function getMemberwithdrawalPayout(id) {
  return request({
    url: '/business/memberwithdrawal/payout/' + id,
    method: 'get'
  })
}

// 删除用户提现申请
export function delMemberwithdrawal(id) {
  return request({
    url: '/business/memberwithdrawal/' + id,
    method: 'delete'
  })
}
