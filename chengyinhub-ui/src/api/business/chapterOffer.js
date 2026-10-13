import request from '@/utils/request'

// 三档承接审核台队列:主题 → 章节 → 商家 offer(章节节点带口径三件套)
export function offerQueue(params) {
  return request({
    url: '/business/chapterOffer/queue',
    method: 'get',
    params: params
  })
}

// 定档定参:只认 id/termsMode/perkId/perHeadFee/quotaTotal/validEnd,闸在后端
export function adjustOffer(data) {
  return request({
    url: '/business/chapterOffer/adjust',
    method: 'put',
    data: data
  })
}

// 停用一行供给(不删行,核销流水保留)
export function deactivateOffer(id) {
  return request({
    url: '/business/chapterOffer/deactivate/' + id,
    method: 'put'
  })
}
