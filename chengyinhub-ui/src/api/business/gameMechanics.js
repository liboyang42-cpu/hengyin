import request from '@/utils/request'

// G2 游戏机制:寻宝(编辑、审核、上线、结束)与试走记录(查看、豁免)。

export function listTreasureHunts(status) {
  return request({ url: '/business/treasureHunt/list', method: 'get', params: { status } })
}

export function getTreasureHunt(id) {
  return request({ url: '/business/treasureHunt/' + id, method: 'get' })
}

export function saveTreasureHunt(data) {
  return request({ url: '/business/treasureHunt', method: 'post', data })
}

export function decideTreasureHunt(id, decision, note) {
  return request({ url: '/business/treasureHunt/' + id + '/' + decision, method: 'put', data: { note } })
}

export function listWalkTests() {
  return request({ url: '/business/walkTest/list', method: 'get' })
}

export function getWalkTest(topicId) {
  return request({ url: '/business/walkTest/' + topicId, method: 'get' })
}

export function waiveWalkTest(topicId, note) {
  return request({ url: '/business/walkTest/' + topicId + '/waive', method: 'put', data: { note } })
}

export function unwaiveWalkTest(topicId) {
  return request({ url: '/business/walkTest/' + topicId + '/unwaive', method: 'put' })
}
