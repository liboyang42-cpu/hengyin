const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const pathFor = relative => require('node:path').resolve(__dirname, '../..', relative)
const os = require('node:os')
const path = require('node:path')
const { buildLedger, discoverExternalHelperNames, targetsForHandler } = require('../../scripts/uiaudit/build-action-ledger')
let currentLedger
const ledger = () => currentLedger || (currentLedger = buildLedger())

test('队间交换真实方法进入台账，预览与核对不写入，发起确认只写一条命令', () => {
  for (const [handler, actionClass, apis] of [
    ['previewThemeTrade', 'read', ['/api/game/session/shared/preview']],
    ['checkThemeTradeReceipt', 'read', ['/api/game/session/receipt', '/api/game/session/view']],
    ['submitThemeTrade', 'write', ['/api/game/session/command', '/api/game/session/view']],
    ['onThemeTradeAction', 'write', ['/api/game/session/command', '/api/game/session/view']],
  ]) {
    const rows = ledger().controls.filter(row => row.entry.js === 'pages/play/index.js' && row.control.handler === handler)
    assert.ok(rows.length, handler)
    for (const row of rows) {
      assert.equal(row.actionClass, actionClass)
      assert.deepEqual(row.targets.primaryApis, apis)
      assert.deepEqual(row.targets.writeApis, actionClass === 'write' ? ['/api/game/session/command'] : [])
      assert.deepEqual(row.externalCapabilities, [])
      assert.match(row.evidence.staticSource, /^pages\/play\/utils\/theme-trade\.js:[1-9]\d*$/)
    }
  }
})

test('台账按混入方法真实文件提取，保留剪贴板能力与状态选择', () => {
  const rows = ledger().controls.filter(row => row.entry.wxml === 'pages/club/topic-detail/index.wxml')
  const copy = rows.find(row => row.control.handler === 'copyPhone')
  assert.ok(copy.externalCapabilities.includes('setClipboardData'))
  assert.equal(copy.actionClass, 'external')
  assert.deepEqual(copy.requiredPlatforms, ['ios', 'android'])
  assert.ok(copy.requiredBranches.includes('timeout'))
  assert.ok(copy.requiredBranches.includes('duplicate-trigger'))
  const select = rows.find(row => row.control.handler === 'onIncidentSelect')
  assert.deepEqual(select.effectClasses, ['state'])
  for (const row of rows) {
    const name = row.control.handler
    if (['onIncidentSelect', 'onStartSession', 'onReconcileUnknownWrite', 'retryUnknownWrite'].includes(name)) {
      assert.match(row.evidence.staticSource, /^pages\/club\/topic-detail\/director\.js:\d+$/, name)
    }
  }
})

test('草稿重试保留真实写入合同，不因 draft 命名降成读取', () => {
  const row = ledger().controls.find(item => item.entry.wxml === 'pages/publish/temp/index.wxml'
    && item.control.handler === 'retrySubmit' && item.variant.id === 'draft')
  assert.ok(row.effectClasses.includes('write'))
  assert.deepEqual(row.targets.writeApis, ['/api/template/draft'])
  assert.ok(row.targets.apiContracts.some(api => api.url === '/api/template/draft' && api.method === 'POST'))
  assert.ok(row.requiredBranches.includes('forbidden-owner'))
  assert.ok(row.requiredBranches.includes('duplicate-trigger'))
})

test('导演台重试追踪 command 写入，核对只读取 receipt 与刷新 view', () => {
  const rows = ledger().controls.filter(row => row.entry.wxml === 'pages/club/topic-detail/index.wxml')
  const retry = rows.find(row => row.control.handler === 'retryUnknownWrite')
  assert.ok(retry.effectClasses.includes('write'))
  assert.deepEqual(retry.targets.writeApis, ['/api/game/session/command'])
  assert.ok(retry.requiredBranches.includes('forbidden-owner'))
  assert.ok(retry.requiredBranches.includes('duplicate-trigger'))
  const read = rows.find(row => row.control.handler === 'onReconcileUnknownWrite')
  assert.ok(read.effectClasses.includes('read'))
  assert.ok(!read.effectClasses.includes('write'))
  // 投影刷新后读取 shared/control 的 PLAN_FALLBACK 可用性（只读 GET），仍不得出现写入。
  assert.deepEqual(read.targets.primaryApis, ['/api/game/session/receipt', '/api/game/session/shared/control', '/api/game/session/view'])
  assert.deepEqual(read.targets.writeApis, [])
  assert.ok(read.targets.apiContracts.every(api => api.method === 'GET'))
  assert.ok(read.requiredBranches.includes('error'))
  assert.ok(read.requiredBranches.includes('timeout'))
})

