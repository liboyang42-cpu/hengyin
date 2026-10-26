const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/audit/index.vue'), 'utf8')
function mount(options = {}) {
  const saved = options.saved || new Map(), calls = []
  const storage = { getItem: key => saved.get(key) || null, setItem: (key, value) => saved.set(key, value), removeItem: key => saved.delete(key) }
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import[^\n]*\n/gm, '').replace(/components:\s*\{[^}]+\},/, 'components: {},').replace('export default', 'return')
  const review = async payload => { calls.push(payload); return options.review ? options.review(payload) : { code: 200 } }
  const detail = async () => options.detail ? options.detail() : { code: 200, itemProblemReceipt: { ...calls.at(-1), state: 'FOUND', applied: true } }
  const component = new Function('sessionStorage', 'reviewAuditTask', 'reviewMediaAsset', 'getAuditTaskDetail', script)(storage, review, review, detail)
  const vm = Object.assign(component.data(), { $store: { getters: { id: '7', token: 'session-a', permissions: ['business:audit:review'] } } })
  for (const [key, fn] of Object.entries(component.methods)) vm[key] = fn.bind(vm)
  for (const [key, fn] of Object.entries(component.computed)) Object.defineProperty(vm, key, { get: fn.bind(vm) })
  vm.getList = () => {}; vm.openReview({ id: '9223372036854775807', bizId: '99', bizType: 'TOPIC', version: 3, sourceType: 'SUBMIT' }, 2)
  return { vm, calls, saved, storage, component }
}
function problem(h) { h.vm.reviewForm.reasonText = '请修改'; h.vm.addProblem(); Object.assign(h.vm.reviewForm.problems[0], { itemType: 'NODE', itemId: '9223372036854775806', reasonText: '节点说明不足' }) }
test('real component sends exact item IDs, original version and immutable request identity', async () => {
  const h = mount(); problem(h); await h.vm.submitReview()
  assert.equal(h.calls.length, 1); assert.equal(h.calls[0].problems[0].itemId, '9223372036854775806')
  assert.equal(h.calls[0].expectedVersion, 3); assert.ok(h.calls[0].requestId); assert.equal(h.vm.reviewConfirmed, true)
  assert.match(h.vm.reviewError, /服务端回执确认/); assert.equal(h.saved.size, 1)
  h.vm.finishConfirmedReview(); assert.equal(h.saved.size, 0)
})
test('invalid or out-of-scope items block writes and preserve form', async () => {
  for (const bad of [{ itemId: '' }, { itemId: 9007199254740992 }, { reasonText: ' ' }, { reasonText: 'a'.repeat(201) }, { itemType: 'TOPIC', itemId: '100' }]) {
    const h = mount(); problem(h); Object.assign(h.vm.reviewForm.problems[0], bad); await h.vm.submitReview()
    assert.equal(h.calls.length, 0); assert.equal(h.vm.reviewForm.problems.length, 1); assert.equal(h.vm.reviewFrozen, false)
  }
})
test('50 item cap; template and appeal scope match backend contract', () => {
  const h = mount(); for (let i = 0; i < 51; i++) h.vm.addProblem(); assert.equal(h.vm.reviewForm.problems.length, 50)
  h.vm.openReview({ id: '2', bizId: '42', bizType: 'TEMPLATE', version: 0 }, 2)
  assert.deepEqual(h.vm.problemTypes, ['TEMPLATE']); h.vm.addProblem(); h.vm.reviewForm.reasonText = '整体'; h.vm.reviewForm.problems[0].reasonText = '模板问题'
  assert.equal(h.vm.reviewPayload().problems[0].itemId, null)
  h.vm.reviewRow.sourceType = 'APPEAL'; assert.equal(h.vm.supportsProblems, false); assert.equal(h.vm.reviewPayload().problems, undefined)
})
test('server validation rejection keeps editable input and readable message', async () => {
  const h = mount({ review: async () => { throw Object.assign(Error('问题条目不属于本次审核内容'), { auditCode: 400 }) } }); problem(h); await h.vm.submitReview()
  assert.match(h.vm.reviewError, /不属于本次审核/); assert.equal(h.vm.reviewFrozen, false); assert.equal(h.vm.reviewForm.problems[0].reasonText, '节点说明不足')
})
test('UNKNOWN survives reentry and retries frozen payload; later 400 cannot release it', async () => {
  const first = mount({ review: async () => { throw Error('timeout') } }); problem(first); await first.vm.submitReview()
  const original = first.calls[0]
  const next = mount({ saved: first.saved, review: async () => { throw Object.assign(Error('rejected'), { auditCode: 400 }) } })
  assert.equal(next.vm.reviewFrozen, true); next.vm.reviewForm.reasonText = 'changed'; await next.vm.submitReview()
  assert.deepEqual(next.calls[0], original); assert.equal(next.vm.reviewFrozen, true); assert.equal(next.saved.size, 1)
})
test('storage failure and missing permission have zero writes', async () => {
  const h = mount(); problem(h); h.storage.setItem = () => { throw Error('full') }; await h.vm.submitReview(); assert.equal(h.calls.length, 0)
  const denied = mount(); problem(denied); denied.vm.$store.getters.permissions = []; await denied.vm.submitReview(); assert.equal(denied.calls.length, 0)
})
test('duplicate submit suppressed and late old-account response cannot confirm new account', async () => {
  let complete; const h = mount({ review: () => new Promise(resolve => { complete = resolve }) }); problem(h)
  const pending = h.vm.submitReview(); await h.vm.submitReview(); assert.equal(h.calls.length, 1)
  h.vm.$store.getters.id = '8'; h.component.watch.auditReviewer.call(h.vm); complete({ code: 200 }); await pending
  assert.equal(h.vm.reviewConfirmed, false); assert.equal(h.vm.reviewOpen, false); assert.equal(h.saved.size, 1)
})
test('API adapter preserves structured validation failures before shared response interceptor', () => {
  const api = fs.readFileSync(path.resolve(__dirname, '../../src/api/business/auditWorkbench.js'), 'utf8')
  const parse = new Function(api.slice(api.indexOf('export function parseAuditReviewResponse'), api.indexOf('export function reviewAuditTask')).replace('export ', '') + '; return parseAuditReviewResponse')()
  assert.throws(() => parse('{"code":400,"msg":"归属错误","errorCode":"AUDIT_REVIEW_INVALID"}'), e => e.auditCode === 400 && e.message === '归属错误')
  assert.deepEqual(parse('{"code":200}'), { code: 200 })
})
test('unsent form reopens with all fields, including optional reason category', () => {
  const h = mount(); problem(h); h.vm.reviewForm.problems[0].reasonCode = 'LOCATION_ISSUE'; h.vm.saveReviewDraft()
  const next = mount({ saved: h.saved }); assert.equal(next.vm.reviewForm.problems[0].reasonCode, 'LOCATION_ISSUE')
  assert.equal(next.vm.reviewPayload().problems[0].reasonCode, 'LOCATION_ISSUE')
  next.vm.reviewForm.problems[0].reasonCode = 'invented'; assert.throws(() => next.vm.reviewPayload(), /原因分类/)
})
test('tampered frozen task identity blocks replay; component destruction ignores late success', async () => {
  const h = mount({ review: async () => { throw Error('timeout') } }); problem(h); await h.vm.submitReview()
  const key = h.vm.reviewStorageKey(), value = JSON.parse(h.saved.get(key)); value.command.taskId = '12'; h.saved.set(key, JSON.stringify(value))
  const next = mount({ saved: h.saved }); await next.vm.submitReview(); assert.equal(next.calls.length, 0); assert.match(next.vm.reviewError, /不匹配/)
  let resolve; const late = mount({ review: () => new Promise(done => { resolve = done }) }); problem(late); const flight = late.vm.submitReview()
  late.component.beforeDestroy.call(late.vm); resolve({ code: 200 }); await flight; assert.equal(late.vm.reviewConfirmed, false)
})

