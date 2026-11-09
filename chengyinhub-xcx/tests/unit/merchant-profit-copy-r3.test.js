'use strict'

// 打磨第 3 轮 §4-13:M-14 未结算时不再把状态写两遍(徽章已说);M-15 删掉列表下的口径说明。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

test('M-14:fc-arrived-date 只在已结算时显示', () => {
  const wxml = read('subpackageA/components/scene-merchant-profit/index.wxml')
  assert.match(wxml, /<text class="fc-arrived-date" wx:if="\{\{item\.settled\}\}">\{\{item\.statusText\}\}<\/text>/)
  assert.match(wxml, /label="\{\{item\.settled \? '已结算' : item\.statusText\}\}"/)
})

test('M-15:不再有 fc-explainer 说明行', () => {
  assert.doesNotMatch(read('subpackageA/components/scene-merchant-profit/index.wxml'), /fc-explainer|已结算：金额已核算/)
  assert.doesNotMatch(read('subpackageA/components/scene-merchant-profit/index.wxss'), /\.fc-explainer/)
  assert.doesNotMatch(read('scripts/shot-matrix.js'), /fc-explainer/)
})
