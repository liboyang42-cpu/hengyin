/** Executable infrastructure controls only. Synthetic reports are NOT browser-run evidence. */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const http = require('node:http')
const { spawnSync } = require('node:child_process')
const { classifyRequest, ORIGIN } = require('../e2e/city-board/network-policy')
const { validateReport, validateScreenshots, SCREENSHOTS, assertInvocation, FILE_COUNTS } = require('../e2e/city-board/report-validator')
const { validateWitness } = require('../e2e/city-board/gate-reporter')
const { resolveRequest, createRequestHandler, HOST, PORT } = require('../e2e/city-board/static-server')
const { readRegular, inventory, verifyReceipt, inputs, digest, RECEIPT } = require('../e2e/city-board/bundle-provenance')
const UI = path.resolve(__dirname, '../..')
const START = Date.parse('2026-01-01T00:00:00.000Z')
const NOW = START + 10000
const RUN_ID = 'a'.repeat(32)
const SYNTHETIC_EVIDENCE = fs.mkdtempSync(path.join(os.tmpdir(), 'audit-report-unit-controls-'))
// A one-pixel unit fixture exercises file validation, never browser screenshots.
for (const name of SCREENSHOTS) fs.writeFileSync(path.join(SYNTHETIC_EVIDENCE, `${name}.png`), Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aOc0AAAAASUVORK5CYII=', 'base64'))
test.after(() => fs.rmSync(SYNTHETIC_EVIDENCE, { recursive: true, force: true }))
function syntheticReport() {
  let id = 0
  const suites = Object.entries(FILE_COUNTS).map(([file, count]) => ({ title: file, file, specs: Array.from({ length: count }, () => {
    id++
    return { id: `synthetic-unit-control-${id}`, file, title: `${id <= 2 ? '[browser-integration] ' : ''}unit control ${id}`, ok: true, tags: id <= 2 ? ['integration'] : [], tests: [{
      expectedStatus: 'passed', projectName: 'city-board-chromium', projectId: 'city-board-chromium', status: 'expected', annotations: [], results: [{ status: 'passed', retry: 0,
        workerIndex: 0, parallelIndex: 0, duration: 1, errors: [], startTime: new Date(START + id).toISOString(),
        attachments: [...(id <= 2 ? [{ name: SCREENSHOTS[id - 1], contentType: 'image/png', path: path.join(SYNTHETIC_EVIDENCE, `${SCREENSHOTS[id - 1]}.png`) }] : []), { name: 'synthetic-network-ledger', contentType: 'application/json', body: Buffer.from(JSON.stringify({ calls: [{ syntheticUnitControl: true }], blocked: [], pageErrors: [] })).toString('base64') }]
      }] }] }
  }) }))
  return { config: { version: '1.62.0', forbidOnly: true, workers: 1, fullyParallel: false, failOnFlakyTests: true, globalTimeout: 8 * 60 * 1000, shard: null,
    configFile: path.join(UI, 'tests/e2e/city-board/playwright.config.js'), rootDir: path.join(UI, 'tests/e2e/city-board/specs'), grep: {}, grepInvert: null, maxFailures: 0,
    argv: ['node', 'cli.js', 'test', '--config=./tests/e2e/city-board/playwright.config.js'], projects: [{ name: 'city-board-chromium', id: 'city-board-chromium', retries: 0, repeatEach: 1,
      testDir: path.join(UI, 'tests/e2e/city-board/specs'), testMatch: ['**/*.browser.test.js'], testIgnore: [] }] }, suites, errors: [],
    stats: { expected: 12, skipped: 0, unexpected: 0, flaky: 0, startTime: new Date(START).toISOString(), duration: 1000 } }
}
const validate = report => validateReport(report, { startedAt: START, now: NOW, uiRoot: UI, evidenceRoot: SYNTHETIC_EVIDENCE })
const first = report => report.suites[0].specs[0].tests[0]
const controls = {
  discovery: report => { for (const suite of report.suites) for (const spec of suite.specs) { spec.tests[0].results = []; spec.tests[0].status = 'skipped' } report.stats.expected = 0; report.stats.skipped = 12 },
  'zero specs': report => { report.suites = [] },
  subset: report => { report.suites[0].specs.pop(); report.stats.expected-- },
  skip: report => { first(report).results[0].status = 'skipped' },
  fixme: report => { first(report).annotations.push({ type: 'fixme' }) },
  focus: report => { report.config.forbidOnly = false },
  'focused annotation': report => { first(report).annotations.push({ type: 'only' }) },
  retries: report => { first(report).results.push({ ...first(report).results[0], retry: 1 }) },
  'retry config': report => { report.config.projects[0].retries = 1 },
  'repeat config': report => { report.config.projects[0].repeatEach = 2 },
  'expected failure': report => { first(report).expectedStatus = 'failed' },
  flaky: report => { first(report).status = 'flaky'; report.stats.flaky = 1 },
  interrupted: report => { first(report).results[0].status = 'interrupted' },
  'global error': report => { report.errors.push({ message: 'failed setup' }) },
  'test error': report => { first(report).results[0].errors.push({ message: 'application error' }) },
  'stale report': report => { report.stats.startTime = new Date(START - 1).toISOString() },
  'stale result': report => { first(report).results[0].startTime = new Date(START - 1).toISOString() },
  'unexecuted worker': report => { first(report).results[0].workerIndex = -1 },
  duplicate: report => { report.suites[0].specs[1].id = report.suites[0].specs[0].id },
  'extra project': report => { report.config.projects.push(report.config.projects[0]) },
  'wrong file': report => { report.suites[0].specs[0].file = 'fake.test.js' },
  'grep override': report => { report.config.argv.push('--grep=one') },
  shard: report => { report.config.shard = { current: 1, total: 2 } },
  'missing ledger': report => { first(report).results[0].attachments = [] },
  'blocked network': report => { first(report).results[0].attachments.find(a => a.name === 'synthetic-network-ledger').body = Buffer.from(JSON.stringify({ calls: [{}], blocked: ['external'], pageErrors: [] })).toString('base64') },
  'missing integration label': report => { report.suites[0].specs[0].title = 'unlabeled integration' }
}
test('synthetic unit-control report exercises validator success shape, not a browser pass', () => assert.deepEqual(validate(syntheticReport()), { executed: 12, userFlow: 10, integration: 2 }))
for (const [name, mutate] of Object.entries(controls)) test(`report gate rejects ${name}`, () => { const report = syntheticReport(); mutate(report); assert.throws(() => validate(report)) })
for (const argument of ['--list', '--grep=one', '--grep-invert=one', '--shard=1/2', '--retries=1', '--repeat-each=2', '--project=other', '--reporter=line', '--pass-with-no-tests', '--last-failed', 'one.test.js']) {
  test(`entrypoint rejects ${argument}`, () => assert.throws(() => assertInvocation([argument])))
}
test('runtime witness rejects discovery, missing, stale, duplicate, skipped and retried execution', () => {
  const ids = Array.from({ length: 12 }, (_, i) => String(i))
  const witness = { schema: 1, runId: RUN_ID, discovered: 12, status: 'passed', errors: [], begun: ids.map(id => ({ id, retry: 0 })), ended: ids.map(id => ({ id, status: 'passed', expectedStatus: 'passed', retry: 0 })) }
  assert.equal(validateWitness(witness, RUN_ID).length, 12)
  for (const mutate of [w => { w.begun = [] }, w => { w.runId = 'b'.repeat(32) }, w => { w.ended[0].retry = 1 }, w => { w.ended[0].status = 'skipped' }, w => { w.ended[0].id = '1' }, w => { w.status = 'interrupted' }]) {
    const bad = structuredClone(witness); mutate(bad); assert.throws(() => validateWitness(bad, RUN_ID))
  }
})
const classify = (suffix, options = {}) => classifyRequest({ url: `${ORIGIN}${suffix}`, method: 'GET', resourceType: 'script', ...options })
test('policy permits only explicit documents/assets and synthetic API methods', () => {
  assert.equal(classify('/static/js/app.abc.js').kind, 'asset')
  assert.equal(classify('/platform/city-board-drafts?draft=synthetic-draft', { resourceType: 'document' }).kind, 'document')
  assert.equal(classify('/login', { resourceType: 'document' }).kind, 'document')
  assert.deepEqual(classify('/prod-api/captchaImage', { resourceType: 'xhr' }), { kind: 'api', endpoint: '/captchaImage' })
  assert.equal(classify('/prod-api/business/city-board-drafts/save', { method: 'POST', resourceType: 'fetch' }).kind, 'api')
})
for (const [name, url, options] of [
  ['external', 'https://example.invalid/static/a.js', {}], ['external redirect', `${ORIGIN}/static/app.js`, { redirectedFrom: true }],
  ['serviceworker', `${ORIGIN}/static/app.js`, { serviceWorker: true }], ['static fetch', `${ORIGIN}/static/app.js`, { resourceType: 'fetch' }],
  ['static xhr', `${ORIGIN}/static/app.js`, { resourceType: 'xhr' }], ['beacon', `${ORIGIN}/static/app.js`, { method: 'POST', resourceType: 'ping' }],
  ['websocket', 'ws://127.0.0.1:4173/static/a.js', { resourceType: 'websocket' }], ['eventsource', `${ORIGIN}/static/a.js`, { resourceType: 'eventsource' }],
  ['unknown API', `${ORIGIN}/prod-api/login`, { method: 'POST', resourceType: 'xhr' }], ['API document', `${ORIGIN}/prod-api/getInfo`, { resourceType: 'document' }],
  ['userinfo', 'http://user@127.0.0.1:4173/static/app.js', {}], ['other port', 'http://127.0.0.1:8080/static/app.js', {}],
  ['data', 'data:text/javascript,alert(1)', {}], ['file', 'file:///tmp/app.js', {}], ['blob', `blob:${ORIGIN}/example`, {}],
  ['path traversal', `${ORIGIN}/static/../static/app.js`, {}], ['encoded traversal', `${ORIGIN}/static/%2e%2e/static/app.js`, {}],
  ['double encoded traversal', `${ORIGIN}/static/%252e%252e/app.js`, {}], ['encoded separator', `${ORIGIN}/static/a%2fapp.js`, {}],
  ['backslash', `${ORIGIN}/static/\\app.js`, {}], ['method', `${ORIGIN}/static/app.js`, { method: 'POST' }],
  ['query asset', `${ORIGIN}/static/app.js?url=external`, {}], ['hidden file', `${ORIGIN}/static/.secret.js`, {}]
]) test(`network policy blocks ${name}`, () => assert.equal(classifyRequest({ url, method: 'GET', resourceType: 'script', ...options }).kind, 'block'))
test('static resolver rejects traversal before URL normalization, API fallbacks and unknown types', () => {
  assert.equal(HOST, '127.0.0.1'); assert.equal(PORT, 4173)
  for (const url of ['/static/../index.html', '/static/%2e%2e/index.html', '/static/%252e%252e/a.js', '/static/%5ca.js', '/static/%00x.js']) assert.equal(resolveRequest(url).status, 403, url)
  for (const url of ['/prod-api/getInfo', '/business/audit/list', '/static/.env', '/static/config.json', '/static/app.js?x=1']) assert.equal(resolveRequest(url).status, 404, url)
  for (const url of ['http://external.invalid/static/a.js', '//external.invalid/a.js', '/static/a.js#x', '/%bad']) assert.equal(resolveRequest(url).status, 400, url)
  for (const method of ['POST', 'PUT', 'DELETE', 'PATCH', 'OPTIONS', 'CONNECT', 'TRACE']) assert.equal(resolveRequest('/static/app.js', method).status, 405)
})
test('filesystem reads reject symlink files, symlink directories and nonfiles', () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'audit-infra-control-'))
  try {
    fs.mkdirSync(path.join(temp, 'root')); fs.writeFileSync(path.join(temp, 'secret'), 'not served')
    fs.symlinkSync(path.join(temp, 'secret'), path.join(temp, 'root', 'asset.js'))
    fs.symlinkSync(temp, path.join(temp, 'root', 'linked'))
    assert.throws(() => readRegular(path.join(temp, 'root'), 'asset.js'), /symlink/)
    assert.throws(() => readRegular(path.join(temp, 'root'), 'linked/secret'), /symlink/)
    assert.throws(() => readRegular(path.join(temp, 'root'), '../secret'), /unsafe/)
    assert.throws(() => inventory(path.join(temp, 'root'), ['asset.js']), /symlink/)
    assert.throws(() => verifyReceipt(temp, 'a'.repeat(40)))
  } finally { fs.rmSync(temp, { recursive: true, force: true }) }
})
test('HTTP handler serves immutable bytes, HEAD has no body, never proxies or redirects, and closes listener', async () => {
  const server = http.createServer(createRequestHandler(new Map([['index.html', Buffer.from('unit-handler-control')], ['static/app.js', Buffer.from('unit-asset-control')]])))
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  const port = server.address().port
  const request = (url, method = 'GET', host = '127.0.0.1:4173') => new Promise((resolve, reject) => {
    const req = http.request({ hostname: '127.0.0.1', port, path: url, method, headers: { host } }, res => {
      let body = ''; res.on('data', chunk => { body += chunk }); res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body }))
    }); req.once('error', reject); req.end()
  })
  try {
    assert.equal((await request('/platform/city-board-drafts')).body, 'unit-handler-control')
    const head = await request('/static/app.js', 'HEAD'); assert.equal(head.status, 200); assert.equal(head.body, '')
    for (const url of ['/prod-api/getInfo', '/api/anything', '/unknown']) { const res = await request(url); assert.equal(res.status, 404); assert.equal(res.headers.location, undefined) }
    assert.equal((await request('/static/app.js', 'POST')).status, 405)
    assert.equal((await request('/static/app.js', 'GET', 'external.invalid')).status, 400)
    assert.equal((await request('/static/../index.html')).status, 403)
  } finally { await new Promise(resolve => server.close(resolve)); server.closeAllConnections() }
  assert.equal(server.listening, false)
})
test('actual entrypoint rejects --list and cleans only isolated subprocess evidence', () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'audit-runner-unit-control-'))
  const files = ['city-board/run.js', 'city-board/report-validator.js', 'city-board/gate-reporter.js', 'city-board/bundle-provenance.js', 'server/audit-bundle-provenance.js']
  try {
    for (const file of files) {
      const target = path.join(temp, 'tests/e2e', file)
      fs.mkdirSync(path.dirname(target), { recursive: true })
      fs.copyFileSync(path.join(UI, 'tests/e2e', file), target)
    }
    const evidence = path.join(temp, 'tests/e2e/evidence/city-board-browser')
    fs.mkdirSync(evidence, { recursive: true }); fs.writeFileSync(path.join(evidence, 'gate.json'), '{"status":"stale unit control"}')
    const result = spawnSync(process.execPath, [path.join(temp, 'tests/e2e/city-board/run.js'), '--list'], { encoding: 'utf8' })
    assert.equal(result.status, 1); assert.match(result.stderr, /no arguments accepted/)
    assert.equal(fs.existsSync(path.join(evidence, 'gate.json')), false)
    assert.equal(JSON.parse(fs.readFileSync(path.join(evidence, 'gate-failure.json'))).status, 'failed')
    // An unsafe ancestor must neither be deleted nor receive failure reports.
    fs.rmSync(path.join(temp, 'tests/e2e/evidence'), { recursive: true, force: true })
    const outside = path.join(temp, 'outside'); fs.mkdirSync(outside)
    fs.symlinkSync(outside, path.join(temp, 'tests/e2e/evidence'))
    const rejected = spawnSync(process.execPath, [path.join(temp, 'tests/e2e/city-board/run.js'), '--list'], { encoding: 'utf8' })
    assert.equal(rejected.status, 1); assert.match(rejected.stderr, /symlinks/)
    assert.deepEqual(fs.readdirSync(outside), [])
  } finally { fs.rmSync(temp, { recursive: true, force: true }) }
})
test('screenshot proof rejects missing, outside, symlink, empty and non-PNG files', () => {
  const attachments = SCREENSHOTS.map(name => ({ name, contentType: 'image/png', path: path.join(SYNTHETIC_EVIDENCE, `${name}.png`) }))
  assert.doesNotThrow(() => validateScreenshots(attachments, SYNTHETIC_EVIDENCE))
  assert.throws(() => validateScreenshots([], SYNTHETIC_EVIDENCE))
  for (const [name, bytes] of [['empty.png', Buffer.alloc(0)], ['invalid.png', Buffer.alloc(80)]]) {
    fs.writeFileSync(path.join(SYNTHETIC_EVIDENCE, name), bytes)
    assert.throws(() => validateScreenshots([{ ...attachments[0], path: path.join(SYNTHETIC_EVIDENCE, name) }, ...attachments.slice(1)], SYNTHETIC_EVIDENCE))
  }
  assert.throws(() => validateScreenshots([{ ...attachments[0], path: '/tmp/outside.png' }, ...attachments.slice(1)], SYNTHETIC_EVIDENCE))
  const link = path.join(SYNTHETIC_EVIDENCE, 'link.png'); fs.symlinkSync(attachments[0].path, link)
  assert.throws(() => validateScreenshots([{ ...attachments[0], path: link }, ...attachments.slice(1)], SYNTHETIC_EVIDENCE), /symlink/)
})
test('child environment strips proxy and remote-browser injection variables', () => {
  const { childEnvironment } = require('../e2e/city-board/run')
  const keys = ['HTTP_PROXY', 'https_proxy', 'ALL_PROXY', 'NO_PROXY', 'PW_TEST_CONNECT_WS_ENDPOINT', 'PLAYWRIGHT_TEST_CONNECT_WS_ENDPOINT', 'PLAYWRIGHT_JSON_OUTPUT_FILE', 'NODE_OPTIONS', 'NODE_PATH']
  const original = Object.fromEntries(keys.map(key => [key, process.env[key]]))
  try {
    for (const key of keys) process.env[key] = 'unit-control-must-not-survive'
    const env = childEnvironment(RUN_ID)
    for (const key of keys) assert.equal(env[key], undefined)
    assert.equal(env.CITY_BOARD_BROWSER_GATE_RUN_ID, RUN_ID)
  } finally { for (const key of keys) if (original[key] === undefined) delete process.env[key]; else process.env[key] = original[key] }
})
