'use strict'

// Native marker IDs belong to the rendered map, never to a database ID range.
function createMarkerIds() {
  const ids = new Map()
  return (kind, key) => {
    const identity = kind + ':' + String(key)
    if (!ids.has(identity)) ids.set(identity, ids.size + 1)
    return ids.get(identity)
  }
}
module.exports = { createMarkerIds }
