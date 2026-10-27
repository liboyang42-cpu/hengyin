'use strict'
// 所有runtime值来自真实tracker/roam/play caller的隔离SDK夹具。仅验证合同，不是真机或HTTP证据。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const crypto = require('node:crypto')
const { stableJson } = require('../../scripts/uiaudit/trusted-attestation')
const { validateBranchReceipt } = require('../../scripts/uiaudit/action-evidence-store')
const { CONTRACT } = require('../../scripts/uiaudit/roam-revocation-evidence')
const { createConsentClient } = require('../../utils/compliance/consent-client')
const settle = () => new Promise(resolve => setImmediate(resolve))
const principal = 'sha256:unit-principal'
const sourceDigest = 'a'.repeat(64)
const fixture = { id: 'unit-only' }
const action = { id: 'unit-revoke', entry: { js: 'pages/privacy/index.js' },
  control: { handler: 'withdrawRoamLocationConsent' }, actionClass: 'external',
  successCapabilityContract: CONTRACT, externalCapabilities: ['startLocationUpdate', 'startLocationUpdateBackground', 'stopLocationUpdate'],
  successCapabilities: ['startLocationUpdate', 'startLocationUpdateBackground', 'stopLocationUpdate'],
  targets: { writeApis: ['/api/compliance/consents'] }, requiredCapabilityByBranch: {
    'startLocationUpdate-denied': 'startLocationUpdate', 'startLocationUpdateBackground-denied': 'startLocationUpdateBackground',
  } }

