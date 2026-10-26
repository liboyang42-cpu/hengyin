import request from '@/utils/request'

// 官方活动列表(分页)
export function listOfficialEvent(query) {
  return request({ url: '/business/officialevent/list', method: 'get', params: query })
}

// 官方活动详情
export function getOfficialEvent(id) {
  return request({ url: '/business/officialevent/' + id, method: 'get' })
}

// 新增官方活动
export function addOfficialEvent(data) {
  return request({ url: '/business/officialevent', method: 'post', data: data })
}

// 修改官方活动
export function updateOfficialEvent(data) {
  return request({ url: '/business/officialevent', method: 'put', data: data })
}

// 上线(status=2)/下线(status=9)
export function setStatusOfficialEvent(id, status) {
  return request({ url: '/business/officialevent/' + id + '/status', method: 'post', params: { status: status } })
}

// 手动结算发奖
export function settleOfficialEvent(id) {
  return request({ url: '/business/officialevent/' + id + '/settle', method: 'post' })
}

// 发官方通知
export function broadcastOfficial(data) {
  return request({ url: '/business/officialevent/broadcast', method: 'post', data: data })
}

// 活动数据/复盘(参与/完成/集体)
export function statsOfficialEvent(id) {
  return request({ url: '/business/officialevent/' + id + '/stats', method: 'get' })
}

// V2 活动合同：草稿、发布检查、受控生命周期与活动 360。
export function saveOfficialEventV2Draft(data) {
  return request({ url: '/business/officialevent/v2/draft', method: 'post', data: data })
}

// Web 后台账号必须先被显式授权为白名单官方会员，不能凭相同数值 ID 推断身份。
export function getOfficialEventV2OperatorIdentity() {
  return request({ url: '/business/officialevent/v2/operator-identity', method: 'get' })
}

export function authorizeOfficialEventV2Operator(data) {
  return request({ url: '/business/officialevent/v2/operator-authorizations', method: 'post', data: data })
}

export function revokeOfficialEventV2Operator(sysUserId, reason) {
  return request({ url: '/business/officialevent/v2/operator-authorizations/' + sysUserId, method: 'delete', params: { reason: reason } })
}

export function getOfficialEventV2Draft(id) {
  return request({ url: '/business/officialevent/v2/' + id + '/draft', method: 'get' })
}

export function checkOfficialEventV2Publish(id) {
  return request({ url: '/business/officialevent/v2/' + id + '/publish-check', method: 'get' })
}

export function scheduleOfficialEventV2(id) {
  return request({ url: '/business/officialevent/v2/' + id + '/schedule', method: 'post' })
}

export function getOfficialEvent360(id) {
  return request({ url: '/business/officialevent/v2/' + id + '/360', method: 'get' })
}

export function pauseOfficialEventV2(id, form) {
  return request({ url: '/business/officialevent/v2/' + id + '/pause', method: 'post', params: form })
}

export function resumeOfficialEventV2(id, reason) {
  return request({ url: '/business/officialevent/v2/' + id + '/resume', method: 'post', params: { reason: reason } })
}

export function queryBroadcastCreation(requestId) {
  return request({ url: '/business/officialevent/broadcast-request', method: 'get', params: { requestId } })
}
export function cancelBroadcastCreation(requestId) {
  return request({ url: '/business/officialevent/broadcast-request/cancel', method: 'post', data: { requestId } })
}
export function readBroadcastDelivery(id) {
  return request({ url: '/business/officialevent/broadcast/' + id + '/stats', method: 'get' })
}
export function recoverBroadcastDelivery(id) {
  return request({ url: '/business/officialevent/broadcast/' + id + '/recover', method: 'post' })
}
