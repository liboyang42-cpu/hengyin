const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { webcrypto } = require('node:crypto')

const root = path.resolve(__dirname, '../..')
const source = fs.readFileSync(path.join(root, 'src/views/business/orient-scoring/components/OrientScoringConsole.vue'), 'utf8')
const apiSource = fs.readFileSync(path.join(root, 'src/api/business/orientScoring.js'), 'utf8')
const ruoyiSource = fs.readFileSync(path.join(root, 'src/utils/ruoyi.js'), 'utf8')
const tansParams = new Function(`${ruoyiSource.replace(/export function /g, 'function ')}; return tansParams`)()
const sid = '9223372036854775807'
const aid = '9007199254740993'
const eid = '9007199254740994'
const rid = '9007199254740995'
const ok = data => ({ code: 200, data })
const clone = value => JSON.parse(JSON.stringify(value))

function loadApi(request = () => {}) {
  const script = apiSource.replace(/^import .*$/gm, '').replace(/export function /g, 'function ')
  return new Function('request', 'tansParams', `${script}; return { scoringId, parseScoringResponse, formatCenti, adjustmentCenti, listScoringAppeals, listScoringDisputes, getScoringSession, verifyScoringSession, resolveScoringAppeal, resolveScoringDispute, reverseScoringEntry }`)(request, tansParams)
}
const api = loadApi()
const entry = (delta = -125) => ({ entryId: eid, deltaCenti: delta, runningCenti: delta, dimension: 'PENALTY', reversesEntryId: null, reasonCode: 'LATE', sourceType: 'SYSTEM', nodeId: '9' })
const appeal = (overrides = {}) => ({ id: aid, sessionId: sid, memberId: '9007199254740996', status: 0, entryId: eid, nodeId: '9', resolutionEntryId: null, resolutionNote: null, handledBy: null, reason: '任务记录有误', ...overrides })
function detail(overrides = {}) {
  return { sessionId: sid, topicId: '7', activityId: '8', status: 2, totalCenti: -125, byDimension: { PENALTY: -125 }, timeline: [entry()], appeals: [appeal()], disputes: [appeal({ id: '22', entryId: null })], settlement: null, ...overrides }
}
const verification = total => ({ sessionId: sid, ledgerTotalCenti: total, settled: false, consistent: true })
function storage() {
  const data = new Map()
  return { getItem: key => data.get(key) || null, setItem: (key, value) => data.set(key, value), removeItem: key => data.delete(key) }
}
function createVm(deps = {}, session = storage()) {
  const dependencies = {
    ...api, checkPermi: () => true,
    listScoringAppeals: async () => ok([appeal()]), listScoringDisputes: async () => ok([]),
    getScoringSession: async () => ok(detail()), verifyScoringSession: async () => ok(verification(-125)),
    resolveScoringAppeal: async () => ok(appeal({ status: 2 })), resolveScoringDispute: async () => ok(appeal({ id: '22', status: 2 })),
    reverseScoringEntry: async () => ok({ id: rid, sessionId: sid }), ...deps
  }
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ ([^}]+) \} from .*$/gm, 'const { $1 } = dependencies')
    .replace(/^import (Cy\w+) from .*$/gm, 'const $1 = {}')
    .replace('export default', 'return')
  const component = new Function('dependencies', 'sessionStorage', 'window', script)(dependencies, session, { crypto: webcrypto })
  const messages = []
  const emitted = []
  const vm = Object.assign(component.data(), {
    visible: true, $store: { getters: { id: 7, name: 'reviewer' } },
    $modal: { confirm: async () => {}, msgError: value => messages.push(value) }, $emit: (...args) => emitted.push(args)
  })
  for (const [key, method] of Object.entries(component.methods)) vm[key] = method.bind(vm)
  for (const [key, getter] of Object.entries(component.computed)) Object.defineProperty(vm, key, { get: getter.bind(vm) })
  return { vm, session, messages, emitted }
}
async function inspect(vm, kind = 'appeals') { await vm.inspect(appeal({ id: kind === 'appeals' ? aid : '22' }), kind) }