function harness(noBackground) {
  const h = { calls: [], listeners: new Set(), order: 0, modals: [] }
  const app = { globalData: { user_id: 9, features: {} }, getUserID: () => 9, isDevEnv: () => false,
    sendRequest() {}, recordConsent(p) { h.record = p; return h.consent.record(p) } }
  global.getApp = () => app
  const sdk = (method, options) => {
    const call = { id: 'call-' + (h.calls.length + 1), method, startedOrder: ++h.order, outcome: 'pending' }
    h.calls.push(call); call.options = options
  }
  const wx = global.wx = {
    getStorageSync: () => '', setStorageSync() {}, showToast() {},
    getWindowInfo: () => ({ statusBarHeight: 20 }), getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    showModal(o) { h.modals.push(o) }, getLocation(o) { o.success({ latitude: 31, longitude: 121 }) },
    startLocationUpdateBackground(o) { sdk('startLocationUpdateBackground', o) },
    startLocationUpdate(o) { sdk('startLocationUpdate', o) }, stopLocationUpdate(o) { sdk('stopLocationUpdate', o) },
    onLocationChange(fn) { h.listeners.add(fn) }, offLocationChange(fn) { h.listeners.delete(fn) }, setKeepScreenOn() {},
  }
  if (noBackground) wx.startLocationUpdateBackground = undefined
  h.consent = createConsentClient({ getUserID: app.getUserID, sendRequest(request) {
    // SC-PRIV-03:撤回结果未知时 consent-client 会先回读 latest;服务端最新仍是旧的同意(与 record-failed 回执的 latest 一致)
    if (request.url === '/api/compliance/consents/latest') {
      request.success({ code: 200, data: { requestId: 'previous-agree', docType: 'privacy_policy', scene: 'roam_location', eventType: 'AGREE' } })
      return
    }
    h.recordRequest = { url: request.url, method: request.method, body: JSON.parse(request.data), startedOrder: ++h.order }
    if (h.rejectRecord) { request.fail(); return }
    h.recordEcho = { ...h.recordRequest.body, docVersion: 'unit-version' }
    request.success({ code: 200, data: h.recordEcho })
  } })
  for (const file of ['../../utils/location/bg-tracker.js', '../../pages/roam/index.js', '../../pages/privacy/index.js']) delete require.cache[require.resolve(file)]
  let definition; global.Page = value => { definition = value }
  require('../../pages/roam/index.js')
  h.roam = { ...definition, data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done() }, _syncGoal() {} }
  require('../../pages/privacy/index.js')
  h.privacy = { ...definition, data: JSON.parse(JSON.stringify(definition.data)), setData: h.roam.setData }
  h.tracker = require('../../utils/location/bg-tracker.js')
  const revoke = h.tracker.revoke
  h.tracker.revoke = key => { h.revokedOrder = ++h.order; revoke(key) }
  const playSource = fs.readFileSync(require.resolve('../../pages/play/index.js'), 'utf8')
  const context = { wx, app, playLocationOwnerSequence: 0, bgTracker: h.tracker, cyModal: { show: o => h.modals.push(o) },
    playAccuracy: require('../../pages/play/utils/play-accuracy.js') }
  h.play = { data: { topicId: 31, gpsOk: false, nav: { active: false }, nodes: [], nextNode: null },
    setData: h.roam.setData, rebuild() {} }
  h.play._trackKey = vm.runInNewContext('({' + playSource.match(/  _trackKey\(\) \{[^\n]*\},/)[0] + '})', context)._trackKey
  // W8:账号守卫同时核对登录上下文(令牌+换代),捕获函数是守卫的一部分,同样取真实现。
  for (const name of ['_playMember', '_boundPlayOwner', '_capturePlayLoginContext', '_isPlayAccountCurrent', '_sessionParams', '_sessionKey', 'requestPlayLocation', 'watchLocation', '_noteLocAccuracy']) {
    const start = playSource.indexOf('  ' + name + '('); const end = playSource.indexOf('\n  },', start)
    h.play[name] = vm.runInNewContext('({' + playSource.slice(start, end + 4).trim() + '})', context)[name]
  }
  h.startPlay = () => {
    h.play.requestPlayLocation(); h.modals.at(-1).success({ confirm: true })
    h.playKey = h.play._playTrackingKey || h.play._trackKey()
    h.playAtStart = { ownerAtStart: h.tracker.hasOwner(h.playKey), gpsOkAtStart: h.play.data.gpsOk,
      watchingAtStart: !!h.play._watching }
  }
  h.fire = (call, outcome = 'success') => {
    assert.equal(call.outcome, 'pending'); call.outcome = outcome; call.completedOrder = ++h.order
    if (outcome === 'success') {
      call.ok = true
      if (call.method !== 'stopLocationUpdate') { call.latitude = 31; call.longitude = 121 }
      call.options.success()
      if (call.method !== 'stopLocationUpdate' && h.play._playLocationHandler) {
        h.play._playLocationHandler({ latitude: 31.001, longitude: 121.002 })
        call.latitude = h.play._loc.latitude; call.longitude = h.play._loc.longitude
        call.gpsSample = { ...ref('pages/play/index.js#_playLocationHandler'), callId: call.id,
          observedOrder: ++h.order, latitude: call.latitude, longitude: call.longitude }
      }
    } else call.options.fail({ errMsg: 'denied' })
  }
  h.frame = () => ({ roamOwner: h.tracker.hasOwner('roam'), revision: h.tracker.revision('roam'), trackerRunning: h.tracker.isRunning(),
    realEpoch: h.roam._realStartEpoch || 0, realOn: !!h.roam._realOn, listener: !!h.roam._onRealLoc,
    starting: !!h.roam._realStarting, goPending: !!h.roam._roamStartPending,
    fogPending: !!(h.roam._fogStartPending && h.roam._fogStartPending.startTracking) })
  return h
}
const sources = {
  roamOwner: 'utils/location/bg-tracker.js#hasOwner:roam', revision: 'utils/location/bg-tracker.js#revision:roam',
  trackerRunning: 'utils/location/bg-tracker.js#isRunning', realEpoch: 'pages/roam/index.js#_realStartEpoch',
  realOn: 'pages/roam/index.js#_realOn', listener: 'pages/roam/index.js#_onRealLoc',
  starting: 'pages/roam/index.js#_realStarting', goPending: 'pages/roam/index.js#_roamStartPending',
  fogPending: 'pages/roam/index.js#_fogStartPending.startTracking', sdkTrace: 'wechat-sdk-callbacks',
}
const ref = source => ({ captureId: 'unit-capture', sourceDigest, source })
async function receiptFor(kind, branch = 'success') {
  const h = harness(kind.startsWith('fg-only'))
  if (kind === 'cold') h.roam._startReal()
  else { h.roam._startReal(true); if (kind !== 'inflight-success' && kind !== 'inflight-denied' && !kind.startsWith('pending-play')) h.fire(h.calls[0]) }
  if (kind === 'already-stopped') { h.roam._stopReal(); h.fire(h.calls.at(-1)) }
  if (kind === 'retained' || kind === 'record-failed-retained' || kind.startsWith('pending-play')) h.startPlay()
  if (kind === 'prior-stop') { h.roam._stopReal(); h.roam._startReal() }
  const before = h.frame(); const playBefore = h.tracker.hasOwner(h.playKey || h.play._trackKey())
  if (kind.startsWith('record-failed')) h.rejectRecord = true
  h.privacy.withdrawRoamLocationConsent(); await settle()
  if (['bg-restart', 'fg-restart', 'both-denied', 'fg-only-restart', 'fg-only-denied'].includes(kind)) h.startPlay()
  if (kind !== 'retained' && kind !== 'record-failed-retained' && !kind.startsWith('pending-play')) h.fire(h.calls.find(c => c.method === 'stopLocationUpdate' && c.outcome === 'pending'))
  if (kind === 'inflight-success' || kind === 'inflight-denied') {
    h.fire(h.calls[0], kind === 'inflight-success' ? 'success' : 'denied')
    if (kind === 'inflight-success') h.fire(h.calls.at(-1))
  }
  if (['bg-restart', 'fg-restart', 'both-denied', 'fg-only-restart', 'fg-only-denied'].includes(kind)) {
    h.fire(h.calls.at(-1), ['bg-restart', 'fg-only-restart'].includes(kind) ? 'success' : 'denied')
    if (['fg-restart', 'both-denied'].includes(kind)) h.fire(h.calls.at(-1), kind === 'fg-restart' ? 'success' : 'denied')
  }
  if (kind.startsWith('pending-play')) {
    h.fire(h.calls[0], kind === 'pending-play-bg' ? 'success' : 'denied')
    if (kind !== 'pending-play-bg') h.fire(h.calls.at(-1), kind === 'pending-play-fg' ? 'success' : 'denied')
  }
  const scenario = ({ cold: 'stop-confirmed', stopped: 'stop-confirmed', 'already-stopped': 'stop-confirmed', 'inflight-success': 'stop-confirmed',
    'inflight-denied': 'stop-confirmed', 'prior-stop': 'prior-stop-completed', retained: 'independent-play-retained',
    'bg-restart': 'independent-play-bg-restarted', 'fg-restart': 'independent-play-fg-restarted',
    'both-denied': 'independent-play-both-denied', 'record-failed': 'record-failed', 'record-failed-retained': 'record-failed',
    'fg-only-restart': 'independent-play-fg-only-restarted', 'fg-only-denied': 'independent-play-fg-only-denied',
    'pending-play-bg': 'independent-play-pending-started', 'pending-play-fg': 'independent-play-pending-started',
    'pending-play-denied': 'independent-play-pending-denied' })[kind]
  const capabilities = Object.fromEntries(h.calls.filter(c => c.outcome !== 'pending').map(c => [c.method,
    { outcome: c.outcome, callId: c.id, ok: c.ok, latitude: c.latitude, longitude: c.longitude }]))
  const calls = h.calls.map(({ options, ...call }) => call)
  const proof = { captureId: 'unit-capture', principalFingerprint: principal, scenario, localScenario: kind.startsWith('record-failed') ? (kind === 'record-failed-retained' ? 'independent-play-retained' : 'stop-confirmed') : undefined, requestId: h.recordRequest.body.requestId, recordRequest: h.recordRequest, recordEcho: h.recordEcho, before,
    after: h.frame(), sourceRefs: Object.fromEntries(Object.entries(sources).map(([key, value]) => [key, ref(value)])),
    sdk: { revokedOrder: h.revokedOrder, settledOrder: ++h.order, calls },
    play: { key: h.playKey || h.play._trackKey(), ...h.playAtStart, ownerBefore: playBefore, ownerAfter: h.tracker.hasOwner(h.playKey || h.play._trackKey()),
      watchingAfter: !!h.play._watching, ownerSource: ref('utils/location/bg-tracker.js#hasOwner:' + (h.playKey || h.play._trackKey())),
      permissionSource: ref('pages/play/index.js#requestPlayLocation'), backgroundApiAvailable: typeof global.wx.startLocationUpdateBackground === 'function',
      availabilitySource: { ...ref('wx.startLocationUpdateBackground#available'), value: typeof global.wx.startLocationUpdateBackground === 'function' } } }
  const latest = { ...h.recordEcho, ...(kind.startsWith('record-failed') ? { requestId: 'previous-agree', docType: 'privacy_policy', scene: 'roam_location', eventType: 'AGREE' } : {}) }
  proof.sourceRefs.recordRequest = { ...ref('utils/compliance/consent-client.js#record'), requestIdValue: h.recordRequest.body.requestId, startedOrderValue: h.recordRequest.startedOrder }
  proof.sourceRefs.recordEcho = { ...ref('utils/compliance/consent-client.js#record.success'), requestIdValue: h.recordEcho && h.recordEcho.requestId }
  Object.assign(proof.play.ownerSource, { beforeValue: proof.play.ownerBefore, atStartValue: proof.play.ownerAtStart, afterValue: proof.play.ownerAfter })
  Object.assign(proof.play.permissionSource, { gpsOkAtStartValue: proof.play.gpsOkAtStart,
    watchingAtStartValue: proof.play.watchingAtStart, watchingAfterValue: proof.play.watchingAfter })
  for (const field of Object.keys(before)) { proof.sourceRefs[field].beforeValue = before[field]; proof.sourceRefs[field].afterValue = proof.after[field] }
  const receipt = { actionId: action.id, branch, fixtureId: fixture.id, assertions: ['unit-only runtime state harvest'],
    observed: { capabilities, roamRevocation: proof, errorClass: branch === 'error' ? 'network' : undefined,
      ...(branch.endsWith('denied') ? { outcome: 'denied', capability: branch === 'startLocationUpdate-denied' ? 'startLocationUpdate' : 'startLocationUpdateBackground', recovery: 'existing play location retry' } : {}) },
    authoritativeReadback: { accessScope: { principalFingerprint: principal, ownerFingerprint: principal },
      authority: { endpoint: '/api/compliance/consents/latest', readMethod: 'POST', after: latest } } }
  return { receipt, h }
}
function captureFor(receipt) { return { verified: true, captureId: 'unit-capture', sourceDigest, principalFingerprint: principal,
  readbackDigest: crypto.createHash('sha256').update(stableJson(receipt)).digest('hex') } }
