import request from '@/utils/request'

// 带队场次列表(跨主题只读)
export function runList(params) {
  return request({
    url: '/business/clubRun/list',
    method: 'get',
    params: params
  })
}

// 场次名册 + 流程经过的商家
export function runManifest(activityId) {
  return request({
    url: '/business/clubRun/manifest/' + activityId,
    method: 'get'
  })
}
