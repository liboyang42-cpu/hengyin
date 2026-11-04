'use strict'
// 第十轮 f4 第 5 项(f1 遗留):我的订单列表把没挂主题的普通活动标成「城市定向」。
// productType 来自活动绑定的主题(cms_activity 本身没有这一列):2 自由探索、1 城市定向,空 = 普通活动。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')

const wxml = fs.readFileSync(path.join(__dirname,
  '../../subpackageMember/components/cy/scene-member-order-history/index.wxml'), 'utf8')

function activityBlock() {
  const start = wxml.indexOf('wx:if="{{item.cmsActivity!=null}}"')
  const end = wxml.indexOf('orderbox_li_con_info_name', start)
  assert.ok(start > 0 && end > start, '找不到场次订单卡片')
  return wxml.slice(start, end)
}

test('普通活动(productType 为空)不再标成城市定向', () => {
  const block = activityBlock()
  assert.match(block, /wx:elif="\{\{item\.cmsActivity\.productType == 1\}\}">\s*<text class="order-mode-tag">城市定向<\/text>/)
  assert.match(block, /wx:else>\s*<text class="order-mode-tag">活动<\/text>/)
  assert.doesNotMatch(block, /wx:else>\s*<text class="order-mode-tag">城市定向<\/text>/)
})

test('自由探索场次仍标自由探索', () => {
  assert.match(activityBlock(), /wx:if="\{\{item\.cmsActivity\.productType == 2\}\}">\s*<text class="order-mode-tag green">自由探索<\/text>/)
})
