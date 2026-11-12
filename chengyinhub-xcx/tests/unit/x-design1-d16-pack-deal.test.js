'use strict'
// x-design1 D16 的发牌节奏被 v2-b(动效 v2 时刻 2)取代:弹出间隔 90ms(弹簧)→ 落格间隔 60ms。
// 仍然锁:packOpen 由 (n-1)*DEAL_STAGGER + DEAL_FLIGHT 驱动,不提前拍落点。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const read = (p) => fs.readFileSync(path.resolve(__dirname, '../..', p), 'utf8')
const js = read('pages/play/index.js')
const num = (name) => Number(js.match(new RegExp('const ' + name + ' = (\\d+(?:\\.\\d+)?);'))[1])

test('v2-b:弹出间隔 90ms、落格间隔 60ms,分界 = 弹出到落格 1100ms', () => {
  assert.equal(num('DEAL_STAGGER'), 90)
  assert.equal(num('DEAL_LAND_STAGGER'), 60)
  assert.equal(Math.round(num('DEAL_FLIGHT') * num('DEAL_ARC')), 1100)
})

test('D16:发牌总时长仍由 (n-1)*DEAL_STAGGER + DEAL_FLIGHT 驱动 packOpen,不提前拍落点', () => {
  assert.match(js, /const done = \(n - 1\) \* DEAL_STAGGER \+ DEAL_FLIGHT;/)
})
