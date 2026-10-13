import request from '@/utils/request'

// 查询创意广场-用户发布列表
export function listSquare(query) {
  return request({
    url: '/business/creativesquare/list',
    method: 'get',
    params: query
  })
}

// 查询创意广场-用户发布详细
export function getSquare(id) {
  return request({
    url: '/business/creativesquare/' + id,
    method: 'get'
  })
}

// 将现有帖文提交统一审核
export function submitSquareReview(id) {
  return request({
    url: '/business/creativesquare/' + id + '/review',
    method: 'post'
  })
}

// 新增创意广场-用户发布
export function addSquare(data) {
  return request({
    url: '/business/creativesquare',
    method: 'post',
    data: data
  })
}

// 修改创意广场-用户发布
export function updateSquare(data) {
  return request({
    url: '/business/creativesquare',
    method: 'put',
    data: data
  })
}

// 删除创意广场-用户发布
export function delSquare(id) {
  return request({
    url: '/business/creativesquare/' + id,
    method: 'delete'
  })
}
