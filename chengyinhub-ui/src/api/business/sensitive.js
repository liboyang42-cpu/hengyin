import request from '@/utils/request'

// 敏感内容库(Round5D):词、放行短语、已知违规图片、命中记录

export function listWords(query) {
  return request({ url: '/business/sensitive/words/list', method: 'get', params: query })
}

export function addWord(data) {
  return request({ url: '/business/sensitive/words', method: 'post', data })
}

export function setWordEnabled(id, enabled) {
  return request({ url: '/business/sensitive/words/' + id + '/enabled/' + enabled, method: 'put' })
}

export function importWords(text) {
  return request({ url: '/business/sensitive/words/import', method: 'post', data: { text } })
}

export function listAllows(query) {
  return request({ url: '/business/sensitive/allows/list', method: 'get', params: query })
}

export function addAllow(data) {
  return request({ url: '/business/sensitive/allows', method: 'post', data })
}

export function setAllowEnabled(id, enabled) {
  return request({ url: '/business/sensitive/allows/' + id + '/enabled/' + enabled, method: 'put' })
}

export function importAllows(text) {
  return request({ url: '/business/sensitive/allows/import', method: 'post', data: { text } })
}

export function listImages(query) {
  return request({ url: '/business/sensitive/images/list', method: 'get', params: query })
}

export function addImage(formData) {
  return request({ url: '/business/sensitive/images', method: 'post', data: formData })
}

export function setImageEnabled(id, enabled) {
  return request({ url: '/business/sensitive/images/' + id + '/enabled/' + enabled, method: 'put' })
}

export function listHits(limit) {
  return request({ url: '/business/sensitive/hits/list', method: 'get', params: { limit } })
}
