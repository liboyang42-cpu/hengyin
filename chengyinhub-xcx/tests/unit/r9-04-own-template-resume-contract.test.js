// R9-04(P1):自己的模板详情无法继续应用。
//
// 审查复现(第九轮 R9-04):玩家 9004 从「我的模板」进入 6029 详情(scope=my)可读,
// 点「开始应用」后显示「模版不存在」——详情页跳转丢失 scope=my,编辑页
// (pages/publish/temp)用公共模板接口 /api/template/info 查个人草稿 ID。
//
// 契约:
//   · 详情页 goPrimary 必须把 scope=my 带到底;
//   · publish/temp 在 scope=my 时走 /api/template/author-myinfo(owner 可读草稿,大 ID 字符串化),其余仍走
//     /api/template/author-info;
//   · scope=my 不污染写链路身份:operationScope 仍只认 MERCHANT,草稿保存仍带原 id 续编。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')

const DETAIL_PAGE = '../../pages/templatedetail/templatedetail.js'
const TEMP_PAGE = '../../pages/publish/temp/index.js'

let appStub
let pageConfig
let navigations
let requests

function installGlobals() {
  appStub = {
    globalData: { nickname: '玩家9004', statusBarHeight: 20, navBarHeight: 44 },
    getUserRole: () => 'player',
    getUserType: () => 1,
    getUserID: () => 9004,
    getAuthorization: () => 'Bearer test',
    getRequestErrorMessage: (_res, fallback) => fallback,
    sendRequest: (options) => { requests.push(options); return { abort() {} } },
    chooseImage: () => {},
    chooseDocument: () => {},
    tips: () => {},
  }
  global.getApp = () => appStub
  global.wx = {
    getStorageSync: () => undefined,
    setStorageSync: () => {},
    removeStorageSync: () => {},
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    showToast: () => {},
    hideLoading: () => {},
    showLoading: () => {},
    nextTick: (cb) => cb(),
    navigateTo(options) { navigations.push({ method: 'navigateTo', url: options.url }) },
    redirectTo(options) { navigations.push({ method: 'redirectTo', url: options.url }) },
    navigateBack() {},
    reLaunch(options) { navigations.push({ method: 'reLaunch', url: options.url }) },
    switchTab(options) { navigations.push({ method: 'switchTab', url: options.url }) },
    createSelectorQuery: () => ({ in: () => ({ select: () => ({ boundingClientRect: () => ({ exec() {} }) }), exec() {} }) }),
  }
}

function loadPage(relative) {
  let config
  global.Page = (value) => { config = value }
  const absolute = require('node:path').resolve(__dirname, '../../', relative)
  delete require.cache[require.resolve(absolute)]
  require(absolute)
  const page = Object.assign({}, config)
  page.data = JSON.parse(JSON.stringify(config.data))
  page.setData = function (patch, callback) {
    Object.keys(patch).forEach((key) => {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
      let cursor = this.data
      for (let i = 0; i < parts.length - 1; i += 1) {
        if (cursor[parts[i]] == null) cursor[parts[i]] = {}
        cursor = cursor[parts[i]]
      }
      cursor[parts[parts.length - 1]] = patch[key]
    })
    if (callback) callback.call(this)
  }
  return page
}

function templateRequest() {
  return requests.filter((item) => /\/api\/template\/author-(info|myinfo)$/.test(String(item.url)))[0]
}

beforeEach(() => {
  requests = []
  navigations = []
  installGlobals()
})

test('RED 锚点:scope=my 的详情开始应用必须把 scope 带进编辑页', () => {
  const page = loadPage('pages/templatedetail/templatedetail.js')
  page.data.id = 6029
  page.data.scope = 'my'
  page.data.isMyScope = true
  page.data.info = { canEdit: true }

  page.goPrimary()

  assert.deepEqual(navigations, [{
    method: 'navigateTo',
    url: '/pages/publish/temp/index?id=6029&scope=my',
  }])
})

test('非本人或归属缺失的个人模板仅引导真实模板广场，不进入编辑或采用', () => {
  for (const canEdit of [false, undefined, null, 'true', 1]) {
    navigations = []
    const page = loadPage('pages/templatedetail/templatedetail.js')
    page.data.id = 6029
    page.data.scope = 'my'
    page.applyDetailPayload({ id: 6029, title: '他人的个人模板', memberId: 9005, canEdit })
    page.goPrimary()
    assert.equal(page.data.primaryLabel, '去模板广场选用')
    assert.notEqual(page.data.publisherText, '玩家9004')
    assert.deepEqual(navigations, [{ method: 'switchTab', url: '/pages/template/index' }])
    assert.equal(requests.length, 0)
  }
})

