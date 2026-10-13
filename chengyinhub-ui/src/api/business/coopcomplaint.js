import request from '@/utils/request'

// 客诉工单列表
export function listCoopComplaint(query) {
  return request({
    url: '/business/coopcomplaint/list',
    method: 'get',
    params: query
  })
}

// 客诉工单详情
export function getCoopComplaint(id) {
  return request({
    url: '/business/coopcomplaint/' + id,
    method: 'get'
  })
}

// 先行垫付(平台→投诉玩家)
export function advanceCoopComplaint(data) {
  return request({
    url: '/business/coopcomplaint/advance',
    method: 'post',
    data: data
  })
}

// 关闭(认定过错方 + 扣划 + 解冻)
export function closeCoopComplaint(data) {
  return request({
    url: '/business/coopcomplaint/close',
    method: 'post',
    data: data
  })
}
