import request from '@/utils/request'

// 客服会话列表
export function listImConversations() {
  return request({
    url: '/business/im/conversations',
    method: 'get'
  })
}

// 某会话消息
export function listImMessages(query) {
  return request({
    url: '/business/im/messages',
    method: 'get',
    params: query
  })
}

// 客服发送消息
export function sendImMessage(data) {
  return request({
    url: '/business/im/send',
    method: 'post',
    params: data
  })
}

// 拉黑用户
export function blockImUser(memberId) {
  return request({
    url: '/business/im/block',
    method: 'post',
    params: { memberId: memberId }
  })
}

// 解除拉黑
export function unblockImUser(memberId) {
  return request({
    url: '/business/im/unblock',
    method: 'post',
    params: { memberId: memberId }
  })
}

// Read the original result; a missing receipt is still UNKNOWN.
export function getImSendReceipt(query) {
  return request({ url: '/business/im/send/receipt', method: 'get', params: query })
}