test('个人模板编辑深链缺明确编辑权限时返回只读详情，加载前后都不能保存', () => {
  for (const canEdit of [false, undefined, null, 'true', 1]) {
    for (const scope of ['', 'MERCHANT']) {
      requests = []
      navigations = []
      const page = loadPage('pages/publish/temp/index.js')
      page.onLoad({ id: '6029', scope: scope || 'my', templateScope: 'my' })
      page.validateForm = () => true
      page.saveDraft()
      page.publishGame()
      templateRequest().success({ code: 200, data: { id: 6029, title: '他人的个人模板', canEdit } })
      page.saveDraft()
      page.publishGame()
      assert.ok(page.data.loadError)
      assert.equal(page.data.formData.title, '')
      assert.deepEqual(navigations, [{ method: 'redirectTo', url: '/pages/templatedetail/templatedetail?id=6029&scope=my' + (scope ? '&operationScope=MERCHANT' : '') }])
      assert.equal(requests.filter(item => /\/api\/template\/(draft|publish|adopt-draft)$/.test(item.url)).length, 0)
    }
  }
})

test('公共库模板的应用跳转保持原样(不额外塞 scope)', () => {
  const page = loadPage('pages/templatedetail/templatedetail.js')
  page.data.id = 12
  page.data.scope = 'library'

  page.goPrimary()

  assert.deepEqual(navigations, [{
    method: 'navigateTo',
    url: '/pages/publish/temp/index?id=12',
  }])
})

test('商家公开详情应用后采用和作者回读仍使用商家作用域', () => {
  const detail = loadPage('pages/templatedetail/templatedetail.js')
  detail.onLoad({ id: '62', operationScope: 'MERCHANT' })
  detail.goPrimary()
  const url = new URL(navigations.at(-1).url, 'https://local.invalid')
  assert.equal(url.searchParams.get('scope'), 'MERCHANT')
  requests = []
  const editor = loadPage('pages/publish/temp/index.js')
  editor.onLoad(Object.fromEntries(url.searchParams))
  assert.equal(editor.data.isMyScope, false)
  const source = templateRequest()
  assert.equal(source.url, '/api/template/author-info')
  source.success({ code: 200, data: { id: 62, title: '今日城市签' } })
  const sourceVersion = requests.find(item => item.url === '/api/template/adoption-source')
  assert.equal(sourceVersion.data.scope, 'MERCHANT')
  sourceVersion.success({ code: 200, data: { originalTemplateId: '62', sourceVersion: 'a'.repeat(64) } })
  const adopt = requests.find(item => item.url === '/api/template/adopt-draft')
  const intent = JSON.parse(adopt.data)
  assert.equal(intent.scope, 'MERCHANT')
  adopt.success({ code: 200, data: { requestId: intent.requestId, originalTemplateId: '62',
    sourceVersion: intent.sourceVersion, status: 'COMPLETED', draftId: '990067' } })
  const readback = requests.find(item => item.url === '/api/template/adoption-detail')
  assert.equal(readback.data.requestId, intent.requestId)
  assert.equal(readback.data.scope, 'MERCHANT')
})

test('RED 锚点:编辑页带 scope=my 时查个人草稿接口,不再查公共库', () => {
  const page = loadPage('pages/publish/temp/index.js')
  page.onLoad({ id: '6029', scope: 'my' })

  const request = templateRequest()
  assert.ok(request, 'onLoad 必须发起模板详情请求')
  assert.equal(request.url, '/api/template/author-myinfo')
  assert.equal(request.data.id, 6029)
})

test('公共库应用仍查 /api/template/author-info,不受 my 分支影响', () => {
  const page = loadPage('pages/publish/temp/index.js')
  page.onLoad({ id: '12' })
  assert.equal(templateRequest().url, '/api/template/author-info')

  const merchant = loadPage('pages/publish/temp/index.js')
  merchant.onLoad({ id: '12', scope: 'MERCHANT' })
  assert.equal(templateRequest().url, '/api/template/author-info')
})

test('scope=my 不污染写链路身份:operationScope 仍只认 MERCHANT', () => {
  const page = loadPage('pages/publish/temp/index.js')
  page.onLoad({ id: '6029', scope: 'my' })
  assert.equal(page.data.operationScope, '')

  const merchant = loadPage('pages/publish/temp/index.js')
  merchant.onLoad({ id: '12', scope: 'MERCHANT' })
  assert.equal(merchant.data.operationScope, 'MERCHANT')
})

test('续编:个人草稿保存草稿时仍带原 id 走 /api/template/draft', () => {
  const page = loadPage('pages/publish/temp/index.js')
  page.onLoad({ id: '6029', scope: 'my' })
  templateRequest().success({ code: 200, data: { id: 6029, title: '我的草稿', canEdit: true } })
  page.data.formData.title = '我的草稿'
  page.validateForm = () => true

  page.saveDraft()

  const draft = requests.filter((item) => item.url === '/api/template/draft')[0]
  assert.ok(draft, '必须发起保存草稿请求')
  const body = JSON.parse(draft.data)
  assert.equal(body.id, 6029, '续编必须更新原草稿而不是另建一份')
  assert.equal(body.scope, '')
})

