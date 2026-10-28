'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { targetsForHandler } = require('../../scripts/uiaudit/build-action-ledger')
const ROOT = path.resolve(__dirname, '../..')
const targets = (file, handler) => targetsForHandler(path.join(ROOT, file), handler)
const writes = result => result.apiContracts.filter(api => api.method !== 'GET').map(api => api.url).sort()

function fixture(moduleSource, action, check, importSource = "const api = require('./helper.js');") {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cy-ledger-module-'))
  try {
    fs.writeFileSync(path.join(dir, 'helper.js'), moduleSource)
    const file = path.join(dir, 'page.js')
    fs.writeFileSync(file, `${importSource}\nPage({ act() { ${action} } });\n`)
    check(targetsForHandler(file, 'act'))
  } finally { fs.rmSync(dir, { recursive: true, force: true }) }
}

const simple = `function chosen() {
  app.sendRequest({url:'/api/fixture/chosen',method:'POST'});
}
function unrelated() {
  app.sendRequest({url:'/api/fixture/unrelated',method:'POST'});
}
module.exports = { chosen, unrelated };
`

test('real report namespaces and inline requires keep only their own write plus taxonomy read', () => {
  for (const [file, handler, write] of [
    ['pages/topic/index/index.js', 'reportTopic', '/api/topic/report'],
  ]) {
    const result = targets(file, handler)
    assert.deepEqual(writes(result), [write], handler)
    assert.ok(result.apiContracts.some(api => api.url === '/api/v1/community/capabilities' && api.method === 'GET'))
  }
})

test('namespace and inline calls do not import uncalled sibling writes', () => {
  for (const action of ['api.chosen();', "require('./helper.js').chosen();"]) {
    fixture(simple, action, result => assert.deepEqual(writes(result), ['/api/fixture/chosen']))
  }
})

test('local helper closure and true multi-write bodies retain every reachable write', () => {
  const source = `function shared() {
    app.sendRequest({url:'/api/fixture/shared',method:'POST'});
  }
  function chosen() {
    shared(); app.sendRequest({url:'/api/fixture/chosen',method:'POST'});
  }
  function unrelated() {
    app.sendRequest({url:'/api/fixture/unrelated',method:'POST'});
  }
  module.exports = { chosen, unrelated };
  `
  fixture(source, 'api.chosen();', result =>
    assert.deepEqual(writes(result), ['/api/fixture/chosen', '/api/fixture/shared']))
  fixture(source.replace('shared();', "if (value) shared(); else app.sendRequest({url:'/api/fixture/other-branch',method:'POST'});"),
    'api.chosen();', result =>
      assert.deepEqual(writes(result), ['/api/fixture/chosen', '/api/fixture/other-branch', '/api/fixture/shared']))
})

test('module constants, export aliases and callback aliases retain conservative full-module targets', () => {
  const cases = [
    simple.replace("function chosen() {", "const URL = '/api/fixture/global';\nfunction chosen() {"),
    simple.replace('module.exports = { chosen, unrelated };', 'module.exports = { chosen: unrelated, unrelated };'),
    simple.replace("app.sendRequest({url:'/api/fixture/chosen',method:'POST'});", "Promise.resolve().then(unrelated);"),
  ]
  for (const source of cases) fixture(source, 'api.chosen();', result => {
    assert.ok(writes(result).includes('/api/fixture/unrelated'))
  })
  fixture(simple, 'api.chosen(api.unrelated);', result =>
    assert.ok(writes(result).includes('/api/fixture/unrelated')))
  fixture(simple, 'api.unknown();', result =>
    assert.deepEqual(writes(result), ['/api/fixture/chosen', '/api/fixture/unrelated']))
})

test('named imports remain conservative until their export binding is proven', () => {
  fixture(simple, 'pick();', result =>
    assert.deepEqual(writes(result), ['/api/fixture/chosen', '/api/fixture/unrelated']),
  "const { chosen: pick } = require('./helper.js');")
})

test('new writes inside the selected method cannot be hidden by a fixed endpoint map', () => {
  fixture(simple.replace("app.sendRequest({url:'/api/fixture/chosen',method:'POST'});",
    "app.sendRequest({url:'/api/fixture/chosen',method:'POST'}); app.sendRequest({url:'/api/fixture/new-write',method:'POST'});"),
  'api.chosen();', result =>
    assert.deepEqual(writes(result), ['/api/fixture/chosen', '/api/fixture/new-write']))
})

test('commented and string-only inline requires do not manufacture write targets', () => {
  for (const action of ["// require('./helper.js').unrelated();\n", 'const sample = "require(\'./helper.js\').unrelated()";']) {
    fixture(simple, action, result => assert.deepEqual(result.primaryApis, []), '')
  }
})

test('complex real share module stays conservative and does not erase the newly found revoke write', () => {
  const result = targets('pages/roam/index.js', 'confirmClearRoamLocal')
  assert.ok(writes(result).includes('/api/roam/share/revoke'))
  assert.ok(writes(result).includes('/api/roam/share/snapshot'), 'unproven complex module keeps its full API envelope')
})


