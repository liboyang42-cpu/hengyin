const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const Vue = require('vue')
const compiler = require('vue-template-compiler')
const source = fs.readFileSync(path.join(__dirname, '../../src/views/business/city-board-drafts/index.vue'), 'utf8')
const sfc = compiler.parseComponent(source)
const script = sfc.script.content.replace(/^import .*$/m, '').replace('export default', 'module.exports =')
function harness(overrides = {}) {
  const calls = []
  const catalog = { regionIds: ['r'], references: [], places: [] }
  const context = { module: { exports: {} }, Uint8Array, window: { crypto: require('node:crypto').webcrypto, confirm: () => true }, cityBoardDraftsEnabled: true,
    readCityDraftCatalog: async () => ({ data: catalog }), readCityDraft: async () => { throw new Error('unused') }, validateCityDraft: async () => ({ data: { valid: true } }),
    saveCityDraft: async cmd => { calls.push(cmd); return { data: { draftId: cmd.draftId, version: cmd.expectedVersion + 1, payloadHash: 'a'.repeat(64), payload: cmd.payload } } },
    previewCityDraft: async cmd => ({ data: { ...cmd, readOnly: true } }), ...overrides }
  vm.runInNewContext(script, context)
  const auth = Vue.observable({ id: 4, token: 'one', roles: ['platform'], permissions: ['edit'] })
  const route = Vue.observable({ fullPath: '/platform/city-board-drafts' })
  const component = new Vue({ ...context.module.exports, beforeCreate() { this.$store = { getters: auth }; this.$route = route } })
  return { component, calls, auth, route, context }
}
const tick = async () => { await Promise.resolve(); await Vue.nextTick() }
test('actual Vue2 template compiles and has no raw HTML directive', () => {
  const result = compiler.compile(sfc.template.content)
  assert.deepEqual(result.errors, [])
  assert.equal(source.includes('v-html'), false)
})
test('new draft has no invented economic or rule defaults', async () => {
  const { component: c } = harness(); await tick()
  for (const field of c.numericFields) assert.equal(c.form.rule[field.key], null)
  for (const ref of c.referenceFields) assert.equal(c.form.rule[ref.field], null)
  assert.equal(c.form.classification, 'platform_rule'); c.$destroy()
})
test('save edit reopen and preview use persisted version/hash contract', async () => {
  let stored
  const { component: c } = harness({ saveCityDraft: async cmd => { stored = { draftId: cmd.draftId, version: cmd.expectedVersion + 1, payloadHash: 'a'.repeat(64), payload: JSON.parse(JSON.stringify(cmd.payload)) }; return { data: stored } }, readCityDraft: async () => ({ data: stored }) })
  await tick(); c.draftId = 'board'; c.form.name = 'First'; await c.save(); assert.equal(c.saved.version, 1)
  c.form.name = 'Second'; await c.save(); assert.equal(c.saved.version, 2)
  c.form.name = 'Unsaved'; await c.reopen(); assert.equal(c.form.name, 'Second'); assert.equal(c.dirty, false)
  await c.preview(); assert.equal(c.playerPreview.readOnly, true); c.$destroy()
})
test('double save is suppressed and timeout retry preserves exact command', async () => {
  let reject, resolve; const commands = []
  const { component: c } = harness({ saveCityDraft: cmd => { commands.push(cmd); return new Promise((ok, no) => { resolve = ok; reject = no }) } })
  await tick(); c.draftId = 'board'; const first = c.save(); await c.save(); assert.equal(commands.length, 1)
  reject(new Error('timeout')); await first; const original = JSON.stringify(c.pending)
  const retry = c.retry(); assert.equal(JSON.stringify(commands[1]), original)
  resolve({ data: { draftId: 'board', version: 1, payloadHash: 'a'.repeat(64), payload: commands[1].payload } }); await retry
  assert.equal(c.pending, null); c.$destroy()
})
test('definite input rejection permits correction; CAS conflict requires reopen', async () => {
  const { component: c } = harness({ saveCityDraft: async () => { throw Object.assign(new Error('bad'), { code: 400, errorCode: 'INTEGER_REQUIRED' }) } })
  await tick(); c.draftId = 'board'; await c.save(); assert.equal(c.pending, null); assert.equal(c.blocked, false); assert.equal(c.error, 'INTEGER_REQUIRED'); c.$destroy()
  const other = harness({ saveCityDraft: async () => { throw Object.assign(new Error('conflict'), { code: 409, errorCode: 'CITY_DRAFT_CONFLICT' }) } }).component
  await tick(); other.draftId = 'board'; await other.save(); assert.equal(other.pending, null); assert.equal(other.blocked, true); assert.equal(other.saved, null); other.$destroy()
})
test('route/account ABA clears form and discards late save response', async () => {
  let complete
  const { component: c, auth, route } = harness({ saveCityDraft: cmd => new Promise(resolve => { complete = () => resolve({ data: { draftId: cmd.draftId, version: 1, payloadHash: 'a'.repeat(64), payload: cmd.payload } }) }) })
  await tick(); c.draftId = 'board'; c.form.name = 'secret'; const save = c.save()
  auth.token = 'two'; auth.token = 'one'; route.fullPath = '/other'; route.fullPath = '/platform/city-board-drafts'
  complete(); await save; assert.equal(c.saved, null); assert.equal(c.form.name, ''); assert.deepEqual(c.catalog.regionIds, ['r']); assert.equal(c.draftId, ''); c.$destroy()
})
test('destroyed page discards late catalog response', async () => {
  let complete
  const { component: c } = harness({ readCityDraftCatalog: () => new Promise(resolve => { complete = resolve }) })
  c.$destroy(); complete({ data: { regionIds: ['secret'], references: [], places: [] } }); await tick(); assert.equal(c.catalog, null)
})

