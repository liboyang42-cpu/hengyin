import request from '@/utils/request'

// R10 平台频道治理
export const moderationChannels = params => request({ url: '/business/channelModeration/channels', method: 'get', params })
export const moderationMessages = params => request({ url: '/business/channelModeration/messages', method: 'get', params })
export const moderationReports = params => request({ url: '/business/channelModeration/reports', method: 'get', params })
export const moderationActions = params => request({ url: '/business/channelModeration/actions', method: 'get', params })
export const hideChannelMessage = (id, data) => request({ url: '/business/channelModeration/messages/' + id + '/hide', method: 'post', data })
export const restoreChannelMessage = (id, data) => request({ url: '/business/channelModeration/messages/' + id + '/restore', method: 'post', data })
export const dismissChannelReport = (id, data) => request({ url: '/business/channelModeration/reports/' + id + '/dismiss', method: 'post', data })
export const muteChannelMember = data => request({ url: '/business/channelModeration/mute', method: 'post', data })
export const removeSpaceMember = data => request({ url: '/business/channelModeration/remove', method: 'post', data })
export const revokeModerationAction = (id, data) => request({ url: '/business/channelModeration/actions/' + id + '/revoke', method: 'post', data })
