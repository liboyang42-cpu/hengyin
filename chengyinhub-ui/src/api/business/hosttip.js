import request from '@/utils/request'

// §18 E：主持小费审计，只读；没有“标已付”。
export function listHostTipIntents(hostMemberId) {
  return request({ url: '/business/hosttip/intent/list', method: 'get', params: { hostMemberId }, rawBusinessError: true })
}