test('cancelled reopen preserves unsaved work; accepted reopen replaces it explicitly', async () => {
  let reads = 0
  const { component: c, context } = harness({ readCityDraft: async () => { reads++; return { data: { draftId: 'board', version: 4, payloadHash: 'b'.repeat(64), payload: { ...JSON.parse(JSON.stringify(c.form)), name: 'Server' } } } } })
  await tick(); c.draftId = 'board'; c.form.name = 'Unsaved'; context.window.confirm = () => false
  await c.reopen(); assert.equal(reads, 0); assert.equal(c.form.name, 'Unsaved')
  context.window.confirm = () => true; await c.reopen(); assert.equal(reads, 1); assert.equal(c.saved.version, 4); assert.equal(c.form.name, 'Server'); c.$destroy()
})
test('deactivation retires all requests; reactivation loads a fresh catalog', async () => {
  let reads = 0
  const { component: c } = harness({ readCityDraftCatalog: async () => ({ data: { regionIds: ['r' + (++reads)], references: [], places: [] } }) })
  await tick(); const previous = c.requestContext(); c.$options.deactivated[0].call(c)
  assert.equal(previous.isCurrent(), false); assert.equal(c.catalog, null)
  c.$options.activated[0].call(c); await tick(); assert.deepEqual(c.catalog.regionIds, ['r2']); c.$destroy()
})
function requestHarness(auth) {
  const hooks = {}, messages = [], dialogs = [], dispatches = [], configs = []
  const location = { href: '/platform/city-board-drafts' }
  let store = { dispatch: (...args) => { dispatches.push(args); return Promise.resolve() } }
  if (auth) {
    const userSource = fs.readFileSync(path.join(__dirname, '../../src/store/modules/user.js'), 'utf8').replace(/^import[^\n]*\n/gm, '').replace('export default user', 'return user')
    const user = new Function('getToken', 'logout', 'removeToken', userSource)(() => auth.token, async () => {}, () => {})
    store = { dispatch(name, options) { dispatches.push([name, options]); return user.actions[name]({ state: auth, commit: (key, value) => user.mutations[key](auth, value) }, options) } }
  }
  const service = { interceptors: { request: { use(fn) { hooks.request = fn } }, response: { use(success, error) { hooks.success = success; hooks.error = error } } } }
  const requestSource = fs.readFileSync(path.join(__dirname, '../../src/utils/request.js'), 'utf8').replace(/^import[^\n]*\n/gm, '').replace('export default service', '').replace(/export /g, '')
  new Function('axios', 'errorCode', 'Message', 'Notification', 'MessageBox', 'store', 'console', 'getToken', 'cache', 'location', requestSource)(
    { defaults: { headers: {} }, create: () => service }, {}, x => messages.push(x), { error: x => messages.push(x) },
    { confirm: () => new Promise(resolve => dialogs.push(resolve)) }, store,
    { log() {}, warn() {} }, () => auth ? auth.token : 'test-context', { session: { getJSON: () => null, setJSON() {} } }, location)
  const apiSource = fs.readFileSync(path.join(__dirname, '../../src/api/business/city-board-drafts.js'), 'utf8').replace(/^import[^\n]*\n/gm, '').replace(/export /g, '')
  const api = new Function('request', 'process', apiSource + '\nreturn { readCityDraftCatalog, readCityDraft, validateCityDraft, saveCityDraft, previewCityDraft };')(config => { configs.push(config); return Promise.resolve() }, { env: { VUE_APP_CITY_BOARD_DRAFTS: 'true' } })
  return { hooks, messages, dialogs, dispatches, configs, api, location }
}
test('API context stays local and requests require a current context', async () => {
  const { component: c } = harness(); await tick(); const h = requestHarness(), context = c.requestContext(), dto = { test: 'payload' }
  await h.api.readCityDraftCatalog(context); await h.api.readCityDraft('board', context); await h.api.validateCityDraft(dto, context); await h.api.saveCityDraft(dto, context); await h.api.previewCityDraft(dto, context)
  for (const config of h.configs) {
    assert.equal(config.auditContext, context); assert.equal(config.preserveBusinessError, true)
    for (const key of ['url', 'headers', 'params', 'data']) assert.doesNotMatch(JSON.stringify(config[key]) || '', /auditContext|isCurrent|captureCurrent/)
  }
  c.invalidate(); await assert.rejects(h.api.saveCityDraft(dto, context)); assert.equal(h.configs.length, 5); c.$destroy()
})
for (const kind of ['401', '500', 'network']) test(`retired ${kind} cannot notify or log out a newer page context`, async () => {
  const { component: c, auth } = harness(); await tick(); const h = requestHarness()
  await h.api.saveCityDraft({}, c.requestContext()); const config = h.configs[0]
  auth.token = 'two'; auth.token = 'one'
  if (kind === 'network') await assert.rejects(h.hooks.error(Object.assign(new Error('Network Error'), { config })))
  else await assert.rejects(h.hooks.success({ data: { code: Number(kind) }, config, request: {} }))
  assert.equal(h.messages.length, 0); assert.equal(h.dialogs.length, 0); assert.equal(h.dispatches.length, 0); c.$destroy()
})
test('account ABA after a current 401 dialog prevents confirmed logout', async () => {
  const { component: c, auth } = harness(); await tick(); const h = requestHarness()
  await h.api.saveCityDraft({}, c.requestContext()); await assert.rejects(h.hooks.success({ data: { code: 401 }, config: h.configs[0], request: {} }))
  assert.equal(h.dialogs.length, 1); auth.token = 'two'; auth.token = 'one'; h.dialogs[0](); await tick()
  assert.equal(h.dispatches.length, 0); c.$destroy()
})

