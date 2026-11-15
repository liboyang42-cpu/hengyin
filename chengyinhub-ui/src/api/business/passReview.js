import request from '@/utils/request'

// T5 通行证:App Store 撤回退款后的人工复核
export const passReviewOrders = params => request({ url: '/business/passReview/orders', method: 'get', params })
export const regrantPassOrder = (orderNo, data) => request({ url: '/business/passReview/orders/' + orderNo + '/regrant', method: 'post', data })
export const closePassReview = (orderNo, data) => request({ url: '/business/passReview/orders/' + orderNo + '/close', method: 'post', data })
