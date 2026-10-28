const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('../helpers/ui-sandbox-vm.js') // node:vm + 沙箱里装真 utils/toast.js

const root = (...p) => path.join(__dirname, '../..', ...p)
const read = (...p) => fs.readFileSync(root(...p), 'utf8')
const WXML = () => read('pages/coop/invite/index.wxml')
const JS = () => read('pages/coop/invite/index.js')
const { maskJsNonCode } = require('../../../ci/api-existence-gate.js')

function loadInvitePage(options = {}) {
  const previous = { Page: global.Page, getApp: global.getApp, wx: global.wx }
  const requests = []
  const storage = options.storage || new Map()
  const wire = [], app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 }, _memberId: 7, _authorization: 'fixture-a',
    getUserID() { return this._memberId }, getAuthorization() { return this._authorization },
    sendRequest(request) { requests.push(request) }
  }
  global.getApp = () => app
  const navigations = []
  global.wx = { showToast() {}, setNavigationBarColor() {}, navigateTo(o) { navigations.push(o.url) }, redirectTo(o) { navigations.push(o.url) },
    setStorageSync(key, value) { if (options.storageFailure === true || options.storageFailure === 'write') throw Error('fixture storage failure'); storage.set(key, JSON.parse(JSON.stringify(value))) },
    getStorageSync(key) { if (options.storageFailure === true || options.storageFailure === 'read') throw Error('fixture storage failure'); return storage.get(key) || '' },
    removeStorageSync(key) { if (options.removeFailure) throw Error('remove failed'); storage.delete(key) } }
  if (options.transport) {
    const { createRequestClient } = require('../../utils/transport/request-client.js')
    const client = createRequestClient({ wxRequest(request) { wire.push(request); return { abort() {} } },
      getBaseUrl: () => 'https://fixture.invalid', getUserID: () => app.getUserID(), getAuthorization: () => app.getAuthorization(),
      reLogin(callback) { app._authorization = 'fixture-renewed'; callback(true, { isCurrentSession: () => true }) } })
    app.sendRequest = request => { requests.push(request); return client.send(request) }
  }
  let definition
  global.Page = (config) => { definition = config }
  const pagePath = root('pages/coop/invite/index.js')
  delete require.cache[require.resolve(pagePath)]
  require(pagePath)
  const page = Object.assign({}, definition)
  page.data = JSON.parse(JSON.stringify(definition.data))
  page.setData = function (patch) { Object.assign(this.data, patch) }
  page._dataIdentity = '7||'
  return {
    page,
    requests, storage, wire, app, navigations,
    restore() {
      page.onUnload()
      if (previous.Page === undefined) delete global.Page; else global.Page = previous.Page
      if (previous.getApp === undefined) delete global.getApp; else global.getApp = previous.getApp
      if (previous.wx === undefined) delete global.wx; else global.wx = previous.wx
      delete require.cache[require.resolve(pagePath)]
    }
  }
}

test('邀请创建流是主题、条款、对象三段卡', () => {
  const titles = [...WXML().matchAll(/<view class="card-tit">([^<]*)/g)].map((m) => m[1].trim())
  assert.deepEqual(titles.slice(0, 3), ['① 关联主题', '② 合作条款', '③ 邀请对象'])
})

