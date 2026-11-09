'use strict'
// 第十轮 f3 j6 F9:我的订单里待支付(registrationStatus==1)的单还没付钱,金额行不能写「实付款」。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const wxml = fs.readFileSync(path.resolve(__dirname, '../../subpackageMember/components/cy/scene-member-order-history/index.wxml'), 'utf8')

test('待支付订单金额行写「待付款」,已付订单仍写「实付款」', () => {
  const line = wxml.split('\n').find(l => l.includes('orderbox_li_bot_left'))
  assert.ok(line)
  assert.match(line, /\{\{item\.registrationStatus==1 \? '待付款' : '实付款'\}\}：/)
  assert.doesNotMatch(line, />实付款：</)
})
