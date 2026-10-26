const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/userInvoice/index.vue'), 'utf8')
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '').replace('export default', 'return')
function harness() {
  const calls = [], storage = new Map(), requests = []
  const sessionStorage = { getItem: k => storage.get(k), setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) }
  const document = { hidden: false, addEventListener() {}, removeEventListener() {} }
  let serial = 0
  const request = options => { calls.push(options); return new Promise((resolve, reject) => requests.push({ resolve, reject })) }
  const component = new Function('request', 'sessionStorage', 'document', 'crypto', script)(request, sessionStorage, document, { randomUUID: () => 'request-' + (++serial) })
  const p = component.data()
  p.$store = { getters: { id: '901' } }; p.$confirm = () => Promise.resolve()
  for (const [key, method] of Object.entries(component.methods)) p[key] = method.bind(p)
  p.owner = p.identity()
  p.detail = { id: '9223372036854775807', version: '2', status: 'PROCESSING', operationId: 'original-operation', orders: [] }
  return { p, calls, requests, storage, document, component, sessionStorage }
}
const tick = () => new Promise(resolve => setImmediate(resolve))

test('actual component freezes command on timeout; retry preserves IDs/version/input, storage has identity only', async () => {
  const h = harness(), p = h.p
  p.invoiceNo = 'CONFIRMED-REFERENCE'; p.evidenceRef = 'evidence:1'
  const action = p.act('RECORD_ISSUED'); await tick()
  const first = h.calls[0].data
  assert.equal(first.operationId, 'original-operation'); assert.equal(first.version, '2')
  h.requests[0].reject(Error('timeout')); await action
  assert.ok(p.pending); assert.ok(p.frozen)
  const stored = [...h.storage.values()].join('')
  assert.ok(!stored.includes('CONFIRMED-REFERENCE')); assert.ok(!stored.includes('evidence:1'))
  p.invoiceNo = 'CHANGED'; p.evidenceRef = 'evidence:changed'
  const retry = p.sendFrozen(); assert.deepEqual(h.calls[1].data, first)
  h.requests[1].reject(Error('timeout')); await retry
  assert.equal(p.pending.requestId, first.requestId)
})

test('negative query and wrong positive receipt retain original intent; matching receipt clears then reloads actual detail', async () => {
  const h = harness(), p = h.p
  p.pending = { id: p.detail.id, requestId: 'old-request', action: 'START' }
  h.storage.set(p.key(), JSON.stringify(p.pending))
  const q = p.recover(); h.requests[0].reject(Error('not found')); await q
  assert.equal(p.pending.requestId, 'old-request')
  const wrong = p.recover(); h.requests[1].resolve({ data: { id: p.detail.id, requestId: 'wrong', action: 'START' } }); await wrong
  assert.ok(p.pending)
  const correct = p.recover(); h.requests[2].resolve({ data: { ...p.pending, status: 'PROCESSING', version: '2' } }); await tick()
  assert.equal(p.pending, null); assert.equal(h.storage.size, 0)
  assert.match(h.calls[3].url, /9223372036854775807$/)
  h.requests[3].resolve({ data: { id: '9223372036854775807', status: 'PROCESSING' } }); await correct
  assert.equal(p.detail.status, 'PROCESSING')
})

test('tax response arriving after page hide is discarded and full tax is never persisted', async () => {
  const h = harness(), p = h.p
  const reveal = p.reveal(); h.document.hidden = true; p.hidden()
  h.requests[0].resolve({ data: { id: '9223372036854775807', taxNo: '913100001234567890' } }); await reveal
  assert.equal(p.taxNo, ''); assert.equal(h.storage.size, 0)
  assert.match(h.calls[0].url, /tax-number$/)
})

test('account change discards late financial response, leaves old-account recovery identity scoped', async () => {
  const h = harness(), p = h.p
  const action = p.act('UNKNOWN'); await tick()
  const oldPending = { ...p.pending }
  p.$store.getters.id = '902'; p.clearView(); p.owner = p.identity(); p.restore()
  h.requests[0].resolve({ data: { ...oldPending, status: 'ISSUE_UNKNOWN', version: '3' } }); await action
  assert.equal(p.detail, null); assert.equal(p.pending, null)
  assert.ok(h.storage.has('user-invoice-command:901')); assert.ok(!h.storage.has('user-invoice-command:902'))
})

test('remount restores only original request, cannot mutate or retry without frozen input', async () => {
  const h = harness(), p = h.p
  h.storage.set(p.key(), JSON.stringify({ id: p.detail.id, requestId: 'original', action: 'START' }))
  p.restore(); await p.act('START'); await p.sendFrozen()
  assert.equal(h.calls.length, 0); assert.equal(p.frozen, null)
  const query = p.recover(); h.requests[0].reject(Error('negative')); await query
  assert.equal(p.pending.requestId, 'original')
})

test('reconciliation uses dedicated endpoint', async () => {
  const h = harness(), p = h.p
  p.detail.status = 'PAUSED'; p.evidenceRef = 'evidence:manual'; p.outcome = 'NO_EXTERNAL_ISSUE'
  const action = p.act('RECONCILE'); await tick()
  assert.match(h.calls[0].url, /reconcile$/)
  assert.equal(h.calls[0].data.operationId, 'original-operation')
  h.requests[0].reject(Error('unknown')); await action
  assert.ok(p.pending)
})


test('storage failure and invalid evidence send no command or UNKNOWN record', async () => {
  const h = harness(), p = h.p
  p.evidenceRef = ''; await p.act('REJECT'); assert.equal(h.calls.length, 0); assert.equal(p.pending, null)
  p.evidenceRef = 'evidence:ok'; h.sessionStorage.setItem = () => { throw Error('storage unavailable') }
  await p.act('REJECT'); assert.equal(h.calls.length, 0); assert.equal(p.pending, null)
})


test('explicit close uses original identity and only clears after a committed matching fence', async () => {
  const h = harness(), p = h.p
  p.pending = { id: p.detail.id, requestId: 'late-original', action: 'START' }
  h.storage.set(p.key(), JSON.stringify(p.pending))
  const close = p.closeOriginal(); await tick()
  assert.match(h.calls[0].url, /requests\/late-original\/close$/)
  assert.deepEqual(h.calls[0].data, { action: 'START' }); assert.ok(p.pending)
  h.requests[0].resolve({ data: { ...p.pending, status: 'FENCED', version: '2' } }); await tick()
  h.requests[1].resolve({ data: { id: '9223372036854775807', status: 'PROCESSING', operationId: 'original-operation' } }); await close
  assert.equal(p.pending, null); assert.equal(p.detail.operationId, 'original-operation'); assert.match(p.error, /封存/)
})

test('invoice-owned manual refund refresh preserves pending command identity and discards tax', async () => {
  const h = harness(), p = h.p
  p.pending = { id: p.detail.id, requestId: 'pending-result', action: 'RECORD_ISSUED' }
  p.taxNo = '913100001234567890'
  const refresh = p.refreshRefundFacts()
  assert.match(h.calls[0].url, /refresh-refund-facts$/); assert.equal(p.taxNo, '')
  h.requests[0].resolve({ data: { id: '9223372036854775807', status: 'PAUSED', operationId: 'original-operation', reconciliationStatus: 'REQUIRED' } })
  await refresh
  assert.equal(p.detail.status, 'PAUSED'); assert.equal(p.pending.requestId, 'pending-result')
  assert.equal(p.detail.operationId, 'original-operation')
})
