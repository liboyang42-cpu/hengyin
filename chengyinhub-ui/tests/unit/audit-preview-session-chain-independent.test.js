const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const Vue = require('vue')
Vue.config.productionTip = false
Vue.config.devtools = false
const src = path.resolve(__dirname, '../../src')
const read = file => fs.readFileSync(path.join(src, file), 'utf8').replace(/^import[^\n]*\n/gm, '')
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const flush = async () => { for (let n = 0; n < 12; n++) await Promise.resolve(); await Vue.nextTick() }
const task = () => ({ id: 31, bizType: 'CONTENT_CAPTURE_REVIEW', bizId: 14, submitterId: 27,
  currentAssignee: 4, sourceType: 'content_capture_review_v1', version: 2, status: 0,
  snapshotJson: JSON.stringify({ schemaVersion: 1, contract: 'content_capture_review_v1', snapshotId: 19,
    topicId: 14, submitterMemberId: 27, sourceConfigVersion: 4, contentHash: 'b'.repeat(64),
    candidateContract: 'questify.content-capture-candidate.v1', canonicalizationVersion: 'questify.canonical-content-json.v1',
    completeRelease: false, reviewScope: 'INCOMPLETE_CANDIDATE_ONLY' }) })

// Join the actual Vue caller, Axios response handler and Vuex logout action.
// Every request is an in-memory deferred fixture; no HTTP, credentials or browser effects are real.
function setup(overrides = {}) {
  const getters = Vue.observable({ token: 'fixture-a', id: 4, roles: ['admin'], permissions: ['business:audit:list', 'business:audit:review'] })
  const route = Vue.observable({ path: '/business/audit', fullPath: '/business/audit', query: {} })
  const hooks = {}, dialogs = [], notices = [], messages = [], logouts = [], writes = [], previewReads = []
  const logoutReply = deferred(), location = { href: '/business/audit' }
  let context
  const user = new Function('getToken', 'logout', 'removeToken', read('store/modules/user.js').replace(/export default user/, 'return user'))(
    () => getters.token, (token, boundContext) => { logouts.push({ token, context: boundContext }); return logoutReply.promise }, () => {})
  const store = { getters, dispatch(name, options) {
    assert.equal(name, 'LogOut')
    return user.actions.LogOut({ state: getters, commit(key, value) { user.mutations[key](getters, value) } }, options)
  } }
  const service = { interceptors: { request: { use(success) { hooks.request = success } }, response: { use(success, failure) { hooks.success = success; hooks.failure = failure } } } }
  new Function('axios', 'errorCode', 'Message', 'Notification', 'MessageBox', 'store', 'console', 'getToken', 'cache', 'location',
    read('utils/request.js').replace(/export default service/, '').replace(/export /g, ''))(
    { defaults: { headers: {} }, create: () => service }, {}, value => messages.push(value), { error: value => notices.push(value) },
    { confirm: () => { const dialog = deferred(); dialogs.push(dialog); return dialog.promise } }, store,
    { log() {}, warn() {} }, () => getters.token, { session: { getJSON: () => null, setJSON() {} } }, location)
  const deps = {
    getAuditTaskDetail: async () => ({ task: task(), records: [], reportCount: 0 }), listAuditTasks: async () => ({ rows: [], total: 0 }),
    reviewAuditTask: async value => { writes.push(value) }, submitTopicProjectReview: async value => { writes.push(value) },
    submitTopicSaleReview: async value => { writes.push(value) },
    getCandidatePreview: (id, binding, captured) => {
      context = captured
      const reply = deferred(); previewReads.push(reply)
      return reply.promise.then(data => hooks.success({ config: { auditContext: captured, preserveBusinessError: true }, request: {}, data }))
    },
    ...overrides
  }
  const script = read('views/business/audit/index.vue').match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/components:\s*\{[^}]+\},/, 'components: {},').replace(/export\s+default\s*/, 'return ')
  const options = new Function(...Object.keys(deps), script)(...Object.values(deps))
  const vm = new Vue({ ...options, beforeCreate() { this.$route = route; this.$store = store; this.$modal = { msgSuccess() {} } } })
  return { vm, getters, route, hooks, dialogs, notices, messages, logouts, logoutReply, location, writes, previewReads, context: () => context }
}
async function expiredPreview(h) {
  await h.vm.openDetail(task())
  const reading = h.vm.loadCandidatePreview()
  h.previewReads.at(-1).resolve({ code: 401, msg: 'Expired fixture' })
  await reading
  assert.equal(h.dialogs.length, 1)
  assert.equal(h.vm.previewBlocked, true)
  return h.context()
}
function retire(h, name) {
  if (name === 'cancel') h.vm.clearCandidatePreview()
  else if (name === 'drawer ABA') { h.vm.detailOpen = false; h.vm.detailOpen = true }
  else if (name === 'task ABA') { const original = h.vm.detailTask; h.vm.detailTask = task(); h.vm.detailTask.version++; h.vm.detailTask = original }
  else if (name === 'route ABA') { h.route.fullPath = '/business/audit?other=1'; h.route.fullPath = '/business/audit' }
  else {
    const original = h.getters[name]
    h.getters[name] = name === 'id' ? 5 : name === 'token' ? 'fixture-b' : ['different']
    h.getters[name] = original
  }
}

