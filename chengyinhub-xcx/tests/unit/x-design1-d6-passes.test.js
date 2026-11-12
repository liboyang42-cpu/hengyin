'use strict'
// x-design1 D6:通行证页不再列「兑换码 暂不可兑换」「付费模板」等不可用行
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const read = (p) => fs.readFileSync(path.resolve(__dirname, '../..', p), 'utf8')
const wxml = read('subpackageMember/market/passes/index.wxml')

test('D6:没有兑换码 / 付费模板 / 暂不可 行', () => {
  assert.doesNotMatch(wxml, /兑换码|付费模板|暂不可兑换|暂不可购买|mk-gated|workshopReason/)
})

test('D6:主题库入口仍只在商店页 ready 且服务端开放(member)时出,orders/gifts 分支不串到商店页', () => {
  assert.match(wxml, /<block wx:if="\{\{tab === 'shop' && state === 'ready'\}\}">\s*<view class="mk-row mk-row--go" wx:if="\{\{member\}\}"/)
  assert.match(wxml, /<block wx:elif="\{\{tab === 'orders'\}\}">/)
})
