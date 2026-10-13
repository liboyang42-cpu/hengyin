import request from '@/utils/request'

export function listRecommendationScores(query) {
  return request({
    url: '/business/recommendation/scores',
    method: 'get',
    params: query
  })
}
