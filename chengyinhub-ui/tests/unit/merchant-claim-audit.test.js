const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { webcrypto } = require('node:crypto')

const root = path.resolve(__dirname, '../..')
const source = fs.readFileSync(path.join(root, 'src/views/business/merchant/components/MerchantClaimAuditDialog.vue'), 'utf8')
const apiSource = fs.readFileSync(path.join(root, 'src/api/business/merchantClaim.js'), 'utf8')

function loadApi(request = () => {}) {
  const script = apiSource.replace(/^import .*$/gm, '').replace(/export function /g, 'function ')
  return new Function('request', `${script}; return { parseClaimResponse, parseAuditResponse, claimId, pendingMerchantClaims, auditMerchantClaim, auditMerchantClaimBatch }`)(request)
}

const { claimId } = loadApi()
const row = (id = '9223372036854775807') => ({
  id, merchantId: '9223372036854775806', claimantMemberId: '9223372036854775805',
  status: 'PENDING', version: 3, contactName: '申请人', contactPhone: '13800000000',
  statement: '本人为店铺经营者', proofs: [{ proofType: 'BUSINESS_LICENSE', assetId: 'https://assets.example.test/proof.png' }]
})
const ok = data => ({ code: 200, data })
const receipt = payload => ok({
  id: payload.claimId, status: payload.action === 'APPROVE' ? 'APPROVED' : 'REJECTED',
  auditRequestId: payload.requestId, version: payload.expectedVersion + 1
})

function storage() {
  const data = new Map()
  return { getItem: key => data.get(key) || null, setItem: (key, value) => data.set(key, value), removeItem: key => data.delete(key) }
}

function createVm(deps = {}, session = storage()) {
  const dependencies = {
    pendingMerchantClaims: async () => ok([]), auditMerchantClaim: async payload => receipt(payload),
    auditMerchantClaimBatch: async () => ok([]), claimId, checkPermi: () => true, ...deps
  }
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ ([^}]+) \} from .*$/gm, 'const { $1 } = dependencies')
    .replace(/^import (Cy\w+) from .*$/gm, 'const $1 = {}')
    .replace('export default', 'return')
  const component = new Function('dependencies', 'sessionStorage', 'window', script)(dependencies, session, { crypto: webcrypto })
  const emitted = []
  const messages = []
  const vm = Object.assign(component.data(), {
    visible: true, $store: { getters: { id: 7 } },
    $modal: { confirm: async () => {}, msgError: value => messages.push(value) },
    $emit: (...args) => emitted.push(args)
  })
  for (const [key, method] of Object.entries(component.methods)) vm[key] = method.bind(vm)
  for (const [key, getter] of Object.entries(component.computed)) Object.defineProperty(vm, key, { get: getter.bind(vm) })
  return { vm, session, emitted, messages, component }
}

test('API preserves exact Long IDs before JSON parsing, including nested batch results and quoted evidence', () => {
  const api = loadApi()
  const raw = '{"code":200,"data":[{"claimId":9223372036854775807,"version":3,"text":"编号 9223372036854775807, \\\"id\\\":9223372036854775806","small":12}]}'
  const parsed = api.parseClaimResponse(raw)
  assert.equal(parsed.data[0].claimId, '9223372036854775807')
  assert.equal(parsed.data[0].version, 3)
  assert.equal(parsed.data[0].small, 12)
  assert.match(parsed.data[0].text, /9223372036854775806/)
  assert.equal(api.claimId('9223372036854775807'), '9223372036854775807')
  for (const id of [9223372036854775807, '9223372036854775808', 0, '-1', '1e3', null]) assert.throws(() => api.claimId(id))
})

