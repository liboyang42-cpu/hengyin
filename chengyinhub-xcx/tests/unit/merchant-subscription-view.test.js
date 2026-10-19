const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const { buildActiveSubscriptions, formatDay } = require('../../pages/merchant/utils/merchant-subscription-view.js')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

const tiers = [
  { key: 'premium_template', name: '高级模板' },
  { key: 'promotion_slot', name: '推广曝光位' }
]

test('BL-108 权益名取已有 tiers,未知类型不外露代码', () => {
  const list = buildActiveSubscriptions([
    { id: 3, subscriptionType: 'premium_template', endDate: '2026-12-31 23:59:59', isActive: 1 },
    { id: 4, subscriptionType: 'city_node', endDate: null, maxUsage: 5, usedCount: 2, isActive: 1 }
  ], tiers)
  assert.deepEqual(list, [
    { key: '3', name: '高级模板', period: '有效期至 2026-12-31', usage: '' },
    { key: '4', name: '其他权益', period: '长期有效', usage: '已用 2/5 次' }
  ])
})

test('BL-108 空/异常返回不展示,停用行过滤', () => {
  assert.deepEqual(buildActiveSubscriptions(null, tiers), [])
  assert.deepEqual(buildActiveSubscriptions([{ id: 1, isActive: 0 }], tiers), [])
  assert.equal(formatDay('bad'), '')
  assert.equal(formatDay(''), '')
})

test('BL-108 开始日期还没到的预购行不列为已生效权益;已开始与无开始日期的照常展示', () => {
  const now = new Date(2026, 9, 19, 12).getTime()
  const rows = [
    { id: 1, subscriptionType: 'DECOR', isActive: 1, startDate: '2026-10-01 00:00:00', endDate: '2026-12-31 23:59:59' },
    { id: 2, subscriptionType: 'DECOR', isActive: 1, startDate: '2026-11-01', endDate: null },
    { id: 3, isActive: 1 },
  ]
  assert.deepEqual(buildActiveSubscriptions(rows, tiers, now).map((r) => r.key), ['1', '3'])
})

test('BL-108 装修页只读接线:不展示价格、不加购买入口', () => {
  const js = read('pages/merchant/decor/index.js')
  const wxml = read('pages/merchant/decor/index.wxml')
  assert.match(js, /url: '\/api\/merchant\/subscription', method: 'POST'/)
  assert.match(js, /activeSubs: \[\]/)
  assert.match(wxml, /wx:if="\{\{activeSubs\.length\}\}"/)
  const block = wxml.slice(wxml.indexOf('activeSubs.length'), wxml.indexOf('wx:for="{{tiers}}"'))
  assert.doesNotMatch(block, /bindtap|price|￥|¥/)
})