test('导演台离线记录独立登记原有 anchor 写接口，普通表单仍只读', () => {
  const rows = ledger().controls.filter(row => row.control.handler === 'openGenericOps')
  const anchor = rows.find(row => row.variant.id === 'offline-start')
  assert.deepEqual(anchor.targets.writeApis, ['/api/game/session/shared/export/anchor'])
  assert.ok(anchor.targets.apiContracts.some(api => api.url === '/api/game/session/shared/export/anchor' && api.method === 'POST'))
  assert.deepEqual(rows.find(row => row.variant.id === 'ops-form').targets.writeApis, [])
})

test('真实反引号模板 URL 动作保留读写分类与分支，不因去污降成 state', () => {
  const cases = [
    ['pages/merchant/aftercare/detail/index.js', 'submitResponse', 'write', '/api/merchant/aftercare/respond'],
    ['pages/merchant/aftercare/index.js', 'switchBucket', 'read', '/api/merchant/aftercare/list'],
    ['pages/merchant/aftercare/index.js', 'loadMore', 'read', '/api/merchant/aftercare/list'],
    ['pages/merchant/customer/detail/index.js', 'removeTag', 'write', '/api/merchant/crm/customers/{param}/tags/remove'],
    ['pages/merchant/customer/detail/index.js', 'submitTag', 'write', '/api/merchant/crm/customers/{param}/tags'],
    ['pages/merchant/customer/detail/index.js', 'hideNote', 'write', '/api/merchant/crm/customers/{param}/notes/hide'],
    ['pages/merchant/customer/detail/index.js', 'submitNote', 'write', '/api/merchant/crm/customers/{param}/notes'],
    ['pages/merchant/customer/index.js', 'retryExportStatus', 'read', '/api/merchant/crm/exports/{param}/status'],
    ['pages/merchant/reviews/index.js', 'retry', 'read', '/api/merchant/reviews/manage'],
    ['pages/merchant/reviews/index.js', 'loadMore', 'read', '/api/merchant/reviews/manage'],
  ]
  for (const [js, handler, actionClass, url] of cases) {
    const hits = ledger().controls.filter(row => row.entry.js === js && row.control.handler === handler)
    assert.ok(hits.length, `${handler} 在记录中缺项`)
    for (const row of hits) {
      assert.equal(row.actionClass, actionClass, `${handler} 读写分类被降级`)
      assert.ok(row.targets.primaryApis.includes(url), `${handler} 缺真实模板 URL ${url}`)
      for (const branch of ['error', 'timeout', 'duplicate-trigger']) {
        assert.ok(row.requiredBranches.includes(branch), `${handler} 缺 ${branch} 分支`)
      }
      if (actionClass === 'write') {
        assert.ok(row.requiredBranches.includes('forbidden-owner'), `${handler} 缺 forbidden-owner 分支`)
        assert.equal(row.accessBoundary, 'owned-resource', `${handler} 写归属边界`)
      }
    }
  }
})

test('四条商家写端点精确落入 writeApis，详情查询保留在 primaryApis 但不被当写', () => {
  const cases = [
    ['pages/merchant/aftercare/detail/index.js', 'submitResponse', '/api/merchant/aftercare/respond', '/api/merchant/aftercare/detail'],
    ['pages/merchant/customer/detail/index.js', 'submitTag', '/api/merchant/crm/customers/{param}/tags', '/api/merchant/crm/customers/{param}/detail'],
    ['pages/merchant/customer/detail/index.js', 'submitNote', '/api/merchant/crm/customers/{param}/notes', '/api/merchant/crm/customers/{param}/detail'],
    ['pages/merchant/customer/detail/index.js', 'hideNote', '/api/merchant/crm/customers/{param}/notes/hide', '/api/merchant/crm/customers/{param}/detail'],
  ]
  for (const [js, handler, writeUrl, readUrl] of cases) {
    const hits = ledger().controls.filter(row => row.entry.js === js && row.control.handler === handler)
    assert.ok(hits.length, `${handler} 在记录中缺项`)
    for (const row of hits) {
      assert.equal(row.actionClass, 'write', `${handler} 必须判写`)
      assert.deepEqual(row.targets.writeApis, [writeUrl], `${handler} writeApis 必须只含真实写端点`)
      assert.ok(row.targets.primaryApis.includes(writeUrl), `${handler} primaryApis 保留写端点`)
      assert.ok(row.targets.primaryApis.includes(readUrl), `${handler} 详情读取必须保留`)
      assert.ok(!row.targets.writeApis.includes(readUrl), `${handler} 详情读取不得冒充写`)
      assert.ok(row.requiredBranches.includes('forbidden-owner'), `${handler} 缺 forbidden-owner`)
      assert.ok(row.requiredBranches.includes('timeout'), `${handler} 缺 timeout`)
      assert.equal(row.accessBoundary, 'owned-resource', `${handler} 写归属边界`)
      const contract = row.targets.apiContracts.find(api => api.url === writeUrl)
      assert.equal(contract && contract.method, 'POST', `${handler} 写端点 method 合同`)
    }
  }
})