test('independent receipt absent, mismatched or disabled never claims itemized save', async () => {
  for (const state of ['NOT_FOUND', 'LEGACY', 'UNKNOWN']) {
    const h = mount({ detail: () => ({ code: 200, itemProblemReceipt: { state } }) }); problem(h); await h.vm.submitReview();
    assert.equal(h.vm.reviewConfirmed, false); assert.equal(h.vm.reviewFrozen, true);
  }
  const disabled = mount({ detail: () => ({ code: 200, itemProblemReceipt: { ...disabled.calls[0], state: 'FOUND', applied: false } }) });
  problem(disabled); await disabled.vm.submitReview(); assert.equal(disabled.vm.reviewConfirmed, true); assert.match(disabled.vm.reviewError, /未保存/);
  const wrong = mount({ detail: () => ({ code: 200, itemProblemReceipt: { ...wrong.calls[0], state: 'FOUND', applied: true, problems: [] } }) });
  problem(wrong); await wrong.vm.submitReview(); assert.equal(wrong.vm.reviewConfirmed, false); assert.match(wrong.vm.reviewError, /不一致/);
});

test('guard review freezes UNKNOWN and reopens original approval without content problems', async () => {
  const h = mount({ review: async () => { throw Error('timeout') } })
  h.vm.openReview({ id: '9223372036854775807', bizId: '91', bizType: 'play_arrive_guard', version: 0, sourceType: 'appeal' }, 1)
  h.vm.reviewForm.reasonText = '重新现场核验'
  await h.vm.submitReview()
  const original = h.calls[0]
  assert.ok(original.requestId); assert.equal(original.problems, undefined); assert.equal(h.vm.reviewFrozen, true)
  const next = mount({ saved: h.saved, detail: () => ({ code: 200, guardReviewReceipt: { ...original, state: 'FOUND', applied: false } }) })
  assert.equal(next.vm.guardReview, true); assert.equal(next.vm.reviewCommand.status, 1)
  next.vm.reviewForm.reasonText = 'changed'
  await next.vm.submitReview()
  assert.deepEqual(next.calls[0], original); assert.equal(next.vm.reviewConfirmed, true)
  assert.match(next.vm.reviewError, /不直接放行或补发奖励/)
})
test('guard missing receipt remains frozen and cross-account late response cannot confirm', async () => {
  const h = mount({ detail: () => ({ code: 200, guardReviewReceipt: { state: 'NOT_FOUND' } }) })
  h.vm.openReview({ id: '91', bizId: '92', bizType: 'play_arrive_guard', version: 0, sourceType: 'appeal' }, 2)
  h.vm.reviewForm.reasonText = '证据不足'; await h.vm.submitReview()
  assert.equal(h.vm.reviewConfirmed, false); assert.equal(h.vm.reviewFrozen, true)
  const command = h.vm.reviewCommand
  await h.vm.recoverReview(); assert.equal(h.vm.reviewCommand, command)
  let resolve
  const next = mount({ saved: h.saved, detail: () => new Promise(r => { resolve = r }) })
  const pending = next.vm.recoverReview()
  next.vm.$store.getters.id = '8'; next.component.watch.auditReviewer.call(next.vm)
  resolve({ code: 200, guardReviewReceipt: { ...command, state: 'FOUND', applied: false } }); await pending
  assert.equal(next.vm.reviewConfirmed, false); assert.equal(next.saved.size, 1)
})
