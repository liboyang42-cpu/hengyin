const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

function page(get, refresh = get) {
  const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/finance/refund/index.vue'), 'utf8')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  const sandbox = { getRefundFulfillment: get, refreshRefundFulfillment: refresh, listRefund() {}, addRefund() {}, reviewRefund() {} }
  for (const match of script.matchAll(/^import (\w+) from .+$/gm)) sandbox[match[1]] = {}
  vm.runInNewContext(script.replace(/^import .+$/gm, '').replace('export default', 'globalThis.component ='), sandbox)
  const component = sandbox.component
  const instance = Object.assign(component.data(), { $store: { getters: { id: 'finance-A', token: 'test-session-A' } } })
  for (const [name, method] of Object.entries(component.methods)) instance[name] = method.bind(instance)
  return { instance, component }
}
function pending() { let resolve; let reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function receipt(id, state = 'APPROVED') { return { data: { refundId: id, holds: [{ registrationId: '99', refundId: id, state }] } } }

test('detail reads exact original Long ID as a string', async () => {
  const calls = []
  const { instance: p } = page(async id => { calls.push(id); return receipt(id) })
  await p.openDetail({ id: '9007199254740993', payoutStatus: 5 })
  assert.deepEqual(calls, ['9007199254740993'])
  assert.equal(p.fulfillment.refundId, '9007199254740993')
  assert.equal(p.detailRow.payoutStatus, 5)
})
test('late result from another refund cannot overwrite current detail', async () => {
  const a = pending(); const b = pending()
  const { instance: p } = page(id => id === '1' ? a.promise : b.promise)
  const first = p.openDetail({ id: '1' }); const second = p.openDetail({ id: '2' })
  b.resolve(receipt('2')); await second
  a.resolve(receipt('1')); await first
  assert.equal(p.fulfillment.refundId, '2')
})
test('account switch clears detail and rejects the previous session result', async () => {
  const response = pending(); const { instance: p, component } = page(() => response.promise)
  const loading = p.openDetail({ id: '1' })
  p.$store.getters.id = 'finance-B'
  component.watch['$store.getters.id'].call(p)
  response.resolve(receipt('1')); await loading
  assert.equal(p.fulfillment, null); assert.equal(p.detailRow, null); assert.equal(p.detailOpen, false)
})
test('a changed session token rejects a late authorized result', async () => {
  const response = pending(); const { instance: p, component } = page(() => response.promise)
  const loading = p.openDetail({ id: '1' }); p.$store.getters.token = 'test-session-B'
  component.watch['$store.getters.token'].call(p)
  response.resolve(receipt('1')); await loading
  assert.equal(p.fulfillment, null); assert.equal(p.fulfillmentLoading, false)
})
test('closing detail invalidates a late response', async () => {
  const response = pending(); const { instance: p, component } = page(() => response.promise)
  const loading = p.openDetail({ id: '1' }); p.detailOpen = false; component.watch.detailOpen.call(p, false)
  response.resolve(receipt('1')); await loading
  assert.equal(p.fulfillment, null); assert.equal(p.fulfillmentLoading, false)
})
test('failed refresh preserves confirmed cash fact and previous hold projection', async () => {
  let refreshes = 0
  const { instance: p } = page(async id => receipt(id, 'MANUAL_REVIEW_HELD'), async id => { assert.equal(id, '1'); refreshes++; throw Error('UNKNOWN') })
  await p.openDetail({ id: '1', payoutStatus: 1 })
  await p.loadFulfillment(true)
  assert.equal(refreshes, 1); assert.equal(p.detailRow.payoutStatus, 1)
  assert.equal(p.fulfillment.holds[0].state, 'MANUAL_REVIEW_HELD'); assert.equal(p.fulfillmentError, 'UNKNOWN')
})
test('wrong original ID receipt remains unknown rather than success', async () => {
  const { instance: p } = page(async () => receipt('2'))
  await p.openDetail({ id: '1', payoutStatus: 5 })
  assert.equal(p.fulfillment, null); assert.match(p.fulfillmentError, /回执不匹配/); assert.equal(p.detailRow.payoutStatus, 5)
})
test('unsafe numeric ID never makes a request', async () => {
  let calls = 0; const { instance: p } = page(async () => { calls++; return receipt('1') })
  await p.openDetail({ id: 9007199254740992 })
  assert.equal(calls, 0); assert.match(p.fulfillmentError, /精度无法确认/)
})
test('manual and rejected holds are never presented as original-channel receipt', () => {
  const { instance: p } = page(async () => receipt('1'))
  for (const state of ['MANUAL_REVIEW_HELD', 'REJECTED_HELD', 'RECONCILIATION_REQUIRED']) {
    assert.doesNotMatch(p.fulfillmentStateLabel(state), /到账/)
  }
  assert.match(p.fulfillmentStateLabel('SUCCEEDED'), /原路到账/)
  assert.match(p.fulfillmentStateLabel('FUTURE_STATE'), /未知/)
})