test('权威回读负控：绑定详情读端点冒充写被拒，绑定真实写端点被接受（实际 validator）', () => {
  const { validateAuthorityReceipt } = require('../../scripts/uiaudit/action-evidence-store')
  const attestation = { identityFingerprint: 'synthetic-principal', accountRole: 'merchant' }
  const receiptFor = (action, urlTemplate, actualUrl) => ({
    actionId: action.id,
    fixtureId: 'v8-synthetic-control',
    request: { method: 'POST', url: actualUrl, resourceId: '99', authMode: 'authenticated' },
    expectedRequest: { urlTemplate, method: 'POST' },
    authority: { endpoint: 'synthetic-only', resourceId: '99', before: { value: 1 }, after: { value: 2 } },
    assertions: ['synthetic-check'],
    accessScope: {
      principalFingerprint: attestation.identityFingerprint, role: 'merchant',
      ownerFingerprint: 'synthetic-owner', scope: 'customer:99', scopeVerified: true, decision: 'allowed',
    },
  })
  const cases = [
    ['pages/merchant/aftercare/detail/index.js', 'submitResponse', '/api/merchant/aftercare/respond', '/api/merchant/aftercare/respond?refundId=99', '/api/merchant/aftercare/detail', '/api/merchant/aftercare/detail?refundId=99'],
    ['pages/merchant/customer/detail/index.js', 'submitTag', '/api/merchant/crm/customers/{param}/tags', '/api/merchant/crm/customers/99/tags', '/api/merchant/crm/customers/{param}/detail', '/api/merchant/crm/customers/99/detail'],
    ['pages/merchant/customer/detail/index.js', 'submitNote', '/api/merchant/crm/customers/{param}/notes', '/api/merchant/crm/customers/99/notes', '/api/merchant/crm/customers/{param}/detail', '/api/merchant/crm/customers/99/detail'],
    ['pages/merchant/customer/detail/index.js', 'hideNote', '/api/merchant/crm/customers/{param}/notes/hide', '/api/merchant/crm/customers/99/notes/hide', '/api/merchant/crm/customers/{param}/detail', '/api/merchant/crm/customers/99/detail'],
  ]
  for (const [js, handler, writeTemplate, writeUrl, readTemplate, readUrl] of cases) {
    const action = ledger().controls.find(row => row.entry.js === js && row.control.handler === handler)
    assert.ok(action, `${handler} 在记录中缺项`)
    // 错误证据：把详情查询读端点绑定成写成功回读，必须被拒
    assert.throws(() => validateAuthorityReceipt(action, receiptFor(action, readTemplate, readUrl), 'write', 'success', attestation),
      /不在动作 API 真源中|未命中本次 fixture 声明/, `${handler} 详情读端点不得被接受为写`)
    // 真实写正控：绑定真实写端点且服务端状态变化，必须被接受
    assert.doesNotThrow(() => validateAuthorityReceipt(action, receiptFor(action, writeTemplate, writeUrl), 'write', 'success', attestation),
      `${handler} 真实写端点刷新必须被接受`)
  }
})

