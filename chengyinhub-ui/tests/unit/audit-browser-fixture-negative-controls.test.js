/** Executable fixture negative controls only; never substitutes for a browser run. */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { EventEmitter } = require('node:events')
const { createRequire } = require('node:module')
const FIXTURE = path.resolve(__dirname, '../e2e/audit/fixture.js')

function loadFixture() {
  let fixture
  const nativeRequire = createRequire(FIXTURE)
  const fakeExpect = actual => ({ toEqual: expected => assert.deepEqual(actual, expected) })
  const sandbox = { module: { exports: {} }, Buffer, URL, Set, WeakMap,
    require: name => name === '@playwright/test'
      ? { test: { extend: definition => { fixture = definition.audit; return {} } }, expect: fakeExpect }
      : nativeRequire(name) }
  vm.runInNewContext(fs.readFileSync(FIXTURE, 'utf8'), sandbox, { filename: FIXTURE })
  return fixture
}

function harness() {
  const page = new EventEmitter()
  page.isClosed = () => false
  const context = new EventEmitter()
  context.pages = () => [page]
  context.addCookies = async () => {}
  context.routeWebSocket = async (_, handler) => { context.socketHandler = handler }
  context.route = async (_, handler) => { context.handler = handler }
  context.close = async () => { context.closed = true }
  const attachments = []
  const info = { status: 'passed', expectedStatus: 'passed',
    attach: async (name, data) => attachments.push({ name, data }) }
  return { page, context, info, attachments }
}

function requestRoute(options = {}) {
  const calls = { fetch: [], fulfill: [], abort: [] }
  const request = {
    url: () => options.url || 'http://127.0.0.1:4173/static/js/app.123.js',
    method: () => options.method || 'GET', resourceType: () => options.resourceType || 'script',
    redirectedFrom: () => options.redirectedFrom || null, serviceWorker: () => options.serviceWorker || null,
    headers: () => options.headers || {}, postData: () => null
  }
  return { calls, request: () => request,
    fetch: async opts => { calls.fetch.push(opts); return { status: () => options.status || 200 } },
    fulfill: async result => { calls.fulfill.push(result) },
    abort: async reason => { calls.abort.push(reason) } }
}

for (const specimen of [
  { name: 'external document', url: 'https://synthetic.invalid/never-fetch', resourceType: 'document' },
  { name: 'static-looking fetch', resourceType: 'fetch' },
  { name: 'same-origin beacon', method: 'POST', resourceType: 'ping' },
  { name: 'redirected request', redirectedFrom: {} },
  { name: 'service-worker request', serviceWorker: {} },
  { name: 'unknown API', url: 'http://127.0.0.1:4173/prod-api/unknown', resourceType: 'xhr' },
  { name: 'non-synthetic bearer', url: 'http://127.0.0.1:4173/prod-api/getInfo', resourceType: 'xhr', headers: { authorization: 'Bearer rejected-placeholder-only' } }
]) {
  test(`fixture blocks ${specimen.name} before any fetch and fails the test`, async () => {
    const h = harness(); const route = requestRoute(specimen)
    await assert.rejects(loadFixture()(h, async () => h.context.handler(route), h.info))
    assert.deepEqual(route.calls.fetch, [])
    assert.deepEqual(route.calls.fulfill, [])
    assert.deepEqual(route.calls.abort, ['blockedbyclient'])
    assert.equal(h.context.closed, true)
    assert.equal(h.attachments.length, 1)
    assert.ok(JSON.parse(h.attachments[0].data.body).blocked.length > 0)
  })
}

test('allowed static fetch disables redirects and rejects 302 before fulfillment', async () => {
  const h = harness(); const route = requestRoute({ status: 302 })
  await assert.rejects(loadFixture()(h, async () => h.context.handler(route), h.info))
  assert.equal(route.calls.fetch.length, 1)
  assert.equal(route.calls.fetch[0].maxRedirects, 0)
  assert.equal(route.calls.fetch[0].timeout, 10000)
  assert.deepEqual(route.calls.fulfill, [])
  assert.deepEqual(route.calls.abort, ['blockedbyclient'])
})

test('allowed bundle response is fulfilled without route.continue or API forwarding', async () => {
  const h = harness(); const route = requestRoute()
  await loadFixture()(h, async () => h.context.handler(route), h.info)
  assert.equal(route.calls.fetch.length, 1)
  assert.equal(route.calls.fulfill.length, 1)
  assert.deepEqual(route.calls.abort, [])
  assert.equal(h.context.closed, true)
})

test('an unexpected WebSocket is closed and makes the fixture fail', async () => {
  const h = harness(); let closed = false
  await assert.rejects(loadFixture()(h, async () => {
    h.context.socketHandler({ url: () => 'wss://synthetic.invalid/socket', close: () => { closed = true } })
  }, h.info))
  assert.equal(closed, true)
  assert.equal(h.context.closed, true)
})

test('unexpected service worker event and popup JavaScript errors make the fixture fail', async () => {
  const h = harness()
  await assert.rejects(loadFixture()(h, async () => {
    h.context.emit('serviceworker', { url: () => 'http://127.0.0.1:4173/static/worker.js' })
    const popup = new EventEmitter(); h.context.emit('page', popup)
    popup.emit('pageerror', new Error('Synthetic popup failure'))
  }, h.info))
  const ledger = JSON.parse(h.attachments[0].data.body)
  assert.equal(ledger.blocked.length, 1)
  assert.equal(ledger.pageErrors.length, 1)
})

test('teardown releases held synthetic responses and completes pending route cleanup', async () => {
  const h = harness()
  const route = requestRoute({ url: 'http://127.0.0.1:4173/prod-api/business/audit/list', resourceType: 'xhr' })
  let pending
  await loadFixture()(h, async state => {
    const gate = state.holdNext('list')
    pending = h.context.handler(route)
    await gate.seen
    // Deliberately leave this request unresolved: fixture teardown owns it.
  }, h.info)
  await pending
  assert.deepEqual(route.calls.fetch, [])
  assert.deepEqual(route.calls.abort, ['blockedbyclient'])
  assert.equal(h.context.closed, true)
})

test('user-flow specs cannot reach Vue internals or fabricate cached component lifecycle', () => {
  for (const filename of ['audit-console.browser.test.js', 'candidate-preview.browser.test.js', 'fixture.js']) {
    const source = fs.readFileSync(path.resolve(__dirname, '../e2e/audit', filename), 'utf8')
    assert.doesNotMatch(source, /__vue__|__auditBrowserSubject|captureAuditInstance|addCachedView|\$router\.(?:push|replace)/)
  }
  const integration = fs.readFileSync(path.resolve(__dirname, '../e2e/audit/session-integration.js'), 'utf8')
  assert.match(integration, /Synthetic external-session integration only/)
  assert.doesNotMatch(integration, /addCachedView|_inactive|_uid|_isDestroyed/)
})