function validate(receipt, options) { return validateBranchReceipt(action, receipt.branch, receipt, fixture, options || captureFor(receipt)) }
for (const kind of ['cold', 'stopped', 'already-stopped', 'inflight-success', 'inflight-denied', 'prior-stop', 'retained', 'bg-restart', 'fg-restart']) {
  test('真实caller/runtime回读形态可通过固定合同：' + kind, async () => { const { receipt } = await receiptFor(kind); validate(receipt) })
}
test('真实BG拒绝→FG成功和两者都拒绝匹配各自capability错误枝', async () => {
  validate((await receiptFor('fg-restart', 'startLocationUpdateBackground-denied')).receipt)
  validate((await receiptFor('both-denied', 'startLocationUpdate-denied')).receipt)
})
test('真实record未确认也必须撤回本机用途，不能冒充审计成功', async () => {
  const { receipt } = await receiptFor('record-failed', 'error'); validate(receipt)
  assert.equal(receipt.observed.roamRevocation.after.roamOwner, false)
  assert.equal(receipt.authoritativeReadback.authority.after.eventType, 'AGREE')
  for (const mutation of [
    proof => { proof.after = structuredClone(proof.before); for (const field of Object.keys(proof.before)) proof.sourceRefs[field].afterValue = proof.before[field] },
    proof => { proof.after.roamOwner = true; proof.sourceRefs.roamOwner.afterValue = true },
    proof => { proof.after.listener = true; proof.sourceRefs.listener.afterValue = true },
    proof => { proof.sdk.calls.at(-1).outcome = 'pending' },
    proof => { proof.sdk.calls = proof.sdk.calls.filter(call => call.method !== 'stopLocationUpdate') },
    proof => { proof.recordEcho = { ...proof.recordRequest.body, docVersion: 'unit-version' } },
    proof => { proof.recordRequest.startedOrder = proof.sdk.revokedOrder; proof.sourceRefs.recordRequest.startedOrderValue = proof.sdk.revokedOrder },
    proof => { proof.localScenario = 'unsupported-local-shape' },
  ]) {
    const changed = structuredClone(receipt); mutation(changed.observed.roamRevocation)
    assert.throws(() => validate(changed), /UNVERIFIED/)
  }
  const timeout = structuredClone(receipt); timeout.branch = 'timeout'
  assert.throws(() => validate(timeout), /elapsedMs\/timedOut=true\/aborted=true/)
})
test('审计未确认时独立play的真实许可和持有仍必须保留', async () => {
  const { receipt } = await receiptFor('record-failed-retained', 'error'); validate(receipt)
  const changed = structuredClone(receipt); const proof = changed.observed.roamRevocation
  proof.play.ownerAfter = false; proof.play.ownerSource.afterValue = false
  assert.throws(() => validate(changed), /UNVERIFIED/)
})
test('缺来源、SDK未知、latest错请求或旧owner/listener/epoch一律UNVERIFIED', async () => {
  const { receipt } = await receiptFor('stopped')
  const mutations = [r => { delete r.observed.roamRevocation.sourceRefs.listener },
    r => { r.observed.roamRevocation.sourceRefs.revision.captureId = 'other' },
    r => { r.observed.roamRevocation.after.roamOwner = true },
    r => { r.observed.roamRevocation.sourceRefs.roamOwner.afterValue = true },
    r => { r.observed.roamRevocation.after.listener = true },
    r => { r.observed.roamRevocation.after.revision = r.observed.roamRevocation.before.revision },
    r => { r.observed.roamRevocation.after.realEpoch = r.observed.roamRevocation.before.realEpoch },
    r => { r.observed.roamRevocation.sdk.calls.at(-1).outcome = 'pending' },
    r => { r.authoritativeReadback.authority.after.requestId = 'previous' },
    r => { r.authoritativeReadback.authority.after.eventType = 'AGREE' },
    r => { r.authoritativeReadback.authority.readMethod = 'GET' },
    r => { r.observed.roamRevocation.scenario = 'hand-written-release' },
    r => { r.observed.capabilities.startLocationUpdate = { outcome: 'success' } }]
  for (const mutate of mutations) { const copy = structuredClone(receipt); mutate(copy); assert.throws(() => validate(copy), /UNVERIFIED/) }
  assert.throws(() => validate(receipt, { ...captureFor(receipt), verified: false }), /受信设备capture/)
  assert.throws(() => validate(receipt, { ...captureFor(receipt), readbackDigest: 'b'.repeat(64) }), /受信设备capture/)
})
test('GPS成功坐标门保留：stop无坐标合法，BG/FG缺坐标和伪造拒绝为成功判红', async () => {
  validate((await receiptFor('stopped')).receipt)
  for (const kind of ['bg-restart', 'fg-restart']) {
    const { receipt } = await receiptFor(kind)
    delete receipt.observed.roamRevocation.sdk.calls.at(-1).latitude
    assert.throws(() => validate(receipt), /GPS坐标/)
  }
  const { receipt } = await receiptFor('fg-restart')
  receipt.observed.capabilities.startLocationUpdateBackground.outcome = 'success'
  assert.throws(() => validate(receipt), /不符实际callback/)
})