test('聊天拉黑选项读取会话对象，清空只打开确认，两者都不直接写入', () => {
  for (const id of ['block', 'clear']) {
    const row = ledger().controls.find(item => item.entry.wxml === 'subpackageB/pages/im/chat/index.wxml'
      && item.control.handler === 'onMoreSelect' && item.variant.id === id)
    assert.deepEqual(row.effectClasses, ['state'])
    assert.deepEqual(row.targets.primaryApis, id === 'block' ? ['/api/im/conversations'] : [])
    assert.deepEqual(row.targets.writeApis, [])
    // doBlock includes the guarded unblock->message refresh closed-conversation fallback.
    // Static candidates are conservative; the ordinary block branch still performs no write.
    // Auth renewal may reopen the explicit first-use agreement gate; it does not record consent here.
    // loadMessages -> decorate constructs canonical card URLs and the allowed generic coupon-wallet URL.
    // extractTargets records route literals in that source closure, not observed navigation destinations;
    // neither the block confirmation nor the clear confirmation navigates to these card destinations.
    assert.deepEqual(row.targets.routes, id === 'block'
      ? ['pages/privacy/index', 'pages/square/detail/index', 'pages/topic/index/index', 'subpackageB/pages/im/list/index', 'subpackageMember/coupon-wallet/index'] : [])
  }
})

test('解除本人拉黑真实读取对象、解除、回读消息与标读，不能登记成只读', () => {
  const row = ledger().controls.find(item => item.entry.wxml === 'subpackageB/pages/im/chat/index.wxml'
    && item.control.handler === 'onMoreSelect' && item.variant.id === 'unblock')
  assert.ok(row)
  assert.equal(row.actionClass, 'write')
  assert.deepEqual(row.targets.writeApis, ['/api/im/read', '/api/im/unblock'])
  assert.deepEqual(row.requiredRequests.map(item => [item.urlTemplate, item.kind]), [
    ['/api/im/conversations', 'read'], ['/api/im/unblock', 'write'],
    ['/api/im/messages', 'read'], ['/api/im/read', 'write'],
  ])
  assert.ok(row.requiredBranches.includes('forbidden-owner'))
  assert.ok(row.requiredBranches.includes('duplicate-trigger'))
})

test('聊天拉黑和清空确认落库记为真实写入，不因 confirm 被 Danger 隔断降成读取', () => {
  const rows = ledger().controls.filter(row => row.entry.js === 'subpackageB/pages/im/chat/index.js')
  for (const [id, api] of [['block', '/api/im/block'], ['clear', '/api/im/delete']]) {
    const confirm = rows.find(row => row.control.handler === 'onDangerConfirm' && row.variant.id === id)
    assert.ok(confirm, `${id} 确认在记录中缺项`)
    assert.equal(confirm.actionClass, 'write')
    assert.deepEqual(confirm.targets.writeApis, [api])
    assert.deepEqual(confirm.requiredRequests, [{ urlTemplate: api, method: 'POST', kind: 'write' }])
    assert.ok(confirm.targets.apiContracts.some(item => item.url === api && item.method === 'POST'))
    assert.ok(confirm.requiredBranches.includes('forbidden-owner'))
    assert.equal(confirm.accessBoundary, 'owned-resource')
  }
  const more = rows.find(row => row.control.handler === 'onMoreSelect' && row.variant.id === 'block')
  assert.deepEqual(more.effectClasses, ['state'])
  assert.deepEqual(more.targets.primaryApis, ['/api/im/conversations'])
  assert.deepEqual(more.targets.writeApis, [])
})

test('导演台对账能力是保守传递闭包：loadProjection 合法可达 receipt，retryUnknownWrite 为 over-approx 候选', () => {
  const rows = ledger().controls.filter(row => row.entry.wxml === 'pages/club/topic-detail/index.wxml')
  assert.ok(!rows.some(row => row.control.handler === 'loadProjection'),
    'constant blank loading failures do not expose actionable retry controls')
  const load = { targets: targetsForHandler(pathFor('pages/club/topic-detail/index.js'), 'loadProjection') }
  // init 存在未确认写入时 writeLocked/_unknownRequestId 为真，applyProjection 的 setData 回调
  // 会调用 onReconcileUnknownWrite → readReceipt，故 loadProjection 的 receipt 是合法传递能力。
  assert.ok(load.targets.primaryApis.includes('/api/game/session/receipt'))
  assert.ok(load.targets.primaryApis.includes('/api/game/session/shared/control'))
  assert.ok(load.targets.primaryApis.includes('/api/game/session/view'))
  const retry = rows.find(row => row.control.handler === 'retryUnknownWrite')
  // primaryApis 是静态可达候选集合，不是本次点击实际发生清单。retryUnknownWrite 成功分支在
  // loadProjection 前已清 _unknownRequestId/writeLocked，守卫必假，receipt 属 over-approx 候选。
  assert.deepEqual(retry.targets.writeApis, ['/api/game/session/command'])
  assert.ok(retry.targets.primaryApis.includes('/api/game/session/receipt'),
    '保守闭包保留可达候选，不为通过复核裁剪真实请求')
})

