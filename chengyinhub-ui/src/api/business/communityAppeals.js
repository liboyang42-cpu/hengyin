import request from '@/utils/request'

export function listCommunityAppeals(params) {
  return request({ url: '/business/community/posts/appeals', method: 'get', params })
}

export function reviewCommunityAppeal(id, data) {
  return request({ url: '/business/community/posts/enforcements/' + id + '/appeals/review', method: 'post', data })
}
