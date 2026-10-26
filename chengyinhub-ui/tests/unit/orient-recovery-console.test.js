const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/orient-scoring/components/OrientRecoveryConsole.vue'), 'utf8')
const sid = '9223372036854775807', id = '9007199254740993'
const record = extra => ({ id, sessionId: sid, memberId: '77', kind: 'APPEAL', status: 0, referenceId: null, ...extra })
const ok = data => ({ code: 200, data })
function mount(options = {}) {
  const saved = options.saved || new Map(), calls = [], messages = []
  const state = { row: record(), list: true, review: true, consistent: true, ...options.state }
  const storage = { getItem: k => saved.get(k) || null, setItem: (k, v) => saved.set(k, v), removeItem: k => saved.delete(k) }
  const deps = { scoringId: x => { const s = String(x); if (!/^[1-9]\d{0,18}$/.test(s)) throw Error('bad id'); return s }, formatCenti: String,
    checkPermi: p => p[0].endsWith(':list') ? state.list : state.review,
    listScoringRecoveries: async () => { calls.push('list'); return ok([state.row]) },
    getScoringSession: async () => { calls.push('session'); return ok({ sessionId: sid, totalCenti: '10', timeline: [], recoveries: [state.row] }) },
    verifyScoringSession: async () => ok({ sessionId: sid, ledgerTotalCenti: '10', consistent: state.consistent }),
    reviewScoringRecovery: async (caseId, data) => { calls.push({ caseId, data }); state.row = record({ status: 1, handledBy: 'reviewer', resolutionNote: data.note, referenceId: data.referenceId }); return ok(state.row) }, ...options.deps }
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import \{ ([^}]+) \} from .*$/gm, 'const { $1 } = dependencies').replace('export default', 'return')
  const component = new Function('dependencies', 'sessionStorage', script)(deps, storage)
  const vm = Object.assign(component.data(), { active: true, $store: { getters: { id: '7', name: 'reviewer' } }, $modal: { confirm: async () => {} } })
  for (const [key, method] of Object.entries(component.methods)) vm[key] = method.bind(vm)
  for (const [key, method] of Object.entries(component.computed)) Object.defineProperty(vm, key, { get: method.bind(vm) })
  return { vm, state, calls, saved, storage, messages }
}
test('review reads session facts then records and independently rereads the result', async () => {
  const h = mount(); await h.vm.load(); await h.vm.inspect(h.state.row)
  h.vm.note = '本局申请已核对'; h.vm.referenceId = '42'; await h.vm.submit()
  assert.equal(h.calls.filter(x => x.caseId).length, 1); assert.equal(h.vm.selected.status, 1)
  assert.equal(h.vm.command, null); assert.equal(h.saved.size, 0); assert.match(h.vm.message, /玩家原操作仍保持待确认/)
  assert.equal(h.calls.filter(x => x === 'session').length, 2)
})
test('no list/review permission and inconsistent ledger have zero write effects', async () => {
  const denied = mount({ state: { list: false } }); await denied.vm.load(); await denied.vm.inspect(record()); await denied.vm.submit(); assert.equal(denied.calls.length, 0)
  for (const state of [{ review: false }, { consistent: false }]) {
    const h = mount({ state }); await h.vm.inspect(h.state.row); h.vm.note = '核对'; await h.vm.submit()
    assert.equal(h.calls.filter(x => x.caseId).length, 0); assert.equal(h.saved.size, 0)
  }
})
test('timeout preserves original decision; readonly pending permits same-parameter CAS retry', async () => {
  let writes = 0, captured
  const h = mount({ deps: { reviewScoringRecovery: async (caseId, data) => { writes++; captured = { caseId, data }; throw Error('timeout') } } })
  await h.vm.inspect(h.state.row); h.vm.note = '固定原意见'; await h.vm.submit()
  assert.equal(h.saved.size, 1); assert.ok(h.vm.command); assert.equal(h.vm.retryAllowed, true)
  h.vm.note = 'changed'; await h.vm.retry(); assert.equal(writes, 2); assert.equal(captured.data.note, '固定原意见')
})
test('reload restores command and reads review without resending', async () => {
  const saved = new Map([['orient-score-recovery:7', JSON.stringify({ reviewer: '7', selected: record(), data: { note: '核对', referenceId: null } })]])
  const h = mount({ saved, state: { row: record({ status: 1, handledBy: 'reviewer', resolutionNote: '核对' }) } }); await h.vm.load()
  assert.equal(h.saved.size, 0); assert.equal(h.calls.filter(x => x.caseId).length, 0); assert.equal(h.vm.command, null)
})
test('account change during confirmation does not send, and late session read cannot populate next account', async () => {
  const h = mount(); await h.vm.inspect(h.state.row); h.vm.note = '核对'
  h.vm.$modal.confirm = async () => { h.vm.$store.getters.id = '8'; h.vm.reset() }; await h.vm.submit()
  assert.equal(h.saved.size, 0); assert.equal(h.calls.filter(x => x.caseId).length, 0)
  let resolve
  const late = mount({ deps: { getScoringSession: () => new Promise(r => { resolve = r }) } }); const read = late.vm.inspect(record())
  late.vm.$store.getters.id = '8'; late.vm.reset(); resolve(ok({ sessionId: sid, totalCenti: '10', timeline: [], recoveries: [record()] })); await read
  assert.equal(late.vm.detail, null); assert.equal(late.vm.selected, null)
})
test('unwritable storage prevents review and wrong-session references do not become verified evidence', async () => {
  const h = mount(); h.storage.setItem = () => { throw Error('full') }; await h.vm.inspect(h.state.row); h.vm.note = '核对'; await h.vm.submit()
  assert.equal(h.calls.filter(x => x.caseId).length, 0); assert.equal(h.vm.command, null)
  const wrong = mount({ state: { row: record({ sessionId: '99' }) } }); await wrong.vm.inspect(record()); assert.equal(wrong.vm.consistent, false)
})
test('other reviewers result is shown as an independent fact, never own successful submission', async () => {
  const h = mount({ deps: { reviewScoringRecovery: async () => { h.state.row = record({ status: 1, handledBy: 'someone-else', resolutionNote: '实际意见' }); throw Error('lost') } } })
  await h.vm.inspect(h.state.row); h.vm.note = '我的意见'; await h.vm.submit()
  assert.match(h.vm.message, /不代表本次提交成功/); assert.equal(h.vm.command, null); assert.equal(h.vm.selected.resolutionNote, '实际意见')
})
test('template exposes actual queue, fact read, submit and unknown recovery actions', () => {
  for (const handler of ['load', 'inspect', 'submit', 'recover', 'retry']) assert.match(source, new RegExp('@click="' + handler))
  assert.match(source, /不解除玩家冻结/)
})
