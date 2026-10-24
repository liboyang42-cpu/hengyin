const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const JS_PATH = 'pages/coop/settlement-detail/index.js'
const WXML = fs.readFileSync(path.join(ROOT, 'pages/coop/settlement-detail/index.wxml'), 'utf8')
const {
  buildMerchantSettlementDetail,
  buildHostSettlementDetail,
  buildClubSettlementDetail,
} = require(path.join(ROOT, 'pages/coop/components/settlement-detail/view-model.js'))

function merchantRow(overrides = {}) {
  return Object.assign({
    id: 9,
    topicId: 7,
    topicName: '外滩夜行',
    verifiedHeads: 0,
    verifiedSales: 0,
    shareMode: 1,
    shareRate: 0,
    amount: 0,
    status: 0,
  }, overrides)
}

function financeRow(overrides = {}) {
  return Object.assign({
    topicId: 7,
    topicName: '外滩夜行',
    settled: false,
    totalSales: 0,
    verifiedSales: 0,
    platformAmount: 0,
    merchantTotal: 0,
    myIncome: null,
    myIncomeArrived: false,
  }, overrides)
}

// CU-C-41:一行俱乐部视角的俱乐部结算汇总(字段全是服务端已格式化字符串)。
function clubRow(overrides = {}) {
  return Object.assign({
    id: 31,
    topicId: 990028,
    name: 'E2E 探店日一期',
    originalAmountText: '¥ 130.00',
    executedAdjustmentText: '¥ -2.00',
    netAmountText: '¥ 128.00',
    amountText: '¥ 128.00',
    amountStatus: 'verified',
    arrivedText: '预计 12月31日',
    paidText: '待入账',
    status: 'pending',
  }, overrides)
}

function loadPage(mutate, realTransport) {
  let source = fs.readFileSync(path.join(ROOT, JS_PATH), 'utf8')
  if (mutate) {
    const changed = mutate(source)
    assert.notEqual(changed, source, '负控锚点失效')
    source = changed
  }
  const requests = []
  let stops = 0
  let definition
  const app = {
    _memberId:1, getUserID() { return this._memberId },
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    sendRequest: (options) => requests.push(options),
  }
  if (realTransport) {
    const { createRequestClient } = require(path.join(ROOT,'utils/transport/request-client.js'))
    const client = createRequestClient({ wxRequest: options => { requests.push(options); return {abort(){}} },
      getBaseUrl: () => 'http://127.0.0.1', getUserID: () => app._memberId,
      reLogin: typeof realTransport === 'object' ? cb => { app._memberId = realTransport.loginMember || 2; cb(true,{isCurrentSession:() => true}) } : undefined })
    app.sendRequest = options => client.send(options)
  }
  const sandbox = {
    getApp: () => app,
    getCurrentPages: () => [{}, {}],
    Page: (value) => { definition = value },
    require: (id) => {
      if (id.includes('merchant-theme')) return { merchantPageShow() {}, merchantPageRestore() {} }
      if (id.includes('view-model')) {
        return {
          buildMerchantSettlementDetail: (row) => ({ built: 'merchant', id: row.id, revision: row.revision }),
          buildHostSettlementDetail: (row) => ({ built: 'host', id: row.topicId, revision: row.revision }),
          buildClubSettlementDetail: (row) => ({ built: 'club', id: row.topicId, revision: row.revision }),
        }
      }
      return require(path.resolve(path.dirname(path.join(ROOT, JS_PATH)), id))
    },
    wx: {
      stopPullDownRefresh: () => { stops += 1 },
      navigateBack() {},
      reLaunch() {},
    },
  }
  vm.runInNewContext(source, sandbox, { filename: JS_PATH })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch) },
  })
  return { page, app, requests, stops: () => stops }
}

test('缺少记录标识时停在缺参终态，不发送无法成立的请求', () => {
  const { page, requests } = loadPage()
  page.onLoad({ source: 'finance' })
  assert.equal(requests.length, 0)
  assert.equal(page.data.loadState, 'missing-param')
})

