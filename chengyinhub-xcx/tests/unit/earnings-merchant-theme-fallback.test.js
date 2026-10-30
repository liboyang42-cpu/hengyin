'use strict'
// 2026-10-30 走查(VPS 测试服实测):商家从项目主页、我的「我的收益」进账户收益没带 theme=merchant,整页落成玩家深色
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

test('账户收益:没带 theme 参数时按身份兜底,商家仍是浅色', () => {
  const js = fs.readFileSync(path.join(__dirname, '../../subpackageA/pages/assetcenter/earnings/index.js'), 'utf8')
  assert.match(js, /if \(\(options && options\.theme === 'merchant'\) \|\| roleGuard\.isMerchantView\(\)\) this\.setData\(\{ merchantView: true \}\)/)
})
