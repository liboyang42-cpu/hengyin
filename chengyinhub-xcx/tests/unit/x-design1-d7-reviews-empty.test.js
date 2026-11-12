'use strict'
// x-design1 D7:商家口碑空态用标准插画空态,不是孤零零一行字
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const read = (p) => fs.readFileSync(path.resolve(__dirname, '../..', p), 'utf8')
const wxml = read('pages/merchant/reviews/index.wxml')

test('D7:管理态整页空态带 kind="empty" 插画,不留空 sub', () => {
  const tag = wxml.match(/<cy-empty wx:elif="\{\{pageState === 'empty'\}\}"[\s\S]*?\/>/)[0]
  assert.match(tag, /kind="empty"/)
  assert.doesNotMatch(tag, /sub=""/)
})

test('D7:公开态没有评价时的提示也是同一个空态组件', () => {
  const note = wxml.match(/class="review-eligibility-note">[\s\S]*?<\/view>/)[0]
  assert.match(note, /<cy-empty size="lg" kind="empty" title=/)
  assert.doesNotMatch(note, /review-eligibility-(title|sub)|到店完成核销后才能评价/)
})