test('首载 single-flight；下拉刷新保留旧详情，失败原位恢复且不被旧回调覆盖', () => {
  const { page, requests, stops } = loadPage()
  page.onLoad({ source: 'finance', topicId: '7' })
  assert.equal(page.data.loadState, 'loading')
  assert.equal(requests.length, 1)

  page.load()
  assert.equal(requests.length, 1, '同步重入不得并发请求')
  requests[0].success({ code: 200, data: { topics: [financeRow({ revision: 'old' })] } })
  assert.equal(page.data.loadState, 'ready')
  assert.equal(page.data.detail.revision, 'old')

  page.onPullDownRefresh()
  assert.equal(requests.length, 2)
  assert.equal(page.data.loadState, 'refreshing')
  assert.equal(page.data.detail.revision, 'old', '刷新不能先清掉可读详情')
  requests[1].fail({ errMsg: 'request:fail timeout' })
  assert.equal(page.data.loadState, 'stale-error')
  assert.equal(page.data.errorKind, 'network')
  assert.equal(page.data.detail.revision, 'old')
  assert.ok(stops() >= 1)

  page.retryLoad()
  assert.equal(requests.length, 3)
  assert.equal(page.data.loadState, 'refreshing')
  requests[1].fail({ errMsg: 'late duplicate callback' })
  assert.equal(page.data.loadState, 'refreshing', '上一航班迟到回调不得覆盖重试')
  requests[2].success({ code: 200, data: { topics: [financeRow({ revision: 'new' })] } })
  assert.equal(page.data.loadState, 'ready')
  assert.equal(page.data.detail.revision, 'new')
})

test('成功响应没有目标记录时是不可见终态，不伪装成断网', () => {
  const { page, requests } = loadPage()
  page.onLoad({ source: 'ledger', id: '99' })
  requests[0].success({ code: 200, data: { settlements: [] } })
  assert.equal(page.data.loadState, 'missing-record')
  assert.equal(page.data.errorKind, 'data')
})

test('code=200 但缺少列表或行身份时是可重试数据错误，不冒充记录不存在', () => {
  for (const data of [{}, { settlements: [{}] }]) {
    const { page, requests } = loadPage()
    page.onLoad({ source: 'ledger', id: '99' })
    requests[0].success({ code: 200, data })
    assert.equal(page.data.loadState, 'error')
    assert.equal(page.data.errorKind, 'data')
    assert.match(page.data.errorText, /结算详情/)
  }
})

test('商家结算记录的核销人数或分成比例未知时不得伪装成 0', () => {
  for (const row of [
    merchantRow({ verifiedHeads: null }),
    merchantRow({ shareRate: null }),
  ]) {
    const { page, requests } = loadPage()
    page.onLoad({ source: 'ledger', id: '9' })
    requests[0].success({ code: 200, data: { settlements: [row] } })
    assert.equal(page.data.loadState, 'error')
    assert.equal(page.data.detail, null)
    assert.match(page.data.errorText, /结算详情/)
  }
})

test('主办结算记录的 settled 未知时不得伪装成待结算', () => {
  const { page, requests } = loadPage()
  page.onLoad({ source: 'finance', topicId: '7' })
  requests[0].success({ code: 200, data: { topics: [financeRow({ settled: null })] } })
  assert.equal(page.data.loadState, 'error')
  assert.equal(page.data.detail, null)
})

