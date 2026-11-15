import request from '@/utils/request'

// R10 主题数值规则与状态键
export const journeyTopics = params => request({ url: '/business/journeyRules/topics', method: 'get', params })
export const readJourneyRules = topicId => request({ url: '/business/journeyRules/' + topicId, method: 'get' })
export const previewJourneyRules = (topicId, data) => request({ url: '/business/journeyRules/' + topicId + '/preview', method: 'post', data })
export const saveJourneyRules = (topicId, data) => request({ url: '/business/journeyRules/' + topicId, method: 'put', data })
export const checkStateKeys = data => request({ url: '/business/journeyRules/state-keys/check', method: 'post', data })
export const createStateKey = (topicId, data) => request({ url: '/business/journeyRules/' + topicId + '/state-keys', method: 'post', data })
export const updateStateKey = (topicId, stateKey, data) => request({ url: '/business/journeyRules/' + topicId + '/state-keys/' + encodeURIComponent(stateKey), method: 'put', data })
export const deleteStateKey = (topicId, stateKey, note) => request({ url: '/business/journeyRules/' + topicId + '/state-keys/' + encodeURIComponent(stateKey), method: 'delete', params: { note } })
