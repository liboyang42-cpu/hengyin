'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')

const {
  bindPlayerRoamMemory,
  clearStoragePreservingPlayerRoamMemory,
  createPlayerRoamMemory,
} = require('../../utils/roam-player-memory.js')

function memoryStorage(initial) {
  const values = { ...(initial || {}) }
  return {
    getStorageSync(key) { return values[key] },
    setStorageSync(key, value) { values[key] = value },
    getStorageInfoSync() { return { keys: Object.keys(values) } },
    removeStorageSync(key) { delete values[key] },
    clearStorageSync() {
      Object.keys(values).forEach(key => { delete values[key] })
    },
    snapshot() { return JSON.parse(JSON.stringify(values)) },
  }
}

test('漫游探索记忆按玩家隔离，同设备切换账号不会串迷雾与历史', () => {
  const storage = memoryStorage({
    roam_tiles: { legacy: 1 },
    roam_reveals: [{ lat: 31.2, lng: 121.5 }],
    roam_sessions: [{ ts: 1 }],
  })
  const playerA = createPlayerRoamMemory('101', storage)
  const playerB = createPlayerRoamMemory('202', storage)

  playerA.writeTiles({ aTile: 1 }, 17)
  playerA.writeReveals([{ lat: 31.21, lng: 121.51 }])
  const finishedAt = Date.now()
  playerA.writeSessions([{ ts: 10101, finishedAt }])

  assert.deepEqual(playerA.readTiles(), { tiles: { aTile: 1 }, serverCursor: 17 })
  assert.deepEqual(playerA.readReveals(), [{ lat: 31.21, lng: 121.51 }])
  assert.deepEqual(playerA.readSessions(), [{ ts: 10101, finishedAt }])
  assert.deepEqual(playerB.readTiles(), { tiles: {}, serverCursor: 0 })
  assert.deepEqual(playerB.readReveals(), [])
  assert.deepEqual(playerB.readSessions(), [])

  const snapshot = storage.snapshot()
  assert.deepEqual(snapshot.roam_tiles, { legacy: 1 }, '不可信旧共享缓存不得认领给当前玩家')
  assert.ok(snapshot['roam_memory_v1:101:tiles'])
  assert.equal(snapshot['roam_memory_v1:202:tiles'], undefined)
})

test('缺少玩家身份时探索记忆读空且拒绝写入', () => {
  const storage = memoryStorage()
  const anonymous = createPlayerRoamMemory('', storage)

  assert.deepEqual(anonymous.readTiles(), { tiles: {}, serverCursor: 0 })
  assert.deepEqual(anonymous.readSessions(), [])
  assert.equal(anonymous.writeTiles({ shouldNotPersist: 1 }, 9), false)
  assert.deepEqual(storage.snapshot(), {})
})

test('页面生命周期绑定首次玩家，切号后旧页面不会改写新玩家记忆桶', () => {
  const storage = memoryStorage()
  let playerId = '101'
  const app = { getUserID: () => playerId }
  const page = {}

  const playerA = bindPlayerRoamMemory(page, app, storage)
  playerId = '202'
  const stillPlayerA = bindPlayerRoamMemory(page, app, storage)
  stillPlayerA.writeTiles({ aTile: 1 }, 3)

  assert.equal(stillPlayerA, playerA)
  assert.equal(stillPlayerA.playerId, '101')
  const storedTiles = storage.snapshot()['roam_memory_v1:101:tiles']
  assert.deepEqual(storedTiles.tiles, { aTile: 1 })
  assert.equal(storedTiles.serverCursor, 3)
  assert.ok(storedTiles.retentionAt.aTile > 0)
  assert.equal(storage.snapshot()['roam_memory_v1:202:tiles'], undefined)
})

test('首次登录前绑定的匿名页可在取得玩家身份后升级一次', () => {
  const storage = memoryStorage()
  let playerId = ''
  const app = { getUserID: () => playerId }
  const page = {}

  assert.equal(bindPlayerRoamMemory(page, app, storage).playerId, '')
  playerId = '101'
  const player = bindPlayerRoamMemory(page, app, storage)
  player.writeTiles({ firstLoginTile: 1 }, 1)

  assert.equal(player.playerId, '101')
  assert.ok(storage.snapshot()['roam_memory_v1:101:tiles'])
})

