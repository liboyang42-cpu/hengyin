import request from '@/utils/request'

// 获取统计数据
export function getHomeData() {
  return request({
    url: '/business/home/data',
    method: 'get'
  })
}
