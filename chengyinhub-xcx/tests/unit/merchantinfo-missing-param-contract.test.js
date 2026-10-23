// 2026-09-16 截图冒烟:pages/topic/merchantinfo/merchantinfo 不带 id 打开时渲染出
// 「未命名路线 · 开放时间待定」的空壳主题页(三条入口分支全不命中:不请求、不报错、也没有出口),
// 而不是与其它详情页一致的缺参提示。
//
// 修法:onLoad 补 else 落 missingParam,wxml 在缺参时渲染 cy-empty kind="missing-param" + 返回出口,
// 并挡掉 project-host / project-join 两个空壳视图。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const PAGE = 'pages/topic/merchantinfo/merchantinfo'
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

function assertMissingParamBranch(js, wxml) {
  assert.match(js, /missingParam:\s*false/, 'data 必须有 missingParam 位')
  assert.match(js, /else\s*\{\s*\/\/[\s\S]{0,400}?missingParam:\s*true/, 'onLoad 必须为「id 与 topicId 都没有」落缺参态')
  const branch = wxml.match(/<block wx:if="\{\{\s*missingParam\s*\}\}">([\s\S]*?)<\/block>/)
  assert.ok(branch, 'wxml 必须有 missingParam 的独立分支')
  assert.match(branch[1], /cy-empty[^>]*kind="missing-param"/, '缺参分支必须用统一缺参空态')
  assert.match(branch[1], /bind:cta="goBack"/, '缺参态必须有一个真的走得通的出口')
  assert.match(wxml, /<project-host wx:elif="\{\{\s*role === 'host'/, '缺参分支必须接在 project-host 之前(条件链)')
  assert.doesNotMatch(wxml, /<project-host wx:if=/, 'project-host 不该还是链首的 wx:if')
}

function loadPage(source = read(PAGE + '.js')) {
  const requests = []
  let config = null
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44, authorization: "fixture-authenticated" },
    getUserID: () => 7,
    sendRequest: (options) => requests.push(options),
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    tips() {},
  }
  const wx = {
    getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
    showToast() {}, showLoading() {}, hideLoading() {}, showModal() {},
    navigateBack() {}, navigateTo() {}, redirectTo() {}, reLaunch() {}, switchTab() {}, pageScrollTo() {},
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
    getWindowInfo: () => ({ windowWidth: 375, statusBarHeight: 20 }),
    createSelectorQuery: () => ({ select: () => ({ boundingClientRect: () => ({ exec() {} }) }), in: () => ({ select: () => ({ boundingClientRect: () => ({ exec() {} }) }), exec() {} }) }),
  }
  const context = {
    console,
    getApp: () => app,
    getCurrentPages: () => [],
    Page: (value) => { config = value },
    require: (id) => require(id.startsWith('.') ? path.resolve(ROOT, PAGE.split('/').slice(0, -1).join('/'), id) : id),
    module: { exports: {} },
    exports: {},
    wx,
  }
  const sandbox = vm.createContext(context)
  vm.runInContext(source, sandbox, { filename: path.join(ROOT, PAGE + '.js') })
  assert.ok(config, 'merchantinfo 必须注册 Page')
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) })
  page.setData = (patch, callback) => { Object.assign(page.data, patch); if (callback) callback() }
  return { page, requests }
}

test('merchantinfo 缺 id/topicId ⇒ 落缺参态,而不是渲染空壳主题页', () => {
  assertMissingParamBranch(read(PAGE + '.js'), read(PAGE + '.wxml'))

  const { page, requests } = loadPage()
  page.onLoad({})
  assert.equal(page.data.missingParam, true, '两个参数都没有时必须落缺参态')
  assert.equal(requests.length, 0, '缺参态不请求是对的，但不能什么都不说')

  const withTopic = loadPage()
  withTopic.page.onLoad({ topicId: '22' })
  assert.equal(withTopic.page.data.missingParam, false, '带 topicId 的正常入口不受影响')
})

test('负控:删掉缺参 else 分支(退回空壳主题页)必须判红', () => {
  const source = read(PAGE + '.js')
  const broken = source.replace('this.setData({ missingParam: true });', '')
  assert.notEqual(broken, source, '变异锚点失效:未找到 onLoad 的缺参 else 分支')

  assert.throws(
    () => assertMissingParamBranch(broken, read(PAGE + '.wxml')),
    assert.AssertionError,
    '缺参分支被删掉后必须判红',
  )

  const shell = loadPage(broken)
  shell.page.onLoad({})
  assert.equal(shell.page.data.missingParam, false, '变异体会退回空壳(证明上一条断言不是恒真)')
})

// M-12:承接报名需要商家完整章节投影；managed 项目始终读取商家接口。
// 旧经典入口仅在商家详情判「不存在」时兼容读取一次公开详情。
// 权限、网络或 managed 项目失败不能通过切换接口隐藏；承接阶段不以评分、评论或票务选读接口。
const TOPIC_READ = /\/api\/topic\/info-to-(merchant|user)$/
const topicReads = (harness) => harness.requests.filter((request) => TOPIC_READ.test(request.url))

