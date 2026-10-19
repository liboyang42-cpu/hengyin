const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const Vue = require('vue')
Vue.config.productionTip = false
Vue.config.devtools = false
const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/audit/index.vue'), 'utf8')

const candidate = (overrides = {}) => ({
  id: 31, bizType: 'CONTENT_CAPTURE_REVIEW', bizId: 14, submitterId: 27,
  sourceType: 'content_capture_review_v1', version: 2, status: 0,
  snapshotJson: JSON.stringify({ schemaVersion: 1, contract: 'content_capture_review_v1', snapshotId: 19,
    topicId: 14, submitterMemberId: 27, sourceConfigVersion: 4, contentHash: 'b'.repeat(64),
    candidateContract: 'questify.content-capture-candidate.v1', canonicalizationVersion: 'questify.canonical-content-json.v1',
    completeRelease: false, reviewScope: 'INCOMPLETE_CANDIDATE_ONLY' }), ...overrides
})
const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const flush = async () => { await Vue.nextTick(); await Promise.resolve(); await Vue.nextTick() }
function mount(overrides = {}) {
  const deps = { getAuditTaskDetail: async id => ({ task: candidate({ id }), records: [], reportCount: 0 }),
    listAuditTasks: async () => ({ rows: [], total: 0 }), reviewAuditTask: async () => ({ code: 200 }),
    submitTopicProjectReview: async () => ({ code: 200 }), submitTopicSaleReview: async () => ({ code: 200 }), ...overrides }
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^\s*import[^\n]*\n/gm, '').replace(/components:\s*\{[^}]+\},/, 'components: {},')
    .replace(/export\s+default\s*/, 'return ')
  const component = new Function(...Object.keys(deps), script)(...Object.values(deps))
  const route = Vue.observable({ path: '/business/audit', fullPath: '/business/audit?bizId=14', query: { bizId: '14' } })
  const getters = Vue.observable({ token: 'fixture-session-a', id: 4, permissions: ['business:audit:list', 'business:audit:review'], roles: ['admin'] })
  const toasts = []
  const vm = new Vue({ ...component, beforeCreate() {
    this.$route = route; this.$store = { getters }; this.$modal = { msgSuccess: message => toasts.push(message) }
  } })
  return { vm, route, getters, toasts }
}
function lifecycle(vm, name) { for (const hook of vm.$options[name] || []) hook.call(vm) }

test('route ABA invalidates pending list even when fullPath returns to its original value before the next tick', async () => {
  const old = deferred(); let calls = 0
  const { vm, route } = mount({ listAuditTasks: () => ++calls === 1 ? old.promise : Promise.resolve({ rows: [candidate({ id: 32 })], total: 1 }) })
  route.fullPath = '/business/audit?bizId=99'; route.query = { bizId: '99' }
  route.fullPath = '/business/audit?bizId=14'; route.query = { bizId: '14' }
  await flush(); old.resolve({ rows: [candidate()], total: 99 }); await flush()
  assert.notEqual(vm.auditList[0] && vm.auditList[0].id, 31)
  assert.notEqual(vm.total, 99); vm.$destroy()
})

test('same-route query navigation resets filters, closes old review, and ignores its delayed candidate binding', async () => {
  const old = deferred()
  const { vm, route } = mount({ getAuditTaskDetail: () => old.promise })
  const read = vm.openReview(candidate(), 1)
  route.query = { bizType: 'ACTIVITY', bizId: '99' }; route.fullPath = '/business/audit?bizType=ACTIVITY&bizId=99'
  await flush(); old.resolve({ task: candidate({ version: 90 }) }); await read
  assert.equal(vm.queryParams.bizId, '99'); assert.equal(vm.queryParams.bizType, 'ACTIVITY')
  assert.equal(vm.reviewOpen, false); assert.equal(vm.reviewTask, null); assert.equal(vm.reviewForm.taskId, null)
  vm.$destroy()
})

