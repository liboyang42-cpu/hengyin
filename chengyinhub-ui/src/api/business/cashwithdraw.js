import request from '@/utils/request'

// 查询提现出金列表(通道A 微信零钱)
export function listCashwithdraw(query) {
  return request({
    url: '/business/cashwithdraw/list',
    method: 'get',
    params: query
  })
}

// 提现出金详情
export function getCashwithdraw(id) {
  return request({
    url: '/business/cashwithdraw/' + id,
    method: 'get'
  })
}

// 手动重新查询微信最终状态(卡在处理中的单)
export function requeryCashwithdraw(id) {
  return request({
    url: '/business/cashwithdraw/requery/' + id,
    method: 'post'
  })
}

// 人工补单(失败待人工:重试退回余额)
export function manualRefundCashwithdraw(data) {
  return request({
    url: '/business/cashwithdraw/manual-refund',
    method: 'post',
    data: data
  })
}
