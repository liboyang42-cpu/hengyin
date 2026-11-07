'use strict'
// 第十轮 f3 j5 L1:俱乐部没填类型时,「俱乐部类型」格与头部元信息行曾拿城市顶替(设了上海就显示「上海」)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const js = fs.readFileSync(path.resolve(__dirname, '../../pages/club/detail/index.js'), 'utf8')

test('俱乐部类型不回落到城市', () => {
  const line = js.split('\n').find(l => /club\.clubTypeText = /.test(l))
  assert.ok(line)
  assert.doesNotMatch(line, /club\.city/)
  assert.match(line, /club\.clubType \|\| '城市探索'/)
})
