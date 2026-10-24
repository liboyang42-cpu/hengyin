const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const { marketView, deriveAuditState, deriveMarketState } = require('../../utils/template-market.js')
const { cases } = require('../fixtures/template-market-state-cases.json')

// U-046:服务端 TemplateMarketState 与这里的兜底推导共用同一份用例(Java 侧 TemplateMarketStateTest 读同一文件)
test('兜底推导与服务端枚举口径逐条一致', () => {
  for (const c of cases) {
    assert.equal(deriveAuditState(c.input), c.auditState, JSON.stringify(c.input))
    assert.equal(deriveMarketState(c.input), c.marketState, JSON.stringify(c.input))
  }
})

test('有服务端枚举时只按枚举渲染,与字段推导结果相同', () => {
  for (const c of cases) {
    const fromFields = marketView(c.input)
    const fromServer = marketView({ auditState: c.auditState, marketState: c.marketState })
    assert.deepEqual(fromServer, fromFields, JSON.stringify(c.input))
  }
})

test('服务端枚举优先于原始状态位', () => {
  const view = marketView({ draftStatus: 0, auditState: 'APPROVED', marketState: 'NONE' })
  assert.equal(view._statusText, '个人审核通过')
  assert.equal(view._canSubmitMarket, true)
})

test('字符串状态位与数字状态位等价', () => {
  for (const c of cases) {
    const asStrings = {}
    Object.keys(c.input).forEach((k) => { asStrings[k] = String(c.input[k]) })
    assert.equal(deriveAuditState(asStrings), c.auditState)
    assert.equal(deriveMarketState(asStrings), c.marketState)
  }
})
