import request from '@/utils/request'

export const cityBoardDraftsEnabled = process.env.VUE_APP_CITY_BOARD_DRAFTS === 'true'
function call(config, context) {
  if (!cityBoardDraftsEnabled) return Promise.reject(new Error('CITY_DRAFT_DISABLED'))
  if (!context || typeof context.isCurrent !== 'function' || !context.isCurrent()) return Promise.reject(new Error('CITY_DRAFT_CONTEXT_RETIRED'))
  return request({ ...config, auditContext: context, preserveBusinessError: true, headers: { repeatSubmit: false } })
}
const base = '/business/city-board-drafts'
export const readCityDraftCatalog = context => call({ url: base + '/catalog', method: 'get' }, context)
export const readCityDraft = (id, context) => call({ url: base + '/' + encodeURIComponent(id), method: 'get' }, context)
export const validateCityDraft = (data, context) => call({ url: base + '/validate', method: 'post', data }, context)
export const saveCityDraft = (data, context) => call({ url: base + '/save', method: 'post', data }, context)
export const previewCityDraft = (data, context) => call({ url: base + '/preview', method: 'post', data }, context)
