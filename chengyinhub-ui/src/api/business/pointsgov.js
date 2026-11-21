import request from '@/utils/request'

// 积分治理看板(某月:发放/兑换/未兑负债、项目预算、来源每日上限)
export function getPointsGovDashboard(period) {
  return request({
    url: '/business/pointsgov/dashboard',
    method: 'get',
    params: { period }
  })
}

// 修改某积分来源的每人每日上限
export function updatePointsCap(data) {
  return request({
    url: '/business/pointsgov/cap',
    method: 'put',
    data
  })
}

// 修改某奖励项目的月度预算
export function updatePointsBudget(data) {
  return request({
    url: '/business/pointsgov/budget',
    method: 'put',
    data
  })
}
