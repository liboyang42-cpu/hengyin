const { distanceText } = require('../../utils/geo.js')

function isEnabled(features) {
  return !!features && features.finishNearbyRoute === true
}

function validCoordinates(latitude, longitude) {
  return latitude != null && longitude != null && latitude !== '' && longitude !== ''
    && Number.isFinite(Number(latitude)) && Math.abs(Number(latitude)) <= 90
    && Number.isFinite(Number(longitude)) && Math.abs(Number(longitude)) <= 180
}

function resolveOrigin(location, nodes) {
  if (location && validCoordinates(location.latitude, location.longitude)) {
    return { latitude: Number(location.latitude), longitude: Number(location.longitude) }
  }
  const completed = (nodes || []).filter((node) => node && node.done && validCoordinates(node.lat, node.lng))
    .sort((a, b) => (b.doneAt || 0) - (a.doneAt || 0))
  const last = completed[0]
  return last ? { latitude: Number(last.lat), longitude: Number(last.lng) } : null
}

function pickCandidate(rows, currentActivityId, currentTopicId) {
  const candidate = (rows || []).find((item) => item && item.id != null
    && (!currentActivityId || String(item.id) !== String(currentActivityId))
    && (!currentTopicId || String(item.topicId || '') !== String(currentTopicId)))
  if (!candidate) return null
  const distance = candidate.distance == null || candidate.distance === '' ? NaN : Number(candidate.distance)
  const meta = [
    Number.isFinite(distance) && distance >= 0 ? '参考直线距离 ' + distanceText(distance) : '距离待确认',
    candidate.addressName || candidate.address || ''
  ].filter(Boolean).join(' · ')
  const amount = candidate.minAmout
  const price = amount == null || String(amount).trim() === '' ? NaN : Number(amount)
  const priceText = Number.isFinite(price) && price >= 0 ? ('展示价 ¥' + price + ' 起') : '价格待确认'
  return {
    id: candidate.id,
    title: candidate.name || '附近路线',
    cover: String(candidate.imgUrl || '').split(',')[0],
    meta,
    priceText
  }
}

function openCandidate(card, dependencies) {
  if (!card || card.id == null || !dependencies) return false
  dependencies.track('finish_route_recommendation_click', {
    bizType: 'activity', bizId: card.id,
    properties: { mode: dependencies.mode, placement: 'play_finish' }
  })
  dependencies.openScene('play-activity-detail', { id: card.id })
  return true
}

module.exports = { isEnabled, resolveOrigin, pickCandidate, openCandidate }