test('API uses only the actual pending, audit and audit-batch contracts and preserves explicit business rejections', async () => {
  const requests = []
  const api = loadApi(config => { requests.push(config); return Promise.resolve(ok([])) })
  const payload = { claimId: '9223372036854775807', requestId: 'stable', action: 'REJECT', reason: '资料不符', expectedStatus: 'PENDING', expectedVersion: 3 }
  await api.pendingMerchantClaims()
  await api.auditMerchantClaim(payload)
  await api.auditMerchantClaimBatch({ requestId: 'batch-stable', action: 'REJECT', reason: '资料不符', items: [{ claimId: payload.claimId, expectedStatus: 'PENDING', expectedVersion: 3 }] })
  assert.deepEqual(requests.map(r => [r.url, r.method]), [
    ['/business/merchant-claim/pending', 'get'], ['/business/merchant-claim/audit', 'post'], ['/business/merchant-claim/audit-batch', 'post']
  ])
  assert.deepEqual(requests[0].params, { limit: 100 })
  assert.equal(requests[1].data, payload)
  assert.equal(requests[1].data.claimId, '9223372036854775807')
  for (const config of requests.slice(1)) {
    assert.throws(() => config.transformResponse[0]('{"code":409,"msg":"状态已变化"}'), e => e.confirmedRejected && e.message === '状态已变化')
    assert.equal(config.transformResponse[0]('{"code":500,"msg":"异常"}').code, 500)
    assert.equal(config.headers, undefined, '不关闭共享请求层的重复提交防护')
  }
})

test('merchant page exposes a permission-gated queue; evidence, reason and error components are reused', () => {
  const page = fs.readFileSync(path.join(root, 'src/views/business/merchant/index.vue'), 'utf8')
  assert.match(page, /@click="claimAuditOpen = true" v-hasPermi="\['business:merchant:edit'\]"/)
  assert.match(page, /<merchant-claim-audit-dialog :visible.sync="claimAuditOpen" @changed="getList"/)
  for (const name of ['cy-confirm-action', 'cy-resource-state', 'cy-empty-state', 'cy-evidence-panel', 'cy-media-preview']) assert.ok(source.includes(`<${name}`))
  assert.match(source, /最早的 100 条/)
})

test('permission denial performs no read or write; empty and malformed queues remain distinct', async () => {
  let calls = 0
  const { vm } = createVm({ checkPermi: () => false, pendingMerchantClaims: () => { calls++; return ok([]) }, auditMerchantClaim: () => { calls++ } })
  await vm.openQueue()
  await vm.prepare([row()], 'APPROVE', false)
  await vm.recover()
  assert.equal(calls, 0)
  assert.equal(vm.canSubmit, false)
  const empty = createVm().vm
  await empty.loadPending()
  assert.equal(empty.error, '')
  assert.deepEqual(empty.rows, [])
  const malformed = createVm({ pendingMerchantClaims: async () => ok({}) }).vm
  await malformed.loadPending()
  assert.match(malformed.error, /返回格式异常/)
  assert.equal(malformed.canSubmit, false)
})

test('approval sends exact IDs and captured version, then independently reads pending before emitting changed', async () => {
  const calls = []
  let sent
  const { vm, session, emitted } = createVm({
    auditMerchantClaim: async payload => { calls.push('write'); sent = payload; return receipt(payload) },
    pendingMerchantClaims: async () => { calls.push('read'); return ok([]) }
  })
  vm.rows = [row()]
  await vm.prepare(vm.rows, 'APPROVE', false)
  assert.deepEqual(calls, ['write', 'read'])
  assert.equal(sent.claimId, '9223372036854775807')
  assert.equal(sent.expectedVersion, 3)
  assert.equal(sent.expectedStatus, 'PENDING')
  assert.equal(sent.action, 'APPROVE')
  assert.equal(sent.reason, '', '通过不额外强制理由政策')
  assert.match(sent.requestId, /^claim-[a-f0-9]{32}$/)
  assert.equal(vm.command, null)
  assert.equal(session.getItem(vm.storageKey), null)
  assert.match(vm.results[0].message, /独立回读确认/)
  assert.deepEqual(emitted, [['changed']])
})

