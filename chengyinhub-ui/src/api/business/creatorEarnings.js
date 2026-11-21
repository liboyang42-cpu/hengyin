import request from '@/utils/request'

// R12 创作者收益台账（只读报表 + 按天幂等重算）
export function getCreatorEarningsReport(query) {
  return request({ url: '/business/creator-earnings/report', method: 'get', params: query })
}

export function getCreatorEarningsLedger(query) {
  return request({ url: '/business/creator-earnings/ledger', method: 'get', params: query })
}

export function getCreatorEarningsRules() {
  return request({ url: '/business/creator-earnings/rules', method: 'get' })
}

export function accrueCreatorEarnings(day) {
  return request({ url: '/business/creator-earnings/accrue', method: 'post', params: { day } })
}