test('正常退出只清账号态，保留各玩家独立的漫游历史与恢复记录', () => {
  const storage = memoryStorage({
    authorization: 'local-auth',
    role: 'player',
    user_id: 101,
    'roam_memory_v1:101:sessions': [{ sessionId: '34', distance: 1.2 }],
    'roam_memory_v1:101:recovery': { sessionId: '34', elapsedSeconds: 25 },
    'roam_memory_v1:202:sessions': [{ sessionId: '52', distance: 0.8 }],
  })

  assert.equal(clearStoragePreservingPlayerRoamMemory(storage), true)

  const snapshot = storage.snapshot()
  assert.equal(snapshot.authorization, undefined, '正常退出不能保留本地凭据')
  assert.equal(snapshot.role, undefined)
  assert.equal(snapshot.user_id, undefined)
  assert.deepEqual(createPlayerRoamMemory('101', storage).readSessions(), [
    { sessionId: '34', distance: 1.2 },
  ])
  assert.deepEqual(createPlayerRoamMemory('101', storage).readRecoveryState().record, {
    sessionId: '34', elapsedSeconds: 25,
  })
  assert.deepEqual(createPlayerRoamMemory('202', storage).readSessions(), [
    { sessionId: '52', distance: 0.8 },
  ])
  assert.deepEqual(createPlayerRoamMemory('202', storage).readRecoveryState().record, null,
    '不同账号只能读取自己的玩家桶')
})

test('退出清理不读取或搬运漫游业务值，只删除非业务键', () => {
  const sessions = [{ sessionId: '34', distance: 1.2 }]
  const recovery = { sessionId: '34', elapsedSeconds: 25 }
  const values = {
    authorization: 'local-auth',
    role: 'player',
    'roam_memory_v1:101:sessions': sessions,
    'roam_memory_v1:101:recovery': recovery,
  }
  const calls = { reads: 0, writes: 0, clears: 0 }
  const storage = {
    getStorageInfoSync() { return { keys: Object.keys(values) } },
    getStorageSync() { calls.reads += 1; throw new Error('业务值不得读取搬运') },
    setStorageSync() { calls.writes += 1; throw new Error('业务值不得写回') },
    removeStorageSync(key) { delete values[key] },
    clearStorageSync() {
      calls.clears += 1
      Object.keys(values).forEach(key => { delete values[key] })
    },
  }

  assert.equal(clearStoragePreservingPlayerRoamMemory(storage), true)
  assert.deepEqual(calls, { reads: 0, writes: 0, clears: 0 })
  assert.equal(values.authorization, undefined)
  assert.equal(values.role, undefined)
  assert.equal(values['roam_memory_v1:101:sessions'], sessions)
  assert.equal(values['roam_memory_v1:101:recovery'], recovery)
})

test('存储键列表读取失败时失败关闭，不清任何值', () => {
  const values = {
    authorization: 'local-auth',
    'roam_memory_v1:101:recovery': { sessionId: '34', elapsedSeconds: 25 },
  }
  const before = { ...values }
  let clearCalls = 0
  const storage = {
    getStorageInfoSync() { throw new Error('storage unavailable') },
    removeStorageSync(key) { delete values[key] },
    clearStorageSync() {
      clearCalls += 1
      Object.keys(values).forEach(key => { delete values[key] })
    },
  }

  assert.equal(clearStoragePreservingPlayerRoamMemory(storage), false)
  assert.deepEqual(values, before)
  assert.equal(clearCalls, 0)
})

test('删除本地账号键失败时返回失败且绝不删除漫游桶', () => {
  const recovery = { sessionId: '34', elapsedSeconds: 25 }
  const values = {
    authorization: 'local-auth',
    'roam_memory_v1:101:recovery': recovery,
  }
  let clearCalls = 0
  const storage = {
    getStorageInfoSync() { return { keys: Object.keys(values) } },
    getStorageSync(key) { return values[key] },
    setStorageSync(key, value) { values[key] = value },
    removeStorageSync() { throw new Error('remove failed') },
    clearStorageSync() {
      clearCalls += 1
      Object.keys(values).forEach(key => { delete values[key] })
    },
  }

  assert.equal(clearStoragePreservingPlayerRoamMemory(storage), false)
  assert.equal(values.authorization, 'local-auth')
  assert.equal(values['roam_memory_v1:101:recovery'], recovery)
  assert.equal(clearCalls, 0)
})
