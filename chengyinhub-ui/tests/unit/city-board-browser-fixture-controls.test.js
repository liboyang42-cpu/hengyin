/** Executable fixture negative controls only; never substitutes for a browser run. */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { EventEmitter } = require('node:events')
const { createRequire } = require('node:module')
const FIXTURE = path.resolve(__dirname, '../e2e/city-board/specs/fixture.js')

function loadFixture(options = {}) {
  let fixture
  const nativeRequire = createRequire(FIXTURE)
  const fakeExpect = actual => ({ toEqual: expected => assert.deepEqual(actual, expected) })
  const sandbox = { module: { exports: {} }, Buffer, URL, Set, WeakMap, Map, structuredClone,
    require: name => name === '@playwright/test'
      ? { test: { extend: definition => { fixture = definition.city; return {} } }, expect: options.expect || fakeExpect }
      : nativeRequire(name) }
  vm.runInNewContext(fs.readFileSync(FIXTURE, 'utf8'), sandbox, { filename: FIXTURE })
  return options.helpers ? sandbox.module.exports : fixture
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
    headers: () => options.headers || { authorization: 'Bearer synthetic-city-board-session-A-not-a-real-credential' }, postData: () => null
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
  { name: 'missing bearer', url: 'http://127.0.0.1:4173/prod-api/getInfo', resourceType: 'xhr', headers: {} },
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
  const route = requestRoute({ url: 'http://127.0.0.1:4173/prod-api/business/city-board-drafts/catalog', resourceType: 'xhr' })
  let pending
  await loadFixture()(h, async state => {
    const gate = state.holdNext('catalog')
    pending = h.context.handler(route)
    await gate.seen
    // Deliberately leave this request unresolved: fixture teardown owns it.
  }, h.info)
  await pending
  assert.deepEqual(route.calls.fetch, [])
  assert.deepEqual(route.calls.abort, ['blockedbyclient'])
  assert.equal(h.context.closed, true)
})

test('native select helpers require the exact accessible role and name, not wrapping label text', async () => {
  const helpers = loadFixture({ helpers: true })
  const labels = ['片区 / Region', '规则参考 / Rules reference', '任务 / Task', '骰子 / Dice policy',
    '挑战 / Challenge policy', '结算 / Settlement policy', '服务点 / Service points (最多 / maximum 50)']
  const selected = []
  const page = {
    getByLabel(name) {
      assert.ok(!labels.includes(name) && !(name instanceof RegExp && /reference|Task|policy/.test(name.source)), 'Selects must use their accessible role')
      return { fill: async () => {} }
    },
    getByRole(role, options) {
      assert.equal(options.exact, true)
      assert.ok(labels.includes(options.name))
      assert.equal(role, options.name.startsWith('服务点') ? 'listbox' : 'combobox')
      return { selectOption: async value => selected.push({ role, name: options.name, value }) }
    }
  }
  await helpers.fillDraft(page)
  assert.deepEqual(selected.map(item => item.name), labels)
  assert.deepEqual(selected.map(item => item.value), ['synthetic-region', 'synthetic-RULES', 'synthetic-TASK',
    'synthetic-DICE', 'synthetic-CHALLENGE', 'synthetic-SETTLEMENT', 'synthetic-place'])
})

test('history preparation waits for rendered Away and retired editor before clicking the canonical workbench tag', async () => {
  const calls = [], workbenchPath = '/platform/city-board-drafts', awayPath = '/city-board-test/away'
  let current, awayRendered = false, editorRetired = false
  const editorTitle = '城市棋盘草稿 · City board drafts', awayTitle = '暂时无法访问此页面'
  const link = (name, href) => ({ name, href, click: async () => {
    if (href === workbenchPath) assert.ok(awayRendered && editorRetired, 'URL alone is not page readiness')
    current = href; calls.push(['click', href])
  } })
  const away = link('无操作页面', awayPath), tag = link('城市棋盘草稿', workbenchPath)
  const page = {
    goto: async href => { current = href; calls.push(['goto', href]) },
    getByLabel: name => { assert.equal(name, '名称 / Name'); return { name } },
    getByRole(role, options) {
      if (role === 'heading') { assert.ok([editorTitle, awayTitle].includes(options.name)); assert.equal(options.exact, true); return { heading: options.name } }
      assert.equal(role, 'link'); assert.equal(options.name, away.name); assert.equal(options.exact, true); return away
    },
    locator(selector) {
      assert.equal(selector, '#tags-view-container')
      return { getByRole: (role, options) => { assert.equal(role, 'link'); assert.ok(options.name.test(tag.name)); return tag } }
    }
  }
  const expect = actual => ({
    toBeVisible: async () => {
      if (actual.heading) {
        if (actual.heading === awayTitle) { assert.equal(current, awayPath); awayRendered = true }
        calls.push(['visible', actual.heading])
      }
    }, toBeEnabled: async () => {},
    toHaveCount: async count => {
      if (actual === tag) assert.equal(count, 1)
      else { assert.equal(actual.heading, editorTitle); assert.equal(count, 0); assert.ok(awayRendered); editorRetired = true; calls.push(['absent', editorTitle]) }
    },
    toHaveAttribute: async (name, value) => { assert.equal(name, 'href'); assert.equal(actual.href, value) },
    toHaveURL: async value => { assert.equal(actual, page); assert.equal(value, 'http://127.0.0.1:4173' + current) }
  })
  await loadFixture({ helpers: true, expect }).prepareHistory(page)
  assert.deepEqual(calls, [['goto', workbenchPath], ['visible', editorTitle], ['click', awayPath],
    ['visible', awayTitle], ['absent', editorTitle], ['click', workbenchPath], ['visible', editorTitle]])
})

test('synthetic menu provides only Away and never shadows or aliases the real workbench route', async () => {
  const h = harness()
  const route = requestRoute({ url: 'http://127.0.0.1:4173/prod-api/getRouters', resourceType: 'xhr' })
  await loadFixture()(h, async () => h.context.handler(route), h.info)
  const routes = route.calls.fulfill[0].json.data
  assert.equal(routes.length, 1)
  assert.equal(routes[0].path, '/city-board-test')
  assert.equal(routes[0].children.length, 1)
  assert.equal(routes[0].children[0].path, 'away')
  assert.equal(routes[0].children[0].component, 'error/401')
  assert.equal(routes[0].children[0].redirect, undefined)
})