for (const field of ['id', 'roles', 'permissions']) test(`${field} ABA invalidates pending context before response handling`, async () => {
  const { component: c, auth } = harness(); await tick(); const h = requestHarness(auth), context = c.requestContext(), original = auth[field]
  auth[field] = field === 'id' ? 9 : ['different']; auth[field] = original
  assert.equal(context.isCurrent(), false)
  await assert.rejects(h.hooks.success({ data: { code: 401 }, config: { auditContext: context }, request: {} }))
  assert.equal(h.dialogs.length, 0); assert.equal(h.dispatches.length, 0); c.$destroy()
})
test('current confirmed 401 follows actual user logout and redirect without unauthenticated catalog reload', async () => {
  let reads = 0
  const { component: c, auth } = harness({ readCityDraftCatalog: async () => { reads++; return { data: { regionIds: [], references: [], places: [] } } } })
  await tick(); const h = requestHarness(auth)
  await h.api.saveCityDraft({}, c.requestContext()); await assert.rejects(h.hooks.success({ data: { code: 401 }, config: h.configs[0], request: {} }))
  h.dialogs[0](); for (let i = 0; i < 8; i++) await tick()
  assert.equal(auth.token, ''); assert.equal(h.dispatches.length, 1); assert.equal(h.location.href, '/index'); assert.equal(reads, 1); c.$destroy()
})
test('hash/query and Back/Forward-style route changes reload current catalog', async () => {
  let reads = 0
  const { component: c, route } = harness({ readCityDraftCatalog: async () => { reads++; return { data: { regionIds: ['r'], references: [], places: [] } } } })
  await tick()
  for (const fullPath of ['/platform/city-board-drafts#preview', '/platform/city-board-drafts?draft=1', '/platform/city-board-drafts#preview', '/platform/city-board-drafts']) {
    route.fullPath = fullPath; await tick(); await tick(); assert.deepEqual(c.catalog.regionIds, ['r'])
  }
  assert.equal(reads, 5); c.$destroy()
})
