import request from '@/utils/request'

export function listSettlementRule(query) {
  return request({
    url: '/business/settlementrule/list',
    method: 'get',
    params: query
  })
}

export function getActiveSettlementRule(query) {
  return request({
    url: '/business/settlementrule/active',
    method: 'get',
    params: query
  })
}

export function addSettlementRule(data) {
  return request({
    url: '/business/settlementrule',
    method: 'post',
    data
  })
}

export function updateSettlementRule(data) {
  return request({
    url: '/business/settlementrule',
    method: 'put',
    data
  })
}

export function getPlatformSettlementAccount() {
  return request({
    url: '/business/settlementrule/platform-account',
    method: 'get'
  })
}

export function updatePlatformSettlementAccount(data) {
  return request({
    url: '/business/settlementrule/platform-account',
    method: 'put',
    data
  })
}
