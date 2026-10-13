import request from '@/utils/request'

export function listFunnel(query) {
  return request({
    url: '/business/analytics/funnel',
    method: 'get',
    params: query
  })
}