function assertMerchantProjection(source = read(PAGE + '.js')) {
  for (const managed of [false, true]) {
    const harness = loadPage(source)
    if (managed) {
      harness.page.data.topicId = 71
      harness.page.data.operationScope = 'CLUB'
      harness.page._managedChapterProject = true
      harness.page.loadBrowseData(false)
    } else {
      harness.page.onLoad({ id: '71', scope: 'MERCHANT' })
    }
    const reads = topicReads(harness)
    assert.equal(reads.length, 1)
    assert.equal(reads[0].url, '/api/topic/info-to-merchant', '商家承接和 managed 项目先读取完整商家投影')
    reads[0].success({ code: 200, data: { name: '招商主题', productType: 1, chaptersList: [{
      id: 1, required: true,
      nodes: [{ id: 8, name: '第一站' }, { id: 9, name: '第二站' }],
      blocks: [{ type: 'node', nodeId: 8 }, { type: 'node', nodeId: 9 }],
    }, { id: 2, required: true, nodes: [{ id: 10, name: '第三站' }], blocks: [{ type: 'node', nodeId: 10 }] }] } })
    assert.equal(harness.page.data.info.chaptersList.length, 2, '不能改读玩家 teaser 而丢掉后续章节')
    assert.equal(harness.page.data.info.chaptersList[0].guideBlocks.map((block) => block.title).join('|'), '第一站|第二站')
    assert.equal(harness.page.data.info.chaptersList[1].guideBlocks[0].title, '第三站')
    assert.equal(topicReads(harness).length, 1, '商家详情成功时不再读取公开详情')
  }
}

function assertLegacyPublicFallback(source = read(PAGE + '.js')) {
  const harness = loadPage(source)
  harness.page.onLoad({ id: '23', scope: 'MERCHANT' })
  topicReads(harness)[0].success({ code: 500, msg: '主题活动不存在' })
  const reads = topicReads(harness)
  assert.equal(reads.length, 2, '旧经典入口不存在时必须有一次公开兼容读取')
  assert.equal(reads[0].url, '/api/topic/info-to-merchant')
  assert.equal(reads[1].url, '/api/topic/info-to-user', '必须用 false 回退公开接口，不能重新请求商家接口')
  assert.equal(reads[1].data.id, 23)
  reads[1].success({ code: 500, msg: '主题活动不存在' })
  assert.equal(topicReads(harness).length, 2, '公开接口也不存在时必须收尾，不得循环读取')
  assert.equal(harness.page.data.browseLoadError, '主题活动不存在')
}

test('M-12:MERCHANT 和 managed 项目读取完整商家章节，成功后不切换公开投影', () => {
  assertMerchantProjection()
})

test('M-12:旧经典商家入口仅不存在时 false 一次公开兼容，公开失败不循环', () => {
  assertLegacyPublicFallback()
})

test('M-12:401、403、网络失败和 managed 不存在均保留错误，不回退公开接口', () => {
  for (const mode of [401, '401', 403, '403', 'network', 'managed', 'other']) {
    const harness = loadPage()
    harness.page.data.topicId = 23
    harness.page.data.operationScope = 'MERCHANT'
    harness.page._managedChapterProject = mode === 'managed'
    harness.page.loadBrowseData()
    const first = topicReads(harness)[0]
    assert.equal(first.url, '/api/topic/info-to-merchant')
    if (mode === 'network') first.fail()
    else first.success({ code: mode === 'managed' || mode === 'other' ? 500 : mode,
      msg: mode === 'other' ? '主题详情暂时无法读取' : '主题活动不存在' })
    assert.equal(topicReads(harness).length, 1, `${mode} 不得回退公开投影`)
    if (Number(mode) === 401 || Number(mode) === 403) {
      assert.equal(harness.page.data.projectReadAuth, Number(mode) === 401 ? 'login' : 'denied', `${mode} 必须展示独立身份恢复/拒绝态`)
      assert.equal(Object.keys(harness.page.data.info).length, 0, '身份拒绝不能保留旧主题投影')
    } else assert.ok(harness.page.data.browseLoadError, `${mode} 必须显示真实错误，不能伪装成功`)
  }
})

test('M-12:空 scope 和 CLUB 继续读取公开详情，不因不存在借用商家投影', () => {
  for (const scope of ['', 'CLUB']) {
    const harness = loadPage()
    harness.page.onLoad({ id: '23', scope })
    const reads = topicReads(harness)
    assert.equal(reads.length, 1)
    assert.equal(reads[0].url, '/api/topic/info-to-user')
    reads[0].success({ code: 500, msg: '主题活动不存在' })
    assert.equal(topicReads(harness).length, 1, '公开查看者不得借商家接口绕过可见性')
    assert.equal(harness.page.data.browseLoadError, '主题活动不存在')
  }
})

test('M-12 负控:删除兼容读取、改回 true 或倒转首读接口都必须判红', () => {
  const source = read(PAGE + '.js')
  const noFallback = source.replace('            that.loadBrowseData(false);\n            return;\n', '')
  assert.notEqual(noFallback, source, '删除回退的变异锚点失效')
  assert.throws(() => assertLegacyPublicFallback(noFallback), assert.AssertionError,
    '旧经典不存在时没有兼容出口必须判红')

  const repeatedMerchant = source.replace('that.loadBrowseData(false);', 'that.loadBrowseData(true);')
  assert.notEqual(repeatedMerchant, source, 'true 回退的变异锚点失效')
  assert.throws(() => assertLegacyPublicFallback(repeatedMerchant), assert.AssertionError,
    '再次请求商家接口不能冒充公开回退')

  const playerFirst = source.replace("? '/api/topic/info-to-merchant'\n        : '/api/topic/info-to-user'",
    "? '/api/topic/info-to-user'\n        : '/api/topic/info-to-merchant'")
  assert.notEqual(playerFirst, source, '首读接口的变异锚点失效')
  assert.throws(() => assertMerchantProjection(playerFirst), assert.AssertionError,
    '恢复旧玩家优先逻辑会缺商家完整章节，必须判红')
})
