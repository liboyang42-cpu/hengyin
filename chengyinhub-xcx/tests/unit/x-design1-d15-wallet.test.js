'use strict'
// x-design1 D15:票夹无图票卡用统一主题兜底图,不是大插画
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const read = (p) => fs.readFileSync(path.resolve(__dirname, '../..', p), 'utf8')

test('D15:无图票卡位 = cy-cover-fb + 小路线插画,没有 280 的 cy-illus', () => {
  const wxml = read('subpackageMember/signup/index.wxml')
  assert.match(wxml, /wc__im wc__im--illus cy-cover-fb/)
  assert.doesNotMatch(wxml, /<cy-illus name="\{\{item\.wType/)
  assert.doesNotMatch(read('subpackageMember/signup/index.wxss'), /\.wc__im--illus \{[^}]*background/)
  assert.match(read('subpackageMember/signup/index.wxss'), /\.wc__im \.cy-cover-fb__art \{ width: 120rpx; height: 90rpx; \}/)
})
