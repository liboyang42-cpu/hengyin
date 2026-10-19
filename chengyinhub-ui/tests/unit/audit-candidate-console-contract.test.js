const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ui = path.resolve(__dirname, '../..')
const source = fs.readFileSync(path.join(ui, 'src/views/business/audit/index.vue'), 'utf8')

function load(overrides = {}) {
  const dependencies = {
    getAuditTaskDetail: async id => ({ task: candidate({ id }), records: [], reportCount: 0 }),
    listAuditTasks: async () => ({ rows: [], total: 0 }),
    reviewAuditTask: async () => ({ code: 200 }),
    ...overrides
  }
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^\s*import[^\n]*\n/gm, '')
    .replace(/components:\s*\{[^}]+\},/, 'components: {},')
    .replace(/export\s+default\s*/, 'return ')
  const component = new Function(...Object.keys(dependencies), script)(...Object.values(dependencies))
  const vm = { ...component.data(), ...component.methods, $modal: { msgSuccess() {} }, parseTime: () => '' }
  for (const [key, getter] of Object.entries(component.computed)) Object.defineProperty(vm, key, { get: () => getter.call(vm) })
  return vm
}
function candidate(overrides = {}, envelope = {}) {
  return {
    id: 31, bizType: 'CONTENT_CAPTURE_REVIEW', bizId: 14, submitterId: 27,
    sourceType: 'content_capture_review_v1', version: 2, status: 0,
    snapshotJson: JSON.stringify({ schemaVersion: 1, contract: 'content_capture_review_v1', snapshotId: 19,
      topicId: 14, submitterMemberId: 27, sourceConfigVersion: 4, contentHash: 'b'.repeat(64),
      candidateContract: 'questify.content-capture-candidate.v1', canonicalizationVersion: 'questify.canonical-content-json.v1',
      completeRelease: false, reviewScope: 'INCOMPLETE_CANDIDATE_ONLY', ...envelope }), ...overrides
  }
}
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

test('real discriminator is selectable; metadata has candidate/source/hash/version and explicitly lacks content/publication', () => {
  const vm = load()
  assert.equal(vm.bizTypeLabel('CONTENT_CAPTURE_REVIEW'), '内容候选审核（不发布）')
  vm.detailTask = candidate()
  const text = vm.auditEvidenceItems.map(item => item.content).join('\n')
  for (const expected of ['候选快照 ID：19', '来源配置版本：4', '审核任务版本：2', 'b'.repeat(64), 'content_capture_review_v1', 'INCOMPLETE_CANDIDATE_ONLY', '正文：需主动读取部分白名单文本', '不赋予发布资格']) assert.ok(text.includes(expected), expected)
  assert.ok(vm.canReview(vm.detailTask))
  assert.equal(vm.candidateDetailError, '')
  vm.detailRecords = [{ id: 3, action: 'SUBMIT', snapshotJson: '{}' }]
  assert.deepEqual(vm.auditTimelineItems[0].evidence, ['附有当时候选绑定元数据，无正文'])
})

test('candidate display is bounded and whitelisted; unknown nested payload is never rendered or fetched', () => {
  const vm = load()
  vm.detailTask = candidate({}, { payload: { title: '<img src=x onerror=bad()>', nested: { secret: 'DO_NOT_RENDER' } } })
  assert.doesNotMatch(vm.auditEvidenceItems[0].content, /DO_NOT_RENDER|onerror|payload/)
  assert.ok(vm.auditEvidenceItems[0].content.length < 1000)
  vm.detailTask.snapshotJson = 'x'.repeat(4097)
  assert.deepEqual(vm.auditEvidenceItems, [])
  assert.ok(vm.candidateDetailError)
  assert.equal(vm.canReview(vm.detailTask), false)
})

test('malformed, spoofed, unsafe, wrong-scope or identity-mismatched envelopes fail closed', () => {
  const vm = load()
  const bad = [candidate({ snapshotJson: '{' }), candidate({ sourceType: 'topic_bundle_v2' }),
    candidate({}, { schemaVersion: 2 }), candidate({}, { contract: 'future' }), candidate({}, { completeRelease: true }),
    candidate({}, { reviewScope: 'FULL_RELEASE' }), candidate({}, { topicId: 99 }), candidate({}, { submitterMemberId: 99 }),
    candidate({}, { snapshotId: 9007199254740992 }), candidate({ id: 9007199254740992 }), candidate({}, { snapshotId: '19' }),
    candidate({}, { sourceConfigVersion: -1 }), candidate({}, { contentHash: 'x'.repeat(64) }), candidate({ version: null }), candidate({ status: 9 })]
  for (const row of bad) { vm.detailTask = row; assert.equal(vm.canReview(row), false); assert.deepEqual(vm.auditEvidenceItems, []); assert.ok(vm.candidateDetailError) }
})

