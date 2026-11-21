import request from '@/utils/request'

// 官方内容冷启动工具：批量导入默认只预检(dryRun)，确认后才写入，写入的都是待审草稿。
function upload(url, file, params) {
  const data = new FormData()
  data.append('file', file)
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') data.append(key, value)
  })
  return request({ url, method: 'post', data, headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000 })
}

export const importOfficialPlaces = (file, params) => upload('/business/seed/places/import', file, params)
export const importOfficialRoutes = (file, params) => upload('/business/seed/routes/import', file, params)
export const densityAreas = () => request({ url: '/business/seed/density/areas', method: 'get' })
export const densityReport = data => request({ url: '/business/seed/density', method: 'post', data })
export const aiNodeDraft = placeId => request({ url: '/business/seed/ai-node/' + placeId, method: 'post', timeout: 90000 })