test('商家草稿列表、详情、继续编辑与保存返回完整保留作用域；个人入口不转商家', () => {
  for (const scope of ['', 'MERCHANT']) {
    requests = []
    navigations = []
    const list = loadPage('subpackageMember/mytemplate/mytemplate.js')
    list.onLoad({ scope })
    const listRequest = requests.find(item => item.url === '/api/template/my-list')
    assert.equal(listRequest.data.scope || '', scope)
    listRequest.success({ code: 200, data: { rows: [{ id: 6029, title: '已采用草稿' }], total: 1 } })
    listRequest.complete()
    list.goInfo({ currentTarget: { dataset: { index: 0 } } })
    const query = url => Object.fromEntries(new URL(url, 'https://local.invalid').searchParams)

    const detail = loadPage('pages/templatedetail/templatedetail.js')
    detail.onLoad(query(navigations.at(-1).url))
    assert.equal(requests.filter(item => item.url === '/api/template/myinfo').at(-1).data.scope || '', scope)
    requests.filter(item => item.url === '/api/template/myinfo').at(-1).success({ code: 200, data: { id: 6029, title: '已采用草稿', canEdit: true } })
    detail.goPrimary()

    const editor = loadPage('pages/publish/temp/index.js')
    editor.onLoad(query(navigations.at(-1).url))
    assert.equal(editor.data.isMyScope, true)
    assert.equal(editor.data.operationScope, scope)
    const readback = requests.filter(item => item.url === '/api/template/author-myinfo').at(-1)
    assert.equal(readback.data.scope || '', scope)
    readback.success({ code: 200, data: { id: 6029, title: '已采用草稿', canEdit: true, originalTemplateId: 16, validationMethod: 1 } })
    editor.validateForm = () => true
    editor.saveDraft()
    const body = JSON.parse(requests.filter(item => item.url === '/api/template/draft').at(-1).data)
    assert.equal(body.id, 6029)
    assert.equal(body.scope, scope)
    assert.equal(body.originalTemplateId, 16)

    const expected = '/subpackageMember/mytemplate/mytemplate' + (scope ? '?scope=MERCHANT' : '')
    editor.onResultSheetClose()
    assert.deepEqual(navigations.at(-1), { method: 'reLaunch', url: expected })
    editor.data.creationSuccess = { secondaryAction: 'templates' }
    editor._leaveCreationSuccess('secondaryAction')
    assert.deepEqual(navigations.at(-1), { method: 'reLaunch', url: expected })
    editor._adoptionUnconfirmed = true
    editor.inspectAdoptionTemplates()
    assert.deepEqual(navigations.at(-1), { method: 'reLaunch', url: expected })
  }
})

test('主题内玩法完整保存为私有草稿并回传呈现和规则，不独立发布', () => {
  const page = loadPage('pages/publish/temp/index.js')
  page.data.from = 'fabu'
  page.data.id = 41
  page.data.formData.title = '观察检定'
  page.prepareFormData = () => ({ title: '观察检定', advancedConfigJson: '{"present":"inline","diceRoll":{"enabled":true,"mode":"d20"}}' })
  let draftValidation
  page.validateForm = (_data, isDraft) => { draftValidation = isDraft; return true }
  let emitted
  page.getOpenerEventChannel = () => ({ emit: (event, value) => { emitted = { event, value } } })
  page.publishGame()
  assert.equal(draftValidation, false, '绑定玩法必须通过完整校验')
  const request = requests.find(item => item.url === '/api/template/draft')
  assert.ok(request)
  const body = JSON.parse(request.data)
  assert.equal(body.status, 0)
  // 后端 CmsTemplatePublishRequest.isSync 是 Integer。2026-09-22 走查实跑:这里发过 isSync:false,
  // 生产 Jackson 反序列化直接炸(HttpMessageNotReadableException),作者从发布页配任何故事玩法
  // 点「完成」都是 500「系统繁忙」—— 方法体都没进。前端单测 mock 了请求,布尔值从来碰不到后端。
  assert.strictEqual(body.isSync, 0, 'isSync 必须是整数 0，后端是 Integer,发布尔会 500')
  assert.equal(body.id, undefined, '首次编辑已发布模板应另存，不能降为草稿')
  request.success({ code: 200, data: 83 })
  assert.equal(emitted.event, 'templateCreated')
  assert.equal(emitted.value.id, 83)
  assert.equal(JSON.parse(emitted.value.advancedConfigJson).present, 'inline')
  assert.equal(emitted.value.draftStatus, 0)
})
