import request from '@/utils/request'

// §20.2 / §26.3：渠道主体、订单验证、兑换码发行与对账。
const raw = { rawBusinessError: true }
const base = '/business/marketing/channel'

export function listAccounts(params) { return request({ url: base + '/accounts', method: 'get', params, ...raw }) }
export function registerAccount(data) { return request({ url: base + '/accounts', method: 'post', data, ...raw }) }
export function updateAccountStatus(accountId, status) {
  return request({ url: base + '/accounts/' + accountId + '/status', method: 'post', params: { status }, ...raw })
}
export function verifyOrder(data) { return request({ url: base + '/verifications', method: 'post', data, ...raw }) }
export function listVerifications(params) { return request({ url: base + '/verifications', method: 'get', params, ...raw }) }
export function revokeVerification(id, data) {
  return request({ url: base + '/verifications/' + id + '/revoke', method: 'post', data, ...raw })
}
export function issueVoucher(data) { return request({ url: base + '/vouchers', method: 'post', data, ...raw }) }
export function listVouchers(params) { return request({ url: base + '/vouchers', method: 'get', params, ...raw }) }
export function revokeVoucher(id, data) {
  return request({ url: base + '/vouchers/' + id + '/revoke', method: 'post', data, ...raw })
}
export function getReconciliation(params) { return request({ url: base + '/reconciliation', method: 'get', params, ...raw }) }
