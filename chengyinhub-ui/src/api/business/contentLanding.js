import request from '@/utils/request'

// §20.1：落地译文审校。只翻状态，不改作者文字。
const raw = { rawBusinessError: true }

export function listLanding(query) {
  return request({ url: '/business/content-landing/list', method: 'get', params: query, ...raw })
}
export function getTranslationDetail(topicId) {
  return request({ url: '/business/content-landing/translation/detail', method: 'get', params: { topicId }, ...raw })
}
export function getTranslationDigest(topicId, locale) {
  return request({ url: '/business/content-landing/translation/digest', method: 'get', params: { topicId, locale }, ...raw })
}
export function reviewTranslation(data) {
  return request({ url: '/business/content-landing/translation/review', method: 'post', data, ...raw })
}
