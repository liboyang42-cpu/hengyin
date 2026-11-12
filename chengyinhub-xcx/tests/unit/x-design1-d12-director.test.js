'use strict'
// x-design1 D12:俱乐部活动详情/导演台没有封面时整块封面区折叠
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const read = (p) => fs.readFileSync(path.resolve(__dirname, '../..', p), 'utf8')

test('D12:无封面时 ctd-head 上提,所有绝对定位都减去 --ctd-shift', () => {
  const css = read('pages/club/topic-detail/index.wxss')
  const tops = css.match(/top: calc\(\d+rpx - var\(--ctd-shift, 0rpx\)\)/g) || []
  assert.equal(tops.length, 6)
  assert.doesNotMatch(css, /ctd-head__cover-fallback|ctd-cover-ph/)
})

test('D12:无封面时避开透明导航(margin-top 取 chromeClearTop)', () => {
  assert.match(read('pages/club/topic-detail/index.wxml'), /margin-top:' \+ chromeClearTop/)
})