test('结算金额字段的 boolean、array 与空白字符串不得被强转成真实零值', () => {
  const merchantCases = [
    { id: false },
    { verifiedHeads: false },
    { amount: [] },
    { verifiedSales: ' ' },
    { shareRate: false },
  ]
  for (const overrides of merchantCases) {
    const { page, requests } = loadPage()
    page.onLoad({ source: 'ledger', id: '9' })
    requests[0].success({ code: 200, data: { settlements: [merchantRow(overrides)] } })
    assert.equal(page.data.loadState, 'error', JSON.stringify(overrides))
    assert.equal(page.data.detail, null, JSON.stringify(overrides))
  }

  const financeCases = [
    { topicId: [] },
    { totalSales: false },
    { platformAmount: ' ' },
    { settled: true, myIncome: [] },
  ]
  for (const overrides of financeCases) {
    const { page, requests } = loadPage()
    page.onLoad({ source: 'finance', topicId: '7' })
    requests[0].success({ code: 200, data: { topics: [financeRow(overrides)] } })
    assert.equal(page.data.loadState, 'error', JSON.stringify(overrides))
    assert.equal(page.data.detail, null, JSON.stringify(overrides))
  }
})

test('负控：结算数字校验退回 Number 强制转换时，畸形资金响应必须变红', () => {
  assert.throws(() => {
    const { page, requests } = loadPage((source) => source.replace(
      `function isFiniteNumber(value) {
  return typeof value === 'number' && Number.isFinite(value);
}`,
      `function isFiniteNumber(value) {
  if (value == null || value === '') return false;
  return Number.isFinite(Number(value));
}`,
    ))
    page.onLoad({ source: 'ledger', id: '9' })
    requests[0].success({ code: 200, data: { settlements: [merchantRow({
      verifiedSales: false,
      shareRate: false,
      amount: false,
    })] } })
    assert.equal(page.data.loadState, 'error', '畸形资金响应必须进入可恢复数据错误态')
  }, /畸形资金响应必须进入可恢复数据错误态/)
})

test('已有详情刷新到未知资金字段时保留旧值并进入 stale-error', () => {
  const { page, requests } = loadPage()
  page.onLoad({ source: 'ledger', id: '9' })
  requests[0].success({ code: 200, data: { settlements: [merchantRow({ revision: 'known' })] } })
  assert.equal(page.data.loadState, 'ready')
  assert.equal(page.data.detail.revision, 'known')

  page.onPullDownRefresh()
  requests[1].success({ code: 200, data: { settlements: [merchantRow({ verifiedHeads: null })] } })
  assert.equal(page.data.loadState, 'stale-error')
  assert.equal(page.data.detail.revision, 'known')
})

// CU-C-41(用户裁决 A):俱乐部收款行走俱乐部视角 —— source=club 按 topicId 从
// /api/club/settlement/summary 命中。病:原来跳 source=finance,而那条线只在
// 「我发起 且 我是该主题发布者」的 /api/coop/finance 里找行,受益方是俱乐部时永不可见。
test('CU-C-41:source=club 按 topicId 从俱乐部结算汇总命中,走专用请求', () => {
  const { page, requests } = loadPage()
  page.onLoad({ source: 'club', topicId: '990028', clubId: '9' })
  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/club/settlement/summary')
  assert.deepEqual(JSON.parse(requests[0].data), { clubId: 9 })
  assert.equal(page.data.source, 'club')

  requests[0].success({ code: 200, data: { topics: [clubRow()] } })
  assert.equal(page.data.loadState, 'ready')
  assert.equal(page.data.detail.built, 'club')
  assert.equal(page.data.detail.id, 990028, '按 topicId 命中，不是结算行 id')
})

// 集成复审:俱乐部汇总是「一条结算记录一行」,同一主题可能多行 —— 带了行 id 就必须按 id 取,
// 否则点第二张卡会显示第一张的金额。
test('CU-C-41:同一主题多条结算记录时按行 id 命中,不按 topicId 取第一条', () => {
  const { page, requests } = loadPage()
  page.onLoad({ source: 'club', topicId: '990028', id: '32', clubId: '9' })
  requests[0].success({ code: 200, data: { topics: [
    clubRow({ id: 31, revision: 'row-31' }),
    clubRow({ id: 32, revision: 'row-32' }),
  ] } })
  assert.equal(page.data.loadState, 'ready')
  assert.equal(page.data.detail.revision, 'row-32')
})

