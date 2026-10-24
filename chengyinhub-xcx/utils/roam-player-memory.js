'use strict'

const { pruneSessions, THIRTY_DAYS } = require('./roam-location-retention.js')
const { roamCacheEpoch, invalidateRoamCache } = require('./roam-cache-epoch.js')
const PREFIX = 'roam_memory_v1'
const PLAYER_MEMORY_KINDS = ['tiles', 'reveals', 'recovery', 'sessions']
// 本机迷雾格容量上限(C:U-123):每格约 38 字节(格键 + 落盘时间),2 万格 ≈ 760KB,
// 留在微信单 key 1MB 上限之内。超过上限时整次 setStorageSync 会失败、一格都存不下;
// 宁可先舍掉最早点亮的格子(服务端仍有全量),也不能让新点亮的格子全部写不进去。
const MAX_LOCAL_TILES = 20000

function isPlayerMemoryKey(storageKey) {
  const parts = typeof storageKey === 'string' ? storageKey.split(':') : []
  return parts.length === 3 && parts[0] === PREFIX
    && normalizePlayerId(parts[1]) !== '' && PLAYER_MEMORY_KINDS.indexOf(parts[2]) !== -1
}

function normalizePlayerId(playerId) {
  const value = String(playerId == null ? '' : playerId).trim()
  return value && value !== '0' ? value : ''
}

/** 超过 MAX_LOCAL_TILES 时保留进行中行程的格子,其余按落盘时间从新到旧保留。 */
function capTiles(tiles, retentionAt, recovery, limit) {
  const max = limit || MAX_LOCAL_TILES
  const keys = Object.keys(tiles)
  if (keys.length <= max) return { tiles, retentionAt }
  const active = new Set(recovery && !recovery.finishedAt && Array.isArray(recovery.allTiles) ? recovery.allTiles : [])
  const ranked = keys.slice().sort((a, b) => (active.has(b) - active.has(a)) || (Number(retentionAt[b]) || 0) - (Number(retentionAt[a]) || 0))
  const keptTiles = {}, keptAt = {}
  ranked.slice(0, max).forEach(tile => { keptTiles[tile] = tiles[tile]; keptAt[tile] = retentionAt[tile] })
  return { tiles: keptTiles, retentionAt: keptAt }
}

