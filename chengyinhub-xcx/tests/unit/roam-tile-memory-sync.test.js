'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')

const { syncRoamTileMemory } = require('../../utils/roam-tile-memory-sync.js')

function fakeMemory(initial) {
  let state = initial
  return {
    readTiles() { return state },
    writeTiles(tiles, serverCursor) {
      state = { tiles: { ...tiles }, serverCursor }
      return true
    },
    state() { return state },
  }
}

test('迷雾记忆从已保存游标增量拉取全部分页，并逐页持久化', async () => {
  const memory = fakeMemory({ tiles: { oldTile: 1 }, serverCursor: 10 })
  const calls = []
  const painted = []
  const pages = [
    { tiles: ['newTileA', 'newTileB'], nextAfterId: 12, hasMore: true },
    { tiles: ['newTileC'], nextAfterId: 13, hasMore: false },
  ]

  const result = await syncRoamTileMemory({
    memory,
    pageSize: 2,
    fetchPage(afterId, limit) {
      calls.push([afterId, limit])
      return Promise.resolve(pages.shift())
    },
    onPage(tiles) { painted.push(tiles) },
  })

  assert.deepEqual(calls, [[10, 2], [12, 2]])
  assert.deepEqual(painted, [['newTileA', 'newTileB'], ['newTileC']])
  assert.deepEqual(result, {
    tiles: { oldTile: 1, newTileA: 1, newTileB: 1, newTileC: 1 },
    serverCursor: 13,
  })
  assert.deepEqual(memory.state(), result)
})

test('服务端声称还有下一页却不推进游标时立即失败，不能死循环', async () => {
  const memory = fakeMemory({ tiles: {}, serverCursor: 7 })

  await assert.rejects(() => syncRoamTileMemory({
    memory,
    fetchPage: () => Promise.resolve({ tiles: ['same'], nextAfterId: 7, hasMore: true }),
  }), /游标没有推进/)
})

test('分页请求期间仅在页面内存新点亮、尚未延迟落盘的格子不会被旧快照覆盖', async () => {
  const memory = fakeMemory({ tiles: { oldTile: 1 }, serverCursor: 10 })
  let localTiles = { oldTile: 1 }

  const result = await syncRoamTileMemory({
    memory,
    readPendingTiles: () => localTiles,
    fetchPage() {
      localTiles = { oldTile: 1, gpsTile: 1 }
      return Promise.resolve({ tiles: ['serverTile'], nextAfterId: 11, hasMore: false })
    },
  })

  assert.deepEqual(result, {
    tiles: { oldTile: 1, gpsTile: 1, serverTile: 1 },
    serverCursor: 11,
  })
  assert.deepEqual(memory.state(), result)
})

test('C3清缓存代次改变后分页回包不能重新写旧快照或下一页', async () => {
  const memory = fakeMemory({ tiles: { oldTile: 1 }, serverCursor: 10 })
  let epoch = 0, resolve; memory.cacheEpoch = () => epoch
  const pending = syncRoamTileMemory({ memory, fetchPage: () => new Promise(r => { resolve = r }) })
  epoch += 1; memory.writeTiles({}, 0)
  resolve({ tiles: ['lateTile'], nextAfterId: 11, hasMore: true })
  await assert.rejects(pending, /缓存已清理/)
  assert.deepEqual(memory.state(), { tiles: {}, serverCursor: 0 })
})

const { createPlayerRoamMemory } = require('../../utils/roam-player-memory.js')
function durableMemory() {
  const values = new Map()
  let failRead = false, failWrite = false
  const storage = {
    getStorageSync(key) { if (failRead) throw new Error('storage read unavailable'); return values.get(key) },
    setStorageSync(key, value) { if (failWrite) throw new Error('storage full'); values.set(key, JSON.parse(JSON.stringify(value))) },
  }
  return { memory: createPlayerRoamMemory('7', storage), values,
    readFailure(v) { failRead = v }, writeFailure(v) { failWrite = v } }
}
test('real memory preserves exact Long cursor across pages, storage failure and reopen', async () => {
  const h = durableMemory(), calls = []
  h.memory.writeTiles({ old: 1 }, '9007199254740993')
  const fetchPage = async afterId => {
    calls.push(afterId)
    if (calls.length === 1) return { tiles: ['first'], nextAfterId: 9007199254740996, nextAfterIdExact: '9007199254740995', hasMore: true }
    h.writeFailure(true)
    return { tiles: ['second'], nextAfterIdExact: '9223372036854775807', hasMore: false }
  }
  await assert.rejects(syncRoamTileMemory({ memory: h.memory, fetchPage }), /持久化失败/)
  assert.deepEqual(calls, ['9007199254740993', '9007199254740995'])
  assert.equal(h.memory.readTiles().serverCursor, '9007199254740995')
  assert.deepEqual(h.memory.readTiles().tiles, { old: 1, first: 1 })
  h.writeFailure(false)
  const result = await syncRoamTileMemory({ memory: h.memory, fetchPage: async cursor => {
    assert.equal(cursor, '9007199254740995')
    return { tiles: ['second'], nextAfterIdExact: '9223372036854775807', hasMore: false }
  } })
  assert.deepEqual(result.tiles, { old: 1, first: 1, second: 1 })
  assert.equal(result.serverCursor, '9223372036854775807')
})
test('unreadable stored tiles never become empty or get overwritten by a subsequent write', async () => {
  const h = durableMemory(); h.memory.writeTiles({ retained: 1 }, 17)
  const saved = JSON.stringify([...h.values])
  h.readFailure(true)
  assert.throws(() => h.memory.readTiles(), /读取/)
  assert.equal(h.memory.writeTiles({ replacement: 1 }, 18), false)
  await assert.rejects(syncRoamTileMemory({ memory: h.memory, fetchPage: async () => { throw new Error('must not request') } }), /读取/)
  assert.equal(JSON.stringify([...h.values]), saved)
})
for (const page of [
  { nextAfterId: Number.MAX_SAFE_INTEGER + 1, hasMore: false },
  { nextAfterIdExact: '9223372036854775808', hasMore: false },
  { nextAfterIdExact: '01', hasMore: false },
  { nextAfterIdExact: null, nextAfterId: 18, hasMore: false },
  { nextAfterId: 18 }, { nextAfterId: 18, hasMore: 'false' },
]) test('malformed page cannot advance persisted cursor ' + JSON.stringify(page), async () => {
  const h = durableMemory(); h.memory.writeTiles({ old: 1 }, 17)
  await assert.rejects(syncRoamTileMemory({ memory: h.memory, fetchPage: async () => ({ tiles: ['new'], ...page }) }))
  assert.deepEqual(h.memory.readTiles(), { tiles: { old: 1 }, serverCursor: 17 })
})
