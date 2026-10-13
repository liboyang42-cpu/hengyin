import request from '@/utils/request'

export function listGrowthMissions(query) {
  return request({
    url: '/business/growth/mission/list',
    method: 'get',
    params: query
  })
}

export function saveGrowthMission(data) {
  return request({
    url: '/business/growth/mission',
    method: 'post',
    data
  })
}

// 用户 360 内的只读成长调证
export function getMemberGrowthCenter(memberId) {
  return request({
    url: '/business/growth/member/' + memberId,
    method: 'get'
  })
}