test('API preserves exact Long IDs and large signed totals without touching quoted text', () => {
  const raw = '{"code":200,"data":{"sessionId":9223372036854775807,"totalCenti":-9007199254740993,"text":"数字 9223372036854775807, \\\"id\\\":9223372036854775806","delta":-125}}'
  const parsed = api.parseScoringResponse(raw)
  assert.equal(parsed.data.sessionId, sid)
  assert.equal(parsed.data.totalCenti, '-9007199254740993')
  assert.match(parsed.data.text, /9223372036854775806/)
  assert.equal(parsed.data.delta, -125)
  assert.equal(api.scoringId(sid), sid)
  assert.equal(api.scoringId(42), '42')
  for (const id of [9223372036854775807, '9223372036854775808', '0', '-1', '01', '1e3', null, undefined]) assert.throws(() => api.scoringId(id))
})

test('signed centi formatting stays exact; adjustments enforce nonzero int32 integers', () => {
  assert.equal(api.formatCenti('-9007199254740993'), '-90071992547409.93 分')
  assert.equal(api.formatCenti(-125), '-1.25 分')
  assert.equal(api.formatCenti(5), '+0.05 分')
  assert.equal(api.formatCenti(0), '0.00 分')
  assert.equal(api.formatCenti(9007199254740992), '分值精度异常')
  assert.equal(api.adjustmentCenti(' -2147483647 '), '-2147483647')
  assert.equal(api.adjustmentCenti('2147483647'), '2147483647')
  for (const value of ['0', '-0', '-2147483648', '2147483648', '-2147483649', '1.1', '1e2', '', 'NaN']) assert.throws(() => api.adjustmentCenti(value))
})

test('API uses all seven actual admin contracts, form booleans and stable reverse key; never invents requestId', async () => {
  const requests = []
  const client = loadApi(config => { requests.push(config); return Promise.resolve(ok([])) })
  await client.listScoringAppeals('')
  await client.listScoringDisputes(0)
  await client.getScoringSession(sid)
  await client.verifyScoringSession(sid)
  await client.resolveScoringAppeal(aid, { accept: true, adjustCenti: '-125', note: '负分 & 修正' })
  await client.resolveScoringDispute('22', { accept: false, note: '' })
  await client.reverseScoringEntry(eid, { reasonCode: 'REVERSAL', idempotencyKey: 'admin-reverse:stable' })
  assert.deepEqual(requests.map(r => [r.url, r.method]), [
    ['/business/orient/scoring/appeals', 'get'], ['/business/orient/scoring/disputes', 'get'],
    [`/business/orient/scoring/session/${sid}`, 'get'], [`/business/orient/scoring/session/${sid}/verify`, 'get'],
    [`/business/orient/scoring/appeals/${aid}/resolve`, 'post'], ['/business/orient/scoring/disputes/22/resolve', 'post'],
    [`/business/orient/scoring/entries/${eid}/reverse`, 'post']
  ])
  assert.deepEqual(requests[0].params, { status: undefined, limit: 200 })
  assert.deepEqual(requests[1].params, { status: 0, limit: 200 })
  assert.equal(new URLSearchParams(requests[4].data).get('adjustCenti'), '-125')
  assert.equal(new URLSearchParams(requests[4].data).get('note'), '负分 & 修正')
  assert.equal(new URLSearchParams(requests[5].data).get('accept'), 'false')
  assert.equal(new URLSearchParams(requests[6].data).get('idempotencyKey'), 'admin-reverse:stable')
  for (const config of requests.slice(4)) {
    assert.equal(config.headers['Content-Type'], 'application/x-www-form-urlencoded')
    assert.equal(config.headers.repeatSubmit, undefined)
    assert.equal(new URLSearchParams(config.data).has('requestId'), false)
    assert.throws(() => config.transformResponse[0]('{"code":500,"errorCode":"ALREADY_RESOLVED","msg":"已处理"}'), error => error.scoringCode === 'ALREADY_RESOLVED' && error.message === '已处理')
    assert.equal(config.transformResponse[0]('{"code":500,"msg":"服务异常"}').code, 500)
  }
})

