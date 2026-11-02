// P-13(2026-11-02 走查修复):自由探索不带队,到开场时间直接进,不显示「队伍集合中 · 等队长开始行程」。
// 是否自由探索由服务端 team-progress 的 freeEntry 决定,客户端只照读,不自行猜玩法。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const js = fs.readFileSync(path.join(ROOT, 'pages/play/index.js'), 'utf8')
const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxml'), 'utf8')

test('集合屏在 freeEntry 时不显示', () => {
  const m = wxml.match(/<view class="leadgather" wx:if="\{\{([^}]*)\}\}">/)
  assert.ok(m, '找不到集合屏')
  assert.match(m[1], /!lead\.freeEntry/)
})

test('team-progress 的 freeEntry 只认服务端的 true', () => {
  assert.match(js, /freeEntry: d\.freeEntry === true/)
})
