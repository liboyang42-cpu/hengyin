import request from '@/utils/request'

// 查询掉落规则列表
export function listDropRules(query) {
  return request({
    url: '/business/reward-drop/rules/list',
    method: 'get',
    params: query
  })
}

// 查询单条掉落规则
export function getDropRule(id) {
  return request({
    url: '/business/reward-drop/rules/' + id,
    method: 'get'
  })
}

// 新增掉落规则
export function addDropRule(data) {
  return request({
    url: '/business/reward-drop/rules',
    method: 'post',
    data: data
  })
}

// 修改掉落规则
export function updateDropRule(data) {
  return request({
    url: '/business/reward-drop/rules',
    method: 'put',
    data: data
  })
}

// 删除掉落规则
export function delDropRule(id) {
  return request({
    url: '/business/reward-drop/rules/' + id,
    method: 'delete'
  })
}

// 获取基础概率（万分）
export function getBaseDropProb() {
  return request({
    url: '/business/reward-drop/base-prob',
    method: 'get'
  })
}

// 更新基础概率（万分）
export function updateBaseDropProb(baseProbBp) {
  return request({
    url: '/business/reward-drop/base-prob',
    method: 'put',
    data: { baseProbBp }
  })
}

// 预览生效概率与期望产出
export function previewDropProbabilities(query) {
  return request({
    url: '/business/reward-drop/preview',
    method: 'get',
    params: query
  })
}

// 查询可审计随机流水
export function listRandomAudits(query) {
  return request({
    url: '/business/reward-drop/audit/list',
    method: 'get',
    params: query
  })
}
