/** Synthetic unit controls for the real production-asset boundary; never browser execution evidence. */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { ORIGIN, THEME_CSS_PATH, classifyRequest } = require('../e2e/audit-network-policy')
const { resolveRequest, loadProductionSnapshot, createRequestHandler } = require('../e2e/server/audit-static-server')
const { RECEIPT, sourceInventory, inventory } = require('../e2e/server/audit-bundle-provenance')
const UI = path.resolve(__dirname, '../..')
const classify = (pathname, options = {}) => classifyRequest({ url: ORIGIN + pathname, method: 'GET', resourceType: 'xhr', ...options })

test('only the exact real ThemePicker stylesheet permits its observed XHR and stylesheet types', () => {
  assert.equal(THEME_CSS_PATH, '/styles/theme-chalk/index.css')
  assert.equal(classify(THEME_CSS_PATH).kind, 'asset')
  assert.equal(classify(THEME_CSS_PATH, { resourceType: 'stylesheet' }).kind, 'asset')
  assert.deepEqual(resolveRequest(THEME_CSS_PATH), { status: 200, file: THEME_CSS_PATH.slice(1) })
  const picker = fs.readFileSync(path.join(UI, 'src/components/ThemePicker/index.vue'), 'utf8')
  assert.ok(picker.includes(THEME_CSS_PATH)); assert.match(picker, /new XMLHttpRequest/)
})
for (const [name, pathname, options] of [
  ['neighbor', '/styles/theme-chalk/other.css', {}], ['directory', '/styles/theme-chalk/', {}],
  ['query', THEME_CSS_PATH + '?target=external', {}], ['script type', THEME_CSS_PATH, { resourceType: 'script' }],
  ['fetch type', THEME_CSS_PATH, { resourceType: 'fetch' }], ['write', THEME_CSS_PATH, { method: 'POST' }],
  ['redirect', THEME_CSS_PATH, { redirectedFrom: true }], ['service worker', THEME_CSS_PATH, { serviceWorker: true }],
  ['traversal', '/styles/theme-chalk/../theme-chalk/index.css', {}],
  ['encoded traversal', '/styles/%2e%2e/theme-chalk/index.css', {}]
]) test(`theme exception rejects ${name}`, () => assert.equal(classify(pathname, options).kind, 'block'))

test('theme exception cannot change origin, port or expose arbitrary styles to the server', () => {
  for (const origin of ['https://example.invalid', 'http://127.0.0.1:8080', 'http://localhost:4173']) {
    assert.equal(classifyRequest({ url: origin + THEME_CSS_PATH, method: 'GET', resourceType: 'xhr' }).kind, 'block')
  }
  for (const name of ['/styles/theme-chalk/other.css', '/styles/theme-chalk/index.css?x=1', '/styles/secret.json']) assert.equal(resolveRequest(name).status, 404)
})

test('verified snapshot serves exact receipt-bound theme bytes and rejects mutation, omission and symlinks', () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'audit-theme-unit-control-'))
  const commit = 'a'.repeat(40), previous = process.env.GITHUB_SHA
  process.env.GITHUB_SHA = commit
  try {
    for (const dir of ['src', 'public', 'build', 'dist/static/js', 'dist/styles/theme-chalk']) fs.mkdirSync(path.join(temp, dir), { recursive: true })
    for (const file of ['src/main.js', 'public/index.html', 'package.json', 'package-lock.json', 'vue.config.js']) fs.writeFileSync(path.join(temp, file), 'explicit synthetic unit control')
    fs.writeFileSync(path.join(temp, 'dist/index.html'), '<script src=/static/js/app.abcd.js></script>')
    fs.writeFileSync(path.join(temp, 'dist/static/js/app.abcd.js'), 'unit control; not an application')
    const theme = path.join(temp, 'dist', THEME_CSS_PATH.slice(1)), css = Buffer.from('/* synthetic unit-control theme */')
    fs.writeFileSync(theme, css)
    fs.writeFileSync(path.join(temp, 'dist/styles/theme-chalk/other.css'), 'must not be exposed')
    const builtAt = '2026-01-01T00:00:00Z'
    fs.writeFileSync(path.join(temp, 'dist/version.json'), JSON.stringify({ commit, builtAt }))
    const stamp = () => {
      const receipt = { schema: 1, command: 'npm run build:prod', mode: 'production', commit, builtAt, inputs: sourceInventory(temp),
        outputs: inventory(path.join(temp, 'dist'), fs.readdirSync(path.join(temp, 'dist')).sort(), new Set([RECEIPT])) }
      fs.writeFileSync(path.join(temp, 'dist', RECEIPT), JSON.stringify(receipt))
    }
    stamp()
    const { snapshot } = loadProductionSnapshot(temp)
    assert.deepEqual(snapshot.get(THEME_CSS_PATH.slice(1)), css)
    assert.equal(snapshot.has('styles/theme-chalk/other.css'), false)
    const response = { setHeader() {}, writeHead(status, headers) { this.status = status; this.headers = headers }, end(bytes) { this.bytes = bytes } }
    createRequestHandler(snapshot)({ url: THEME_CSS_PATH, method: 'GET', headers: { host: '127.0.0.1:4173' } }, response)
    assert.equal(response.status, 200); assert.match(response.headers['Content-Type'], /^text\/css/); assert.deepEqual(response.bytes, css)
    fs.writeFileSync(theme, 'changed after receipt'); assert.throws(() => loadProductionSnapshot(temp), /bundle/)
    fs.unlinkSync(theme); stamp(); assert.throws(() => loadProductionSnapshot(temp), /Missing production theme stylesheet/)
    fs.symlinkSync(path.join(temp, 'src/main.js'), theme); assert.throws(() => loadProductionSnapshot(temp), /symlink/)
  } finally {
    if (previous === undefined) delete process.env.GITHUB_SHA; else process.env.GITHUB_SHA = previous
    fs.rmSync(temp, { recursive: true, force: true })
  }
})

test('all browser audit heading waits use the actual semantic h1, including leave-view negatives', () => {
  const header = fs.readFileSync(path.join(UI, 'src/components/CyPageHeader/index.vue'), 'utf8')
  assert.match(header, /<h1>\{\{ title \}\}<\/h1>/)
  for (const file of ['fixture.js', 'audit-console.browser.test.js', 'candidate-preview.browser.test.js']) {
    const source = fs.readFileSync(path.join(UI, 'tests/e2e/audit', file), 'utf8')
    assert.doesNotMatch(source, /cy-page-header__title/)
    assert.ok(source.includes("getByRole('heading', { name: '统一审核台', exact: true })"))
  }
  const fixture = fs.readFileSync(path.join(UI, 'tests/e2e/audit/fixture.js'), 'utf8')
  assert.match(fixture, /await link\.click\(\)/); assert.match(fixture, /page\.goBack\(\)/); assert.match(fixture, /page\.goForward\(\)/)
})
