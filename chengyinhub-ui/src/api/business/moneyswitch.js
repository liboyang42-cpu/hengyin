import request from '@/utils/request'

// Q5 资金开关:六个区域的存储值、生效值与服务端上限
export function getMoneySwitch() {
  return request({
    url: '/business/money-switch',
    method: 'get'
  })
}

// 打开或关闭一个区域(须填写原因;服务端上限关闭时不能打开)
export function changeMoneySwitch(data) {
  return request({
    url: '/business/money-switch',
    method: 'put',
    data: data
  })
}

// 最近的改动记录(谁、何时、旧值、新值、原因)
export function listMoneySwitchAudit(limit) {
  return request({
    url: '/business/money-switch/audit',
    method: 'get',
    params: { limit: limit || 50 }
  })
}
