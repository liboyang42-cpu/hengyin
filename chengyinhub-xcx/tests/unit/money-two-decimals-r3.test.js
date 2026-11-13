'use strict'

// 打磨第 3 轮 §4-20:金额一律两位小数(我的资产、确认终价不再「¥0」与「¥0.00」并存);
// 我的队伍四格读数加载前:D-119(2026-11-11)起金额位写 ¥0.00(原为「—」)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

test('确认终价页金额全部走 money.amount2', () => {
  const wxml = read('pages/topic/pricing/index.wxml')
  assert.doesNotMatch(wxml, /money\.amount\(/)
  assert.match(wxml, /money\.amount2\(finalPrice\)/)
  assert.match(wxml, /money\.amount2\(sliderMax\)/)
  assert.match(read('pages/topic/pricing/index.js'), /'固定 · ¥' \+ money\(row\.fixedFee\)/)
})

test('money.amount2:0 → ¥0.00,整数补两位,空值 ¥0.00(D-119 业主「金额也写 0」)', () => {
  const src = read('utils/wxs/money.wxs')
  const mod = { exports: {} }
  new Function('module', src)(mod)
  assert.equal(mod.exports.amount2(0), '¥0.00')
  assert.equal(mod.exports.amount2(1200), '¥1200.00')
  assert.equal(mod.exports.amount2(null), '¥0.00')
})

test('我的队伍四格读数初值为 null(渲「—」)', () => {
  const js = read('subpackageRoam/my-teams/index.js')
  assert.match(js, /stats: \[\{ k: '已通过', v: null \}, \{ k: '审核中', v: null \}, \{ k: '带队', v: null \}, \{ k: '未通过', v: null \}\]/)
  assert.match(read('subpackageRoam/my-teams/index.wxml'), /\{\{num\.n\(item\.v\)\}\}/)
})