test('independent preview-specific current 401 preserves the complete existing confirmation/logout/redirect chain', async () => {
  const h = setup(), context = await expiredPreview(h)
  assert.equal(context.isCurrent(), true)
  h.dialogs[0].resolve(); await flush()
  assert.equal(h.logouts.length, 1); assert.equal(h.logouts[0].context, context); assert.equal(h.logouts[0].token, 'fixture-a')
  h.logoutReply.resolve(); await flush()
  assert.equal(h.getters.token, ''); assert.equal(h.vm.detailOpen, false); assert.equal(h.vm.previewItems.length, 0)
  assert.equal(h.location.href, '/index'); assert.deepEqual(h.writes, []); h.vm.$destroy()
})

for (const change of ['cancel', 'drawer ABA', 'task ABA', 'route ABA', 'id', 'token', 'roles', 'permissions']) {
  test(`independent preview ${change} while 401 confirmation waits cannot log out or redirect`, async () => {
    const h = setup(), context = await expiredPreview(h)
    retire(h, change); assert.equal(context.isCurrent(), false)
    h.dialogs[0].resolve(); await flush()
    assert.equal(h.logouts.length, 0); assert.equal(h.getters.token, 'fixture-a'); assert.equal(h.location.href, '/business/audit')
    assert.deepEqual(h.writes, []); h.vm.$destroy()
  })
  test(`independent preview ${change} during logout cannot clear the restored session or redirect`, async () => {
    const h = setup(), context = await expiredPreview(h)
    h.dialogs[0].resolve(); await flush(); assert.equal(h.logouts.length, 1)
    retire(h, change); assert.equal(context.isCurrent(), false)
    h.logoutReply.resolve(); await flush()
    assert.equal(h.getters.token, 'fixture-a'); assert.equal(h.location.href, '/business/audit'); assert.deepEqual(h.writes, []); h.vm.$destroy()
  })
}

test('independent retired preview 401 cannot present a dialog or overwrite a newer read', async () => {
  const h = setup(); await h.vm.openDetail(task())
  const old = h.vm.loadCandidatePreview(); h.vm.clearCandidatePreview(); const current = h.vm.loadCandidatePreview()
  h.previewReads[0].resolve({ code: 401, msg: 'Old expiry' }); await old
  assert.equal(h.dialogs.length, 0); assert.equal(h.vm.previewLoading, true); assert.equal(h.vm.previewError, '')
  h.previewReads[1].resolve({ code: 503, errorCode: 'CONTENT_PREVIEW_UNAVAILABLE' }); await current
  assert.equal(h.dialogs.length, 0); assert.equal(h.vm.previewLoading, false); assert.equal(h.vm.previewBlocked, false)
  assert.deepEqual(h.logouts, []); h.vm.$destroy()
})

for (const code of [403, 409]) test(`independent failed reread after ${code} cannot reuse old binding to unlock preview`, async () => {
  let reads = 0
  const h = setup({ getAuditTaskDetail: async () => { if (++reads === 1) return { task: task(), records: [], reportCount: 0 }; throw new Error('Fixture detail unavailable') } })
  await h.vm.openDetail(task())
  const reading = h.vm.loadCandidatePreview(); h.previewReads[0].resolve({ code }); await reading
  assert.equal(h.vm.previewBlocked, true)
  await h.vm.loadDetail(31)
  assert.equal(reads, 2); assert.equal(h.vm.detailTask, null); assert.ok(h.vm.detailError)
  await h.vm.loadCandidatePreview(); assert.equal(h.previewReads.length, 1); assert.equal(h.vm.canLoadCandidatePreview, false)
  assert.deepEqual(h.writes, []); h.vm.$destroy()
})