test('对象使用头像、文字状态与 Threads 式邀请按钮，底部唯一主 CTA 带数量', () => {
  const wxml = WXML()
  assert.match(wxml, /class="cta-bar" wx:if="\{\{selected\.length\}\}"/)
  assert.match(wxml, /发送邀约\(\{\{selected\.length\}\}\)/)
  assert.match(wxml, /bindtap="toggleTarget"/)
  assert.ok(!/class="target-btn"/.test(wxml), '行内邀请按钮已撤销')
  assert.match(wxml, /presetTarget\.logo/)
  assert.match(wxml, /presetTarget\.meta/)
  assert.match(wxml, /target-action--locked">已锁定/)
  assert.match(wxml, /selectedMap\[item\.id\] \? '已选' : '邀请'/)
})

test('现役 type0/type1 邀约只使用结果口径', () => {
  const wxml = WXML()
  const js = JS()
  assert.match(wxml, /data-mode="0"/)
  assert.match(wxml, /data-mode="2"/)
  assert.match(wxml, /引流合作（不产生现金结算）/)
  // CU-M-98:条款名与单位收进 js 的 feeTerms 单一真源,wxml 只渲染 {{feeName}}/{{feeUnit}}
  assert.match(js, /按核销人头付带队费/)
  assert.match(js, /按核销付费/)
  assert.doesNotMatch(wxml, /固定带队费|每核销 1 人/, 'wxml 不再自己抄一份条款名')
  assert.doesNotMatch(wxml, /引流型|固定型|分成型|计酬档/)
  assert.doesNotMatch(js, /shareModeTabs:|ratePresets:/)
  assert.match(js, /shareMode: 0/)
})

// CU-M-98(2026-09-24 走查)· 固定带队费选项却以「每核销 1 人」计价。
// 后端 shareMode=2 走 FIXED_PER_FULFILLMENT(CoopSettlementContract.java:39-45:
// 每条已核销报名各结一次 fixedFee),是按人头的计费口径,不是整场一口价。
// ① 金额行不许再出现像整场总价的「固定 ×」与按人单位同屏;
// ② 确认弹层那条条款必须带上单位,否则双方点确认时看到的不是同一条约定。
test('CU-M-98 确认条款与金额行同源,且单位不省', () => {
  const cases = [
    { activeType: 1, expect: '按核销人头付带队费 · ¥12.00 / 每核销 1 人' },
    { activeType: 0, expect: '按核销付费 · ¥12.00 / 人' },
  ]
  for (const testCase of cases) {
    const env = loadInvitePage()
    try {
      const page = env.page
      page.onLoad({ type: String(testCase.activeType), topicId: '301', toId: '18', toName: '合作对象' })
      if (testCase.activeType === 0) {
        const metadata = env.requests.find(request => request.url === '/api/topic/list')
        assert.ok(metadata, '主题类型必须从原 owner 读取路径确认')
        metadata.success({ code: 200, data: { rows: [{ id: '301', name: '原主题', productType: 1 }], total: 1 } })
        const stations = env.requests.find(request => request.url === '/api/coop/supply-stations')
        assert.ok(stations, '经典商家邀约必须读取获准据点')
        stations.success({ code: 200, data: { requiresSupplyStation: true,
          stations: [{ supplyStationId: '101', stationName: '原核销据点' }] } })
        page.onSupplyStationChange({ currentTarget: { dataset: { toid: '18' } }, detail: { value: 0 } })
      }
      page.data.shareMode = 2
      page.data.fixedFee = 12
      page.sendInvite()
      assert.equal(page.data.inviteModal.show, true, '必须检验本次成功打开的确认弹层')
      assert.equal(page.data.inviteModal.termsText, testCase.expect)
      assert.equal(page.data.feeName, testCase.expect.split(' · ')[0],
        '金额行左边的名字必须就是确认弹层里那个名字')
      assert.ok(page.data.inviteModal.termsText.indexOf(page.data.feeUnit) > 0,
        '确认弹层不得省掉计价单位')
    } finally {
      env.restore()
    }
  }
})

test('CU-M-98 引流合作条款不带金额', () => {
  const env = loadInvitePage()
  try {
    const page = env.page
    page.onLoad({ type: '1' })
    page.data.topicsState = 'ready'
    page.data.targetsState = 'ready'
    page.data.topicId = 301
    page.data.selected = [{ toId: 18, name: '外滩夜行俱乐部', logo: '', meta: '' }]
    page.data.shareMode = 0
    page.sendInvite()
    assert.equal(page.data.inviteModal.termsText, '引流合作 · 不产生现金结算')
  } finally {
    env.restore()
  }
})

/** 负控专用:把「改回旧写法」的源码放进沙箱跑，验证上面两条 CU-M-98 用例真的会红。 */
function loadInviteFromSource(source) {
  let definition
  const pagePath = root('pages/coop/invite/index.js')
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => 'member-a',
    getUserRole: () => 'merchant',
    getUserType: () => 2,
    sendRequest() {},
  }
  vm.runInNewContext(source, {
    Page: (value) => { definition = value },
    getApp: () => app,
    wx: { showToast() {}, hideToast() {}, stopPullDownRefresh() {}, navigateTo() {}, navigateBack() {}, setStorageSync() {}, getStorageSync: () => '' },
    console,
    setTimeout: () => 0,
    clearTimeout() {},
    require(id) {
      if (id.includes('subscribe')) return { request: () => Promise.resolve({ status: 'accepted' }) }
      if (id.includes('merchant-theme')) return { merchantPageShow() {}, merchantPageRestore() {} }
      if (id.includes('identity-policy')) return { isMerchantView: () => true }
      if (id.includes('response-shape')) {
        return { isRecordList: (rows) => Array.isArray(rows) }
      }
      return require(path.resolve(path.dirname(pagePath), id))
    },
  }, { filename: 'pages/coop/invite/index.js' })
  const page = Object.assign({}, definition)
  page.data = JSON.parse(JSON.stringify(definition.data))
  page.setData = function setData(patch) { Object.assign(this.data, patch) }
  return page
}

test('CU-M-98 负控:确认条款退回省略单位 / 各写一份时,上面两条必须真红', () => {
  const regressed = JS().replace(
    "? (terms.feeName + ' · ¥' + Number(this.data.fixedFee || 0).toFixed(2) + ' ' + terms.feeUnit)",
    "? ((this.data.activeType === 1 ? '固定带队费' : '按核销付费') + ' · ¥' + Number(this.data.fixedFee || 0).toFixed(2))"
  )
  assert.notEqual(regressed, JS(), '负控锚点失效:sendInvite 的 termsText 已改名，扫描口径需同步')
  const page = loadInviteFromSource(regressed)
  page.onLoad({ type: '1' })
  page.data.topicsState = 'ready'
  page.data.targetsState = 'ready'
  page.data.topicId = 301
  page.data.selected = [{ toId: 18, name: '外滩夜行俱乐部', logo: '', meta: '' }]
  page.data.shareMode = 2
  page.data.fixedFee = 12
  page.sendInvite()
  // 旧写法既丢了单位、名字也和金额行不是同一份 —— 正是走查里那条自相矛盾的条款
  assert.throws(() => assert.equal(
    page.data.inviteModal.termsText, '按核销人头付带队费 · ¥12.00 / 每核销 1 人'), assert.AssertionError)
  assert.equal(page.data.inviteModal.termsText, '固定带队费 · ¥12.00')
  assert.equal(page.data.inviteModal.termsText.indexOf(page.data.feeUnit), -1)
})

test('提交只会生成 type0/type1，伪造 type2 也归一为 type1', () => {
  const env = loadInvitePage()
  try {
    for (const activeType of [0, 1]) {
      env.page.data.activeType = activeType
      env.page.data.shareMode = 2
      env.page.data.shareRate = 50
      env.page.data.fixedFee = 88
      const payload = env.page._invitePayload(7)
      assert.equal(payload.shareMode, 2)
      assert.equal(payload.fixedFee, 88)
      assert.equal('shareRate' in payload, false)
    }

    env.page.data.activeType = 2
    env.page.data.shareMode = 2
    env.page.data.shareRate = 50
    env.page.data.fixedFee = 88
    const forgedPayload = env.page._invitePayload(7)
    assert.equal(forgedPayload.inviteType, 1)
    assert.equal(forgedPayload.toType, 'club')
    assert.equal(forgedPayload.shareMode, 2)
    assert.equal('nodeId' in forgedPayload, false)

    env.page.data.activeType = '2'
    const stringForgedPayload = env.page._invitePayload(7)
    assert.equal(stringForgedPayload.inviteType, 1)
    assert.equal('nodeId' in stringForgedPayload, false)

    env.page.data.activeType = 1
    env.page.data.shareMode = 1
    const invalidPayload = env.page._invitePayload(7)
    assert.equal(invalidPayload.shareMode, 0)
    assert.equal('shareRate' in invalidPayload, false)
    assert.equal('fixedFee' in invalidPayload, false)
  } finally {
    env.restore()
  }
})

