'use strict'

const { pruneSessions, THIRTY_DAYS } = require('./roam-location-retention.js')
const { roamCacheEpoch, invalidateRoamCache } = require('./roam-cache-epoch.js')
const { tileCursor } = require('./roam-tile-cursor.js')
const { exactSessionId } = require('./roam-recovery.js')
const PREFIX = 'roam_memory_v1'
const PLAYER_MEMORY_KINDS = ['tiles', 'reveals', 'recovery', 'sessions']

function isPlayerMemoryKey(storageKey) {
  const parts = typeof storageKey === 'string' ? storageKey.split(':') : []
  return parts.length === 3 && parts[0] === PREFIX
    && normalizePlayerId(parts[1]) !== '' && PLAYER_MEMORY_KINDS.indexOf(parts[2]) !== -1
}

function normalizePlayerId(playerId) {
  const value = String(playerId == null ? '' : playerId).trim()
  return value && value !== '0' ? value : ''
}

function createPlayerRoamMemory(playerId, storage) {
  const owner = normalizePlayerId(playerId)
  const target = storage || {}

  function key(kind) {
    return owner ? `${PREFIX}:${owner}:${kind}` : ''
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
      const result = readResult('tiles')
      if (!result.ok) throw new Error('迷雾记忆暂时无法读取')
      const state = result.value
      if (!state || typeof state !== 'object' || Array.isArray(state)) {
        return { tiles: {}, serverCursor: 0 }
      }
      const tiles = state.tiles && typeof state.tiles === 'object' && !Array.isArray(state.tiles)
        ? state.tiles : {}
      const cursor = tileCursor(state.serverCursor)
      const recoveryRead = readResult('recovery')
      if (!recoveryRead.ok) throw new Error('漫游恢复记录暂时无法读取')
      const recovery = recoveryRead.value
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
      // An old rounded cursor cannot be recovered: replay from zero while retaining local tiles.
      return { tiles: retained, serverCursor: cursor === null ? 0 : cursor }
    },
    writeTiles(tiles, serverCursor, serverRetentionAt) {
      const values = tiles && typeof tiles === 'object' && !Array.isArray(tiles) ? tiles : {}
      const cursor = tileCursor(serverCursor)
      if (cursor === null) return false
      const prior = readResult('tiles')
      if (!prior.ok) return false
      const previous = prior.value && typeof prior.value === 'object' && !Array.isArray(prior.value) ? prior.value : {}
      const retentionAt = {}
      Object.keys(values).forEach(tile => { if (previous.retentionAt && tile in previous.retentionAt) retentionAt[tile] = previous.retentionAt[tile] })
      Object.keys(values).forEach(tile => {
        if (serverRetentionAt && Number(serverRetentionAt[tile]) > 0) retentionAt[tile] = Number(serverRetentionAt[tile])
        else if (!(tile in retentionAt)) retentionAt[tile] = previous.tiles && tile in previous.tiles ? 0 : Date.now()
      })
      try {
        const serialized = JSON.stringify({ tiles: values, retentionAt, serverCursor: cursor })
        if (!write('tiles', JSON.parse(serialized))) return false
        const result = readResult('tiles')
        return result.ok && JSON.stringify(result.value) === serialized
      } catch (_) { return false }
    },
    readReveals() {
      const result = readResult('reveals')
      if (!result.ok) throw new Error('漫游轨迹暂时无法读取')
      const value = result.value
      if (!Array.isArray(value)) return []
      const retained = value.filter(point => Number(point && point.retentionAt) > Date.now() - THIRTY_DAYS)
      if (retained.length !== value.length && !write('reveals', retained)) throw new Error('过期定位点清理未能保存')
      return retained.map(({ retentionAt, ...point }) => point)
    },
    writeReveals(value) {
      const result = readResult('reveals')
      if (!result.ok) return false
      const previous = result.value
      const key = point => point && [point.lat, point.lng, point.r].join(':')
      const known = new Map((Array.isArray(previous) ? previous : []).map(point => [key(point), point.retentionAt || 0]))
      const points = (Array.isArray(value) ? value : []).map(point => ({ ...point,
        retentionAt: known.has(key(point)) ? known.get(key(point)) : Date.now() }))
      try {
        const serialized = JSON.stringify(points)
        if (!write('reveals', JSON.parse(serialized))) return false
        const saved = readResult('reveals')
        return saved.ok && JSON.stringify(saved.value) === serialized
      } catch (_) { return false }
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
      const recovery = readResult('recovery')
      if (!recovery.ok) return { ok: false, sessions: [] }
      const sessions = pruneSessions(value, recovery.value)
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

  // Validate recovery identity before deleting anything. A failed read is not proof that an intention is absent.
  const commandKeys = new Set()
  const recordLike = value => value && typeof value === 'object' && !Array.isArray(value)
  for (const storageKey of keys) {
    const match = typeof storageKey === 'string'
      && storageKey.match(/^cy-team-event-command-v1:([1-9]\d{0,18}):(TEAM|EVENT|SIGNUP):(.+)$/)
    if (!match || !exactSessionId(match[1])) continue
    const [, actor, domain, slot] = match
    const scoped = slot.startsWith('scope:')
    if (!scoped && exactSessionId(slot) !== slot) continue
    try {
      if (scoped) {
        const encoded = slot.slice(6)
        if (domain !== 'TEAM' || !encoded || encodeURIComponent(decodeURIComponent(encoded)) !== encoded) return false
      }
      if (typeof target.getStorageSync !== 'function') return false
      const saved = target.getStorageSync(storageKey)
      if (!recordLike(saved) || saved.version !== 1 || saved.actor !== actor || saved.domain !== domain
        || typeof saved.targetId !== 'string' || !exactSessionId(saved.targetId)
        || (!scoped && saved.targetId !== slot)) return false
      const command = saved.command
      if (!recordLike(command) || command.targetId !== saved.targetId || typeof command.action !== 'string'
        || typeof command.requestId !== 'string' || !/^[a-f0-9]{32}$/.test(command.requestId)) return false
      if (saved.result != null && (!recordLike(saved.result) || saved.result.requestId !== command.requestId
        || !['APPLIED', 'REJECTED', 'CANCELLED'].includes(saved.result.state))) return false
      commandKeys.add(storageKey)
    } catch (_) { return false }
  }

  for (let index = 0; index < keys.length; index += 1) {
    const storageKey = keys[index]
    // 核销 UNKNOWN 按原账号摘要隔离；退出账号不能变成再次 POST 的入口。
    // 同意补传台账只保留严格 cy_usage_pending_v1:<正整数账号> 键，重登沿用原请求 ID。
    const consentLedger = typeof storageKey === 'string' && storageKey.match(/^cy_usage_pending_v1:[1-9][0-9]*$/)
    // POI UNKNOWN has no coordinates/answer/photo payload; keep its original identity until confirmation.
    // The separate :photo cache is deliberately not matched.
    const poiPending = typeof storageKey === 'string'
      && storageKey.match(/^cy_roam_poi_checkin_pending:v1:([1-9]\d{0,18}):([1-9]\d{0,18})$/)
    const shareRevokePending = typeof storageKey === 'string'
      && storageKey.match(/^roam_share_revoke_pending:([1-9]\d{0,18})$/)
    if (isPlayerMemoryKey(storageKey) || /^verification_unknown_v1:[a-f0-9]{64}$/.test(storageKey) ||
      commandKeys.has(storageKey) ||
      (shareRevokePending && exactSessionId(shareRevokePending[1])) ||
      (poiPending && exactSessionId(poiPending[1]) && exactSessionId(poiPending[2])) ||
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
  bindPlayerRoamMemory,
  clearStoragePreservingPlayerRoamMemory,
  createPlayerRoamMemory,
  currentPlayerRoamMemory,
}
