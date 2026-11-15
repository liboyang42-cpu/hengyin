import request from '@/utils/request'

// R10 随机掉落规则编辑器
export const listRewardDropRules = params => request({ url: '/business/rewardDropRule/list', method: 'get', params })
export const validateRewardDropRule = data => request({ url: '/business/rewardDropRule/validate', method: 'post', data })
export const addRewardDropRule = data => request({ url: '/business/rewardDropRule', method: 'post', data })
export const updateRewardDropRule = (id, data) => request({ url: '/business/rewardDropRule/' + id, method: 'put', data })
export const setRewardDropRuleStatus = (id, status) => request({ url: '/business/rewardDropRule/' + id + '/status/' + status, method: 'put' })
