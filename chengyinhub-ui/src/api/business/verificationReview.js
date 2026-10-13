import request from '@/utils/request'

// 核销率/频次异常人工复核队列。
// 只做人审：处置只翻 verification_review.status，不改核销事实、不退款、不动任何资金。
export function listVerificationReview(query) {
  return request({
    url: '/business/verificationReview/list',
    method: 'get',
    params: query
  })
}

// status: 1 = 已确认正常，2 = 确认异常。
// 只收 1/2 —— 收 0 等于把处置过的退回待复核，那是撤销不是处置。
// 后端 WHERE 带 topic_id + status=0：重复处置返回 0 行并报「已被处置」，不会覆写别人的结论。
export function handleVerificationReview(id, status) {
  return request({
    url: '/business/verificationReview/handle',
    method: 'post',
    params: { id: id, status: status }
  })
}