function createPlayerRoamMemory(playerId, storage) {
  const owner = normalizePlayerId(playerId)
  const target = storage || {}

  function key(kind) {
    return owner ? `${PREFIX}:${owner}:${kind}` : ''
  }

  function read(kind, fallback) {
    const result = readResult(kind)
    return result.ok && result.value != null && result.value !== '' ? result.value : fallback
  }

  function readResult(kind) {
    const storageKey = key(kind)
    if (!storageKey || typeof target.getStorageSync !== 'function') return { ok: true, value: undefined }
    try {
      return { ok: true, value: target.getStorageSync(storageKey) }
    } catch (_) {
      return { ok: false, value: undefined }
    }
  }

  function write(kind, value) {
    const storageKey = key(kind)
    if (!storageKey || typeof target.setStorageSync !== 'function') return false
    try {
      target.setStorageSync(storageKey, value)
      return true
    } catch (_) {
      return false
    }
  }

  return {
    playerId: owner,
    cacheEpoch() { return roamCacheEpoch(owner) },
    readTiles() {
      const state = read('tiles', null)
      if (!state || typeof state !== 'object' || Array.isArray(state)) {
        return { tiles: {}, serverCursor: 0 }
      }
      const tiles = state.tiles && typeof state.tiles === 'object' && !Array.isArray(state.tiles)
        ? state.tiles : {}
      const cursor = Number(state.serverCursor)
      const recovery = read('recovery', null)
      const active = recovery && !recovery.finishedAt && Array.isArray(recovery.allTiles) ? recovery.allTiles : []
      const retained = {}
      Object.keys(tiles).forEach(tile => {
        const at = Number(state.retentionAt && state.retentionAt[tile])
        if ((at > 0 && at > Date.now() - THIRTY_DAYS) || active.includes(tile)) retained[tile] = tiles[tile]
      })
      const keptAt = {}
      Object.keys(retained).forEach(tile => { keptAt[tile] = state.retentionAt && state.retentionAt[tile] || 0 })
      if (Object.keys(retained).length !== Object.keys(tiles).length && !write('tiles', { ...state, tiles: retained, retentionAt: keptAt }))
        throw new Error('过期迷雾位置清理未能保存')
      return { tiles: retained, serverCursor: Number.isSafeInteger(cursor) && cursor > 0 ? cursor : 0 }
    },
    writeTiles(tiles, serverCursor, serverRetentionAt) {
      const values = tiles && typeof tiles === 'object' && !Array.isArray(tiles) ? tiles : {}
      const cursor = Number(serverCursor)
      const previous = read('tiles', {})
      const retentionAt = {}
      Object.keys(values).forEach(tile => { if (previous.retentionAt && tile in previous.retentionAt) retentionAt[tile] = previous.retentionAt[tile] })
      Object.keys(values).forEach(tile => {
        if (serverRetentionAt && Number(serverRetentionAt[tile]) > 0) retentionAt[tile] = Number(serverRetentionAt[tile])
        else if (!(tile in retentionAt)) retentionAt[tile] = previous.tiles && tile in previous.tiles ? 0 : Date.now()
      })
      const capped = capTiles(values, retentionAt, read('recovery', null))
      try {
        const serialized = JSON.stringify({ tiles: capped.tiles, retentionAt: capped.retentionAt, serverCursor: Number.isSafeInteger(cursor) && cursor > 0 ? cursor : 0 })
        if (!write('tiles', JSON.parse(serialized))) return false
        const result = readResult('tiles')
        return result.ok && JSON.stringify(result.value) === serialized
      } catch (_) { return false }
    },
    readReveals() {
      const value = read('reveals', [])
      if (!Array.isArray(value)) return []
      const retained = value.filter(point => Number(point && point.retentionAt) > Date.now() - THIRTY_DAYS)
      if (retained.length !== value.length && !write('reveals', retained)) throw new Error('过期定位点清理未能保存')
      return retained.map(({ retentionAt, ...point }) => point)
    },
    writeReveals(value) {
      const previous = read('reveals', [])
      const key = point => point && [point.lat, point.lng, point.r].join(':')
      const known = new Map((Array.isArray(previous) ? previous : []).map(point => [key(point), point.retentionAt || 0]))
      const points = (Array.isArray(value) ? value : []).map(point => ({ ...point,
        retentionAt: known.has(key(point)) ? known.get(key(point)) : Date.now() }))
      return write('reveals', points)
    },
    readRecoveryState() {
      const result = readResult('recovery')
      return { ok: result.ok, record: result.value == null || result.value === '' ? null : result.value }
    },
    writeRecovery(value) {
      try {
        const serialized = JSON.stringify(value)
        if (!write('recovery', JSON.parse(serialized))) return false
        const result = readResult('recovery')
        return result.ok && JSON.stringify(result.value) === serialized
      } catch (_) { return false }
    },
    readSessions() {
      return this.readSessionState().sessions
    },
    readSessionState() {
      const result = readResult('sessions')
      if (!result.ok) return { ok: false, sessions: [] }
      const value = result.value
      if (value == null || value === '') return { ok: true, sessions: [] }
      if (!Array.isArray(value)) return { ok: false, sessions: [] }
      const sessions = pruneSessions(value, read('recovery', null))
      if (JSON.stringify(sessions) !== JSON.stringify(value) && !write('sessions', sessions))
        return { ok: false, sessions: [] }
      return { ok: true, sessions }
    },
    writeSessions(value) {
      try {
        const serialized = JSON.stringify(Array.isArray(value) ? value : [])
        if (!write('sessions', JSON.parse(serialized))) return false
        const result = readResult('sessions')
        return result.ok && JSON.stringify(result.value) === serialized
      } catch (_) { return false }
    },
    /* 清空本机缓存(原型 setScreen 的最后一行「不影响已上传的记录」)。
       只清这一个玩家桶里的四样本机数据 —— 服务端那份(roam_tile / roam_session / 邮票)
       一条都不动,所以那句「不影响已上传的记录」是真的。
       逐个 scope 写空值而不是 clearStorage:后者会把别的功能的本机数据一起端掉。
       ⚠️ recovery 必须一起清:确认框写着「会清掉本次记录」,而原来不清它 ⇒ 清完再进漫游
       仍弹「已找回本次漫游记录」,用户看到的和刚做过的操作自相矛盾。 */
    clearLocal() {
      invalidateRoamCache(owner)
      const done = [this.writeTiles({}, 0), this.writeReveals([]), this.writeSessions([]),
        this.writeRecovery(null)]
      return done.every(Boolean)
    },
  }
}

function currentPlayerRoamMemory(app, storage) {
  const playerId = app && typeof app.getUserID === 'function' ? app.getUserID() : ''
  return createPlayerRoamMemory(playerId, storage)
}

function bindPlayerRoamMemory(holder, app, storage) {
  if (!holder || typeof holder !== 'object') return currentPlayerRoamMemory(app, storage)
  const playerId = app && typeof app.getUserID === 'function' ? normalizePlayerId(app.getUserID()) : ''
  if (!holder._roamMemory || (!holder._roamMemory.playerId && playerId)) {
    holder._roamMemory = createPlayerRoamMemory(playerId, storage)
  }
  return holder._roamMemory
}

function clearStoragePreservingPlayerRoamMemory(storage) {
  const target = storage || {}
  if (typeof target.getStorageInfoSync !== 'function'
    || typeof target.removeStorageSync !== 'function') return false
  let keys
  try {
    const info = target.getStorageInfoSync()
    if (!info || !Array.isArray(info.keys)) return false
    keys = info.keys
  } catch (_) {
    return false
  }

  for (let index = 0; index < keys.length; index += 1) {
    const storageKey = keys[index]
    // 核销 UNKNOWN 按原账号摘要隔离；退出账号不能变成再次 POST 的入口。
    // 同意补传台账只保留严格 cy_usage_pending_v1:<正整数账号> 键，重登沿用原请求 ID。
    const consentLedger = typeof storageKey === 'string' && storageKey.match(/^cy_usage_pending_v1:[1-9][0-9]*$/)
    if (isPlayerMemoryKey(storageKey) || /^verification_unknown_v1:[a-f0-9]{64}$/.test(storageKey) ||
      (consentLedger && consentLedger[0] === storageKey)) continue
    try {
      target.removeStorageSync(storageKey)
    } catch (_) {
      return false
    }
  }
  return true
}

module.exports = {
  MAX_LOCAL_TILES,
  capTiles,
  bindPlayerRoamMemory,
  clearStoragePreservingPlayerRoamMemory,
  createPlayerRoamMemory,
  currentPlayerRoamMemory,
}
