'use strict'
// x-design1 D9:开场页主题标题最多两行加省略号,字号与其它开场页同量级
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const css = fs.readFileSync(path.resolve(__dirname, '../../pages/play/index.wxss'), 'utf8')

test('D9:.st-intro__t 56rpx、两行截断、带省略号', () => {
  const rule = css.match(/\.st-intro__t\{[^}]*\}/)[0]
  assert.match(rule, /font-size:56rpx/)
  assert.match(rule, /-webkit-line-clamp:2/)
  assert.match(rule, /overflow:hidden/)
  assert.match(rule, /text-overflow:ellipsis/)
  assert.doesNotMatch(rule, /font-size:80rpx/)
})