test('topic entry uses existing list/review permissions and shared evidence/error/empty components', () => {
  const page = fs.readFileSync(path.join(root, 'src/views/business/topic/index.vue'), 'utf8')
  assert.match(page, /@click="scoringConsoleOpen = true" v-hasPermi="\['business:orientScoring:list'\]"/)
  assert.match(page, /<orient-scoring-console :visible.sync="scoringConsoleOpen"/)
  for (const name of ['cy-resource-state', 'cy-empty-state', 'cy-evidence-panel']) assert.ok(source.includes(`<${name}`))
  assert.match(source, /business:orientScoring:review/)
  assert.match(source, /最新 200 条/)
})

test('no list permission performs no reads/writes, and list-only users cannot resolve, reverse or retry', async () => {
  let reads = 0
  let writes = 0
  const deps = { getScoringSession: async () => { reads++; return ok(detail()) }, listScoringAppeals: async () => { reads++; return ok([]) }, resolveScoringAppeal: async () => { writes++ }, reverseScoringEntry: async () => { writes++ } }
  const denied = createVm({ ...deps, checkPermi: () => false }).vm
  await denied.openConsole()
  await denied.lookupSession()
  await denied.readSession(sid)
  assert.equal(reads, 0)
  const viewer = createVm({ ...deps, checkPermi: perms => perms.includes('business:orientScoring:list') }).vm
  await inspect(viewer)
  viewer.openResolve(true)
  viewer.openReverse(viewer.detail.timeline[0])
  await viewer.submitDraft()
  viewer.command = { type: 'appeals', id: aid }
  viewer.retryAllowed = true
  await viewer.sendCommand()
  await viewer.retryCommand()
  assert.equal(viewer.draft, null)
  assert.equal(writes, 0)
})

test('queue failures and empty results remain distinct; partial queue reads are retained', async () => {
  const { vm } = createVm({ listScoringAppeals: async () => { throw new Error('forbidden') }, listScoringDisputes: async () => ok([]) })
  await vm.loadQueues()
  assert.match(vm.queueErrors.appeals, /forbidden/)
  assert.equal(vm.queueErrors.disputes, '')
  assert.deepEqual(vm.queues.disputes, [])
  const malformed = createVm({ listScoringAppeals: async () => ok([appeal(), appeal()]) }).vm
  await malformed.loadQueues()
  assert.match(malformed.queueErrors.appeals, /编号重复/)
})

test('session requires independent breakdown AND verify with matching session/totals and exact IDs', async () => {
  const { vm } = createVm()
  assert.equal(await vm.readSession(sid), true)
  assert.equal(vm.detail.appeals[0].id, aid)
  assert.equal(vm.detail.timeline[0].entryId, eid)
  for (const badDetail of [detail({ sessionId: '2' }), detail({ timeline: [entry(), entry()] }), detail({ appeals: [appeal({ sessionId: '2' })] }), detail({ totalCenti: 9007199254740992 })]) {
    const bad = createVm({ getScoringSession: async () => ok(badDetail) }).vm
    assert.equal(await bad.readSession(sid), false)
    assert.equal(bad.canAct, false)
    assert.ok(bad.sessionError)
  }
  const mismatch = createVm({ verifyScoringSession: async () => ok(verification(0)) }).vm
  assert.equal(await mismatch.readSession(sid), false)
  assert.match(mismatch.verifyError, /同一流水快照/)
})

test('newer session read wins when earlier async responses return last', async () => {
  let complete
  const { vm } = createVm({ getScoringSession: id => id === sid ? new Promise(resolve => { complete = () => resolve(ok(detail())) }) : Promise.resolve(ok(detail({ sessionId: id, appeals: [], disputes: [] }))), verifyScoringSession: async id => ok({ ...verification(-125), sessionId: id }) })
  const first = vm.readSession(sid)
  await vm.readSession('2')
  complete()
  assert.equal(await first, false)
  assert.equal(vm.detail.sessionId, '2')
  assert.equal(vm.sessionLoading, false)
})

