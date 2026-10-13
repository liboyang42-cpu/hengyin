import request from '@/utils/request'

// 查询积分商城商品列表
export function listProduct(query) {
  return request({
    url: '/business/product/list',
    method: 'get',
    params: query
  })
}

// 查询积分商城商品详细
export function getProduct(id) {
  return request({
    url: '/business/product/' + id,
    method: 'get'
  })
}

// 新增积分商城商品
export function addProduct(data) {
  return request({
    url: '/business/product',
    method: 'post',
    data: data
  })
}

// 修改积分商城商品
export function updateProduct(data) {
  return request({
    url: '/business/product',
    method: 'put',
    data: data
  })
}

// 商品目录上下架（只传商品 ID 和目标状态，不携带 SKU）
export function updateProductPublishStatus(data) {
  return request({
    url: '/business/product/publish-status',
    method: 'put',
    data: data
  })
}

// 删除积分商城商品
export function delProduct(id) {
  return request({
    url: '/business/product/' + id,
    method: 'delete'
  })
}
