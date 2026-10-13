const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const source = fs.readFileSync(
  path.join(__dirname, '../../src/views/business/order/detail.vue'),
  'utf8'
)

test('旧订单详情深链将 query id 规范为 finance/order 的路径参数', () => {
  assert.match(source, /name:\s*'LegacyOrderDetailRedirect'/)
  assert.match(source, /delete targetQuery\.id/)
  assert.match(source, /name:\s*'financeOrderDetail'/)
  assert.match(source, /params:\s*\{ id: String\(id\) \}/)
})
