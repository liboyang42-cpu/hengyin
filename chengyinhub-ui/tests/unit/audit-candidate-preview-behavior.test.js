const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const Vue = require('vue')
const compiler = require('vue-template-compiler')
Vue.config.productionTip = false
Vue.config.devtools = false
const root = path.resolve(__dirname, '../..')
const source = fs.readFileSync(path.join(root, 'src/views/business/audit/index.vue'), 'utf8')
const candidate = (overrides = {}, envelope = {}) => ({
  id: 31, bizType: 'CONTENT_CAPTURE_REVIEW', bizId: 14, submitterId: 27,
  sourceType: 'content_capture_review_v1', version: 2, status: 0, currentAssignee: 4,
  snapshotJson: JSON.stringify({ schemaVersion: 1, contract: 'content_capture_review_v1', snapshotId: 19,
    topicId: 14, submitterMemberId: 27, sourceConfigVersion: 4, contentHash: 'b'.repeat(64),
    candidateContract: 'questify.content-capture-candidate.v1', canonicalizationVersion: 'questify.canonical-content-json.v1',
    completeRelease: false, reviewScope: 'INCOMPLETE_CANDIDATE_ONLY', ...envelope }), ...overrides
})
const dto = (overrides = {}) => ({ contract: 'questify.content-capture-preview.v1', auditTaskId: '31',
  auditTaskVersion: 2, status: 0, snapshotId: '19', topicId: '14', sourceConfigVersion: '4', contentHash: 'b'.repeat(64),
  textEncoding: 'HTML_ESCAPED_PLAIN_TEXT', reviewScope: 'INCOMPLETE_CANDIDATE_TEXT_ONLY', completeRelease: false,
  publicationEligible: false, previewComplete: false, entries: [{ path: 'nodes[0].name', text: '冻结文字' }], ...overrides })
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const flush = async () => { await Vue.nextTick(); await Promise.resolve(); await Vue.nextTick() }
function component(file, deps = {}) {
  const raw = fs.readFileSync(path.join(root, file), 'utf8')
  const script = raw.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^\s*import[^\n]*\n/gm, '')
    .replace(/components:\s*\{[^}]+\},/, 'components: { CyEvidencePanel },').replace(/export\s+default\s*/, 'return ')
  const options = new Function(...Object.keys(deps), script)(...Object.values(deps))
  const template = compiler.parseComponent(raw).template.content
  const compiled = compiler.compile(template)
  assert.deepEqual(compiled.errors, [])
  return { ...options, ...compiler.compileToFunctions(template) }
}
const CyEvidencePanel = component('src/components/CyEvidencePanel/index.vue')
function mount(overrides = {}) {
  const requests = [], writes = [], reads = [], ui = []
  const deps = { CyEvidencePanel,
    getAuditTaskDetail: async id => { reads.push(id); return { task: candidate({ id }), records: [], reportCount: 0 } },
    listAuditTasks: async () => ({ rows: [], total: 0 }),
    reviewAuditTask: async value => { writes.push(value); return { code: 200 } },
    getCandidatePreview: async (id, binding, context) => { requests.push({ id, binding, context }); return { code: 200, data: dto() } },
    submitTopicProjectReview: async () => { writes.push('project') }, submitTopicSaleReview: async () => { writes.push('sale') }, ...overrides }
  const route = Vue.observable({ path: '/business/audit', fullPath: '/business/audit?bizId=14', query: { bizId: '14' } })
  const getters = Vue.observable({ token: 'fixture-a', id: 4, roles: ['admin'], permissions: ['business:audit:list', 'business:audit:review'] })
  const vm = new Vue({ ...component('src/views/business/audit/index.vue', deps), beforeCreate() {
    this.$route = route; this.$store = { getters }; this.$modal = { msgSuccess: value => ui.push(value) }; this.parseTime = () => ''
  } })
  return { vm, route, getters, requests, writes, reads, ui }
}
function walk(node, visit) {
  if (!node) return
  visit(node)
  for (const child of node.children || []) walk(child, visit)
  for (const child of node.componentOptions && node.componentOptions.children || []) walk(child, visit)
}
async function previewWith(data) {
  const h = mount({ getCandidatePreview: async () => ({ code: 200, data }) })
  await h.vm.openDetail(candidate()); await h.vm.loadCandidatePreview(); return h
}

