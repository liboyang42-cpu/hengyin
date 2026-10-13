import request from '@/utils/request'

// 查询我的模板列表
export function listMembertemplate(query) {
  return request({
    url: '/business/membertemplate/list',
    method: 'get',
    params: query
  })
}

// 查询我的模板详细
export function getMembertemplate(id) {
  return request({
    url: '/business/membertemplate/' + id,
    method: 'get'
  })
}

// 新增我的模板
export function addMembertemplate(data) {
  return request({
    url: '/business/membertemplate',
    method: 'post',
    data: data
  })
}

// 修改我的模板
export function updateMembertemplate(data) {
  return request({
    url: '/business/membertemplate',
    method: 'put',
    data: data
  })
}

// 将存量模板提交统一审核
export function submitMembertemplateReview(id) {
  return request({
    url: '/business/membertemplate/' + id + '/review',
    method: 'post'
  })
}

// 删除我的模板
export function delMembertemplate(id) {
  return request({
    url: '/business/membertemplate/' + id,
    method: 'delete'
  })
}
