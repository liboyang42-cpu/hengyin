import request from '@/utils/request'

// 查询众筹礼物列表
export function listGift(query) {
  return request({
    url: '/business/gift/list',
    method: 'get',
    params: query
  })
}

// 查询众筹礼物详细
export function getGift(id) {
  return request({
    url: '/business/gift/' + id,
    method: 'get'
  })
}

// 新增众筹礼物
export function addGift(data) {
  return request({
    url: '/business/gift',
    method: 'post',
    data: data
  })
}

// 修改众筹礼物
export function updateGift(data) {
  return request({
    url: '/business/gift',
    method: 'put',
    data: data
  })
}

// 删除众筹礼物
export function delGift(id) {
  return request({
    url: '/business/gift/' + id,
    method: 'delete'
  })
}