test('缺固定合同不能沿旧三SDK全成功或全空数组跳过用途回读', async () => {
  const { receipt } = await receiptFor('stopped')
  const old = { ...action }; delete old.successCapabilityContract
  for (const capabilities of [action.successCapabilities, []]) {
    old.successCapabilities = capabilities
    assert.throws(() => validateBranchReceipt(old, 'success', receipt, fixture, captureFor(receipt)), /旧合同不能跳过/)
  }
})

test('独立play许可/owner原始capture缺失或GPS在SDK终态之前都不能冒充恢复', async () => {
  for (const mutation of [
    r => { r.observed.roamRevocation.play.ownerSource.atStartValue = false },
    r => { delete r.observed.roamRevocation.play.permissionSource.watchingAtStartValue },
    r => { r.observed.roamRevocation.sdk.calls.at(-1).gpsSample.observedOrder = 0 },
    r => { r.observed.roamRevocation.sdk.calls.at(-1).gpsSample.callId = 'old-sdk' },
  ]) {
    const { receipt } = await receiptFor('bg-restart'); mutation(receipt)
    assert.throws(() => validate(receipt), /UNVERIFIED/)
  }
})

test('删掉撤回前在途SDK历史不能用stop后的false快照冒充终态', async () => {
  const { receipt } = await receiptFor('inflight-denied')
  const proof = receipt.observed.roamRevocation
  proof.sdk.calls = proof.sdk.calls.filter(call => call.method === 'stopLocationUpdate')
  delete receipt.observed.capabilities.startLocationUpdateBackground
  assert.throws(() => validate(receipt), /在途历史未回读/)
})

