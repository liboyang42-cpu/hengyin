'use strict'

// C:U-123:本机迷雾格容量上限 + 分页同步不再逐页整块重写。
const assert = require('node:assert/strict')
const test = require('node:test')
const { syncRoamTileMemory } = require('../../utils/roam-tile-memory-sync.js')
const { capTiles, MAX_LOCAL_TILES, createPlayerRoamMemory } = require('../../utils/roam-player-memory.js')

function countingMemory() {
  let state = { tiles: {}, serverCursor: 0 }
  const writes = []
  return {
    writes,
    readTiles() { return state },
    writeTiles(tiles, serverCursor, retentionAt) {
      writes.push({ size: Object.keys(tiles).length, serverCursor, retentionAt: { ...(retentionAt || {}) } })
      state = { tiles: { ...tiles }, serverCursor }
      return true
    },
    state() { return state },
  }
}

function pages(n) {
  return Array.from({ length: n }, (_, i) => ({
    tiles: ['t' + i], nextAfterId: i + 1, hasMore: i < n - 1, tileRetentionAt: { ['t' + i]: 1000 + i },
  }))
}

test('U-123: 12 页同步只落 3 次盘(每 5 页 + 最后一页),最终结果与逐页落盘一致', async () => {
  const memory = countingMemory()
  const queue = pages(12)
  const result = await syncRoamTileMemory({ memory, fetchPage: () => Promise.resolve(queue.shift()) })
  assert.deepEqual(memory.writes.map((w) => w.serverCursor), [5, 10, 12])
  assert.equal(Object.keys(result.tiles).length, 12)
  assert.deepEqual(memory.state(), result)
  assert.deepEqual(Object.keys(memory.writes[0].retentionAt), ['t0', 't1', 't2', 't3', 't4'], '未落盘页的服务端保留时间要累计带上')
})

test('U-123: 中途断网时已落盘游标停在上一个批次,重开从那里续传、不丢格子', async () => {
  const memory = countingMemory()
  const queue = pages(12)
  let served = 0
  await assert.rejects(() => syncRoamTileMemory({
    memory,
    fetchPage: () => (++served === 8 ? Promise.reject(new Error('offline')) : Promise.resolve(queue.shift())),
  }), /offline/)
  assert.equal(memory.state().serverCursor, 5)
  const rest = pages(12).slice(5)
  const result = await syncRoamTileMemory({ memory, fetchPage: () => Promise.resolve(rest.shift()) })
  assert.equal(Object.keys(result.tiles).length, 12)
  assert.equal(result.serverCursor, 12)
})

test('U-123: persistEvery=1 保持逐页落盘(调用方可选回旧行为)', async () => {
  const memory = countingMemory()
  const queue = pages(3)
  await syncRoamTileMemory({ memory, persistEvery: 1, fetchPage: () => Promise.resolve(queue.shift()) })
  assert.deepEqual(memory.writes.map((w) => w.serverCursor), [1, 2, 3])
})

test('U-123 capTiles: 超上限时保留进行中行程的格子,其余按落盘时间从新到旧', () => {
  const tiles = { a: 1, b: 1, c: 1, d: 1 }
  const at = { a: 10, b: 40, c: 30, d: 20 }
  const kept = capTiles(tiles, at, { allTiles: ['a'] }, 2)
  assert.deepEqual(Object.keys(kept.tiles).sort(), ['a', 'b'])
  assert.deepEqual(kept.retentionAt, { a: 10, b: 40 })
  assert.strictEqual(capTiles(tiles, at, null, 10).tiles, tiles, '未超上限原样返回')
  assert.deepEqual(Object.keys(capTiles(tiles, at, { allTiles: ['a'], finishedAt: 1 }, 1).tiles), ['b'], '已结束行程不享受保留')
})

test('U-123: writeTiles 落盘不超过 MAX_LOCAL_TILES,序列化体积留在单 key 1MB 内', () => {
  const store = {}
  const storage = { getStorageSync: (k) => store[k], setStorageSync: (k, v) => { store[k] = v } }
  const memory = createPlayerRoamMemory('42', storage)
  const tiles = {}
  for (let i = 0; i < MAX_LOCAL_TILES + 500; i++) tiles['w' + String(i).padStart(6, '0')] = 1
  assert.equal(memory.writeTiles(tiles, 9), true)
  const saved = store['roam_memory_v1:42:tiles']
  assert.equal(Object.keys(saved.tiles).length, MAX_LOCAL_TILES)
  assert.ok(JSON.stringify(saved).length < 1024 * 1024, 'bytes=' + JSON.stringify(saved).length)
})
