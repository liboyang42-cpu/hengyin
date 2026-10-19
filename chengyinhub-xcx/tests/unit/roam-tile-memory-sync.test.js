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

test('真实玩家桶初始读故障不能用空快照覆盖旧格子，恢复后从原游标继续', async () => {
  const { createPlayerRoamMemory } = require('../../utils/roam-player-memory.js')
  const key = 'roam_memory_v1:101:tiles'
  let failRead = true, writes = 0, fetches = 0
  const values = { [key]: { tiles: { localOnly: 1 }, serverCursor: 7 } }
  const memory = createPlayerRoamMemory('101', {
    getStorageSync(storageKey) { if (failRead) throw new Error('read unavailable'); return values[storageKey] },
    setStorageSync(storageKey, value) { writes++; values[storageKey] = JSON.parse(JSON.stringify(value)); failRead = false },
  })
  const run = () => syncRoamTileMemory({
    memory,
    fetchPage(cursor) { fetches++; return Promise.resolve({
      tiles: ['serverTile'], nextAfterId: cursor + 1, hasMore: false,
    }) },
  })
  await assert.rejects(run, /读取/)
  assert.equal(writes, 0)
  assert.equal(fetches, 0)
  assert.deepEqual(values[key], { tiles: { localOnly: 1 }, serverCursor: 7 })
  failRead = false
  assert.deepEqual(await run(), { tiles: { localOnly: 1, serverTile: 1 }, serverCursor: 8 })
})

test('真实玩家桶分页在途读故障不写盘、不推进游标，修复后从已确认页继续', async () => {
  const { createPlayerRoamMemory } = require('../../utils/roam-player-memory.js')
  const key = 'roam_memory_v1:101:tiles'
  let failRead = false, writes = 0
  const values = { [key]: { tiles: { old: 1 }, serverCursor: 7 } }
  const memory = createPlayerRoamMemory('101', {
    getStorageSync(storageKey) { if (failRead) throw new Error('read unavailable'); return values[storageKey] },
    setStorageSync(storageKey, value) { writes++; values[storageKey] = JSON.parse(JSON.stringify(value)) },
  })
  await assert.rejects(() => syncRoamTileMemory({
    memory,
    fetchPage() { failRead = true; return Promise.resolve({ tiles: ['server'], nextAfterId: 8, hasMore: false }) },
  }), /读取/)
  assert.equal(writes, 0)
  assert.deepEqual(values[key], { tiles: { old: 1 }, serverCursor: 7 })
})

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

test('真实桶分页写配额/静默丢写失败保留上一页，重试合并同批未落盘GPS格子', async () => {
  const { createPlayerRoamMemory } = require('../../utils/roam-player-memory.js')
  for (const fault of ['quota', 'drop']) {
    const values = {}, painted = [], calls = []
    let failWrite = false
    const memory = createPlayerRoamMemory('101', {
      getStorageSync: key => values[key],
      setStorageSync(key, value) {
        if (failWrite) { if (fault === 'quota') throw new Error('quota'); return }
        values[key] = JSON.parse(JSON.stringify(value))
      },
    })
    memory.writeTiles({ old: 1 }, 7)
    const run = () => syncRoamTileMemory({
      memory, readPendingTiles: () => ({ gps: 1 }),
      fetchPage(cursor) {
        calls.push(cursor)
        failWrite = cursor === 8 && calls.length <= 2
        return Promise.resolve({ tiles: [cursor === 7 ? 'first' : 'second'],
          nextAfterId: cursor + 1, hasMore: cursor === 7 })
      },
      onPage: page => painted.push(page),
    })
    await assert.rejects(run, /持久化失败/)
    assert.deepEqual(memory.readTiles(), { tiles: { old: 1, gps: 1, first: 1 }, serverCursor: 8 })
    assert.deepEqual(painted, [['first']])
    failWrite = false
    await run()
    assert.deepEqual(calls, [7, 8, 8])
    assert.deepEqual(memory.readTiles(), { tiles: { old: 1, gps: 1, first: 1, second: 1 }, serverCursor: 9 })
  }
})

test('重复旧页回包拒绝游标回退，不覆盖已确认的玩家桶', async () => {
  const memory = fakeMemory({ tiles: { old: 1 }, serverCursor: 7 })
  let calls = 0, painted = 0
  await assert.rejects(() => syncRoamTileMemory({
    memory,
    fetchPage: () => {
      calls++
      return Promise.resolve({ tiles: ['first'], nextAfterId: 8, hasMore: true })
    },
    onPage: () => { painted++ },
  }), /游标没有推进/)
  assert.equal(calls, 2)
  assert.equal(painted, 1)
  assert.deepEqual(memory.state(), { tiles: { old: 1, first: 1 }, serverCursor: 8 })
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
