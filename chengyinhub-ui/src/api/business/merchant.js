import request from '@/utils/request'

// 查询商家列表
export function listMerchant(query) {
  return request({
    url: '/business/merchant/list',
    method: 'get',
    params: query
  })
}

// 查询商家详细
export function getMerchant(id) {
  return request({
    url: '/business/merchant/' + id,
    method: 'get'
  })
}

// 新增商家
export function addMerchant(data) {
  return request({
    url: '/business/merchant',
    method: 'post',
    data: data
  })
}

// 修改商家
export function updateMerchant(data) {
  return request({
    url: '/business/merchant',
    method: 'put',
    data: data
  })
}

// 平台停用/启用商家账号(R9-35):停用必须带 disableReason;启用会清空停用原因
export function changeMerchantStatus(data) {
  return request({
    url: '/business/merchant/changeStatus',
    method: 'put',
    data: data
  })
}

// 删除商家
export function delMerchant(id) {
  return request({
    url: '/business/merchant/' + id,
    method: 'delete'
  })
}
