const { distanceText } = require('../../utils/geo.js')

function isEnabled(features) {
  return !!features && features.finishNearbyRoute === true
}

function numericOrNaN(value) {
  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim() === '')) return NaN
  return Number(value)
}

function validCoordinates(latitude, longitude) {
  const lat = numericOrNaN(latitude), lng = numericOrNaN(longitude)
  return Number.isFinite(lat) && Math.abs(lat) <= 90 && Number.isFinite(lng) && Math.abs(lng) <= 180
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
  const distance = numericOrNaN(candidate.distance)
  const meta = [
    Number.isFinite(distance) && distance >= 0 ? distanceText(distance) : '',
    candidate.addressName || candidate.address || ''
  ].filter(Boolean).join(' · ')
  const amount = candidate.minAmout
  const priceText = amount == null || amount === '' ? '' : (Number(amount) > 0 ? ('¥' + Number(amount) + ' 起') : '免费')
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
