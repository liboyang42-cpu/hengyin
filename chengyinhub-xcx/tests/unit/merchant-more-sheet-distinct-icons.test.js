'use strict'
// x-fix3 ME-2：商家工作台「更多」里退款售后/收款账户/管理优惠券曾共用同一钱包图标
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

test('更多 sheet 每个入口各有各的图标', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/merchant/index/index.wxml'), 'utf8')
  const rows = [...wxml.matchAll(/<view class="rv-more-item"[^>]*data-action="([a-zA-Z]+)"[\s\S]*?<\/view>/g)]
  const icon = {}
  for (const [block, action] of rows) {
    const m = block.match(/src="([^"]+)"/) || block.match(/<cy-icon[^>]*name="([^"]+)"/)
    if (m) icon[action] = m[1]
  }
  for (const a of ['aftercare', 'payoutAccount', 'coupon']) assert.ok(icon[a], a + ' 缺图标')
  assert.equal(new Set(Object.values(icon)).size, Object.values(icon).length, JSON.stringify(icon))
})
