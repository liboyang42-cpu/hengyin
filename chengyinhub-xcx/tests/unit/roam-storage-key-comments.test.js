'use strict'

// C:U-124:漫游本机记忆的真实 storage key 是 roam_memory_v1:<玩家ID>:<kind>(utils/roam-player-memory.js),
// 旧共享 key roam_sessions / roam_tiles 只作为迁移来源存在。页面注释不能再把旧 key 写成数据源。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { createPlayerRoamMemory } = require('../../utils/roam-player-memory.js')

const FILES = ['pages/roam/index.js', 'subpackageRoam/session/index.js', 'utils/roam-share-snapshot.js']

test('U-124: 注释引用的会话/迷雾格 key 与 roam-player-memory 实际 key 一致', () => {
  const store = {}
  const memory = createPlayerRoamMemory('7', { getStorageSync: (k) => store[k], setStorageSync: (k, v) => { store[k] = v } })
  memory.writeTiles({ a: 1 }, 1)
  assert.ok('roam_memory_v1:7:tiles' in store, '真实 key 变了就同步改注释')
  for (const file of FILES) {
    const src = fs.readFileSync(path.join(__dirname, '../..', file), 'utf8')
    assert.doesNotMatch(src, /\broam_sessions\b|\broam_tiles\b/, file + ' 仍把旧共享 key 写成数据源')
  }
})
