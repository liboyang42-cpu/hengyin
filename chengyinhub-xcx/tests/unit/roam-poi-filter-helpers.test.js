'use strict'

// BL-143:补 #37 utils/roam-poi-filter 纯函数单测(依 #180 TEST-GAPS.md),不改被测代码。
const test = require('node:test')
const assert = require('node:assert/strict')
const f = require('../../utils/roam-poi-filter.js')

test('BL-143 storageKey:按账号隔离,空/0/空白账号不给 key', () => {
  assert.equal(f.storageKey('42'), 'roam_filter_v1:42')
  assert.equal(f.storageKey(42), 'roam_filter_v1:42')
  assert.equal(f.storageKey(' 42 '), 'roam_filter_v1:42')
  assert.notEqual(f.storageKey('42'), f.storageKey('43'), '不同账号不得共用同一个 key')
  for (const v of [undefined, null, '', '   ', '0', 0]) {
    assert.equal(f.storageKey(v), '', `账号 ${JSON.stringify(v)} 不得落盘`)
  }
})

test('BL-143 storageKey 隔离贯穿读写:A 写入不影响 B,也不影响无账号读', () => {
  const box = {}
  const s = { getStorageSync: (k) => box[k], setStorageSync: (k, v) => { box[k] = v } }
  f.writeFilter('7', { radiusM: 1000, unvisitedOnly: true }, s)
  assert.deepEqual(Object.keys(box), ['roam_filter_v1:7'])
  assert.deepEqual(f.readFilter('8', s), { radiusM: 3000, unvisitedOnly: false })
  assert.deepEqual(f.readFilter('', s), { radiusM: 3000, unvisitedOnly: false })
})

test('BL-143 isVisitedPoi:只认 passed/done', () => {
  assert.equal(f.isVisitedPoi({ state: 'passed' }), true)
  assert.equal(f.isVisitedPoi({ state: 'done' }), true)
  for (const state of ['pendingRedeem', 'seen', 'locked', '', undefined, 'PASSED']) {
    assert.equal(f.isVisitedPoi({ state }), false, `state=${String(state)} 不算到过`)
  }
  assert.equal(f.isVisitedPoi(null), false)
  assert.equal(f.isVisitedPoi(undefined), false)
  assert.equal(f.isVisitedPoi({}), false)
})

// 已知缺口(不改被测代码,交 #37 处理):VISITED_STATES 是普通对象,原型键(toString/constructor)会被当成「到过」。
// 后端 state 不会出现这些值,风险极低;修法是 Object.create(null) 或 hasOwnProperty。
test('BL-143 isVisitedPoi:原型键不应算到过', { todo: '#37 VISITED_STATES 用了普通对象' }, () => {
  assert.equal(f.isVisitedPoi({ state: 'toString' }), false)
  assert.equal(f.isVisitedPoi({ state: 'constructor' }), false)
})

test('BL-143 radiusLabel:米转 km 文案', () => {
  assert.equal(f.radiusLabel(1000), '1 km')
  assert.equal(f.radiusLabel(3000), '3 km')
  assert.equal(f.radiusLabel(5000), '5 km')
  assert.equal(f.radiusLabel('5000'), '5 km')
  assert.equal(f.radiusLabel(1500), '1.5 km')
  assert.deepEqual(f.radiusTabs().map((t) => t.label), ['1 km', '3 km', '5 km'])
})
