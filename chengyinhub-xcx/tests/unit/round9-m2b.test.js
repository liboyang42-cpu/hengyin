'use strict'
// 第九轮 m2 补充项(root 复核 m1 后的追加)
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')
const read = (f) => fs.readFileSync(path.join(__dirname, '../..', f), 'utf8')

test('补充第 4 项:店铺分享卡用店铺照片铺满,右下是放大的店铺码,不再是一块空色', () => {
  const wxml = read('components/cy/share-card/index.wxml')
  assert.match(wxml, /class="sc-card sc-card--theme \{\{kind === 'shop' \? 'sc-card--shop' : ''\}\}" wx:elif="\{\{kind === 'theme' \|\| kind === 'shop'\}\}"/)
  assert.match(wxml, /<view class="sc-card sc-card--act" wx:if="\{\{kind === 'activity'\}\}">/)
  assert.match(read('components/cy/share-card/index.wxss'), /\.sc-card--shop \.sc-qr \{ width: 168rpx; height: 168rpx; \}/)
  const js = read('components/cy/share-card/index.js')
  assert.match(js, /if \(d\.kind === 'theme' \|\| d\.kind === 'shop'\) \{\s*if \(cover\) fit\(cover, cx, cy, cw, ch\);/)
  const profile = read('subpackageMember/components/cy/profile/index.js')
  assert.match(profile, /cover: String\(m\.coverImage \|\| \(this\.data\.merchantGallery \|\| \[\]\)\[0\] \|\| m\.logo \|\| m\.avatar \|\| ''\)/)
})
