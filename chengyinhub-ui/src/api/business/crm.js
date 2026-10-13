import request from '@/utils/request'

export function listCrmCustomer(query) {
  return request({
    url: '/business/crm/list',
    method: 'get',
    params: query
  })
}

export function getCrmSummary(query) {
  return request({
    url: '/business/crm/summary',
    method: 'get',
    params: query
  })
}

export function aggregateCrm(ownerMemberId) {
  return request({
    url: '/business/crm/aggregate',
    method: 'post',
    params: { ownerMemberId }
  })
}
