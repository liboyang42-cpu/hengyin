import request from '@/utils/request'

// UG-12：玩法提示阶梯配置摘要与单局明细（严格只读，不改写对局事实、不代玩家触发提示、不可导出答案）。
export function getTopicHelpConfig(topicId) {
  return request({
    url: `/business/playhelp/topic/${topicId}/config`,
    method: 'get',
    rawBusinessError: true
  })
}

export function getPlayHelpRunLedger(runId) {
  return request({
    url: `/business/playhelp/run/${runId}`,
    method: 'get',
    rawBusinessError: true
  })
}