test('peer实证FG-only和获独立许可的旧pending代际正常保留，不伪造BG拒绝/补停', async () => {
  for (const kind of ['fg-only-restart', 'pending-play-bg']) validate((await receiptFor(kind)).receipt)
  validate((await receiptFor('pending-play-fg', 'startLocationUpdateBackground-denied')).receipt)
  for (const kind of ['fg-only-denied', 'pending-play-denied']) validate((await receiptFor(kind, 'startLocationUpdate-denied')).receipt)
  const { receipt } = await receiptFor('fg-only-restart')
  receipt.branch = 'startLocationUpdateBackground-denied'
  assert.throws(() => validate(receipt), /UNVERIFIED/)
})
test('本次actual consent-client请求是new，但latest/proof/echo都old REVOKE仍必须判红', async () => {
  const { receipt, h } = await receiptFor('stopped')
  assert.equal(h.recordRequest.body.requestId, receipt.observed.roamRevocation.requestId)
  assert.match(h.recordRequest.body.requestId, /^consent-/)
  const proof = receipt.observed.roamRevocation
  proof.requestId = 'old-revoke'; proof.recordEcho.requestId = 'old-revoke'
  proof.sourceRefs.recordEcho.requestIdValue = 'old-revoke'
  receipt.authoritativeReadback.authority.after.requestId = 'old-revoke'
  assert.throws(() => validate(receipt), /proof未绑定原审计请求/)
})
