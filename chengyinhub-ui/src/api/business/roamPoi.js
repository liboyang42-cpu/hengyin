import request from '@/utils/request'

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