test('CU-C-41:source=club 缺 clubId 停在缺参终态,不发注定失败的请求', () => {
  const { page, requests } = loadPage()
  page.onLoad({ source: 'club', topicId: '990028' })
  assert.equal(requests.length, 0)
  assert.equal(page.data.loadState, 'missing-param')

  const bad = loadPage()
  bad.page.onLoad({ source: 'club', topicId: '990028', clubId: '0' })
  assert.equal(bad.requests.length, 0, 'clubId=0 也取不到数，不得发请求')
  assert.equal(bad.page.data.loadState, 'missing-param')
})

test('CU-C-41:俱乐部行的金额/状态不齐时判数据错误,不把缺字段渲染成金额', () => {
  for (const overrides of [
    { status: 'pending', amountStatus: 'unverified' },
    { amountStatus: 'verified', amountText: null },
    { name: '' },
    { paidText: undefined },
    { topicId: 0 },
  ]) {
    const { page, requests } = loadPage()
    page.onLoad({ source: 'club', topicId: '990028', clubId: '9' })
    requests[0].success({ code: 200, data: { topics: [clubRow(overrides)] } })
    assert.equal(page.data.loadState, 'error', JSON.stringify(overrides))
    assert.equal(page.data.detail, null, JSON.stringify(overrides))
  }
})

test('CU-C-41:俱乐部视角不挂商家守卫(后端按 clubId + finance:read 准入)', () => {
  assert.match(WXML, /<cy-access-gate[^>]*wx:if="\{\{source === 'ledger'\}\}"/, '商家守卫只能盖商家记录线')
  assert.doesNotMatch(WXML, /source !== 'finance'/, '新来源一律不得再落到商家守卫里')
})

test('CU-C-41 负控:俱乐部视角退回「按发布者查 /api/coop/finance」即判红', () => {
  assert.throws(() => {
    const { page, requests } = loadPage((source) => source.replace(
      "url: club ? '/api/club/settlement/summary' : (finance ? '/api/coop/finance' : '/api/coop/mybiz'),",
      "url: (finance || club) ? '/api/coop/finance' : '/api/coop/mybiz',",
    ))
    page.onLoad({ source: 'club', topicId: '990028', clubId: '9' })
    assert.equal(requests[0].url, '/api/club/settlement/summary', '俱乐部分润行必须走俱乐部结算汇总')
  }, /俱乐部分润行必须走俱乐部结算汇总/)
})

test('CU-C-41:俱乐部视角视图模型透传已格式化金额,未知不伪造', () => {
  const ready = buildClubSettlementDetail(clubRow())
  assert.equal(ready.perspective, '俱乐部分润')
  assert.equal(ready.title, 'E2E 探店日一期')
  assert.equal(ready.amount, '¥ 128.00')
  assert.equal(ready.status, '待入账')
  assert.deepStrictEqual(ready.lines.map((item) => item.value), ['¥ 130.00', '¥ -2.00', '¥ 128.00'])

  const unverified = buildClubSettlementDetail(clubRow({ status: 'settled', amountStatus: 'unverified', amountText: null }))
  assert.equal(unverified.amount, '—', '未核验不伪造成 0，也不算半个数')
  assert.equal(unverified.status, '待核验')

  const voided = buildClubSettlementDetail(clubRow({ status: 'void' }))
  assert.equal(voided.status, '已作废')
  assert.equal(voided.amountLabel, '作废前核算净额')
})

test('视图模型守住 unknown 与真实零值的边界', () => {
  const unknownMerchant = buildMerchantSettlementDetail(merchantRow({ verifiedHeads: null, shareRate: null }))
  assert.equal(unknownMerchant.lines.some((item) => item.value === '0 人'), false)
  assert.equal(unknownMerchant.lines.some((item) => item.value === '分成 · 0%'), false)

  const zeroMerchant = buildMerchantSettlementDetail(merchantRow())
  assert.equal(zeroMerchant.lines.find((item) => item.key === 'verifiedHeads').value, '0 人')
  assert.equal(zeroMerchant.lines.find((item) => item.key === 'shareRule').value, '分成 · 0%')

  const unknownHost = buildHostSettlementDetail(financeRow({ settled: null }))
  assert.equal(unknownHost.status, '状态待确认')
  assert.equal(unknownHost.timeline.some((item) => item.label === '主题待结算'), false)

  const pendingHost = buildHostSettlementDetail(financeRow({ settled: false }))
  assert.equal(pendingHost.status, '待结算')
})