test('逐条串行发送并保留目标级失败回执', () => {
  const js = JS()
  assert.match(js, /function step\(i\)/)
  assert.match(js, /const target = targets\[i\]/)
  assert.match(js, /this\._invitePayload\(target\.toId\)/)
  assert.match(js, /payload:\s*this\._invitePayload\(target\.toId\)/)
  assert.match(js, /JSON\.stringify\(Object\.assign\(\{\}, target\.payload, \{ requestId: identity\.requestId \}\)\)/)
  assert.doesNotMatch(js, /that\._invitePayload\(target\.toId\)/, '串行外呼必须消费确认时冻结的 payload，不在每次发送时重建据点')
  assert.match(js, /okNames\.push\(target\.name\)/)
  assert.match(js, /failed\.push\(target\.name \+ ':'/)
  assert.match(js, /workflow\.run\(target\.key/)
  assert.match(WXML(), /class="iv-result" wx:if="\{\{sendResult\}\}"/)
})

test('部分失败后只保留失败对象，重试不会重复发送成功项', () => {
  const env = loadInvitePage()
  try {
    env.page.data.topicId = 9
    env.page._setSelected([{ toId: 11, name: '甲店' }, { toId: 22, name: '乙店' }])
    env.page.confirmInvite()
    assert.equal(env.requests.length, 1)
    assert.equal(JSON.parse(env.requests[0].data).toId, 11)
    okInvite(env.requests[0])
    assert.equal(env.requests.length, 2, '第二条只能在第一条明确回执后发，必须串行')
    assert.equal(JSON.parse(env.requests[1].data).toId, 22)
    env.requests[1].success({ code: '400', msg: '容量不足' })
    assert.deepEqual(env.page.data.selected, [{ toId: 22, name: '乙店' }])
    assert.match(env.page.data.sendResult, /已发起 1 条；失败：乙店:容量不足/)
    assert.equal(env.page.data.resultSheet.kind, 'fail')
    assert.equal(env.page.data.resultSheet.title, '协作邀请部分发送成功')
    assert.equal(env.page.data.resultSheet.sub, '已发起 1 条，失败 1 条。仅重试失败对象。')
    const redirects = []
    global.wx.redirectTo = options => redirects.push(options)
    const receipt = env.page.data.sendResult
    env.page.onResultSheetClose()
    assert.equal(env.page.data['resultSheet.show'], false)
    assert.deepEqual(redirects, [], '关闭部分失败面板留在当前页')
    assert.equal(env.page.data.sendResult, receipt)
    assert.deepEqual(env.page.data.selected, [{ toId: 22, name: '乙店' }])
    env.page.confirmInvite()
    assert.equal(env.requests.length, 3, '重试只发一条')
    assert.equal(JSON.parse(env.requests[2].data).toId, 22, '只重试乙店，不重复发送甲店')
  } finally {
    env.restore()
  }
})

test('type2 的 tab、节点 UI、请求与 payload 分支全部撤销，预置目标仍锁为单选', () => {
  const js = JS()
  const wxml = WXML()
  assert.match(js, /if \(presetTarget\) this\._setSelected\(\[presetTarget\]\)/)
  assert.match(js, /if \(this\.data\.presetTarget\) return;/)
  assert.doesNotMatch(js + wxml, /inviteTypeTabs|activeType\s*===\s*2|loadNodes|nodeIndex|nodesState|邀商家做节点|承接节点/)
  // 站点校验注释可以解释 nodeId 不可冒充站点；可执行分支与 UI 仍禁止节点身份。
  assert.doesNotMatch(maskJsNonCode(js) + wxml, /\bnodeId\b/)
  assert.doesNotMatch(wxml, /<cy-tabs\b/)
})

test('发送确认半屏回显已锁定邀请对象与拟议条款(条款在对方接受并成单后才冻结)', () => {
  const wxml = WXML()
  const js = JS()
  assert.match(wxml, /邀请对象 · 已锁定/)
  // CoopInviteServiceImpl 在对方接受、生成合作单时才冻结条款快照,发送时只是拟议条款
  assert.match(wxml, /拟议条款 · 对方接受并成单后锁定/)
  assert.doesNotMatch(wxml, /合作条款 · 已锁定|发送确认时锁定/)
  assert.match(wxml, /inviteModal\.targetLogo/)
  assert.match(wxml, /inviteModal\.termsText/)
  assert.match(js, /termsText\s*=\s*this\.data\.shareMode === 2/)
  assert.match(js, /targetLogo:\s*firstTarget\.logo/)
})

test('历史 type2 仍可读且 merchantMemberId 节点模型保留', () => {
  const listJs = read('pages/coop/list/index.js')
  const listWxml = read('pages/coop/list/index.wxml')
  const nodeModel = read('../chengyinhub-system/src/main/java/com/chengyinhub/business/domain/CmsTopicNode.java')
  // 2026-09-08 TYPE_LABEL 随邀约整形搬进 utils/coop-invite-view.js(列表与 02d 详情共用一份)。
  // 断言跟着真源走 —— 留在 coop/list 上只会证明「那一行没被改过」,证明不了标签表还在。
  const inviteView = read('utils/coop-invite-view.js')
  assert.match(inviteView, /TYPE_LABEL\s*=\s*\[[^\]]*'商家 → 商家承接节点'/)
  assert.match(listJs, /require\([^)]*coop-invite-view/, 'coop/list 必须仍消费这份共用整形，而不是自己再抄一份')
  assert.match(inviteView, /const legacyReadonly\s*=\s*Number\(it\.inviteType\)\s*===\s*2/)
  // 俱乐部带队申请卡(inv-acts--apply)不是邀约,没有 inviteType,不参与 type2 只读闸
  const actionConditions = [...listWxml.matchAll(/class="inv-acts(?! inv-acts--apply)[^"]*"\s+wx:if="\{\{([^"]+)\}\}"/g)].map((m) => m[1])
  const inviteActionConditions = actionConditions.filter((condition) => /item\.(?:status|depositRefundPending)/.test(condition))
  assert.ok(inviteActionConditions.length >= 5)
  assert.ok(inviteActionConditions.every((condition) => condition.includes('!item.legacyReadonly')))
  assert.match(nodeModel, /private Long merchantMemberId;/)
})

test('陈旧 type2 深链不得把 merchantMemberId 静默改邀为 clubId', () => {
  const js = JS()
  assert.match(js, /const invalidLegacyType\s*=\s*t\s*===\s*2/)
  assert.match(js, /const presetTarget\s*=\s*!invalidLegacyType\s*&&\s*options\.toId/)
})

test('附近商家入口位于对象列表尾部', () => {
  const wxml = WXML()
  assert.match(wxml, /class="nearby-row"[^>]*bindtap="goNearby"/)
  assert.ok(wxml.indexOf('nearby-row') > wxml.indexOf('③ 邀请对象'))
})

test('★负控：删除串行推进或多选入口会判红', () => {
  assert.throws(() => assert.match('function step(i) {}', /workflow\.run\(target\.key/))
  assert.throws(() => assert.match('<view bindtap="sendInvite"/>', /bindtap="toggleTarget"/))
})

test('★负控：type2 tab/payload 或分成选择器回潮会判红', () => {
  const source = JS() + WXML()
  assert.throws(() => assert.doesNotMatch(source + "{ key: '2', label: '邀商家做节点' }", /inviteTypeTabs|邀商家做节点/))
  assert.throws(() => assert.doesNotMatch(source + 'if (data.activeType === 2) payload.nodeId = data.nodeId', /activeType\s*===\s*2|nodeId/))
  assert.throws(() => assert.doesNotMatch(source + '<text>分成型</text>', /引流型|固定型|分成型|计酬档/))
})

// 2026-09-23 CU-M-29:主题下拉原来不分招商期,选完填完发送才被写闸拒(「招商写入已关闭:主题不在招商期」)。
test('不在招商期的主题在下拉里标明,选中后不能发送', () => {
  const env = loadInvitePage()
  try {
    const { page, requests } = env
    page.data.activeType = 1
    page.loadMyTopics(true)
    const req = requests.find((r) => r.url === '/api/topic/list')
    assert.ok(req, '必须读我的主题')
    assert.equal(req.data.invite_target, 'club', '商家→俱乐部要按俱乐部口径回填招商期')
    req.success({ code: '200', data: { rows: [
      { id: 1, name: '招募中', inviteWindowOpen: true },
      { id: 2, name: '已锁价', inviteWindowOpen: false },
    ] } })
    assert.deepEqual(page.data.myTopics.map((t) => t.label), ['招募中', '已锁价（不在招商期）'])
    page.onTopicChange({ detail: { value: 1 } })
    assert.equal(page.data.topicClosed, true)
    page.setData({ selected: [{ toId: 9, name: '跑团' }], targetsState: 'ready', topicsState: 'ready' })
    page.sendInvite()
    assert.notEqual(page.data.inviteModal && page.data.inviteModal.show, true, '不在招商期不得打开发送确认')
    assert.match(WXML(), /topicClosed \|\| topicsState !== 'ready'/)
    // 招商期提示不得插进主题区 wx:if/elif/else 链中间:插进去 cy-empty 会接到它上面,空态到处冒
    assert.match(WXML(), /<\/cy-dropdown>\s*<cy-empty wx:else title="还没有可关联的主题"/)
  } finally { env.restore() }
})

// 2026-09-23 CU-C-85:发送成功后落到「收到的」,刚发的邀约不在那页,容易误判失败而重发。
test('发送成功关掉结果面板后进协作邀请的「我发出的」', () => {
  const env = loadInvitePage()
  const navigations = []
  try {
    global.wx.redirectTo = (o) => { navigations.push(o.url) }
    const { page } = env
    page.setData({ resultSheet: { show: true, kind: 'success' } })
    page._resultScope = page._submitScope()
    page.onResultSheetClose()
    assert.deepEqual(navigations, ['/pages/coop/list/index?tab=sent'])
  } finally { env.restore() }
})

// W3: real persistent helper, account-scoped digest rows and the production transport.
// 真实契约：ApiCoopController 带 requestId 时回 CoopInviteRequestResult.created(receipt, replayed)。
const okInvite = request => request.success({ code: 200, data: { ...requestReceipt(request), replayed: false } })
const prepare = (env, targets = [{ toId: 11, name: '甲店' }]) => {
  env.page.data.topicId = 9
  env.page._setSelected(targets)
  return env
}
const snapshot = env => JSON.stringify({ data: env.page.data, count: env.requests.length })

test('W3 网络UNKNOWN独立保留，改条款/重选/重进均不POST，列表不自动结算', () => {
  const storage = new Map()
  let env = prepare(loadInvitePage({ storage }))
  try {
    env.page.confirmInvite()
    env.requests[0].fail({ errMsg: 'request:fail timeout' })
    assert.equal(env.page.data.submitting, false)
    assert.equal(env.page.data.selected.length, 0)
    assert.equal(env.page.data.unknownTargets[0].toId, 11)
    assert.match(env.page.data.sendResult, /结果待确认/)
    env.page.onResultSheetClose()
    assert.deepEqual(env.navigations, [])
    const stored = JSON.stringify([...storage])
    assert.doesNotMatch(stored, /message|fixedFee|shareRate|payload|甲店/)
    env.page.checkUnknownInvites()
    assert.deepEqual(env.navigations, ['/pages/coop/list/index?tab=sent'])
    env.page.onShow()
    env.page._setSelected([{ toId: '0011', name: '甲店' }])
    env.page.data.message = '新的条款不能成为重发理由'
    env.page.data.operationScope = 'MERCHANT' // type1 ignores this scope on the backend
    env.page.confirmInvite()
    assert.equal(env.requests.filter(r => r.url === '/api/coop/invite').length, 1)
    assert.equal(env.page.data.unknownTargets.length, 1)
    env.restore()
    env = prepare(loadInvitePage({ storage }))
    env.page.confirmInvite()
    assert.equal(env.requests.length, 0, 're-entry recovers durable UNKNOWN before sending')
    assert.equal(env.page.data.unknownTargets.length, 1)
  } finally { env.restore() }
})

test('W3 成功/明确失败/UNKNOWN三分；重试失败对象不清其他UNKNOWN', () => {
  const env = prepare(loadInvitePage(), [{ toId: 11, name: '甲店' }, { toId: 22, name: '乙店' }, { toId: 33, name: '丙店' }])
  try {
    env.page.confirmInvite()
    okInvite(env.requests[0])
    env.requests[1].success({ code: 400, msg: '明确拒绝' })
    env.requests[2].fail({})
    assert.deepEqual(env.page.data.selected, [{ toId: 22, name: '乙店' }])
    assert.deepEqual(env.page.data.unknownTargets, [{ toId: 33, name: '丙店',
      key: '7:coop-invite:[1,"","9","33"]', payload: { inviteType: 1, toType: 'club', toId: 33,
        topicId: 9, shareMode: 0, message: '邀请贵俱乐部来参加活动' } }])
    assert.equal(env.page._inviteWorkflow().pendingRequest(env.page.data.unknownTargets[0].key).requestId,
      JSON.parse(env.requests[2].data).requestId, '摘要保留真实原请求号，不只保留显示名称')
    env.page.confirmInvite()
    assert.equal(JSON.parse(env.requests[3].data).toId, 22)
    okInvite(env.requests[3])
    assert.equal(env.page.data.resultSheet.kind, 'fail')
    assert.equal(env.page.data.unknownTargets.length, 1)
    env.page.onResultSheetClose()
    assert.deepEqual(env.navigations, [])
  } finally { env.restore() }
})

test('W3 成功、明确失败、UNKNOWN与本次未提交同批出现时保留全部摘要', () => {
  const options = {}
  const targets = [{ toId: 11, name: '甲店' }, { toId: 22, name: '乙店' },
    { toId: 33, name: '丙店' }, { toId: 44, name: '丁店' }]
  const env = prepare(loadInvitePage(options), targets)
  try {
    env.page.confirmInvite()
    okInvite(env.requests[0])
    env.requests[1].success({ code: 400, msg: '明确拒绝' })
    options.storageFailure = 'write'
    env.requests[2].fail({})
    assert.equal(env.requests.length, 3, '第四个对象尚未POST')
    assert.deepEqual(env.page.data.selected, [targets[1], targets[3]])
    assert.deepEqual(env.page.data.unknownTargets, [{ ...targets[2],
      key: '7:coop-invite:[1,"","9","33"]', payload: { inviteType: 1, toType: 'club', toId: 33,
        topicId: 9, shareMode: 0, message: '邀请贵俱乐部来参加活动' } }])
    assert.equal(env.page._inviteWorkflow().pendingRequest(env.page.data.unknownTargets[0].key).requestId,
      JSON.parse(env.requests[2].data).requestId)
    for (const summary of [/已发起 1 条/, /明确失败 1 条/, /本次未提交 1 个对象/, /另有 1 条原邀请结果待确认/]) {
      assert.match(env.page.data.sendResult, summary)
      assert.match(env.page.data.resultSheet.sub, summary)
    }
    env.page.onResultSheetClose()
    assert.deepEqual(env.navigations, [])
  } finally { env.restore() }
})

for (const response of [null, { code: 200 }, { code: 200, data: { inviteId: 0 } }, { code: 500, msg: '未知数据库错误' }]) {
  test('W3 无有效inviteId或通用500不能猜成功/失败 ' + JSON.stringify(response), () => {
    const env = prepare(loadInvitePage())
    try {
      env.page.confirmInvite(); env.requests[0].success(response)
      assert.equal(env.page.data.unknownTargets.length, 1)
      assert.equal(env.page.data.selected.length, 0)
      assert.notEqual(env.page.data.resultSheet.kind, 'success')
    } finally { env.restore() }
  })
}

for (const fault of ['read', 'write']) {
  test('W3 存储' + fault + '故障为本次未提交；修复后仅主动再试才POST', () => {
    const options = { storageFailure: fault }
    const env = prepare(loadInvitePage(options))
    try {
      env.page.confirmInvite()
      assert.equal(env.requests.length, 0)
      assert.equal(env.page.data.unknownTargets.length, 0)
      assert.deepEqual(env.page.data.selected, [{ toId: 11, name: '甲店' }])
      assert.equal(env.page.data.submitting, false)
      assert.match(env.page.data.sendResult, /本次未提交/)
      assert.match(env.page.data.sendResult, /本机待办.*存储.*登录/)
      assert.doesNotMatch(env.page.data.sendResult, /已发起|服务端.*失败/)
      env.page.onResultSheetClose()
      assert.deepEqual(env.navigations, [])
      options.storageFailure = false
      assert.equal(env.requests.length, 0, '修复前置不会自动发出请求')
      env.page.confirmInvite()
      assert.equal(env.requests.length, 1)
      okInvite(env.requests[0])
      assert.equal(env.page.data.resultSheet.kind, 'success')
    } finally { env.restore() }
  })
}

test('W3 已有pending读故障不清记录/旧UNKNOWN；恢复读取仍不POST', () => {
  const options = {}
  const env = prepare(loadInvitePage(options))
  try {
    env.page.confirmInvite(); env.requests[0].fail({})
    const stored = JSON.stringify([...env.storage])
    const unknown = JSON.stringify(env.page.data.unknownTargets)
    env.page._setSelected([{ toId: 11, name: '甲店' }])
    options.storageFailure = 'read'
    env.page.confirmInvite()
    assert.equal(env.requests.length, 1, '旧请求之外没有新POST')
    assert.equal(JSON.stringify([...env.storage]), stored)
    assert.equal(JSON.stringify(env.page.data.unknownTargets), unknown)
    assert.equal(env.page.data.selected.length, 1)
    assert.match(env.page.data.sendResult, /本次未提交/)
    options.storageFailure = false
    env.page.confirmInvite()
    assert.equal(env.requests.length, 1, '读到原UNKNOWN后继续拦截，不重邀')
    assert.equal(JSON.stringify([...env.storage]), stored)
    assert.equal(JSON.stringify(env.page.data.unknownTargets), unknown)
    assert.equal(env.page.data.selected.length, 0)
  } finally { env.restore() }
})

for (const change of ['hide', 'unload', 'identity', 'topic']) {
  test('W3 ' + change + '→返回后旧success/fail不清新busy，旧目标仍占位', () => {
    const env = prepare(loadInvitePage())
    try {
      env.page.confirmInvite(); const old = env.requests[0]
      if (change === 'hide') { env.page.onHide(); env.page.onShow() }
      if (change === 'unload') { env.page.onUnload(); env.page.onLoad({ type: '1', topicId: 9, toId: 11 }) }
      if (change === 'identity') {
        env.app._memberId = 8; env.page.onShow()
        env.app._memberId = 7; env.page.onShow()
        env.page.data.topicId = 9
      }
      if (change === 'topic') {
        env.page.data.myTopics = [{ id: 10, name: 'B' }, { id: 9, name: 'A' }]
        const epoch = env.page._submitEpoch
        env.page.onTopicChange({ detail: { value: 1 } })
        assert.equal(env.page._submitEpoch, epoch, '相同主题事件不能退休原活跃命令')
        assert.equal(env.page._submitting, true)
        env.page.onTopicChange({ detail: { value: 0 } })
        assert.equal(env.page.data.topicId, 10, '真实主题切换必须发生，不能靠 busy 提前返回伪装往返')
        assert.notEqual(env.page._submitEpoch, epoch)
        env.page.onTopicChange({ detail: { value: 1 } })
        assert.equal(env.page.data.topicId, 9)
      }
      env.page._setSelected([{ toId: 22, name: '乙店' }]); env.page.confirmInvite()
      const before = snapshot(env), busy = env.page._submitting
      old.success({ code: 200, data: { inviteId: 501 } }); old.fail({})
      okInvite(old)
      assert.equal(snapshot(env), before); assert.equal(env.page._submitting, busy)
      assert.equal(env.page.data.submitting, true)
    } finally { env.restore() }
  })
}

test('W3 单飞、重复回调和重复目标不产生额外POST', () => {
  const env = prepare(loadInvitePage(), [{ toId: 11, name: '甲店' }, { toId: '0011', name: '同店' }, { toId: 22, name: '乙店' }])
  try {
    env.page.confirmInvite(); const old = env.requests[0]
    env.page.confirmInvite(); assert.equal(env.requests.length, 1)
    okInvite(old); assert.equal(env.requests.length, 2)
    const before = snapshot(env); okInvite(old); old.fail({})
    assert.equal(snapshot(env), before)
    okInvite(env.requests[1]); assert.equal(env.page.data.submitting, false)
  } finally { env.restore() }
})

for (const ending of ['success', 'http403', 'http503', 'http408', 'network', 'external']) {
  test('W3 真实transport续登及 ' + ending + ' 分类，不靠complete推进或解锁', () => {
    const env = prepare(loadInvitePage({ transport: true }))
    try {
      env.page.confirmInvite(); const first = env.wire[0]
      first.success({ statusCode: 401, data: { code: 401 } }); first.complete({})
      assert.equal(env.wire.length, 2)
      const retry = env.wire[1]
      assert.equal(retry.header.Authorization, 'fixture-renewed')
      assert.equal(retry.data, first.data)
      assert.equal(env.page.data.submitting, true)
      if (ending === 'network') retry.fail({ errMsg: 'fixture timeout' })
      else {
        if (ending === 'external') env.app._authorization = 'fixture-external'
        const statusCode = ending.startsWith('http') ? Number(ending.slice(4)) : 200
        retry.success({ statusCode, data: { code: statusCode, data: { ...requestReceipt(retry), replayed: false } } })
      }
      retry.complete({})
      assert.equal(env.page.data.submitting, false)
      if (ending === 'success') assert.equal(env.page.data.resultSheet.kind, 'success')
      else if (ending === 'http403') assert.equal(env.page.data.selected.length, 1)
      else assert.equal(env.page.data.unknownTargets.length, 1)
    } finally { env.restore() }
  })
}

test('W3 部分成功后离页只保留未发送对象，已成功和在途UNKNOWN不再重邀', () => {
  const env = prepare(loadInvitePage(), [{ toId: 11, name: '甲店' }, { toId: 22, name: '乙店' }, { toId: 33, name: '丙店' }])
  try {
    env.page.confirmInvite(); okInvite(env.requests[0])
    assert.equal(env.requests.length, 2)
    assert.equal(env.page.data.selected.some(t => t.toId === 11), false)
    env.page.onHide(); env.page.onShow()
    assert.deepEqual(env.page.data.selected.map(t => t.toId), [33])
    assert.equal(env.page.data.unknownTargets[0].toId, 22)
    env.page.confirmInvite()
    assert.equal(JSON.parse(env.requests[2].data).toId, 33)
    okInvite(env.requests[2])
    assert.equal(env.page.data.resultSheet.kind, 'fail', '旧UNKNOWN仍独立保留，不整批报成功')
  } finally { env.restore() }
})

const requestReceipt = (request, extra = {}) => {
  const p = JSON.parse(request.data)
  return { state: 'CREATED', requestId: p.requestId, inviteId: '9223372036854775807',
    inviteType: p.inviteType, topicId: String(p.topicId).replace(/^0+/, ''), toType: p.toType,
    toId: String(p.toId).replace(/^0+/, ''), scope: p.inviteType === 0 && p.scope === 'MERCHANT' ? 'MERCHANT' : '', ...extra }
}
const queryTarget = env => env.page.queryOriginalInvite({ currentTarget: { dataset: { toid: 11 } } })

test('W4 POST前持久原请求号；丢响应后精确GET解原锁，一次POST且重复回调不再提交', () => {
  const env = prepare(loadInvitePage())
  try {
    env.page.data.message = '原文'
    env.page.confirmInvite()
    const post = env.requests[0], payload = JSON.parse(post.data), row = [...env.storage.values()][0]
    assert.equal(payload.requestId, row.attemptId)
    assert.equal(row.requestIdentityVersion, 1)
    env.page.data.message = '后来修改'
    assert.equal(JSON.parse(post.data).message, '原文')
    post.fail({}); queryTarget(env)
    const get = env.requests[1]
    assert.equal(get.method, 'GET'); assert.equal(get.url, '/api/coop/invite/request')
    assert.equal(get.data.requestId, payload.requestId)
    get.success({ code: 200, data: requestReceipt(post) })
    assert.equal(env.storage.size, 0); assert.equal(env.page.data.unknownTargets.length, 0)
    assert.match(env.page.data.sendReceipt, /9223372036854775807/)
    get.fail({}); post.success({ code: 200, data: { ...requestReceipt(post, { inviteId: '88' }), replayed: false } })
    env.page._setSelected([{ toId: 11, name: '甲店' }]); env.page.confirmInvite()
    assert.equal(env.requests.filter(r => r.method === 'POST').length, 1)
  } finally { env.restore() }
})

for (const bad of [
  { state: 'NOT_FOUND' }, { state: 'UNAVAILABLE' }, { state: 'CREATED', inviteId: 9007199254740992 },
  { state: 'CREATED', inviteId: '9223372036854775808' }, { state: 'CREATED', toId: '12' },
  { state: 'CREATED', topicId: '10' }, { state: 'CREATED', scope: 'MERCHANT' },
  { state: 'CREATED', requestId: 'wrong-key' }, { state: 'CREATED', inviteType: 0, toType: 'merchant' },
]) test('W4负向或错配精确回读不结算 ' + JSON.stringify(bad), () => {
  const env = prepare(loadInvitePage())
  try {
    env.page.confirmInvite(); const post = env.requests[0]; post.fail({})
    const original = JSON.stringify([...env.storage])
    queryTarget(env)
    let receipt = requestReceipt(post, bad)
    if (bad.state !== 'CREATED') receipt = { state: bad.state, requestId: JSON.parse(post.data).requestId }
    env.requests[1].success({ code: 200, data: receipt })
    assert.equal(JSON.stringify([...env.storage]), original)
    assert.equal(env.page.data.unknownTargets.length, 1)
    env.page._setSelected([{ toId: 11, name: '甲店' }]); env.page.confirmInvite()
    assert.equal(env.requests.filter(r => r.method === 'POST').length, 1)
  } finally { env.restore() }
})

for (const ending of ['403', 'network', 'account', 'token', 'topic', 'hide']) test('W4查询 ' + ending + ' 不解锁或回填旧上下文', () => {
  const env = prepare(loadInvitePage())
  try {
    env.page.confirmInvite(); const post = env.requests[0]; post.fail({}); queryTarget(env)
    const get = env.requests[1], original = JSON.stringify([...env.storage])
    if (ending === 'account') env.app._memberId = 8
    if (ending === 'token') env.app._authorization = 'another-token'
    if (ending === 'topic') env.page.data.topicId = 10
    if (ending === 'hide') env.page.onHide()
    const before = JSON.stringify(env.page.data)
    if (ending === '403') get.successStatusAbnormal({ code: 403 }, 403)
    else if (ending === 'network') get.fail({})
    else get.success({ code: 200, data: requestReceipt(post) })
    assert.equal(JSON.stringify([...env.storage]), original)
    if (!['403', 'network'].includes(ending)) assert.equal(JSON.stringify(env.page.data), before)
    assert.equal(env.requests.filter(r => r.method === 'POST').length, 1)
  } finally { env.restore() }
})

test('W4重新进入可直接查原请求，旧无绑定UNKNOWN仅人工核对，存储故障不发GET或POST', () => {
  const storage = new Map(); let env = prepare(loadInvitePage({ storage }))
  let post
  try {
    env.page.confirmInvite(); post = env.requests[0]; post.fail({}); env.restore()
    env = prepare(loadInvitePage({ storage })); queryTarget(env)
    assert.equal(env.requests.length, 1); assert.equal(env.requests[0].method, 'GET')
    env.requests[0].success({ code: 200, data: requestReceipt(post) }); assert.equal(storage.size, 0)
    env.restore(); env = prepare(loadInvitePage({ storage })); env.page.confirmInvite(); env.requests[0].fail({})
    for (const row of storage.values()) delete row.requestIdentityVersion
    queryTarget(env); assert.equal(env.requests.length, 1); assert.match(env.page.data.receiptError, /旧.*人工核对/)
    env.page._setSelected([{ toId: 11, name: '甲店' }]); env.page.confirmInvite()
    assert.equal(env.requests.length, 1); assert.equal(storage.size, 1)
  } finally { env.restore() }
  const broken = prepare(loadInvitePage({ storageFailure: 'read' }))
  try { queryTarget(broken); assert.equal(broken.requests.length, 0); assert.match(broken.page.data.receiptError, /无法读取/) }
  finally { broken.restore() }
})

test('W4同key409不可误当明确失败，不清原记录或允许新POST', () => {
  const env = prepare(loadInvitePage())
  try {
    env.page.confirmInvite(); env.requests[0].success({ code: 409, msg: 'COOP_REQUEST_CONFLICT' })
    assert.equal(env.storage.size, 1); assert.equal(env.page.data.unknownTargets.length, 1)
    env.page._setSelected([{ toId: 11, name: '甲店' }]); env.page.confirmInvite()
    assert.equal(env.requests.length, 1)
  } finally { env.restore() }
})

test('W4跨设备枚举显式分页，全部UNAVAILABLE仍沿原游标前进；匹配当前主题的已创建回执阻止重邀', () => {
  const env = prepare(loadInvitePage())
  try {
    env.page.discoverOriginalInvites(); const first = env.requests[0]
    assert.equal(first.method, 'GET'); assert.equal(first.url, '/api/coop/invite/requests')
    assert.deepEqual(first.data, { limit: 20 })
    first.success({ code: 200, data: { rows: [{ state: 'UNAVAILABLE', requestId: 'other' }], nextCursor: '100', hasMore: true } })
    assert.equal(env.requests.length, 1, '没有自动扫描下一页')
    env.page.moreOriginalInvites(); const second = env.requests[1]
    assert.deepEqual(second.data, { limit: 20, before: '100' })
    second.success({ code: 200, data: { rows: [{ state: 'CREATED', requestId: 'device-two', inviteId: '90',
      inviteType: 1, topicId: '9', toType: 'club', toId: '11', scope: '' }], nextCursor: null, hasMore: false } })
    assert.match(env.page.data.sendReceipt, /邀请 #90/); assert.equal(env.page.data.selected.length, 0)
    env.page._setSelected([{ toId: 11, name: '甲店' }]); env.page.confirmInvite(); env.page.moreOriginalInvites()
    assert.equal(env.requests.length, 2); assert.equal(env.storage.size, 0)
  } finally { env.restore() }
})

for (const cursor of ['100', '101', '00099', 99]) test('W4错乱分页游标不处理同页正向回执 ' + cursor, () => {
  const env = prepare(loadInvitePage())
  try {
    env.page.confirmInvite(); const post = env.requests[0]; post.fail({})
    env.page.discoverOriginalInvites()
    env.requests[1].success({ code: 200, data: { rows: [], nextCursor: '100', hasMore: true } })
    env.page.moreOriginalInvites()
    env.requests[2].success({ code: 200, data: { rows: [requestReceipt(post)], nextCursor: cursor, hasMore: true } })
    assert.equal(env.storage.size, 1); assert.equal(env.page.data.unknownTargets.length, 1)
    assert.match(env.page.data.receiptError, /分页/)
  } finally { env.restore() }
})

test('W4正向回执遇本机删除故障保留待确认，不放行下一次POST', () => {
 const options = {}, env = prepare(loadInvitePage(options))
 try {
  env.page.confirmInvite(); const post = env.requests[0]; post.fail({}); options.removeFailure = true
  queryTarget(env); env.requests[1].success({ code: 200, data: requestReceipt(post) })
  assert.equal(env.storage.size, 1); assert.equal(env.page.data.unknownTargets.length, 1)
  assert.match(env.page.data.receiptError, /仍待确认/)
  env.page._setSelected([{ toId: 11, name: '甲店' }]); env.page.confirmInvite()
  assert.equal(env.requests.filter(r => r.method === 'POST').length, 1)
 } finally { env.restore() }
})

for (const patch of [{ inviteId: 9007199254740992 }, { inviteId: '9223372036854775808' }, { requestId: 'different' }, { replayed: undefined },
  { state: undefined }, { state: 'NOT_FOUND' }, { topicId: undefined }, { toType: 'shop' }, { scope: undefined }]) {
 test('W4 POST不完整或不精确的keyed成功回包保留UNKNOWN '+JSON.stringify(patch), () => {
  const env = prepare(loadInvitePage())
  try {
   env.page.confirmInvite(); const post = env.requests[0]
   post.success({ code: 200, data: { ...requestReceipt(post), replayed: false, ...patch } })
   assert.equal(env.storage.size, 1); assert.equal(env.page.data.unknownTargets.length, 1)
  } finally { env.restore() }
 })
}
test('W8 POST成功按服务端真实契约解析：完整CREATED回执才清待办，缺状态与主体的简化回包保留UNKNOWN', () => {
 const env = prepare(loadInvitePage())
 try {
  env.page.confirmInvite(); const post = env.requests[0], requestId = JSON.parse(post.data).requestId
  post.success({ code: 200, data: { inviteId: '1', requestId, replayed: false } })
  assert.equal(env.storage.size, 1); assert.equal(env.page.data.unknownTargets.length, 1)
 } finally { env.restore() }
 const real = prepare(loadInvitePage())
 try {
  real.page.confirmInvite(); const post = real.requests[0]
  post.success({ code: 200, msg: '已发起', data: { ...requestReceipt(post), replayed: true } })
  assert.equal(real.storage.size, 0); assert.equal(real.page.data.unknownTargets.length, 0)
 } finally { real.restore() }
})
test('W4只读调用同步异常结束loading并保留UNKNOWN', () => {
 const env = prepare(loadInvitePage())
 try {
  env.page.confirmInvite(); env.requests[0].fail({}); env.app.sendRequest = () => { throw Error('fixture') }
  queryTarget(env); assert.equal(env.page.data.receiptLoading, false); assert.match(env.page.data.receiptError, /暂无法查询/)
  assert.equal(env.storage.size, 1); assert.equal(env.page.data.unknownTargets.length, 1)
 } finally { env.restore() }
})

for (const code of ['2e2', '4e2', ' 200', '0200']) test('W4畸形状态码不按成功或明确失败清待办 '+code, () => {
 const env = prepare(loadInvitePage())
 try {
  env.page.confirmInvite(); const post = env.requests[0]
  post.success({ code, data: { ...requestReceipt(post), replayed: false } })
  assert.equal(env.storage.size, 1); assert.equal(env.page.data.unknownTargets.length, 1)
  queryTarget(env); env.requests[1].success({ code, data: requestReceipt(post) })
  assert.equal(env.storage.size, 1); assert.equal(env.page.data.unknownTargets.length, 1)
  assert.match(env.page.data.receiptError, /暂无法查询/)
 } finally { env.restore() }
})

test('据点邀请原请求身份包含冻结据点，历史无据点键保持原格式', () => {
  const request = require('../../utils/coop-invite-request.js')
  const legacy = { inviteType: 0, topicId: '17', toType: 'merchant', toId: '23', scope: '' }
  assert.equal(request.writeKey('7', legacy), '7:coop-invite:[0,"","17","23"]')
  const first = { ...legacy, supplyStationId: '31' }, second = { ...legacy, supplyStationId: '32' }
  assert.notEqual(request.writeKey('7', first), request.writeKey('7', second))
  assert.notEqual(request.writeKey('7', first), request.writeKey('7', legacy))
  const row = { state: 'CREATED', requestId: 'original_1', inviteId: '41', inviteType: 0,
    topicId: '17', toType: 'merchant', toId: '23', scope: '', supplyStationId: '31' }
  assert.equal(request.matches(request.receipt(row), first), true)
  assert.equal(request.matches(request.receipt(row), second), false)
  const old = { ...row }; delete old.supplyStationId
  assert.equal(request.matches(request.receipt(old), first), false, '旧回执不能补造据点证明')
  assert.equal(request.matches(request.receipt(old), legacy), true)
  assert.equal(request.receipt({ ...row, supplyStationId: 31 }), null, '新据点ID沿原精确字符串投影')
  assert.equal(request.receipt({ ...row, inviteType: 1, toType: 'club' }), null)
})

test('结果未知保留确认时原payload和key，不能用选择器未来据点重建原号', () => {
  const source = JS()
  assert.match(source, /target\.payload \|\| this\._invitePayload\(target\.toId\)/)
  assert.match(source, /unknownTargets\.push\(\{ toId: target\.toId, name: target\.name, key: target\.key, payload: target\.payload \}\)/)
  assert.match(source, /pending\.requestId !== row\.requestId/)
  assert.match(source, /payload\.supplyStationId && \(!pending \|\| pending\.requestId !== row\.requestId\)/)
})
