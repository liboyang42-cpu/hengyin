import request from '@/utils/request'

export const officialPoiDraft = id => request({ url: '/business/roamPoi/official/' + id, method: 'get' })
export const saveOfficialPoi = data => request({ url: '/business/roamPoi/official', method: 'post', data })
export const approveOfficialPoi = id => request({ url: '/business/roamPoi/official/' + id + '/approve', method: 'put' })
export const rejectOfficialPoi = (id, reason) => request({ url: '/business/roamPoi/official/' + id + '/reject', method: 'put', params: { reason } })

// POI 治理列表(全量含下架;status/cityCode/merchantId 可筛)
export function adminPoiList(params) {
  return request({
    url: '/business/roamPoi/list',
    method: 'get',
    params: params
  })
}

export function poiOnline(id) {
  return request({
    url: '/business/roamPoi/online/' + id,
    method: 'put'
  })
}

export function poiOffline(id) {
  return request({
    url: '/business/roamPoi/offline/' + id,
    method: 'put'
  })
}

export function roamPoiApplications(params) {
  return request({
    url: '/business/roamPoi/applications',
    method: 'get',
    params: params
  })
}

export function approveRoamPoiApplication(id) {
  return request({
    url: '/business/roamPoi/applications/approve/' + id,
    method: 'put'
  })
}

export function rejectRoamPoiApplication(id, reason) {
  return request({
    url: '/business/roamPoi/applications/reject/' + id,
    method: 'put',
    params: { reason: reason }
  })
}