for (const key of ['token', 'id']) test(`${key} ABA invalidates pending review with real Vue synchronous observation`, async () => {
  const old = deferred()
  const { vm, getters } = mount({ getAuditTaskDetail: () => old.promise })
  const read = vm.openReview(candidate(), 1); const previous = getters[key]
  getters[key] = key === 'token' ? 'fixture-session-b' : 8; getters[key] = previous
  old.resolve({ task: candidate({ version: 90 }) }); await read; await flush()
  assert.equal(vm.reviewOpen, false); assert.equal(vm.reviewTask, null)
  assert.equal(vm.reviewRetryPayload, null); vm.$destroy()
})

test('permission and role changes clear already-loaded evidence and pending retry without changing permission policy', async () => {
  const { vm, getters } = mount({ reviewAuditTask: async () => { throw new Error('timeout') } })
  await vm.openDetail(candidate()); await vm.openReview(candidate(), 1); await vm.submitReview()
  assert.ok(vm.reviewRetryPayload)
  getters.permissions = ['business:audit:list']; getters.roles = ['viewer']; await flush()
  assert.equal(vm.reviewOpen, false); assert.equal(vm.reviewRetryPayload, null)
  assert.equal(vm.detailTask, null); assert.equal(vm.detailOpen, false); vm.$destroy()
})

test('cache deactivation clears evidence, prevents delayed reads and reloads only on activation', async () => {
  const old = deferred(); let lists = 0
  const { vm } = mount({ getAuditTaskDetail: () => old.promise, listAuditTasks: async () => { lists++; return { rows: [], total: 0 } } })
  const read = vm.openDetail(candidate()); const initialLists = lists
  lifecycle(vm, 'deactivated'); old.resolve({ task: candidate() }); await read; await flush()
  assert.equal(vm.detailTask, null); assert.equal(vm.detailOpen, false); assert.equal(lists, initialLists)
  lifecycle(vm, 'activated'); await flush(); assert.equal(lists, initialLists + 1); vm.$destroy()
})

test('destroyed audit component cannot accept old data or display late write success', async () => {
  const old = deferred(); const write = deferred(); let lists = 0, writes = 0
  const { vm, toasts } = mount({
    listAuditTasks: () => ++lists === 1 ? Promise.resolve({ rows: [], total: 0 }) : old.promise,
    reviewAuditTask: () => { writes++; return write.promise }
  })
  await flush(); assert.equal(vm.loading, false)
  await vm.openReview(candidate(), 1); const request = vm.submitReview()
  assert.equal(writes, 1); assert.equal(typeof request.then, 'function'); assert.equal(vm.reviewSubmitting, true)
  const list = vm.getList(); assert.equal(lists, 2); vm.$destroy()
  old.resolve({ rows: [candidate()], total: 88 }); write.resolve({ code: 200 })
  await Promise.all([request, list]); await flush()
  assert.equal(vm.auditList.length, 0); assert.equal(vm.total, 0); assert.equal(toasts.length, 0)
  assert.equal(lists, 2); assert.equal(writes, 1)
})

test('old write finalizer cannot unlock a newer-context write after credential change', async () => {
  const writes = [deferred(), deferred()]; let calls = 0
  const { vm, getters, toasts } = mount({ reviewAuditTask: () => writes[calls++].promise })
  await flush(); assert.equal(vm.loading, false)
  await vm.openReview(candidate(), 1); const first = vm.submitReview()
  getters.token = 'fixture-session-b'; await flush()
  await vm.openReview(candidate({ id: 32 }), 1); const second = vm.submitReview()
  assert.equal(calls, 2); assert.equal(vm.reviewSubmitting, true)
  writes[0].resolve({ code: 200 }); await first
  assert.equal(vm.reviewSubmitting, true); assert.equal(vm.reviewForm.taskId, 32); assert.equal(toasts.length, 0)
  writes[1].resolve({ code: 200 }); await second; assert.equal(vm.reviewSubmitting, false); assert.equal(toasts.length, 1)
  vm.$destroy()
})

