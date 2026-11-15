import request from '@/utils/request'

// C3 频道活动:俱乐部频道里发出的活动,带实时人数(参加 = 已确认报名)
export function listChannelEvents(params) {
  return request({
    url: '/business/channel-event/list',
    method: 'get',
    params: params
  })
}

// 某场活动的相册照片(含审核中与已移除的,附审核预览链接)
export function listChannelEventPhotos(eventId) {
  return request({
    url: '/business/channel-event/' + eventId + '/photos',
    method: 'get'
  })
}

// 从相册移除一张照片
export function removeChannelEventPhoto(photoId) {
  return request({
    url: '/business/channel-event/photo/' + photoId + '/remove',
    method: 'post'
  })
}

// 恢复一张已移除的照片
export function restoreChannelEventPhoto(photoId) {
  return request({
    url: '/business/channel-event/photo/' + photoId + '/restore',
    method: 'post'
  })
}