test('appeal rejection sends only captured decision/note, then independently confirms handler and status', async () => {
  const calls = []
  let state = detail()
  let sent
  const { vm, session } = createVm({
    getScoringSession: async () => { calls.push('breakdown'); return ok(clone(state)) },
    verifyScoringSession: async () => { calls.push('verify'); return ok(verification(-125)) },
    resolveScoringAppeal: async (id, data) => { calls.push('write'); sent = { id, data }; state.appeals[0] = appeal({ status: 2, handledBy: 'reviewer', resolutionNote: data.note }); return ok(state.appeals[0]) }
  })
  await inspect(vm)
  calls.length = 0
  vm.openResolve(false)
  vm.draft.data.note = '  依据不足  '
  await vm.submitDraft()
  assert.deepEqual(sent, { id: aid, data: { accept: false, note: '依据不足' } })
  assert.deepEqual(calls.slice(0, 3), ['write', 'breakdown', 'verify'])
  assert.equal(vm.command, null)
  assert.equal(session.getItem(vm.storageKey), null)
  assert.equal(vm.outcome.type, 'success')
})

for (const amount of ['-125', '250', '-2147483647']) test(`accepted missing-entry appeal appends exact signed adjustment ${amount}`, async () => {
  let state = detail({ appeals: [appeal({ entryId: null })] })
  let sent
  const { vm } = createVm({
    getScoringSession: async () => ok(clone(state)), verifyScoringSession: async () => ok(verification(state.totalCenti)),
    resolveScoringAppeal: async (id, data) => {
      sent = { id, data }
      state.appeals[0] = appeal({ entryId: null, status: 1, resolutionEntryId: rid, handledBy: 'reviewer', resolutionNote: data.note })
      state.timeline.push({ ...entry(Number(data.adjustCenti)), entryId: rid, dimension: 'ADJUSTMENT', reasonCode: 'APPEAL_ACCEPTED', sourceType: 'APPEAL' })
      state.totalCenti += Number(data.adjustCenti)
      return ok(state.appeals[0])
    }
  })
  await inspect(vm)
  vm.openResolve(true)
  assert.equal(vm.draft.mode, 'adjust')
  vm.draft.adjustCenti = amount
  await vm.submitDraft()
  assert.deepEqual(sent.data, { accept: true, note: '', adjustCenti: amount })
  assert.equal(vm.outcome.type, 'success')
  assert.equal(vm.command, null)
})

test('appeal acceptance without adjustCenti reverses the target and verifies linked APPEAL entry', async () => {
  let state = detail()
  let sent
  const { vm } = createVm({
    getScoringSession: async () => ok(clone(state)), verifyScoringSession: async () => ok(verification(state.totalCenti)),
    resolveScoringAppeal: async (id, data) => {
      sent = data
      state.appeals[0] = appeal({ status: 1, handledBy: 'reviewer', resolutionEntryId: rid, resolutionNote: data.note })
      state.timeline.push({ ...entry(125), entryId: rid, reversesEntryId: eid, reasonCode: 'APPEAL_ACCEPTED', sourceType: 'APPEAL' })
      state.totalCenti = 0
      return ok(state.appeals[0])
    }
  })
  await inspect(vm)
  vm.openResolve(true)
  assert.equal(vm.draft.mode, 'reverse')
  await vm.submitDraft()
  assert.equal(Object.hasOwn(sent, 'adjustCenti'), false)
  assert.equal(vm.outcome.type, 'success')
  assert.equal(vm.canReverse(vm.detail.timeline[0]), false)
})

for (const accept of [true, false]) test(`dispute ${accept ? 'acceptance' : 'rejection'} preserves ledger and sends no adjustment/requestId`, async () => {
  let state = detail()
  let sent
  const before = clone(state.timeline)
  const { vm } = createVm({
    getScoringSession: async () => ok(clone(state)),
    resolveScoringDispute: async (id, data) => { sent = { id, data }; state.disputes[0] = appeal({ id: '22', entryId: null, status: accept ? 1 : 2, handledBy: 'reviewer', resolutionNote: data.note }); return ok(state.disputes[0]) }
  })
  await inspect(vm, 'disputes')
  vm.openResolve(accept)
  await vm.submitDraft()
  assert.deepEqual(sent, { id: '22', data: { accept, note: '' } })
  assert.deepEqual(vm.detail.timeline, before)
  assert.equal(vm.outcome.type, 'success')
  assert.match(vm.outcome.message, /争议决定不自动改分/)
})

