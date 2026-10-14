'use strict'
// Process-local cancellation only; clearing never undoes an accepted server write.
const epochs = new Map()
const listeners = new Map()
function roamCacheEpoch(owner) { return epochs.get(String(owner || '')) || 0 }
function invalidateRoamCache(owner) {
  const key = String(owner || '')
  epochs.set(key, roamCacheEpoch(key) + 1)
  ;(listeners.get(key) || new Set()).forEach(callback => callback())
}
function onRoamCacheInvalidated(owner, callback) {
  const key = String(owner || '')
  const bucket = listeners.get(key) || new Set()
  bucket.add(callback); listeners.set(key, bucket)
  return () => { bucket.delete(callback); if (!bucket.size) listeners.delete(key) }
}
module.exports = { roamCacheEpoch, invalidateRoamCache, onRoamCacheInvalidated }
