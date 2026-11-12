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

// U08-02:只推荐实际还能报名的候选 —— 服务端给了结束时间且已过,就不是可报名候选(未给则按列表公开闸)
function parseServerTime(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}/.test(value)) return NaN
  return new Date(value.replace(' ', 'T') + (value.length === 16 ? ':00' : '') + '+08:00').getTime()
}

function stillOpen(item, now) {
  const end = parseServerTime(item && item.endDate)
  return !Number.isFinite(end) || end > now
}

function pickCandidate(rows, currentActivityId, currentTopicId, now) {
  const at = Number.isFinite(now) ? now : Date.now()
  const candidate = (rows || []).find((item) => item && item.id != null
    && (!currentActivityId || String(item.id) !== String(currentActivityId))
    && (!currentTopicId || String(item.topicId || '') !== String(currentTopicId))
    && stillOpen(item, at))
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

// 曝光与点击同一身份:impressionId 把一次点击连到那一次曝光;来源局与来源主题让推荐效果追到原局。
// 点击只是打开详情,不是报名成功 —— 报名事实仍由详情页原报名链产生。
function impressionSource(source, now) {
  const s = source || {}
  const pick = (v) => (v == null || v === '' ? null : String(v))
  const at = Number.isFinite(now) ? now : Date.now()
  return {
    impressionId: 'frr-' + at.toString(36) + '-' + Math.floor(Math.random() * 1e8).toString(36),
    sourceActivityId: pick(s.activityId), sourceTopicId: pick(s.topicId), sourceRunId: pick(s.runId)
  }
}

function trackProperties(card, mode) {
  const src = (card && card.source) || {}
  return { mode, placement: 'play_finish', impressionId: src.impressionId || null,
    sourceActivityId: src.sourceActivityId || null, sourceTopicId: src.sourceTopicId || null, sourceRunId: src.sourceRunId || null }
}

function openCandidate(card, dependencies) {
  if (!card || card.id == null || !dependencies) return false
  dependencies.track('finish_route_recommendation_click', {
    bizType: 'activity', bizId: card.id,
    properties: trackProperties(card, dependencies.mode)
  })
  dependencies.openScene('play-activity-detail', { id: card.id })
  return true
}

module.exports = { isEnabled, resolveOrigin, pickCandidate, openCandidate, impressionSource, trackProperties }