for (const delta of [-125, 250]) test(`direct reversal negates ${delta} centi and verifies receipt key AND independent ledger`, async () => {
  let state = detail({ timeline: [entry(delta)], totalCenti: delta })
  let sent
  const { vm } = createVm({
    getScoringSession: async () => ok(clone(state)), verifyScoringSession: async () => ok(verification(state.totalCenti)),
    reverseScoringEntry: async (id, data) => {
      sent = { id, data }
      const reversal = { ...entry(-delta), entryId: rid, reversesEntryId: eid, reasonCode: data.reasonCode, sourceType: 'ADMIN' }
      state.timeline.push(reversal); state.totalCenti = 0
      return ok({ ...reversal, id: rid, sessionId: sid, idempotencyKey: data.idempotencyKey })
    }
  })
  await inspect(vm)
  vm.openReverse(vm.detail.timeline[0])
  await vm.submitDraft()
  assert.equal(sent.id, eid)
  assert.match(sent.data.idempotencyKey, /^admin-reverse:[a-f0-9]{32}$/)
  assert.equal(vm.outcome.type, 'success')
  assert.equal(vm.command, null)
})

test('cannot reverse a reversal, an already reversed entry, or int32 MIN_VALUE', async () => {
  const { vm } = createVm()
  await inspect(vm)
  for (const value of [entry(-2147483648), { ...entry(), reversesEntryId: '2' }]) {
    vm.openReverse(value)
    assert.equal(vm.draft, null)
  }
  vm.detail.timeline.push({ ...entry(125), entryId: rid, reversesEntryId: eid })
  vm.openReverse(vm.detail.timeline[0])
  assert.equal(vm.draft, null)
})

test('blank/zero/overflow adjustments, invalid reason codes and overlong notes do not write', async () => {
  let writes = 0
  const { vm, messages } = createVm({ resolveScoringAppeal: async () => { writes++ }, reverseScoringEntry: async () => { writes++ } })
  await inspect(vm)
  vm.openResolve(true)
  vm.draft.mode = 'adjust'
  for (const amount of ['', '0', '-2147483648', '2147483648', '1.5']) { vm.draft.adjustCenti = amount; await vm.submitDraft() }
  vm.openResolve(false)
  vm.draft.data.note = '字'.repeat(501)
  await vm.submitDraft()
  vm.openReverse(vm.detail.timeline[0])
  vm.draft.data.reasonCode = 'invalid reason'
  await vm.submitDraft()
  assert.equal(writes, 0)
  assert.equal(messages.length, 7)
})

test('cancel confirmation preserves editable input and writes nothing; duplicate confirm/write clicks send once', async () => {
  let writes = 0
  let finishConfirm
  let finishWrite
  const { vm } = createVm({ resolveScoringAppeal: async () => { writes++; return new Promise(resolve => { finishWrite = () => resolve(ok(appeal({ status: 2 }))) }) } })
  await inspect(vm)
  vm.openResolve(false)
  vm.draft.data.note = '保留输入'
  vm.$modal.confirm = async () => { throw new Error('cancel') }
  await vm.submitDraft()
  assert.equal(writes, 0)
  assert.equal(vm.draft.data.note, '保留输入')
  assert.equal(vm.command, null)
  vm.$modal.confirm = () => new Promise(resolve => { finishConfirm = resolve })
  const first = vm.submitDraft()
  await vm.submitDraft()
  assert.equal(writes, 0)
  finishConfirm()
  await Promise.resolve()
  assert.equal(writes, 1)
  await vm.sendCommand()
  await vm.recover()
  await vm.submitDraft()
  assert.equal(writes, 1)
  finishWrite()
  await first
})