test('视图模型不得把 boolean、array 与空白字符串格式化成零金额', () => {
  for (const value of [false, [], ' ']) {
    const merchant = buildMerchantSettlementDetail(merchantRow({
      verifiedHeads: value,
      verifiedSales: value,
      shareRate: value,
      amount: value,
    }))
    assert.equal(merchant.amount, '—', JSON.stringify(value))
    assert.equal(merchant.lines.some((item) => item.key === 'verifiedHeads'), false, JSON.stringify(value))
    assert.equal(merchant.lines.some((item) => item.key === 'verifiedSales'), false, JSON.stringify(value))
    assert.equal(merchant.lines.find((item) => item.key === 'shareRule').value, '分成', JSON.stringify(value))

    const host = buildHostSettlementDetail(financeRow({
      settled: true,
      totalSales: value,
      verifiedSales: value,
      platformAmount: value,
      merchantTotal: value,
      myIncome: value,
    }))
    assert.equal(host.amount, '—', JSON.stringify(value))
    assert.equal(host.status, '金额待确认', JSON.stringify(value))
    assert.equal(host.lines.some((item) => item.value === '¥0.00'), false, JSON.stringify(value))
  }
})

test('页面结构区分首载、陈旧刷新、缺参、不可见与可重试错误', () => {
  assert.match(WXML, /wx:if="\{\{detail\}\}"[\s\S]*loadState === 'refreshing'[\s\S]*loadState === 'stale-error'/)
  assert.match(WXML, /<cy-empty\b[^>]*wx:elif="\{\{loadState === 'missing-param'\}\}"[^>]*kind="missing-param"/s)
  assert.match(WXML, /<cy-empty\b[^>]*wx:elif="\{\{loadState === 'missing-record'\}\}"[^>]*kind="empty"/s)
  assert.match(WXML, /<cy-error\b[^>]*bind:retry="retryLoad"/s)
})

test('epoch 判据负控：移除迟到回调保护后旧失败会覆盖新刷新', () => {
  const { page, requests } = loadPage((source) => source.replaceAll(
    'if (epoch !== this._loadEpoch) return false;',
    'if (false) return false;'
  ))
  page.onLoad({ source: 'finance', topicId: '7' })
  requests[0].fail({ errMsg: 'first' })
  page.retryLoad()
  requests[0].fail({ errMsg: 'late' })
  assert.notEqual(page.data.loadState, 'loading', '负控应复现旧回调写入')
})

for (const sample of [
    {options:{source:'ledger',id:'9'},data:{settlements:[merchantRow({revision:'old'})]}},
    {options:{source:'finance',topicId:'7'},data:{topics:[financeRow({revision:'old'})]}},
    {options:{source:'club',id:'31',topicId:'990028',clubId:'9'},data:{topics:[clubRow({revision:'old'})]}},
  ]) {
  test(sample.options.source + '结算换号后旧详情清除，旧在途请求不挡新账号读取', () => {
    const e = loadPage(); e.page.onLoad(sample.options)
    e.requests[0].success({code:200,data:sample.data})
    e.page.load(); const old = e.requests[1]
    e.app._memberId = 2; e.page.onShow()
    assert.equal(e.requests.length,3,'换号不得被旧single-flight挡住')
    assert.equal(e.page.data.detail,null,'新账号读取中不得展示旧金额')
    assert.equal(e.page.data.loadState,'loading')
    const stops = e.stops()
    old.success({code:200,data:sample.data}); old.fail({errMsg:'request:fail timeout'})
    assert.equal(e.page.data.detail,null); assert.equal(e.page.data.loadState,'loading')
    assert.equal(e.stops(),stops,'旧回包不得结束新账号下拉刷新')
    const empty = sample.options.source === 'ledger' ? {settlements:[]} : {topics:[]}
    e.requests[2].success({code:200,data:empty})
    assert.equal(e.page.data.loadState,'missing-record')
  })
}