test('legacy TOPIC remains separate; no invented versioned type, member status API, or mutable topic fetch', () => {
  const vm = load()
  assert.equal(vm.isCandidateTask({ bizType: 'TOPIC' }), false)
  vm.detailTask = { bizType: 'TOPIC', snapshotJson: '{"title":"旧主题"}' }
  assert.match(vm.auditEvidenceItems[0].content, /旧主题/)
  assert.doesNotMatch(source, /VERSIONED_TOPIC|VERSIONED_ACTIVITY|\/api\/content-capture-review|selectCmsTopic|getTopic\(/)
  assert.match(source, /v-hasPermi="\['business:audit:review'\]"/)
  const api = fs.readFileSync(path.join(ui, 'src/api/business/auditWorkbench.js'), 'utf8')
  assert.match(api, /url: '\/business\/audit\/review'/)
  assert.doesNotMatch(api, /content-capture-review/)
})

test('candidate review reads current task and binds fresh integer expectedVersion before enabling confirmation', async () => {
  const read = deferred(); let writes = 0
  const vm = load({ getAuditTaskDetail: () => read.promise, reviewAuditTask: () => writes++ })
  const open = vm.openReview(candidate({ version: 1 }), 1)
  assert.equal(vm.reviewLoading, true)
  vm.submitReview(); assert.equal(writes, 0)
  read.resolve({ task: candidate({ version: 5 }) }); await open
  assert.equal(vm.reviewLoading, false)
  assert.deepEqual(vm.reviewForm, { taskId: 31, expectedVersion: 5, status: 1, reasonText: null })
  assert.equal(vm.reviewCurrentTask.version, 5)
})

test('missing/wrong/terminal/failed fresh candidate read blocks action without falling back to old row', async () => {
  for (const result of [{}, { task: candidate({ id: 999 }) }, { task: candidate({ status: 1 }) }, { task: candidate({}, { contentHash: 'bad' }) }]) {
    let writes = 0
    const vm = load({ getAuditTaskDetail: async () => result, reviewAuditTask: async () => { writes++ } })
    await vm.openReview(candidate(), 1); await vm.submitReview()
    assert.equal(vm.reviewBlocked, true); assert.ok(vm.reviewError); assert.equal(writes, 0)
  }
  const vm = load({ getAuditTaskDetail: async () => { throw new Error('network') } })
  await vm.openReview(candidate(), 2); assert.equal(vm.reviewBlocked, true)
})

test('late candidate read cannot reopen cancelled review or replace a newer selection', async () => {
  const old = deferred()
  const vm = load({ getAuditTaskDetail: id => id === 31 ? old.promise : Promise.resolve({ task: candidate({ id }), records: [], reportCount: 0 }) })
  const opening = vm.openReview(candidate(), 1)
  vm.reviewOpen = false; vm.closeReview()
  await vm.openReview(candidate({ id: 32 }), 2)
  old.resolve({ task: candidate({ version: 99 }) }); await opening
  assert.equal(vm.reviewForm.taskId, 32); assert.equal(vm.reviewForm.expectedVersion, 2); assert.equal(vm.reviewForm.status, 2)
})

test('pending send is single-flight and cannot switch review target', async () => {
  const send = deferred(); const payloads = []
  const vm = load({ reviewAuditTask: payload => { payloads.push(payload); return send.promise } })
  await vm.openReview(candidate(), 1)
  const first = vm.submitReview(); vm.submitReview(); vm.openReview(candidate({ id: 32 }), 2)
  assert.equal(payloads.length, 1); assert.equal(vm.reviewForm.taskId, 31); assert.equal(vm.reviewSubmitting, true)
  send.resolve({ code: 200 }); await first
  assert.equal(vm.reviewSubmitting, false); assert.equal(vm.reviewOpen, false); assert.equal(vm.reviewRetryPayload, null)
})

test('unknown response failure retains exact request for explicit retry despite changed current task', async () => {
  const payloads = []; let current = candidate()
  const vm = load({ getAuditTaskDetail: async () => ({ task: current }), reviewAuditTask: async payload => {
    payloads.push(payload)
    if (payloads.length === 1) { current = candidate({ status: 2, version: 3 }); throw new Error('timeout') }
    return { code: 200 }
  } })
  await vm.openReview(candidate(), 2); vm.reviewForm.reasonText = '待补证据'
  await vm.submitReview()
  assert.equal(vm.reviewOpen, true); assert.equal(vm.reviewSubmitting, false); assert.equal(vm.reviewCurrentTask.version, 3)
  assert.equal(vm.reviewForm.expectedVersion, 2); assert.ok(vm.reviewRetryPayload)
  vm.reviewForm.reasonText = '不能改写这次重试'; vm.reviewForm.expectedVersion = 3
  await vm.submitReview()
  assert.deepEqual(payloads[1], payloads[0]); assert.equal(payloads.length, 2)
})

test('409 and audit stale error refresh state and block automatic or repeated writes', async () => {
  for (const error of [{ code: 409 }, { errorCode: 'AUDIT_STATE_CONFLICT' }, { errorCode: 'AUDIT_SNAPSHOT_STALE' }]) {
    let writes = 0; let reads = 0
    const vm = load({ getAuditTaskDetail: async () => { reads++; return { task: candidate({ version: reads }) } },
      reviewAuditTask: async () => { writes++; throw error } })
    await vm.openReview(candidate(), 1); await vm.submitReview(); await vm.submitReview()
    assert.equal(writes, 1); assert.equal(reads, 2); assert.equal(vm.reviewBlocked, true)
    assert.equal(vm.reviewForm.expectedVersion, 1); assert.equal(vm.reviewCurrentTask.version, 2); assert.equal(vm.reviewRetryPayload, null)
    assert.match(vm.reviewError, /未自动重提/)
  }
})

test('rejection reason required before network and 400 remains editable; auth errors block', async () => {
  let writes = 0
  const vm = load({ reviewAuditTask: async () => { writes++; throw { code: 400, errorCode: 'REJECT_REASON_REQUIRED' } } })
  await vm.openReview(candidate(), 2); await vm.submitReview(); assert.equal(writes, 0)
  vm.reviewForm.reasonText = '原因'; await vm.submitReview()
  assert.equal(writes, 1); assert.equal(vm.reviewRetryPayload, null); assert.equal(vm.reviewBlocked, false)
  for (const code of [401, 403]) {
    const authVm = load({ reviewAuditTask: async () => { throw { code } } })
    await authVm.openReview(candidate(), 1); await authVm.submitReview(); assert.equal(authVm.reviewBlocked, true)
  }
})

test('detail failures clear stale snapshots; late reads cannot replace newer detail or survive close', async () => {
  const old = deferred()
  const vm = load({ getAuditTaskDetail: id => id === 31 ? old.promise : Promise.resolve({ task: candidate({ id }), records: [], reportCount: 0 }) })
  const first = vm.openDetail(candidate())
  await vm.openDetail(candidate({ id: 32 })); old.resolve({ task: candidate() }); await first
  assert.equal(vm.detailTask.id, 32)
  const failVm = load({ getAuditTaskDetail: async () => { throw new Error('unavailable') } })
  failVm.detailTask = candidate(); await failVm.openDetail(candidate())
  assert.equal(failVm.detailTask, null); assert.ok(failVm.detailError)
  const late = deferred(); const closing = load({ getAuditTaskDetail: () => late.promise })
  const fetch = closing.openDetail(candidate()); closing.detailOpen = false; closing.closeDetail()
  late.resolve({ task: candidate() }); await fetch; assert.equal(closing.detailTask, null)
})

test('failed response plus failed refresh remains retryable, with no automatic write', async () => {
  let reads = 0, writes = 0
  const vm = load({ getAuditTaskDetail: async () => { if (++reads > 1) throw new Error('offline'); return { task: candidate() } },
    reviewAuditTask: async () => { writes++; throw new Error('offline') } })
  await vm.openReview(candidate(), 1); await vm.submitReview()
  assert.equal(writes, 1); assert.equal(vm.reviewSubmitting, false); assert.ok(vm.reviewRetryPayload)
  assert.match(vm.reviewError, /最新状态暂时无法读取/)
})

test('merchant and legacy topic reviews keep their original route, integer status and reason DTO', async () => {
  for (const bizType of ['merchant_node', 'merchant_node_template', 'TOPIC', 'ACTIVITY']) {
    const payloads = []; let detailReads = 0
    const vm = load({ getAuditTaskDetail: async () => { detailReads++; return {} }, reviewAuditTask: async payload => { payloads.push(payload); return { code: 200 } } })
    vm.openReview({ id: 44, version: 7, bizType, status: 0 }, 2)
    vm.reviewForm.reasonText = '需修改'
    await vm.submitReview()
    assert.equal(detailReads, 0)
    assert.deepEqual(payloads, [{ taskId: 44, expectedVersion: 7, status: 2, reasonText: '需修改' }])
  }
})

test('newer list request wins even when an older response resolves later', async () => {
  const old = deferred(); let calls = 0
  const vm = load({ listAuditTasks: async () => ++calls === 1 ? old.promise : { rows: [candidate({ id: 32 })], total: 1 } })
  const first = vm.getList(); await vm.getList()
  old.resolve({ rows: [candidate()], total: 999 }); await first
  assert.equal(vm.auditList[0].id, 32); assert.equal(vm.total, 1); assert.equal(vm.loading, false)
})
