#!/usr/bin/env node
'use strict'

const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const { execFileSync } = require('child_process')
const {
  assertNoSensitiveEvidence,
  fixtureDigest,
  isValidPng,
  provenanceGeneration,
  validateAuthorityReceipt,
  validateAuthorityEvidence,
  validateActionLocator,
  validateBranchReceipt,
  validateFixture,
} = require('./action-evidence-store')
const {
  stableJson, trustedControlPlane, verifyAsymmetricEnvelope, verifySignedEnvelope,
} = require('./trusted-attestation')
const {
  ROOT,
  rel,
  buildJsToWxmlMap,
  findEventBindings,
  classifyBinding,
  scanTags,
  extractOwnHandlerEntries,
  maskNonCode,
  maskJavaScriptComments,
  matchBrace,
  resolveRequireFile,
  parseJavaScript,
} = require('../lib/xcx-scan')
const { readAppRoutes } = require('./build-route-matrix')
const { CONTRACT: ROAM_REVOKE_CONTRACT } = require('./roam-revocation-evidence')

const OUTPUT = path.join(__dirname, 'action-ledger.json')
const EVIDENCE_OVERLAY = path.join(__dirname, 'action-evidence.json')
const ROUTE_MATRIX = path.join(__dirname, 'route-matrix.json')
const SERVER_CONTROLLER_ROOT = path.resolve(ROOT, '../chengyinhub-admin/src/main/java')