test('unknown reverse survives close/reopen; recovery is read-only and explicit retry retains original key and payload', async () => {
  let original
  let writes = 0
  const { vm, session } = createVm({ reverseScoringEntry: async (id, data) => { writes++; original = { id, data: clone(data) }; throw new Error('timeout') } })
  await inspect(vm)
  vm.openReverse(vm.detail.timeline[0])
  vm.draft.data.reasonCode = 'CORRECTION'
  await vm.submitDraft()
  assert.ok(vm.command)
  assert.equal(vm.retryAllowed, true)
  assert.equal(vm.canAct, false)
  vm.close()
  let replay
  const reopened = createVm({ reverseScoringEntry: async (id, data) => { writes++; replay = { id, data: clone(data) }; throw new Error('timeout') } }, session).vm
  await reopened.openConsole()
  await reopened.recover()
  assert.equal(writes, 1)
  assert.equal(reopened.command.data.idempotencyKey, original.data.idempotencyKey)
  await reopened.retryCommand()
  assert.equal(writes, 2)
  assert.deepEqual(replay, original)
  assert.ok(reopened.command)
})

test('a success receipt cannot close an unknown outcome until independent GET reads agree', async () => {
  let failRead = false
  let writes = 0
  let state = detail()
  const { vm } = createVm({
    getScoringSession: async () => { if (failRead) throw new Error('offline'); return ok(clone(state)) },
    resolveScoringAppeal: async () => { writes++; failRead = true; state.appeals[0] = appeal({ status: 2, handledBy: 'reviewer', resolutionNote: '' }); return ok(state.appeals[0]) }
  })
  await inspect(vm)
  vm.openResolve(false)
  await vm.submitDraft()
  assert.ok(vm.command.receipt)
  assert.equal(vm.outcome, null)
  assert.match(vm.operationError, /结果未知/)
  failRead = false
  await vm.recover()
  assert.equal(writes, 1)
  assert.equal(vm.command, null)
  assert.equal(vm.outcome.type, 'success')
})

test('unknown appeal recovery never auto-posts; explicit retry keeps the exact original decision and centi without requestId', async () => {
  const sent = []
  let state = detail({ appeals: [appeal({ entryId: null })] })
  const deps = {
    getScoringSession: async () => ok(clone(state)), verifyScoringSession: async () => ok(verification(state.totalCenti)),
    resolveScoringAppeal: async (id, data) => {
      sent.push({ id, data: clone(data) })
      if (sent.length === 1) throw new Error('timeout')
      state.appeals[0] = appeal({ entryId: null, status: 1, resolutionEntryId: rid, handledBy: 'reviewer', resolutionNote: data.note })
      state.timeline.push({ ...entry(-25), entryId: rid, dimension: 'ADJUSTMENT', reasonCode: 'APPEAL_ACCEPTED', sourceType: 'APPEAL' })
      state.totalCenti -= 25
      return ok(state.appeals[0])
    }
  }
  const { vm, session } = createVm(deps)
  await inspect(vm)
  vm.openResolve(true)
  vm.draft.adjustCenti = '-25'
  vm.draft.data.note = '  原始理由  '
  await vm.submitDraft()
  assert.ok(vm.command)
  const reopened = createVm(deps, session).vm
  await reopened.openConsole()
  assert.equal(sent.length, 1)
  assert.equal(reopened.retryAllowed, true)
  await reopened.retryCommand()
  assert.equal(sent.length, 2)
  assert.deepEqual(sent[1], sent[0])
  assert.deepEqual(sent[1].data, { accept: true, note: '原始理由', adjustCenti: '-25' })
  assert.equal(reopened.command, null)
  assert.equal(reopened.outcome.type, 'success')
})

test('accepted appeal with wrong linked delta is warned; inconsistent settled projection never reports success', async () => {
  for (const wrongEntry of [true, false]) {
    let state = detail()
    const { vm } = createVm({
      getScoringSession: async () => ok(clone(state)),
      verifyScoringSession: async () => ok({ ...verification(state.totalCenti), settled: true, consistent: false, ledgerAtWatermarkCenti: -125, projectionTotalCenti: 1, postSettlementDeltaCenti: state.totalCenti + 125 }),
      resolveScoringAppeal: async (id, data) => {
        const delta = wrongEntry ? 124 : 125
        state.appeals[0] = appeal({ status: 1, resolutionEntryId: rid, handledBy: 'reviewer', resolutionNote: data.note })
        state.timeline.push({ ...entry(delta), entryId: rid, reversesEntryId: eid, reasonCode: 'APPEAL_ACCEPTED', sourceType: 'APPEAL' })
        state.totalCenti += delta
        return ok(state.appeals[0])
      }
    })
    await inspect(vm)
    vm.openResolve(true)
    await vm.submitDraft()
    assert.equal(vm.outcome.type, 'warning')
    assert.match(vm.outcome.message, /复算不一致/)
    if (wrongEntry) assert.match(vm.outcome.message, /未完全匹配/)
    assert.equal(vm.command, null)
  }
})

