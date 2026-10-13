import request from '@/utils/request'

// 扫码核销流水(只读;数人头的唯一真源)
export function scanList(params) {
  return request({
    url: '/business/verifyEvidence/scans',
    method: 'get',
    params: params
  })
}

// 团核销台账(只读)
export function groupVerifyList(params) {
  return request({
    url: '/business/verifyEvidence/groupVerifies',
    method: 'get',
    params: params
  })
}
