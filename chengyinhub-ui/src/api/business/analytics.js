import request from '@/utils/request'

export function listFunnel(query) {
  return request({
    url: '/business/analytics/funnel',
    method: 'get',
    params: query
  })
}

// P6 measurement：登录前首会话漏斗（按匿名安装号去重，只读即时计算）。
export function getPreSignInFunnel(query) {
  return request({
    url: '/business/analytics/funnel/pre-sign-in',
    method: 'get',
    params: query
  })
}

// U08-04 / §27.4：完局维护与有效复报（只读即时计算）。
export function getPostGameOutcome(query) {
  return request({
    url: '/business/analytics/post-game-outcome',
    method: 'get',
    params: query,
    rawBusinessError: true
  })
}
