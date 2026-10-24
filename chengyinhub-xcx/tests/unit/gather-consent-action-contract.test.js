'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const ledger = require('../../scripts/uiaudit/action-ledger.json')
const { validateAuthorityEvidence, validateBranchReceipt } = require('../../scripts/uiaudit/action-evidence-store')
const gather = ledger.controls.find(c => c.entry.js === 'pages/play/index.js' && c.control.handler === 'leadArrive')
const principal = { identityFingerprint: 'sha256:fixture-player', accountRole: 'player' }
function authority(action, step, before, after, options = {}) {
  return {
    actionId: action.id, fixtureId: 'contract-only', expectedRequest: step,
    request: { method: step.method, url: step.urlTemplate, resourceId: '100', authMode: 'jwt-fixture', ...options },
    accessScope: { principalFingerprint: principal.identityFingerprint, role: 'player', ownerFingerprint: principal.identityFingerprint,
      scope: 'owned-resource', scopeVerified: true, decision: 'allowed' },
    authority: { endpoint: step.urlTemplate, resourceId: '100', before, after },
    mapping: { matched: true, paths: ['lead.meArrived'] }, assertions: ['controlled validator fixture; no UI acceptance'],
  }
}
test('集合签到只绑定单次GCJ02与真实签到写，再独立成员进度读', () => {
  assert.ok(gather)
  assert.deepEqual(gather.externalCapabilities, ['getLocation'])
  assert.deepEqual(gather.targets.writeApis, ['/api/club/lead/arrive'])
  assert.deepEqual(gather.requiredRequests, [
    { urlTemplate: '/api/club/lead/arrive', method: 'POST', kind: 'write' },
    { urlTemplate: '/api/club/lead/team-progress', method: 'GET', kind: 'read' },
  ])
  assert.deepEqual(gather.requiredRequestOutcomes['progress-error-after-arrive'], ['success', 'error'])
  assert.deepEqual(gather.requiredRequestOutcomes['progress-timeout-after-arrive'], ['success', 'timeout'])
  assert.deepEqual(gather.requiredRequestOutcomes['getLocation-denied'], ['not-called'])
  assert.deepEqual(gather.requiredRequestOutcomes['modal-cancelled'], ['not-called'])
})
test('签到不能只有toast/POST成功、缺独立GET或调换因果顺序', () => {
  const [write, read] = gather.requiredRequests
  const sequence = [authority(gather, write, { meArrived: false }, { meArrived: true }),
    authority(gather, read, { meArrived: true }, { meArrived: true })]
  assert.doesNotThrow(() => validateAuthorityEvidence(gather, { sequence }, 'write', 'success', principal))
  assert.throws(() => validateAuthorityEvidence(gather, { sequence: sequence.slice(0, 1) }, 'write', 'success', principal), /2 个/)
  assert.throws(() => validateAuthorityEvidence(gather, { sequence: sequence.slice().reverse() }, 'write', 'success', principal), /顺序/)
  const missingMapping = structuredClone(sequence); delete missingMapping[1].mapping
  assert.throws(() => validateAuthorityEvidence(gather, { sequence: missingMapping }, 'write', 'success', principal), /映射/)
})
test('签到撤销定位/取消弹窗须零请求零签到事实；GPS成功仍要求坐标', () => {
  const step = gather.requiredRequestsByBranch['getLocation-denied'][0]
  const noCall = authority(gather, step, { meArrived: false }, { meArrived: false }, { observed: false })
  assert.doesNotThrow(() => validateAuthorityEvidence(gather, noCall, 'write', 'getLocation-denied', principal))
  assert.throws(() => validateAuthorityEvidence(gather, { ...noCall, request: { ...noCall.request, observed: true } }, 'write', 'getLocation-denied', principal), /未发出/)
  assert.throws(() => validateAuthorityEvidence(gather, { ...noCall, authority: { ...noCall.authority, after: { meArrived: true } } }, 'write', 'getLocation-denied', principal), /未变化/)
  const receipt = { actionId: gather.id, branch: 'success', fixtureId: 'contract-only', assertions: ['contract-only'],
    observed: { capabilities: { getLocation: { outcome: 'success' } } } }
  assert.throws(() => validateBranchReceipt(gather, 'success', receipt, { id: 'contract-only' }), /latitude\/longitude/)
})
test('漫游AGREE fresh latest→write与固定基线超时重试明确分开，REVOKE不读前置', () => {
  const starts = ledger.controls.filter(c => c.entry.js === 'pages/roam/index.js' && ['goStart', 'goStartAndShoot'].includes(c.control.handler))
  // 2026-10-17 漫游两栏改版删掉拍照开局 goStartAndShoot,只剩 goStart 的 3 个入口。
  assert.equal(starts.length, 3)
  for (const entry of starts) {
    assert.deepEqual(entry.requiredRequests.map(r => [r.urlTemplate, r.method, r.kind]), [
      ['/api/compliance/consents/latest', 'POST', 'read'], ['/api/compliance/consents', 'POST', 'write'],
    ])
    assert.deepEqual(entry.requiredRequestsByBranch['baseline-retry'].map(r => r.urlTemplate), ['/api/compliance/consents'])
    assert.deepEqual(entry.requiredRequestOutcomes['record-error-after-latest'], ['success', 'error'])
    const denied = entry.requiredBranches.find(b => entry.requiredCapabilityByBranch[b] === 'getLocation')
    assert.ok(denied); assert.deepEqual(entry.requiredRequestOutcomes[denied], ['not-called'])
  }
  for (const entry of ledger.controls.filter(c => c.control.handler === 'withdrawRoamLocationConsent')) {
    assert.deepEqual(entry.requiredRequests.map(r => r.urlTemplate), ['/api/compliance/consents'])
    assert.equal(entry.targets.primaryApis.includes('/api/compliance/consents/latest'), false)
    assert.equal(entry.successCapabilityContract, 'roam-purpose-revocation')
  }
})
test('漫游fresh不能偷掉CAS前置读或调换顺序；retry不强迫新读', () => {
  const action = ledger.controls.find(c => c.entry.js === 'pages/roam/index.js' && c.control.handler === 'goStart')
  const [read, write] = action.requiredRequests
  const sequence = [authority(action, read, { event: 'REVOKE' }, { event: 'REVOKE' }),
    authority(action, write, { event: 'REVOKE' }, { event: 'AGREE' })]
  assert.doesNotThrow(() => validateAuthorityEvidence(action, { sequence }, 'write', 'success', principal))
  assert.throws(() => validateAuthorityEvidence(action, { sequence: sequence.slice(1) }, 'write', 'success', principal), /2 个/)
  assert.throws(() => validateAuthorityEvidence(action, { sequence: sequence.slice().reverse() }, 'write', 'success', principal), /顺序/)
  assert.doesNotThrow(() => validateAuthorityEvidence(action, sequence[1], 'write', 'baseline-retry', principal))
})
test('app_launch和报名同意不被中央roam条件分支污染，门店独立latest回读仍保留', () => {
  for (const entry of ledger.controls.filter(c => ['agreePrivacy', 'retrySubmit', 'onSelfPlayConfirm'].includes(c.control.handler))) {
    assert.equal(entry.targets.primaryApis.includes('/api/compliance/consents/latest'), false, entry.id)
  }
  for (const handler of ['openVerifyCode', 'revokeMerchantConsent']) {
    const entry = ledger.controls.find(c => c.entry.js === 'pages/play/merchant/index.js' && c.control.handler === handler)
    assert.ok(entry.targets.primaryApis.includes('/api/compliance/consents/latest'))
  }
})