test('结算详情换号后不经onShow直接刷新也不保留旧金额', () => {
  const e = loadPage(); e.page.onLoad({source:'finance',topicId:'7'})
  e.requests[0].success({code:200,data:{topics:[financeRow({revision:'old'})]}})
  e.app._memberId = 2; e.page.retryLoad()
  assert.equal(e.page.data.detail,null)
  e.requests[1].fail({errMsg:'request:fail timeout'})
  assert.equal(e.page.data.loadState,'error','旧账号金额不得成为新账号stale-error')
})

test('结算详情同会员切角色后拒绝旧读取，返回按新角色刷新', () => {
  const e = loadPage(); e.page.onLoad({source:'finance',topicId:'7'})
  const old = e.requests[0]; e.app.globalData.role = 'club'
  old.success({code:200,data:{topics:[financeRow()]}})
  assert.equal(e.page.data.detail,null)
  e.page.onShow(); assert.equal(e.requests.length,2)
})

test('结算来源、记录、俱乐部或记录键变化后旧回包不落到新上下文', () => {
  for (const change of [
    e => { e.page.data.recordId = '8' },
    e => { e.page.data.source = 'ledger' },
    e => { e.page._clubId = 10 },
    e => { e.page._recordKey = 'id' },
  ]) {
    const e = loadPage(); e.page.onLoad({source:'finance',topicId:'7'})
    const old = e.requests[0]; change(e)
    old.success({code:200,data:{topics:[financeRow({topicId:Number(e.page.data.recordId)})]}})
    old.fail({errMsg:'request:fail timeout'})
    assert.equal(e.page.data.detail,null); assert.equal(e.page.data.loadState,'error')
    e.page.load(); assert.equal(e.requests.length,2,'上下文变化必须允许新的读取')
  }
})

test('结算详情隐藏时旧回包不落地，返回可重读；卸载后不再发送请求', () => {
  const e = loadPage(); e.page.onLoad({source:'finance',topicId:'7'})
  const old = e.requests[0]; e.page.onHide()
  const before = JSON.stringify(e.page.data)
  old.success({code:200,data:{topics:[financeRow()]}}); old.fail({errMsg:'request:fail timeout'})
  assert.equal(JSON.stringify(e.page.data),before)
  e.page.onShow(); assert.equal(e.requests.length,2)
  e.requests[1].success({code:200,data:{topics:[financeRow()]}})
  assert.equal(e.page.data.loadState,'ready')
  e.page.onUnload(); e.page.retryLoad()
  assert.equal(e.requests.length,2)
})

test('结算详情卸载后失败及成功无副作用，初次onShow不重复取数', () => {
  const e = loadPage(); e.page.onLoad({source:'finance',topicId:'7'})
  e.page.onShow(); assert.equal(e.requests.length,1)
  e.page.onUnload(); const before = JSON.stringify(e.page.data); const stops = e.stops()
  e.requests[0].success({code:200,data:{topics:[financeRow()]}})
  e.requests[0].fail({errMsg:'request:fail timeout'})
  assert.equal(JSON.stringify(e.page.data),before); assert.equal(e.stops(),stops)
})

test('未触发页面返回时，旧回包发现账号变化也会清除旧金额', () => {
  const e = loadPage(); e.page.onLoad({source:'finance',topicId:'7'})
  e.requests[0].success({code:200,data:{topics:[financeRow({revision:'old'})]}})
  e.page.load(); e.app._memberId = 2
  const stops = e.stops()
  e.requests[1].fail({errMsg:'request:fail timeout'})
  assert.equal(e.page.data.detail,null)
  assert.equal(e.page.data.loadState,'error')
  assert.equal(e.stops(),stops+1,'没有新请求时要结束本次旧身份刷新')
  e.page.retryLoad(); assert.equal(e.requests.length,3)
})

