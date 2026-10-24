'use strict'

const crypto = require('crypto')
const zlib = require('zlib')
const fs = require('fs')
const path = require('path')
const os = require('os')
const roamRevocation = require('./roam-revocation-evidence')

const SENSITIVE_KEY = /(?:authorization|bearer|token|password|passwd|secret|cookie|session|openid|unionid|phone|mobile|email|idcard)/i
const SENSITIVE_TEXT = /(?:\b(?:authorization|token|access_token|refresh_token|openid|unionid|password|passwd|secret|cookie|session)\s*[:=]\s*["']?[A-Za-z0-9._~+\/=;-]{6,}|Bearer\s+[A-Za-z0-9._~-]+|\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b|\b1[3-9]\d{9}\b|\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b)/gi

function redactEvidence(value, key) {
  if (SENSITIVE_KEY.test(String(key || ''))) return '[REDACTED]'
  if (Array.isArray(value)) return value.map((item) => redactEvidence(item))
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([childKey, child]) => [childKey, redactEvidence(child, childKey)]))
  }
  return typeof value === 'string' ? value.replace(SENSITIVE_TEXT, '[REDACTED]') : value
}

function assertNoSensitiveEvidence(value) {
  const serialized = JSON.stringify(value)
  if (new RegExp(SENSITIVE_TEXT.source, 'i').test(serialized)) {
    throw new Error('证据仍包含 token/手机号/邮箱等敏感信息')
  }
}

function parseObjectEnv(name, required) {
  const raw = process.env[name]
  if (!raw && !required) return null
  if (!raw) throw new Error(`缺少 ${name}`)
  try {
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('必须是 object')
    return parsed
  } catch (error) {
    throw new Error(`${name} 非法: ${error.message}`)
  }
}

