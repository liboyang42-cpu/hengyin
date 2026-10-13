import request from '@/utils/request'

// 调配审核树：主题 → 商家报名（平铺；竞争位=主题×节点，不按模板分层）
export function dispatchTree(topicId) {
  return request({
    url: '/business/registrationmerchant/dispatch_tree',
    method: 'get',
    params: { topicId: topicId }
  })
}

// 选定中标商家（同竞争位其余待调配落选；不再生成活动，合作单走条款邀约）
export function selectDispatch(id) {
  return request({
    url: '/business/registrationmerchant/select/' + id,
    method: 'put'
  })
}

// 驳回单条报名
export function rejectDispatch(id, reason) {
  return request({
    url: '/business/registrationmerchant/reject/' + id,
    method: 'put',
    data: { reason: reason }
  })
}
