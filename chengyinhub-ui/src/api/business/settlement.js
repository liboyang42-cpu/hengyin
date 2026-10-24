import request from '@/utils/request'

export function listSettlementBatch(query) {
  return request({
    url: '/business/settlement/list',
    method: 'get',
    params: query
  })
}

export function listSettlementDetail(query) {
  return request({
    url: '/business/settlement/detail/list',
    method: 'get',
    params: query
  })
}

export function getSettlementSummary(query) {
  return request({
    url: '/business/settlement/summary',
    method: 'get',
    params: query
  })
}

export function generateSettlement(data) {
  return request({
    url: '/business/settlement/generate',
    method: 'post',
    data
  })
}

export function confirmSettlement(batchId) {
  return request({
    url: '/business/settlement/confirm',
    method: 'post',
    params: { batchId }
  })
}

export function getSettlementReconciliation(merchantId) {
  return request({
    url: '/business/settlement/reconciliation',
    method: 'get',
    params: { merchantId }
  })
}

export function listSettlementManualCases(query) {
  return request({ url: '/business/settlement/manual-case/list', method: 'get', params: query })
}

export function getSettlementManualCase(id) {
  return request({ url: '/business/settlement/manual-case/' + id, method: 'get' })
}