test('基础玩法后的投影只增加真实可达归零恢复写入，不混入其他指令端点', () => {
  const rows = ledger().controls.filter(row => row.entry.js === 'pages/play/index.js')
  // 2026-09-22 retryArrival 随底部读数卡的到达错误条一起删除,不再有可达入口。
  for (const name of ['photoGame', 'scanGame', 'submitGame', 'revealAnswer', 'onScanArrive']) {
    const matched = rows.filter(row => row.control.handler === name)
    assert.ok(matched.length, name)
    for (const row of matched) {
      assert.ok(row.targets.primaryApis.includes('/api/game/session/view'))
      // R5 回读归零状态会启动服务器截止计时，到点 ZERO_HP_SYNC 真实可达两个既有写入口。
      assert.ok(row.targets.primaryApis.includes('/api/game/session/command'), row.id)
      assert.ok(row.targets.primaryApis.includes('/api/play/theme/gameplay-action'), row.id)
      assert.ok(row.targets.writeApis.includes('/api/play/theme/action'), '章节发放是实际可达的主题写入')
      assert.equal(row.targets.writeApis.filter(api => !['/api/play/theme/action', '/api/play/theme/gameplay-action', '/api/game/session/command'].includes(api)).length, 1, row.id)
    }
  }
  assert.ok(rows.some(row => row.targets.writeApis.includes('/api/game/session/command')),
    '真实游戏指令入口必须保留写入合同')
  const photo = rows.find(row => row.control.handler === 'photoGame')
  assert.ok(photo.targets.primaryApis.includes('/api/play/photo'))
  assert.ok(photo.targets.primaryApis.includes('/api/common/uploadOSS'), '真实照片上传不能随白名单一起删除')
  assert.ok(photo.targets.primaryApis.includes('/api/club/lead/team-progress'), '实际完成回调的领队进度读取必须保留')
})

