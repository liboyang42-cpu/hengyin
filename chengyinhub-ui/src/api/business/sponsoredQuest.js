import request from '@/utils/request'

// S3b 赞助任务:运营创建与上下线;玩家只在「优惠」图层看到带「广告」标签的任务。计费只在资金开关打开时可用。

export function listSponsoredQuests() {
  return request({ url: '/business/sponsoredQuest/list', method: 'get' })
}

export function getSponsoredQuestState() {
  return request({ url: '/business/sponsoredQuest/state', method: 'get' })
}

export function getSponsoredQuest(id) {
  return request({ url: '/business/sponsoredQuest/' + id, method: 'get' })
}

export function addSponsoredQuest(data) {
  return request({ url: '/business/sponsoredQuest', method: 'post', data })
}

export function updateSponsoredQuest(data) {
  return request({ url: '/business/sponsoredQuest', method: 'put', data })
}

export function setSponsoredQuestStatus(id, status, reason) {
  return request({ url: '/business/sponsoredQuest/' + id + '/status', method: 'put', params: { status, reason } })
}

export function listSponsoredQuestCharges(id) {
  return request({ url: '/business/sponsoredQuest/' + id + '/charges', method: 'get' })
}

export function chargeSponsoredQuest(id, refKey, amountCent, reason) {
  return request({ url: '/business/sponsoredQuest/' + id + '/charges', method: 'post', params: { refKey, amountCent, reason } })
}