test('rejection requires a trimmed reason within the backend limit and uses the same audit endpoint', async () => {
  const sent = []
  const { vm, messages } = createVm({ auditMerchantClaim: async payload => { sent.push(payload); return receipt(payload) } })
  vm.rows = [row()]
  await vm.prepare(vm.rows, 'REJECT', false)
  await vm.submitReject('   ')
  await vm.submitReject('字'.repeat(501))
  assert.equal(sent.length, 0)
  assert.equal(messages.length, 2)
  await vm.submitReject('  营业执照与申请主体不符  ')
  assert.equal(sent.length, 1)
  assert.equal(sent[0].action, 'REJECT')
  assert.equal(sent[0].reason, '营业执照与申请主体不符')
  assert.equal(vm.results[0].currentStatus, 'REJECTED')
})

test('cancelling approval leaves no command or write', async () => {
  let writes = 0
  const { vm } = createVm({ auditMerchantClaim: () => { writes++ } })
  vm.$modal.confirm = async () => { throw new Error('cancel') }
  vm.rows = [row()]
  await vm.prepare(vm.rows, 'APPROVE', false)
  assert.equal(writes, 0)
  assert.equal(vm.command, null)
  assert.equal(vm.preparing, false)
})

test('duplicate clicks and recovery while a write is outstanding cannot send a second command', async () => {
  let finish
  const sent = []
  const { vm } = createVm({ auditMerchantClaim: payload => { sent.push(payload); return new Promise(resolve => { finish = () => resolve(receipt(payload)) }) } })
  vm.rows = [row()]
  const first = vm.prepare(vm.rows, 'APPROVE', false)
  await Promise.resolve()
  assert.equal(vm.busy, true)
  await vm.prepare(vm.rows, 'REJECT', false)
  await vm.recover()
  assert.equal(sent.length, 1)
  finish()
  await first
  assert.equal(vm.busy, false)
})

test('unknown network result survives closing and reopening; recovery replays the identical requestId and payload', async () => {
  let original
  const { vm, session } = createVm({ auditMerchantClaim: async payload => { original = { ...payload }; throw new Error('timeout') } })
  vm.rows = [row()]
  await vm.prepare(vm.rows, 'APPROVE', false)
  assert.match(vm.error, /结果尚未确认/)
  assert.equal(vm.canSubmit, false)
  let replay
  const reopened = createVm({ auditMerchantClaim: async payload => { replay = payload; return receipt(payload) } }, session).vm
  await reopened.openQueue()
  assert.ok(reopened.command, '空队列本身不能证明未知请求已成功')
  await reopened.recover()
  assert.deepEqual(replay, original)
  assert.equal(reopened.command, null)
  assert.match(reopened.results[0].message, /已处理/)
})

test('HTTP 200 or a valid write receipt with pending read failure cannot close acceptance; recovery only rereads', async () => {
  let writes = 0
  let failRead = true
  const { vm, emitted } = createVm({
    auditMerchantClaim: async payload => { writes++; return receipt(payload) },
    pendingMerchantClaims: async () => { if (failRead) throw new Error('offline'); return ok([]) }
  })
  vm.rows = [row()]
  await vm.prepare(vm.rows, 'APPROVE', false)
  assert.ok(vm.command.receipt)
  assert.match(vm.error, /回读队列失败/)
  assert.deepEqual(emitted, [])
  failRead = false
  await vm.recover()
  assert.equal(writes, 1)
  assert.equal(vm.command, null)
})

test('a returned success that is still PENDING stays unresolved until readback agrees', async () => {
  let pending = true
  const { vm, emitted } = createVm({ pendingMerchantClaims: async () => ok(pending ? [row()] : []) })
  vm.rows = [row()]
  await vm.prepare(vm.rows, 'APPROVE', false)
  assert.ok(vm.command)
  assert.match(vm.results[0].message, /回读仍为待审/)
  assert.deepEqual(emitted, [])
  pending = false
  await vm.recover()
  assert.equal(vm.command, null)
})

