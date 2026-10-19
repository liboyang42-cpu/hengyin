const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const read = relativePath => fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')

function loadDetail(dependencies) {
  const source = read('src/views/business/finance/order/detail.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ getOrder \} from '@\/api\/business\/order'$/m, 'const { getOrder } = dependencies;')
    .replace(/^import \{ getOrderPaymentFacts, queryOrderPaymentFacts \} from '@\/api\/business\/orderPaymentFacts'$/m,
      'const { getOrderPaymentFacts, queryOrderPaymentFacts } = dependencies;')
    .replace(/^import auth from '@\/plugins\/auth'$/m, 'const auth = dependencies.auth;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(Object.assign({
    getOrder: () => Promise.resolve({ data: {} }),
    getOrderPaymentFacts: () => Promise.resolve({ data: null }),
    queryOrderPaymentFacts: () => Promise.resolve({ data: null }),
    auth: { hasPermi: () => true }
  }, dependencies))
}

test('本地保存状态永远标注为未经渠道核验，现金只以渠道回执给出未确认/已确认/冲突', () => {
  const source = read('src/views/business/finance/order/detail.vue')
  assert.match(source, /本地保存状态（未经渠道核验）/)
  assert.match(source, /<h2>支付事实核对<\/h2>/)
  assert.match(source, /v-hasPermi="\['business:order:query'\]"/)
  const component = loadDetail({})
  assert.equal(component.methods.verdictLabel('UNCONFIRMED'), '未确认')
  assert.equal(component.methods.verdictLabel('CONFIRMED'), '已确认')
  assert.equal(component.methods.verdictLabel('CONFLICT'), '冲突')
  assert.equal(component.methods.verdictLabel(undefined), '未确认', '缺结论只能显示未确认')
  assert.match(component.methods.receiptLabel({ queryStatus: 'OK', channelTradeState: 'SUCCESS', channelTransactionId: '4200', channelTotalFen: 1990 }), /SUCCESS · 渠道交易号 4200 · 渠道金额 ¥19\.90/)
  assert.match(component.methods.receiptLabel({ queryStatus: 'FAILED', errorMessage: 'timeout' }), /查询失败/)
})

test('支付事实单独读取，失败不影响订单详情；向渠道查询后用回执刷新结论', async () => {
  const queried = []
  const component = loadDetail({
    getOrderPaymentFacts: () => Promise.reject(new Error('offline')),
    queryOrderPaymentFacts: id => {
      queried.push(id)
      return Promise.resolve({ data: { orderId: id, source: 'CASH', cash: { verdict: 'CONFLICT', lastQueryTime: '2026-10-12 10:00:00' } } })
    }
  })
  const vm = Object.assign(component.data(), {})
  await component.methods.loadPaymentFacts.call(vm, '9')
  assert.match(vm.paymentFactsError, /无法读取/)
  assert.equal(vm.detailError, false, '支付事实失败不把订单详情打成错误')

  vm.paymentFactsError = ''
  vm.paymentFacts = { orderId: 9, source: 'CASH', cash: { verdict: 'UNCONFIRMED' } }
  await component.methods.queryPaymentChannel.call(vm)
  assert.deepEqual(queried, [9])
  assert.equal(vm.paymentFacts.cash.verdict, 'CONFLICT')
  assert.equal(vm.paymentQuerying, false)
})

test('积分订单显示积分来源，不出现渠道查询入口', async () => {
  const component = loadDetail({
    getOrderPaymentFacts: () => Promise.resolve({ data: { orderId: 5, source: 'POINTS', points: { pointsPrice: 300, note: '按整单取整扣减' } } })
  })
  const vm = Object.assign(component.data(), {})
  await component.methods.loadPaymentFacts.call(vm, '5')
  assert.equal(component.computed.isPointsSource.call(vm), true)
  const source = read('src/views/business/finance/order/detail.vue')
  assert.match(source, /v-if="paymentFacts && paymentFacts\.source === 'CASH'"[\s\S]*?向渠道查询/)
})