const EXTERNAL_METHODS = [
  'scanCode', 'getLocation', 'chooseLocation', 'choosePoi', 'chooseAddress', 'openLocation',
  'chooseImage', 'chooseMedia', 'chooseMessageFile', 'requestPayment', 'requestSubscribeMessage',
  'openSetting', 'authorize', 'openPrivacyContract', 'startLocationUpdate',
  'startLocationUpdateBackground', 'stopLocationUpdate', 'vibrateShort', 'createCameraContext',
  'login', 'previewImage', 'setClipboardData', 'createInnerAudioContext', 'showShareMenu',
  'setKeepScreenOn', 'getSetting', 'saveImageToPhotosAlbum', 'makePhoneCall', 'openCustomerServiceChat',
]
const EXTERNAL_METHOD_PATTERN = EXTERNAL_METHODS.join('|')
// [C] wx 的可注入别名。utils/location/location-manager.js 把 wx 注入成 `_wx` 以便单测替身,
//   于是它的 wx.chooseLocation 在源码里长成 `_wx.chooseLocation(` —— 只认 `wx.` 会漏判成非外部。
//   全仓只有这一个文件这么写,所以这里只列举这一个别名,不做「任意标识符 + .能力名」的泛化
//   (那会把 foo.getLocation() 这类同名方法误判成外部能力)。
const WX_RECEIVER_PATTERN = '(?:wx|_wx)'
const EXTERNAL_RE = new RegExp(`\\b${WX_RECEIVER_PATTERN}\\.(?:${EXTERNAL_METHOD_PATTERN})\\s*\\(`)
// app.sendRequest 内部可能用 wx.login 续期；它是基础设施，不得把所有业务请求传染成 external。
const APP_EXTERNAL_METHOD_PATTERN = EXTERNAL_METHODS.filter((name) => name !== 'login').join('|')
const APP_EXTERNAL_RE = new RegExp(`\\b${WX_RECEIVER_PATTERN}\\.(?:${APP_EXTERNAL_METHOD_PATTERN})\\s*\\(`)
const NAVIGATION_RE = /\bwx\.(?:navigateTo|redirectTo|switchTab|reLaunch|navigateBack)\s*\(/
const DURABLE_STATE_RE = /\b(?:this\.)?setData\s*\(|\bthis\.openScene\s*\(|\bwx\.(?:showModal|showActionSheet)\s*\(|\b(?:cy)?[mM]odal\.show\s*\(/
const NETWORK_RE = /\b(?:sendRequest|uploadFile|downloadFile)\s*\(/
const WRITE_RE = /(?:^|_)(?:(?:on|handle|continue))?(?:save|set|add|assign|revoke|create|delete|remove|update|edit|pay|submit|upload|verify|redeem|cancel|decline|refund|withdraw|publish|graduate|join|quit|kick|disband|bind|unbind|confirm|complete|collect|like|follow|report|apply|approve|reject|checkin|sign|dissolve|toggle|transfer|unlock|settle)(?=$|_|[A-Z])/i
const API_WRITE_RE = /\/(?:action|save|set-member-role|assign|revoke|create|delete|remove|update|edit|pay|submit|upload|verify|redeem|cancel|decline|refund|withdraw|publish|graduate|broadcast|join|quit|kick|disband|bind|unbind|confirm|complete|collect|like|favorite|follow|report|apply|approve|reject|checkin|sign|signup|dissolve|design|unlock(?:-chapter)?|settle|photo|answer|ending|reveal|transfer-to-club|update_publish_status|updateLibraryStatus)(?:\/|$)/i
const APP_WRITE_HELPERS = new Set(['recordConsent', 'chooseImage', 'chooseDocument'])
// 这些真实写口的名称不在通用动词表中；不能因此把所有 POST 都当写入。
// /api/im/block 落库拉黑关系，onDangerConfirm 名字含 confirm 但被 Danger 隔断，需显式登记。
// 商家 CRM/售后四个模板端点都是后端 @PostMapping 的落库写（ApiMerchantCrmController:160/170/185、
// ApiMerchantAftercareController:respond），路径里没有通用写动词，只能按真实合同逐个登记；
// 同前缀的 /aftercare/detail、/customers/{param}/detail 是查询读，绝不登记，避免写读串味。
const EXPLICIT_WRITE_APIS = new Set([
  '/api/template/draft', '/api/template/adopt-draft', '/api/game/session/command', '/api/im/block',
  '/api/im/unblock', '/api/im/read',
  '/api/merchant/aftercare/respond',
  '/api/merchant/crm/customers/{param}/notes',
  '/api/merchant/crm/customers/{param}/notes/hide',
  '/api/merchant/crm/customers/{param}/tags',
  // 俱乐部承接方改运营详情(ApiClubLeadController @PostMapping("/edit-ops"),落库写 cms_activity);
  // 路径里的 `edit-ops` 被连字符截断,匹配不到通用写动词,只能按真实合同登记。
  '/api/club/lead/edit-ops',
  '/api/club/lead/arrive',
  // 本人发票抬头按原请求号幂等保存(ApiUserInvoiceController @PostMapping("/titles/save-request"),落库写);
  // `save-request` 被连字符截断匹配不到通用写动词，按真实合同登记。同前缀 /titles/request 是原请求回读，不登记。
  '/api/invoice/titles/save-request',
])
// 精确读例外:路径段命中通用写动词、但后端只读的端点。只按完整路径逐个登记,不放宽 API_WRITE_RE。
// /api/publish/home(ApiPublishController#home)是发布广场首页聚合(能力 + 额度 + 项目摘要),只查不写;
// 同前缀的真实发布写口(/api/activity/publish、/api/coupon/publish、/api/template/publish 等)仍判写。
const EXPLICIT_READ_APIS = new Set([
  '/api/publish/home',
  // POST 回读原核销事实，不执行核销或发券；只登记这两个完整路径。
  '/api/verify/seat/receipt',
  '/api/verify/citynode/receipt',
])
const isWriteApi = (api) => !EXPLICIT_READ_APIS.has(api) && (API_WRITE_RE.test(api) || EXPLICIT_WRITE_APIS.has(api))
const TAP_EVENTS = new Set(['bindtap', 'catchtap', 'bind:tap', 'catch:tap'])
// App session/feature bootstrap; exact endpoints only, never a prefix exemption for business requests.
const AUXILIARY_APIS = new Set(['/api/login/code', '/api/config/features', '/api/role/info', '/api/analytics/events/batch'])
const SELF_ONLY_WRITE_APIS = new Set([
  '/api/compliance/consents', '/api/user/update', '/api/logout', '/api/user/deregister/apply',
  '/api/user/deregister/cancel', '/api/sms/send', '/api/registration/create',
  '/api/registration/cancel', '/api/team/create', '/api/team/quit',
])
const SELF_ONLY_WRITE_API_RE = /\/(?:favorite|follow|unfollow|like|unlike)(?:\/|$)/i
const CAPABILITY_WRITE_APIS = new Set(['/api/official/publish', '/api/official/broadcast'])
const RECOVERY_ONLY_EXTERNAL_CAPABILITIES = new Set([
  'openSetting', 'authorize', 'openPrivacyContract', 'requestSubscribeMessage',
])
// Only privacy_policy / roam_location / AGREE reads the server revision. A timeout
// retry reuses its captured CAS baseline; REVOKE and other consent purposes do not.
function roamAgreeRequestPlan() {
  const latest = { urlTemplate: '/api/compliance/consents/latest', method: 'POST', kind: 'read' }
  const record = { urlTemplate: '/api/compliance/consents', method: 'POST', kind: 'write' }
  return {
    requiredRequests: [latest, record],
    requiredRequestsByBranch: {
      'baseline-retry': [record], 'baseline-retry-error': [record], 'baseline-retry-timeout': [record],
      'duplicate-trigger': [record],
    },
    requiredRequestOutcomes: {
      success: ['success', 'success'], error: ['error'], timeout: ['timeout'],
      'record-error-after-latest': ['success', 'error'], 'record-timeout-after-latest': ['success', 'timeout'],
      'baseline-retry': ['success'], 'baseline-retry-error': ['error'], 'baseline-retry-timeout': ['timeout'],
      'duplicate-trigger': ['duplicate-trigger'],
    },
  }
}
function twoStepWritePlan(first, second, forbiddenIndex = 0) {
  return {
    requiredRequests: [
      { urlTemplate: first, method: 'POST', kind: 'write' },
      { urlTemplate: second, method: 'POST', kind: 'write' },
    ],
    requiredBranches: [
      'success', 'first-error', 'first-timeout', 'second-error-after-first',
      'second-timeout-after-first', 'duplicate-trigger', 'forbidden-owner',
    ],
    requiredRequestOutcomes: {
      success: ['success', 'success'],
      'first-error': ['error'],
      'first-timeout': ['timeout'],
      'second-error-after-first': ['success', 'error'],
      'second-timeout-after-first': ['success', 'timeout'],
      'duplicate-trigger': ['duplicate-trigger', 'duplicate-trigger'],
      'forbidden-owner': forbiddenIndex === 1 ? ['success', 'forbidden-owner'] : ['forbidden-owner'],
    },
  }
}

function photoCompletionPlan() {
  const upload = { urlTemplate: '/api/common/uploadOSS', method: 'POST', kind: 'write' }
  const complete = { urlTemplate: '/api/city/nodes/{param}/complete', method: 'POST', kind: 'write' }
  const plan = twoStepWritePlan(upload.urlTemplate, complete.urlTemplate, 1)
  return {
    ...plan,
    requiredBranches: plan.requiredBranches.concat(['cancelled', 'chooseImage-denied', 'getLocation-denied']),
    requiredRequestsByBranch: {
      cancelled: [upload, complete],
      'chooseImage-denied': [upload, complete],
      'getLocation-denied': [upload, complete],
    },
    requiredRequestOutcomes: {
      ...plan.requiredRequestOutcomes,
      cancelled: ['not-called', 'not-called'],
      'chooseImage-denied': ['not-called', 'not-called'],
      'getLocation-denied': ['success', 'not-called'],
    },
    requiredAuthorityLinksByBranch: Object.fromEntries([
      'success', 'second-error-after-first', 'second-timeout-after-first',
      'duplicate-trigger', 'forbidden-owner',
    ].map((branch) => [branch, [{
      fromIndex: 0, fromPath: 'authority.after.url', toIndex: 1, toPath: 'request.body.photoUrl',
    }]])),
    requiredReadbackLinksByBranch: {
      'getLocation-denied': [{
        fromPath: 'authoritativeReadback.sequence.0.authority.after.url',
        toPath: 'observed.pendingCompletionPhotoUrl',
      }],
    },
  }
}
function checkoutPlan(team, paid, consentReady, quoteReady) {
  const consent = { urlTemplate: '/api/compliance/consents', method: 'POST', kind: 'write' }
  const create = { urlTemplate: '/api/registration/create', method: 'POST', kind: 'write' }
  const quote = { urlTemplate: '/api/registration/quote', method: 'POST', kind: 'read' }
  const verify = { urlTemplate: '/api/registration/info', method: 'POST', kind: 'read' }
  const createTeam = { urlTemplate: '/api/team/create', method: 'POST', kind: 'write' }
  const cancel = { urlTemplate: '/api/registration/cancel', method: 'POST', kind: 'write' }
  const stages = []
  if (!consentReady) stages.push({ name: 'consent', request: consent })
  if (paid && !quoteReady) stages.push({ name: 'quote', request: quote })
  stages.push({ name: 'create', request: create })
  if (paid) stages.push({ name: 'verify', request: verify })
  if (team) stages.push({ name: 'team', request: createTeam })
  const success = stages.map((stage) => stage.request)
  const branches = ['success', 'subscription-cancelled', 'duplicate-trigger']
  const outcomes = {
    success: success.map(() => 'success'),
    'duplicate-trigger': success.map(() => 'duplicate-trigger'),
    'subscription-cancelled': success.map(() => 'success'),
  }
  const byBranch = {}
  stages.forEach((stage, index) => {
    if (stage.name === 'quote') {
      const createIndex = stages.findIndex((item) => item.name === 'create')
      const requestPlan = stages.slice(0, createIndex + 1).map((item) => item.request)
      ;['error', 'timeout'].forEach((failure) => {
        const branch = `quote-${failure}-then-create-error`
        branches.push(branch)
        byBranch[branch] = requestPlan
        outcomes[branch] = requestPlan.map((request, requestIndex) => {
          if (requestIndex < index) return 'success'
          return requestIndex === index ? failure : 'error'
        })
      })
      return
    }
    ;['error', 'timeout'].forEach((failure) => {
      const branch = `${stage.name}-${failure}`
      branches.push(branch)
      byBranch[branch] = stages.slice(0, index + 1).map((item) => item.request)
      outcomes[branch] = stages.slice(0, index).map(() => 'success').concat(failure)
    })
  })
  if (paid) {
    const createIndex = stages.findIndex((item) => item.name === 'create')
    const paymentFailurePlan = stages.slice(0, createIndex + 1).map((item) => item.request).concat(cancel)
    branches.push('payment-error', 'payment-cancelled')
    outcomes['payment-error'] = paymentFailurePlan.map(() => 'success')
    outcomes['payment-cancelled'] = paymentFailurePlan.map(() => 'success')
    byBranch['payment-error'] = paymentFailurePlan
    byBranch['payment-cancelled'] = paymentFailurePlan
  }
  return {
    requiredRequests: success,
    requiredRequestsByBranch: byBranch,
    requiredBranches: branches,
    requiredRequestOutcomes: outcomes,
    requiredCapabilityByBranch: {
      'subscription-cancelled': 'requestSubscribeMessage',
      ...(paid ? { 'payment-cancelled': 'requestPayment' } : {}),
    },
  }
}

function checkoutVariants(idPrefix, team, paid, predicate, apis, externalCapabilities) {
  const readiness = paid
    ? [[false, false], [false, true], [true, false], [true, true]]
    : [[false, true], [true, true]]
  return readiness.map(([consentReady, quoteReady]) => ({
    id: `${idPrefix}-${consentReady ? 'consent-ready' : 'consent-pending'}${paid ? `-${quoteReady ? 'quote-ready' : 'quote-pending'}` : ''}`,
    handler: 'handlePayment',
    events: ['bindtap'],
    predicate: {
      ...predicate,
      hostShareConsentReady: consentReady,
      ...(paid ? { quoteReady } : {}),
    },
    apis,
    externalCapabilities,
    ...checkoutPlan(team, paid, consentReady, quoteReady),
  }))
}
// 提现提交:单独同意先落库,再建提现单(2026-08-25)。
// 银行账号是个保法 §28 的敏感个人信息,§29 要求单独同意;前端在同一次点击里串行发两个写请求,
// 所以这个控件是两段式的,必须显式声明,否则会被「多写入口未声明」那条断言判红。
function withdrawSubmitVariant(handler) {
  const consent = { urlTemplate: '/api/compliance/consents', method: 'POST' }
  const create = { urlTemplate: '/api/withdrawal/create', method: 'POST' }
  return {
    id: null,
    handler,
    apis: ['/api/compliance/consents', '/api/withdrawal/create'],
    requiredRequests: [consent, create],
    requiredBranches: [
      'success', 'consent-error', 'consent-timeout',
      'create-error-after-consent', 'create-timeout-after-consent',
      'duplicate-trigger', 'forbidden-capability',
    ],
    requiredRequestOutcomes: {
      success: ['success', 'success'],
      'consent-error': ['error'],
      'consent-timeout': ['timeout'],
      // 同意已落库、建单失败:这两条分支存在的意义就是证明「同意存证不会被回滚」
      'create-error-after-consent': ['success', 'error'],
      'create-timeout-after-consent': ['success', 'timeout'],
      'duplicate-trigger': ['duplicate-trigger', 'duplicate-trigger'],
      'forbidden-capability': ['forbidden-capability'],
    },
  }
}

const ACTION_VARIANTS = new Map([
  // 清本机失败不联网；成功后只作废本人分享，不发布或读取快照。重复确认会再次幂等作废。
  ['pages/roam/index.js:confirmClearRoamLocal', [{
    id: null, handler: 'confirmClearRoamLocal',
    apis: ['/api/roam/share/revoke'], writeApis: ['/api/roam/share/revoke'],
    effectClasses: ['write', 'state'], externalCapabilities: [],
    requiredRequests: [{ urlTemplate: '/api/roam/share/revoke', method: 'POST', kind: 'write' }],
    requiredBranches: ['success', 'error', 'timeout', 'clear-local-failed', 'duplicate-trigger'],
    requiredRequestsByBranch: {
      'clear-local-failed': [],
      'duplicate-trigger': [
        { urlTemplate: '/api/roam/share/revoke', method: 'POST', kind: 'write' },
        { urlTemplate: '/api/roam/share/revoke', method: 'POST', kind: 'write' },
      ],
    },
    requiredRequestOutcomes: {
      success: ['success'], error: ['error'], timeout: ['timeout'],
      'clear-local-failed': [], 'duplicate-trigger': ['success', 'success'],
    },
  }]],
  // 集合签到的主事务是本次GPS→arrive；team-progress是独立权威回读。
  // 生命周期/随后展示刷新不是再次签到，不能把整页所有玩法SDK都要求为本次签到成功。
  ['pages/play/index.js:leadArrive', [{
    id: null, handler: 'leadArrive',
    apis: ['/api/club/lead/arrive', '/api/club/lead/team-progress'],
    writeApis: ['/api/club/lead/arrive'], effectClasses: ['write', 'read', 'state'],
    externalCapabilities: ['getLocation'],
    requiredRequests: [
      { urlTemplate: '/api/club/lead/arrive', method: 'POST', kind: 'write' },
      { urlTemplate: '/api/club/lead/team-progress', method: 'GET', kind: 'read' },
    ],
    requiredBranches: ['success', 'error', 'timeout', 'progress-error-after-arrive', 'progress-timeout-after-arrive',
      'duplicate-trigger', 'forbidden-owner', 'getLocation-denied', 'modal-cancelled'],
    requiredRequestsByBranch: Object.fromEntries(['duplicate-trigger', 'forbidden-owner', 'getLocation-denied', 'modal-cancelled']
      .map(branch => [branch, [{ urlTemplate: '/api/club/lead/arrive', method: 'POST', kind: 'write' }]])),
    requiredRequestOutcomes: {
      success: ['success', 'success'], error: ['error'], timeout: ['timeout'],
      'progress-error-after-arrive': ['success', 'error'], 'progress-timeout-after-arrive': ['success', 'timeout'],
      'duplicate-trigger': ['duplicate-trigger'], 'forbidden-owner': ['forbidden-owner'],
      'getLocation-denied': ['not-called'], 'modal-cancelled': ['not-called'],
    },
  }]],
  // 身份失效会清除金额，但这三个场景入口仍须验成功与拒绝分支。
  ['components/cy/scene-asset-earnings/index.js:openWithdraw', [
    { id: null, handler: 'openWithdraw', requiredBranches: ['success', 'error'] },
  ]],
  ['components/cy/scene-asset-earnings/index.js:openWithdrawHistory', [
    { id: null, handler: 'openWithdrawHistory', requiredBranches: ['success', 'error'] },
  ]],
  ['components/cy/scene-asset-earnings/index.js:openInvite', [
    { id: null, handler: 'openInvite', requiredBranches: ['success', 'error'] },
  ]],
  ...['closePlayerOperations', 'openPlayerOperation', 'closePlayerOperation', 'choosePlayerOperationTarget', 'inputPlayerOperationTime', 'inputPlayerOperationReason', 'cancelPlayerExit'].map(handler => [`pages/play/index.js:${handler}`, [{ id: 'player-operation-local', handler, apis: [], writeApis: [], effectClasses: ['state'], externalCapabilities: [] }]]),
  ['pages/play/index.js:openPlayerOperations', [{ id: 'player-operation-view', handler: 'openPlayerOperations', apis: ['/api/game/session/view'], writeApis: [], effectClasses: ['read', 'state'], externalCapabilities: [] }]],
  ['pages/play/index.js:submitPlayerOperation', [{
    id: 'player-operation-command', handler: 'submitPlayerOperation', apis: ['/api/game/session/command', '/api/game/session/receipt', '/api/game/session/view'], writeApis: ['/api/game/session/command'], effectClasses: ['write', 'state'], externalCapabilities: [],
    requiredRequestsByBranch: { 'selection-or-disabled': [], 'exit-confirmation-only': [], 'arrival-issue': [{ urlTemplate: '/api/game/session/command', method: 'POST', kind: 'write' }], 'manual-sign': [{ urlTemplate: '/api/game/session/command', method: 'POST', kind: 'write' }] },
  }]],
  ['pages/play/index.js:confirmPlayerExit', [{ id: 'player-team-exit', handler: 'confirmPlayerExit', apis: ['/api/game/session/command', '/api/game/session/receipt', '/api/game/session/view'], writeApis: ['/api/game/session/command'], effectClasses: ['write', 'state'], externalCapabilities: [], requiredRequestsByBranch: { 'cancel-or-stale': [], 'confirmed-exit': [{ urlTemplate: '/api/game/session/command', method: 'POST', kind: 'write' }] } }]],
  ['pages/play/index.js:checkPlayerOperationReceipt', [{ id: 'player-operation-receipt', handler: 'checkPlayerOperationReceipt', apis: ['/api/game/session/receipt', '/api/game/session/view'], writeApis: [], effectClasses: ['read', 'state'], externalCapabilities: [] }]],
  ['pages/club/topic-detail/index.js:confirmGenericOps', [
    'ACT_UNLOCK', 'ARRIVAL_CONFIRM', 'ARRIVAL_REVOKE', 'RUN_PAUSE', 'RUN_RESUME', 'RUN_INTERRUPT', 'RUN_CANCEL',
    'MANUAL_BEGIN', 'STAFF_MANUAL_SIGN', 'MANUAL_IMPORT', 'MANUAL_REVIEW', 'AUTHORITY_RESUME', 'QUEST_GUIDE', 'LOCATION_REBIND', 'STAFF_TEAM_EXIT',
  ].map(action => ({ id: action.toLowerCase().replace(/_/g, '-'), handler: 'confirmGenericOps', predicate: { genericOpsAction: action },
    apis: ['/api/game/session/command', '/api/game/session/receipt', '/api/game/session/view', '/api/game/session/shared/control'],
    writeApis: ['/api/game/session/command'], effectClasses: ['write', 'read', 'state'], externalCapabilities: [],
    requiredRequests: [{ urlTemplate: '/api/game/session/command', method: 'POST', kind: 'write' }],
  }))],
  ['pages/club/topic-detail/index.js:openGenericOps', [{ id: 'offline-start', handler: 'openGenericOps', predicate: { action: 'OFFLINE_ANCHOR' },
    apis: ['/api/game/session/shared/export/anchor', '/api/game/session/shared/control', '/api/game/session/view'],
    writeApis: ['/api/game/session/shared/export/anchor'], effectClasses: ['write', 'read', 'state'], externalCapabilities: [],
    requiredRequests: [{ urlTemplate: '/api/game/session/shared/export/anchor', method: 'POST', kind: 'write' }] },
    { id: 'ops-form', handler: 'openGenericOps',
    apis: ['/api/game/session/shared/export'], writeApis: [], effectClasses: ['read', 'state'], externalCapabilities: [] }]],
  ['pages/club/topic-detail/index.js:closeGenericOps', [{ id: 'ops-close', handler: 'closeGenericOps', apis: [], writeApis: [], effectClasses: ['state'], externalCapabilities: [] }]],
  ['pages/club/topic-detail/index.js:inputGenericOps', [{ id: 'ops-fields', handler: 'inputGenericOps', apis: [], writeApis: [], effectClasses: ['state'], externalCapabilities: [] }]],
  ['pages/play/index.js:openThemePlan', [{ id: 'plan-local-state', handler: 'openThemePlan', apis: [], writeApis: [], effectClasses: ['state'], externalCapabilities: [] }]],
  ['pages/play/index.js:closeThemePlan', [{ id: 'plan-local-state', handler: 'closeThemePlan', apis: [], writeApis: [], effectClasses: ['state'], externalCapabilities: [] }]],
  ['pages/play/index.js:editThemePlanFunding', [{ id: 'plan-local-state', handler: 'editThemePlanFunding', apis: [], writeApis: [], effectClasses: ['state'], externalCapabilities: [] }]],
  ['pages/play/index.js:toggleThemePlanAcceptance', [{ id: 'plan-local-state', handler: 'toggleThemePlanAcceptance', apis: [], writeApis: [], effectClasses: ['state'], externalCapabilities: [] }]],
  ['pages/play/index.js:inputThemePlanFunding', [{ id: 'plan-local-state', handler: 'inputThemePlanFunding', apis: [], writeApis: [], effectClasses: ['state'], externalCapabilities: [] }]],
  ['pages/play/index.js:cancelThemePlanFunding', [{ id: 'plan-local-state', handler: 'cancelThemePlanFunding', apis: [], writeApis: [], effectClasses: ['state'], externalCapabilities: [] }]],
  ['pages/play/index.js:openThemePlanOperation', [{ id: 'plan-local-state', handler: 'openThemePlanOperation', apis: [], writeApis: [], effectClasses: ['state'], externalCapabilities: [] }]],
  ['pages/club/topic-detail/index.js:openPlanFallback', [{ id: 'plan-local-state', handler: 'openPlanFallback', apis: [], writeApis: [], effectClasses: ['state'], externalCapabilities: [] }]],
  ['pages/club/topic-detail/index.js:closePlanFallback', [{ id: 'plan-local-state', handler: 'closePlanFallback', apis: [], writeApis: [], effectClasses: ['state'], externalCapabilities: [] }]],
  ['pages/club/topic-detail/index.js:inputPlanFallbackReason', [{ id: 'plan-local-state', handler: 'inputPlanFallbackReason', apis: [], writeApis: [], effectClasses: ['state'], externalCapabilities: [] }]],
  ['pages/play/index.js:saveThemePlanFunding', [{
    id: 'plan-command', handler: 'saveThemePlanFunding',
    apis: ['/api/game/session/command', '/api/game/session/receipt', '/api/game/session/view'], writeApis: ['/api/game/session/command'], effectClasses: ['write', 'state'], externalCapabilities: [],
    requiredRequests: [{ urlTemplate: '/api/game/session/command', method: 'POST', kind: 'write' }],
  }]],
  ['pages/play/index.js:onThemePlanCommand', [{
    id: 'plan-command', handler: 'onThemePlanCommand',
    apis: ['/api/game/session/command', '/api/game/session/receipt', '/api/game/session/view'], writeApis: ['/api/game/session/command'], effectClasses: ['write', 'state'], externalCapabilities: [],
    requiredRequests: [{ urlTemplate: '/api/game/session/command', method: 'POST', kind: 'write' }],
  }]],
  ['pages/play/index.js:rejectThemePlanVote', [{
    id: 'plan-command', handler: 'rejectThemePlanVote',
    apis: ['/api/game/session/command', '/api/game/session/receipt', '/api/game/session/view'], writeApis: ['/api/game/session/command'], effectClasses: ['write', 'state'], externalCapabilities: [],
    requiredRequests: [{ urlTemplate: '/api/game/session/command', method: 'POST', kind: 'write' }],
  }]],
  ['pages/play/index.js:submitThemePlanAcceptance', [{
    id: 'plan-command', handler: 'submitThemePlanAcceptance',
    apis: ['/api/game/session/command', '/api/game/session/receipt', '/api/game/session/view'], writeApis: ['/api/game/session/command'], effectClasses: ['write', 'state'], externalCapabilities: [],
    requiredRequests: [{ urlTemplate: '/api/game/session/command', method: 'POST', kind: 'write' }],
  }]],
  ['pages/play/index.js:checkThemePlanReceipt', [{
    id: 'plan-receipt-read', handler: 'checkThemePlanReceipt',
    apis: ['/api/game/session/receipt', '/api/game/session/view'], writeApis: [], effectClasses: ['read', 'state'], externalCapabilities: [],
  }]],
  ['pages/club/topic-detail/index.js:confirmPlanFallback', [{
    id: 'plan-fallback', handler: 'confirmPlanFallback',
    apis: ['/api/game/session/command', '/api/game/session/receipt', '/api/game/session/view', '/api/game/session/shared/control'], writeApis: ['/api/game/session/command'], effectClasses: ['write', 'state'], externalCapabilities: [],
    requiredRequests: [{ urlTemplate: '/api/game/session/command', method: 'POST', kind: 'write' }],
  }]],
  ['pages/play/index.js:selectThemeParticipant', [{
    id: 'participants-preview', handler: 'selectThemeParticipant',
    apis: ['/api/game/session/shared/preview'], writeApis: [], effectClasses: ['read', 'state'], externalCapabilities: [],
  }]],
  ...['pages/play/index.js', 'pages/topic/index/index.js'].map(file => [file + ':replayThemeSession', [{
    id: 'theme-replay', handler: 'replayThemeSession',
    requiredBranches: ['success', 'replay-denied', 'replay-unknown', 'replay-unknown-recovered', 'timer-clear-unknown', 'duplicate-trigger'],
    apis: ['/api/play/theme/state', '/api/play/theme/replay', '/api/play/run-session/clear', '/api/play/run-session'], writeApis: ['/api/play/theme/replay', '/api/play/run-session/clear'],
    effectClasses: ['read', 'write', 'state', 'navigation'], externalCapabilities: [],
    requiredRequestsByBranch: {
      success: [{ urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' }, { urlTemplate: '/api/play/theme/replay', method: 'POST', kind: 'write' }, { urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' }, { urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' }, { urlTemplate: '/api/play/run-session/clear', method: 'POST', kind: 'write' }],
      'replay-denied': [{ urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' }],
      'replay-unknown': [{ urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' }, { urlTemplate: '/api/play/theme/replay', method: 'POST', kind: 'write' }, { urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' }],
      'replay-unknown-recovered': [{ urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' }, { urlTemplate: '/api/play/theme/replay', method: 'POST', kind: 'write' }, { urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' }, { urlTemplate: '/api/play/run-session/clear', method: 'POST', kind: 'write' }],
      'timer-clear-unknown': [{ urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' }, { urlTemplate: '/api/play/theme/replay', method: 'POST', kind: 'write' }, { urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' }, { urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' }, { urlTemplate: '/api/play/run-session/clear', method: 'POST', kind: 'write' }, { urlTemplate: '/api/play/run-session', method: 'GET', kind: 'read' }],
      'duplicate-trigger': [],
    },
  }]]),
  ['pages/play/index.js:previewThemeTrade', [{
    id: 'trade-preview', handler: 'previewThemeTrade',
    apis: ['/api/game/session/shared/preview'], writeApis: [], effectClasses: ['read', 'state'], externalCapabilities: [],
  }]],
  ['pages/play/index.js:submitThemeTrade', [{
    id: 'trade-open', handler: 'submitThemeTrade',
    apis: ['/api/game/session/command', '/api/game/session/view'], writeApis: ['/api/game/session/command'], effectClasses: ['write', 'state'], externalCapabilities: [],
    requiredRequests: [{ urlTemplate: '/api/game/session/command', method: 'POST', kind: 'write' }],
  }]],
  ['pages/play/index.js:onThemeTradeAction', [{
    id: 'trade-confirm-or-cancel', handler: 'onThemeTradeAction',
    apis: ['/api/game/session/command', '/api/game/session/view'], writeApis: ['/api/game/session/command'], effectClasses: ['write', 'state'], externalCapabilities: [],
    requiredRequests: [{ urlTemplate: '/api/game/session/command', method: 'POST', kind: 'write' }],
  }]],
  ['pages/play/index.js:checkThemeTradeReceipt', [{
    id: 'trade-receipt-read', handler: 'checkThemeTradeReceipt',
    apis: ['/api/game/session/receipt', '/api/game/session/view'], writeApis: [], effectClasses: ['read', 'state'], externalCapabilities: [],
  }]],
  // 帖文第三轮:发现页帖文流混着俱乐部帖文与社区帖文,点赞按帖子来源只打其中一个接口(utils/post-feed-actions.toggleLike);
  // 关注只打 /api/user/follow/action(同一模块里的点赞接口不属于这次点击)。
  ['pages/talent/list/index.js:onLikePost', [
    { id: 'club-post', handler: 'onLikePost', predicate: { postSource: 'club' },
      apis: ['/api/club/post/like'], writeApis: ['/api/club/post/like'], effectClasses: ['write', 'state'], externalCapabilities: [],
      requiredRequests: [{ urlTemplate: '/api/club/post/like', method: 'POST', kind: 'write' }] },
    { id: 'community-post', handler: 'onLikePost', predicate: { postSource: 'community' },
      apis: ['/api/creativesquare/like'], writeApis: ['/api/creativesquare/like'], effectClasses: ['write', 'state'], externalCapabilities: [],
      requiredRequests: [{ urlTemplate: '/api/creativesquare/like', method: 'POST', kind: 'write' }] },
  ]],
  ['pages/talent/list/index.js:onFollowPost', [{ id: null, handler: 'onFollowPost',
    apis: ['/api/user/follow/action'], writeApis: ['/api/user/follow/action'], effectClasses: ['write', 'state'], externalCapabilities: [],
    requiredRequests: [{ urlTemplate: '/api/user/follow/action', method: 'POST', kind: 'write' }] }]],
  ['subpackageMember/components/cy/profile/index.js:onPrimaryCta', [
    {
      id: 'visitor-follow', handler: 'toggleFollow', predicate: { isSelf: false, subjectIsMerchant: false },
      predicateScope: 'component', componentHostSelector: '#profile',
    },
    {
      id: 'self-merchant-scan', handler: 'goScanQR', predicate: { isSelf: true, isMerchantView: true },
      predicateScope: 'component', componentHostSelector: '#profile',
    },
  ]],
  ['pages/activity/list/index.js:pubOnSubmit', [
    {
      id: 'activity-only', handler: 'pubOnSubmit', predicate: {
        'pub.mode': 'activity', 'pub.notify.player': false, 'pub.notify.club': false, 'pub.notify.merchant': false,
      }, apis: ['/api/official/publish'],
    },
    {
      id: 'activity-broadcast', handler: 'pubOnSubmit',
      predicate: { 'pub.mode': 'activity', 'pub.notify': { $someTrue: ['player', 'club', 'merchant'] } },
      apis: ['/api/official/publish', '/api/official/broadcast'],
      requiredRequests: [
        { urlTemplate: '/api/official/publish', method: 'POST' },
        { urlTemplate: '/api/official/broadcast', method: 'POST' },
      ],
      requiredBranches: [
        'success', 'publish-error', 'publish-timeout',
        'broadcast-error-after-publish', 'broadcast-timeout-after-publish',
        'duplicate-trigger', 'forbidden-capability',
      ],
      requiredRequestOutcomes: {
        success: ['success', 'success'],
        'publish-error': ['error'],
        'publish-timeout': ['timeout'],
        'broadcast-error-after-publish': ['success', 'error'],
        'broadcast-timeout-after-publish': ['success', 'timeout'],
        'duplicate-trigger': ['duplicate-trigger', 'duplicate-trigger'],
        'forbidden-capability': ['forbidden-capability'],
      },
      requiredAuthorityLinks: [{
        fromIndex: 0, fromPath: 'authority.after.id', toIndex: 1, toPath: 'request.parentResourceId',
      }],
    },
    {
      id: 'notification-broadcast', handler: 'pubOnSubmit',
      predicate: { 'pub.mode': 'notification', 'pub.notify': { $someTrue: ['player', 'club', 'merchant'] } },
      apis: ['/api/official/broadcast'],
    },
  ]],
  ['pages/activity/official-detail/index.js:onCta', [
    { id: 'signup', handler: 'doSignup', predicate: { 'cta.type': 'signup' }, apis: ['/api/official/events/{param}/signup'] },
    { id: 'explore', handler: 'doComplete', predicate: { 'cta.type': 'explore' }, apis: ['/api/official/events/{param}/complete'] },
    { id: 'roam', handler: 'goRoam', predicate: { 'cta.type': 'roam' }, apis: [], effectClasses: ['navigation', 'state'] },
    { id: 'theme', handler: 'onCta', predicate: { 'cta.type': 'theme' }, apis: [], effectClasses: ['state'] },
  ]],
  ['pages/activity/baoming/baoming.js:handlePayment', [
    { id: 'disabled-feedback', handler: 'handlePayment', events: ['bind:disabledtap'], apis: [], effectClasses: ['state'] },
    ...checkoutVariants('solo-free', false, false,
      { totalAmount: 0 },
      ['/api/compliance/consents', '/api/registration/create'], ['requestSubscribeMessage']),
    ...checkoutVariants('solo-paid', false, true,
      { totalAmount: { $gt: 0 } },
      ['/api/compliance/consents', '/api/registration/quote', '/api/registration/create', '/api/registration/info', '/api/registration/cancel'],
      ['requestPayment', 'requestSubscribeMessage']),
  ]],
  ['pages/activity/baoming/baoming.js:cancelWaitlist', [{
    id: null,
    handler: 'cancelWaitlist',
    apis: ['/api/club/event-ops/waitlist/cancel'],
    effectClasses: ['write', 'state'],
  }]],
  ['subpackageMember/tixian/tixian.js:saveData', [withdrawSubmitVariant('saveData')]],
  ['subpackageA/pages/assetcenter/earnings/index.js:onTxSave', [withdrawSubmitVariant('onTxSave')]],
  ['pages/roam/index.js:goStart', [{
    id: null, handler: 'goStart', apis: ['/api/compliance/consents', '/api/compliance/consents/latest'],
    effectClasses: ['write', 'state'], externalCapabilities: ['getLocation'],
  }]],
  ['pages/roam/index.js:goStartAndShoot', [{
    id: null, handler: 'goStartAndShoot', apis: ['/api/compliance/consents', '/api/compliance/consents/latest'],
    effectClasses: ['write', 'state'], externalCapabilities: ['getLocation', 'chooseMedia'],
  }]],
  ['pages/roam/index.js:onFogScreenLoad', [{
    id: null, handler: 'onFogScreenLoad', apis: [], effectClasses: ['state'], externalCapabilities: [],
  }]],
  ['pages/roam/index.js:toggleGps', [
    {
      id: 'start', handler: 'toggleGps', predicate: { gpsMode: false },
      apis: ['/api/compliance/consents', '/api/compliance/consents/latest'], effectClasses: ['write', 'state'],
      externalCapabilities: ['startLocationUpdateBackground', 'setKeepScreenOn'],
    },
    {
      id: 'stop', handler: 'toggleGps', predicate: { gpsMode: true },
      apis: [], effectClasses: ['state'], externalCapabilities: ['stopLocationUpdate', 'setKeepScreenOn'],
    },
  ]],
  ['pages/roam/index.js:verifyEventArrival', [
    { id: 'unsigned', handler: 'verifyEventArrival', predicate: { 'eventOverlay.signed': false }, apis: [], effectClasses: ['state'], externalCapabilities: [] },
    { id: 'paused', handler: 'verifyEventArrival', predicate: { 'eventOverlay.signed': true, 'eventOverlay.paused': true }, apis: [], effectClasses: ['state'], externalCapabilities: [] },
    { id: 'inactive', handler: 'verifyEventArrival', predicate: { 'eventOverlay.signed': true, 'eventOverlay.paused': false, 'eventOverlay.status': { $not: 3 } }, apis: [], effectClasses: ['state'], externalCapabilities: [] },
    {
      id: 'gps-off', handler: 'verifyEventArrival',
      predicate: { 'eventOverlay.signed': true, 'eventOverlay.paused': false, 'eventOverlay.status': 3, gpsMode: false },
      apis: ['/api/compliance/consents'], effectClasses: ['write', 'state'],
      externalCapabilities: ['startLocationUpdateBackground', 'setKeepScreenOn'],
    },
    {
      id: 'session-pending', handler: 'verifyEventArrival',
      predicate: { 'eventOverlay.signed': true, 'eventOverlay.paused': false, 'eventOverlay.status': 3, gpsMode: true, roamSessionReady: false },
      apis: [], effectClasses: ['state'], externalCapabilities: [],
    },
    {
      id: 'verify', handler: 'verifyEventArrival',
      predicate: { 'eventOverlay.signed': true, 'eventOverlay.paused': false, 'eventOverlay.status': 3, gpsMode: true, roamSessionReady: true },
      apis: ['/api/official/events/{param}/arrivals'], effectClasses: ['write', 'state'], externalCapabilities: ['getLocation'],
    },
  ]],
  ['pages/roam/index.js:onGoalTap', [
    {
      id: 'gps', handler: 'onGoalTap', predicate: { 'goal.act': 'gps' },
      apis: ['/api/compliance/consents'], effectClasses: ['write', 'state'],
      externalCapabilities: ['startLocationUpdateBackground', 'setKeepScreenOn'],
    },
    { id: 'nearby', handler: 'onGoalTap', predicate: { 'goal.act': 'nearby' }, apis: [], effectClasses: ['state'], externalCapabilities: [] },
    { id: 'retry-poi', handler: 'onGoalTap', predicate: { 'goal.act': 'retry-poi' }, apis: ['/api/roam/pois'], effectClasses: ['read', 'state'], externalCapabilities: [] },
    // 2026-09-17 拍板「漫游打卡不存在失败态」:goal.act='retry-checkin' 分支与 retryCheckin 已随
    // 失败浮卡一起删除(startVisit 距离闸把「打不了」挡在发请求之前),本台账的该动作一并注销。
    { id: 'discover', handler: 'onGoalTap', predicate: { 'goal.act': 'discover' }, apis: [], effectClasses: ['state'], externalCapabilities: [] },
    { id: 'event', handler: 'onGoalTap', predicate: { 'goal.act': 'event' }, apis: [], effectClasses: ['state'], externalCapabilities: [] },
  ]],
  ['subpackageMember/components/cy/scene-roam-poi-detail/index.js:startInteract', [
    {
      id: 'answer', handler: 'startInteract', predicate: { 'node.validationMethod': 1 },
      predicateScope: 'component', apis: ['/api/city/nodes/{param}/complete'],
      effectClasses: ['write', 'state'], externalCapabilities: ['getLocation'],
      confirmationMode: 'wx.showModal',
    },
    {
      id: 'photo', handler: 'startInteract', predicate: { 'node.validationMethod': 2 },
      predicateScope: 'component', apis: ['/api/common/uploadOSS', '/api/city/nodes/{param}/complete'],
      effectClasses: ['write', 'state'], externalCapabilities: ['app.chooseImage', 'getLocation'],
      confirmationMode: null,
      writeApis: ['/api/common/uploadOSS', '/api/city/nodes/{param}/complete'],
      ...photoCompletionPlan(),
    },
    {
      id: 'choice', handler: 'startInteract', predicate: { 'node.validationMethod': 3 },
      predicateScope: 'component', apis: ['/api/city/nodes/{param}/complete'],
      effectClasses: ['write', 'state'], externalCapabilities: ['showActionSheet', 'getLocation'],
      confirmationMode: null,
    },
    {
      id: 'scan', handler: 'startInteract', predicate: { 'node.validationMethod': 4 },
      predicateScope: 'component', apis: ['/api/city/nodes/{param}/complete'],
      effectClasses: ['write', 'state'], externalCapabilities: ['scanCode', 'getLocation'],
      confirmationMode: null,
    },
    {
      id: 'direct', handler: 'startInteract', predicate: { 'node.validationMethod': { $notIn: [1, 2, 3, 4] } },
      predicateScope: 'component', apis: ['/api/city/nodes/{param}/complete'],
      effectClasses: ['write', 'state'], externalCapabilities: ['getLocation'],
      confirmationMode: null,
    },
  ]],
  ['pages/club/join-requests/index.js:approve', [
    { id: null, handler: 'approve', apis: ['/api/club/join-request/approve'] },
  ]],
  ['pages/club/join-requests/index.js:reject', [
    { id: null, handler: 'reject', apis: ['/api/club/join-request/reject'] },
  ]],
  ['pages/club/join-requests/index.js:retryReview', [
    {
      id: 'approve', handler: 'retryReview', predicate: { actionErrorAction: 'approve' },
      apis: ['/api/club/join-request/approve'],
    },
    {
      id: 'reject', handler: 'retryReview', predicate: { actionErrorAction: 'reject' },
      apis: ['/api/club/join-request/reject'],
    },
  ]],
  ['components/cy/scene-route-content/index.js:submitForm', [
    // 2026-09-15 收款模型定稿 §3:平台不打款,member-withdraw 不再建提现单,只弹平台客服微信(纯 state)。
    { id: 'member-withdraw', handler: 'submitForm', predicate: { sceneId: 'member-withdraw' }, predicateScope: 'component', apis: [], effectClasses: ['state'] },
    { id: 'settings-profile', handler: 'submitForm', predicate: { sceneId: 'settings-profile' }, predicateScope: 'component', apis: ['/api/user/update'] },
    { id: 'settings-feedback', handler: 'submitForm', predicate: { sceneId: 'settings-feedback' }, predicateScope: 'component', apis: ['/api/coop/complaint/report'] },
    { id: 'merchant-decor', handler: 'submitForm', predicate: { sceneId: 'merchant-decor' }, predicateScope: 'component', apis: ['/api/merchant/update'] },
    { id: 'merchant-apply', handler: 'submitForm', predicate: { sceneId: 'merchant-apply' }, predicateScope: 'component', apis: ['/api/merchant/merchant_registration'] },
    { id: 'club-apply', handler: 'submitForm', predicate: { sceneId: 'club-apply' }, predicateScope: 'component', apis: ['/api/club/become-leader'] },
    { id: 'club-create', handler: 'submitForm', predicate: { sceneId: 'club-create' }, predicateScope: 'component', apis: ['/api/club/create'] },
    { id: 'merchant-citynode', handler: 'submitForm', predicate: { sceneId: 'merchant-citynode' }, predicateScope: 'component', apis: ['/api/merchant/city-node/template/submit'] },
  ]],
  ['pages/publish/temp/index.js:retrySubmit', [
    {
      id: 'draft', handler: 'retrySubmit', predicate: { lastSubmitAction: { $not: 'publish' } },
      apis: ['/api/template/draft'],
    },
    {
      id: 'publish-citynode', handler: 'retrySubmit', predicate: { lastSubmitAction: 'publish', from: 'citynode' },
      apis: ['/api/merchant/city-node/template/submit'],
    },
    {
      id: 'publish-template', handler: 'retrySubmit', predicate: { lastSubmitAction: 'publish', from: { $not: 'citynode' } },
      apis: ['/api/template/publish'],
    },
  ]],
  ['pages/publish/temp/index.js:saveDraft', [
    { id: 'draft', handler: 'saveDraft', predicate: { from: { $not: 'citynode' } }, apis: ['/api/template/draft'] },
    { id: 'citynode-unavailable', handler: 'saveDraft', predicate: { from: 'citynode' }, apis: [], effectClasses: ['other'], externalCapabilities: [] },
  ]],
  ['pages/merchant/reviews/index.js:submitReport', [
    {
      id: 'manage', handler: 'submitReport', predicate: { mode: 'manage' },
      apis: ['/api/merchant/reviews/manage/report'],
      requiredRequests: [{ urlTemplate: '/api/merchant/reviews/manage/report', method: 'POST', kind: 'write' }],
    },
    {
      id: 'public', handler: 'submitReport', predicate: { mode: { $not: 'manage' } },
      apis: ['/api/merchant/reviews/report'],
      requiredRequests: [{ urlTemplate: '/api/merchant/reviews/report', method: 'POST', kind: 'write' }],
    },
  ]],
  ['pages/merchant/team/index.js:confirmDanger', [
    {
      id: 'operator', handler: 'confirmDanger', predicate: { 'dangerConfirm.type': 'operator' },
      apis: ['/api/merchant/operators/remove'],
      requiredRequests: [{ urlTemplate: '/api/merchant/operators/remove', method: 'POST', kind: 'write' }],
    },
    {
      id: 'invite', handler: 'confirmDanger', predicate: { 'dangerConfirm.type': { $not: 'operator' } },
      apis: ['/api/merchant/operators/invite/revoke'],
      requiredRequests: [{ urlTemplate: '/api/merchant/operators/invite/revoke', method: 'POST', kind: 'write' }],
    },
  ]],
  ['pages/merchant/coop-center/index.js:onChapterNodeFormSubmit', [
    { id: null, handler: 'onChapterNodeFormSubmit', apis: [
      '/api/merchant/chapter-application/apply', '/api/merchant/chapter-node/submit',
    ], ...twoStepWritePlan('/api/merchant/chapter-application/apply', '/api/merchant/chapter-node/submit') },
  ]],
  ['pages/topic/merchantinfo/merchantinfo.js:onChapterNodeFormSubmit', [
    {
      id: 'application', handler: 'onChapterNodeFormSubmit', predicate: { chapterNodeFormMode: 'application' },
      apis: ['/api/merchant/chapter-application/apply', '/api/merchant/chapter-node/submit'],
      ...twoStepWritePlan('/api/merchant/chapter-application/apply', '/api/merchant/chapter-node/submit'),
    },
    {
      id: 'offer', handler: 'submitApprovedChapterOffer', predicate: { chapterNodeFormMode: 'offer' },
      apis: ['/api/coop/offer/enroll'],
    },
  ]],
  ['pages/merchant/decor/index.js:confirmField', [
    ...['name', 'derivatives'].map((key) => ({
      id: `brand-${key}`, handler: 'saveBrand', predicate: { 'fieldSheet.key': key },
      apis: ['/api/merchant/update'], effectClasses: ['write', 'state'],
    })),
    ...['cityRole', 'slogan'].map((key) => ({
      id: `decor-${key}`, handler: 'saveDecor', predicate: { 'fieldSheet.key': key },
      apis: ['/api/merchant/decor/save'], effectClasses: ['write', 'state'],
    })),
  ]],
  ['pages/merchant/decor/index.js:onGuideTap', [
    {
      id: 'upload-cover', handler: 'uploadCover', locatorInstance: { attribute: 'data-action', value: 'uploadCover' },
      apis: ['/api/common/uploadOSS', '/api/merchant/decor/save'],
      ...twoStepWritePlan('/api/common/uploadOSS', '/api/merchant/decor/save', 1),
    },
    {
      id: 'upload-logo', handler: 'uploadLogo', locatorInstance: { attribute: 'data-action', value: 'uploadLogo' },
      apis: ['/api/common/uploadOSS', '/api/merchant/update'],
      ...twoStepWritePlan('/api/common/uploadOSS', '/api/merchant/update', 1),
    },
    { id: 'category', handler: 'goCategory', locatorInstance: { attribute: 'data-action', value: 'goCategory' }, apis: [] },
    { id: 'location', handler: 'geoLocate', locatorInstance: { attribute: 'data-action', value: 'geoLocate' }, apis: ['/api/merchant/decor/save'] },
    { id: 'story', handler: 'goStory', locatorInstance: { attribute: 'data-action', value: 'goStory' }, apis: [] },
    { id: 'name', handler: 'openField', locatorInstance: { attribute: 'data-action', value: 'editName' }, apis: [], effectClasses: ['state'] },
    { id: 'slogan', handler: 'openField', locatorInstance: { attribute: 'data-action', value: 'editSlogan' }, apis: [], effectClasses: ['state'] },
  ]],
  // 门店信息胶囊半屏「保存」:经营类目(kind=preference)走 saveBrand,行业类型走 saveDecor,两条写入互斥,一次点击只发其一。
  ['pages/merchant/decor/index.js:saveChipSheet', [{
    id: null, handler: 'saveChipSheet',
    apis: ['/api/merchant/decor/save', '/api/merchant/update'],
    writeApis: ['/api/merchant/decor/save', '/api/merchant/update'],
    effectClasses: ['write', 'state'], externalCapabilities: [],
    requiredRequestsByBranch: {
      'busy-or-invalid': [],
      'preference': [{ urlTemplate: '/api/merchant/update', method: 'POST', kind: 'write' }],
      'industry': [{ urlTemplate: '/api/merchant/decor/save', method: 'POST', kind: 'write' }],
    },
  }]],
  // pages/merchant/decor/story 的 onSave 证据规格随 2026-09-19 审查 #30 撤掉:
  // 那一页没进 app.json、全仓零入口,已整页删除;再留一份「必须验到 8 个分支」的规格,
  // 台账会永远报缺证据。品牌故事的实际写入口在装修首页 goStory→openField(上面那条)。
  ['pages/topic/merchantapply/index.js:onSubmit', [
    {
      id: 'create', handler: 'onSubmit', predicate: { registrationId: 0 },
      apis: ['/api/registration/merchant/create'],
    },
    {
      id: 'edit', handler: 'onSubmit', predicate: { registrationId: { $not: 0 } },
      apis: ['/api/registration/merchant/update'],
    },
  ]],
  ['pages/topic/merchantapply/index.js:onCreateSubmit', [
    {
      id: 'create', handler: 'onSubmit', predicate: { registrationId: 0 },
      apis: ['/api/registration/merchant/create'],
    },
  ]],
  ['pages/topic/merchantapply/index.js:onEditSubmit', [
    {
      id: 'edit', handler: 'onSubmit', predicate: { registrationId: { $not: 0 } },
      apis: ['/api/registration/merchant/update'],
    },
  ]],
  ['pages/publish/fabu/index.js:submitForm', [
    { id: 'create-precheck', handler: 'runPublishPrecheck', predicate: { editingTopicId: '' }, apis: ['/api/ai/safety/precheck'] },
    { id: 'update-precheck', handler: 'runPublishPrecheck', predicate: { editingTopicId: { $not: '' } }, apis: ['/api/ai/safety/precheck'] },
  ]],
  // 2026-10-12 云端草稿:首次保存草稿 create(saveDraft=1),之后 update 同一份草稿;成功后都回读 edit-detail。
  ['pages/publish/fabu/index.js:saveServerDraft', [
    { id: 'create-draft', handler: 'saveServerDraft', predicate: { editingTopicId: '' }, apis: ['/api/topic/v2/create'] },
    { id: 'update-draft', handler: 'saveServerDraft', predicate: { editingTopicId: { $not: '' } }, apis: ['/api/topic/v2/update'] },
  ]],
  // W8 赛事联络/签到组件:主办侧(organizer)走 EVENT 原命令，参赛侧走 SIGNUP 原命令；终止未执行请求只发本侧一条 cancel。
  ['components/cy/event-attendance/index.js:cancelCommand', [
    { id: 'organizer', handler: 'cancelCommand', predicate: { organizer: true }, predicateScope: 'component', apis: ['/api/official/command/cancel'] },
    { id: 'participant', handler: 'cancelCommand', predicate: { organizer: { $not: true } }, predicateScope: 'component', apis: ['/api/official/signup-command/cancel'] },
  ]],
  // W8 分支条件映射完成 / 注册属性确认:点击只改本地规则并经 _onDraftEdited 排队云端草稿自动保存,
  // 唯一网络写入是延时后的 saveServerDraft({ automatic: true }),与上面 saveServerDraft 同一 v2 草稿写入口。
  ['pages/publish/fabu/index.js:finishNodeRouteMapping', [
    { id: 'autosave-create-draft', handler: 'finishNodeRouteMapping', predicate: { editingTopicId: '' }, apis: ['/api/topic/v2/create'] },
    { id: 'autosave-update-draft', handler: 'finishNodeRouteMapping', predicate: { editingTopicId: { $not: '' } }, apis: ['/api/topic/v2/update'] },
  ]],
  ['pages/publish/fabu/index.js:onAttributeBindingConfirm', [
    { id: 'autosave-create-draft', handler: 'onAttributeBindingConfirm', predicate: { editingTopicId: '' }, apis: ['/api/topic/v2/create'] },
    { id: 'autosave-update-draft', handler: 'onAttributeBindingConfirm', predicate: { editingTopicId: { $not: '' } }, apis: ['/api/topic/v2/update'] },
  ]],
  ['pages/publish/fabu/index.js:confirmPublishCheck', [
    { id: 'create', handler: 'confirmPublishCheck', predicate: { editingTopicId: '' }, apis: ['/api/topic/create'] },
    { id: 'update', handler: 'confirmPublishCheck', predicate: { editingTopicId: { $not: '' } }, apis: ['/api/topic/update'] },
  ]],
  ['pages/publish/temp/index.js:publishGame', [
    { id: 'city-node', handler: 'publishGame', predicate: { from: 'citynode' }, apis: ['/api/merchant/city-node/template/submit'] },
    { id: 'template', handler: 'publishGame', predicate: { from: { $not: 'citynode' } }, apis: ['/api/template/publish'] },
  ]],
  // 父 kitaction 真正委托 #advancedGame；照片同意仅 SUBMIT_PHOTO_CHECK 分支，其他动作不写 consent。
  ['pages/play/index.js:onPlayKitAction', [{
    handler: 'onPlayKitAction',
    apis: ['/api/play/advanced/action', '/api/play/advanced/state', '/api/common/uploadOSS',
      '/api/compliance/consents', '/api/game/session/command', '/api/play/theme/action',
      '/api/play/theme/state', '/api/game/session/view', '/api/play/theme/gameplay-action', '/api/play/journey/thought/sync', '/api/media/asset/{param}', '/api/media/asset/{param}/retry'],
    writeApis: ['/api/play/advanced/action', '/api/common/uploadOSS', '/api/compliance/consents',
      '/api/game/session/command', '/api/play/theme/action', '/api/play/theme/gameplay-action', '/api/play/journey/thought/sync', '/api/media/asset/{param}/retry'],
    requiredRequests: [{ urlTemplate: '/api/play/advanced/action', method: 'POST', kind: 'write' }],
    requiredRequestsByBranch: {
      'local-preview-or-invalid': [],
      'photo-consent-declined': [],
      'advanced-action': [{ urlTemplate: '/api/play/advanced/action', method: 'POST', kind: 'write' }],
      'terminal-explicit-retry': [{ urlTemplate: '/api/play/advanced/action', method: 'POST', kind: 'write' }],
      'advanced-refresh-only': [{ urlTemplate: '/api/play/advanced/state', method: 'GET', kind: 'read' }],
      'advanced-unknown-readback': [
        { urlTemplate: '/api/play/advanced/action', method: 'POST', kind: 'write' },
        { urlTemplate: '/api/play/advanced/state', method: 'GET', kind: 'read' },
      ],
      'photo-existing-consent': [
        { urlTemplate: '/api/common/uploadOSS', method: 'POST', kind: 'write' },
        { urlTemplate: '/api/play/advanced/action', method: 'POST', kind: 'write' },
      ],
      'photo-new-consent': [
        { urlTemplate: '/api/compliance/consents', method: 'POST', kind: 'write' },
        { urlTemplate: '/api/common/uploadOSS', method: 'POST', kind: 'write' },
        { urlTemplate: '/api/play/advanced/action', method: 'POST', kind: 'write' },
      ],
      'photo-upload-failed-or-review-pending': [{ urlTemplate: '/api/common/uploadOSS', method: 'POST', kind: 'write' }],
      'photo-question-changed-after-upload': [{ urlTemplate: '/api/common/uploadOSS', method: 'POST', kind: 'write' }],
      'profile-avatar-local-draft': [{ urlTemplate: '/api/common/uploadOSS', method: 'POST', kind: 'write' }],
      'media-existing-pending': [{ urlTemplate: '/api/media/asset/{param}', method: 'GET', kind: 'read' }],
      'media-rejected-retry': [
        { urlTemplate: '/api/media/asset/{param}', method: 'GET', kind: 'read' },
        { urlTemplate: '/api/media/asset/{param}/retry', method: 'POST', kind: 'write' },
      ],
      'album-submit-existing-photo': [{ urlTemplate: '/api/play/advanced/action', method: 'POST', kind: 'write' }],
      'team-zero-hp-already-due-after-view': [
        { urlTemplate: '/api/game/session/view', method: 'GET', kind: 'read' },
        { urlTemplate: '/api/game/session/command', method: 'POST', kind: 'write' },
      ],
      'legacy-personal-chapter-entry-after-state': [
        { urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' },
        { urlTemplate: '/api/play/theme/action', method: 'POST', kind: 'write' },
      ],
      'personal-zero-hp-already-due-after-state': [
        { urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' },
        { urlTemplate: '/api/play/theme/gameplay-action', method: 'POST', kind: 'write' },
      ],
      'personal-step-baseline-after-state': [
        { urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' },
        { urlTemplate: '/api/play/journey/thought/sync', method: 'POST', kind: 'write' },
      ],
    },
    requiredBranches: ['local-preview-or-invalid', 'photo-consent-declined', 'advanced-action', 'terminal-explicit-retry',
      'advanced-refresh-only', 'advanced-unknown-readback', 'photo-existing-consent', 'photo-new-consent',
      'photo-upload-failed-or-review-pending', 'photo-question-changed-after-upload', 'profile-avatar-local-draft',
      'media-existing-pending', 'media-rejected-retry', 'album-submit-existing-photo', 'team-zero-hp-already-due-after-view',
      'legacy-personal-chapter-entry-after-state', 'personal-zero-hp-already-due-after-state', 'personal-step-baseline-after-state',
      'error', 'timeout', 'duplicate-trigger', 'forbidden-owner'],
  }]],
  ['pages/play/index.js:retryGameSubmission', [
    { id: 'photo', handler: 'submitPhoto', predicate: { gRetryType: 'photo' }, apis: ['/api/play/photo'] },
    { id: 'checkin', handler: 'checkin', predicate: { gRetryType: 'gameCheckin' }, apis: ['/api/play/checkin'] },
    { id: 'scan', handler: 'scanGame', predicate: { gRetryType: 'gameScan' }, apis: ['/api/play/checkin'] },
    { id: 'reveal', handler: 'revealAnswer', predicate: { gRetryType: 'puzzleReveal' }, apis: ['/api/play/puzzle/reveal'] },
    { id: 'answer', handler: 'submitGame', predicate: { gRetryType: 'answer' }, apis: ['/api/play/answer'] },
  ]],
  ...[
    'onThemeBagAction', 'retryThemeBagAction', 'onThemeGameplayAction', 'onThemeCancelGameplayAction',
    'confirmThemeCostChoice', 'confirmThemeRewardPreview', 'confirmThemePendingClaim',
    'selectThemeQuestChoice', 'rereadThemeRewardPreview',
  ].map((handler) => [`pages/play/index.js:${handler}`, [{
    handler,
    // 个人/团队命令互斥；POST preview 是只读模拟，过期后重读不自动再次提交。
    requiredRequestsByBranch: {
      'selection-or-disabled': [],
      ...(handler === 'onThemeGameplayAction' ? {
        'basic-gps-proof': [{ urlTemplate: '/api/play/arrive', method: 'POST', kind: 'write' }],
        'basic-gps-with-thought-sync': [
          { urlTemplate: '/api/play/journey/thought/sync', method: 'POST', kind: 'write' },
          { urlTemplate: '/api/play/arrive', method: 'POST', kind: 'write' },
        ],
      } : {}),
      ...(handler === 'retryThemeBagAction' ? {
        'quest-readback-team': [{ urlTemplate: '/api/game/session/receipt', method: 'GET', kind: 'read' }],
        'quest-readback-personal': [{ urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' }],
      } : {}),
      'personal-item': [{ urlTemplate: '/api/play/theme/action', method: 'POST', kind: 'write' }],
      'personal-gameplay': [{ urlTemplate: '/api/play/theme/gameplay-action', method: 'POST', kind: 'write' }],
      'personal-preview': [{ urlTemplate: '/api/play/theme/gameplay-preview', method: 'POST', kind: 'read' }],
      'team-command': [{ urlTemplate: '/api/game/session/command', method: 'POST', kind: 'write' }],
      'team-preview': [{ urlTemplate: '/api/game/session/shared/preview', method: 'POST', kind: 'read' }],
      'personal-preview-stale': [
        { urlTemplate: '/api/play/theme/gameplay-action', method: 'POST', kind: 'write' },
        { urlTemplate: '/api/play/theme/state', method: 'GET', kind: 'read' },
        { urlTemplate: '/api/play/theme/gameplay-preview', method: 'POST', kind: 'read' },
      ],
      'team-preview-stale': [
        { urlTemplate: '/api/game/session/command', method: 'POST', kind: 'write' },
        { urlTemplate: '/api/game/session/view', method: 'GET', kind: 'read' },
        { urlTemplate: '/api/game/session/shared/preview', method: 'POST', kind: 'read' },
      ],
    },
    requiredBranches: ['success', 'error', 'timeout', 'duplicate-trigger', 'forbidden-owner',
      'selection-or-disabled', 'personal-item', 'personal-gameplay', 'personal-preview', 'team-command',
      'team-preview', 'personal-preview-stale', 'team-preview-stale', 'personal-gameplay-with-chapter-grant',
      ...(handler === 'onThemeGameplayAction' ? ['basic-gps-proof', 'basic-gps-with-thought-sync'] : []),
      ...(handler === 'retryThemeBagAction' ? ['quest-readback-team', 'quest-readback-personal'] : [])],
  }]]),
  ...[
    ['photoGame', '/api/play/photo', true],
    ['revealAnswer', '/api/play/puzzle/reveal', false],
    ['scanGame', '/api/play/checkin', true],
    ['submitGame', '/api/play/answer', false],
    ['onScanArriveFromTools', '/api/play/checkin', true],
    ['onScanArrive', '/api/play/checkin', true],
  ].map(([handler, primary, external]) => {
    const first = { urlTemplate: primary, method: 'POST', kind: 'write' }
    return [`pages/play/index.js:${handler}`, [{
      handler,
      requiredRequests: [first],
      requiredRequestsByBranch: {
        'preview-or-no-write': [],
        'personal-chapter-grant': [first, { urlTemplate: '/api/play/theme/action', method: 'POST', kind: 'write' }],
      },
      requiredBranches: [
        'success', 'error', 'timeout', 'duplicate-trigger', 'forbidden-owner',
        'preview-or-no-write', 'personal-chapter-grant',
        ...(external ? ['cancelled', 'denied'] : []),
      ],
    }]]
  }),
  ['pages/play/index.js:onRunToggleFromTools', [{
    handler: 'onRunToggleFromTools', writeApis: ['/api/play/run-session/save'],
    requiredRequests: [{ urlTemplate: '/api/play/run-session/save', method: 'POST', kind: 'write' }],
    requiredRequestsByBranch: { 'start-or-resume': [] },
    requiredBranches: ['success', 'error', 'timeout', 'duplicate-trigger', 'forbidden-owner', 'start-or-resume'],
  }]],
  ['pages/play/index.js:continueChapterStory', [{
    handler: 'continueChapterStory',
    requiredRequestsByBranch: {
      'opening-complete': [{ urlTemplate: '/api/play/journey/opening/complete', method: 'POST', kind: 'write' }],
      'personal-chapter-end': [{ urlTemplate: '/api/play/theme/action', method: 'POST', kind: 'write' }],
      'preview-or-not-ready': [],
    },
    requiredBranches: ['opening-complete', 'personal-chapter-end', 'preview-or-not-ready', 'error', 'timeout', 'duplicate-trigger', 'forbidden-owner'],
  }]],
  ...[
    ['leadBroadcastFromTools', '/api/club/lead/broadcast'],
    ['leadUnlockFromTools', '/api/club/lead/unlock-chapter'],
    ['leadSettleFromTools', '/api/club/lead/settle'],
  ].map(([handler, primary]) => {
    const first = { urlTemplate: primary, method: 'POST', kind: 'write' }
    return [`pages/play/index.js:${handler}`, [{
      handler,
      requiredRequests: [first],
      requiredRequestsByBranch: {
        'modal-cancelled': [],
      },
      requiredBranches: ['success', 'error', 'timeout', 'duplicate-trigger', 'forbidden-owner'],
    }]]
  }),
  ['subpackageA/pages/myproject/index.js:deleteItem', [
    { id: 'activity', handler: 'deleteItem', locatorInstance: { attribute: 'data-biz', value: 'activity' }, apis: ['/api/activity/delete'] },
    { id: 'topic', handler: 'deleteItem', locatorInstance: { attribute: 'data-biz', value: 'topic' }, apis: ['/api/topic/delete'] },
  ]],
  ['subpackageA/pages/myproject/index.js:cancelActivity', [
    { id: 'activity', handler: 'cancelActivity', apis: ['/api/activity/cancel'] },
  ]],
  ['pages/publish/activity/index.js:submitForm', [
    { id: 'create', handler: 'submitForm', predicate: { editingActivityId: 0 }, apis: ['/api/activity/publish'] },
    { id: 'update', handler: 'submitForm', predicate: { editingActivityId: { $not: 0 } }, apis: ['/api/activity/update'] },
  ]],
  ['subpackageB/pages/im/chat/index.js:onMoreSelect', [
    { id: 'report', handler: 'onMoreSelect', sourceHandler: 'doReport', predicate: { morePick: 'report' }, apis: ['/api/im/report'] },
    { id: 'block', handler: 'onMoreSelect', sourceHandler: 'doBlock', predicate: { morePick: 'block' }, apis: ['/api/im/conversations'], effectClasses: ['state'] },
    { id: 'unblock', handler: 'onMoreSelect', sourceHandler: 'doBlock',
      predicate: { morePick: 'unblock', blockedByMe: true },
      apis: ['/api/im/conversations', '/api/im/unblock', '/api/im/messages', '/api/im/read'],
      writeApis: ['/api/im/unblock', '/api/im/read'], effectClasses: ['read', 'write', 'state'],
      requiredRequests: [
        { urlTemplate: '/api/im/conversations', method: 'POST', kind: 'read' },
        { urlTemplate: '/api/im/unblock', method: 'POST', kind: 'write' },
        { urlTemplate: '/api/im/messages', method: 'POST', kind: 'read' },
        { urlTemplate: '/api/im/read', method: 'POST', kind: 'write' },
      ],
    },
    { id: 'clear', handler: 'onMoreSelect', sourceHandler: 'clearConversation', predicate: { morePick: 'clear' }, apis: [], effectClasses: ['state'] },
  ]],
  ['pages/topic/merchantinfo/merchantinfo.js:onDangerConfirm', [
    ['chapter-finish', 'doFinishChapter', '/api/topic/chapter/finish'],
    ['chapter-invitation-revoke', 'submitChapterInvitationRevoke', '/api/merchant/chapter-application/invite/revoke'],
    ['chapter-application-withdraw', 'submitChapterApplicationWithdraw', '/api/merchant/chapter-application/withdraw'],
    ['chapter-invitation-reject', 'respondChapterInvitation', '/api/merchant/chapter-application/invite/respond'],
  ].map(([id, sourceHandler, api]) => ({
    id, handler: 'onDangerConfirm', sourceHandler, predicate: null,
    apis: [api], writeApis: [api], effectClasses: ['write', 'state'],
    requiredRequests: [{ urlTemplate: api, method: 'POST', kind: 'write' }],
  }))],
  ['subpackageB/pages/im/chat/index.js:onDangerConfirm', [
    // 由确认组件的事件 key 分流，验收按对应单写请求回读，不把互斥动作算成两次写。
    { id: 'block', handler: 'onDangerConfirm', predicate: null, apis: ['/api/im/block'],
      requiredRequests: [{ urlTemplate: '/api/im/block', method: 'POST', kind: 'write' }] },
    { id: 'clear', handler: 'onDangerConfirm', predicate: null, apis: ['/api/im/delete'],
      requiredRequests: [{ urlTemplate: '/api/im/delete', method: 'POST', kind: 'write' }] },
  ]],
  // 2026-09-16 H050/H051 帖文操作接线:square 详情/列表把 cy-post-actions 的 select 事件
  // 按 action 分发。bind:select 是事件合同,静态体能顺着同步 helper 同时看到 delete 与 report
  // 两次写 ——「一次点击两次写」的判据会误报,所以必须按互斥分支显式声明。
  // 举报分支的确认闸在 reportSquare 的 modal.show 里(与 tap 是同一条闸),事件合同推不出
  // 确认模式,这里按检测器对 modal.show 的既有标签如实声明(wx.showModal),才能保住
  // 「举报有确认 + 有取消分支」这条棘轮。
  ['pages/square/detail/index.js:onPostAction', [
    { id: 'edit', handler: 'openPostEdit', predicate: { selectAction: 'edit' }, apis: [], effectClasses: ['state'] },
    { id: 'delete', handler: 'deleteSquarePost', predicate: { selectAction: 'delete' }, apis: ['/api/v1/community/posts/{param}'] },
    { id: 'report', handler: 'reportSquareFromActions', predicate: { selectAction: 'report' }, apis: ['/api/v1/community/capabilities', '/api/v1/community/reports'], confirmationMode: 'wx.showModal' },
  ]],
  ['pages/square/components/cy/post-actions/index.js:onDelete', [
    // 组件的删除闸走 this._danger() 间接拿 cy-danger-confirm(selectComponent 不在本函数体),
    // 静态推不出确认模式;组件内确实挂了闸且用户可取消,如实声明。
    { id: null, handler: 'onDelete', apis: [], effectClasses: ['state'], confirmationMode: 'cy-danger-confirm' },
  ]],
])

function interactionModeFor(event) {
  return TAP_EVENTS.has(event) ? 'tap' : 'event-contract'
}

function callArgumentSource(source, callIndex) {
  const body = String(source || '')
  const open = body.indexOf('(', callIndex)
  if (open < 0) return ''
  let depth = 0
  let quote = null
  let escaped = false
  let lineComment = false
  let blockComment = false
  for (let index = open; index < body.length; index += 1) {
    const char = body[index]
    const next = body[index + 1]
    if (lineComment) {
      if (char === '\n') lineComment = false
      continue
    }
    if (blockComment) {
      if (char === '*' && next === '/') { blockComment = false; index += 1 }
      continue
    }
    if (quote) {
      if (escaped) escaped = false
      else if (char === '\\') escaped = true
      else if (char === quote) quote = null
      continue
    }
    if (char === '/' && next === '/') { lineComment = true; index += 1; continue }
    if (char === '/' && next === '*') { blockComment = true; index += 1; continue }
    if (char === '"' || char === "'" || char === '`') { quote = char; continue }
    if (char === '(') depth += 1
    else if (char === ')' && --depth === 0) return body.slice(open + 1, index)
  }
  return ''
}

// 2026-09-06 原生弹层全删:确认框统一走 utils/modal.js 的 modal.show(与 wx.showModal 同形),契约名沿用 'wx.showModal'
function modalContractFor(directHandlerBody, interactionMode) {
  if (interactionMode !== 'tap') return null
  const body = String(directHandlerBody || '')
  for (const match of body.matchAll(/\b(?:wx\.showModal|(?:cy)?[mM]odal\.show)\s*\(/g)) {
    const args = callArgumentSource(body, match.index)
    if (!args || /\bshowCancel\s*:\s*false\b/.test(args)) continue
    // 只把用户选择会控制后续动作的确认框列为交互分支。信息提示/错误回调即使能关闭，
    // 也不是当前按钮的 confirm/cancel 决策，不能污染 success 或制造不可达取消证据。
    if (/\b[A-Za-z_$][\w$]*\.confirm\b/.test(args)) return 'wx.showModal'
  }
  return null
}

function nestedCallbackRanges(source) {
  const body = String(source || '')
  // ★ 边界判定必须先屏蔽字符串/注释/正则:body 里一句 `const re = /}/` 就能让 braceDepthAt
  //   整体错位,把顶层确认框误判成嵌套(假事实:台账写下「没有确认框」);反过来一句
  //   `"wx.showModal({...r.confirm...})"` 字符串又会被当成真的确认框(假事实:凭空多一个确认)。
  //   maskNonCode 保持长度与换行,索引可直接在原文上复用。
  const masked = maskNonCodeSafely(body)
  const starts = []
  const patterns = [
    /\bfunction(?:\s+[A-Za-z_$][\w$]*)?\s*\([^)]*\)\s*\{/g,
    /(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>\s*\{/g,
    /\b(?:success|fail|complete|then|catch|finally)\s*\([^)]*\)\s*\{/g,
  ]
  patterns.forEach((pattern) => {
    for (const match of masked.matchAll(pattern)) starts.push(match.index + match[0].lastIndexOf('{'))
  })
  if (!starts.length) return []
  const ordered = [...new Set(starts)].sort((a, b) => a - b)
  const findClose = (open) => {
    let depth = 0
    for (let index = open; index < masked.length; index += 1) {
      const char = masked[index]
      if (char === '{') depth += 1
      else if (char === '}' && --depth === 0) return index
    }
    return masked.length - 1
  }
  const ranges = []
  let maskedUntil = -1
  ordered.forEach((open) => {
    if (open <= maskedUntil) return
    // legacy `name: function () { ... }` 的第一个 function 是 handler 本体，不是回调。
    if (braceDepthAt(masked, open) === 0) return
    const close = findClose(open)
    ranges.push({ open, close })
    maskedUntil = close
  })
  return ranges
}

// 同一源码会被多个控件反复解析；只缓存成功掩码，每轮台账构建清空。
const nonCodeMasks = new Map()
function maskNonCodeSafely(source) {
  const text = String(source || '')
  if (nonCodeMasks.has(text)) return nonCodeMasks.get(text)
  try {
    const masked = maskNonCode(text)
    nonCodeMasks.set(text, masked)
    return masked
  } catch (_) {
    return text
  }
}

function maskNestedCallbackBodies(source) {
  const body = String(source || '')
  const chars = [...body]
  nestedCallbackRanges(body).forEach(({ open, close }) => {
    for (let index = open + 1; index < close; index += 1) {
      if (chars[index] !== '\n') chars[index] = ' '
    }
  })
  return chars.join('')
}

function braceDepthAt(source, targetIndex) {
  // 与 nestedCallbackRanges 同源:先屏蔽字符串/注释/正则再数深度,否则 body 里的
  // `const re = /}/`、`"success() {"` 会把顶层深度算错(FP/FN 双向都会发生)。
  const body = maskNonCodeSafely(String(source || ''))
  let depth = 0
  for (let index = 0; index < targetIndex; index += 1) {
    const char = body[index]
    if (char === '{') depth += 1
    else if (char === '}') depth -= 1
  }
  return depth
}

function synchronousModalContract(source, interactionMode) {
  if (interactionMode !== 'tap') return null
  const body = String(source || '')
  // 匹配与深度都走屏蔽后的副本:字符串里一句 showModal 文本不算真确认框,
  // 正则里的 `}` 也不能把顶层确认框的 depth 顶掉。
  const masked = maskNonCodeSafely(body)
  const nested = nestedCallbackRanges(body)
  for (const match of masked.matchAll(/\b(?:wx\.showModal|(?:cy)?[mM]odal\.show)\s*\(/g)) {
    // 只接受当前 method 顶层同步路径；条件块必须由 variant 显式声明，不能把一条
    // 分支里的确认框强加给同一 handler 的其它状态。
    if (braceDepthAt(masked, match.index) !== 1) continue
    if (nested.some(({ open, close }) => match.index > open && match.index < close)) continue
    const args = maskNonCodeSafely(callArgumentSource(body, match.index))
    if (!args || /\bshowCancel\s*:\s*false\b/.test(args)) continue
    if (/\b[A-Za-z_$][\w$]*\.confirm\b/.test(args)) return 'wx.showModal'
  }
  return null
}

// 2026-08-27:危险动作的确认框从 wx.showModal 换成了 <cy-danger-confirm> 三段式
// (确认 → 执行 → 结果卡),文案在 utils/danger-actions.js 登记。台账必须认这一种,
// 否则这批动作会被记成「没有确认框」—— 那比没有台账更坏:它会写下一条假事实。
// 判据和 showModal 那条同源:handler 里同步地把确认交给了别人,而不是直接就干。
const DANGER_CONFIRM_OPEN_RE = /\.open\(\s*(?:[^)]*?\?\s*)?['"][\w.]+['"]/

function synchronousDangerConfirmContract(body) {
  const synchronousBody = maskNestedCallbackBodies(body)
  if (!/selectComponent\s*\(/.test(synchronousBody)) return false
  return DANGER_CONFIRM_OPEN_RE.test(synchronousBody)
}

function modalContractForHandler(handlerName, slices, interactionMode, seen) {
  if (interactionMode !== 'tap') return null
  seen = seen || new Set()
  if (seen.has(handlerName)) return null
  seen.add(handlerName)
  const body = slices.get(handlerName) || ''
  const synchronousBody = maskNestedCallbackBodies(body)
  if (synchronousModalContract(body, interactionMode)) return 'wx.showModal'
  if (synchronousDangerConfirmContract(body)) return 'cy-danger-confirm'
  const callRe = /\b(?:this|that|self)\.([A-Za-z_$][\w$]*)\s*\(/g
  let match
  while ((match = callRe.exec(synchronousBody))) {
    if (braceDepthAt(synchronousBody, match.index) === 1 && slices.has(match[1])) {
      const nested = modalContractForHandler(match[1], slices, interactionMode, seen)
      if (nested) return nested
    }
  }
  return null
}

let serverApiContractCache = null
function normalizeApiTemplate(value) {
  const normalized = String(value || '').replace(/\/+/g, '/').replace(/\{[^}]+\}/g, '{param}')
  return normalized.length > 1 ? normalized.replace(/\/$/, '') : normalized
}

function serverApiContracts() {
  if (serverApiContractCache) return serverApiContractCache
  const contracts = new Map()
  const files = []
  const walk = (dir) => {
    if (!fs.existsSync(dir)) return
    fs.readdirSync(dir, { withFileTypes: true }).forEach((entry) => {
      const file = path.join(dir, entry.name)
      if (entry.isDirectory()) walk(file)
      else if (entry.isFile() && entry.name.endsWith('Controller.java')) files.push(file)
    })
  }
  const add = (base, endpoint, method, sourceFile) => {
    const url = normalizeApiTemplate(`${String(base || '').replace(/\/$/, '')}/${String(endpoint || '').replace(/^\//, '')}`)
    if (!url.startsWith('/api/')) return
    if (!contracts.has(url)) contracts.set(url, new Map())
    contracts.get(url).set(String(method).toUpperCase(), path.relative(ROOT, sourceFile))
  }
  walk(SERVER_CONTROLLER_ROOT)
  files.forEach((file) => {
    const source = fs.readFileSync(file, 'utf8')
    const classIndex = source.search(/\bclass\s+[A-Za-z_$][\w$]*/)
    if (classIndex < 0) return
    const header = source.slice(0, classIndex)
    const bases = [...header.matchAll(/@RequestMapping\s*\(\s*(?:(?:value|path)\s*=\s*)?["']([^"']+)["']/g)]
      .map((match) => match[1])
    const base = bases.at(-1) || ''
    for (const match of source.matchAll(/@(Get|Post|Put|Delete|Patch)Mapping(?:\s*\(([^)]*)\))?/g)) {
      const quoted = String(match[2] || '').match(/["']([^"']*)["']/)
      add(base, quoted ? quoted[1] : '', match[1], file)
    }
    for (const match of source.matchAll(/@RequestMapping\s*\(([^)]*RequestMethod\.(GET|POST|PUT|DELETE|PATCH)[^)]*)\)/g)) {
      const quoted = match[1].match(/["']([^"']*)["']/)
      add(base, quoted ? quoted[1] : '', match[2], file)
    }
  })
  serverApiContractCache = contracts
  return contracts
}

function reconcileServerContracts(apiContracts) {
  const server = serverApiContracts()
  const reconciled = apiContracts.map((contract) => {
    const methods = server.get(normalizeApiTemplate(contract.url))
    if (!methods || methods.size === 0) return contract
    if (methods.size === 1) {
      const serverMethod = [...methods.keys()][0]
      if (contract.source === 'local-wrapper' && String(contract.method).toUpperCase() !== serverMethod) {
        throw new Error(`${contract.url}: 本地 wrapper method ${contract.method} 不匹配后端 Controller ${serverMethod}`)
      }
      return { ...contract, method: serverMethod, source: 'server-controller' }
    }
    if (!methods.has(String(contract.method || '').toUpperCase())) {
      throw new Error(`${contract.url}: 前端 method ${contract.method || 'null'} 不在后端 Controller 合同 ${[...methods.keys()].join('/')}`)
    }
    return { ...contract, method: String(contract.method).toUpperCase(), source: 'server-controller' }
  })
  return [...new Map(reconciled.map((item) => [`${item.url}\0${item.method}`, item])).values()]
}

function callsNamedHelper(source, externalHelperNames, receiverPattern) {
  if (!externalHelperNames || externalHelperNames.size === 0) return false
  const body = String(source || '')
  return [...externalHelperNames].some((name) => new RegExp(
    `${receiverPattern}\\.\\s*${name.replace(/[$]/g, '\\$&')}\\s*\\(`,
  ).test(body))
}

/**
 * [A] 只抹注释、保留字符串(等长替换,偏移可直接复用)。
 *
 * 分类此前直接扫原始 body,注释里的字句会参与判定。2026-09-22 实证:两行中文注释
 * (其中并无 wx.*)把 play 页 4 个控件从 read 翻成 external —— 即「需真机、豁免常规证据」
 * 那一档。反过来也成立:注释里写一句 wx.scanCode( 就能给控件挂上它并不具备的能力。
 *
 * 用仓库既有的 AST 掩码器(maskJavaScriptComments),不自造正则:它按语法树抹注释,
 * 不会误伤字符串里的 `//`。maskNonCode 不能用 —— 它连字符串也抹,实测会把 705 个控件的
 * write/read 降级成 state,那是削弱门禁。
 *
 * 片段(方法体切片)未必是可独立解析的 JS;解析不了就原样返回并计数,由 selftest 盯住
 * 降级规模,不静默扩散。
 */
let commentMaskFallbacks = 0
function stripJsCommentsKeepStrings(source) {
  const text = String(source || '')
  if (!text.includes('//') && !text.includes('/*')) return text
  try {
    const masked = maskJavaScriptComments(text)
    if (typeof masked === 'string' && masked.length === text.length) return masked
  } catch (e) {
    // 片段不可独立解析:退回原文。计数让 selftest 能看见降级面。
  }
  try {
    const wrapped = maskJavaScriptComments(`function __probe__() {${text}\n}`)
    if (typeof wrapped === 'string' && wrapped.length === text.length + 24) {
      return wrapped.slice(22, wrapped.length - 2)
    }
  } catch (e) {
    // 包一层仍解析不了(例如切片本身就是半个对象字面量)。
  }
  commentMaskFallbacks += 1
  return text
}

function externalCapabilitiesFor(source, externalHelperNames) {
  // [A] 判定能力时连注释带字符串一起抹:能力调用永远不会长在字符串字面量里,
  //   而注释/字符串里出现 `wx.scanCode(` 会凭空给控件挂上它并不具备的能力。
  //   ⚠️ 只在这里能这么抹 —— 效果判定(read/write)要从字符串里读 `/api/...`,
  //   对它抹字符串会把 705 个控件的 write/read 降级成 state(实测),那是削弱门禁。
  const body = maskNonCodeSafely(String(source || ''))
  const direct = [...body.matchAll(new RegExp(`\\b${WX_RECEIVER_PATTERN}\\.(${EXTERNAL_METHOD_PATTERN})\\s*\\(`, 'g'))]
    .map((match) => match[1])
  const helpers = [...(externalHelperNames || [])].filter((name) => new RegExp(
    `(?:\\bapp|\\bgetApp\\(\\))\\.\\s*${name.replace(/[$]/g, '\\$&')}\\s*\\(`,
  ).test(body)).map((name) => `app.${name}`)
  return [...new Set(direct.concat(helpers))].sort()
}

function successCapabilitiesFor(capabilities) {
  const primary = (capabilities || []).filter((rawName) => !RECOVERY_ONLY_EXTERNAL_CAPABILITIES.has(
    String(rawName).replace(/^app\./, ''),
  ))
  return primary
}

function appHelperCalls(source, helperBodies) {
  const body = String(source || '')
  return [...(helperBodies || new Map()).entries()].filter(([name]) => new RegExp(
    `(?:\\bapp|\\bgetApp\\(\\))\\.\\s*${name.replace(/[$]/g, '\\$&')}\\s*\\(`,
  ).test(body))
}

function inferEffectClasses(handlerName, source, appHelperBodies, targets, directSource) {
  const name = String(handlerName || '')
  const body = String(source || '')
  const directBody = directSource == null ? body : String(directSource)
  const effects = []
  const targetWrites = targets && ((targets.primaryApis || []).some(isWriteApi)
    || (targets.apiContracts || []).some((c) => c.adapterBranch && c.method !== 'GET'))
  if (NETWORK_RE.test(body)) effects.push(WRITE_RE.test(name) || targetWrites ? 'write' : 'read')
  const appNetworkCalls = appHelperCalls(body, appHelperBodies)
    .filter(([, helperBody]) => NETWORK_RE.test(helperBody))
  if (!effects.length && appNetworkCalls.length) {
    effects.push(targetWrites || appNetworkCalls.some(([helperName]) => WRITE_RE.test(helperName) || APP_WRITE_HELPERS.has(helperName))
      ? 'write' : 'read')
  }
  if (!effects.some((kind) => kind === 'read' || kind === 'write')
      && targets && targets.primaryApis && targets.primaryApis.length) {
    effects.push(WRITE_RE.test(name) || targetWrites ? 'write' : 'read')
  }
  const hasDurableState = DURABLE_STATE_RE.test(body)
  const callsImportedSceneEntry = name !== 'openScene' && /(?<!\.)\bopenScene\s*\(/.test(directBody)
  if (NAVIGATION_RE.test(body) || callsImportedSceneEntry
      || (/^(?:go|to|nav|back)(?:[A-Z_]|$)/.test(name) && !hasDurableState)) effects.push('navigation')
  // Toast 是瞬时回执，不是可回读完成态；把它算作 state 会让 navigation success
  // 被迫证明错误回调里的 toast，反而漏掉真正的下一页。只登记可持续回读的页内状态。
  if (hasDurableState) {
    effects.push('state')
  }
  return [...new Set(effects.length ? effects : ['other'])]
}

function inferBaseActionClass(handlerName, source, appHelperBodies) {
  const effects = inferEffectClasses(handlerName, source, appHelperBodies)
  return ['write', 'read', 'navigation', 'state', 'other'].find((kind) => effects.includes(kind)) || 'other'
}

function inferActionClass(handlerName, source, externalHelperNames, appHelperBodies) {
  return externalCapabilitiesFor(source, externalHelperNames).length
    ? 'external' : inferBaseActionClass(handlerName, source, appHelperBodies)
}

function buildExpectedReadback(actionClass, baseActionClass, effectClasses) {
  const checks = {
    navigation: '回读 getCurrentPages() 末页 route/options，并确认目标页关键数据已装载',
    write: '重新读取服务端权威资源，核对 owner/scope、状态与幂等结果；success/toast 不算完成',
    read: '记录实际 URL + method + auth，回读 page.data 的 loading/error/empty/ready 与响应映射',
    state: '调用真实 handler 后回读 page.data 与可见 DOM，禁止直接 setData 伪造完成态',
    external: '真机回读微信能力结果及取消/拒绝/超时分支；开发者工具回执不替代真机证据',
    other: '调用真实 handler，回读 page.data、页面栈或服务端权威状态，按实际副作用补齐证据',
  }
  const effects = (effectClasses && effectClasses.length ? effectClasses : [baseActionClass || actionClass])
    .filter((kind) => kind && kind !== 'other')
  if (actionClass === 'external') effects.push('external')
  if (!effects.length) effects.push(actionClass || 'other')
  return [...new Set(effects)].map((kind) => checks[kind] || checks.other).join('；')
}

const CANCEL_CAPABILITIES = new Set([
  'scanCode', 'chooseLocation', 'choosePoi', 'chooseAddress', 'chooseImage', 'chooseMedia',
  'chooseMessageFile', 'requestPayment', 'requestSubscribeMessage', 'openSetting',
  'openPrivacyContract', 'saveImageToPhotosAlbum', 'makePhoneCall', 'openCustomerServiceChat',
  'showActionSheet',
])
const DENY_CAPABILITIES = new Set([
  'scanCode', 'getLocation', 'chooseLocation', 'choosePoi', 'chooseAddress', 'chooseImage',
  'chooseMedia', 'chooseMessageFile', 'authorize', 'openSetting', 'openPrivacyContract',
  'startLocationUpdate', 'startLocationUpdateBackground', 'createCameraContext',
  'saveImageToPhotosAlbum',
])

function normalizedCapability(name) {
  return String(name || '').replace(/^app\./, '')
}

function capabilityFailureContract(externalCapabilities) {
  const capabilities = [...new Set((externalCapabilities || []).map(normalizedCapability))]
  const result = { branches: [], requiredCapabilityByBranch: {} }
  ;[
    ['cancelled', capabilities.filter((name) => CANCEL_CAPABILITIES.has(name))],
    ['denied', capabilities.filter((name) => DENY_CAPABILITIES.has(name))],
  ].forEach(([outcome, candidates]) => {
    candidates.forEach((capability) => {
      const branch = candidates.length === 1 ? outcome : `${capability}-${outcome}`
      result.branches.push(branch)
      result.requiredCapabilityByBranch[branch] = capability
    })
  })
  return result
}

function requiredBranchesFor(actionClass, baseActionClass, externalCapabilities, effectClasses, accessBoundary) {
  const branches = {
    state: ['success'],
    navigation: ['success', 'error'],
    other: ['success', 'error'],
    read: ['success', 'error', 'timeout', 'duplicate-trigger'],
    write: ['success', 'error', 'timeout', 'duplicate-trigger', 'forbidden-owner'],
    external: ['success', 'error', 'timeout', 'duplicate-trigger'],
  }
  if (actionClass === 'external') {
    const contract = capabilityFailureContract(externalCapabilities)
    const capabilityBranches = branches.external.slice()
    capabilityBranches.push(...contract.branches)
    const base = (effectClasses && effectClasses.length ? effectClasses : [baseActionClass])
      .filter((kind) => kind && kind !== 'other').flatMap((kind) => branches[kind] || branches.other)
    const result = [...new Set(base.concat(capabilityBranches))]
    if (accessBoundary === 'self') return result.filter((branch) => branch !== 'forbidden-owner')
    if (accessBoundary === 'config-capability') return result.map((branch) => branch === 'forbidden-owner' ? 'forbidden-capability' : branch)
    return result
  }
  const result = [...new Set((effectClasses && effectClasses.length ? effectClasses : [actionClass])
    .flatMap((kind) => branches[kind] || branches.other))]
  if (accessBoundary === 'self') return result.filter((branch) => branch !== 'forbidden-owner')
  if (accessBoundary === 'config-capability') return result.map((branch) => branch === 'forbidden-owner' ? 'forbidden-capability' : branch)
  return result
}

function requiredPlatformsFor(actionClass) {
  return actionClass === 'external' ? ['ios', 'android'] : []
}

function parseAttrs(tagSource) {
  const attrs = {}
  const re = /([A-Za-z_@:][\w:.-]*)\s*=\s*(["'])([\s\S]*?)\2/g
  let match
  while ((match = re.exec(tagSource))) attrs[match[1]] = match[3]
  return attrs
}

// Only the two shared error components implement this product-level blank state.
// Other tags or unknown expressions remain actionable and keep their handler checks.
function loadFailureEventVisibility(component, tagSource, attrs) {
  const supported = ['components/cy/error/index', 'components/cy/inline-error/index']
  if (!supported.includes(component)) return { excluded: false, condition: null }
  const template = fs.readFileSync(path.join(ROOT, component + '.wxml'), 'utf8')
  const blankTemplate = component === supported[0]
    ? template.includes('wx:if="{{loadFailure}}" aria-hidden="true"')
    : template.includes('wx:if="{{!loadFailure}}"')
  if (!blankTemplate) return { excluded: false, condition: null }
  const value = attrs['load-failure']
  const bare = value === undefined && /(?:^|\s)load-failure(?=\s|\/?>)(?!\s*=)/.test(tagSource)
  const alwaysBlank = bare || value === 'true' || /^\{\{\s*true\s*\}\}$/.test(value || '')
  return { excluded: alwaysBlank, condition: bare ? '{{true}}' : (value || null) }
}

function staticAttr(value) {
  const text = value == null ? '' : String(value)
  return text && !text.includes('{{') ? text : null
}

function dynamicAttr(value) {
  const text = value == null ? '' : String(value)
  return text.includes('{{') ? text : null
}
// ponytail: only literal branches under a simple data-path condition are supported.
// Extend this grammar when a real binding needs more; unknown expressions fail closed.
function eventHandlerNames(value) {
  const classified = classifyBinding(value)
  if (classified.kind === 'identifier') return [classified.name]
  if (classified.kind === 'empty') return []
  const match = /^\{\{\s*!?[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\s*\?\s*(['"])([A-Za-z_$][\w$]*)\1\s*:\s*(['"])([A-Za-z_$][\w$]*)\3\s*\}\}$/.exec(value)
  if (!match) throw new Error(`无法静态解析事件绑定: ${value}`)
  return [...new Set([match[2], match[4]])]
}

/**
 * [B] 模块级 helper 的函数体。
 *
 * 此前展开只跟 `this.x()` / `that.x()`,跟不进 `require` 进来的工具函数。真实后果:
 * publish/fabu 的 choosePoiForNode 调的是 `pickLocation(...)`(内部才是 wx.chooseLocation),
 * 代码里根本没有 wx.chooseLocation —— 它一直被判成 external,靠的是**注释里提了一句**。
 * 把注释抹掉(见 [A])它就会掉成 state,等于把一个真需要真机验证的控件挪出证据要求。
 * 所以 A 必须和 B 一起做,单做任何一个都会让判定更错。
 */
const moduleFunctionCache = new Map()
function moduleFunctionBodies(file) {
  if (moduleFunctionCache.has(file)) return moduleFunctionCache.get(file)
  const out = new Map()
  try {
    const src = fs.readFileSync(file, 'utf8')
    const masked = maskNonCodeSafely(src)
    // function f(){} / const f = function(){} / const f = (...) => {}
    const re = /(?:^|[\n;])\s*(?:function\s+([A-Za-z_$][\w$]*)\s*\(|(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s+)?(?:function\s*\*?\s*\(|\([^)]*\)\s*=>|[A-Za-z_$][\w$]*\s*=>))/g
    let m
    while ((m = re.exec(masked))) {
      const name = m[1] || m[2]
      const open = masked.indexOf('{', m.index + m[0].length - 1)
      if (open === -1) continue
      const close = matchBrace(masked, open)
      if (close === -1) continue
      if (!out.has(name)) out.set(name, src.slice(open, close + 1))
    }
  } catch (e) {
    // 只容忍读不到文件;其余(如依赖没导入)必须炸出来,否则 B 会静默不生效。
    if (e && e.code !== 'ENOENT') throw e
  }
  moduleFunctionCache.set(file, out)
  return out
}

/** 模块内递归:helper 调用同模块的其它 helper 时一并带上。 */
function expandModuleFunction(file, name, seen, functionBodies) {
  seen = seen || new Set()
  const key = `${file}#${name}`
  if (seen.has(key)) return ''
  seen.add(key)
  const bodies = functionBodies || moduleFunctionBodies(file)
  const body = bodies.get(name)
  if (!body) return ''
  const inner = stripJsCommentsKeepStrings(body)
  const parts = [body]
  const callRe = /(^|[^\w$.])([A-Za-z_$][\w$]*)\s*\(/g
  let m
  while ((m = callRe.exec(inner))) {
    if (bodies.has(m[2])) parts.push(expandModuleFunction(file, m[2], seen, bodies))
  }
  return parts.join('\n')
}

// Narrow only proven flat CommonJS namespaces; unresolved exports/aliases keep the conservative full module.
function namespaceDependencySource(file, aliases, source, inlineExports) {
  const full = fs.readFileSync(file, 'utf8')
  const code = maskNonCodeSafely(source)
  const called = new Set(inlineExports || [])
  for (const alias of aliases) {
    const pattern = new RegExp(`\\b${alias.replace(/[$]/g, '\\$&')}\\b`, 'g')
    for (const reference of code.matchAll(pattern)) {
      const call = code.slice(reference.index + alias.length).match(/^\.([A-Za-z_$][\w$]*)\s*\(/)
      if (!call) return full
      called.add(call[1])
    }
  }
  return selectedDependencySource(file, full, called, false)
}

/** Only direct uses of proven named exports narrow the module; callbacks/aliases stay conservative. */
function namedDependencySource(file, namedImports, source) {
  const full = fs.readFileSync(file, 'utf8')
  const code = maskNonCodeSafely(source)
  const called = new Set()
  for (const parts of namedImports) {
    if (parts.length > 2 || parts.some(name => !/^[A-Za-z_$][\w$]*$/.test(name))) return full
    const local = parts.at(-1)
    const pattern = new RegExp(`(^|[^\\w$])${local.replace(/[$]/g, '\\$&')}(?![\\w$])`, 'g')
    for (const reference of code.matchAll(pattern)) {
      const start = reference.index + reference[1].length
      if (code[start - 1] === '.' || !/^\s*\(/.test(code.slice(start + local.length))) return full
      called.add(parts[0])
    }
  }
  return selectedDependencySource(file, full, called, true)
}

/** Reuse the flat export proof and local function closure for both import forms. */
function selectedDependencySource(file, full, called, named) {
  if (!called.size) return full
  let ast
  try { ast = parseJavaScript(full) } catch (error) {
    if (error.name === 'SyntaxError') return full
    throw error
  }
  // Named helpers may be pure expression arrows (registration-seat validators). Their AST
  // body spans avoid the regex scanner borrowing a later, unrelated function's braces.
  const bodies = named ? new Map() : moduleFunctionBodies(file)
  const functions = new Set()
  const imports = new Set()
  const declarations = []
  // This actual module has primitive cache state; retain its declarations when following clear/load.
  // Other modules and executable/unresolved initializers keep the conservative whole-module path.
  const roleCache = file === path.join(ROOT, 'utils/roleGuard.js')
  const primitive = node => node && (node.type === 'Literal'
    || node.type === 'BinaryExpression' && primitive(node.left) && primitive(node.right))
  let exportsObject
  for (const statement of ast.body) {
    if (statement.type === 'FunctionDeclaration' && statement.id) {
      if (named) {
        if (statement.params.some(parameter => parameter.type !== 'Identifier')) return full
        bodies.set(statement.id.name, full.slice(statement.body.start, statement.body.end))
      } else if (bodies.get(statement.id.name) !== full.slice(statement.body.start, statement.body.end)) return full
      functions.add(statement.id.name)
      continue
    }
    if (named && statement.type === 'VariableDeclaration' && statement.declarations.every(({ id, init }) =>
      id.type === 'Identifier' && init && ['FunctionExpression', 'ArrowFunctionExpression'].includes(init.type)
      && init.params.every(parameter => parameter.type === 'Identifier'))) {
      statement.declarations.forEach(({ id, init }) => {
        functions.add(id.name)
        bodies.set(id.name, full.slice(init.body.start, init.body.end))
      })
      continue
    }
    if (statement.type === 'VariableDeclaration' && statement.declarations.every(({ id, init }) =>
      id.type === 'Identifier' && init && init.type === 'CallExpression' && init.callee.name === 'require'
      && init.arguments.length === 1 && typeof init.arguments[0].value === 'string')) {
      statement.declarations.forEach(({ id }) => imports.add(id.name))
      continue
    }
    if (roleCache && statement.type === 'VariableDeclaration' && statement.declarations.every(({ id, init }) =>
      id.type === 'Identifier' && primitive(init))) {
      declarations.push(full.slice(statement.start, statement.end))
      continue
    }
    const expression = statement.type === 'ExpressionStatement' && statement.expression
    if (expression && expression.type === 'Literal' && expression.value === 'use strict') continue
    if (!exportsObject && expression && expression.type === 'AssignmentExpression' && expression.operator === '='
        && expression.left.type === 'MemberExpression' && !expression.left.computed
        && expression.left.object.name === 'module' && expression.left.property.name === 'exports'
        && expression.right.type === 'ObjectExpression') {
      exportsObject = expression.right
      continue
    }
    return full
  }
  if (!exportsObject || exportsObject.properties.some(property => property.type !== 'Property'
      || property.computed || property.kind !== 'init' || property.key.type !== 'Identifier'
      || property.value.type !== 'Identifier' || property.key.name !== property.value.name
      || !functions.has(property.value.name))) return full
  const exported = new Set(exportsObject.properties.map(property => property.key.name))
  if ([...called].some(name => !exported.has(name))) return full
  const selected = declarations.concat([...called].map(name => expandModuleFunction(file, name, null, bodies))).join('\n')
  const selectedCode = maskNonCodeSafely(selected)
  // A second imported module is not a proven local closure. Keep the existing conservative envelope.
  if (named && [...imports].some(name =>
    new RegExp(`(^|[^\\w$])${name.replace(/[$]/g, '\\$&')}(?![\\w$])`).test(selectedCode))) return full
  // Passing a local helper as a callback/alias is not a proven direct-call closure.
  for (const name of functions) {
    const pattern = new RegExp(`\\b${name.replace(/[$]/g, '\\$&')}\\b`, 'g')
    for (const reference of selectedCode.matchAll(pattern)) {
      if (selectedCode[reference.index - 1] === '.'
          || !/^\s*\(/.test(selectedCode.slice(reference.index + name.length))) return full
    }
  }
  return selected
}

/** `const { a } = require('x')` 与 `const ns = require('x')` 两种绑定。 */
function requireBindings(src, dir) {
  const named = new Map()
  const ns = new Map()
  // ⚠️ 这里必须用「只抹注释、保字符串」:maskNonCode 会把 require('x') 的路径连引号一起
  //   抹成空白,正则零命中而且不报错 —— 一个会静默让 [B] 整块失效的坑(已实测踩到)。
  const masked = stripJsCommentsKeepStrings(src)
  const destructure = /(?:const|let|var)\s*\{([^}]*)\}\s*=\s*require\(\s*['"]([^'"]+)['"]\s*\)/g
  let m
  while ((m = destructure.exec(masked))) {
    const file = resolveRequireFile(dir, m[2])
    if (!file) continue
    m[1].split(',').forEach((piece) => {
      const parts = piece.split(':').map((x) => x.trim()).filter(Boolean)
      if (!parts.length) return
      const exported = parts[0]
      const local = parts[1] || parts[0]
      if (/^[A-Za-z_$][\w$]*$/.test(local)) named.set(local, { file, exported })
    })
  }
  const namespace = /(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*require\(\s*['"]([^'"]+)['"]\s*\)/g
  while ((m = namespace.exec(masked))) {
    const file = resolveRequireFile(dir, m[2])
    if (file) ns.set(m[1], file)
  }
  return { named, ns }
}

// This shared controller exposes an object of methods, not flat module functions. Follow only
// the component's explicit methods.X.call(this, ...) delegates; never add the entire 16KB flow.
function deregisterDelegates(jsFile, slices) {
  if (rel(jsFile) !== 'components/cy/scene-settings-deregister/index.js') return
  const component = fs.readFileSync(jsFile, 'utf8')
  if (!/const\s*\{\s*initialData,\s*methods\s*\}\s*=\s*require\(['"]\.\.\/\.\.\/\.\.\/utils\/deregister-flow\.js['"]\)/.test(component))
    throw new Error('注销组件共享 owner 导入不再符合已证明合同')
  const file = path.join(ROOT, 'utils/deregister-flow.js'), source = fs.readFileSync(file, 'utf8')
  const ast = parseJavaScript(source)
  const declaration = ast.body.flatMap(node => node.type === 'VariableDeclaration' ? node.declarations : [])
    .find(node => node.id.name === 'methods' && node.init && node.init.type === 'ObjectExpression')
  const exported = ast.body.some(node => node.type === 'ExpressionStatement'
    && node.expression.type === 'AssignmentExpression' && source.slice(node.expression.left.start, node.expression.left.end) === 'module.exports'
    && node.expression.right.type === 'ObjectExpression' && node.expression.right.properties.some(property =>
      property.key.name === 'methods' && property.value.name === 'methods'))
  if (!declaration || !exported) throw new Error('注销共享 methods 对象缺少可证明的真实导出')
  for (const statement of ast.body) {
    if (statement.type === 'FunctionDeclaration') continue
    if (statement.type === 'VariableDeclaration' && statement.kind === 'const' && statement.declarations.every(node => {
      if (node === declaration) return true
      const value = node.init
      return value && (value.type === 'CallExpression' && (value.callee.name === 'getApp' && value.arguments.length === 0
          || value.callee.name === 'require' && value.arguments.length === 1 && value.arguments[0].type === 'Literal'
            && typeof value.arguments[0].value === 'string')
        || value.type === 'ArrayExpression' && value.elements.every(item => item.type === 'Literal'))
    })) continue
    if (statement.type === 'ExpressionStatement' && statement.expression.type === 'AssignmentExpression'
        && source.slice(statement.expression.left.start, statement.expression.left.end) === 'module.exports'
        && statement.expression.right.type === 'ObjectExpression'
        && statement.expression.right.properties.every(property => !property.computed && property.value.type === 'Identifier')) continue
    throw new Error('注销共享 owner 存在未登记的顶层副作用')
  }
  const methods = new Map()
  const helpers = new Map(ast.body.filter(node => node.type === 'FunctionDeclaration').map(node =>
    [node.id.name, source.slice(node.body.start, node.body.end)]))
  for (const property of declaration.init.properties) {
    if (property.type !== 'Property' || property.computed || property.value.type !== 'FunctionExpression')
      throw new Error('注销共享 methods 存在未解析分支')
    const body = source.slice(property.start, property.end)
    const local = [...helpers.keys()].filter(name => new RegExp('\\b' + name + '\\s*\\(').test(maskNonCodeSafely(body)))
    methods.set(property.key.name, [body].concat(local.map(name => expandModuleFunction(file, name, null, helpers))).join('\n'))
  }
  for (const [name, body] of slices) {
    const delegate = body.match(/\breturn\s+methods\.([A-Za-z_$][\w$]*)\.call\(this(?:\s*,[^;]*)?\);/)
    if (!delegate) continue
    const wrapper = parseJavaScript('({' + body + '})').body[0].expression.properties[0].value
    const parsed = wrapper.body.body, call = parsed[0] && parsed[0].argument
    if (parsed.length !== 1 || parsed[0].type !== 'ReturnStatement' || !call || call.type !== 'CallExpression'
        || call.callee.type !== 'MemberExpression' || call.callee.computed || call.callee.property.name !== 'call'
        || call.arguments.length !== wrapper.params.length + 1 || call.arguments[0].type !== 'ThisExpression'
        || wrapper.params.some((parameter, index) => parameter.type !== 'Identifier'
          || call.arguments[index + 1].type !== 'Identifier' || call.arguments[index + 1].name !== parameter.name))
      throw new Error('注销委托入口存在未登记的附加动作: ' + name)
    if (delegate[1] !== name || !methods.has(name)) throw new Error('注销共享方法委托身份无法证明: ' + name)
    slices.set(name, methods.get(name))
  }
}

function handlerSlices(jsFile) {
  const sources = new Map()
  const entries = extractOwnHandlerEntries(jsFile)
  const slices = new Map()
  entries.forEach((entry) => {
    if (!sources.has(entry.file)) sources.set(entry.file, fs.readFileSync(entry.file, 'utf8'))
    slices.set(entry.name, sources.get(entry.file).slice(entry.start, entry.end))
  })
  deregisterDelegates(jsFile, slices)
  // [B] 把 require 进来的 helper 按「本地名」和「命名空间.成员」塞进同一张表,
  //   展开时就能跟进去;页面自己的同名方法优先(JS 里本来就是它遮蔽 import)。
  try {
    const src = fs.readFileSync(jsFile, 'utf8')
    const { named, ns } = requireBindings(src, path.dirname(jsFile))
    named.forEach((target, local) => {
      if (slices.has(local)) return
      const body = expandModuleFunction(target.file, target.exported)
      if (body) slices.set(local, body)
    })
    ns.forEach((file, alias) => {
      moduleFunctionBodies(file).forEach((_body, fnName) => {
        const key = `${alias}.${fnName}`
        if (slices.has(key)) return
        const body = expandModuleFunction(file, fnName)
        if (body) slices.set(key, body)
      })
    })
  } catch (e) {
    // 同上:只容忍读不到入口文件。
    if (e && e.code !== 'ENOENT') throw e
  }
  return slices
}

/**
 * @param {{modules?: boolean}} [opts] modules=true 时额外跟进 require 进来的模块级 helper。
 *
 * ⚠️ 只有**能力探测**能开 modules。API/效果提取一定不能开:
 *   utils/ui-state-request.js 的 sendUiStateRequest 是个 switch 分发器,函数体里列着 14 个
 *   API 字面量;展开它会把 14 个 API 全算到调用方头上 —— 实测让 square/detail 的一个「关注」
 *   按钮凭空拥有 /api/topic/create、/api/user/deregister/apply 等 9 个写接口。
 *   能力探测不受这个影响:wx.* 能力不会被参数分发,函数体里有就是真能走到。
 */
function expandHandlerBody(handlerName, slices, seen, opts) {
  seen = seen || new Set()
  if (seen.has(handlerName)) return ''
  seen.add(handlerName)
  const raw = slices.get(handlerName) || ''
  // [A] 找调用点只看代码:注释里写一句 this.foo() 不该把 foo 的能力算到本控件头上。
  const body = stripJsCommentsKeepStrings(raw)
  const nested = []
  const callRe = /\b(?:this|that|self)\.([A-Za-z_$][\w$]*)\s*\(/g
  let match
  while ((match = callRe.exec(body))) {
    if (slices.has(match[1])) nested.push(expandHandlerBody(match[1], slices, seen, opts))
  }
  if (opts && opts.modules) {
    // [B] 裸调用(require 进来的 helper)与命名空间调用(ns.fn)。
    const bareRe = /(^|[^\w$.])([A-Za-z_$][\w$]*)\s*\(/g
    while ((match = bareRe.exec(body))) {
      if (slices.has(match[2])) nested.push(expandHandlerBody(match[2], slices, seen, opts))
    }
    const nsRe = /\b([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)\s*\(/g
    while ((match = nsRe.exec(body))) {
      const key = `${match[1]}.${match[2]}`
      if (slices.has(key)) nested.push(expandHandlerBody(key, slices, seen, opts))
    }
  }
  return [raw].concat(nested).join('\n')
}

/** 能力探测专用:跟进模块级 helper(见 expandHandlerBody 的告警)。 */
function expandCapabilityBody(handlerName, slices) {
  return expandHandlerBody(handlerName, slices, null, { modules: true })
}

function discoverExternalHelperNames(jsFiles) {
  const methods = []
  jsFiles.forEach((jsFile) => {
    if (!fs.existsSync(jsFile)) return
    const slices = handlerSlices(jsFile)
    for (const name of slices.keys()) methods.push({ name, body: expandHandlerBody(name, slices) })
  })
  const names = new Set(methods.filter((item) => APP_EXTERNAL_RE.test(item.body)).map((item) => item.name))
  let changed = true
  while (changed) {
    changed = false
    methods.forEach((item) => {
      if (!names.has(item.name) && callsNamedHelper(item.body, names, '(?:\\bthis|\\bthat|\\bself)')) {
        names.add(item.name)
        changed = true
      }
    })
  }
  return names
}

function discoverAppHelperBodies(appFile) {
  if (!fs.existsSync(appFile)) return new Map()
  const slices = handlerSlices(appFile)
  // Usage consent's record callback belongs to the separate agree gesture, not ensure()/session renewal.
  const usageFactory = slices.get('getUsageConsent')
  if (usageFactory) slices.set('getUsageConsent', usageFactory.replace(
    /record:\s*function\s*\(input\)\s*\{\s*return that\.recordConsent\(input\);?\s*\}/,
    'record: function (input) { /* invoked only by agreeUsageConsent */ }'))
  const bodies = new Map([...slices.keys()].map((name) => [name, expandHandlerBody(name, slices)]))
  if (bodies.has('agreeUsageConsent')) bodies.set('agreeUsageConsent', bodies.get('agreeUsageConsent') + '\n' + bodies.get('recordConsent'))
  return bodies
}

function routeFor(wxmlFile, jsFile, registeredRoutes) {
  const relative = rel(wxmlFile).replace(/\.wxml$/, '')
  if (registeredRoutes.has(relative)) return relative
  // <include src="..."> 的局部 WXML 由宿主页 JS 驱动，没有自己的页面注册；
  // 其动作必须归宿主页，不能伪装成独立 page，也不能因组件宿主图不认识 include 而被删掉。
  const jsRoute = rel(jsFile).replace(/\.js$/, '')
  if (registeredRoutes.has(jsRoute)) return jsRoute
  return `component:${relative}`
}

function loadRoles() {
  const byRoute = new Map()
  if (!fs.existsSync(ROUTE_MATRIX)) return byRoute
  const parsed = JSON.parse(fs.readFileSync(ROUTE_MATRIX, 'utf8'))
  const rows = Array.isArray(parsed) ? parsed : (parsed.rows || parsed.routes || [])
  rows.forEach((row) => {
    if (row.pagePath && row.role && !byRoute.has(row.pagePath)) byRoute.set(row.pagePath, row.role)
  })
  return byRoute
}

// 资源文件不是路由。`/pages/xxx/images/a.png` 会命中下面那条路由正则,
// 但它是图片路径,不是可跳转的页面 —— 把它记成 route 会让「这个控件能去哪些页」失真。
// (实测台账里有 3 条这样的:map-pin.png / d_smapicon.png / icon_map6.png)
const ASSET_PATH = /\.(?:png|jpe?g|gif|svg|webp|ico|mp3|wav|m4a|mp4|json|wxss|wxml|wxs)$/i;

function extractTargets(source) {
  // shortcut: 仅识别此白名单音频拼接；新增资源构造时扩展解析和正负控，真实导航仍保留。
  source = source.replace(/(function bundledMerchantGameAudioPath\(path\)\s*\{[\s\S]*?if \(typeof path !== 'string' \|\| !path\.startsWith\('\/audio\/merchant-games\/'\)\) return '';\s*)const relocated = '\/pages\/play' \+ path;(\s*return merchantGameAssetSet\.has\(relocated\) \? relocated : '';\s*\})/g,
    '$1const relocated = path;$2')
  const routes = [...source.matchAll(/["'](\/?(?:pages|subpackage[A-Za-z0-9_-]*)\/[^"']+)["']/g)]
    .map((m) => m[1].replace(/^\//, ''))
    .filter((route) => !ASSET_PATH.test(route))
  const apis = [...source.matchAll(/["'](\/api\/[^"'?#\s]+)[^"']*["']/g)].map((m) => m[1])
  const apiContracts = []
  for (const match of source.matchAll(/\b(?:this\.)?([A-Za-z_$][\w$]*(?:Post|Put|Patch|Delete|Get)[A-Za-z_$\d]*)\s*\(\s*["'](\/api\/[^"']+)["']/g)) {
    const helper = match[1]
    const method = /Post/i.test(helper) ? 'POST' : /Put/i.test(helper) ? 'PUT'
      : /Patch/i.test(helper) ? 'PATCH' : /Delete/i.test(helper) ? 'DELETE' : 'GET'
    apiContracts.push({ url: match[2], method, auth: 'authenticated', source: 'named-helper' })
  }
  for (const match of source.matchAll(/\burl\s*:\s*([^,\n}]+)/g)) {
    const expression = match[1]
    const parts = [...expression.matchAll(/["']([^"']*)["']/g)].map((item) => item[1])
    if (!parts.length || !parts[0].startsWith('/api/')) continue
    const dynamic = /\+/.test(expression)
    const url = dynamic ? parts.join('{param}') : parts[0]
    const nearby = source.slice(match.index, match.index + 400)
    const methodMatch = nearby.match(/\bmethod\s*:\s*["']([A-Za-z]+)["']/)
    apiContracts.push({ url, method: methodMatch ? methodMatch[1].toUpperCase() : 'GET', auth: 'authenticated' })
  }
  // 反引号模板字面量 URL（例：`url: `/api/merchant/aftercare/respond?refundId=${id}``）。
  // 旧引号分支解析不了它，会把真实网络动作的 primaryApis 清空，再被“无 API 就抹掉 read/write”
  // 的降级规则误降成 state。这里按真实 token 边界取值：从 /api/ 起、到 ? / # 止，${...} 归一为
  // {param}，method 就近读取；只处理 /api/ 请求，不把页面路由模板当成接口。
  for (const match of source.matchAll(/\burl\s*:\s*`([^`]*)`/g)) {
    const inner = match[1]
    const apiIndex = inner.indexOf('/api/')
    if (apiIndex < 0) continue
    const head = inner.slice(apiIndex)
    const queryIndex = head.search(/[?#]/)
    const path = queryIndex >= 0 ? head.slice(0, queryIndex) : head
    const url = /\$\{/.test(path)
      ? path.replace(/\$\{[^}]*\}/g, '{param}').replace(/(?:\{param\})+/, '{param}')
      : path
    const nearby = source.slice(match.index, match.index + 400)
    const methodMatch = nearby.match(/\bmethod\s*:\s*["']([A-Za-z]+)["']/)
    apiContracts.push({
      url,
      method: methodMatch ? methodMatch[1].toUpperCase() : 'GET',
      auth: 'authenticated',
      source: 'template-literal',
    })
  }
  return {
    routes: [...new Set(routes)].sort(),
    apis: [...new Set(apis)].sort(),
    apiContracts,
  }
}

function appPageRoutes() {
  const parsed = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'))
  const routes = [...(parsed.pages || [])]
  ;(parsed.subPackages || parsed.subpackages || []).forEach((pkg) => {
    ;(pkg.pages || []).forEach((page) => routes.push(`${String(pkg.root || '').replace(/\/$/, '')}/${page}`))
  })
  return [...new Set(routes.map((route) => route.replace(/^\//, '')))]
}

function resolveComponentJson(ownerJson, request) {
  const value = String(request || '')
  if (!value || value.startsWith('plugin://') || value.startsWith('ext://')) return null
  const absolute = value.startsWith('/')
    ? path.join(ROOT, value.slice(1)) : path.resolve(path.dirname(ownerJson), value)
  const candidates = [absolute, `${absolute}.json`, path.join(absolute, 'index.json')]
  const file = candidates.find((candidate) => fs.existsSync(candidate) && fs.statSync(candidate).isFile())
  return file && file.startsWith(`${ROOT}${path.sep}`) ? rel(file).replace(/\.json$/, '') : null
}

function componentHostDependencies(ownerJson, parsed, wxmlSource) {
  const tags = scanTags(wxmlSource)
  const dependencies = []
    Object.entries(parsed.usingComponents || {}).forEach(([tagName, request]) => {
      const component = resolveComponentJson(ownerJson, request)
      if (!component) return
      const targets = [component]
      const placeholderTag = (parsed.componentPlaceholder || {})[tagName]
      if (placeholderTag && placeholderTag !== 'view' &&
          Object.prototype.hasOwnProperty.call(parsed.usingComponents || {}, placeholderTag)) {
        const placeholder = resolveComponentJson(ownerJson, parsed.usingComponents[placeholderTag])
        if (placeholder) targets.push(placeholder)
      }
      const matching = tags.filter((tag) => tag.name === tagName)
      matching.forEach((tag, occurrence) => {
        const tagSource = wxmlSource.slice(tag.start, tag.end + 1)
        const attrs = parseAttrs(tagSource)
        const id = staticAttr(attrs.id)
        const className = staticAttr(attrs.class)
        const selector = id ? `#${id}` : (className ? `${tagName}.${className.trim().split(/\s+/)[0]}` : tagName)
        const sameSelectorIndex = matching.slice(0, occurrence).filter((candidate) => {
          const source = wxmlSource.slice(candidate.start, candidate.end + 1)
          const candidateAttrs = parseAttrs(source)
          const candidateId = staticAttr(candidateAttrs.id)
          const candidateClass = staticAttr(candidateAttrs.class)
          const candidateSelector = candidateId ? `#${candidateId}`
            : (candidateClass ? `${tagName}.${candidateClass.trim().split(/\s+/)[0]}` : tagName)
          return candidateSelector === selector
        }).length
        // Placeholder props/locator are inherited from the rendered carrier tag.
        targets.forEach((target) => dependencies.push({
          component: target, segment: { selector, index: sameSelectorIndex },
        }))
      })
    })
  return dependencies
}

function discoverComponentHosts() {
  const graph = new Map()
  const jsonFiles = []
  const walk = (dir) => {
    if (!fs.existsSync(dir)) return
    fs.readdirSync(dir, { withFileTypes: true }).forEach((entry) => {
      if (entry.name === 'node_modules' || entry.name.startsWith('.')) return
      const file = path.join(dir, entry.name)
      if (entry.isDirectory()) walk(file)
      else if (entry.isFile() && entry.name.endsWith('.json')) jsonFiles.push(file)
    })
  }
  ;['pages', 'components', ...fs.readdirSync(ROOT).filter((name) => name.startsWith('subpackage'))]
    .forEach((name) => walk(path.join(ROOT, name)))
  jsonFiles.forEach((file) => {
    let parsed
    try { parsed = JSON.parse(fs.readFileSync(file, 'utf8')) } catch (_) { return }
    const owner = rel(file).replace(/\.json$/, '')
    const wxmlFile = path.join(ROOT, `${owner}.wxml`)
    const wxmlSource = fs.existsSync(wxmlFile) ? fs.readFileSync(wxmlFile, 'utf8') : ''
    const dependencies = componentHostDependencies(file, parsed, wxmlSource)
    graph.set(owner, dependencies)
  })
  const hosts = new Map()
  appPageRoutes().forEach((pageRoute) => {
    const visit = (node, pathSegments, ancestors) => {
      ;(graph.get(node) || []).forEach(({ component, segment }) => {
        if (ancestors.has(component)) return
        const componentPath = pathSegments.concat(segment)
        if (!hosts.has(component)) hosts.set(component, new Map())
        const key = JSON.stringify(componentPath)
        hosts.get(component).set(`${pageRoute}\0${key}`, { route: pageRoute, segments: componentPath })
        visit(component, componentPath, new Set([...ancestors, component]))
      })
    }
    visit(pageRoute, [], new Set([pageRoute]))
  })
  return new Map([...hosts].map(([component, paths]) => [component, [...paths.values()].sort((a, b) => {
    return a.route.localeCompare(b.route) || JSON.stringify(a.segments).localeCompare(JSON.stringify(b.segments))
  })]))
}

function isSelfOnlyWriteApi(api) {
  return SELF_ONLY_WRITE_APIS.has(api) || SELF_ONLY_WRITE_API_RE.test(api)
    || /^\/api\/v1\/community\/(?:posts|comments)\/\{param\}\/actions(?:\/(?:LIKE|BOOKMARK))?$/.test(api)
}

function communityAliasesForSource(source, jsFile) {
  const masked = maskNonCodeSafely(source)
  return [...source.matchAll(/(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*require\(['"]([^'"]+)['"]\)\.createClient\s*\(/g)]
    .filter((m) => /^(?:const|let|var)\b/.test(masked.slice(m.index))
      && resolveSourceDependency(rel(jsFile), m[2], ['.js', '/index.js']) === 'utils/community-post-api.js').map((m) => m[1])
}

// Resolve only real community adapter calls; legacy URLs are commands, not HTTP routes.
function communityRequestContracts(source, aliases) {
  const contracts = []
  const commands = new Set()
  const base = '/api/v1/community'
  const add = (path, method, branch) => contracts.push({ url: base + path, method,
    auth: 'authenticated', source: 'community-adapter-call', adapterBranch: branch })
  const masked = maskNonCodeSafely(source)
  for (const alias of aliases) {
    const pattern = new RegExp(`\\b${alias.replace(/[$]/g, '\\$&')}\\.request\\s*\\(\\s*\\{`, 'g')
    for (const match of source.matchAll(pattern)) {
      if (!masked.slice(match.index).startsWith(alias + '.request')) continue
      const start = match.index + match[0].lastIndexOf('{')
      let depth = 1, end = start + 1
      for (; end < masked.length && depth; end += 1) {
        if (masked[end] === '{') depth += 1
        if (masked[end] === '}') depth -= 1
      }
      if (depth) throw new Error('community request object incomplete')
      const call = source.slice(start, end)
      const url = call.match(/\burl\s*:\s*['"]([^'"]+)['"]/)?.[1]
      if (!url) throw new Error('community request URL must be literal')
      if (!/^\/api\/(creativesquare|comment)\//.test(url)) continue
      commands.add(url)
      switch (url) {
        case '/api/creativesquare/list': {
          const dataMatch = call.match(/\bdata\s*:\s*([A-Za-z_$][\w$]*|\{)/)
          let dataSource = ''
          if (dataMatch) {
            if (dataMatch[1] === '{') dataSource = call.slice(dataMatch.index)
            else {
              const name = dataMatch[1].replace(/[$]/g, '\\$&')
              const declaration = [...source.matchAll(new RegExp(`(?:const|let|var)\\s+${name}\\s*=\\s*\\{`, 'g'))]
                .find((m) => /^(?:const|let|var)\b/.test(masked.slice(m.index)))
              if (declaration) dataSource = source.slice(declaration.index)
            }
          }
          // Only the request's data object, not its success callback or another request.
          if (dataSource.includes('{')) {
            const begin = dataSource.indexOf('{'), clean = maskNonCodeSafely(dataSource)
            let level = 1, close = begin + 1
            for (; close < clean.length && level; close++) { if (clean[close] === '{') level++; if (clean[close] === '}') level--; }
            dataSource = dataSource.slice(begin, close)
          }
          const possibilities = (field) => {
            const value = maskNonCodeSafely(dataSource).match(new RegExp(`\\b${field}\\s*:\\s*([^,}]+)`))?.[1]?.trim()
            if (value == null || /^(?:0|false|null)$/.test(value)) return [false]
            if (/^(?:1|true)$/.test(value)) return [true]
            return [false, true]
          }
          const favorite = possibilities('favorite_only'), own = possibilities('is_my')
          if (favorite.includes(true)) add('/me/bookmarks', 'GET', 'bookmarks')
          if (favorite.includes(false)) {
            if (own.includes(false)) add('/feeds/LATEST', 'GET', 'latest')
            if (own.includes(true)) add('/me/posts', 'GET', 'own')
          }
          break
        }
        case '/api/comment/list': add('/posts/{param}/comments', 'GET', 'comments'); break
        case '/api/creativesquare/info': add('/posts/{param}', 'GET', 'detail'); break
        case '/api/creativesquare/like': case '/api/creativesquare/bookmark': {
          const type = url.endsWith('/like') ? 'LIKE' : 'BOOKMARK'
          add('/posts/{param}/actions', 'POST', 'enable-' + type)
          add('/posts/{param}/actions/' + type, 'DELETE', 'disable-' + type); break
        }
        case '/api/comment/like':
          add('/comments/{param}/actions', 'POST', 'enable-LIKE'); add('/comments/{param}/actions/LIKE', 'DELETE', 'disable-LIKE'); break
        case '/api/creativesquare/delete': add('/posts/{param}', 'DELETE', 'expected-version'); break
        case '/api/comment/delete': add('/posts/{param}/comments/{param}', 'DELETE', 'expected-version'); break
        case '/api/comment/add': add('/posts/{param}/comments', 'POST', 'create-or-reply'); break
        case '/api/creativesquare/report': case '/api/comment/report':
          add('/capabilities', 'GET', 'report-taxonomy'); add('/reports', 'POST', 'confirmed-report'); break
        case '/api/creativesquare/action':
          add('/guidelines/active', 'GET', 'publish-guideline'); add('/guidelines/ack', 'POST', 'confirmed-guideline')
          add('/posts/{param}', 'GET', 'edit-version'); add('/media/register', 'POST', 'new-upload-receipt')
          add('/posts', 'POST', 'create-draft'); add('/posts/{param}', 'PATCH', 'edit-expected-version')
          add('/posts/{param}/publish', 'POST', 'draft-only-publish'); break
        default: throw new Error(`未登记 community adapter 指令: ${url}`)
      }
    }
  }
  return { commands, contracts }
}

function communityRequestPlan(contracts) {
  const community = contracts.filter((c) => c.adapterBranch)
  if (!community.length) return null
  const byBranch = {}, outcomes = {}
  const request = (c) => ({ urlTemplate: c.url, method: c.method, kind: c.method === 'GET' ? 'read' : 'write' })
  const named = (branch) => request(community.find((c) => c.adapterBranch === branch))
  const plan = (name, sequence) => {
    byBranch[name] = sequence
    outcomes[name] = sequence.map(() => 'success')
  }
  if (community.some((c) => c.adapterBranch === 'publish-guideline')) {
    // A media-N branch proves N registrations, not an arbitrary-length upload loop.
    for (const mode of ['create', 'edit-published', 'edit-draft']) {
      for (let count = 0; count <= 6; count++) {
        const sequence = [named('publish-guideline'), named('confirmed-guideline')]
        if (mode !== 'create') sequence.push(named('edit-version'))
        for (let n = 0; n < count; n++) sequence.push(named('new-upload-receipt'))
        sequence.push(named(mode === 'create' ? 'create-draft' : 'edit-expected-version'))
        if (mode !== 'edit-published') sequence.push(named('draft-only-publish'))
        plan(`${mode}-media-${count}`, sequence)
      }
    }
    plan('success', byBranch['create-media-0'])
    byBranch['forbidden-owner'] = byBranch['edit-published-media-0']
    outcomes['forbidden-owner'] = ['success', 'success', 'success', 'forbidden-owner']
  } else if (community.some((c) => c.adapterBranch === 'report-taxonomy')) {
    plan('success', [named('report-taxonomy'), named('confirmed-report')])
  } else {
    community.forEach((c) => plan(c.adapterBranch, [request(c)]))
    const writes = community.filter((c) => c.method !== 'GET')
    const versioned = writes.filter((c) => c.adapterBranch === 'expected-version')
    const enabled = writes.filter((c) => /^enable-/.test(c.adapterBranch))
    const toggled = writes.length === 2 && enabled.length === 1
      && writes.some((c) => c.adapterBranch === enabled[0].adapterBranch.replace(/^enable-/, 'disable-'))
    // Conditional readback must not replace the write that authorizes success.
    const primary = versioned.length === 1 ? versioned[0]
      : writes.length === 1 ? writes[0] : toggled ? enabled[0] : writes.length === 0 ? community[0] : null
    if (!primary) throw new Error('ambiguous community write authority: declare an explicit request plan')
    plan('success', byBranch[primary.adapterBranch])
  }
  // A normal write can precede adapter readback (comment follow -> refreshed comments).
  // Keep that existing write; resolving its readback must not erase the authority step.
  const precedingWrites = contracts.filter((c) => !c.adapterBranch && c.method !== 'GET'
    && !AUXILIARY_APIS.has(c.url) && (isWriteApi(c.url) || SELF_ONLY_WRITE_APIS.has(c.url))).map(request)
  if (precedingWrites.length) {
    Object.keys(byBranch).forEach((branch) => {
      byBranch[branch] = precedingWrites.concat(byBranch[branch])
      outcomes[branch] = precedingWrites.map(() => 'success').concat(outcomes[branch])
    })
  }
  const success = byBranch.success
  plan('duplicate-trigger', success)
  for (const failure of ['error', 'timeout']) {
    byBranch[failure] = success.slice(0, 1); outcomes[failure] = [failure]
    for (let index = 1; index < success.length; index++) {
      const branch = `stage-${index + 1}-${failure}`
      byBranch[branch] = success.slice(0, index + 1)
      outcomes[branch] = success.slice(0, index).map(() => 'success').concat(failure)
    }
  }
  return { requiredRequests: success, requiredRequestsByBranch: byBranch,
    requiredRequestOutcomes: outcomes, branches: Object.keys(byBranch).filter((b) => b !== 'forbidden-owner') }
}

/** Prove the existing Page/Component _request adapter before binding a literal caller URL to its method. */
function pageRequestContracts(source, jsFile) {
  const code = maskNonCodeSafely(source)
  const calls = [...source.matchAll(/\b(?:this|that|self)\._request\s*\(\s*['"](\/api\/[^'"]+)['"]/g)]
    .filter(call => /^(?:this|that|self)\._request/.test(code.slice(call.index)))
  if (!calls.length) return []
  const wrapper = handlerSlices(jsFile).get('_request')
  const signature = wrapper && wrapper.match(/^\s*_request\s*\(\s*([A-Za-z_$][\w$]*)[^)]*\)\s*\{/)
  if (!signature) return []
  const masked = maskNonCodeSafely(wrapper)
  const sends = [...wrapper.matchAll(/\bapp\.sendRequest\s*\(\s*\{/g)]
    .filter(send => masked.slice(send.index).startsWith('app.sendRequest'))
  if (sends.length !== 1) return []
  const param = signature[1].replace(/[$]/g, '\\$&')
  const preamble = masked.slice(signature[0].length, sends[0].index)
  if (new RegExp(`\\b${param}\\s*(?:[+*/-]?=|\\+\\+|--)|\\b(?:const|let|var)\\s+${param}\\b|\\([^)]*\\b${param}\\b[^)]*\\)\\s*=>`).test(preamble)) return []
  const open = wrapper.indexOf('{', sends[0].index)
  const close = matchBrace(masked, open)
  if (close < 0) return []
  let request
  try { request = parseJavaScript('(' + wrapper.slice(open, close + 1) + ')').body[0].expression } catch (error) {
    if (error.name === 'SyntaxError') return []
    throw error
  }
  if (request.type !== 'ObjectExpression' || request.properties.some(property => property.type !== 'Property' || property.computed)) return []
  const urls = request.properties.filter(property => (property.key.name || property.key.value) === 'url')
  const methods = request.properties.filter(property => (property.key.name || property.key.value) === 'method')
  if (urls.length !== 1 || urls[0].value.type !== 'Identifier' || urls[0].value.name !== signature[1]
      || methods.length !== 1 || methods[0].value.type !== 'Literal'
      || !['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(methods[0].value.value)) return []
  return calls.map(call => ({ url: call[1], method: methods[0].value.value, auth: 'authenticated',
    source: 'local-wrapper', wrapper: '_request' }))
}

/** One control, mutually exclusive direct Promise-chain outcomes; timed status polling is never enrollment. */
function voiceConfirmedRequestPlan(relative, handler, contracts) {
  const prefixes = {
    'pages/merchant/decor/ai-npc/index.js': '/api/merchant/npc/voice',
    'pages/topic/components/cy/node-npc-form/index.js': '/api/merchant/chapter-node/npc/voice',
  }
  const prefix = prefixes[relative]
  if (!prefix || handler !== 'confirmNewVoiceRequest') return null
  const status = prefix + '/status', enroll = prefix + '/enroll', consent = '/api/compliance/consents'
  const expected = new Set([status, consent, enroll])
  if (contracts.some(contract => !expected.has(contract.url) && !AUXILIARY_APIS.has(contract.url))
      || [status, enroll].some(url => !contracts.some(contract => contract.url === url
        && contract.method === 'POST' && contract.wrapper === '_request'))
      || !contracts.some(contract => contract.url === consent && contract.method === 'POST')) {
    throw new Error(`${relative}:${handler}: 未登记或未证明的声音请求流程,禁止隐藏额外请求`)
  }
  const read = { urlTemplate: status, method: 'POST', kind: 'read' }
  const agree = { urlTemplate: consent, method: 'POST', kind: 'write' }
  const submit = { urlTemplate: enroll, method: 'POST', kind: 'write' }
  const success = [read, agree, submit, read]
  const byBranch = {
    success, 'duplicate-trigger': success,
    blocked: [], cancelled: [read], 'consent-declined': [read], 'original-no-longer-unknown': [read],
    'stale-after-read': [read], 'stale-after-consent': [read, agree],
    error: [read, agree], 'consent-timeout': [read, agree], timeout: success, 'forbidden-owner': success,
    'enroll-disabled': [read, agree, submit], 'enroll-error': success, 'refresh-error': success,
    'status-error-confirmed': success, 'status-timeout-confirmed': success,
    'status-timeout-cancelled': [read], 'status-error-cancelled': [read], 'refresh-timeout': success,
  }
  const outcomes = Object.fromEntries(Object.entries(byBranch).map(([branch, sequence]) =>
    [branch, sequence.map(() => 'success')]))
  outcomes.error = ['success', 'error']
  outcomes['consent-timeout'] = ['success', 'timeout']
  outcomes.timeout = ['success', 'success', 'timeout', 'success']
  outcomes['forbidden-owner'] = ['success', 'success', 'forbidden-owner', 'success']
  outcomes['enroll-disabled'] = ['success', 'success', 'error']
  outcomes['enroll-error'] = ['success', 'success', 'error', 'success']
  outcomes['refresh-error'] = ['success', 'success', 'success', 'error']
  outcomes['status-error-confirmed'] = ['error', 'success', 'success', 'success']
  outcomes['status-timeout-confirmed'] = ['timeout', 'success', 'success', 'success']
  outcomes['status-timeout-cancelled'] = ['timeout']
  outcomes['status-error-cancelled'] = ['error']
  outcomes['refresh-timeout'] = ['success', 'success', 'success', 'timeout']
  return { writeApis: [consent, enroll], requiredRequests: success, requiredRequestsByBranch: byBranch,
    requiredRequestOutcomes: outcomes, requiredBranches: Object.keys(byBranch) }
}

function deregisterRequestPlan(relative, handler, contracts) {
  if (relative !== 'components/cy/scene-settings-deregister/index.js' || handler !== 'apply') return null
  const consent = { urlTemplate: '/api/compliance/consents', method: 'POST', kind: 'write' }
  const apply = { urlTemplate: '/api/user/deregister/apply', method: 'POST', kind: 'write' }
  const status = { urlTemplate: '/api/user/deregister/status', method: 'GET', kind: 'read' }
  const expected = [consent, apply, status]
  for (const command of expected) if (!contracts.some(c => c.url === command.urlTemplate && c.method === command.method))
    throw new Error('注销原请求链缺少真实接口: ' + command.urlTemplate)
  if (contracts.some(c => !expected.some(e => e.urlTemplate === c.url) && !AUXILIARY_APIS.has(c.url)))
    throw new Error('注销申请出现未登记请求，不能隐藏额外写入')
  const success = [consent, apply]
  return { writeApis: [consent.urlTemplate, apply.urlTemplate], requiredRequests: success,
    requiredRequestsByBranch: { success, 'duplicate-trigger': success, blocked: [], 'consent-error': [consent],
      'verification-error': [consent], 'stale-after-consent': [consent], 'apply-blocked': success,
      error: [consent, apply, status], timeout: [consent, apply, status], 'status-error': [consent, apply, status] },
    requiredRequestOutcomes: { success: ['success','success'], 'duplicate-trigger': ['success','success'], blocked: [],
      'consent-error': ['error'], 'verification-error': ['success'], 'stale-after-consent': ['success'],
      'apply-blocked': ['success','success'], error: ['success','error','success'],
      timeout: ['success','timeout','success'], 'status-error': ['success','error','error'] },
    requiredBranches: ['success','duplicate-trigger','blocked','consent-error','verification-error','stale-after-consent','apply-blocked','error','timeout','status-error'] }
}

function extractHandlerTargets(source, jsFile, handlerName, attrs, appHelperBodies) {
  const expandedSource = [source].concat(appHelperCalls(source, appHelperBodies).map(([, body]) => body)).join('\n')
  const targets = extractTargets(expandedSource)
  // The helper contains a conditional roam preflight. Resolve only proven
  // non-roam literal inputs / app_launch helper, preserving direct readback calls
  // (merchant_redeem legitimately invokes latest) and unknown dynamic inputs.
  const consentInputs = [...source.matchAll(/\brecordConsent\s*\(\s*\{([^{}]*)\}/g)].map(match => match[1])
  const knownNoRoamAgree = consentInputs.length > 0 && consentInputs.every(input =>
    /\beventType\s*:\s*['"]REVOKE['"]/.test(input)
      || /\bscene\s*:\s*['"](?!roam_location['"])[^'"]+['"]/.test(input)
      || /\bdocType\s*:\s*['"](?!privacy_policy['"])[^'"]+['"]/.test(input))

  if (/\bapp\.requestSeparateConsent\s*\(/.test(source)) {
    targets.apis.push('/api/compliance/consents')
    targets.apiContracts.push({ url: '/api/compliance/consents', method: 'POST', auth: 'authenticated', source: 'separate-consent-call' })
  }
  // director.js 的 this._getAdapter() 返回唯一 club adapter。只映射实际调用的方法，
  // 不把整个 game-session-client 的 command/export 等端点灌入一次 receipt 读取。
  if (rel(jsFile) === 'pages/club/topic-detail/index.js'
      && /\bcreateClubGameDirectorAdapter\s*\(/.test(expandedSource)) {
    const methods = {
      load: ['/api/game/session/view', 'GET'],
      exportRecap: ['/api/game/session/recap/export', 'GET'],
      loadSharedExport: ['/api/game/session/shared/export', 'GET'],
      anchorSharedRun: ['/api/game/session/shared/export/anchor', 'POST'],
      submit: ['/api/game/session/command', 'POST'],
      readReceipt: ['/api/game/session/receipt', 'GET'],
    }
    for (const call of expandedSource.matchAll(/\b(?:this|that|self)\._getAdapter\(\)\.(\w+)\s*\(/g)) {
      if (!methods[call[1]]) throw new Error(`未登记的 club director adapter 方法: ${call[1]}`)
      const [url, method] = methods[call[1]]
      targets.apis.push(url)
      targets.apiContracts.push({ url, method, auth: 'authenticated', source: 'club-director-adapter-call' })
      if (call[1] === 'load') {
        targets.apis.push('/api/game/session/shared/control')
        targets.apiContracts.push({ url: '/api/game/session/shared/control', method: 'GET', auth: 'authenticated', source: 'club-director-adapter-call' })
      }
    }
  }
  const fileSource = fs.readFileSync(jsFile, 'utf8')
  if (rel(jsFile) === 'components/cy/scene-settings-deregister/index.js') {
    const code = maskNonCodeSafely(source)
    for (const call of source.matchAll(/\bsendUiStateRequest\(app,\s*['"](\/api\/[^'"]+)['"],\s*\{/g)) {
      if (!code.slice(call.index).startsWith('sendUiStateRequest')) continue
      const open = call.index + call[0].lastIndexOf('{'), close = matchBrace(code, open)
      const method = source.slice(open, close + 1).match(/^\{\s*method:\s*['"](GET|POST)['"]/)
      if (close < 0 || !method) throw new Error('注销请求没有明确 HTTP 合同: ' + call[1])
      targets.apiContracts.push({ url: call[1], method: method[1], auth: 'authenticated', source: 'deregister-ui-state-call' })
    }
  }
  const pageRequests = pageRequestContracts(source, jsFile)
  targets.apis.push(...pageRequests.map(contract => contract.url))
  targets.apiContracts.push(...pageRequests)
  const communityAliases = communityAliasesForSource(fileSource, jsFile)
  const communityTargets = communityRequestContracts(expandedSource, communityAliases)
  targets.apis = targets.apis.filter((url) => !communityTargets.commands.has(url))
  targets.apiContracts = targets.apiContracts.filter((c) => !communityTargets.commands.has(c.url))
  targets.apis.push(...communityTargets.contracts.map((c) => c.url))
  targets.apiContracts.push(...communityTargets.contracts)
  for (const declaration of fileSource.matchAll(/\bfunction\s+([A-Za-z_$][\w$]*)\s*\(([^)]*)\)\s*\{/g)) {
    const helperName = declaration[1]
    const urlParam = declaration[2].split(',')[0].trim()
    if (!urlParam || !new RegExp(`\\b${helperName.replace(/[$]/g, '\\$&')}\\s*\\(`).test(expandedSource)) continue
    const tail = fileSource.slice(declaration.index, declaration.index + 1600)
    const end = tail.search(/\n}\s*(?:\n|$)/)
    const body = end >= 0 ? tail.slice(0, end + 2) : tail
    const methodMatch = body.match(/\bmethod\s*:\s*["']([A-Za-z]+)["']/)
    const urlBinding = new RegExp(`\\burl\\s*:\\s*${urlParam.replace(/[$]/g, '\\$&')}\\b`).test(body)
      || (urlParam === 'url' && /(?:^|[,\n{])\s*url\s*,/.test(body))
    const communityWrapper = communityAliases.some((alias) => new RegExp(`\\b${alias}\\.request\\s*\\(`).test(maskNonCodeSafely(body)))
    if (!urlBinding || (!methodMatch && !communityWrapper)) continue
    const callPattern = new RegExp(`\\b${helperName.replace(/[$]/g, '\\$&')}\\s*\\(\\s*["'](\\/api\\/[^"']+)["']`, 'g')
    for (const call of expandedSource.matchAll(callPattern)) {
      if (!maskNonCodeSafely(expandedSource).slice(call.index).startsWith(helperName)) continue
      if (communityWrapper) {
        const tail = expandedSource.slice(call.index + call[0].length)
        const dataName = tail.match(/^\s*,\s*([A-Za-z_$][\w$]*)/)?.[1]
        const synthetic = `community.request({url:'${call[1]}',data:${dataName || '{}'}});\n` + expandedSource
        const resolved = communityRequestContracts(synthetic, ['community'])
        resolved.commands.forEach((url) => communityTargets.commands.add(url))
        targets.apis.push(...resolved.contracts.map((c) => c.url))
        targets.apiContracts.push(...resolved.contracts)
        continue
      }
      targets.apiContracts.push({
        url: call[1], method: methodMatch[1].toUpperCase(), auth: 'authenticated', source: 'local-wrapper',
      })
    }
  }
  if (handlerName === 'retryList') {
    const listName = String((attrs && attrs['data-list']) || '')
    if (listName === 'recommendedTopicList') targets.apis.push('/api/topic/list')
    if (listName === 'nearbyActivityList' || listName === 'upcomingActivityList') targets.apis.push('/api/activity/list')
  }
  const appendDependencyTargets = (scanFile, inlineSource) => {
    const jsSource = fs.readFileSync(scanFile, 'utf8')
    const dependencies = []
    for (const match of jsSource.matchAll(/(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*require\(['"]([^'"]+)['"]\)/g)) {
      dependencies.push({ names: [match[1]], request: match[2] })
    }
    for (const match of jsSource.matchAll(/(?:const|let|var)\s*\{([^}]+)\}\s*=\s*require\(['"]([^'"]+)['"]\)/g)) {
      const namedImports = match[1].split(',').map((name) => name.trim().split(/\s*:\s*/))
      dependencies.push({
        names: namedImports.map((parts) => parts.at(-1)).filter(Boolean),
        namedImports,
        request: match[2],
      })
    }
    const inlineCode = maskNonCodeSafely(inlineSource)
    for (const match of inlineSource.matchAll(/\brequire\(\s*['"]([^'"]+)['"]\s*\)\.([A-Za-z_$][\w$]*)\s*\(/g)) {
      if (inlineCode.slice(match.index).startsWith('require')) {
        dependencies.push({ names: [], request: match[1], inlineExports: [match[2]] })
      }
    }
    dependencies.forEach(({ names, request, namedImports, inlineExports }) => {
      const dependency = resolveSourceDependency(rel(scanFile), request, ['.js', '/index.js'])
      // 恢复实例的方法调用(this._recovery.query() 等)不出现模块导入名，须先于导入名过滤判定。
      if (dependency && recoveryInstanceTargets(dependency, scanFile, expandedSource, targets)) return
      if (!inlineExports && !names.some((alias) => namedImports
        ? new RegExp(`(^|[^\\w$])${alias.replace(/[$]/g, '\\$&')}(?![\\w$])`).test(maskNonCodeSafely(expandedSource))
        : new RegExp(`\\b${alias.replace(/[$]/g, '\\$&')}(?:\\.[A-Za-z_$][\\w$]*)?\\s*\\(`).test(expandedSource))) return
      if (!dependency) return
      if (dependency === 'utils/community-post-api.js') return
      // X5（10-28）：发票拆成 5 页，各页经 subpackageMember/invoice/base.js 混入同一套 read/mutate。
      if (['utils/user-invoice.js', 'subpackageMember/invoice/base.js'].includes(dependency)
          && /^subpackageMember\/invoice(?:-[a-z-]+)?\/index\.js$/.test(rel(scanFile))) {
        // Resolve the page's finite dispatch arguments instead of importing every invoice write.
        const adapter = fs.readFileSync(path.join(ROOT, 'utils/user-invoice.js'), 'utf8')
        const endpoints = new Map([...adapter.matchAll(/case '([^']+)':[^\n]+url: '([^']+)'/g)].map(match => [match[1], match[2]]))
        for (const call of expandedSource.matchAll(/this\.(?:read|mutate)\(\s*'([^']+)'|invoice\.send\(\s*app,\s*'([^']+)'/g)) {
          if (!/^(?:this|invoice)\./.test(maskNonCodeSafely(expandedSource).slice(call.index))) continue
          const url = endpoints.get(call[1] || call[2])
          if (!url) throw new Error(`未登记的发票接口: ${call[1] || call[2]}`)
          targets.apis.push(url)
          targets.apiContracts.push({ url, method: 'POST', auth: 'authenticated', source: 'invoice-wrapper-call' })
        }
        return
      }
      if (dependency === 'utils/deregister-flow.js'
          && rel(scanFile) === 'components/cy/scene-settings-deregister/index.js'
          && !/\bmethods\b/.test(maskNonCodeSafely(expandedSource))) {
        // handlerSlices already proved and expanded the exact method plus its local helper closure.
        // Re-adding the module here would turn initialData() into all three unrelated write commands.
        return
      }
      if (dependency === 'utils/verification-scan.js' && namedImports && namedImports.length === 1
          && namedImports[0][0] === 'DYN_TTL_MS') {
        const dependencySource = fs.readFileSync(path.join(ROOT, dependency), 'utf8')
        const ast = parseJavaScript(dependencySource)
        const ttl = ast.body.find(node => node.type === 'VariableDeclaration' && node.kind === 'const'
          && node.declarations.length === 1 && node.declarations[0].id.name === 'DYN_TTL_MS'
          && node.declarations[0].init.type === 'Literal' && Number.isFinite(node.declarations[0].init.value))
        const local = namedImports[0].at(-1).replace(/[$]/g, '\\$&')
        const callable = new RegExp('\\b' + local + '\\s*\\(').test(maskNonCodeSafely(expandedSource))
        const pureModule = ast.body.every(node => node.type === 'FunctionDeclaration'
          || node === ttl
          || node.type === 'ExpressionStatement' && node.expression.type === 'AssignmentExpression'
            && dependencySource.slice(node.expression.left.start, node.expression.left.end) === 'module.exports'
            && node.expression.right.type === 'ObjectExpression'
            && node.expression.right.properties.every(property => !property.computed && property.value.type === 'Identifier'))
        if (ttl && pureModule && !callable && /module\.exports\s*=\s*\{\s*resolveVerificationScan,\s*DYN_TTL_MS\s*\}/.test(dependencySource)) return
      }
      if (dependency === 'utils/game-session-client.js') {
        const methods = {
          loadProjection: ['/api/game/session/view', 'GET'],
          loadClubRecapExport: ['/api/game/session/recap/export', 'GET'],
          loadSharedControl: ['/api/game/session/shared/control', 'GET'],
          loadSharedExport: ['/api/game/session/shared/export', 'GET'],
          submitAction: ['/api/game/session/command', 'POST'],
          readReceipt: ['/api/game/session/receipt', 'GET'],
        }
        const called = new Set(inlineExports || [])
        if (namedImports) {
          namedImports.forEach((parts) => {
            const alias = parts.at(-1).replace(/[$]/g, '\\$&')
            if (new RegExp(`\\b${alias}\\s*\\(`).test(expandedSource)) called.add(parts[0])
          })
        } else {
          names.forEach((alias) => {
            const pattern = new RegExp(`\\b${alias.replace(/[$]/g, '\\$&')}\\.(\\w+)\\s*\\(`, 'g')
            for (const call of expandedSource.matchAll(pattern)) called.add(call[1])
          })
        }
        for (const methodName of called) {
          if (methodName === 'normalizeClubRecap' || methodName === 'normalizeClubRecapExport') continue
          if (!methods[methodName]) throw new Error(`未登记的 game-session-client 方法: ${methodName}`)
          const [url, method] = methods[methodName]
          targets.apis.push(url)
          targets.apiContracts.push({ url, method, auth: 'authenticated', source: 'game-session-client-call' })
        }
        return
      }
      // 2026-09-17 第二轮拍板 22:play-run-session 同时有本机快照(无请求)与服务端会话三种请求。
      // 整份依赖扫描会让「调了 readPausedRun」的每个点击都带上 /save 写入;与 game-session-client 同法按方法取端点。
      if (dependency === 'pages/play/utils/play-run-session.js' || dependency === 'utils/play-run-session.js') {
        const methods = {
          saveServerPausedRun: ['/api/play/run-session/save', 'POST'],
          clearServerRunSession: ['/api/play/run-session/clear', 'POST'],
          readServerPausedRun: ['/api/play/run-session', 'GET'],
        }
        ;(namedImports || []).forEach((parts) => {
          const endpoint = methods[parts[0]]
          if (!endpoint || !new RegExp(`\\b${parts.at(-1).replace(/[$]/g, '\\$&')}\\s*\\(`).test(expandedSource)) return
          targets.apis.push(endpoint[0])
          targets.apiContracts.push({ url: endpoint[0], method: endpoint[1], auth: 'authenticated', source: 'play-run-session-call' })
        })
        if (!namedImports) throw new Error('play-run-session 必须按具名导入使用,台账才能按方法取端点')
        return
      }
      // ui-state-request 是“端点白名单 + 统一静默错误策略”边界。把整份依赖源码
      // 塞进每个调用者会将 switch 中 13 个互斥端点全部污染到单次点击，制造假的
      // 多写链路。这里只读取调用点第二个参数中的 /api 字面量；未知/动态端点已由
      // wrapper 自身 fail-closed，不能靠全文件字符串扫描来猜。
      if (dependency === 'utils/ui-state-request.js') {
        names.forEach((alias) => {
          const callPattern = new RegExp(`\\b${alias.replace(/[$]/g, '\\$&')}\\s*\\(\\s*[^,]+,\\s*([^,]+),\\s*\\{`, 'g')
          for (const call of expandedSource.matchAll(callPattern)) {
            const endpointExpression = call[1]
            const endpoints = [...endpointExpression.matchAll(/["'](\/api\/[^"']+)["']/g)].map((item) => item[1])
            const nearby = expandedSource.slice(call.index, call.index + 500)
            const methodMatch = nearby.match(/\bmethod\s*:\s*["']([A-Za-z]+)["']/)
            endpoints.forEach((url) => {
              targets.apis.push(url)
              targets.apiContracts.push({
                url,
                method: methodMatch ? methodMatch[1].toUpperCase() : 'GET',
                auth: 'authenticated',
                source: 'ui-state-wrapper-call',
              })
            })
          }
        })
        return
      }
      const dependencyFile = path.join(ROOT, dependency)
      const dependencyTargets = extractTargets(namedImports
        ? namedDependencySource(dependencyFile, namedImports, expandedSource)
        : namespaceDependencySource(dependencyFile, names, expandedSource, inlineExports))
      targets.routes.push(...dependencyTargets.routes)
      targets.apis.push(...dependencyTargets.apis)
      targets.apiContracts.push(...dependencyTargets.apiContracts)
    })
  }
  appendDependencyTargets(jsFile, source)
  const calledAppHelpers = appHelperCalls(source, appHelperBodies)
  if (calledAppHelpers.length) appendDependencyTargets(path.join(ROOT, 'app.js'),
    calledAppHelpers.map(([, body]) => body).join('\n'))
  targets.apis = targets.apis.filter((url) => !communityTargets.commands.has(url))
  targets.apiContracts = targets.apiContracts.filter((c) => !communityTargets.commands.has(c.url))
  if (!/['"]\/api\/compliance\/consents\/latest['"]/.test(source)
      && (knownNoRoamAgree || /\bapp\.agreeUsageConsent\s*\(/.test(source))) {
    targets.apis = targets.apis.filter(url => url !== '/api/compliance/consents/latest')
    targets.apiContracts = targets.apiContracts.filter(contract => contract.url !== '/api/compliance/consents/latest')
  }
  let apiContracts = [...new Map(targets.apiContracts.map((item) => [`${item.url}\0${item.method || ''}`, item])).values()]
    .sort((a, b) => a.url.localeCompare(b.url) || String(a.method).localeCompare(String(b.method)))
  const templatePrefixes = new Set(apiContracts.filter((item) => item.url.includes('{param}'))
    .map((item) => item.url.split('{param}')[0]))
  const apis = [...new Set(targets.apis)].filter((api) => !templatePrefixes.has(api)).sort()
  const declared = [...new Set(apis.concat(apiContracts.map((item) => item.url)))].sort()
  const contractedUrls = new Set(apiContracts.map((item) => item.url))
  apis.filter((api) => !contractedUrls.has(api)).forEach((api) => {
    apiContracts.push({
      url: api,
      method: WRITE_RE.test(handlerName) || isWriteApi(api) ? 'POST' : 'GET',
      auth: 'authenticated',
      source: 'handler-default',
    })
  })
  apiContracts = reconcileServerContracts(apiContracts)
  apiContracts.sort((a, b) => a.url.localeCompare(b.url) || String(a.method).localeCompare(String(b.method)))
  const contracted = [...new Set(apiContracts.map((item) => item.url))].sort()
  return {
    routes: [...new Set(targets.routes)].sort(),
    apis: declared,
    primaryApis: contracted.filter((api) => !AUXILIARY_APIS.has(api)).sort(),
    auxiliaryApis: contracted.filter((api) => AUXILIARY_APIS.has(api)).sort(),
    apiContracts,
  }
}

// Native page handlers have no WXML binding; reuse the same resolver without adding controls.
function targetsForHandler(jsFile, handlerName) {
  const slices = handlerSlices(jsFile)
  if (!slices.has(handlerName)) throw new Error(`未找到实际 handler: ${handlerName}`)
  return extractHandlerTargets(expandHandlerBody(handlerName, slices), jsFile, handlerName, {},
    discoverAppHelperBodies(path.join(ROOT, 'app.js')))
}

// W8 原请求恢复实例:按实例方法取端点(见各分支注释)。返回 true 表示已精确处理，调用方不再做整份依赖扫描。
function recoveryInstanceTargets(dependency, scanFile, expandedSource, targets) {
  // W8 原请求恢复(队伍/报名/官方活动命令):utils/team-event-command.js 按 createRecovery 的 domain 三选一发请求，
  // 实例方法 start/retry → 原命令，query → /query，cancel → /cancel；pending/setTarget/acknowledge/dispose 只读写本机记录。
  // 只有本页 domain 全是字面量、且只以 this._recovery.<方法>( 静态调用时才按方法取端点；
  // 动态 domain 或 this._recovery[…] 动态调用仍走下面的整份依赖保守扫描。
  if (dependency === 'utils/team-event-command.js') {
    const rawSource = fs.readFileSync(scanFile, 'utf8')
    const pageSource = maskNonCodeSafely(rawSource)
    const domainExprs = [...rawSource.matchAll(/\bcreateRecovery\(\s*\{[^}]*?\bdomain\s*:\s*([^,}]+)/g)].map(match => match[1].trim())
    // domain 只接受字面量，或「条件 ? 字面量 : 字面量」(两者都列，宿主属性决定实际一条)。
    const domainSets = domainExprs.map(expr => {
      const literal = /^'([A-Z]+)'$/.exec(expr)
      const choice = /^[\w.$]+\s*\?\s*'([A-Z]+)'\s*:\s*'([A-Z]+)'$/.exec(expr)
      return literal ? [literal[1]] : choice ? [choice[1], choice[2]] : null
    })
    const literalDomains = domainSets.every(Boolean) ? domainSets.flat() : []
    // 本地别名(const recovery = this._recovery)同样是实例调用。
    const aliases = [...new Set([...pageSource.matchAll(/\b([A-Za-z_$][\w$]*)\s*=\s*this\._recovery\b(?!\s*=)/g)].map(match => match[1]))]
    const receiver = ['this\\._recovery'].concat(aliases.map(alias => alias.replace(/[$]/g, '\\$&'))).join('|')
    const calls = [...expandedSource.matchAll(new RegExp(`(?:^|[^\\w$.])(?:${receiver})\\.(\\w+)\\s*\\(`, 'g'))].map(match => match[1])
    if (domainExprs.length && literalDomains.length && !/\b_recovery\s*\[/.test(pageSource)) {
      const routes = { TEAM: '/api/team/command', SIGNUP: '/api/official/signup-command', EVENT: '/api/official/command' }
      const methods = { start: '', retry: '', query: '/query', cancel: '/cancel', pending: null, setTarget: null, acknowledge: null, dispose: null }
      for (const name of calls) {
        if (!Object.prototype.hasOwnProperty.call(methods, name)) throw new Error(`未登记的 team-event-command 方法: ${name}`)
        if (methods[name] === null) continue
        new Set(literalDomains).forEach(domain => {
          const url = (routes[domain] || routes.EVENT) + methods[name]
          targets.apis.push(url)
          targets.apiContracts.push({ url, method: 'POST', auth: 'authenticated', source: 'team-event-command-call' })
        })
      }
      return true
    }
  }
  // W8 商家评价回复原请求恢复:createReplyRecovery 构造与 sync/pending/suspend/resume 只读本机恢复记录、不发请求;
  // 只有实例的 submit(按调用处 action 字面量取回复/改回复/删回复;无字面量时三条保守全列)与 recover(原请求回读)发请求。
  // 整份依赖扫描会让每个调了 syncReplyRecovery 的读取(重试、加载更多)都带上三条回复写入。
  if (dependency === 'pages/merchant/reviews/reply-recovery.js' && rel(scanFile) === 'pages/merchant/reviews/index.js') {
    const submitByAction = {
      MERCHANT_REVIEW_REPLIED: ['/api/merchant/reviews/reply', 'POST'],
      MERCHANT_REVIEW_REPLY_UPDATED: ['/api/merchant/reviews/reply/update', 'POST'],
      MERCHANT_REVIEW_REPLY_DELETED: ['/api/merchant/reviews/reply/delete', 'POST'],
    }
    const methods = {
      submit: Object.values(submitByAction),
      recover: [['/api/merchant/reviews/reply/receipt', 'POST']],
      sync: [], pending: [], suspend: [], resume: [],
    }
    for (const call of expandedSource.matchAll(/\bthis\._replyRecovery\.(\w+)\s*\(([^)]*)\)/g)) {
      if (!methods[call[1]]) throw new Error(`未登记的 reply-recovery 方法: ${call[1]}`)
      const actions = call[1] === 'submit' ? [...call[2].matchAll(/'(MERCHANT_REVIEW_[A-Z_]+)'/g)].map(match => match[1]) : []
      if (actions.some(action => !submitByAction[action])) throw new Error(`未登记的回复动作: ${actions.join(',')}`)
      ;(actions.length ? actions.map(action => submitByAction[action]) : methods[call[1]]).forEach(([url, method]) => {
        targets.apis.push(url)
        targets.apiContracts.push({ url, method, auth: 'authenticated', source: 'reply-recovery-call' })
      })
    }
    return true
  }
  return false
}

function buildLedger() {
  nonCodeMasks.clear()
  const roles = loadRoles()
  const registeredRoutes = new Set(readAppRoutes().map((route) => route.pagePath))
  const { map } = buildJsToWxmlMap(ROOT)
  // 跨文件只信 receiver 明确为 app/getApp() 的 App() 真源；Page/Component 同名方法不能全局串线。
  const appFile = path.join(ROOT, 'app.js')
  const externalHelperNames = discoverExternalHelperNames([appFile])
  const appHelperBodies = discoverAppHelperBodies(appFile)
  const componentHosts = discoverComponentHosts()
  const controls = []

  ;[...map.entries()].sort(([a], [b]) => rel(a).localeCompare(rel(b))).forEach(([jsFile, wxmlFiles]) => {
    const handlerEntries = extractOwnHandlerEntries(jsFile)
    const handlerSources = new Map(handlerEntries.map((entry) => [entry.name, `${rel(entry.file || jsFile)}:${entry.line}`]))
    const slices = handlerSlices(jsFile)
    const ownerJson = jsFile.replace(/\.js$/, '.json')
    const ownerConfig = fs.existsSync(ownerJson) ? JSON.parse(fs.readFileSync(ownerJson, 'utf8')) : {}
    wxmlFiles.slice().sort().forEach((wxmlFile) => {
      const source = fs.readFileSync(wxmlFile, 'utf8')
      const tags = scanTags(source)
      const route = routeFor(wxmlFile, jsFile, registeredRoutes)
      if (!route.startsWith('component:') && !roles.has(route)) {
        throw new Error(`${route}: 未进入当前 route-matrix，拒绝默认成 player`)
      }
      const hostComponentPaths = route.startsWith('component:')
        ? (componentHosts.get(route.slice('component:'.length)) || []) : []
      const hostRoutes = [...new Set(hostComponentPaths.map((item) => item.route))].sort()
      // 未被任何 app page（含组件依赖闭包）引用的孤儿组件不属于可操作产品分母。
      if (route.startsWith('component:') && hostRoutes.length === 0) return
      findEventBindings(source).flatMap((binding, bindingIndex) =>
        eventHandlerNames(binding.value).map(name => ({ binding, bindingIndex, name }))
      ).forEach(({ binding, bindingIndex, name }) => {
        const tag = tags.find((candidate) => candidate.start <= binding.offset && binding.offset <= candidate.end)
        const tagSource = tag ? source.slice(tag.start, tag.end + 1) : ''
        const attrs = parseAttrs(tagSource)
        const component = tag && resolveComponentJson(ownerJson, (ownerConfig.usingComponents || {})[tag.name])
        const loadFailure = loadFailureEventVisibility(component, tagSource, attrs)
        if (loadFailure.excluded) return
        const rawAriaLabel = attrs['aria-label'] || attrs['accessibility-label'] || null
        const variantKey = `${rel(jsFile)}:${name}`
        const variants = (ACTION_VARIANTS.get(variantKey) || [{ id: null, handler: name, predicate: null }])
          .filter((variant) => !variant.events || variant.events.includes(binding.attrName))
        variants.forEach((variant) => {
        const body = expandHandlerBody(variant.sourceHandler || variant.handler, slices)
        const targets = extractHandlerTargets(body, jsFile, variant.handler, attrs, appHelperBodies)
        if (variant.apis) {
          const allowed = new Set(variant.apis)
          const serverContracts = serverApiContracts()
          targets.apis = [...allowed].sort()
          targets.primaryApis = [...allowed].filter((api) => !AUXILIARY_APIS.has(api)).sort()
          targets.auxiliaryApis = [...allowed].filter((api) => AUXILIARY_APIS.has(api)).sort()
          targets.apiContracts = [...allowed].map((api) => {
            const existing = targets.apiContracts.find((contract) => contract.url === api)
            if (existing) return existing
            const methods = serverContracts.get(normalizeApiTemplate(api))
            if (!methods || methods.size !== 1) throw new Error(`${variantKey}: variant API ${api} 缺少唯一后端 method 合同`)
            return { url: api, method: [...methods.keys()][0], auth: 'authenticated', source: 'server-controller' }
          })
        }
        let effectClasses = variant.effectClasses || inferEffectClasses(
          variant.handler, body, appHelperBodies, targets, slices.get(variant.handler),
        )
        if (!targets.primaryApis.length && effectClasses.some((kind) => kind === 'read' || kind === 'write')) {
          effectClasses = effectClasses.filter((kind) => kind !== 'read' && kind !== 'write')
          if (!effectClasses.length) {
            effectClasses = /\b(?:this\.)?setData\s*\(|\bwx\.(?:showModal|showActionSheet)\s*\(|\b(?:cy)?[mM]odal\.show\s*\(/.test(body)
              ? ['state'] : ['other']
          }
        }
        const baseActionClass = ['write', 'read', 'navigation', 'state', 'other']
          .find((kind) => effectClasses.includes(kind)) || 'other'
        const externalCapabilities = variant.externalCapabilities
          || externalCapabilitiesFor(
            expandCapabilityBody(variant.sourceHandler || variant.handler, slices), externalHelperNames)
        const actionClass = externalCapabilities.length ? 'external' : baseActionClass
        const confirmationMode = Object.prototype.hasOwnProperty.call(variant, 'confirmationMode')
          ? variant.confirmationMode
          : modalContractForHandler(variant.handler, slices, interactionModeFor(binding.attrName))
        const voicePlan = voiceConfirmedRequestPlan(rel(jsFile), variant.handler, targets.apiContracts)
        if (voicePlan) variant = { ...variant, ...voicePlan }
        const deregisterPlan = deregisterRequestPlan(rel(jsFile), variant.handler, targets.apiContracts)
        if (deregisterPlan) variant = { ...variant, ...deregisterPlan }
        const inferredWriteApis = effectClasses.includes('write') ? targets.primaryApis.filter((api) => {
          return targets.apiContracts.some((contract) => contract.url === api && contract.method && contract.method !== 'GET'
            && (contract.adapterBranch || isWriteApi(api) || SELF_ONLY_WRITE_APIS.has(api)))
        }) : []
        const writeApis = variant.writeApis || inferredWriteApis
        if (writeApis.some((api) => !targets.primaryApis.includes(api)
          || !targets.apiContracts.some((contract) => contract.url === api && contract.method !== 'GET'))) {
          throw new Error(`${variantKey}: variant writeApis 必须命中声明的非 GET API 合同`)
        }
        targets.writeApis = writeApis.slice().sort()
        const accessBoundary = writeApis.length && writeApis.every(isSelfOnlyWriteApi)
          ? 'self' : (writeApis.length && writeApis.every((api) => CAPABILITY_WRITE_APIS.has(api))
            ? 'config-capability' : (effectClasses.includes('write') ? 'owned-resource' : null))
        // Refresh may enter a configured chapter and grant its inventory after the primary write.
        // Keep both writes in the contract; themes without a grant take a separate branch.
        if (rel(jsFile) === 'pages/play/index.js' && writeApis.length === 2
          && writeApis.includes('/api/play/theme/action') && !variant.requiredRequests && !variant.requiredRequestsByBranch) {
          const first = writeApis.find(api => api !== '/api/play/theme/action')
          const plan = twoStepWritePlan(first, '/api/play/theme/action')
          variant = { ...variant, ...plan,
            requiredRequestsByBranch: { 'without-chapter-grant': [{ urlTemplate: first, method: 'POST', kind: 'write' }] },
            requiredRequestOutcomes: { ...plan.requiredRequestOutcomes, 'without-chapter-grant': ['success'] },
            requiredBranches: plan.requiredBranches.concat(['without-chapter-grant']) }
        }
        const communityPlan = communityRequestPlan(targets.apiContracts)
        if (communityPlan && !variant.requiredRequests && !variant.requiredRequestsByBranch) {
          variant = { ...variant, ...communityPlan,
            requiredBranches: [...new Set(requiredBranchesFor(actionClass, baseActionClass,
              externalCapabilities, effectClasses, accessBoundary).concat(communityPlan.branches))] }
        }
        const isRoamAgree = rel(jsFile) === 'pages/roam/index.js'
          && (['goStart', 'goStartAndShoot'].includes(variant.handler) || variant.handler === 'toggleGps' && variant.id === 'start')
        if (isRoamAgree) {
          const branches = requiredBranchesFor(actionClass, baseActionClass, externalCapabilities, effectClasses, accessBoundary)
          const plan = roamAgreeRequestPlan()
          // GO obtains its one-shot position before recording; its delayed chooser
          // can fail after the record. toggleGps records before starting the SDK.
          const noRecord = variant.handler === 'toggleGps' ? []
            : branches.filter(branch => /denied$/.test(branch)
              && capabilityFailureContract(externalCapabilities).requiredCapabilityByBranch[branch] === 'getLocation')
          variant = { ...variant, ...plan,
            requiredRequestsByBranch: { ...plan.requiredRequestsByBranch,
              ...Object.fromEntries(noRecord.map(branch => [branch, [{ urlTemplate: '/api/compliance/consents', method: 'POST', kind: 'write' }]])) },
            requiredRequestOutcomes: { ...plan.requiredRequestOutcomes,
              ...Object.fromEntries(branches.filter(branch => /(?:cancelled|denied)$/.test(branch) && !noRecord.includes(branch))
                .map(branch => [branch, ['success', 'success']])),
              ...Object.fromEntries(noRecord.map(branch => [branch, ['not-called']])) },
            requiredBranches: [...new Set(branches.concat(['record-error-after-latest', 'record-timeout-after-latest',
              'baseline-retry', 'baseline-retry-error', 'baseline-retry-timeout']))],
          }
        }
        const isRoamRevoke = name === 'withdrawRoamLocationConsent' && [
          'pages/privacy/index.js', 'pages/shezhi/components/privacy-sheet/index.js',
        ].includes(rel(jsFile))
        if (isRoamRevoke) {
          const request = { urlTemplate: '/api/compliance/consents', method: 'POST', kind: 'write' }
          const branches = requiredBranchesFor(actionClass, baseActionClass, externalCapabilities, effectClasses, accessBoundary)
          variant = { ...variant, requiredRequests: [request],
            requiredRequestOutcomes: Object.fromEntries(branches.map(branch => [branch,
              [branch === 'error' || branch === 'timeout' ? branch : 'success']])),
            requiredReadbackLinksByBranch: Object.fromEntries(branches.map(branch => [branch, [{
              fromPath: 'observed.roamRevocation.principalFingerprint',
              toPath: 'authoritativeReadback.accessScope.principalFingerprint',
            }].concat(branch === 'error' || branch === 'timeout' ? [] : [
              { fromPath: 'observed.roamRevocation.recordRequest.body.requestId', toPath: 'observed.roamRevocation.recordEcho.requestId' },
              { fromPath: 'observed.roamRevocation.recordRequest.body.requestId', toPath: 'authoritativeReadback.authority.after.requestId' },
              ...['docType', 'scene', 'eventType'].map(field => ({
                fromPath: `observed.roamRevocation.recordRequest.body.${field}`, toPath: `authoritativeReadback.authority.after.${field}`,
              })),
            ])])),
          }
        }
        const baseControlId = `${rel(wxmlFile)}:${binding.line}:${binding.attrName}:${name}:${bindingIndex}`
        const controlId = variant.id ? `${baseControlId}:variant:${variant.id}` : baseControlId
        controls.push({
          id: controlId,
          route,
          hostRoutes,
          hostComponentPaths,
          role: route.startsWith('component:') ? 'inherited' : roles.get(route),
          entry: { wxml: rel(wxmlFile), js: rel(jsFile) },
          variant: variant.id ? {
            id: variant.id,
            predicate: variant.predicate,
            predicateScope: variant.predicateScope || 'page',
            componentHostSelector: variant.componentHostSelector || null,
            locatorInstance: variant.locatorInstance || null,
          } : null,
          requiredRequests: variant.requiredRequests || null,
          requiredRequestsByBranch: variant.requiredRequestsByBranch || null,
          requiredRequestOutcomes: variant.requiredRequestOutcomes || null,
          requiredAuthorityLinks: variant.requiredAuthorityLinks || null,
          requiredAuthorityLinksByBranch: variant.requiredAuthorityLinksByBranch || null,
          requiredReadbackLinksByBranch: variant.requiredReadbackLinksByBranch || null,
          requiredCapabilityByBranch: {
            ...capabilityFailureContract(externalCapabilities).requiredCapabilityByBranch,
            ...(variant.requiredCapabilityByBranch || {}),
          },
          requestMode: variant.requestMode || 'ordered',
          confirmationMode,
          control: {
            tag: tag ? tag.name : 'unknown',
            event: binding.attrName,
            handler: name,
            line: binding.line,
            selectorHint: attrs.id ? `#${attrs.id}` : (attrs.class ? `.${attrs.class.trim().split(/\s+/)[0]}` : `${tag ? tag.name : 'unknown'}[${binding.attrName}]`),
            visibleCondition: attrs['wx:if'] || attrs['wx:elif'] || attrs.hidden || null,
            ...(loadFailure.condition ? { loadFailureCondition: loadFailure.condition } : {}),
            disabledCondition: attrs.disabled || null,
            ariaLabel: staticAttr(rawAriaLabel),
            ariaLabelExpression: dynamicAttr(rawAriaLabel),
            ariaRole: staticAttr(attrs['aria-role'] || attrs.role),
            ariaRoleExpression: dynamicAttr(attrs['aria-role'] || attrs.role),
            ariaDisabled: staticAttr(attrs['aria-disabled']),
            ariaDisabledExpression: dynamicAttr(attrs['aria-disabled']),
            ariaExpanded: staticAttr(attrs['aria-expanded']),
            ariaExpandedExpression: dynamicAttr(attrs['aria-expanded']),
            ariaChecked: staticAttr(attrs['aria-checked']),
            ariaCheckedExpression: dynamicAttr(attrs['aria-checked']),
          },
          actionClass,
          interactionMode: interactionModeFor(binding.attrName),
          baseActionClass,
          effectClasses,
          accessBoundary,
          externalCapabilities,
          successCapabilities: successCapabilitiesFor(externalCapabilities),
          ...(isRoamRevoke ? { successCapabilityContract: ROAM_REVOKE_CONTRACT } : {}),
          targets,
          expectedReadback: buildExpectedReadback(actionClass, baseActionClass, effectClasses),
          requiredBranches: [...new Set((variant.requiredBranches || requiredBranchesFor(
            actionClass, baseActionClass, externalCapabilities, effectClasses, accessBoundary,
          )).concat(confirmationMode ? ['modal-cancelled'] : []))],
          requiredPlatforms: requiredPlatformsFor(actionClass),
          externalGate: actionClass === 'external' ? 'real-device-required' : null,
          evidence: { status: 'pending', staticSource: handlerSources.get(name) || `${rel(jsFile)}:0` },
        })
        })
      })
    })
  })

  controls.sort((a, b) => a.id.localeCompare(b.id))
  nonCodeMasks.clear()
  return {
    schemaVersion: 7,
    generatedBy: 'scripts/uiaudit/build-action-ledger.js --write',
    controlCount: controls.length,
    verificationRule: 'success/toast/截图不是完成；每条动作必须按 expectedReadback 读取对端事实。',
    controls,
  }
}

function serialized() {
  return JSON.stringify(buildLedger(), null, 2) + '\n'
}

function resolveSourceDependency(fromRelative, request, extensions) {
  if (!request || /^(?:plugin:|wx:|https?:)/.test(request)) return null
  const base = request.startsWith('/')
    ? request.slice(1) : path.posix.normalize(path.posix.join(path.posix.dirname(fromRelative), request))
  const candidates = path.posix.extname(base) ? [base] : (extensions || []).map((extension) => `${base}${extension}`)
  return candidates.find((relative) => fs.existsSync(path.join(ROOT, relative))) || null
}

function directSourceDependencies(relative) {
  const absolute = path.join(ROOT, relative)
  const source = fs.readFileSync(absolute, 'utf8')
  const dependencies = []
  if (relative.endsWith('.wxss')) {
    for (const match of source.matchAll(/@import\s+['"]([^'"]+)['"]/g)) {
      const resolved = resolveSourceDependency(relative, match[1], ['.wxss'])
      if (resolved) dependencies.push(resolved)
    }
  } else if (relative.endsWith('.json')) {
    try {
      const parsed = JSON.parse(source)
      Object.values(parsed.usingComponents || {}).forEach((request) => {
        const base = resolveSourceDependency(relative, request, ['.json'])
        if (!base) return
        const componentBase = base.replace(/\.json$/, '')
        ;['.wxml', '.js', '.wxss', '.json'].forEach((extension) => {
          const candidate = `${componentBase}${extension}`
          if (fs.existsSync(path.join(ROOT, candidate))) dependencies.push(candidate)
        })
      })
    } catch (error) {
      // JSON 真源自身仍进入 digest；语法错误由既有编译/静态门禁报告。
    }
  } else if (relative.endsWith('.wxml')) {
    for (const match of source.matchAll(/<(?:import|include|wxs)\b[^>]*\bsrc\s*=\s*['"]([^'"]+)['"]/g)) {
      const resolved = resolveSourceDependency(relative, match[1], ['.wxml', '.wxs'])
      if (resolved) dependencies.push(resolved)
    }
  } else if (relative.endsWith('.js')) {
    for (const match of source.matchAll(/(?:require\s*\(|\bfrom\s*)['"]([^'"]+)['"]/g)) {
      if (!match[1].startsWith('.') && !match[1].startsWith('/')) continue
      const resolved = resolveSourceDependency(relative, match[1], ['.js', '/index.js'])
      if (resolved) dependencies.push(resolved)
    }
  }
  return [...new Set(dependencies)]
}

function sourceFilesFor(control) {
  const wxmlBase = control.entry.wxml.replace(/\.wxml$/, '')
  const seeds = [
    control.entry.wxml, control.entry.js, `${wxmlBase}.wxss`, `${wxmlBase}.json`,
    'app.wxss',
  ].filter((relative) => fs.existsSync(path.join(ROOT, relative)))
  const seen = new Set()
  const queue = seeds.slice()
  const drain = () => {
    while (queue.length) {
      const relative = queue.shift()
      if (seen.has(relative)) continue
      seen.add(relative)
      directSourceDependencies(relative).forEach((dependency) => {
        if (!seen.has(dependency)) queue.push(dependency)
      })
    }
  }
  drain()
  // 只有真实调用 getApp() 的动作才绑定全局请求/鉴权运行时；app.json 的路由与角色漂移
  // 已由 inventory 自身锁定，不能让无关新增路由把所有既有组件证据一起判旧。
  const usesAppRuntime = [...seen].some((relative) => relative.endsWith('.js')
    && /\bgetApp\s*\(\s*\)/.test(fs.readFileSync(path.join(ROOT, relative), 'utf8')))
  if (usesAppRuntime && fs.existsSync(path.join(ROOT, 'app.js'))) {
    queue.push('app.js')
    drain()
  }
  const files = [...seen].sort()
  return files
}

function sourceDigestFor(control, overrides) {
  const hash = crypto.createHash('sha256')
  sourceFilesFor(control).forEach((relative) => {
    hash.update(relative)
    hash.update('\0')
    hash.update(overrides && Object.prototype.hasOwnProperty.call(overrides, relative)
      ? overrides[relative] : fs.readFileSync(path.join(ROOT, relative)))
    hash.update('\0')
  })
  return hash.digest('hex')
}

function sourceDigestAtCommit(control, sha) {
  const hash = crypto.createHash('sha256')
  const repoRoot = path.resolve(ROOT, '..')
  try {
    sourceFilesFor(control).forEach((relative) => {
      const repoPath = `${path.basename(ROOT)}/${relative}`.split(path.sep).join('/')
      hash.update(relative)
      hash.update('\0')
      hash.update(execFileSync('git', ['show', `${sha}:${repoPath}`], {
        cwd: repoRoot,
        maxBuffer: 16 * 1024 * 1024,
        stdio: ['ignore', 'pipe', 'ignore'],
      }))
      hash.update('\0')
    })
    return hash.digest('hex')
  } catch (error) {
    return null
  }
}

function sha256File(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
}

function commitExists(sha) {
  try {
    execFileSync('git', ['cat-file', '-e', `${sha}^{commit}`], { cwd: path.resolve(ROOT, '..'), stdio: 'ignore' })
    return true
  } catch (error) {
    return false
  }
}

function validateArtifact(id, artifact, errors) {
  if (!artifact || typeof artifact.uri !== 'string' || !artifact.uri.trim()
      || !/^[0-9a-f]{64}$/i.test(String(artifact.sha256 || ''))) {
    errors.push(`${id}: artifacts 每项必须含非空 uri 与 64 位 sha256`)
    return
  }
  if (/^https?:\/\//.test(artifact.uri)) {
    errors.push(`${id}: 远端 artifact 未回读内容，release evidence 只接受可读取的仓库内文件`)
    return
  }
  if (path.isAbsolute(artifact.uri)) {
    errors.push(`${id}: 本地 artifact 必须是小程序根目录内的相对路径`)
    return
  }
  const resolved = path.resolve(ROOT, artifact.uri)
  if (!resolved.startsWith(`${ROOT}${path.sep}`) || !fs.existsSync(resolved) || !fs.statSync(resolved).isFile()) {
    errors.push(`${id}: artifact 不存在或越出小程序目录: ${artifact.uri}`)
    return
  }
  if (sha256File(resolved) !== artifact.sha256.toLowerCase()) {
    errors.push(`${id}: artifact sha256 与文件内容不一致: ${artifact.uri}`)
  }
}

function isRecognizedMedia(uri, content) {
  const png = /\.png$/i.test(uri) && isValidPng(content)
  const jpeg = /\.jpe?g$/i.test(uri) && content.length >= 4
    && content[0] === 0xff && content[1] === 0xd8 && content.at(-2) === 0xff && content.at(-1) === 0xd9
  const video = /\.(?:mp4|mov)$/i.test(uri) && content.length >= 12
    && content.subarray(4, 8).toString('ascii') === 'ftyp'
  return png || jpeg || video
}

function validateExternalArtifact(id, platform, branch, artifact, errors) {
  validateArtifact(id, artifact, errors)
  const uri = artifact && String(artifact.uri || '')
  if (!uri.startsWith('scripts/uiaudit/evidence/device/')) {
    errors.push(`${id}: ${platform}/${branch} 真机 artifact 必须位于 scripts/uiaudit/evidence/device/`)
  }
  if (!/\.(?:png|jpe?g|mp4|mov)$/i.test(uri)) {
    errors.push(`${id}: ${platform}/${branch} 真机 artifact 必须是截图或录屏`)
    return
  }
  const file = path.resolve(ROOT, uri)
  if (file.startsWith(`${ROOT}${path.sep}`) && fs.existsSync(file)) {
    const content = fs.readFileSync(file)
    if (!isRecognizedMedia(uri, content)) errors.push(`${id}: ${platform}/${branch} artifact 内容不是可识别的媒体文件`)
  }
}

function validateLocalProvenance(control, observation, errors) {
  const id = control.id
  const provenance = observation.provenance || {}
  if (observation.generation !== provenanceGeneration(provenance)) {
    errors.push(`${id}: ${observation.branch} provenance generation 与驱动清单不一致`)
  }
  const runnerUri = 'tests/automator/action-evidence-runner.js'
  const runnerFile = path.join(ROOT, runnerUri)
  const expectedTransport = control.interactionMode === 'tap'
    ? ((/^cy-/.test(String(control.control && control.control.tag || ''))
      || String(control.route || '').startsWith('component:'))
      ? 'miniprogram-automator custom-component dispatchEvent' : 'miniprogram-automator Element.tap')
    : 'event-contract-driver'
  if (provenance.runner !== runnerUri || provenance.transport !== expectedTransport
      || !fs.existsSync(runnerFile) || provenance.runnerSha256 !== sha256File(runnerFile)) {
    errors.push(`${id}: ${observation.branch} 缺少当前 runner provenance`)
  }
  const validateTool = (field, prefix, required) => {
    const uri = String(provenance[field] || '')
    const file = path.resolve(ROOT, uri)
    if (!required && !uri) return
    if (!uri.startsWith(prefix) || !file.startsWith(`${ROOT}${path.sep}`) || !fs.existsSync(file)
        || provenance[`${field}Sha256`] !== sha256File(file)) {
      errors.push(`${id}: ${observation.branch} 缺少当前 ${field} provenance`)
    }
  }
  validateTool('fixtureDriver', 'tests/automator/fixtures/', true)
  validateTool('branchDriver', 'tests/automator/branches/', observation.branch !== 'success')
  const effects = control.effectClasses && control.effectClasses.length
    ? control.effectClasses : [control.baseActionClass || control.actionClass]
  validateTool('readbackAdapter', 'tests/automator/readbacks/', effects.includes('read') || effects.includes('write'))
  if (control.interactionMode === 'event-contract') {
    const driverUri = String(provenance.eventDriver || '')
    const driverFile = path.resolve(ROOT, driverUri)
    if (!driverUri.startsWith('tests/automator/events/') || !driverFile.startsWith(`${ROOT}${path.sep}`)
        || !fs.existsSync(driverFile) || provenance.eventDriverSha256 !== sha256File(driverFile)) {
      errors.push(`${id}: ${observation.branch} 缺少当前 event driver provenance`)
    }
  }
  if (provenance.fixtureDriver !== 'tests/automator/fixtures/controlled-backend.js'
      || (observation.branch !== 'success'
        && provenance.branchDriver !== 'tests/automator/branches/controlled-fault.js')
      || ((effects.includes('read') || effects.includes('write'))
        && provenance.readbackAdapter !== 'tests/automator/readbacks/controlled-authority.js')
      || (control.interactionMode === 'event-contract'
        && provenance.eventDriver !== 'tests/automator/events/interaction-sequence.js')) {
    errors.push(`${id}: ${observation.branch} release provenance 必须使用仓库受控标准驱动`)
  }
}

function validateControlPlaneObservation(control, observation, fixture, errors) {
  const id = control.id
  try {
    const plane = trustedControlPlane(fixture)
    const at = Date.parse(observation.verifiedAt)
    if (Number.isNaN(at)) throw new Error('observation 缺 verifiedAt')
    const identityEnvelope = observation.fixtureAttestation && observation.fixtureAttestation.controlPlaneAttestation
    const identityPayload = identityEnvelope && identityEnvelope.payload
    const runNonce = identityPayload && identityPayload.runNonce
    if (!/^[0-9a-f]{48}$/i.test(String(runNonce || ''))) throw new Error('identity attestation 缺少本次 runNonce')
    verifySignedEnvelope(identityEnvelope, {
      kind: 'fixture-identity', scenarioId: plane.scenarioId, fixtureId: fixture.id,
      actionId: id, branch: observation.branch, runNonce,
    }, 'ACTION_CONTROL_PLANE_HMAC_KEY', at)
    if (observation.branch !== 'success') {
      verifySignedEnvelope(observation.readback && observation.readback.controlPlaneAttestation, {
        kind: 'fault-status', scenarioId: plane.scenarioId, fixtureId: fixture.id,
        actionId: id, branch: observation.branch, runNonce,
      }, 'ACTION_CONTROL_PLANE_HMAC_KEY', at)
    }
    const authority = observation.readback && observation.readback.authoritativeReadback
    const authorityReceipts = authority && Array.isArray(authority.sequence) ? authority.sequence : (authority ? [authority] : [])
    if (authorityKindForControl(control)) {
      const outcomes = control.requiredRequestOutcomes
        ? control.requiredRequestOutcomes[observation.branch] : [observation.branch]
      if (!Array.isArray(outcomes) || authorityReceipts.length !== outcomes.length
          || authorityReceipts.some((receipt) => !Array.isArray(receipt.controlPlaneAttestations)
            || receipt.controlPlaneAttestations.length !== 2)) {
        throw new Error('权威回读缺少逐阶段 before/after 签名回执')
      }
      outcomes.forEach((_, requestIndex) => {
        const receipt = authorityReceipts[requestIndex]
        ;['before', 'after'].forEach((phase, phaseIndex) => {
          const payload = verifySignedEnvelope(receipt.controlPlaneAttestations[phaseIndex], {
            kind: 'authority-read', scenarioId: plane.scenarioId, fixtureId: fixture.id,
            actionId: id, branch: observation.branch, phase, requestIndex, runNonce,
          }, 'ACTION_CONTROL_PLANE_HMAC_KEY', at)
          assertSignedAuthorityFacts(
            receipt, phase, payload.facts,
            control.requestMode === 'parallel' && phase === 'after' ? authority.requestTrace : undefined,
          )
        })
      })
    }
  } catch (error) {
    errors.push(`${id}: ${observation.branch} 受控后端签名事实无效: ${error.message}`)
  }
}

function assertSignedAuthorityFacts(receipt, phase, facts, expectedRequestTrace) {
  if (!facts || stableJson(facts.request) !== stableJson(receipt.request)
      || stableJson(facts.accessScope) !== stableJson(receipt.accessScope)) {
    throw new Error(`authority ${phase} 持久化 request/accessScope 与签名 facts 不一致`)
  }
  const expectedAuthority = {
    endpoint: receipt.authority && receipt.authority.endpoint,
    resourceId: receipt.authority && receipt.authority.resourceId,
    fact: receipt.authority && receipt.authority[phase],
  }
  if (stableJson(facts.authority) !== stableJson(expectedAuthority)) {
    throw new Error(`authority ${phase} 持久化权威事实与签名 facts 不一致`)
  }
  if (phase === 'after' && (stableJson(facts.mapping) !== stableJson(receipt.mapping)
      || stableJson(facts.timeoutResolution) !== stableJson(receipt.timeoutResolution))) {
    throw new Error('authority after 持久化 mapping/timeoutResolution 与签名 facts 不一致')
  }
  if (phase === 'before' && facts.requestTrace != null
      && (!Array.isArray(facts.requestTrace) || facts.requestTrace.length !== 0)) {
    throw new Error('authority before 不得伪造尚未发生的并发 requestTrace')
  }
  if (phase === 'after' && expectedRequestTrace !== undefined
      && stableJson(facts.requestTrace) !== stableJson(expectedRequestTrace)) {
    throw new Error('并发 requestTrace 与签名 facts 不一致')
  }
}

function authorityKindForControl(control) {
  const effects = control.effectClasses && control.effectClasses.length
    ? control.effectClasses : [control.baseActionClass || control.actionClass]
  return effects.includes('write') ? 'write' : (effects.includes('read') ? 'read' : null)
}

function deviceBranchReadbackDigests(branches) {
  return Object.fromEntries((branches || []).map((item) => [
    item.name,
    crypto.createHash('sha256').update(stableJson({
      branch: item.name, captureId: item.captureId, capturedAt: item.capturedAt, readback: item.readback,
    })).digest('hex'),
  ]))
}

function assertDeviceCaptureWindow(capture, now, maxAgeMs) {
  const capturedAt = Date.parse(capture && capture.capturedAt)
  const completedAt = Date.parse(capture && capture.completedAt)
  const referenceNow = Number(now == null ? Date.now() : now)
  if (!Number.isFinite(maxAgeMs) || maxAgeMs <= 0
      || !Number.isFinite(capturedAt) || !Number.isFinite(completedAt) || completedAt < capturedAt
      || capturedAt > referenceNow + 5 * 60 * 1000 || completedAt > referenceNow + 5 * 60 * 1000
      || referenceNow - capturedAt > maxAgeMs || referenceNow - completedAt > maxAgeMs
      || completedAt - capturedAt > 4 * 60 * 60 * 1000) {
    throw new Error('capture 时间窗过期、倒置、未来或持续超过 4 小时')
  }
}

function evidenceMaxAgeMs(rawValue) {
  const hours = Number(rawValue == null || rawValue === '' ? 72 : rawValue)
  const milliseconds = hours * 60 * 60 * 1000
  if (!Number.isFinite(hours) || hours <= 0 || !Number.isFinite(milliseconds)) {
    throw new Error('ACTION_EVIDENCE_MAX_AGE_HOURS 必须是有限正数')
  }
  return milliseconds
}

function assertSignedDeviceBranchFacts(signedDigests, branches) {
  if (!signedDigests || typeof signedDigests !== 'object' || Array.isArray(signedDigests)) {
    throw new Error('device farm 签名缺少逐分支 readback digest')
  }
  const expected = deviceBranchReadbackDigests(branches)
  if (stableJson(signedDigests) !== stableJson(expected)) {
    throw new Error('逐分支 readback/captureId 与 device farm 签名 facts 不一致')
  }
}

function validateObservationReadback(control, observation, fixture, errors) {
  const id = control.id
  const readback = observation.readback
  try {
    if (observation.branch !== 'success') {
      validateBranchReceipt(control, observation.branch, readback, fixture)
      if (!(readback.uiEffect && readback.uiEffect.assertions && readback.uiEffect.assertions.failure)) {
        throw new Error('失败分支缺少精确 UI/route 恢复终态')
      }
      const branchEffects = control.effectClasses && control.effectClasses.length
        ? control.effectClasses : [control.baseActionClass || control.actionClass]
      const branchKind = branchEffects.includes('write') ? 'write' : (branchEffects.includes('read') ? 'read' : null)
      if (branchKind) {
        validateAuthorityEvidence(control, readback.authoritativeReadback, branchKind, observation.branch, observation.fixtureAttestation)
      }
      return
    }
    const effects = control.effectClasses && control.effectClasses.length
      ? control.effectClasses : [control.baseActionClass || control.actionClass]
    if (effects.includes('state') && !(readback.assertions && readback.assertions.state)) {
      throw new Error('state success 缺少精确前后状态断言')
    }
    if (effects.includes('navigation') && !(readback.assertions && readback.assertions.navigation
        && readback.changed && readback.changed.routeStack === true)) {
      throw new Error('navigation success 缺少精确 route/options 与页面栈变化')
    }
    const authorityKind = effects.includes('write') ? 'write' : (effects.includes('read') ? 'read' : null)
    if (authorityKind) {
      validateAuthorityEvidence(control, readback.authoritativeReadback, authorityKind, observation.branch, observation.fixtureAttestation)
      if (readback.authoritativeReadback.fixtureId !== fixture.id) throw new Error('服务端回读 fixtureId 不一致')
      if (effects.includes('read') && !(readback.assertions && readback.assertions.read)) {
        throw new Error('read success 缺少 page.data 精确映射断言')
      }
    }
    if (effects.includes('other') && !(readback.assertions && readback.assertions.other)) {
      throw new Error('other success 缺少动作特定 data/route 断言')
    }
  } catch (error) {
    errors.push(`${id}: ${observation.branch} 分支事实无效: ${error.message}`)
  }
}

function validateActionFixture(control, fixture, label, errors) {
  try {
    validateFixture(fixture)
    validateActionLocator(control, fixture)
    if (control.route.startsWith('component:')) {
      if (fixture.locator.componentPath !== control.route.slice('component:'.length)) {
        throw new Error('locator.componentPath 未绑定组件真源')
      }
    } else if (String(fixture.entry.route).replace(/^\//, '') !== control.route) {
      throw new Error('entry.route 未绑定动作页面')
    }
  } catch (error) {
    errors.push(`${control.id}: ${label} fixture 无效: ${error.message}`)
  }
}

function validateEvidenceOverlay(ledger, overlay) {
  const errors = []
  const externalMediaOwners = new Map()
  const localMediaOwners = new Map()
  // 缓存仅活在单次同步校验内；下次校验必重新读取真源，避免长进程内源码漂移假绿。
  const currentDigests = new Map()
  const committedDigests = new Map()
  const currentDigest = (control) => {
    const key = `${control.entry.wxml}\0${control.entry.js}`
    if (!currentDigests.has(key)) currentDigests.set(key, sourceDigestFor(control))
    return currentDigests.get(key)
  }
  const committedDigestFor = (control, sha) => {
    const key = `${sha}\0${control.entry.wxml}\0${control.entry.js}`
    if (!committedDigests.has(key)) committedDigests.set(key, sourceDigestAtCommit(control, sha))
    return committedDigests.get(key)
  }
  if (!overlay || overlay.schemaVersion !== 1 || !overlay.results || Array.isArray(overlay.results)
      || typeof overlay.results !== 'object') {
    return ['action-evidence.json 必须是 schemaVersion=1 且含 results 对象']
  }
  let maxAgeMs
  try { maxAgeMs = evidenceMaxAgeMs(process.env.ACTION_EVIDENCE_MAX_AGE_HOURS) } catch (error) {
    return [error.message]
  }
  const controls = new Map(ledger.controls.map((item) => [item.id, item]))
  Object.entries(overlay.results).forEach(([id, evidence]) => {
    const control = controls.get(id)
    if (!control) {
      errors.push(`${id}: 不在当前 inventory，可能是真源已漂移的旧证据`)
      return
    }
    if (!evidence || !['passed', 'failed', 'blocked'].includes(evidence.status)) {
      errors.push(`${id}: status 必须为 passed/failed/blocked`)
      return
    }
    if (!/^[0-9a-f]{40}$/i.test(String(evidence.sourceSha || ''))) {
      errors.push(`${id}: sourceSha 必须是 40 位 Git SHA`)
    } else if (!commitExists(evidence.sourceSha)) {
      errors.push(`${id}: sourceSha 在当前 Git 对象库中不存在`)
    }
    if (evidence.sourceDigest !== currentDigest(control)) {
      errors.push(`${id}: sourceDigest 与当前 WXML/JS 真源不一致，证据已过期`)
    }
    const committedDigest = /^[0-9a-f]{40}$/i.test(String(evidence.sourceSha || ''))
      ? committedDigestFor(control, evidence.sourceSha) : null
    if (/^[0-9a-f]{40}$/i.test(String(evidence.sourceSha || '')) && committedDigest === null) {
      errors.push(`${id}: sourceSha 提交不包含当前 WXML/JS/WXSS/JSON 真源`)
    } else if (committedDigest && evidence.sourceDigest !== committedDigest) {
      errors.push(`${id}: sourceDigest 与 sourceSha 提交中的 WXML/JS 不一致`)
    }
    const verifiedAtMs = Date.parse(evidence.verifiedAt)
    if (!evidence.verifiedAt || Number.isNaN(verifiedAtMs)) {
      errors.push(`${id}: verifiedAt 必须是可解析时间`)
    } else if (verifiedAtMs > Date.now() + 5 * 60 * 1000 || Date.now() - verifiedAtMs > maxAgeMs) {
      errors.push(`${id}: verifiedAt 超出发布证据有效期`)
    }
    if (!Array.isArray(evidence.branches) || evidence.branches.length === 0) {
      errors.push(`${id}: branches 必须记录实际执行分支`)
    } else if (evidence.status === 'passed') {
      const missing = control.requiredBranches.filter((branch) => !evidence.branches.includes(branch))
      if (missing.length) errors.push(`${id}: passed 缺少必跑分支 ${missing.join(',')}`)
    }
    if (evidence.status === 'passed' && control.actionClass !== 'external') {
      const fixture = evidence.fixture || {}
      validateActionFixture(control, fixture, 'local', errors)
      let localFixtureDigest = null
      try { localFixtureDigest = fixtureDigest(fixture) } catch (error) {}
      if (fixture.synthetic !== true || !String(fixture.id || '').trim()
          || !String(fixture.accountRole || '').trim() || !String(fixture.networkProfile || '').trim()
          || !String(fixture.backendMode || '').trim()) {
        errors.push(`${id}: passed 必须绑定 synthetic 受控 fixture`)
      }
      const attestations = Array.isArray(evidence.fixtureAttestations) ? evidence.fixtureAttestations : []
      if (attestations.length !== control.requiredBranches.length
          || attestations.some((item) => !item || item.fixtureId !== fixture.id || item.synthetic !== true
            || !String(item.identityFingerprint || '').trim() || !String(item.backendBase || '').trim()
            || !/^[0-9a-f]{40}$/i.test(String(item.backendDeploymentSha || ''))
            || !Array.isArray(item.assertions) || item.assertions.length === 0)) {
        errors.push(`${id}: passed 必须逐分支含 fixture driver 的受控账号回读 attestation`)
      }
      const backendDeployments = new Set(attestations.map((item) => item && item.backendDeploymentSha).filter(Boolean))
      if (backendDeployments.size !== 1) errors.push(`${id}: passed 各分支必须绑定同一 backendDeploymentSha`)
      const observations = Array.isArray(evidence.observations) ? evidence.observations : []
      const missingObservations = control.requiredBranches.filter((branch) => !observations.some((item) => item && item.branch === branch))
      if (missingObservations.length) errors.push(`${id}: passed 缺少分支事实 ${missingObservations.join(',')}`)
      observations.forEach((observation) => {
        if (!observation || !control.requiredBranches.includes(observation.branch)
            || !observation.readback || typeof observation.readback !== 'object') {
          errors.push(`${id}: observations 必须逐分支记录结构化 readback`)
          return
        }
        const observationAt = Date.parse(observation.verifiedAt)
        if (Number.isNaN(observationAt) || observationAt > Date.now() + 5 * 60 * 1000
            || Date.now() - observationAt > maxAgeMs) {
          errors.push(`${id}: ${observation.branch} 分支证据超出发布有效期`)
        }
        validateObservationReadback(control, observation, fixture, errors)
        validateLocalProvenance(control, observation, errors)
        validateControlPlaneObservation(control, observation, fixture, errors)
        if (!Array.isArray(observation.artifacts) || observation.artifacts.length === 0) {
          errors.push(`${id}: ${observation.branch} 分支缺少独立 artifact`)
        } else observation.artifacts.forEach((artifact) => {
          validateArtifact(id, artifact, errors)
          if (!String(artifact.uri || '').startsWith('scripts/uiaudit/evidence/artifacts/')
              || !/\.png$/i.test(String(artifact.uri || ''))) {
            errors.push(`${id}: ${observation.branch} local artifact 必须是 runner 生成的 evidence/artifacts PNG`)
          }
          const expectedToken = localFixtureDigest && crypto.createHash('sha256').update([
            id, observation.branch, localFixtureDigest, evidence.sourceSha,
          ].join('\0')).digest('hex').slice(0, 24)
          if (expectedToken && !path.basename(String(artifact.uri || '')).startsWith(`${expectedToken}-${observation.branch}-`)) {
            errors.push(`${id}: ${observation.branch} local artifact 文件名未绑定 action/branch/fixture/source`)
          }
          const owner = localMediaOwners.get(artifact.uri)
          if (owner && owner !== `${id}:${observation.branch}`) {
            errors.push(`${id}: local artifact URI 不得跨 action/branch 重放`)
          } else localMediaOwners.set(artifact.uri, `${id}:${observation.branch}`)
        })
      })
    }
    if (evidence.status === 'passed' && control.actionClass === 'external') {
      const usedMedia = new Set()
      if (!Array.isArray(evidence.executions)) {
        errors.push(`${id}: external passed 必须含 executions，并分别记录 ios/android 真机环境与分支`)
      } else control.requiredPlatforms.forEach((platform) => {
        const execution = evidence.executions.find((item) => item && item.platform === platform)
        if (!execution) {
          errors.push(`${id}: external executions 缺少 ${platform} 真机记录`)
          return
        }
        const device = execution.device || {}
        const fixture = execution.fixture || {}
        const executionAttestation = execution.fixtureAttestation
        validateActionFixture(control, fixture, `${platform} execution`, errors)
        if (!executionAttestation || executionAttestation.fixtureId !== fixture.id
            || executionAttestation.identityFingerprint == null
            || executionAttestation.accountRole !== fixture.accountRole) {
          errors.push(`${id}: ${platform} execution 缺少受控账号 fixtureAttestation`)
        }
        const capture = execution.capture || {}
        if (device.physical !== true || !String(device.model || '').trim()
            || !String(device.osVersion || '').trim() || !String(device.wechatVersion || '').trim()) {
          errors.push(`${id}: ${platform} execution 必须含 physical=true 的 model/osVersion/wechatVersion`)
        }
        if (fixture.synthetic !== true || !String(fixture.id || '').trim()
            || !String(fixture.accountRole || '').trim() || !String(fixture.networkProfile || '').trim()) {
          errors.push(`${id}: ${platform} execution 必须绑定受控 synthetic fixture 与网络条件`)
        }
        let captureWindowValid = true
        try { assertDeviceCaptureWindow(capture, Date.now(), maxAgeMs) } catch (error) { captureWindowValid = false }
        if (capture.devtools !== false || !String(capture.id || '').trim()
            || !String(capture.operator || '').trim() || !captureWindowValid) {
          errors.push(`${id}: ${platform} execution 必须含合理时间窗的 devtools=false capture id/operator/capturedAt/completedAt`)
        }
        if (!capture.provenance || capture.provenance.attestor !== 'trusted-device-farm'
            || !/^sha256:[0-9a-f]{64}$/i.test(String(capture.provenance.publicKeyFingerprint || ''))) {
          errors.push(`${id}: ${platform} execution 缺少受信 device farm 非对称签名 provenance`)
        }
        let manifest = null
        let signedMediaSha256s = null
        let signedBranchReadbackDigests = null
        if (!capture.manifest) {
          errors.push(`${id}: ${platform} execution 缺少 capture sidecar manifest`)
        } else {
          validateArtifact(id, capture.manifest, errors)
          const manifestUri = String(capture.manifest.uri || '')
          const manifestFile = path.resolve(ROOT, manifestUri)
          if (!manifestUri.startsWith('scripts/uiaudit/evidence/device/') || !manifestUri.endsWith('.json')
              || !manifestFile.startsWith(`${ROOT}${path.sep}`)) {
            errors.push(`${id}: ${platform} capture manifest 必须位于 device 证据目录`)
          } else {
            try { manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8')) } catch (error) {
              errors.push(`${id}: ${platform} capture manifest 无法解析`)
            }
          }
          if (manifest && (manifest.schemaVersion !== 1 || manifest.captureId !== capture.id
              || manifest.platform !== platform || manifest.fixtureId !== fixture.id
              || manifest.deviceModel !== device.model || manifest.actionId !== id
              || manifest.sourceSha !== evidence.sourceSha || manifest.sourceDigest !== evidence.sourceDigest
              || !Array.isArray(manifest.branches))) {
            errors.push(`${id}: ${platform} capture manifest 未绑定 action/source/capture/device/fixture`)
          }
          if (manifest) {
            try {
              const publicKeyFile = String(process.env.ACTION_DEVICE_EVIDENCE_PUBLIC_KEY_FILE || '')
              if (!path.isAbsolute(publicKeyFile) || publicKeyFile.startsWith(`${ROOT}${path.sep}`)
                  || !fs.existsSync(publicKeyFile)) throw new Error('未配置仓库外只读 device farm 公钥')
              const publicKey = fs.readFileSync(publicKeyFile)
              const fingerprint = `sha256:${crypto.createHash('sha256').update(publicKey).digest('hex')}`
              if (fingerprint !== String(process.env.ACTION_DEVICE_EVIDENCE_PUBLIC_KEY_SHA256 || '')) {
                throw new Error('device farm 公钥未命中发布环境固定指纹')
              }
              if (capture.provenance.publicKeyFingerprint !== fingerprint) throw new Error('device farm 公钥指纹不一致')
              const payload = verifyAsymmetricEnvelope(manifest.attestation, {
                kind: 'device-capture', actionId: id, captureId: capture.id, platform,
                fixtureId: fixture.id, sourceSha: evidence.sourceSha, sourceDigest: evidence.sourceDigest,
              }, publicKey, Date.parse(capture.capturedAt))
              const facts = payload.facts
              if (facts.deviceModel !== device.model || facts.osVersion !== device.osVersion
                  || facts.wechatVersion !== device.wechatVersion || facts.operator !== capture.operator
                  || facts.capturedAt !== capture.capturedAt || facts.completedAt !== capture.completedAt
                  || facts.devtools !== false || !/^sha256:[0-9a-f]{64}$/i.test(String(facts.deviceSerialFingerprint || ''))) {
                throw new Error('签名设备事实与 execution 不一致')
              }
              signedMediaSha256s = Array.isArray(facts.mediaSha256s) ? facts.mediaSha256s.slice().sort() : null
              signedBranchReadbackDigests = facts.branchReadbackDigests
            } catch (error) {
              errors.push(`${id}: ${platform} capture 缺少可信签名: ${error.message}`)
            }
          }
        }
        const branchEvidence = Array.isArray(execution.branches) ? execution.branches : []
        const missing = control.requiredBranches.filter((branch) => !branchEvidence.some((item) => item && item.name === branch))
        if (missing.length) errors.push(`${id}: ${platform} execution 缺少分支 ${missing.join(',')}`)
        branchEvidence.forEach((item) => {
          if (!item || !control.requiredBranches.includes(item.name) || !item.readback || typeof item.readback !== 'object') {
            errors.push(`${id}: ${platform} execution 含非法分支或缺少结构化 readback`)
            return
          }
          try {
            const signedBranch = signedBranchReadbackDigests && signedBranchReadbackDigests[item.name]
            const actualBranch = deviceBranchReadbackDigests([item])[item.name]
            validateBranchReceipt(control, item.name, item.readback, fixture, {
              verified: !!signedBranch && signedBranch === actualBranch,
              captureId: capture.id, sourceDigest: evidence.sourceDigest,
              principalFingerprint: executionAttestation.identityFingerprint,
              readbackDigest: crypto.createHash('sha256').update(stableJson(item.readback)).digest('hex'),
            })
            const externalEffects = control.effectClasses && control.effectClasses.length
              ? control.effectClasses : [control.baseActionClass || 'other']
            const externalKind = externalEffects.includes('write') ? 'write'
              : (externalEffects.includes('read') ? 'read' : null)
            if (externalKind) {
              validateAuthorityEvidence(
                control, item.readback.authoritativeReadback, externalKind, item.name, executionAttestation,
              )
            }
          } catch (error) {
            errors.push(`${id}: ${platform}/${item.name} 分支事实无效: ${error.message}`)
          }
          if (item.captureId !== capture.id || Number.isNaN(Date.parse(item.capturedAt))) {
            errors.push(`${id}: ${platform}/${item.name} 必须绑定 captureId 与分支时间锚点`)
          }
          if (Date.parse(item.capturedAt) < Date.parse(capture.capturedAt)
              || Date.parse(item.capturedAt) > Date.parse(capture.completedAt)) {
            errors.push(`${id}: ${platform}/${item.name} 时间锚点越出 capture 时间窗`)
          }
          if (!Array.isArray(item.artifacts) || item.artifacts.length === 0) {
            errors.push(`${id}: ${platform}/${item.name} 必须单独绑定截图或录屏`)
          } else item.artifacts.forEach((artifact) => {
            validateExternalArtifact(id, platform, item.name, artifact, errors)
            const mediaKey = String(artifact.sha256 || '').toLowerCase()
            if (usedMedia.has(mediaKey)) errors.push(`${id}: 真机媒体不得跨 platform/branch 复用: ${artifact.uri}`)
            usedMedia.add(mediaKey)
            const globalOwner = externalMediaOwners.get(mediaKey)
            if (globalOwner && globalOwner !== `${id}:${platform}:${item.name}`) {
              errors.push(`${id}: 真机媒体不得跨 action 重放: ${artifact.uri}`)
            } else externalMediaOwners.set(mediaKey, `${id}:${platform}:${item.name}`)
          })
          if (manifest) {
            const manifestBranch = manifest.branches.find((branch) => branch && branch.name === item.name)
            const expectedArtifacts = (item.artifacts || []).map((artifact) => `${artifact.uri}:${artifact.sha256}`).sort()
            const declaredArtifacts = manifestBranch && Array.isArray(manifestBranch.artifacts)
              ? manifestBranch.artifacts.map((artifact) => `${artifact.uri}:${artifact.sha256}`).sort() : []
            if (!manifestBranch || manifestBranch.capturedAt !== item.capturedAt
                || JSON.stringify(expectedArtifacts) !== JSON.stringify(declaredArtifacts)) {
              errors.push(`${id}: ${platform}/${item.name} 与 capture manifest 时间/媒体清单不一致`)
            }
          }
        })
        const executionMediaSha256s = branchEvidence.flatMap((item) => (item && item.artifacts) || [])
          .map((artifact) => String(artifact.sha256 || '').toLowerCase()).sort()
        if (!signedMediaSha256s || JSON.stringify(signedMediaSha256s) !== JSON.stringify(executionMediaSha256s)) {
          errors.push(`${id}: ${platform} 签名媒体清单与逐分支 artifacts 不一致`)
        }
        try {
          assertSignedDeviceBranchFacts(signedBranchReadbackDigests, branchEvidence)
        } catch (error) {
          errors.push(`${id}: ${platform} ${error.message}`)
        }
      })
    }
    if (!Array.isArray(evidence.artifacts) || evidence.artifacts.length === 0) {
      errors.push(`${id}: artifacts 必须至少包含一个带 sha256 的证据`)
    } else evidence.artifacts.forEach((artifact) => validateArtifact(id, artifact, errors))
    if (typeof evidence.readback !== 'string' || !evidence.readback.trim()) {
      errors.push(`${id}: readback 必须记录 DOM/页面栈/API/服务端事实`)
    }
    if (evidence.status === 'blocked') {
      const blocker = evidence.blocker
      if (!blocker || !String(blocker.reason || '').trim() || !String(blocker.owner || '').trim()
          || !/^\d{4}-\d{2}-\d{2}$/.test(String(blocker.due || ''))
          || !String(blocker.alternativeVerification || '').trim()) {
        errors.push(`${id}: blocked 必须含 reason/owner/due/alternativeVerification`)
      }
    }
    try { assertNoSensitiveEvidence(evidence) } catch (error) { errors.push(`${id}: ${error.message}`) }
  })
  return errors
}

function evidenceReadiness(ledger, overlay) {
  const results = (overlay && overlay.results) || {}
  const pending = []
  const nonPassed = []
  ledger.controls.forEach((control) => {
    const evidence = results[control.id]
    if (!evidence) pending.push(control.id)
    else if (evidence.status !== 'passed') nonPassed.push(control.id)
  })
  return { ready: pending.length === 0 && nonPassed.length === 0, pending, nonPassed }
}

function inventoryMatchesCurrent(ledger) {
  return JSON.stringify(ledger) === JSON.stringify(buildLedger())
}

function loadEvidenceOverlay() {
  if (!fs.existsSync(EVIDENCE_OVERLAY)) return null
  return JSON.parse(fs.readFileSync(EVIDENCE_OVERLAY, 'utf8'))
}

function selfTest() {
  const placeholderOwner = path.join(ROOT, 'pages/member/index/index.json')
  const carrier = '/subpackageMember/components/cy/profile/index'
  const placeholder = '/components/cy/profile-loading/index'
  const host = {
    usingComponents: { 'cy-profile': carrier, 'cy-profile-loading': placeholder },
    componentPlaceholder: { 'cy-profile': 'cy-profile-loading' },
  }
  const markup = '<cy-profile id="profile-test" viewer="self" />'
  const expectedPlaceholder = 'components/cy/profile-loading/index'
  const hasPlaceholder = (config, source = markup) =>
    componentHostDependencies(placeholderOwner, config, source)
      .some((item) => item.component === expectedPlaceholder && item.segment.selector === '#profile-test')
  const cases = [
    hasPlaceholder(host),
    !hasPlaceholder(host, '<view />'),
    !hasPlaceholder({ ...host, usingComponents: { 'cy-profile': carrier } }),
    !hasPlaceholder({ ...host, usingComponents: { ...host.usingComponents,
      'cy-profile-loading': '/components/cy/__missing_placeholder__/index' } }),
    !hasPlaceholder({ ...host, componentPlaceholder: { 'cy-profile': 'view' } }),
    inferActionClass('goDetail', "wx.navigateTo({url:'/pages/x/index'})") === 'navigation',
    inferActionClass('saveAddress', "app.sendRequest({url:'/api/user/address/add',method:'POST'})") === 'write',
    inferActionClass('loadList', "app.sendRequest({url:'/api/topic/list',method:'POST'})") === 'read',
    inferActionClass('openSheet', 'this.setData({show:true})') === 'state',
    inferActionClass('scanCode', 'wx.scanCode({})') === 'external',
    // A:注释不是代码。2026-09-22 实证:两行中文注释能把 play 页 4 个控件从 read 翻成
    //   external(「需真机、豁免常规证据」那一档),注释里根本没有 wx.* —— 但分类扫的是原始
    //   body。只抹注释、保字符串(maskNonCode 会连字符串一起抹,把 705 个控件的 write/read
    //   降级成 state,方向相反)。
    inferActionClass('loadList', "// wx.scanCode( 只是注释\napp.sendRequest({url:'/api/topic/list',method:'POST'})") === 'read',
    inferActionClass('quoteInString', "app.sendRequest({url:'/api/x/list',method:'POST'}); var s = 'wx.scanCode('") === 'read',
    // C:wx 的可注入别名。全仓只有 utils/location/location-manager.js 这么写(_wx = resolveWx(env)),
    //   不做泛化推断,只认这一个明确别名。
    inferActionClass('pick', '_wx.chooseLocation({})') === 'external',
    buildExpectedReadback('write').includes('服务端'),
  ]
  if (cases.every(Boolean)) {
    console.log('ACTION-LEDGER SELFTEST PASS')
    return 0
  }
  console.error('ACTION-LEDGER SELFTEST FAIL')
  return 1
}

function main() {
  if (process.argv.includes('--selftest')) return selfTest()
  const current = serialized()
  if (process.argv.includes('--write')) {
    fs.writeFileSync(OUTPUT, current)
    console.log(`ACTION-LEDGER WRITE PASS: ${JSON.parse(current).controlCount} controls`)
    return 0
  }
  if (!fs.existsSync(OUTPUT) || fs.readFileSync(OUTPUT, 'utf8') !== current) {
    console.error('ACTION-LEDGER FAIL: 台账缺失或已漂移；运行 npm run audit:actions:update 后审阅变更')
    return 1
  }
  const ledger = JSON.parse(current)
  const overlay = loadEvidenceOverlay()
  const evidenceErrors = validateEvidenceOverlay(ledger, overlay)
  if (evidenceErrors.length) {
    console.error(`ACTION-LEDGER FAIL: 动态证据 overlay 无效\n${evidenceErrors.join('\n')}`)
    return 1
  }
  const readiness = evidenceReadiness(ledger, overlay)
  if (process.argv.includes('--release-ready') && !readiness.ready) {
    console.error(`ACTION-LEDGER RELEASE BLOCK: pending=${readiness.pending.length}, nonPassed=${readiness.nonPassed.length}`)
    return 1
  }
  console.log(`ACTION-LEDGER PASS: ${ledger.controlCount} controls, ${Object.keys(overlay.results).length} evidence results`)
  return 0
}

if (require.main === module) process.exitCode = main()

module.exports = {
  namespaceDependencySource,
  eventHandlerNames,
  loadFailureEventVisibility,
  inferActionClass,
  inferEffectClasses,
  buildExpectedReadback,
  requiredBranchesFor,
  requiredPlatformsFor,
  interactionModeFor,
  modalContractFor,
  modalContractForHandler,
  expandHandlerBody,
  handlerSlices,
  discoverExternalHelperNames,
  serverApiContracts,
  buildLedger,
  targetsForHandler,
  communityRequestContracts,
  communityAliasesForSource,
  communityRequestPlan,
  voiceConfirmedRequestPlan,
  deregisterRequestPlan,
  isSelfOnlyWriteApi,
  isWriteApi,
  extractTargets,
  sourceFilesFor,
  sourceDigestFor,
  sourceDigestAtCommit,
  validateEvidenceOverlay,
  evidenceReadiness,
  inventoryMatchesCurrent,
  isRecognizedMedia,
  assertSignedAuthorityFacts,
  assertSignedDeviceBranchFacts,
  assertDeviceCaptureWindow,
  evidenceMaxAgeMs,
  deviceBranchReadbackDigests,
}