test('a mismatched receipt is unknown and can replay the original request instead of persisting unusable evidence', async () => {
  let wrong = true
  const { vm } = createVm({ auditMerchantClaim: async payload => wrong ? ok({ id: '6', status: 'APPROVED' }) : receipt(payload) })
  vm.rows = [row()]
  await vm.prepare(vm.rows, 'APPROVE', false)
  assert.ok(vm.command)
  assert.equal(vm.command.receipt, null)
  assert.match(vm.error, /不一致/)
  wrong = false
  await vm.recover()
  assert.equal(vm.command, null)
})

test('explicit business rejection refreshes the queue and unlocks after review without reporting success', async () => {
  const rejected = new Error('认领状态或版本已变化')
  rejected.confirmedRejected = true
  const { vm, emitted } = createVm({ auditMerchantClaim: async () => { throw rejected }, pendingMerchantClaims: async () => ok([row()]) })
  vm.rows = [row()]
  await vm.prepare(vm.rows, 'APPROVE', false)
  assert.equal(vm.command, null)
  assert.match(vm.error, /审核请求被拒绝/)
  assert.deepEqual(emitted, [])
  await vm.loadPending()
  assert.equal(vm.canSubmit, true)
})

test('batch partial results keep each backend failure, verify successes independently and never invent batch IDs', async () => {
  let sent
  const first = row('9007199254740993')
  const second = row('9007199254740994')
  const { vm } = createVm({
    auditMerchantClaimBatch: async payload => {
      sent = payload
      return ok([
        { claimId: first.id, success: true, currentStatus: 'REJECTED', code: 'OK', message: '处理成功' },
        { claimId: second.id, success: false, currentStatus: 'PENDING', code: 'STATE_CONFLICT', message: '版本已变化' }
      ])
    }, pendingMerchantClaims: async () => ok([second])
  })
  vm.rows = [first, second]
  await vm.prepare(vm.rows, 'REJECT', true)
  await vm.submitReject('材料不符')
  assert.deepEqual(Object.keys(sent).sort(), ['action', 'items', 'reason', 'requestId'])
  assert.deepEqual(sent.items, [first, second].map(r => ({ claimId: r.id, expectedStatus: 'PENDING', expectedVersion: 3 })))
  assert.match(vm.results[0].message, /独立回读确认/)
  assert.match(vm.results[1].message, /STATE_CONFLICT：版本已变化；回读仍待审/)
  assert.equal(vm.command, null)
  assert.equal(vm.rows[0].id, second.id)
})

test('duplicate batch result IDs and unsafe ID numbers are rejected rather than falsely verified', async () => {
  const { vm } = createVm({ auditMerchantClaimBatch: async () => ok([
    { claimId: '1', success: true, currentStatus: 'APPROVED' }, { claimId: '1', success: true, currentStatus: 'APPROVED' }
  ]) })
  vm.rows = [row('1'), row('2')]
  await vm.prepare(vm.rows, 'APPROVE', true)
  assert.match(vm.error, /项目与原请求不一致/)
  assert.ok(vm.command)
  const unsafe = createVm({ pendingMerchantClaims: async () => ok([row(9223372036854775807)]) }).vm
  await unsafe.loadPending()
  assert.match(unsafe.error, /精度已丢失/)
  assert.equal(unsafe.canSubmit, false)
})

test('storage failure prevents sending an unrecorded command; evidence uses safe URLs and preserves opaque IDs', async () => {
  let writes = 0
  const session = storage()
  session.setItem = () => { throw new Error('quota') }
  const { vm } = createVm({ auditMerchantClaim: () => { writes++ } }, session)
  vm.rows = [row()]
  await vm.prepare(vm.rows, 'APPROVE', false)
  assert.equal(writes, 0)
  assert.equal(vm.command, null)
  assert.match(vm.error, /本次未提交/)
  assert.match(vm.evidence(row())[0].content, /申请说明：本人为店铺经营者/)
  assert.equal(vm.proofUrl('https://assets.example.test/proof.png'), 'https://assets.example.test/proof.png')
  for (const url of ['javascript:alert(1)', '//untrusted.test/x', '1234567890123456789', 'data:image/png;base64,x']) assert.equal(vm.proofUrl(url), '')
})