test('HTTP 200 with still-pending GET remains unresolved; mismatched handler/ledger is a warning', async () => {
  let state = detail()
  const { vm } = createVm({ getScoringSession: async () => ok(clone(state)) })
  await inspect(vm)
  vm.openResolve(false)
  await vm.submitDraft()
  assert.ok(vm.command)
  assert.equal(vm.outcome, null)
  state.appeals[0] = appeal({ status: 2, handledBy: 'another-reviewer', resolutionNote: '' })
  await vm.recover()
  assert.equal(vm.command, null)
  assert.equal(vm.outcome.type, 'warning')
  assert.match(vm.outcome.message, /未完全匹配/)
})

test('lost reverse receipt reads existing reversal as warning without claiming this request owns it or resending', async () => {
  let state = detail()
  let writes = 0
  const { vm } = createVm({
    getScoringSession: async () => ok(clone(state)), verifyScoringSession: async () => ok(verification(state.totalCenti)),
    reverseScoringEntry: async () => { writes++; state.timeline.push({ ...entry(125), entryId: rid, reversesEntryId: eid, sourceType: 'ADMIN', reasonCode: 'REVERSAL' }); state.totalCenti = 0; throw new Error('timeout after commit') }
  })
  await inspect(vm)
  vm.openReverse(vm.detail.timeline[0])
  await vm.submitDraft()
  assert.equal(writes, 1)
  assert.equal(vm.command, null)
  assert.equal(vm.outcome.type, 'warning')
  assert.match(vm.outcome.message, /不能确认归属/)
})

test('malformed reverse receipt cannot prevent read-only recovery of an independently visible reversal', async () => {
  let state = detail()
  const { vm } = createVm({
    getScoringSession: async () => ok(clone(state)), verifyScoringSession: async () => ok(verification(state.totalCenti)),
    reverseScoringEntry: async () => { state.timeline.push({ ...entry(125), entryId: rid, reversesEntryId: eid, sourceType: 'ADMIN', reasonCode: 'REVERSAL' }); state.totalCenti = 0; return ok({ unexpected: true }) }
  })
  await inspect(vm)
  vm.openReverse(vm.detail.timeline[0])
  await vm.submitDraft()
  assert.equal(vm.command, null)
  assert.equal(vm.outcome.type, 'warning')
})

test('explicit business rejection unlocks only after independent pending read; generic failure stays unknown', async () => {
  for (const explicit of [true, false]) {
    const rejected = new Error('暂未开放')
    if (explicit) rejected.scoringCode = 'DISABLED'
    const { vm } = createVm({ resolveScoringAppeal: async () => { throw rejected } })
    await inspect(vm)
    vm.openResolve(false)
    await vm.submitDraft()
    if (explicit) {
      assert.equal(vm.command, null)
      assert.equal(vm.outcome.type, 'error')
      assert.match(vm.outcome.message, /DISABLED/)
    } else {
      assert.ok(vm.command)
      assert.equal(vm.outcome, null)
    }
  }
})

test('storage failure prevents POST; unknown commands stay scoped to reviewer and broken storage never triggers writes', async () => {
  let writes = 0
  const session = storage()
  session.setItem = () => { throw new Error('storage blocked') }
  const { vm } = createVm({ resolveScoringAppeal: async () => { writes++ } }, session)
  await inspect(vm)
  vm.openResolve(false)
  await vm.submitDraft()
  assert.equal(writes, 0)
  assert.equal(vm.command, null)
  assert.match(vm.operationError, /本次未提交/)
  const broken = storage()
  broken.setItem('orient-scoring-admin:7', '{broken')
  const reopened = createVm({}, broken).vm
  await reopened.openConsole()
  assert.match(reopened.operationError, /无法读取/)
  reopened.$store.getters.id = 8
  assert.equal(reopened.storageKey, 'orient-scoring-admin:8')
})
