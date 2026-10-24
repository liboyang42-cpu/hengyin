'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { targetsForHandler, voiceConfirmedRequestPlan } = require('../../scripts/uiaudit/build-action-ledger')
const ROOT = path.resolve(__dirname, '../..')
// Requires the actual C f61 pages; never substitute a fabricated enrollment handler.
const PAGES = [
  ['pages/merchant/decor/ai-npc/index.js', '/api/merchant/npc/voice'],
  ['pages/topic/components/cy/node-npc-form/index.js', '/api/merchant/chapter-node/npc/voice'],
]

for (const [file, prefix] of PAGES) {
  test(`${file}: confirmed independent request includes actual POST enrollment and ordered consent`, () => {
    const targets = targetsForHandler(path.join(ROOT, file), 'confirmNewVoiceRequest')
    for (const url of [prefix + '/status', prefix + '/enroll']) {
      assert.ok(targets.apiContracts.some(contract => contract.url === url && contract.method === 'POST'
        && contract.wrapper === '_request'), url)
    }
    const plan = voiceConfirmedRequestPlan(file, 'confirmNewVoiceRequest', targets.apiContracts)
    assert.deepEqual(plan.writeApis, ['/api/compliance/consents', prefix + '/enroll'])
    assert.deepEqual(plan.requiredRequests.map(request => [request.urlTemplate, request.method, request.kind]), [
      [prefix + '/status', 'POST', 'read'], ['/api/compliance/consents', 'POST', 'write'],
      [prefix + '/enroll', 'POST', 'write'], [prefix + '/status', 'POST', 'read'],
    ])
    for (const branch of ['cancelled', 'consent-declined', 'original-no-longer-unknown', 'stale-after-read', 'blocked']) {
      assert.ok(!plan.requiredRequestsByBranch[branch].some(request => request.kind === 'write'), branch)
    }
    assert.deepEqual(plan.requiredRequestsByBranch.error.map(request => request.urlTemplate),
      [prefix + '/status', '/api/compliance/consents'])
    assert.deepEqual(plan.requiredRequestOutcomes.timeout, ['success', 'success', 'timeout', 'success'])
    assert.deepEqual(plan.requiredRequestsByBranch['consent-timeout'].map(request => request.urlTemplate),
      [prefix + '/status', '/api/compliance/consents'])
    assert.equal(plan.requiredRequestsByBranch['duplicate-trigger'].filter(request => request.urlTemplate === prefix + '/enroll').length, 1)
    for (const [branch, requests] of Object.entries(plan.requiredRequestsByBranch)) {
      assert.equal(plan.requiredRequestOutcomes[branch].length, requests.length, branch)
    }
    assert.throws(() => voiceConfirmedRequestPlan(file, 'confirmNewVoiceRequest', targets.apiContracts.concat({
      url: prefix + '/save-extra', method: 'POST', source: 'local-wrapper', wrapper: '_request',
    })), /未登记或未证明/)
  })

  test(`${file}: refresh and original history never become a new enrollment`, () => {
    for (const handler of ['refreshVoice', 'readVoiceHistory']) {
      const targets = targetsForHandler(path.join(ROOT, file), handler)
      assert.ok(targets.apiContracts.some(contract => contract.url === prefix + '/status' && contract.method === 'POST'))
      assert.ok(!targets.primaryApis.includes(prefix + '/enroll'))
      assert.equal(voiceConfirmedRequestPlan(file, handler, targets.apiContracts), null)
    }
  })
}

function fixture(wrapper, action, check, imported = '') {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cy-ledger-voice-request-'))
  try {
    fs.writeFileSync(path.join(dir, 'helper.js'), `function allowed(){return true;}
      function unused(){app.sendRequest({url:'/api/fixture/unrelated/save',method:'POST'});}
      module.exports={allowed,unused};`)
    const file = path.join(dir, 'page.js')
    fs.writeFileSync(file, `${imported}\nPage({ ${wrapper}, inspect(){ ${action} } });`)
    check(targetsForHandler(file, 'inspect'))
  } finally { fs.rmSync(dir, { recursive: true, force: true }) }
}
const wrapper = `_request(url,data){return app.sendRequest({url,method:'POST',data});}`

test('actual bound receiver wrapper retains named pure-helper precision', () => {
  fixture(wrapper, "if(allowed())return this._request('/api/fixture/chosen',{});", targets => {
    assert.deepEqual(targets.primaryApis, ['/api/fixture/chosen'])
    assert.ok(targets.apiContracts.some(contract => contract.url === '/api/fixture/chosen'
      && contract.method === 'POST' && contract.wrapper === '_request'))
  }, "const {allowed}=require('./helper.js');")
})

test('unknown named callback binding remains conservative with receiver wrapper parsing', () => {
  fixture(wrapper, "Promise.resolve().then(allowed);this._request('/api/fixture/chosen',{});", targets => {
    assert.ok(targets.primaryApis.includes('/api/fixture/unrelated/save'))
  }, "const {allowed}=require('./helper.js');")
})

test('nested url, reassignment and unresolved method do not prove a wrapper binding', () => {
  for (const unknown of [
    `_request(url,data){return app.sendRequest({url:'/api/fixture/actual',method:'POST',data:{url}});}`,
    `_request(url,data){url='/api/fixture/actual';return app.sendRequest({url,method:'POST',data});}`,
    `_request(url,data){return app.sendRequest({url,method:data.method,data});}`,
  ]) fixture(unknown, "this._request('/api/fixture/chosen',{});", targets => {
    assert.ok(!targets.apiContracts.some(contract => contract.url === '/api/fixture/chosen' && contract.wrapper === '_request'))
  })
})

test('missing proven enrollment binding cannot be papered over with a fixed write list', () => {
  const [file, prefix] = PAGES[0]
  assert.throws(() => voiceConfirmedRequestPlan(file, 'confirmNewVoiceRequest', [
    { url: prefix + '/status', method: 'POST', wrapper: '_request' },
    { url: prefix + '/enroll', method: 'POST', source: 'handler-default' },
    { url: '/api/compliance/consents', method: 'POST' },
  ]), /未登记或未证明/)
})

test('exact App bootstrap reads do not block voice flow or exempt extra business requests', () => {
  for (const [file, prefix] of PAGES) {
    const contracts = [
      { url: prefix + '/status', method: 'POST', source: 'server-controller', wrapper: '_request' },
      { url: prefix + '/enroll', method: 'POST', source: 'server-controller', wrapper: '_request' },
      { url: '/api/compliance/consents', method: 'POST', source: 'server-controller' },
      { url: '/api/config/features', method: 'POST', source: 'server-controller' },
      { url: '/api/login/code', method: 'POST', source: 'server-controller' },
    ]
    const plan = voiceConfirmedRequestPlan(file, 'confirmNewVoiceRequest', contracts)
    assert.deepEqual(plan.writeApis, ['/api/compliance/consents', prefix + '/enroll'])
    assert.deepEqual(plan.requiredRequests.map(request => request.urlTemplate),
      [prefix + '/status', '/api/compliance/consents', prefix + '/enroll', prefix + '/status'])
    for (const url of ['/api/config/features/save', '/api/login/code/save', prefix + '/save-extra']) {
      assert.throws(() => voiceConfirmedRequestPlan(file, 'confirmNewVoiceRequest',
        contracts.concat({ url, method: 'POST', source: 'server-controller' })), /未登记或未证明/, url)
    }
  }
})
