import request from '@/utils/request'

// 查询商家目录列表
export function listMerchantLead(query) {
  return request({
    url: '/business/merchantlead/list',
    method: 'get',
    params: query
  })
}

// 查询商家目录详细
export function getMerchantLead(id) {
  return request({
    url: '/business/merchantlead/' + id,
    method: 'get'
  })
}

// 新增商家目录
export function addMerchantLead(data) {
  return request({
    url: '/business/merchantlead',
    method: 'post',
    data: data
  })
}

// 修改商家目录
export function updateMerchantLead(data) {
  return request({
    url: '/business/merchantlead',
    method: 'put',
    data: data
  })
}

// 删除商家目录
export function delMerchantLead(id) {
  return request({
    url: '/business/merchantlead/' + id,
    method: 'delete'
  })
}
