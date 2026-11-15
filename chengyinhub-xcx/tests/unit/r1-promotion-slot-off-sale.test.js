// R1 MKT-01:推广曝光位下架。服务端开关 merchant.promotionSlot.onSale 默认 false;
// 可开通列表不出现、点了也不下单;已有授予记录仍能用全量 tiers 取名。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

test('装修页可开通列表渲染 saleTiers,不再直接渲染全量 tiers', () => {
  const wxml = read('pages/merchant/decor/index.wxml')
  assert.match(wxml, /wx:for="\{\{saleTiers\}\}"/)
  assert.doesNotMatch(wxml, /wx:for="\{\{tiers\}\}"/)
})

test('默认下架:saleTiers 初值不含 promotion_slot,开关只认服务端 true', () => {
  const js = read('pages/merchant/decor/index.js')
  const fn = js.match(/function saleTiersOf\(tiers, promotionSlotOnSale\) \{[\s\S]*?\n\}/)[0]
  // eslint-disable-next-line no-new-func
  const saleTiersOf = new Function(fn + '; return saleTiersOf')()
  const tiers = [{ key: 'premium_template' }, { key: 'promotion_slot' }, { key: 'brand_home' }]
  assert.deepEqual(saleTiersOf(tiers, false).map(t => t.key), ['premium_template', 'brand_home'])
  assert.deepEqual(saleTiersOf(tiers, undefined).map(t => t.key), ['premium_template', 'brand_home'])
  assert.deepEqual(saleTiersOf(tiers, 'true').map(t => t.key), ['premium_template', 'brand_home'])
  assert.deepEqual(saleTiersOf(tiers, true).map(t => t.key), ['premium_template', 'promotion_slot', 'brand_home'])
  assert.match(js, /saleTiers: saleTiersOf\(TIERS, false\)/)
  assert.match(js, /res\.data\.promotionSlotOnSale/)
})

test('点推广曝光位在下架时直接提示,不进入下单流程', () => {
  const js = read('pages/merchant/decor/index.js')
  const tap = js.slice(js.indexOf('tapTier(e) {'))
  const guard = tap.indexOf("bizType === 'promotion_slot' && !this.data.promotionSlotOnSale")
  assert.ok(guard > 0 && guard < tap.indexOf('_initCommerceWorkflow'))
})