test('当前服务器明确拒绝权限时不保留旧金额，网络失败仍保留', () => {
  for (const fail of [false,true]) {
    const e = loadPage(); e.page.onLoad({source:'finance',topicId:'7'})
    e.requests[0].success({code:200,data:{topics:[financeRow({revision:'old'})]}})
    e.page.load()
    if (fail) e.requests[1].fail({statusCode:403,msg:'无权读取结算'})
    else e.requests[1].success({code:403,msg:'无权读取结算'})
    assert.equal(e.page.data.detail,null)
    assert.equal(e.page.data.loadState,'error')
    assert.match(e.page.data.errorText,/无权/)
  }
})

test('俱乐部真实业务500拒绝口径与请先登录也清除旧金额', () => {
  const java = fs.readFileSync(path.join(ROOT,'../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubSettlementController.java'),'utf8')
  for (const msg of ['仅主理人与联席主理人可查看分润','请先登录']) {
    assert.ok(java.includes('return error("'+msg+'")'),'负控使用实际后端拒绝文案')
    const e = loadPage(); e.page.onLoad({source:'club',id:'31',clubId:'9'})
    e.requests[0].success({code:200,data:{topics:[clubRow()]}})
    e.page.load(); e.requests[1].success({code:500,msg})
    assert.equal(e.page.data.detail,null)
    assert.equal(e.page.data.loadState,'error')
  }
})

test('真实请求封装把HTTP403放在fail第二参数时也清除旧金额', () => {
  const e = loadPage(null,true); e.page.onLoad({source:'finance',topicId:'7'})
  e.requests[0].success({statusCode:200,data:{code:200,data:{topics:[financeRow()]}}})
  assert.equal(e.page.data.loadState,'ready')
  e.page.load()
  e.requests[1].success({statusCode:403,data:{msg:'被禁止访问'}})
  assert.equal(e.page.data.detail,null)
  assert.equal(e.page.data.loadState,'error')
})

// 2026-10-24 owner 拍板「游客可浏览，写入时弹窗登录」:游客读私有结算被 401 拦下时不再静默 wx.login 建号,
// 页面停在可见的失败态、不出任何金额;登录只在写入动作或页面自己的登录入口里发生。
test('真实匿名首登完成后无需手动重试即可读取当前身份结算', () => {
  const e = loadPage(null,{}); e.app._memberId = ''
  e.page.onLoad({source:'finance',topicId:'7'})
  e.requests[0].success({statusCode:401,data:{code:401}})
  assert.equal(e.requests.length,1,'游客 401 不得静默登录后重读')
  assert.equal(e.app._memberId,'','游客读私有页不得建号')
  assert.equal(e.page.data.loadState,'error')
  assert.equal(e.page.data.detail,null)
})

test('成功码但金额字段畸形时不用“操作成功”充当错误说明', () => {
  const e = loadPage(); e.page.onLoad({source:'finance',topicId:'7'})
  e.requests[0].success({code:200,msg:'操作成功',data:{topics:[financeRow({settled:null})]}})
  assert.equal(e.page.data.loadState,'error')
  assert.equal(e.page.data.detail,null)
  assert.match(e.page.data.errorText,/结算详情/)
})

test('真实请求封装同账号重登后旧code2仍拒绝时清除旧金额', () => {
  const e = loadPage(null,{loginMember:1}); e.page.onLoad({source:'finance',topicId:'7'})
  e.requests[0].success({statusCode:200,data:{code:200,data:{topics:[financeRow()]}}})
  e.page.load(); e.requests[1].success({statusCode:200,data:{code:2,msg:'登录失效'}})
  e.requests[2].success({statusCode:200,data:{code:2,msg:'登录失效'}})
  assert.equal(e.page.data.detail,null)
  assert.equal(e.page.data.loadState,'error')
  assert.equal(e.requests.length,3,'旧鉴权失败不能无限重登')
})
