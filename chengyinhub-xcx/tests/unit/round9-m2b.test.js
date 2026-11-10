'use strict'
// 第九轮 m2 补充项(root 复核 m1 后的追加)
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')
const read = (f) => fs.readFileSync(path.join(__dirname, '../..', f), 'utf8')

test('补充第 4 项:店铺分享卡用店铺照片铺满,右下是放大的店铺码,不再是一块空色', () => {
  // r3-share 改版:四种卡同一版式(class 带 kind),店铺照片铺满上半、店铺码仍比别的卡大
  const wxml = read('components/cy/share-card/index.wxml')
  assert.match(wxml, /<view class="sc-card sc-card--\{\{kind\}\}">/)
  assert.match(wxml, /<image class="sc-cover" wx:elif="\{\{cover\}\}" src="\{\{cover\}\}" mode="aspectFill" \/>/)
  assert.match(read('components/cy/share-card/index.wxss'), /\.sc-card--shop \.sc-qr \{ width: 128rpx; height: 128rpx; \}/)
  const js = read('components/cy/share-card/index.js')
  assert.match(js, /\} else if \(cover\) fit\(cover, 0, 0, W, TOP\);/)
  assert.match(js, /const qs = d\.kind === 'shop' \? 172 : 134;/)
  const profile = read('subpackageMember/components/cy/profile/index.js')
  assert.match(profile, /cover: String\(m\.coverImage \|\| \(this\.data\.merchantGallery \|\| \[\]\)\[0\] \|\| m\.logo \|\| m\.avatar \|\| ''\)/)
})

test('补充第 7 项:成长中心不再提供好友分享(收到的人看到的是他自己的页)', () => {
  const src = read('subpackageP3/pages/growthcenter/index/index.js').replace(/\/\/[^\n]*/g, '')
  assert.doesNotMatch(src, /onShareAppMessage\s*\(/)
  assert.doesNotMatch(src, /来城瘾看看我的城市探索成就/)
})