test('保存草稿按普通草稿与据点拒绝两条真实分支登记', () => {
  const rows = ledger().controls.filter(row => row.entry.js === 'pages/publish/temp/index.js'
    && row.control.handler === 'saveDraft')
  const groups = new Map()
  for (const row of rows) {
    const binding = row.id.split(':variant:')[0]
    if (!groups.has(binding)) groups.set(binding, [])
    groups.get(binding).push(row)
  }
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8')
  const bindingCount = [...wxml.matchAll(/\bbindtap=["']saveDraft["']/g)].length
  assert.ok(bindingCount > 0)
  assert.equal(groups.size, bindingCount, '每个实际绑定都必须登记，不能漏掉整组')
  for (const [binding, variants] of groups) {
    assert.equal(variants.length, 2, binding)
    assert.deepEqual(variants.map(row => row.variant.id).sort(), ['citynode-unavailable', 'draft'],
      `${binding}: 分支不能缺失或重复`)
    const draft = variants.find(row => row.variant.id === 'draft')
    assert.deepEqual(draft.targets.writeApis, ['/api/template/draft'])
    assert.deepEqual(draft.targets.primaryApis, ['/api/template/draft'])
    assert.ok(draft.effectClasses.includes('write'))
    const blocked = variants.find(row => row.variant.id === 'citynode-unavailable')
    assert.deepEqual(blocked.targets.primaryApis, [])
    assert.deepEqual(blocked.targets.writeApis, [])
    assert.ok(!blocked.effectClasses.includes('write'))
  }
})

test('同一行的独立方法不互相截断或串入后续函数', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cy-handler-source-'))
  try {
    const file = path.join(dir, 'index.js')
    fs.writeFileSync(file, "Page({noop(){return 1},copy(){wx.setClipboardData({data:'x'})},last(){return 2}}); function outside(){wx.getLocation({})}\n")
    assert.deepEqual([...discoverExternalHelperNames([file])], ['copy'])
  } finally { fs.rmSync(dir, { recursive: true, force: true }) }
})

test('本文件覆盖混入方法后仍只提取获胜方法自身', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cy-handler-override-'))
  try {
    fs.writeFileSync(path.join(dir, 'mixed.js'), "const METHODS = {copy(){wx.setClipboardData({data:'x'})},locate(){wx.getLocation({})}}; module.exports = {METHODS};\n")
    const file = path.join(dir, 'index.js')
    fs.writeFileSync(file, "const {METHODS} = require('./mixed');\nPage({...METHODS,copy(){return 'overridden'}});\n")
    assert.deepEqual([...discoverExternalHelperNames([file])], ['locate'])
  } finally { fs.rmSync(dir, { recursive: true, force: true }) }
})

function assertEarningsGuardBranches(controls) {
  const rows = controls.filter(row => row.entry.wxml === 'components/cy/scene-asset-earnings/index.wxml')
  for (const handler of ['openWithdraw', 'openWithdrawHistory', 'openInvite']) {
    const matches = rows.filter(row => row.control.handler === handler)
    assert.equal(matches.length, 1, handler + '不得增加或重复入口')
    const row = matches[0]
    assert.equal(row.variant, null, handler + '保留原控件身份')
    assert.deepEqual(row.requiredBranches, ['success', 'error'], handler + '必须保留成功及身份/能力拒绝验收')
    assert.deepEqual(row.targets.writeApis, [], handler + '不得引入提现写接口')
    assert.equal(row.evidence.status, 'pending', handler + '静态合同不能转为动态通过')
  }
}

test('收益场景的身份拒绝状态不得删掉原成功与错误验收分支', () => {
  assertEarningsGuardBranches(ledger().controls)
})

test('负控：任一收益场景入口删去错误验收分支必须判红', () => {
  for (const handler of ['openWithdraw', 'openWithdrawHistory', 'openInvite']) {
    const controls = ledger().controls.map(row => row.entry.wxml === 'components/cy/scene-asset-earnings/index.wxml'
      && row.control.handler === handler ? { ...row, requiredBranches: ['success'] } : row)
    assert.throws(() => assertEarningsGuardBranches(controls), assert.AssertionError)
  }
})


test('session renewal does not invoke deferred usage consent records; explicit agree still does', () => {
  const ordinary = targetsForHandler(pathFor('pages/merchant/aftercare/detail/index.js'), 'submitResponse')
  assert.ok(!ordinary.primaryApis.includes('/api/compliance/consents'))
  const agree = targetsForHandler(pathFor('pages/privacy/index.js'), 'agreePrivacy')
  assert.ok(agree.primaryApis.includes('/api/compliance/consents'))
  const photo = targetsForHandler(pathFor('pages/play/index.js'), '_submitKitPhoto')
  assert.ok(photo.primaryApis.includes('/api/compliance/consents'))
})

test('author older-media button retains its real authenticated GET and paging visibility', () => {
  const controls = ledger().controls.filter(row => row.entry.js === 'pages/privacy/index.js'
    && row.control.handler === 'loadOlderMedia');
  assert.equal(controls.length, 1);
  const control = controls[0];
  assert.equal(control.actionClass, 'read');
  assert.equal(control.control.visibleCondition, '{{mediaHasMore}}');
  assert.equal(control.control.disabledCondition, '{{mediaLoading || !!mediaBusyId}}');
  assert.deepEqual(control.targets.writeApis, []);
  assert.ok(control.targets.apiContracts.some(call => call.url === '/api/media/asset/list'
    && call.method === 'GET' && call.auth === 'authenticated'));
  assert.ok(!control.targets.primaryApis.includes('/api/compliance/consents'));
  const source = require('node:fs').readFileSync(pathFor('pages/privacy/index.js'), 'utf8');
  assert.match(source, /this\.requestOwnMedia\('\/api\/media\/asset\/list\?beforeId=' \+ encodeURIComponent\(cursor\), 'GET', scope/);
  assert.match(source, /!this\._mediaRowsScope \|\| !this\.mediaScopeLive\(this\._mediaRowsScope\)/);
});

test('PlayKit parent delegates real advanced retry and scopes photo consent to its true conditional request plans', () => {
  const fs = require('node:fs'), path = require('node:path');
  const root = path.resolve(__dirname, '../..');
  const parent = fs.readFileSync(path.join(root, 'pages/play/index.js'), 'utf8');
  const child = fs.readFileSync(path.join(root, 'pages/play/components/advanced-game/index.js'), 'utf8');
  const album = fs.readFileSync(path.join(root, 'pages/play/utils/playkit-r5-segments.js'), 'utf8');
  assert.match(parent, /case 'retry':[\s\S]{0,650}return game\.retry\(\)/);
  assert.match(child, /retry\(\)[\s\S]{0,420}return this\.action\('RETRY', \{\}\)/);
  assert.match(child, /this\.request\('\/api\/play\/advanced\/action', 'POST', command\)/);
  const consentGuard = /const consentCurrent = \(\) => !operation\.isAborted\(\) && this\._kitPhotoIntent === intent && contextCurrent\(\) && sameQuestion\(\);/;
  assert.match(parent, consentGuard);
  // 2026-10-30 A23:同账号同一局已同意过就走 photo-existing-consent(不再弹),否则仍先单独同意
  assert.match(parent, /if \(option\.name === 'SUBMIT_PHOTO_CHECK'\) \{\s*const consentCurrent = [^\n]+;[\s\S]{0,700}this\._photoConsentScope === consentScope \? Promise\.resolve\(\) : app\.requestSeparateConsent\('photo', consentCurrent\)/);
  assert.match(album, /game\.action\('SUBMIT_ALBUM'/);
  const source = fs.readFileSync(path.join(root, 'scripts/uiaudit/build-action-ledger.js'), 'utf8');
  const plan = source.slice(source.indexOf("['pages/play/index.js:onPlayKitAction'"), source.indexOf("['pages/play/index.js:retryGameSubmission'"));
  assert.match(plan, /'terminal-explicit-retry': \[\{ urlTemplate: '\/api\/play\/advanced\/action'/);
  assert.match(plan, /'photo-new-consent':[\s\S]*?compliance\/consents[\s\S]*?uploadOSS[\s\S]*?advanced\/action/);
  assert.match(plan, /'photo-consent-declined': \[\]/);
  assert.match(plan, /'album-submit-existing-photo': \[\{ urlTemplate: '\/api\/play\/advanced\/action'/);
  const controls = ledger().controls.filter(item => item.entry.js === 'pages/play/index.js' && item.control.handler === 'onPlayKitAction');
  assert.equal(controls.length, 2);
  for (const control of controls) {
    const branches = control.requiredRequestsByBranch;
    assert.deepEqual(branches['terminal-explicit-retry'].map(r => r.urlTemplate), ['/api/play/advanced/action']);
    assert.deepEqual(branches['photo-existing-consent'].map(r => r.urlTemplate), ['/api/common/uploadOSS', '/api/play/advanced/action']);
    assert.deepEqual(branches['photo-new-consent'].map(r => r.urlTemplate), ['/api/compliance/consents', '/api/common/uploadOSS', '/api/play/advanced/action']);
    assert.deepEqual(branches['photo-consent-declined'], []);
    assert.deepEqual(branches['album-submit-existing-photo'].map(r => r.urlTemplate), ['/api/play/advanced/action']);
  }
  // A changed delegate or consent guard must turn this proof red rather than retaining a hand-written plan.
  assert.throws(() => assert.match(child.replace("this.action('RETRY', {})", "this.start()"), /retry\(\)[\s\S]{0,420}return this\.action\('RETRY', \{\}\)/));
  assert.throws(() => assert.match(parent.replace('contextCurrent() && sameQuestion();', 'contextCurrent();'), consentGuard));
});


test('真实 roleGuard.clear 只清缓存；load 仍保留 Controller POST，未知别名不裁剪', () => {
  const { namespaceDependencySource, extractTargets } = require('../../scripts/uiaudit/build-action-ledger')
  const file = pathFor('utils/roleGuard.js')
  const clear = namespaceDependencySource(file, ['roleGuard'], 'roleGuard.clear()')
  assert.match(clear, /removeStorageSync/)
  assert.deepEqual(extractTargets(clear).apiContracts, [])
  const load = namespaceDependencySource(file, ['roleGuard'], 'roleGuard.load(callback)')
  assert.ok(extractTargets(load).apiContracts.some(api => api.url === '/api/role/info' && api.method === 'POST'))
  for (const source of ['const alias = roleGuard; alias.clear()', 'roleGuard[method]()', 'roleGuard.unknown()']) {
    const conservative = namespaceDependencySource(file, ['roleGuard'], source)
    assert.ok(extractTargets(conservative).apis.includes('/api/role/info'), source)
  }
})
