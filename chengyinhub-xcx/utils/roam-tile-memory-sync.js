'use strict'

async function syncRoamTileMemory(options) {
  const config = options || {}
  const memory = config.memory
  const fetchPage = config.fetchPage
  const readPendingTiles = typeof config.readPendingTiles === 'function'
    ? config.readPendingTiles : function () { return {} }
  const onPage = typeof config.onPage === 'function' ? config.onPage : function () {}
  const pageSize = Number.isInteger(config.pageSize) && config.pageSize > 0 ? config.pageSize : 1000
  // C:U-123:每页都整块重写本机格子 = 写入量随页数平方增长。改为每 persistEvery 页落一次盘 + 最后一页必落;
  // 中途断开最多重拉 persistEvery-1 页(合并是幂等的),游标只在落盘成功后才前进到已写入的位置。
  const persistEvery = Number.isInteger(config.persistEvery) && config.persistEvery > 0 ? config.persistEvery : 5
  if (!memory || typeof memory.readTiles !== 'function' || typeof memory.writeTiles !== 'function') {
    throw new Error('缺少玩家迷雾记忆存储')
  }
  if (typeof fetchPage !== 'function') throw new Error('缺少迷雾记忆分页读取器')

  const epoch = typeof memory.cacheEpoch === 'function' ? memory.cacheEpoch() : null
  const assertCurrent = () => {
    if (typeof config.isCurrent === 'function' && !config.isCurrent()) throw new Error('漫游读取上下文已变化')
    if (epoch !== null && memory.cacheEpoch() !== epoch) throw new Error('漫游缓存已清理')
  }
  assertCurrent()
  const initial = memory.readTiles()
  let tiles = { ...((initial && initial.tiles) || {}) }
  let cursor = Number(initial && initial.serverCursor) || 0

  let unsaved = 0
  let retention = {}
  while (true) {
    const page = await fetchPage(cursor, pageSize)
    assertCurrent()
    if (!page || typeof page !== 'object' || !Array.isArray(page.tiles)) {
      throw new Error('迷雾记忆分页响应无效')
    }
    const next = Number(page.nextAfterId)
    if (!Number.isSafeInteger(next) || next < cursor) throw new Error('迷雾记忆游标无效')
    const latest = memory.readTiles()
    const pending = readPendingTiles()
    tiles = {
      ...((latest && latest.tiles) || {}),
      ...tiles,
      ...(pending && typeof pending === 'object' && !Array.isArray(pending) ? pending : {}),
    }
    page.tiles.forEach((tile) => {
      const key = String(tile == null ? '' : tile).trim()
      if (key) tiles[key] = 1
    })
    if (page.hasMore === true && next <= cursor) throw new Error('迷雾记忆游标没有推进')
    cursor = Math.max(next, Number(latest && latest.serverCursor) || 0)
    if (page.tileRetentionAt && typeof page.tileRetentionAt === 'object') Object.assign(retention, page.tileRetentionAt)
    unsaved += 1
    if (page.hasMore !== true || unsaved >= persistEvery) {
      if (!memory.writeTiles(tiles, cursor, retention)) throw new Error('迷雾记忆本地持久化失败')
      unsaved = 0
      retention = {}
    }
    onPage(page.tiles.slice(), { tiles, serverCursor: cursor })
    if (page.hasMore !== true) return { tiles, serverCursor: cursor }
  }
}

module.exports = { syncRoamTileMemory }
