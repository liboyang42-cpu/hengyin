import request from '@/utils/request'

// E1 主题人物表 (npc_profile scope_type=1)、3D形象任务审核、声音授权审计与AI提供方状态

export function listThemeNpc(params) {
  return request({
    url: '/business/themeNpc/list',
    method: 'get',
    params
  })
}

export function getThemeNpc(id) {
  return request({
    url: '/business/themeNpc/' + id,
    method: 'get'
  })
}

export function listThemeNpcTopics(params) {
  return request({
    url: '/business/themeNpc/topics',
    method: 'get',
    params
  })
}

export function listThemeRelationKeys(topicId) {
  return request({
    url: '/business/themeNpc/relationKeys',
    method: 'get',
    params: { topicId }
  })
}

export function addThemeNpc(data) {
  return request({
    url: '/business/themeNpc',
    method: 'post',
    data
  })
}

export function updateThemeNpc(data) {
  return request({
    url: '/business/themeNpc',
    method: 'put',
    data
  })
}

export function delThemeNpc(id) {
  return request({
    url: '/business/themeNpc/' + id,
    method: 'delete'
  })
}

export function listAvatarJobs(params) {
  return request({
    url: '/business/themeNpc/avatarJobs',
    method: 'get',
    params
  })
}

export function reviewAvatarJob(id, data) {
  return request({
    url: '/business/themeNpc/avatarJobs/' + id + '/review',
    method: 'post',
    data
  })
}

export function listVoiceAudits(params) {
  return request({
    url: '/business/themeNpc/voiceAudits',
    method: 'get',
    params
  })
}

export function revokeVoiceConsent(id, voiceVersion) {
  return request({
    url: '/business/themeNpc/voiceAudits/' + id + '/revoke',
    method: 'post',
    data: { voiceVersion }
  })
}

export function listAiProviderStatuses() {
  return request({
    url: '/business/themeNpc/providers',
    method: 'get'
  })
}
