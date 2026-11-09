'use strict'
// 第十轮 f4 第 5 项(f1 遗留):商家首页滚到底时项目卡下沿被页内底栏盖住一截。
// 底栏实高 = 上内边距 16rpx + 按钮 88rpx + 下内边距 16rpx + 安全区;垫底必须比它高出一档间距,
// 与玩家首页 .v3-scroll-pad 同口径(--cy-tabbar-h + --cy-space-6)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const read = (f) => fs.readFileSync(path.join(__dirname, '../..', f), 'utf8')

test('商家首页底部垫高 = 底栏占位 + 一档间距,不再与底栏等高', () => {
  const wxss = read('pages/merchant/index/index.wxss')
  const rule = wxss.match(/\.rv-scroll-pad\s*\{([^}]*)\}/)
  assert.ok(rule, '找不到 .rv-scroll-pad')
  assert.match(rule[1], /height:\s*calc\(var\(--cy-tabbar-h\)\s*\+\s*var\(--cy-space-6\)\)/)
  assert.doesNotMatch(rule[1], /120rpx/)
  assert.match(read('pages/index/index.wxss'), /\.v3-scroll-pad\s*\{\s*height:\s*calc\(var\(--cy-tabbar-h\)\s*\+\s*var\(--cy-space-6\)\)/)
})

test('垫高仍是首页滚动区的最后一个子节点', () => {
  const wxml = read('pages/merchant/index/index.wxml')
  assert.match(wxml, /<view class="rv-scroll-pad"><\/view>\s*<\/scroll-view>/)
})
