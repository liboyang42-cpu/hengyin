'use strict'
const { distM } = require('./roam-geo.js')
const { ROAM_MAX_ACCURACY_M, ROAM_MAX_SPEED_MPS } = require('./roam-geo-thresholds.js')
// Existing roam displacement/jitter/accuracy limits and server POI speed guard.
function acceptRoamLocation(loc, previous, now) {
  const lat = loc && loc.latitude, lng = loc && loc.longitude
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) return null
  if (loc.type != null && loc.type !== 'gcj02') return null
  if (loc.accuracy != null && (!Number.isFinite(loc.accuracy) || loc.accuracy < 0 || loc.accuracy > ROAM_MAX_ACCURACY_M)) return null
  if (!Number.isFinite(now)) return null
  const point = { lat, lng, ts: now }
  if (!previous) return { point, distance: 0 }
  const elapsed = now - previous.ts
  if (elapsed <= 0) return null
  const distance = distM(previous, point)
  if (!Number.isFinite(distance) || distance < 3 || distance > 120 || distance / (elapsed / 1000) > ROAM_MAX_SPEED_MPS) return null
  return { point, distance }
}
module.exports = { acceptRoamLocation }