test('unknown-outcome refresh cannot restore a retired identity or trigger an automatic write', async () => {
  const current = deferred(); let reads = 0, writes = 0
  const { vm, getters } = mount({ getAuditTaskDetail: () => ++reads === 1 ? Promise.resolve({ task: candidate() }) : current.promise,
    reviewAuditTask: async () => { writes++; throw new Error('Network Error') } })
  await flush(); assert.equal(vm.loading, false)
  await vm.openReview(candidate(), 2); vm.reviewForm.reasonText = '需要补证'; const request = vm.submitReview(); await flush()
  getters.id = 9; await flush(); current.resolve({ task: candidate({ status: 2, version: 3 }) }); await request
  assert.equal(vm.reviewCurrentTask, null); assert.equal(vm.reviewRetryPayload, null); assert.equal(writes, 1); vm.$destroy()
})

test('candidate task versions beyond safe integer range fail closed', () => {
  const { vm } = mount()
  assert.equal(vm.canReview(candidate({ version: Number.MAX_SAFE_INTEGER + 1 })), false)
  vm.detailTask = candidate({ version: Number.MAX_SAFE_INTEGER + 1 }); assert.equal(vm.auditEvidenceItems.length, 0); vm.$destroy()
})

test('known 500 response remains unknown and explicit exact retry keeps the original fields', async () => {
  const payloads = []
  const { vm } = mount({ reviewAuditTask: async value => { payloads.push(value); throw { code: 500, errorCode: 'AUDIT_REVIEW_FAILED' } } })
  await flush(); assert.equal(vm.loading, false)
  await vm.openReview(candidate(), 2); vm.reviewForm.reasonText = '原始原因'; await vm.submitReview()
  assert.equal(vm.reviewBlocked, false); assert.ok(vm.reviewRetryPayload); assert.equal(payloads.length, 1)
  vm.reviewForm.reasonText = '不同原因'; vm.reviewForm.status = 1; await vm.submitReview()
  assert.deepEqual(payloads[1], payloads[0]); assert.equal(payloads.length, 2); vm.$destroy()
})

test('late failed old list cannot erase current filters and results', async () => {
  const old = deferred(); let calls = 0
  const { vm } = mount({ listAuditTasks: () => ++calls === 1 ? old.promise : Promise.resolve({ rows: [candidate({ id: 32 })], total: 1 }) })
  await vm.getList(); old.reject(new Error('old offline')); await flush()
  assert.equal(vm.auditList[0].id, 32); assert.equal(vm.total, 1); vm.$destroy()
})

test('successful write releases single-flight before the dialog close hook invalidates its sequence', async () => {
  const { vm } = mount()
  vm.$watch('reviewOpen', visible => { if (!visible) vm.closeReview() })
  await vm.openReview(candidate(), 1); await vm.submitReview(); await flush()
  assert.equal(vm.reviewSubmitting, false)
  await vm.openReview(candidate({ id: 32 }), 1); assert.equal(vm.reviewForm.taskId, 32); vm.$destroy()
})

test('late dialog and drawer close events cannot invalidate newly reopened reads', async () => {
  const detail = deferred(); const review = deferred(); let reads = 0
  const { vm } = mount({ getAuditTaskDetail: () => ++reads === 1 ? detail.promise : review.promise })
  const loadingDetail = vm.openDetail(candidate()); const loadingReview = vm.openReview(candidate(), 1)
  vm.closeDetail(); vm.closeReview()
  assert.equal(vm.reviewLoading, true); assert.equal(vm.detailLoading, true)
  detail.resolve({ task: candidate(), records: [], reportCount: 0 }); review.resolve({ task: candidate({ version: 3 }) })
  await loadingDetail; await loadingReview
  assert.equal(vm.detailTask.id, 31); assert.equal(vm.reviewForm.expectedVersion, 3); vm.$destroy()
})