// Read the actual registry entry without rebuilding all controls or exposing test-only product exports.
function clearContract() {
  const file = path.join(ROOT, 'scripts/uiaudit/build-action-ledger.js')
  const source = fs.readFileSync(file, 'utf8')
  const ast = require('../../scripts/lib/xcx-scan').parseJavaScript(source)
  const declaration = ast.body.flatMap(node => node.declarations || []).find(node => node.id.name === 'ACTION_VARIANTS')
  const entry = declaration.init.arguments[0].elements.find(node => node && node.type === 'ArrayExpression'
    && node.elements[0].value === 'pages/roam/index.js:confirmClearRoamLocal')
  return require('node:vm').runInNewContext(source.slice(entry.start, entry.end))[1][0]
}

function clearHarness(reply, localOk = true) {
  const storage = new Map()
  const sent = []
  const app = { getUserID: () => '9', sendRequest(options) {
    sent.push({ urlTemplate: options.url, method: options.method, kind: 'write' })
    if (reply === 'fail') options.fail({ errMsg: 'request:fail timeout' })
    else options.success(reply)
  } }
  const wx = { getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: key => storage.delete(key) }
  let cleared = 0
  const method = require('../../scripts/uiaudit/build-action-ledger').handlerSlices(path.join(ROOT, 'pages/roam/index.js')).get('confirmClearRoamLocal')
  const config = require('node:vm').runInNewContext(`({${method}})`, {
    require: require('node:module').createRequire(path.join(ROOT, 'pages/roam/index.js')),
    getApp: () => app, wx, cyToast() {}, roamMemory: () => ({ clearLocal: () => { cleared++; return localOk } }),
  })
  const page = { data: { clearAsk: true }, setData(patch) { Object.assign(this.data, patch) } }
  return { sent, storage, page, cleared: () => cleared, run: () => config.confirmClearRoamLocal.call(page) }
}

function assertClearRequests(contract, branch, sent) {
  const expected = Object.prototype.hasOwnProperty.call(contract.requiredRequestsByBranch, branch)
    ? contract.requiredRequestsByBranch[branch] : contract.requiredRequests
  assert.equal(JSON.stringify(sent), JSON.stringify(expected), branch)
}

test('clear contract matches actual Page success, business refusal, network failure and local refusal readback', async () => {
  const contract = clearContract()
  assert.equal(contract.id, null)
  assert.deepEqual(Array.from(contract.apis), ['/api/roam/share/revoke'])
  for (const [branch, reply, localOk, result, pending] of [
    ['success', { code: 200 }, true, true, undefined],
    ['error', { code: 500 }, true, false, 1],
    ['timeout', 'fail', true, false, 1],
    ['clear-local-failed', { code: 200 }, false, false, undefined],
  ]) {
    const harness = clearHarness(reply, localOk)
    assert.equal(await harness.run(), result, branch)
    assertClearRequests(contract, branch, harness.sent)
    assert.equal(harness.cleared(), 1)
    assert.equal(harness.page.data.clearAsk, false)
    assert.equal(harness.storage.get('roam_share_revoke_pending:9'), pending, branch)
  }
})

test('clear repeated confirmation really sends two revokes and never publishes a snapshot', async () => {
  const harness = clearHarness({ code: 200 })
  assert.deepEqual(await Promise.all([harness.run(), harness.run()]), [true, true])
  assertClearRequests(clearContract(), 'duplicate-trigger', harness.sent)
  assert.equal(harness.cleared(), 2)
  assert.equal(harness.storage.has('roam_share_revoke_pending:9'), false)
})

test('actual revoke helper closure and contract reject an additional snapshot write', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cy-ledger-revoke-'))
  try {
    const file = path.join(dir, 'page.js')
    fs.writeFileSync(file, `const api = require(${JSON.stringify(path.join(ROOT, 'utils/roam-share-snapshot.js'))}); Page({act(){api.revokeShareSnapshots();}});`)
    const ledger = require('../../scripts/uiaudit/build-action-ledger')
    const closure = ledger.handlerSlices(file).get('api.revokeShareSnapshots')
    assert.ok(closure)
    assert.deepEqual(writes(ledger.extractTargets(closure)), ['/api/roam/share/revoke'])
    assert.deepEqual(writes(ledger.extractTargets(closure + "app.sendRequest({url:'/api/roam/share/snapshot',method:'POST'});")),
      ['/api/roam/share/revoke', '/api/roam/share/snapshot'])
    assert.throws(() => assertClearRequests(clearContract(), 'success', [
      { urlTemplate: '/api/roam/share/revoke', method: 'POST', kind: 'write' },
      { urlTemplate: '/api/roam/share/snapshot', method: 'POST', kind: 'write' },
    ]), /success/)
  } finally { fs.rmSync(dir, { recursive: true, force: true }) }
})
