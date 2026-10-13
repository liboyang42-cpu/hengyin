import request from '@/utils/request'

export function listCreators(query) {
  return request({
    url: '/business/creator/list',
    method: 'get',
    params: query
  })
}

export function reviewCreator(data) {
  return request({
    url: '/business/creator/review',
    method: 'post',
    data
  })
}
