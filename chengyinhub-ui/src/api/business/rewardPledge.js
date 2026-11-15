import request from '@/utils/request'

// R10 非现金奖励承诺：政策与平台审核
export const rewardPolicies = () => request({ url: '/business/rewardPledge/policies', method: 'get' })
export const saveRewardPolicy = data => request({ url: '/business/rewardPledge/policies', method: 'post', data })
export const rewardPledgeQueue = params => request({ url: '/business/rewardPledge/queue', method: 'get', params })
export const rewardPledgeNotes = pledgeId => request({ url: '/business/rewardPledge/' + pledgeId + '/notes', method: 'get' })
export const approveRewardPledge = (versionId, data) => request({ url: '/business/rewardPledge/versions/' + versionId + '/approve', method: 'post', data })
export const rejectRewardPledge = (versionId, data) => request({ url: '/business/rewardPledge/versions/' + versionId + '/reject', method: 'post', data })
export const noteRewardPledge = (versionId, data) => request({ url: '/business/rewardPledge/versions/' + versionId + '/note', method: 'post', data })
