'use strict'

const THIRTY_DAYS = 30 * 24 * 60 * 60 * 1000
const GEOMETRY = new Set(['lat', 'lng', 'latitude', 'longitude', 'lastLat', 'lastLng', 'lastReportAt',
  'track', 'polyline', 'coordinates', 'center', 'pois', 'foundPois', 'allTiles', 'pendingTiles'])

function stripGeometry(value) {
  if (Array.isArray(value)) return value.map(stripGeometry)
  if (!value || typeof value !== 'object') return value
  const result = {}
  Object.keys(value).forEach(key => { if (!GEOMETRY.has(key)) result[key] = stripGeometry(value[key]) })
  return result
}

function pruneSessions(sessions, recovery, now = Date.now()) {
  return sessions.map(row => {
    if (!row || typeof row !== 'object') return row
    // A lost finish reply must be checked against the server before expiring an active run.
    if (recovery && Number(recovery.archiveTs) > 0 && recovery.archiveTs === row.ts && !recovery.finishedAt) return row
    const time = Number(row.finishedAt || row.ts)
    if (time > 0 && time <= now && time > now - THIRTY_DAYS) return row
    const stripped = stripGeometry(row)
    return JSON.stringify(stripped) === JSON.stringify(row) ? row : { ...stripped, locationExpired: true }
  })
}

module.exports = { THIRTY_DAYS, stripGeometry, pruneSessions }
