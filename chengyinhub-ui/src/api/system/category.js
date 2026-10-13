import request from '@/utils/request'

// 查询活动、主题等分类列表
export function listCategory(query) {
  return request({
    url: '/system/category/list',
    method: 'get',
    params: query
  })
}

// 查询玩法模板标签详情列表
export function getCategoryList(query) {
  const ids = String((query && query.ids) || '')
    .split(',')
    .map(id => id.trim())
    .filter(Boolean)

  return listCategory({ type: 4, pageSize: 1000 }).then(response => {
    const list = response.rows || response.data || response || []
    const filtered = ids.length ? list.filter(item => ids.includes(String(item.id))) : list
    return { ...response, data: filtered }
  })
}
 

// 查询活动、主题等分类详细
export function getCategory(id) {
  return request({
    url: '/system/category/' + id,
    method: 'get'
  })
}

// 新增活动、主题等分类
export function addCategory(data) {
  return request({
    url: '/system/category',
    method: 'post',
    data: data
  })
}

// 修改活动、主题等分类
export function updateCategory(data) {
  return request({
    url: '/system/category',
    method: 'put',
    data: data
  })
}

// 删除活动、主题等分类
export function delCategory(id) {
  return request({
    url: '/system/category/' + id,
    method: 'delete'
  })
}
