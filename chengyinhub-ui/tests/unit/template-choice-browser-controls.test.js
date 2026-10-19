const test = require('node:test')
const assert = require('node:assert/strict')
const { classifyRequest } = require('../e2e/template-choice/network-policy')
const { classifyRequest: auditRequest } = require('../e2e/audit-network-policy')
const { validateWitness, validateReport, assertInvocation } = require('../e2e/template-choice/gate')
const titles = require('../e2e/template-choice/manifest')
const base = { url: 'http://127.0.0.1:4173/prod-api/business/membertemplate', method: 'PUT', resourceType: 'xhr' }
const runId = 'a'.repeat(32)
function witness() { return { schema: 1, runId, status: 'passed', discovered: 7, errors: [],
  begun: titles.map((title, i) => ({ id: `id-${i}`, title, file: 'editor.browser.test.js', retry: 0 })),
  ended: titles.map((_, i) => ({ id: `id-${i}`, retry: 0, status: 'passed', expectedStatus: 'passed' })) } }
function report() { return { errors: [], stats: { expected: 7, unexpected: 0, flaky: 0, skipped: 0 }, suites: [{ specs: titles.map((title, i) => ({ title, id: `id-${i}`, tests: [{ projectName: 'template-choice-chromium', expectedStatus: 'passed', results: [{ status: 'passed', retry: 0 }] }] })) }] } }

test('template additions allow only their explicit methods without widening audit43', () => {
  assert.equal(classifyRequest(base).kind, 'api')
  assert.equal(auditRequest(base).kind, 'block')
  for (const endpoint of ['/business/membertemplate/list', '/business/membertemplate/1801', '/system/category/list']) {
    assert.equal(classifyRequest({ ...base, method: 'GET', url: `http://127.0.0.1:4173/prod-api${endpoint}` }).kind, 'api')
  }
  for (const method of ['POST', 'DELETE', 'PATCH']) assert.equal(classifyRequest({ ...base, method }).kind, 'block')
})

test('network additions reject external origins, redirects, traversal, workers and wrong resource types', () => {
  for (const patch of [
    { url: 'https://example.com/prod-api/business/membertemplate' },
    { url: 'http://127.0.0.1:4174/prod-api/business/membertemplate' },
    { url: 'http://user@127.0.0.1:4173/prod-api/business/membertemplate' },
    { url: 'http://127.0.0.1:4173/prod-api/business/membertemplate#fragment' },
    { url: 'http://127.0.0.1:4173/prod-api/business/%2e%2e/membertemplate' },
    { redirectedFrom: true }, { serviceWorker: true }, { resourceType: 'document' }
  ]) assert.equal(classifyRequest({ ...base, ...patch }).kind, 'block')
})

test('browser gate rejects list, grep, alternate config and retry arguments', () => {
  assert.doesNotThrow(() => assertInvocation([]))
  for (const args of [['--list'], ['--grep', 'choice'], ['--config=x'], ['--retries=1']]) assert.throws(() => assertInvocation(args))
})

test('runtime witness requires all seven exact tests with no skips or retries', () => {
  assert.equal(validateWitness(witness(), runId).length, 7)
  for (const mutate of [
    w => { w.begun = [] }, w => { w.ended.pop() }, w => { w.ended[0].status = 'skipped' },
    w => { w.ended[0].expectedStatus = 'failed' }, w => { w.ended[0].retry = 1 },
    w => { w.begun[0].title = 'unrelated passing test' }, w => { w.begun[0].file = 'other.js' },
    w => { w.begun[1] = w.begun[0] }, w => { w.discovered = 8 }, w => { w.errors.push('crash') }
  ]) { const w = witness(); mutate(w); assert.throws(() => validateWitness(w, runId)) }
  assert.throws(() => validateWitness(witness(), 'b'.repeat(32)))
})

test('JSON gate independently rejects empty, skipped, retried and wrong-project reports', () => {
  assert.equal(validateReport(report()).executed, 7)
  for (const mutate of [
    r => { r.suites[0].specs = [] }, r => { r.stats.skipped = 1 },
    r => { r.suites[0].specs[0].tests[0].results.push({ status: 'passed', retry: 1 }) },
    r => { r.suites[0].specs[0].tests[0].projectName = 'audit-chromium' },
    r => { r.suites[0].specs[0].title = 'substitute' }, r => { r.errors.push({ message: 'crash' }) }
  ]) { const r = report(); mutate(r); assert.throws(() => validateReport(r)) }
})
