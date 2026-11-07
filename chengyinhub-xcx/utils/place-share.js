'use strict'

// 第九轮 m1 第 3 项:地点卡「分享」。落点沿用据点深链页(subpackageRoam/poi-detail,长期契约),
// 据点按 poiId、只有商家的按 merchantId。封面只用地点真照片,没有就用固定插画 —— 不让微信截地图(实时位置)。
const PLACE_PATH = '/subpackageRoam/poi-detail/index'
const { ROAM_SHARE_COVER } = require('./roam-menu-share.js')

function placeShare(kind, id, name, image) {
  const key = kind === 'poi' ? 'poiId' : (kind === 'merchant' ? 'merchantId' : '')
  const value = id == null ? '' : String(id)
  if (!key || !/^\d+$/.test(value)) return null
  return { title: name || '这个地点', path: PLACE_PATH + '?' + key + '=' + value, imageUrl: image || ROAM_SHARE_COVER }
}

function placeShareFromEvent(event) {
  const d = event && event.from === 'button' && event.target && event.target.dataset
  if (!d || d.shareType !== 'place') return null
  return placeShare(d.shareKind, d.shareId, d.shareName, d.shareImage)
}

module.exports = { placeShare, placeShareFromEvent, PLACE_PATH }
