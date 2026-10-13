const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const source = fs.readFileSync(
  path.join(__dirname, '../../src/views/business/order/index.vue'),
  'utf8'
)

test('旧订单菜单页只保留筛选分页 query 的兼容跳转', () => {
  assert.match(source, /name:\s*'LegacyOrderRedirect'/)
  assert.match(source, /name:\s*'financeOrder'/)
  assert.match(source, /query:\s*Object\.assign\(\{\}, query\)/)
  assert.doesNotMatch(source, /\b(deliverProduct|addOrder|delOrder)\b/)
})