function validateFixture(fixture) {
  if (!fixture || !String(fixture.id || '').trim() || !String(fixture.accountRole || '').trim()
      || !String(fixture.networkProfile || '').trim() || !String(fixture.backendMode || '').trim()
      || fixture.synthetic !== true) {
    throw new Error('release evidence 必须绑定 synthetic=true 的受控 fixture(id/accountRole/networkProfile/backendMode)')
  }
  const entry = fixture.entry
  if (!entry || !String(entry.route || '').trim() || String(entry.route).startsWith('component:')
      || !entry.query || typeof entry.query !== 'object' || Array.isArray(entry.query)) {
    throw new Error('release evidence fixture 必须绑定可启动的 entry.route 与 entry.query')
  }
  const locator = fixture.locator
  if (!locator || !String(locator.selector || '').trim()
      || locator.index !== 0
      || !/^[0-9a-f]{64}$/i.test(String(locator.actionFingerprint || ''))) {
    throw new Error('release evidence fixture 必须绑定 locator.selector/index/actionFingerprint')
  }
  const componentHostPath = locator.componentHostPath == null ? null : locator.componentHostPath
  if (componentHostPath != null && (!Array.isArray(componentHostPath) || !componentHostPath.length
      || componentHostPath.some((segment) => !segment || !String(segment.selector || '').trim()
        || !Number.isInteger(segment.index) || segment.index < 0))) {
    throw new Error('release evidence fixture locator.componentHostPath 必须绑定组件宿主 selector/index 链')
  }
  return {
    id: String(fixture.id),
    accountRole: String(fixture.accountRole),
    networkProfile: String(fixture.networkProfile),
    backendMode: String(fixture.backendMode),
    synthetic: true,
    entry: { route: String(entry.route).replace(/^\//, ''), query: entry.query },
    locator: {
      selector: String(locator.selector),
      index: locator.index,
      componentPath: locator.componentPath ? String(locator.componentPath) : null,
      componentHostPath: componentHostPath == null ? null : componentHostPath.map((segment) => ({
        selector: String(segment.selector), index: segment.index,
      })),
      actionFingerprint: String(locator.actionFingerprint || ''),
      expectedText: locator.expectedText == null ? null : String(locator.expectedText),
      instance: locator.instance ? {
        attribute: String(locator.instance.attribute || ''), value: String(locator.instance.value || ''),
      } : null,
    },
    controlPlane: fixture.controlPlane ? {
      baseUrl: String(fixture.controlPlane.baseUrl || ''),
      scenarioId: String(fixture.controlPlane.scenarioId || ''),
    } : null,
    branchFaults: fixture.branchFaults && typeof fixture.branchFaults === 'object'
      ? fixture.branchFaults : null,
  }
}

function fixtureDigest(fixture) {
  return crypto.createHash('sha256').update(stableJson(validateFixture(fixture))).digest('hex')
}

function actionLocatorFingerprint(action) {
  return crypto.createHash('sha256').update(stableJson({
    id: action.id,
    entry: action.entry,
    control: action.control,
  })).digest('hex')
}

function validateActionLocator(action, fixture) {
  const locator = fixture.locator || {}
  const defaultSelector = String(action.control && action.control.selectorHint || '').replace(
    /^([A-Za-z][\w-]*)\[(?:bind|catch):?tap\]$/, '$1',
  )
  if (locator.selector !== defaultSelector) throw new Error('fixture locator.selector 必须等于动作生成的 selector')
  if (locator.actionFingerprint !== actionLocatorFingerprint(action)) {
    throw new Error('fixture locator.actionFingerprint 未绑定当前 action 真源')
  }
  if (action.variant && action.variant.locatorInstance) {
    const expectedInstance = action.variant.locatorInstance
    if (!locator.instance || locator.instance.attribute !== expectedInstance.attribute
        || locator.instance.value !== expectedInstance.value) {
      throw new Error('fixture locator.instance 未绑定动作 variant 的 data-* 实例')
    }
  }
  const componentPath = String(action.route || '').startsWith('component:') ? action.route.slice('component:'.length) : null
  if ((componentPath && locator.componentPath !== componentPath) || (!componentPath && locator.componentPath)) {
    throw new Error('fixture locator.componentPath 与动作组件真源不一致')
  }
  if (componentPath && (!Array.isArray(action.hostRoutes) || !action.hostRoutes.includes(fixture.entry.route))) {
    throw new Error('fixture entry.route 不是组件静态可达的真实宿主页')
  }
  if (!componentPath && locator.componentHostPath != null) {
    throw new Error('页面动作不得声明 componentHostPath')
  }
  if (componentPath) {
    const allowed = (action.hostComponentPaths || []).filter((item) => item.route === fixture.entry.route)
      .map((item) => stableJson(item.segments))
    if (!locator.componentHostPath || !allowed.includes(stableJson(locator.componentHostPath))) {
      throw new Error('fixture locator.componentHostPath 未绑定组件静态可达的宿主实例链')
    }
  }
}

function normalizeUrl(value) {
  return String(value || '').replace(/[?#].*$/, '')
}

function endpointMatches(template, actual) {
  const escaped = normalizeUrl(template).split('{param}')
    .map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('[^/?#]+')
  return new RegExp(`^${escaped}$`).test(normalizeUrl(actual))
}

function authorityKindFor(action) {
  const effects = action && action.effectClasses && action.effectClasses.length
    ? action.effectClasses : [action && (action.baseActionClass || action.actionClass)]
  return effects.includes('write') ? 'write' : (effects.includes('read') ? 'read' : null)
}

function readObjectPath(value, dottedPath) {
  return String(dottedPath).split('.').reduce((current, part) => (current == null ? undefined : current[part]), value)
}

function validateAuthorityReceipt(action, receipt, kind, branch, fixtureAttestation) {
  if (!receipt || typeof receipt !== 'object') throw new Error('权威回读必须返回结构化 object')
  if (receipt.actionId !== action.id) throw new Error('权威回读 actionId 与当前动作不一致')
  if (!receipt.fixtureId || !receipt.request || !receipt.authority || !Array.isArray(receipt.assertions)
      || receipt.assertions.length === 0) throw new Error('权威回读缺少 fixtureId/request/authority/assertions')
  const request = receipt.request
  const targets = action.targets || {}
  const declaredSource = kind === 'write' && Array.isArray(targets.writeApis) && targets.writeApis.length
    ? targets.writeApis
    : (Object.prototype.hasOwnProperty.call(targets, 'primaryApis') ? targets.primaryApis : targets.apis)
  const declared = (declaredSource || []).map(normalizeUrl)
  if (declared.length === 0) throw new Error('read/write 动作缺少可绑定的 API 真源，拒绝任意 URL 回读')
  if (!request.method || !request.url || !request.resourceId || !request.authMode) {
    throw new Error('权威回读 request 必须含 method/url/resourceId/authMode')
  }
  if (declared.length && !declared.some((template) => endpointMatches(template, request.url))) {
    throw new Error(`权威回读 URL ${request.url} 不在动作 API 真源中`)
  }
  const expectedRequest = receipt.expectedRequest
  if (!expectedRequest || !String(expectedRequest.urlTemplate || '').trim() || !String(expectedRequest.method || '').trim()) {
    throw new Error('权威回读必须绑定 ACTION_EXPECT_JSON.request.urlTemplate/method')
  }
  if (!declared.includes(normalizeUrl(expectedRequest.urlTemplate))
      || !endpointMatches(expectedRequest.urlTemplate, request.url)) {
    throw new Error('权威回读实际 URL 未命中本次 fixture 声明的 urlTemplate')
  }
  if (String(request.method).toUpperCase() !== String(expectedRequest.method).toUpperCase()) {
    throw new Error('权威回读 method 与本次 fixture 声明不一致')
  }
  const contract = (targets.apiContracts || []).find((item) => normalizeUrl(item.url) === normalizeUrl(expectedRequest.urlTemplate))
  if (!contract || !contract.method) throw new Error('权威回读 urlTemplate 缺少仓库静态 method 合同')
  if (contract && contract.method && String(request.method).toUpperCase() !== String(contract.method).toUpperCase()) {
    throw new Error(`权威回读 method ${request.method} 与动作合同 ${contract.method} 不一致`)
  }
  if (/^(?:anonymous|none|public)$/i.test(String(request.authMode))) {
    throw new Error('权威回读不得用 anonymous/public 身份替代受控账号权限')
  }
  const authority = receipt.authority
  if (!authority.endpoint || authority.resourceId !== request.resourceId
      || authority.before === undefined || authority.after === undefined) {
    throw new Error('权威回读 authority 必须含匹配 resourceId 的 endpoint/before/after')
  }
  const changed = JSON.stringify(authority.before) !== JSON.stringify(authority.after)
  const accessScope = receipt.accessScope
  if (!fixtureAttestation || !accessScope
      || accessScope.principalFingerprint !== fixtureAttestation.identityFingerprint
      || accessScope.role !== fixtureAttestation.accountRole
      || !String(accessScope.ownerFingerprint || '').trim()
      || !String(accessScope.scope || '').trim() || accessScope.scopeVerified !== true
      || !['allowed', 'denied'].includes(accessScope.decision)) {
    throw new Error('权威回读权限事实未绑定 fixture principal/role/owner/scope')
  }
  if (branch === 'forbidden-owner' && (accessScope.decision !== 'denied'
      || accessScope.ownerFingerprint === accessScope.principalFingerprint)) {
    throw new Error('forbidden-owner 必须证明跨 owner 且权限决策 denied')
  }
  if (branch === 'forbidden-owner' && changed) {
    throw new Error('forbidden-owner 不得产生服务端副作用')
  }
  if (branch === 'forbidden-capability' && (accessScope.decision !== 'denied' || changed
      || accessScope.capability !== 'canPublish' || accessScope.capabilityGranted !== false)) {
    throw new Error('forbidden-capability 必须证明 canPublish=false、denied 且无服务端副作用')
  }
  if (branch !== 'forbidden-owner' && branch !== 'forbidden-capability' && accessScope.decision !== 'allowed') {
    throw new Error(`${branch} 权限事实必须是 allowed`)
  }
  if (branch === 'not-called') {
    if (request.observed !== false || changed) {
      throw new Error('not-called 阶段必须证明请求未发出且权威资源未变化')
    }
    return receipt
  }
  if (request.observed === false) throw new Error(`${branch} 阶段不得把未发出的请求冒充已执行`)
  if (action.accessBoundary === 'self' && accessScope.ownerFingerprint !== accessScope.principalFingerprint) {
    throw new Error('self 动作必须证明 owner 与受控 principal 一致')
  }
  if (kind === 'write' && branch === 'success' && !changed) {
    throw new Error('写操作 success 的服务端权威状态没有变化')
  }
  if (kind === 'write' && (branch === 'error' || branch === 'modal-cancelled') && changed) {
    throw new Error(`写操作 ${branch} 分支产生了不应有的服务端副作用`)
  }
  if (kind === 'write' && branch === 'timeout'
      && !['unchanged', 'committed'].includes(receipt.timeoutResolution)) {
    throw new Error('写操作 timeout 必须轮询到 unchanged/committed 确定终态')
  }
  if (kind === 'read' && (!receipt.mapping || receipt.mapping.matched !== true
      || !Array.isArray(receipt.mapping.paths) || receipt.mapping.paths.length === 0)) {
    throw new Error('读操作必须证明服务端响应映射到声明的 page.data 路径')
  }
  return receipt
}

function validateAuthorityEvidence(action, evidence, kind, branch, fixtureAttestation) {
  const branchRequests = action.requiredRequestsByBranch && action.requiredRequestsByBranch[branch]
  const required = Array.isArray(branchRequests) ? branchRequests
    : (Array.isArray(action.requiredRequests) ? action.requiredRequests : [])
  const outcomes = action.requiredRequestOutcomes && action.requiredRequestOutcomes[branch]
  if (required.length <= 1) {
    const outcome = Array.isArray(outcomes) && outcomes.length === 1 ? outcomes[0] : branch
    return validateAuthorityReceipt(action, evidence, kind, outcome, fixtureAttestation)
  }
  if (!Array.isArray(outcomes) || outcomes.length === 0 || outcomes.length > required.length) {
    throw new Error(`复合写操作缺少 ${branch} 的阶段结果合同`)
  }
  if (!evidence || !Array.isArray(evidence.sequence) || evidence.sequence.length !== outcomes.length) {
    throw new Error(`复合写操作 ${branch} 必须回读 ${outcomes.length} 个有序 authority receipts`)
  }
  evidence.sequence.forEach((receipt, index) => {
    const expected = required[index]
    if (!receipt.expectedRequest || receipt.expectedRequest.urlTemplate !== expected.urlTemplate
        || String(receipt.expectedRequest.method).toUpperCase() !== String(expected.method).toUpperCase()) {
      throw new Error(`复合写操作第 ${index + 1} 个 request 顺序或合同不一致`)
    }
    validateAuthorityReceipt(action, receipt, expected.kind || kind, outcomes[index], fixtureAttestation)
  })
  if (action.requestMode === 'parallel') {
    const trace = evidence.requestTrace
    if (!Array.isArray(trace) || trace.length !== outcomes.length) {
      throw new Error(`并发复合写 ${branch} 必须回读 ${outcomes.length} 条受控请求 trace`)
    }
    const indexes = new Set()
    const requestIds = new Set()
    const intervals = trace.map((entry) => {
      if (!entry || !Number.isInteger(entry.requestIndex) || entry.requestIndex < 0
          || entry.requestIndex >= outcomes.length || indexes.has(entry.requestIndex)
          || !String(entry.requestId || '').trim() || requestIds.has(String(entry.requestId))) {
        throw new Error('并发请求 trace 必须含唯一 requestIndex/requestId')
      }
      indexes.add(entry.requestIndex)
      requestIds.add(String(entry.requestId))
      const expected = required[entry.requestIndex]
      if (!endpointMatches(expected.urlTemplate, entry.url)
          || String(entry.method || '').toUpperCase() !== String(expected.method).toUpperCase()) {
        throw new Error(`并发请求 trace 第 ${entry.requestIndex + 1} 条未命中静态 endpoint/method 合同`)
      }
      const startedAt = Date.parse(entry.startedAt)
      const completedAt = Date.parse(entry.completedAt)
      if (!Number.isFinite(startedAt) || !Number.isFinite(completedAt) || completedAt <= startedAt) {
        throw new Error('并发请求 trace 必须含有效 startedAt/completedAt 时间区间')
      }
      return { startedAt, completedAt }
    })
    if (Math.max(...intervals.map((item) => item.startedAt))
        >= Math.min(...intervals.map((item) => item.completedAt))) {
      throw new Error('并发复合写没有证明请求时间区间真实并发重叠')
    }
  }
  const authorityLinks = action.requiredAuthorityLinksByBranch && action.requiredAuthorityLinksByBranch[branch]
    || action.requiredAuthorityLinks || []
  authorityLinks.forEach((link) => {
    if (link.fromIndex >= outcomes.length || link.toIndex >= outcomes.length) return
    const from = readObjectPath(evidence.sequence[link.fromIndex], link.fromPath)
    const to = readObjectPath(evidence.sequence[link.toIndex], link.toPath)
    if (from == null || from === '' || String(from) !== String(to)) {
      throw new Error(`复合写操作链路 ${link.fromPath} -> ${link.toPath} 未绑定同一业务资源`)
    }
  })
  return evidence
}

function validateBranchReceipt(action, branch, receipt, fixture, verifiedCapture) {
  if (!receipt || receipt.actionId !== action.id || receipt.branch !== branch) {
    throw new Error('分支 driver 回执未绑定当前 actionId/branch')
  }
  if (receipt.fixtureId !== fixture.id || !Array.isArray(receipt.assertions) || receipt.assertions.length === 0) {
    throw new Error('分支 driver 回执缺少受控 fixture/assertions')
  }
  if (!receipt.observed || typeof receipt.observed !== 'object') throw new Error('分支 driver 回执缺少 observed 事实')
  const observed = receipt.observed
  if (roamRevocation.isCaller(action) && !roamRevocation.isContract(action)) {
    throw new Error('UNVERIFIED roam REVOKE: 旧合同不能跳过实际用途回读')
  }
  if (roamRevocation.isContract(action)) roamRevocation.validate(action, branch, receipt, verifiedCapture)
  if (branch === 'success' && action.actionClass === 'external' && !roamRevocation.isContract(action)) {
    const capabilities = observed.capabilities
    if (!capabilities || typeof capabilities !== 'object') throw new Error('external success 必须逐能力回读 capabilities')
    ;(action.successCapabilities || action.externalCapabilities || []).forEach((rawName) => {
      const name = String(rawName).replace(/^app\./, '')
      const fact = capabilities[name]
      if (!fact || fact.outcome !== 'success') throw new Error(`external success 缺少 ${name} 成功事实`)
      if (/Location|Poi/.test(name) && (!Number.isFinite(Number(fact.latitude)) || !Number.isFinite(Number(fact.longitude)))) {
        throw new Error(`${name} success 必须回读 latitude/longitude`)
      }
      if (/choose(?:Image|Media|MessageFile|Document)/.test(name) && !(Number(fact.count) > 0)) {
        throw new Error(`${name} success 必须回读选择结果 count`)
      }
      if (!/Location|Poi|choose(?:Image|Media|MessageFile|Document)/.test(name) && fact.ok !== true) {
        throw new Error(`${name} success 必须回读 ok=true`)
      }
    })
  }
  if (/(?:^|-)error(?:-|$)/.test(branch) && !String(observed.errorCode || observed.errorClass || '').trim()) {
    throw new Error('error 分支必须回读 errorCode/errorClass')
  }
  if (/(?:^|-)timeout(?:-|$)/.test(branch) && (observed.timedOut !== true || observed.aborted !== true
      || !(Number(observed.elapsedMs) > 0))) {
    throw new Error('timeout 分支必须回读 elapsedMs/timedOut=true/aborted=true')
  }
  if (branch === 'duplicate-trigger') {
    const required = action.requiredRequestsByBranch && action.requiredRequestsByBranch[branch]
      || action.requiredRequests || []
    if (required.length > 1) {
      if (observed.triggerCount !== 2 || !Array.isArray(observed.effects)
          || observed.effects.length !== required.length) {
        throw new Error('复合 duplicate-trigger 必须逐 endpoint 回读 effects')
      }
      required.forEach((request, index) => {
        const effect = observed.effects[index]
        if (!effect || effect.urlTemplate !== request.urlTemplate
            || effect.triggerCount !== 2 || effect.sideEffectCount !== 1) {
          throw new Error(`复合 duplicate-trigger 第 ${index + 1} 个 endpoint 必须证明触发 2 次、权威副作用恰好 1 次`)
        }
      })
    } else if (observed.triggerCount !== 2 || !(Number(observed.sideEffectCount) <= 1)) {
      throw new Error('duplicate-trigger 必须证明触发 2 次且权威副作用至多 1 次')
    }
  }
  if (/(?:^|-)cancelled$/.test(branch)) {
    if (observed.outcome !== 'cancelled') throw new Error('cancelled 分支必须回读 cancelled')
    const expectedCapability = action.requiredCapabilityByBranch && action.requiredCapabilityByBranch[branch]
    if (expectedCapability && observed.capability !== expectedCapability) {
      throw new Error(`${branch} 必须回读 ${expectedCapability} 的真实取消事实`)
    }
  }
  if (/(?:^|-)denied$/.test(branch) && (observed.outcome !== 'denied' || !String(observed.recovery || '').trim())) {
    throw new Error('denied 分支必须回读 denied 与恢复路径')
  }
  if (/(?:^|-)denied$/.test(branch)) {
    const expectedCapability = action.requiredCapabilityByBranch && action.requiredCapabilityByBranch[branch]
    if (expectedCapability && observed.capability !== expectedCapability) {
      throw new Error(`${branch} 必须回读 ${expectedCapability} 的真实拒权事实`)
    }
  }
  if (branch === 'forbidden-owner' && (observed.outcome !== 'forbidden'
      || observed.statusCode !== 403 || observed.sideEffectCount !== 0
      || !String(observed.targetOwnerFingerprint || '').trim()
      || observed.targetOwnerFingerprint === observed.principalFingerprint)) {
    throw new Error('forbidden-owner 必须证明跨 owner、403 且副作用为 0')
  }
  if (branch === 'forbidden-capability' && (observed.outcome !== 'forbidden'
      || observed.transportStatus !== 200 || observed.businessCode !== 500 || observed.sideEffectCount !== 0
      || observed.capability !== 'canPublish' || observed.capabilityGranted !== false)) {
    throw new Error('forbidden-capability 必须证明 canPublish=false、当前 Controller 业务拒绝码且副作用为 0')
  }
  ;(action.requiredReadbackLinksByBranch && action.requiredReadbackLinksByBranch[branch] || []).forEach((link) => {
    const from = readObjectPath(receipt, link.fromPath)
    const to = readObjectPath(receipt, link.toPath)
    if (from == null || from === '' || String(from) !== String(to)) {
      throw new Error(`${branch} 事实链 ${link.fromPath} -> ${link.toPath} 未绑定同一值`)
    }
  })
  return receipt
}

function fragmentDirectory(projectPath, actionId) {
  const actionKey = crypto.createHash('sha256').update(actionId).digest('hex').slice(0, 24)
  return path.join(projectPath, 'scripts/uiaudit/evidence/fragments', actionKey)
}

function sha256File(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
}

function crc32(buffer) {
  let crc = 0xffffffff
  for (const byte of buffer) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0)
  }
  return (crc ^ 0xffffffff) >>> 0
}

function isValidPng(content) {
  if (!Buffer.isBuffer(content) || content.length < 57
      || !content.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) return false
  let offset = 8
  let seenIhdr = false
  let seenIend = false
  const idat = []
  while (offset < content.length) {
    if (offset + 12 > content.length) return false
    const length = content.readUInt32BE(offset)
    const end = offset + 12 + length
    if (end > content.length) return false
    const type = content.subarray(offset + 4, offset + 8)
    const data = content.subarray(offset + 8, offset + 8 + length)
    if (content.readUInt32BE(offset + 8 + length) !== crc32(Buffer.concat([type, data]))) return false
    const name = type.toString('ascii')
    if (!seenIhdr) {
      if (name !== 'IHDR' || length !== 13 || data.readUInt32BE(0) === 0 || data.readUInt32BE(4) === 0) return false
      seenIhdr = true
    }
    if (name === 'IDAT') idat.push(data)
    if (name === 'IEND') {
      if (length !== 0 || end !== content.length) return false
      seenIend = true
      break
    }
    offset = end
  }
  if (!seenIhdr || !seenIend || idat.length === 0) return false
  try { return zlib.inflateSync(Buffer.concat(idat)).length > 0 } catch (_) { return false }
}

function assertPngFile(file, message) {
  const content = fs.readFileSync(file)
  if (!isValidPng(content)) throw new Error(message)
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(',')}}`
  }
  return JSON.stringify(value)
}

function provenanceGeneration(provenance) {
  return crypto.createHash('sha256').update(stableJson(provenance || {})).digest('hex')
}

function staleProvenance(message) {
  const error = new Error(message)
  error.code = 'STALE_PROVENANCE'
  return error
}

function validateFragment(action, fragment, projectPath) {
  if (!fragment || fragment.schemaVersion !== 1 || fragment.actionId !== action.id
      || !action.requiredBranches.includes(fragment.branch)) throw new Error(`${action.id}: fragment schema/action/branch 无效`)
  const fixture = validateFixture(fragment.fixture)
  validateActionLocator(action, fixture)
  if (fragment.fixtureDigest !== fixtureDigest(fixture)) throw new Error(`${action.id}: fragment fixtureDigest 与完整 fixture 不一致`)
  if (!/^[0-9a-f]{40}$/i.test(String(fragment.sourceSha || ''))
      || !/^[0-9a-f]{64}$/i.test(String(fragment.sourceDigest || ''))) throw new Error(`${action.id}: fragment source 绑定无效`)
  const provenance = fragment.provenance || {}
  if (!fragment.generation) throw staleProvenance(`${action.id}: fragment 缺少 provenance generation`)
  if (fragment.generation !== provenanceGeneration(provenance)) {
    throw new Error(`${action.id}: fragment generation 与 provenance 内容不一致`)
  }
  const runnerUri = 'tests/automator/action-evidence-runner.js'
  const runnerFile = path.join(projectPath, runnerUri)
  const expectedTransport = action.interactionMode === 'tap'
    ? ((/^cy-/.test(String(action.control && action.control.tag || ''))
      || String(action.route || '').startsWith('component:'))
      ? 'miniprogram-automator custom-component dispatchEvent' : 'miniprogram-automator Element.tap')
    : 'event-contract-driver'
  if (provenance.runner !== runnerUri || provenance.transport !== expectedTransport
      || !fs.existsSync(runnerFile) || provenance.runnerSha256 !== sha256File(runnerFile)) {
    throw staleProvenance(`${action.id}: fragment 缺少当前 runner 的可回读 provenance`)
  }
  const validateTool = (field, prefix, required) => {
    const uri = String(provenance[field] || '')
    const file = path.resolve(projectPath, uri)
    if (!required && !uri) return
    if (!uri.startsWith(prefix) || !file.startsWith(`${projectPath}${path.sep}`) || !fs.existsSync(file)
        || provenance[`${field}Sha256`] !== sha256File(file)) {
      throw staleProvenance(`${action.id}: fragment 缺少当前 ${field} provenance`)
    }
  }
  validateTool('fixtureDriver', 'tests/automator/fixtures/', true)
  validateTool('branchDriver', 'tests/automator/branches/', fragment.branch !== 'success'
    && !(action.confirmationMode === 'wx.showModal' && fragment.branch === 'modal-cancelled'))
  const kind = authorityKindFor(action)
  validateTool('readbackAdapter', 'tests/automator/readbacks/', Boolean(kind))
  if (action.interactionMode === 'event-contract') {
    const eventDriver = String(provenance.eventDriver || '')
    const driverFile = path.resolve(projectPath, eventDriver)
    if (!eventDriver.startsWith('tests/automator/events/') || !driverFile.startsWith(`${projectPath}${path.sep}`)
        || !fs.existsSync(driverFile) || provenance.eventDriverSha256 !== sha256File(driverFile)) {
      throw staleProvenance(`${action.id}: event fragment 缺少当前 event driver provenance`)
    }
    const receipts = fragment.readback && fragment.readback.eventReceipts
    if (!Array.isArray(receipts) || receipts.length === 0
        || receipts.some((item) => item.actionId !== action.id || item.event !== action.control.event
          || item.fixtureId !== fixture.id || !Array.isArray(item.assertions) || item.assertions.length === 0)) {
      throw new Error(`${action.id}: event fragment 缺少绑定 action/event/fixture 的回执`)
    }
  }
  if (action.confirmationMode === 'wx.showModal') {
    const receipts = fragment.readback && fragment.readback.modalReceipts
    const expectedDecision = fragment.branch === 'modal-cancelled' ? 'cancel' : 'confirm'
    const expectedCount = fragment.branch === 'duplicate-trigger' ? 2 : 1
    if (!Array.isArray(receipts) || receipts.length !== expectedCount
        || receipts.some((item) => item.actionId !== action.id || item.branch !== fragment.branch
          || item.fixtureId !== fixture.id || item.kind !== 'wx.showModal' || item.decision !== expectedDecision
          || item.nativeObserved !== true || !(Number(item.attempts) >= 1))) {
      throw new Error(`${action.id}: modal fragment 缺少绑定 action/branch/fixture 的 ${expectedDecision} 回执`)
    }
  }
  if (!Array.isArray(fragment.artifacts) || fragment.artifacts.length < 2) throw new Error(`${action.id}: fragment 缺少 before/after 截图`)
  fragment.artifacts.forEach((artifact) => {
    const uri = String(artifact && artifact.uri || '')
    const file = path.resolve(projectPath, uri)
    if (!uri.startsWith('scripts/uiaudit/evidence/artifacts/') || !/\.png$/i.test(uri)
        || !file.startsWith(`${projectPath}${path.sep}`) || !fs.existsSync(file)
        || sha256File(file) !== String(artifact.sha256 || '').toLowerCase()) {
      throw new Error(`${action.id}: fragment artifact 必须是 runner 生成且 hash 可回读的 before/after PNG`)
    }
    assertPngFile(file, `${action.id}: fragment artifact 不是可解析的 PNG`)
    const token = crypto.createHash('sha256').update([
      action.id, fragment.branch, fragment.fixtureDigest, fragment.sourceSha,
    ].join('\0')).digest('hex').slice(0, 24)
    if (!path.basename(uri).startsWith(`${token}-${fragment.branch}-`)) {
      throw new Error(`${action.id}: fragment artifact 文件名未绑定 action/branch/fixture/source`)
    }
  })
  if (!fragment.fixtureAttestation || fragment.fixtureAttestation.fixtureId !== fixture.id
      || fragment.fixtureAttestation.synthetic !== true || !String(fragment.fixtureAttestation.identityFingerprint || '').trim()
      || !/^[0-9a-f]{40}$/i.test(String(fragment.fixtureAttestation.backendDeploymentSha || ''))) {
    throw new Error(`${action.id}: fragment 缺少 fixture driver attestation`)
  }
  if (!fragment.readback || typeof fragment.readback !== 'object') throw new Error(`${action.id}: fragment 缺少结构化 readback`)
  if (fragment.branch !== 'success') {
    validateBranchReceipt(action, fragment.branch, fragment.readback, fixture)
    if (!(fragment.readback.uiEffect && fragment.readback.uiEffect.assertions
        && fragment.readback.uiEffect.assertions.failure)) {
      throw new Error(`${action.id}: ${fragment.branch} fragment 缺少精确 UI 恢复终态`)
    }
  }
  if (kind) {
    validateAuthorityEvidence(action, fragment.readback.authoritativeReadback, kind, fragment.branch, fragment.fixtureAttestation)
  }
  return fragment
}

function writeFragment(projectPath, fragment) {
  const directory = fragmentDirectory(projectPath, fragment.actionId)
  fs.mkdirSync(directory, { recursive: true })
  const identity = crypto.createHash('sha256').update([
    fragment.actionId, fragment.branch, fragment.sourceSha, fragment.sourceDigest,
    fragment.fixtureDigest, fragment.fixtureAttestation && fragment.fixtureAttestation.identityFingerprint,
    fragment.generation,
  ].join('\0')).digest('hex').slice(0, 20)
  const file = path.join(directory, `${fragment.branch}-${identity}.json`)
  const temporary = path.join(directory, `.${fragment.branch}-${identity}-${process.pid}-${crypto.randomBytes(4).toString('hex')}.tmp`)
  const safe = redactEvidence(fragment)
  assertNoSensitiveEvidence(safe)
  let fd
  try {
    fd = fs.openSync(temporary, 'wx', 0o600)
  } catch (error) {
    throw error
  }
  try {
    fs.writeFileSync(fd, `${JSON.stringify(safe, null, 2)}\n`)
    fs.fsyncSync(fd)
  } finally {
    fs.closeSync(fd)
  }
  try {
    fs.linkSync(temporary, file)
  } catch (error) {
    if (error.code === 'EEXIST') throw new Error(`分支片段已存在，拒绝静默覆盖: ${path.relative(projectPath, file)}`)
    throw error
  } finally {
    try { fs.unlinkSync(temporary) } catch (error) { if (error.code !== 'ENOENT') throw error }
  }
  const dirFd = fs.openSync(directory, 'r')
  try { fs.fsyncSync(dirFd) } finally { fs.closeSync(dirFd) }
  return path.relative(projectPath, file)
}

function listFragments(projectPath) {
  const root = path.join(projectPath, 'scripts/uiaudit/evidence/fragments')
  if (!fs.existsSync(root)) return []
  return fs.readdirSync(root, { withFileTypes: true }).filter((entry) => entry.isDirectory())
    .flatMap((entry) => fs.readdirSync(path.join(root, entry.name)).filter((name) => name.endsWith('.json'))
      .map((name) => path.join(root, entry.name, name)))
}

function aggregateActionEvidence(action, fragments, options) {
  const selected = (fragments || []).filter((item) => item.actionId === action.id)
  if (!selected.length) return null
  if (!(options && options.skipFileValidation)) selected.forEach((item) => validateFragment(action, item, options.projectPath))
  const sourceKeys = new Set(selected.map((item) => `${item.sourceSha}:${item.sourceDigest}`))
  if (sourceKeys.size !== 1) throw new Error(`${action.id}: 分支证据 sourceSha/sourceDigest 不一致`)
  const fixtureIds = new Set(selected.map((item) => item.fixture && item.fixture.id))
  if (fixtureIds.size !== 1 || fixtureIds.has(undefined)) throw new Error(`${action.id}: 分支证据 fixture 不一致`)
  const fixtureDigests = new Set(selected.map((item) => item.fixtureDigest))
  if (fixtureDigests.size !== 1 || fixtureDigests.has(undefined)) throw new Error(`${action.id}: 分支证据完整 fixture 不一致`)
  const identities = new Set(selected.map((item) => item.fixtureAttestation && item.fixtureAttestation.identityFingerprint))
  if (identities.size !== 1 || identities.has(undefined)) throw new Error(`${action.id}: 分支证据受控账号身份不一致`)
  const backendDeployments = new Set(selected.map((item) => item.fixtureAttestation
    && item.fixtureAttestation.backendDeploymentSha))
  if (backendDeployments.size !== 1 || backendDeployments.has(undefined)) {
    throw new Error(`${action.id}: 分支证据后端部署代次不一致`)
  }
  if (selected.some((item) => !item.fixtureAttestation
      || item.fixtureAttestation.fixtureId !== item.fixture.id
      || !String(item.fixtureAttestation.identityFingerprint || '').trim())) {
    throw new Error(`${action.id}: 分支证据缺少受控账号回读 attestation`)
  }
  const byBranch = new Map()
  selected.sort((a, b) => String(a.verifiedAt).localeCompare(String(b.verifiedAt))).forEach((item) => {
    if (byBranch.has(item.branch)) throw new Error(`${action.id}: 分支 ${item.branch} 存在重复片段，需人工裁决`)
    byBranch.set(item.branch, item)
  })
  const missing = action.requiredBranches.filter((branch) => !byBranch.has(branch))
  if (missing.length) return { complete: false, missing }
  const observations = action.requiredBranches.map((branch) => byBranch.get(branch))
  const artifacts = observations.flatMap((item) => item.artifacts || [])
  return {
    complete: true,
    evidence: {
      status: 'passed',
      sourceSha: observations[0].sourceSha,
      sourceDigest: observations[0].sourceDigest,
      verifiedAt: observations.map((item) => item.verifiedAt).sort().at(-1),
      branches: action.requiredBranches.slice(),
      artifacts,
      fixture: observations[0].fixture,
      fixtureAttestations: observations.map((item) => item.fixtureAttestation),
      observations: observations.map((item) => ({
        branch: item.branch,
        verifiedAt: item.verifiedAt,
        readback: item.readback,
        expectation: item.expectation,
        artifacts: item.artifacts,
        provenance: item.provenance,
        generation: item.generation,
        fixtureAttestation: item.fixtureAttestation,
      })),
      readback: JSON.stringify(observations.map((item) => ({ branch: item.branch, readback: item.readback }))),
    },
  }
}

function processAlive(pid) {
  try { process.kill(pid, 0); return true } catch (error) { return error.code !== 'ESRCH' }
}

function acquireLeaseLock(lockFile) {
  const lease = { pid: process.pid, host: os.hostname(), createdAt: new Date().toISOString() }
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      const fd = fs.openSync(lockFile, 'wx', 0o600)
      fs.writeFileSync(fd, `${JSON.stringify(lease)}\n`)
      fs.fsyncSync(fd)
      return fd
    } catch (error) {
      if (error.code !== 'EEXIST') throw error
      let existing
      try { existing = JSON.parse(fs.readFileSync(lockFile, 'utf8')) } catch (readError) {
        throw new Error('action evidence 汇总锁损坏；拒绝自动删除，需人工核验')
      }
      const ageMs = Date.now() - Date.parse(existing.createdAt)
      const safelyStale = existing.host === os.hostname() && Number.isInteger(existing.pid)
        && ageMs >= 60_000 && !processAlive(existing.pid)
      if (!safelyStale || attempt > 0) throw new Error('action evidence 汇总锁已被占用，拒绝并发覆盖')
      const stale = `${lockFile}.stale-${existing.pid}-${Date.now()}`
      fs.renameSync(lockFile, stale)
      fs.unlinkSync(stale)
    }
  }
  throw new Error('无法获取 action evidence 汇总锁')
}

function mergeFragments(projectPath, ledger, validateOverlay, sourceDigestResolver) {
  const overlayFile = path.join(projectPath, 'scripts/uiaudit/action-evidence.json')
  const lockFile = `${overlayFile}.lock`
  const lock = acquireLeaseLock(lockFile)
  try {
    const fragments = listFragments(projectPath).map((file) => JSON.parse(fs.readFileSync(file, 'utf8')))
    const previous = JSON.parse(fs.readFileSync(overlayFile, 'utf8'))
    const controls = new Map(ledger.controls.map((action) => [action.id, action]))
    const preserved = Object.fromEntries(Object.entries(previous.results || {}).filter(([id, evidence]) => {
      const action = controls.get(id)
      return action && (action.actionClass === 'external' || evidence.status !== 'passed')
    }))
    const overlay = { schemaVersion: 1, note: previous.note, results: preserved }
    ledger.controls.forEach((action) => {
      const expectedSourceDigest = sourceDigestResolver ? sourceDigestResolver(action) : null
      const usable = fragments.filter((fragment) => fragment.actionId === action.id
        && (!expectedSourceDigest || fragment.sourceDigest === expectedSourceDigest)).filter((fragment) => {
        try {
          validateFragment(action, fragment, projectPath)
          return true
        } catch (error) {
          if (error.code === 'STALE_PROVENANCE') return false
          throw error
        }
      })
      const aggregate = aggregateActionEvidence(action, usable, { skipFileValidation: true })
      if (aggregate && aggregate.complete) overlay.results[action.id] = aggregate.evidence
    })
    const errors = validateOverlay(ledger, overlay)
    if (errors.length) throw new Error(`拒绝汇总 action-evidence.json: ${errors.join('; ')}`)
    const temp = `${overlayFile}.${process.pid}.${crypto.randomBytes(4).toString('hex')}.tmp`
    const fd = fs.openSync(temp, 'wx', 0o600)
    try {
      fs.writeFileSync(fd, `${JSON.stringify(overlay, null, 2)}\n`)
      fs.fsyncSync(fd)
    } finally {
      fs.closeSync(fd)
    }
    fs.renameSync(temp, overlayFile)
    const dirFd = fs.openSync(path.dirname(overlayFile), 'r')
    try { fs.fsyncSync(dirFd) } finally { fs.closeSync(dirFd) }
    return overlay
  } finally {
    if (lock != null) fs.closeSync(lock)
    try { fs.unlinkSync(lockFile) } catch (error) { if (error.code !== 'ENOENT') throw error }
  }
}

module.exports = {
  aggregateActionEvidence,
  acquireLeaseLock,
  actionLocatorFingerprint,
  authorityKindFor,
  assertNoSensitiveEvidence,
  mergeFragments,
  parseObjectEnv,
  provenanceGeneration,
  fixtureDigest,
  isValidPng,
  redactEvidence,
  validateAuthorityReceipt,
  validateAuthorityEvidence,
  validateActionLocator,
  validateBranchReceipt,
  validateFixture,
  validateFragment,
  writeFragment,
}
