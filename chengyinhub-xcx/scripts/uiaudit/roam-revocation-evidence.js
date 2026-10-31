'use strict'

const crypto = require('crypto')
const { stableJson } = require('./trusted-attestation')
const CONTRACT = 'roam-purpose-revocation'
const ENTRIES = new Set(['pages/privacy/index.js', 'pages/shezhi/components/privacy-sheet/index.js'])
const METHODS = ['startLocationUpdate', 'startLocationUpdateBackground', 'stopLocationUpdate']
const RUNTIME_SOURCES = {
  roamOwner: 'utils/location/bg-tracker.js#hasOwner:roam',
  revision: 'utils/location/bg-tracker.js#revision:roam',
  trackerRunning: 'utils/location/bg-tracker.js#isRunning',
  realEpoch: 'pages/roam/index.js#_realStartEpoch',
  realOn: 'pages/roam/index.js#_realOn',
  listener: 'pages/roam/index.js#_onRealLoc',
  starting: 'pages/roam/index.js#_realStarting',
  goPending: 'pages/roam/index.js#_roamStartPending',
  fogPending: 'pages/roam/index.js#_fogStartPending.startTracking',
}
function fail(reason) { throw new Error(`UNVERIFIED roam REVOKE: ${reason}`) }
function requireFact(value, reason) { if (!value) fail(reason) }
function isCaller(action) {
  return action && ENTRIES.has(action.entry && action.entry.js)
    && action.control && action.control.handler === 'withdrawRoamLocationConsent'
}
function isContract(action) {
  return action && action.successCapabilityContract === CONTRACT
}
function validate(action, branch, receipt, capture) {
  requireFact(ENTRIES.has(action.entry && action.entry.js)
    && action.control && action.control.handler === 'withdrawRoamLocationConsent'
    && (action.targets.writeApis || []).length === 1
    && action.targets.writeApis[0] === '/api/compliance/consents', '合同未绑定真实撤回caller')
  const observed = receipt.observed
  const proof = observed.roamRevocation
  requireFact(capture && capture.verified === true && capture.captureId && capture.sourceDigest
    && capture.principalFingerprint
    && capture.readbackDigest === crypto.createHash('sha256').update(stableJson(receipt)).digest('hex'), '缺同源受信设备capture')
  requireFact(proof && proof.captureId === capture.captureId
    && proof.principalFingerprint === capture.principalFingerprint, '用途回读与capture/principal不一致')
  const source = (ref, expected) => requireFact(ref && ref.captureId === capture.captureId
    && ref.sourceDigest === capture.sourceDigest && ref.source === expected, `缺实际capture来源 ${expected}`)
  const before = proof.before; const after = proof.after
  requireFact(before && after && proof.sourceRefs, '缺用途前后状态回读')
  Object.entries(RUNTIME_SOURCES).forEach(([field, expected]) => {
    source(proof.sourceRefs[field], expected)
    requireFact(proof.sourceRefs[field].beforeValue === before[field]
      && proof.sourceRefs[field].afterValue === after[field], `用途事实不符原始capture ${field}`)
    if (field === 'revision' || field === 'realEpoch') {
      requireFact(Number.isSafeInteger(before[field]) && before[field] >= 0
        && Number.isSafeInteger(after[field]) && after[field] >= 0, `缺实际${field}`)
    } else requireFact(typeof before[field] === 'boolean' && typeof after[field] === 'boolean', `缺实际${field}`)
  })
  const authority = receipt.authoritativeReadback
  requireFact(authority && authority.accessScope && authority.accessScope.principalFingerprint === capture.principalFingerprint
    && authority.accessScope.ownerFingerprint === capture.principalFingerprint, '撤回权威回读非同principal')
  const latest = authority.authority && authority.authority.after
  requireFact(authority.authority && authority.authority.endpoint === '/api/compliance/consents/latest'
    && authority.authority.readMethod === 'POST', '缺既有POST latest独立回读')
  const record = proof.recordRequest
  source(proof.sourceRefs.recordRequest, 'utils/compliance/consent-client.js#record')
  requireFact(record && record.url === '/api/compliance/consents' && record.method === 'POST'
    && record.body && record.body.docType === 'privacy_policy' && record.body.scene === 'roam_location'
    && record.body.eventType === 'REVOKE' && record.body.requestId
    && proof.sourceRefs.recordRequest.requestIdValue === record.body.requestId,
  '缺本次真实record request/body')
  // 审计结果与本机用途分开证明：写失败也必须有完整停采/独立用途保留事实，不能冒充已落库。
  const recordFailed = ['error', 'timeout'].includes(branch) && proof.scenario === 'record-failed'
  const scenario = recordFailed ? proof.localScenario : proof.scenario
  requireFact(proof.requestId === record.body.requestId, 'proof未绑定原审计请求')
  if (recordFailed) {
    requireFact(['stop-confirmed', 'prior-stop-completed', 'independent-play-retained'].includes(scenario), '缺审计未确认时的本机用途终态')
    requireFact(!proof.recordEcho && !(latest && latest.eventType === 'REVOKE'
      && latest.requestId === record.body.requestId), '审计未确认不能伪造成功回执')
  } else {
    source(proof.sourceRefs.recordEcho, 'utils/compliance/consent-client.js#record.success')
    requireFact(proof.recordEcho && proof.recordEcho.requestId === record.body.requestId
      && proof.recordEcho.docType === record.body.docType && proof.recordEcho.scene === record.body.scene
      && proof.recordEcho.eventType === record.body.eventType && proof.recordEcho.docVersion
      && proof.requestId === record.body.requestId
      && proof.sourceRefs.recordEcho.requestIdValue === proof.recordEcho.requestId,
    '本次record request/echo与proof不一致')
    requireFact(latest && latest.docType === 'privacy_policy' && latest.scene === 'roam_location'
      && latest.eventType === 'REVOKE' && latest.requestId && latest.requestId === proof.requestId
      && latest.docVersion === proof.recordEcho.docVersion, 'latest不是本次REVOKE')
  }
  requireFact(after.revision > before.revision && after.realEpoch >= before.realEpoch
    && (!before.roamOwner && !before.listener && !before.starting && !before.goPending && !before.fogPending
      || after.realEpoch > before.realEpoch), '旧revision/epoch未失效')
  requireFact(after.roamOwner === false && after.listener === false && after.realOn === false
    && after.starting === false && after.goPending === false && after.fogPending === false, '漫游owner/listener/pending未释放')
  source(proof.sourceRefs.sdkTrace, 'wechat-sdk-callbacks')
  const sdk = proof.sdk
  requireFact(sdk && Number.isSafeInteger(sdk.revokedOrder) && Number.isSafeInteger(sdk.settledOrder)
    && sdk.settledOrder > sdk.revokedOrder && Array.isArray(sdk.calls), '缺SDK撤回与终态时序')
  requireFact(Number.isSafeInteger(record.startedOrder) && sdk.revokedOrder < record.startedOrder
    && record.startedOrder < sdk.settledOrder
    && proof.sourceRefs.recordRequest.startedOrderValue === record.startedOrder, '本机撤回必须早于原审计POST')
  const ids = new Set()
  sdk.calls.forEach(call => {
    requireFact(call && call.id && !ids.has(call.id) && METHODS.includes(call.method)
      && Number.isSafeInteger(call.startedOrder) && Number.isSafeInteger(call.completedOrder)
      && call.completedOrder > call.startedOrder && call.completedOrder <= sdk.settledOrder
      && ['success', 'denied', 'error'].includes(call.outcome), '未知或未终态SDK调用')
    ids.add(call.id)
  })
  const capabilities = observed.capabilities
  requireFact(capabilities && typeof capabilities === 'object', '缺逐SDK事实')
  METHODS.forEach(method => {
    const calls = sdk.calls.filter(call => call.method === method).sort((a, b) => a.completedOrder - b.completedOrder)
    const fact = capabilities[method]
    if (!calls.length) { requireFact(!fact, '未发生SDK被伪造成功'); return }
    const last = calls[calls.length - 1]
    requireFact(fact && fact.callId === last.id && fact.outcome === last.outcome, 'SDK摘要不符实际callback trace')
  })
  const newCalls = sdk.calls.filter(call => call.startedOrder > sdk.revokedOrder)
  const stops = sdk.calls.filter(call => call.method === 'stopLocationUpdate')
  const oldStarts = sdk.calls.filter(call => call.method !== 'stopLocationUpdate'
    && call.startedOrder < sdk.revokedOrder && call.completedOrder > sdk.revokedOrder)
  if (before.roamOwner && !before.trackerRunning) {
    requireFact(oldStarts.length > 0 || stops.some(call => call.startedOrder < sdk.revokedOrder
      && call.completedOrder > sdk.revokedOrder), '原持有者启动/停止在途历史未回读，不能仅snapshot停止认成功')
  }
  const pendingPlay = ['independent-play-pending-started', 'independent-play-pending-denied'].includes(scenario)
  oldStarts.filter(call => call.outcome === 'success' && !pendingPlay).forEach(call => requireFact(
    stops.some(stop => stop.startedOrder > call.completedOrder && stop.outcome === 'success'), '旧start成功后缺补停终态'))
  const success = call => {
    requireFact(call && call.outcome === 'success', '缺实际SDK成功')
    if (call.method === 'stopLocationUpdate') requireFact(call.ok === true, '缺停止回调成功')
    else {
      const fact = capabilities[call.method]
      requireFact(Number.isFinite(call.latitude) && Number.isFinite(call.longitude)
        && fact.latitude === call.latitude && fact.longitude === call.longitude, 'start成功缺真实GPS坐标')
      const gps = call.gpsSample
      source(gps, 'pages/play/index.js#_playLocationHandler')
      requireFact(gps.callId === call.id && Number.isSafeInteger(gps.observedOrder)
        && gps.observedOrder > call.completedOrder && gps.observedOrder <= sdk.settledOrder
        && gps.latitude === call.latitude && gps.longitude === call.longitude,
      'GPS采样未绑定新SDK终态后的真实play监听')
    }
  }
  const stopped = () => {
    requireFact(after.trackerRunning === false, '系统定位未回读到停止')
    requireFact(stops.length > 0 && stops.every(call => call.outcome === 'success'), '缺全部实际stop成功')
    stops.forEach(success)
    requireFact(newCalls.every(call => call.method === 'stopLocationUpdate'), '漫游撤回后出现未授权新start')
  }
  if (scenario === 'no-active-collection') {
    source(proof.sourceRefs.stopState, 'utils/location/bg-tracker.js#stopState')
    requireFact(proof.sourceRefs.stopState.beforeValue === before.stopState
      && proof.sourceRefs.stopState.afterValue === after.stopState
      && before.stopState === 'confirmed' && after.stopState === 'confirmed', '无采集分型缺实际已确认停止状态')
    requireFact(before.roamOwner === false && before.trackerRunning === false && before.starting === false
      && before.realOn === false && before.listener === false
      && after.roamOwner === false && after.trackerRunning === false && after.starting === false
      && after.realOn === false && after.listener === false, '无采集分型存在持有者或在途采集')
    requireFact(newCalls.length === 0, '无采集分型期间不得出现新SDK调用')
    requireFact(stops.every(call => call.outcome === 'success' && call.ok === true
      && call.completedOrder < sdk.revokedOrder), '无采集分型此前停止失败或尚在途')
    requireFact(sdk.calls.filter(call => call.method !== 'stopLocationUpdate' && call.outcome === 'success')
      .every(call => stops.some(stop => stop.completedOrder > call.completedOrder
        && stop.completedOrder < sdk.revokedOrder)), '无采集分型此前成功启动缺已完成停止')
    requireFact(['success', 'duplicate-trigger', 'timeout'].includes(branch), '无采集分型不符分支')
  } else if (scenario === 'stop-confirmed') {
    requireFact(newCalls.some(call => call.method === 'stopLocationUpdate'), '缺本次实际stop')
    stopped()
    requireFact(['success', 'duplicate-trigger', 'timeout'].includes(branch) || recordFailed, '停止形态不符分支')
  } else if (scenario === 'prior-stop-completed') {
    requireFact(newCalls.length === 0 && stops.some(call => call.startedOrder < sdk.revokedOrder
      && call.completedOrder > sdk.revokedOrder), '缺此前在途stop的真实终态')
    stopped()
    requireFact(['success', 'duplicate-trigger', 'timeout'].includes(branch) || recordFailed, '先前stop形态不符分支')
  } else {
    const play = proof.play
    requireFact(play && /^play:[^\s]+$/.test(play.key), '缺独立play用途')
    source(play.ownerSource, `utils/location/bg-tracker.js#hasOwner:${play.key}`)
    source(play.permissionSource, 'pages/play/index.js#requestPlayLocation')
    requireFact(play.ownerSource.beforeValue === play.ownerBefore && play.ownerSource.atStartValue === play.ownerAtStart
      && play.ownerSource.afterValue === play.ownerAfter
      && play.permissionSource.gpsOkAtStartValue === play.gpsOkAtStart
      && play.permissionSource.watchingAtStartValue === play.watchingAtStart
      && play.permissionSource.watchingAfterValue === play.watchingAfter, '独立play事实不符实际capture')
    requireFact(play.gpsOkAtStart === true && play.watchingAtStart === true && play.ownerAtStart === true,
      '缺真实独立play许可/持有状态')
    if (pendingPlay) {
      requireFact(before.trackerRunning === false && play.ownerBefore === true && oldStarts.length > 0
        && stops.every(call => call.completedOrder < sdk.revokedOrder)
        && newCalls.every(call => call.method === 'startLocationUpdate'), '旧pending代际没有实际独立play许可或发生补停')
      const terminalStarts = sdk.calls.filter(call => call.method !== 'stopLocationUpdate'
        && call.completedOrder > sdk.revokedOrder).sort((a, b) => a.completedOrder - b.completedOrder)
      const final = terminalStarts[terminalStarts.length - 1]
      if (terminalStarts.length > 1) requireFact(terminalStarts.length === 2
        && terminalStarts[0].method === 'startLocationUpdateBackground' && terminalStarts[0].outcome === 'denied'
        && final.method === 'startLocationUpdate' && final.startedOrder > terminalStarts[0].completedOrder,
      'pending play没有真实BG拒→FG链')
      if (scenario === 'independent-play-pending-denied') {
        requireFact(branch === 'startLocationUpdate-denied' && final.method === 'startLocationUpdate'
          && final.outcome === 'denied' && after.trackerRunning === false
          && play.ownerAfter === false && play.watchingAfter === false, 'pending拒绝没有真实失败终态')
        return
      }
      requireFact(['success', 'startLocationUpdateBackground-denied'].includes(branch)
        && after.trackerRunning === true && play.ownerAfter === true && play.watchingAfter === true,
      '独立pending play未实际运行')
      success(final)
      if (branch === 'startLocationUpdateBackground-denied') requireFact(terminalStarts.length === 2, '缺实际BG拒绝')
      return
    }
    if (scenario === 'independent-play-retained') {
      requireFact(before.trackerRunning === true && after.trackerRunning === true && play.ownerBefore === true
        && play.ownerAfter === true && play.watchingAfter === true && newCalls.length === 0,
      '保留play形态发生了stop/start或未保留实际owner')
      requireFact(['success', 'duplicate-trigger', 'timeout'].includes(branch) || recordFailed, '保留play形态不符分支')
    } else {
      requireFact(stops.some(call => call.outcome === 'success'), '独立重启缺真实stop终态')
      stops.forEach(success)
      const bg = newCalls.filter(call => call.method === 'startLocationUpdateBackground')
      const fg = newCalls.filter(call => call.method === 'startLocationUpdate')
      if (['independent-play-fg-only-restarted', 'independent-play-fg-only-denied'].includes(scenario)) {
        source(play.availabilitySource, 'wx.startLocationUpdateBackground#available')
        requireFact(play.backgroundApiAvailable === false && play.availabilitySource.value === false
          && bg.length === 0 && fg.length === 1
          && fg[0].startedOrder > Math.max(...stops.map(call => call.completedOrder)), '缺实际FG-only能力与时序')
        if (scenario === 'independent-play-fg-only-denied') {
          requireFact(branch === 'startLocationUpdate-denied' && fg[0].outcome === 'denied'
            && after.trackerRunning === false && play.ownerAfter === false && play.watchingAfter === false,
          'FG-only拒绝未回读失败终态')
          return
        }
        requireFact(branch === 'success' && after.trackerRunning === true && play.ownerAfter === true
          && play.watchingAfter === true, 'FG-only未真实运行')
        success(fg[0])
        return
      }
      requireFact(bg.length === 1 && bg[0].startedOrder > Math.max(...stops.map(call => call.completedOrder)), '重启未在stop终态之后')
      if (scenario === 'independent-play-bg-restarted') {
        requireFact(branch === 'success' && fg.length === 0, 'BG成功形态不符实际调用')
        success(bg[0])
      } else if (scenario === 'independent-play-fg-restarted') {
        requireFact(['success', 'startLocationUpdateBackground-denied'].includes(branch)
          && bg[0].outcome === 'denied' && fg.length === 1 && fg[0].startedOrder > bg[0].completedOrder, '缺BG拒绝→FG实际降级')
        success(fg[0])
      } else if (scenario === 'independent-play-both-denied') {
        requireFact(branch === 'startLocationUpdate-denied' && bg[0].outcome === 'denied'
          && fg.length === 1 && fg[0].startedOrder > bg[0].completedOrder && fg[0].outcome === 'denied'
          && after.trackerRunning === false && play.ownerAfter === false && play.watchingAfter === false,
        '双拒绝未回读到失败终态/用途释放')
        return
      } else fail('未知实际形态')
      requireFact(after.trackerRunning === true && play.ownerAfter === true && play.watchingAfter === true, '独立play没有恢复运行')
    }
  }
  requireFact(['success', 'duplicate-trigger', 'timeout', 'startLocationUpdateBackground-denied'].includes(branch) || recordFailed, '形态与分支不匹配')
}
module.exports = { CONTRACT, isCaller, isContract, validate }