test('detail and review open never auto-load preview; explicit read uses only exact immutable binding and local context', async () => {
  const h = mount(); await h.vm.openDetail(candidate()); assert.equal(h.requests.length, 0)
  await h.vm.loadCandidatePreview(); assert.equal(h.requests.length, 1)
  assert.deepEqual(h.requests[0].binding, { expectedTaskVersion: 2, snapshotId: '19', sourceConfigVersion: '4', contentHash: 'b'.repeat(64) })
  assert.equal(h.requests[0].context.isCurrent(), true); assert.equal(h.vm.previewLoaded, true)
  await h.vm.openReview(candidate(), 1); assert.equal(h.vm.previewItems.length, 0); assert.equal(h.requests[0].context.isCurrent(), false)
  assert.equal(h.requests.length, 1); assert.equal(h.writes.length, 0); assert.equal(h.ui.length, 0); h.vm.$destroy()
})

test('compiled Vue evidence renders one-pass decoded visible text, never HTML nodes or remote assets', async () => {
  const text = '&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; &amp;lt;img src=x&amp;gt; &#39;原文&#39;'
  const h = await previewWith(dto({ entries: [{ path: 'chapters[0].blocks[2].content', text }] }))
  const visible = '<script>alert("x")</script> & &lt;img src=x&gt; \'原文\''
  assert.equal(h.vm.previewItems[0].content, visible)
  let evidence
  walk(h.vm._render(), node => { if (node.componentOptions && node.componentOptions.propsData.title === '冻结候选部分白名单文本') evidence = node })
  assert.ok(evidence, 'actual detail template connects to the real evidence component')
  const panel = new Vue({ ...CyEvidencePanel, propsData: evidence.componentOptions.propsData })
  let texts = ''; const tags = []
  walk(panel._render(), node => { if (node.text) texts += node.text; if (node.tag) tags.push(node.tag) })
  assert.ok(texts.includes(visible)); assert.equal(tags.some(tag => /^(script|img|iframe|a|el-image)$/.test(tag)), false)
  assert.doesNotMatch(source.replace(/\/\/[^\n]*/g, ''), /v-html|innerHTML|DOMParser|createElement\(['"](?:textarea|div)/)
  panel.$destroy(); h.vm.$destroy()
})

test('valid repeated whitelist paths, controls, literal entities, and an empty projection retain honest partial status', async () => {
  const entries = [
    { path: 'story.journeyStory.call.options[].line', text: '&amp;#x3c;img&amp;#x3e; &amp;nbsp;' },
    { path: 'story.journeyStory.call.options[].line', text: '  保留空白\n\t[CONTROL]  ' },
    { path: 'nodeJourney[9223372036854775807].blockConfig.enter.questions[].q', text: '[REDACTED_LINK_OR_CREDENTIAL]' }
  ]
  const h = await previewWith(dto({ entries })); assert.equal(h.vm.previewItems.length, 3)
  assert.equal(h.vm.previewItems[0].content, '&#x3c;img&#x3e; &nbsp;'); assert.equal(h.vm.previewItems[1].content, entries[1].text)
  h.vm.$destroy(); const empty = await previewWith(dto({ entries: [] })); assert.equal(empty.vm.previewLoaded, true)
  assert.deepEqual(empty.vm.previewItems, []); assert.match(source, /不代表完整内容为空或已通过核验/); empty.vm.$destroy()
})

for (const [name, change] of [
  ['task id type', { auditTaskId: 31 }], ['different task', { auditTaskId: '32' }], ['different version', { auditTaskVersion: 3 }],
  ['version type', { auditTaskVersion: '2' }], ['different status', { status: 1 }], ['different topic', { topicId: '15' }],
  ['different candidate', { snapshotId: '20' }], ['different config', { sourceConfigVersion: '5' }], ['different hash', { contentHash: 'c'.repeat(64) }],
  ['unsupported contract', { contract: 'future' }], ['raw encoding', { textEncoding: 'PLAIN_TEXT' }], ['wrong scope', { reviewScope: 'FULL' }],
  ['complete flag', { completeRelease: true }], ['publication flag', { publicationEligible: true }], ['complete preview', { previewComplete: true }],
  ['unexpected payload', { secret: 'DO_NOT_RENDER' }], ['malformed entries', { entries: {} }]
]) test(`malformed DTO fails closed: ${name}`, async () => {
  const h = await previewWith(dto(change)); assert.equal(h.vm.previewLoaded, false); assert.equal(h.vm.previewItems.length, 0)
  assert.ok(h.vm.previewError); assert.equal(h.writes.length, 0); h.vm.$destroy()
})

for (const [name, entries] of [
  ['raw markup', [{ path: 'nodes[0].name', text: '<img src=x>' }]],
  ['unknown entity', [{ path: 'nodes[0].name', text: '&#60;img&#62;' }]],
  ['malformed entity', [{ path: 'nodes[0].name', text: 'a & b' }]],
  ['control character', [{ path: 'nodes[0].name', text: 'a\u202Eb' }]],
  ['private answer path', [{ path: 'nodeJourney[1].blockConfig.answer', text: 'secret' }]],
  ['wrong namespace', [{ path: 'story.blockConfig.brief.body', text: 'wrong' }]],
  ['unknown path', [{ path: 'nodes[0].url', text: 'private' }]],
  ['extra asset field', [{ path: 'nodes[0].name', text: 'name', src: 'https://not-used.invalid' }]],
  ['nontext', [{ path: 'nodes[0].name', text: { nested: 'secret' } }]],
  ['entry count', Array.from({ length: 257 }, () => ({ path: 'nodes[0].name', text: 'x' }))],
  ['raw minimum length', [{ path: 'nodes[0].name', text: 'x'.repeat(4097) }]],
  ['aggregate escaped length', Array.from({ length: 9 }, () => ({ path: 'nodes[0].name', text: 'x'.repeat(4096) }))]
]) test(`projection bounds and whitelist fail closed: ${name}`, async () => {
  const h = await previewWith(dto({ entries })); assert.equal(h.vm.previewLoaded, false); assert.equal(h.vm.previewItems.length, 0); assert.ok(h.vm.previewError); h.vm.$destroy()
})

test('encoded expansion remains bounded without rejecting legitimate maximum raw text', async () => {
  const h = await previewWith(dto({ entries: [{ path: 'nodes[0].name', text: '&quot;'.repeat(4096) }] }))
  assert.equal(h.vm.previewLoaded, true); assert.equal(h.vm.previewItems[0].content, '"'.repeat(4096)); h.vm.$destroy()
  const controls = await previewWith(dto({ entries: [{ path: 'nodes[0].name', text: '[CONTROL]'.repeat(3000) }] }))
  assert.equal(controls.vm.previewLoaded, true); controls.vm.$destroy()
})

test('cancel invalidates pending preview before interceptor side effects; reopen needs a new click', async () => {
  const old = deferred(); let context, count = 0
  const h = mount({ getCandidatePreview: (id, binding, captured) => { context = captured; count++; return old.promise } })
  await h.vm.openDetail(candidate()); const request = h.vm.loadCandidatePreview(); h.vm.clearCandidatePreview()
  assert.equal(context.isCurrent(), false); assert.equal(h.vm.previewLoading, false)
  h.vm.detailOpen = false; h.vm.closeDetail(); await h.vm.openDetail(candidate()); assert.equal(count, 1)
  old.resolve({ code: 200, data: dto() }); await request; assert.equal(h.vm.previewLoaded, false); h.vm.$destroy()
})

test('double clicks are single-flight and an old completion cannot unlock a newer pending preview', async () => {
  const pending = [deferred(), deferred()]; const contexts = []; let count = 0
  const h = mount({ getCandidatePreview: (id, binding, context) => { contexts.push(context); return pending[count++].promise } })
  await h.vm.openDetail(candidate()); const first = h.vm.loadCandidatePreview(); await h.vm.loadCandidatePreview(); assert.equal(count, 1)
  h.vm.clearCandidatePreview(); const second = h.vm.loadCandidatePreview(); assert.equal(count, 2)
  pending[0].resolve({ code: 200, data: dto() }); await first; assert.equal(h.vm.previewLoading, true); assert.equal(h.vm.previewLoaded, false)
  pending[1].resolve({ code: 200, data: dto() }); await second; assert.equal(h.vm.previewLoaded, true); h.vm.$destroy()
})

for (const key of ['id', 'token', 'roles', 'permissions']) test(`same-tick ${key} ABA clears loaded text and fences old preview responses`, async () => {
  const pending = deferred(); let context, count = 0
  const h = mount({ getCandidatePreview: (id, binding, capture) => { context = capture; return ++count === 1 ? Promise.resolve({ code: 200, data: dto() }) : pending.promise } })
  await h.vm.openDetail(candidate()); await h.vm.loadCandidatePreview(); assert.equal(h.vm.previewLoaded, true)
  const request = h.vm.loadCandidatePreview(); const old = h.getters[key]
  h.getters[key] = key === 'id' ? 9 : key === 'token' ? 'fixture-b' : ['different']; h.getters[key] = old
  assert.equal(h.vm.previewItems.length, 0); assert.equal(context.isCurrent(), false)
  pending.resolve({ code: 200, data: dto() }); await request; assert.equal(h.vm.previewLoaded, false); h.vm.$destroy()
})

for (const change of ['route', 'task', 'version', 'hash', 'owner', 'assignment', 'drawer', 'deactivate', 'destroy']) test(`${change} retirement drops old preview before callback effects`, async () => {
  const pending = deferred(); let context
  const h = mount({ getCandidatePreview: (id, binding, capture) => { context = capture; return pending.promise } })
  await h.vm.openDetail(candidate()); const request = h.vm.loadCandidatePreview()
  if (change === 'route') { h.route.fullPath = '/business/audit?bizId=99'; h.route.fullPath = '/business/audit?bizId=14' }
  if (change === 'task') { const old = h.vm.detailTask; h.vm.detailTask = candidate({ id: 32 }); h.vm.detailTask = old }
  if (change === 'version') { h.vm.detailTask.version = 3; h.vm.detailTask.version = 2 }
  if (change === 'hash') { const old = h.vm.detailTask.snapshotJson; h.vm.detailTask.snapshotJson = candidate({}, { contentHash: 'c'.repeat(64) }).snapshotJson; h.vm.detailTask.snapshotJson = old }
  if (change === 'owner') { h.vm.detailTask.submitterId = 99; h.vm.detailTask.submitterId = 27 }
  if (change === 'assignment') { h.vm.detailTask.currentAssignee = 9; h.vm.detailTask.currentAssignee = 4 }
  if (change === 'drawer') { h.vm.detailOpen = false; h.vm.detailOpen = true }
  if (change === 'deactivate') for (const hook of h.vm.$options.deactivated) hook.call(h.vm)
  if (change === 'destroy') h.vm.$destroy()
  assert.equal(context.isCurrent(), false); assert.equal(h.vm.previewItems.length, 0)
  pending.reject({ code: 401 }); await request; assert.equal(h.vm.previewError, ''); assert.equal(h.vm.previewLoaded, false); h.vm.$destroy()
})

test('current permission denial clears text and blocks repeated reads until explicit detail reopen; local missing permission never sends', async () => {
  let calls = 0
  const h = mount({ getCandidatePreview: async () => { calls++; throw { code: 403, errorCode: 'CONTENT_PREVIEW_FORBIDDEN' } } })
  await h.vm.openDetail(candidate()); await h.vm.loadCandidatePreview(); assert.equal(h.vm.previewBlocked, true)
  assert.match(h.vm.previewError, /主题范围|任务分配/); h.vm.clearCandidatePreview(); await h.vm.loadCandidatePreview(); assert.equal(calls, 1)
  h.vm.detailOpen = false; h.vm.closeDetail(); await h.vm.openDetail(candidate()); await h.vm.loadCandidatePreview(); assert.equal(calls, 2)
  h.getters.permissions = ['business:audit:list']; await flush(); await h.vm.openDetail(candidate()); await h.vm.loadCandidatePreview(); assert.equal(calls, 2)
  h.vm.$destroy()
})

test('409 requires a fresh detail action and another explicit preview click, with no review or publication', async () => {
  let previews = 0
  const h = mount({ getCandidatePreview: async () => { previews++; throw { code: 409, errorCode: 'CONTENT_PREVIEW_CONFLICT' } } })
  await h.vm.openDetail(candidate()); const reads = h.reads.length; await h.vm.loadCandidatePreview()
  assert.equal(h.vm.previewBlocked, true); assert.equal(h.vm.previewNeedsRefresh, true); assert.equal(h.reads.length, reads)
  await h.vm.loadCandidatePreview(); assert.equal(previews, 1)
  await h.vm.loadDetail(31); assert.equal(h.reads.length, reads + 1); assert.equal(previews, 1); assert.equal(h.vm.canLoadCandidatePreview, true)
  await h.vm.loadCandidatePreview(); assert.equal(previews, 2); assert.equal(h.writes.length, 0); h.vm.$destroy()
})

test('unavailable and malformed responses never become stale conflicts or retain prior text', async () => {
  let count = 0
  const h = mount({ getCandidatePreview: async () => { if (++count === 1) return { code: 200, data: dto() }; throw { code: 503, errorCode: 'CONTENT_PREVIEW_UNAVAILABLE' } } })
  await h.vm.openDetail(candidate()); await h.vm.loadCandidatePreview(); await h.vm.loadCandidatePreview()
  assert.equal(h.vm.previewLoaded, false); assert.equal(h.vm.previewItems.length, 0); assert.equal(h.vm.previewNeedsRefresh, false)
  assert.equal(h.vm.previewBlocked, false); assert.match(h.vm.previewError, /暂不可用/); h.vm.$destroy()
})

test('API serializes only exact GET bindings and preserves typed error plus local request context', async () => {
  const apiSource = fs.readFileSync(path.join(root, 'src/api/business/auditWorkbench.js'), 'utf8').replace(/^import[^\n]*\n/gm, '').replace(/export /g, '')
  const calls = []; const get = new Function('request', apiSource + '\nreturn getCandidatePreview')(config => { calls.push(config) })
  const context = { isCurrent: () => true, captureCurrent: () => context }
  get(31, { expectedTaskVersion: 2, snapshotId: '19', sourceConfigVersion: '4', contentHash: 'b'.repeat(64), secret: 'never' }, context)
  assert.equal(calls[0].url, '/business/audit/31/candidate-preview'); assert.equal(calls[0].method, 'get')
  assert.equal(calls[0].auditContext, context); assert.equal(calls[0].preserveBusinessError, true); assert.equal(calls[0].data, undefined)
  assert.deepEqual(Object.keys(calls[0].params), ['expectedTaskVersion', 'snapshotId', 'sourceConfigVersion', 'contentHash'])
})

function requestInterceptorHarness() {
  const hooks = {}, effects = []
  const service = { interceptors: { request: { use(success) { hooks.request = success } }, response: { use(success, failure) { hooks.success = success; hooks.failure = failure } } } }
  const script = fs.readFileSync(path.join(root, 'src/utils/request.js'), 'utf8').replace(/^import[^\n]*\n/gm, '').replace(/export default service/, '').replace(/export /g, '')
  new Function('axios', 'errorCode', 'Message', 'Notification', 'MessageBox', 'store', 'console', 'getToken', 'cache', 'location', script)(
    { defaults: { headers: {} }, create: () => service }, {}, value => effects.push(['message', value]),
    { error: value => effects.push(['notification', value]) }, { confirm: () => { effects.push(['login']); return Promise.resolve() } },
    { dispatch: () => { effects.push(['logout']); return Promise.resolve() } }, { log() {}, warn() {} }, () => 'fixture-a',
    { session: { getJSON: () => null, setJSON() {} } }, { href: '/business/audit' })
  return { hooks, effects }
}

test('real shared interceptor rejects a cancelled preview before dispatch, notifications or expired-login effects', async () => {
  const pending = deferred(); let context
  const h = mount({ getCandidatePreview: (id, binding, capture) => { context = capture; return pending.promise } })
  await h.vm.openDetail(candidate()); const reading = h.vm.loadCandidatePreview(); h.vm.clearCandidatePreview()
  const actual = requestInterceptorHarness()
  const config = { auditContext: context, preserveBusinessError: true, method: 'get', url: '/business/audit/31/candidate-preview', headers: {} }
  await assert.rejects(Promise.resolve().then(() => actual.hooks.request(config)), { errorCode: 'AUDIT_CONTEXT_RETIRED' })
  assert.equal(config.headers.Authorization, undefined)
  await assert.rejects(actual.hooks.success({ config, request: {}, data: { code: 401 } }), { errorCode: 'AUDIT_CONTEXT_RETIRED' })
  await assert.rejects(actual.hooks.failure(Object.assign(new Error('403'), { config, response: { status: 403 } })), { errorCode: 'AUDIT_CONTEXT_RETIRED' })
  assert.deepEqual(actual.effects, []); pending.resolve({ code: 200, data: dto() }); await reading; h.vm.$destroy()
})

test('exact aggregate and count ceilings accept valid inert DTOs', async () => {
  const aggregate = await previewWith(dto({ entries: Array.from({ length: 8 }, () => ({ path: 'nodes[0].description', text: 'x'.repeat(4096) })) }))
  assert.equal(aggregate.vm.previewLoaded, true); aggregate.vm.$destroy()
  const count = await previewWith(dto({ entries: Array.from({ length: 256 }, () => ({ path: 'nodes[0].name', text: 'x' })) }))
  assert.equal(count.vm.previewLoaded, true); count.vm.$destroy()
})
