import request from '@/utils/request'

// §18 E/F：视频悬赏运营后台。拒绝带 reasonCode，页面按码分支，不弹全局提示。
const raw = { rawBusinessError: true }

export function listCampaigns(status) {
  return request({ url: '/business/videobounty/campaign/list', method: 'get', params: { status }, ...raw })
}
export function createCampaign(data) {
  return request({ url: '/business/videobounty/campaign', method: 'post', data, ...raw })
}
export function updateCampaign(id, data) {
  return request({ url: '/business/videobounty/campaign/' + id, method: 'put', data, ...raw })
}
export function changeCampaignStatus(id, status) {
  return request({ url: '/business/videobounty/campaign/' + id + '/status', method: 'post', params: { status }, ...raw })
}
export function listSubmissions(query) {
  return request({ url: '/business/videobounty/submission/list', method: 'get', params: query, ...raw })
}
export function reviewSubmission(id, data) {
  return request({ url: '/business/videobounty/submission/' + id + '/review', method: 'post', data, ...raw })
}
export function revokeSubmission(id, note) {
  return request({ url: '/business/videobounty/submission/' + id + '/revoke', method: 'post', data: { note }, ...raw })
}
export function verifyGroup(id, data) {
  return request({ url: '/business/videobounty/submission/' + id + '/verify-group', method: 'post', data, ...raw })
}
export function recordEvidence(id, data) {
  return request({ url: '/business/videobounty/submission/' + id + '/evidence', method: 'post', data, ...raw })
}
export function recomputeAccrual(id) {
  return request({ url: '/business/videobounty/submission/' + id + '/accrual/recompute', method: 'post', ...raw })
}
export function listAccruals(query) {
  return request({ url: '/business/videobounty/accrual/list', method: 'get', params: query, ...raw })
}
export function submissionAttribution(id) {
  return request({ url: '/business/videobounty/submission/' + id + '/attribution', method: 'get', ...raw })
}
