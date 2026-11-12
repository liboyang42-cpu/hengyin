'use strict'
// x-design1 D10:商家工作台收入区收紧、问候语最多两行
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const read = (p) => fs.readFileSync(path.resolve(__dirname, '../..', p), 'utf8')
const css = read('pages/merchant/index/index.wxss')
const rule = (sel) => css.match(new RegExp('\\n' + sel.replace(/[.]/g, '\\.') + ' \\{[^}]*\\}'))[0]

test('D10:收入区不再有 360rpx 最小高,底部只留一档区块间距', () => {
  const r = rule('.rv-revenue-zone')
  assert.doesNotMatch(r, /360rpx/)
  assert.match(r, /min-height: 0;/)
  assert.match(r, /padding-bottom: var\(--cy-space-5\)/)
})

test('D10:收入反馈条回到文档流(不绝对定位占 112rpx)', () => {
  const r = rule('.rv-revenue-feedback')
  assert.match(r, /position: static;/)
  assert.doesNotMatch(r, /position: absolute|min-height: 112rpx/)
})

test('D10:问候语去掉 ep1,最多两行加省略号', () => {
  assert.doesNotMatch(read('pages/merchant/index/index.wxml'), /class="rv-hero-line ep1"/)
  const r = rule('.rv-hero-line')
  assert.match(r, /-webkit-line-clamp: 2;/)
  assert.match(r, /overflow: hidden;/)
})
