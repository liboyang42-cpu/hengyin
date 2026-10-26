'use strict'

// CU-M-68 采用公开玩法后保存草稿必须真落库。
//   病灶:data.id 在「本人草稿续编」时是 cms_member_template.id,从货架/公开库进来时却是
//         cms_template_library.id;saveDraft 不问来历一律把它发给 /api/template/draft,
//         后端按 cms_member_template + memberId 归属查行 → 必然抛错,草稿零写入。
//   修法:只有 scope=my 才带 id;采用态只带 originalTemplateId(后端据此新建草稿并保留来源)。
//
// CU-M-69 「保存前摘要」必须按当前表单算。
//   病灶:摘要直插预览状态机字段 pvMethodLabel,初值恰好是「无需验证」,只有预览走到
//         「点位任务」那一步才被覆盖 ⇒ 一打开预览先说无需验证,同屏第 3 步又要求作答;
//         时长也只印裸数字「5」。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')

const TEMP_PAGE = '../../pages/publish/temp/index.js'

let appStub
let requests
let storage

function installGlobals() {
  storage = {}
  appStub = {
    globalData: { nickname: '商家', statusBarHeight: 20, navBarHeight: 44 },
    getUserRole: () => 'merchant',
    getUserType: () => 2,
    getUserID: () => 9004,
    getAuthorization: () => 'Bearer test',
    getRequestErrorMessage: (_res, fallback) => fallback,
    sendRequest: (options) => { requests.push(options); if(options.url==='/api/template/adoption-source') options.success({code:200,data:{originalTemplateId:String(options.data.originalTemplateId),sourceVersion:'a'.repeat(64)}}); return { abort() {} } },
    chooseImage: () => {},
    chooseDocument: () => {},
    tips: () => {},
  }
  global.getApp = () => appStub
  global.wx = {
    getStorageSync: key => storage[key],
    setStorageSync: (key,value) => { storage[key]=value },
    removeStorageSync: key => { delete storage[key] },
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    showToast: () => {},
    hideLoading: () => {},
    showLoading: () => {},
    nextTick: (cb) => cb(),
    navigateTo() {},
    redirectTo() {},
    navigateBack() {},
    reLaunch() {},
    createSelectorQuery: () => ({ in: () => ({ select: () => ({ boundingClientRect: () => ({ exec() {} }) }), exec() {} }) }),
  }
}

function loadPage(relative) {
  let config
  global.Page = (value) => { config = value }
  const absolute = require('node:path').resolve(__dirname, relative)
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
    if (callback) callback()
  }
  return page
}

// 采用公开库玩法:与真实回填同形的一条 cms_template_library 行(validation_method=1)
const LIBRARY_TEMPLATE = {
  id: 16,
  title: 'CU样板·今晚的暗号',
  description: '到店对上暗号',
  players: '2人',
  duration: 5,
  validationMethod: 1,
  questionName: '今晚的暗号是什么',
  questionAnswer: '风铃',
  status: 1,
}

function adoptedReceipt(request,draftId){const body=JSON.parse(request.data);return {code:200,data:{requestId:body.requestId,originalTemplateId:body.originalTemplateId,sourceVersion:body.sourceVersion,status:'COMPLETED',draftId:String(draftId)}}}

function adoptLibraryTemplate(id, scope = '') {
  const page = loadPage(TEMP_PAGE)
  page.onLoad({ id: String(id || 16), scope })
  const request = requests.filter((item) => item.url === '/api/template/author-info')[0]
  assert.ok(request, '从货架进来必须走 /api/template/author-info 回填')
  request.success({ code: '200', data: JSON.parse(JSON.stringify(LIBRARY_TEMPLATE)) })
  const adoption = requests.filter(item => item.url === '/api/template/adopt-draft').at(-1)
  assert.equal(adoption.header['Content-Type'], 'application/json', '@RequestBody 请求必须明确使用 JSON')
  const intent=JSON.parse(adoption.data);assert.match(intent.requestId,/^adopt_/);
  assert.deepEqual(intent,{requestId:intent.requestId,originalTemplateId:String(id||16),sourceVersion:'a'.repeat(64),scope})
  assert.equal(page.data.editorLoading, true)
  request.complete()
  assert.equal(page.data.editorLoading, true, '公开详情 complete 不能提前放开编辑')
  adoption.success(adoptedReceipt(adoption,9101))
  const own = requests.filter(item => item.url === '/api/template/adoption-detail').at(-1)
  assert.equal(own.data.scope, scope, '草稿刷新必须和采用写入使用同一身份')
  own.success({ code: 200, data: Object.assign({}, LIBRARY_TEMPLATE, { id: 9101, canEdit: true, advancedConfigJson:'{}', originalTemplateId: id || 16 }) })
  page.validateForm = () => true
  return page
}

function draftBody(page) {
  page.saveDraft()
  const draft = requests.filter((item) => item.url === '/api/template/draft').at(-1)
  assert.ok(draft, '必须发起保存草稿请求')
  return JSON.parse(draft.data)
}

beforeEach(() => {
  requests = []
  installGlobals()
})

test('新版文字和选项问答发布后由服务端高级判题收尾，不再进入空旧题', async () => {
  global.Behavior = value => value
  for (const key of ['qaText', 'qaPick']) {
    const editor = loadPage(TEMP_PAGE)
    editor.data.moduleList = [{ key: 'finish' }, { key: 'reward' }]
    editor.pickGame({ currentTarget: { dataset: { key } } })
    editor.data.advanced.qa.title = '本地回归题'
    editor.data.advanced.qa.answerText = 'CY150'
    editor.data.advanced.qa.options = [
      { id: 'opt_1', label: '正确', correct: true },
      { id: 'opt_2', label: '错误', correct: false }
    ]
    editor.data.formData.couponId = 31
    const body = editor.prepareFormData()
    assert.equal(body.validationMethod, 0, key + '不能再要求空的旧文字题或选择题')
    assert.equal(body.couponId, 31)
    assert.equal(body.feedbackMethod, 1)
    assert.equal(JSON.parse(body.advancedConfigJson).qa.enabled, true)
    const player = loadPage('../../pages/play/index.js')
    player.data.advancedPlay = { show: true, node: { nodeId: 150, vm: body.validationMethod } }
    let completed = 0, legacy = 0
    player.onSheetGoTap = () => { completed++ }
    player.startGame = () => { legacy++ }
    await player.onAdvancedReady({ detail: { nodeId: 150 } })
    assert.equal(completed, 1)
    assert.equal(legacy, 0)
  }
})

test('重新保存此前只有高级问答配置的本人模板，修正空旧题完成方式', () => {
  for (const [mode, validationMethod] of [['TYPE', 1], ['PICK', 3]]) {
    const editor = loadPage(TEMP_PAGE)
    editor.data.isMyScope = true
    editor.fillFormWithTemplateData({ id: 6018, title: '本地问答回归', validationMethod,
      couponId: 31, advancedConfigJson: JSON.stringify({ schemaVersion: 1,
        qa: { enabled: true, mode, title: '本地题目', answerText: 'CY150',
          options: [{ id: 'opt_1', label: '正确', correct: true }, { id: 'opt_2', label: '错误', correct: false }] } }) })
    const body = editor.prepareFormData()
    assert.equal(body.validationMethod, 0)
    assert.equal(body.couponId, 31)
    assert.equal(JSON.parse(body.advancedConfigJson).qa.mode, mode)
  }
})

test('旧文字题、旧选择题及明确叠加旧题的模板保留原来的判题方式', () => {
  for (const advanced of [null, { schemaVersion: 1, qa: { enabled: true, mode: 'TYPE', title: '高级题', answerText: 'CY150' } }]) {
    for (const validationMethod of [1, 3]) {
      const editor = loadPage(TEMP_PAGE)
      editor.fillFormWithTemplateData({ title: '旧题回归', validationMethod,
        questionName: '旧题', questionAnswer: '旧答案', questionA: '甲', questionB: '乙', correctAnswer: 'B',
        advancedConfigJson: advanced ? JSON.stringify(advanced) : '' })
      const body = editor.prepareFormData()
      assert.equal(body.validationMethod, validationMethod)
      assert.equal(body.questionName, '旧题')
      if (validationMethod === 1) assert.equal(body.questionAnswer, '旧答案')
      else { assert.equal(body.questionB, '乙'); assert.equal(body.correctAnswer, 'B') }
    }
  }
})

test('拍照问答仍走照片验证，不能转成到达收尾', () => {
  const editor = loadPage(TEMP_PAGE)
  editor.data.moduleList = [{ key: 'finish' }]
  editor.pickGame({ currentTarget: { dataset: { key: 'qaShot' } } })
  editor.data.advanced.qa.title = '拍本地测试卡片'
  assert.equal(editor.prepareFormData().validationMethod, 2)
})

test('CU-M-68 采用完整源配置后只更新自己的草稿，不把库 id 当草稿 id', () => {
  const page = adoptLibraryTemplate(16)
  assert.equal(page.data.isMyScope, true)

  const body = draftBody(page)
  assert.equal(body.id, 9101, '采用成功后只更新服务端返回的本人草稿')
  assert.equal(body.originalTemplateId, 16, '来源 id 要留着，后端据此建新草稿并记来源')
  assert.equal(body.status, 0)
})

test('商家员工采用、回读和再次保存始终带同一商家作用域', () => {
  const page = adoptLibraryTemplate(16, 'MERCHANT')
  const body = draftBody(page)
  assert.equal(body.scope, 'MERCHANT')
  assert.equal(body.id, 9101)
})

test('采用后草稿读取被拒绝时阻止空配置保存，也不重新采用', () => {
  const page = loadPage(TEMP_PAGE)
  page.onLoad({ id: '16', scope: 'MERCHANT' })
  requests.find(item => item.url === '/api/template/author-info').success({ code: 200, data: LIBRARY_TEMPLATE })
  requests.find(item => item.url === '/api/template/adopt-draft').success(adoptedReceipt(requests.find(item=>item.url==='/api/template/adopt-draft'),9101))
  const readback = requests.find(item => item.url === '/api/template/adoption-detail')
  readback.success({ code: 403, msg: '商家身份无效或权限不足' })
  assert.ok(page.data.loadError)
  assert.equal(page.validateForm({}, true), false)
  page.saveDraft()
  assert.equal(requests.filter(item => item.url === '/api/template/draft').length, 0)
  page.getData()
  assert.equal(requests.filter(item => item.url === '/api/template/adopt-draft').length, 1)
  assert.equal(requests.filter(item => item.url === '/api/template/adoption-receipt').at(-1).data.scope, 'MERCHANT')
})

test('公开预览只读：不能创建草稿或读取非本人答案', () => {
  const page = loadPage(TEMP_PAGE)
  page.onLoad({ id: '16', preview: '1' })
  requests.find(item => item.url === '/api/template/author-info').success({ code: 200, data: LIBRARY_TEMPLATE })
  assert.equal(requests.filter(item => item.url === '/api/template/draft').length, 0)
  assert.equal(requests.filter(item => item.url === '/api/template/adopt-draft').length, 0)
  assert.equal(page.data.isMyScope, false)
})

test('采用请求未确认时不能盲重试；加载中不能保存默认空配置', () => {
  const page = loadPage(TEMP_PAGE)
  page.onLoad({ id: '16' })
  assert.equal(page.validateForm({}, true), false)
  requests.find(item => item.url === '/api/template/author-info').success({ code: 200, data: LIBRARY_TEMPLATE })
  page.getData()
  assert.equal(requests.filter(item => item.url === '/api/template/adopt-draft').length, 1)
  requests.find(item => item.url === '/api/template/adopt-draft').fail({})
  page.getData()
  assert.equal(requests.filter(item => item.url === '/api/template/adopt-draft').length, 1)
  assert.match(page.data.loadError, /未确认/)
  assert.equal(page.data.loadErrorIsOperation, true, '未知写结果必须可见，不按详情读取失败留白')
  assert.equal(page.validateForm({}, true), false)
})

test('旧后端不支持采用接口时明确阻断，不回退普通保存或创建空草稿', () => {
  for (const status of [404, 405]) {
    requests = []; storage = {}
    const page = loadPage(TEMP_PAGE)
    page.onLoad({ id: '16' })
    requests.find(item => item.url === '/api/template/author-info').success({ code: 200, data: LIBRARY_TEMPLATE })
    requests.find(item => item.url === '/api/template/adopt-draft').fail({}, status)
    assert.match(page.data.loadError, /尚未支持/)
    assert.equal(page.data.editorLoading, false)
    assert.equal(page.validateForm({}, true), false)
    assert.equal(page._templateId, 16)
    assert.equal(page.data.isMyScope, false)
    assert.equal(requests.filter(item => item.url === '/api/template/draft').length, 0)
    assert.equal(requests.filter(item => item.url === '/api/template/author-myinfo').length, 0)
  }
})

test('采用请求经过真实请求封装仍为 JSON，HTTP 404 正确回到页面阻断态', () => {
  const page = loadPage(TEMP_PAGE)
  page.onLoad({ id: '16' })
  requests.find(item => item.url === '/api/template/author-info').success({ code: 200, data: LIBRARY_TEMPLATE })
  const adoption = requests.find(item => item.url === '/api/template/adopt-draft')
  let sent
  const client = require('../../utils/transport/request-client.js').createRequestClient({
    getBaseUrl: () => '', wxRequest: options => { sent = options; return { abort() {} } }
  })
  client.send(adoption)
  assert.equal(sent.url, '/api/template/adopt-draft')
  assert.equal(sent.header['Content-Type'], 'application/json')
  assert.equal(JSON.parse(sent.data).originalTemplateId, '16')
  sent.success({ statusCode: 404, data: { msg: '未提供接口' } })
  assert.match(page.data.loadError, /尚未支持/)
  assert.equal(page.validateForm({}, true), false)
  assert.equal(requests.filter(item => item.url === '/api/template/draft').length, 0)
})

// 集成复审:采用后第一次存草稿建了新草稿,留在页里再存一次必须更新这份,不能再建一份重复草稿
test('CU-M-68 采用后首存成功即切到本人草稿:再存带新草稿 id', () => {
  const page = adoptLibraryTemplate(16)
  page.data.from = 'fabu'
  page.getOpenerEventChannel = () => null
  draftBody(page)
  const first = requests.filter((item) => item.url === '/api/template/draft').at(-1)
  first.success({ code: '200', data: 9101 })
  assert.equal(page._templateId, 9101)
  assert.equal(Object.hasOwn(page.data, 'id'), false)
  assert.equal(page.data.isMyScope, true)

  const second = draftBody(page)
  assert.equal(second.id, 9101, '第二次保存要更新刚建的草稿，不是再建一份')
})

test('CU-M-68 本人草稿续编仍带原 id(不能把 R9-04 的续编改回新建)', () => {
  const page = loadPage(TEMP_PAGE)
  page.onLoad({ id: '6029', scope: 'my' })
  const request = requests.filter((item) => item.url === '/api/template/author-myinfo')[0]
  assert.ok(request, 'scope=my 必须查个人草稿接口')
  request.success({ code: '200', data: Object.assign({}, LIBRARY_TEMPLATE, { id: 6029, canEdit: true }) })
  page.validateForm = () => true

  const body = draftBody(page)
  assert.equal(body.id, 6029, '续编必须更新原草稿而不是另建一份')
})

test('新建首存后先回读本人权限，再次保存更新同一份未完整草稿', () => {
  const page = loadPage(TEMP_PAGE)
  page.onLoad({})
  page.data.formData.title = '新建未完成草稿'
  page.saveDraft()
  const first = requests.filter(r => r.url === '/api/template/draft').at(-1)
  assert.equal(JSON.parse(first.data).id, undefined)
  page.data.formData.title = '继续补充草稿'
  first.success({ code: 200, data: 9202 })
  const readback = requests.filter(r => r.url === '/api/template/author-myinfo').at(-1)
  assert.ok(readback, '首存后必须从服务端确认本人编辑权限')
  assert.equal(readback.data.id, 9202)
  page.saveDraft()
  assert.equal(requests.filter(r => r.url === '/api/template/draft').length, 1)
  readback.success({ code: 200, data: { id: 9202, title: '新建未完成草稿', canEdit: true } })
  assert.equal(page.data.formData.title, '继续补充草稿', '权限刷新不能覆盖首存期间继续输入的内容')
  page.saveDraft()
  const second = requests.filter(r => r.url === '/api/template/draft').at(-1)
  assert.equal(JSON.parse(second.data).id, 9202)
  assert.equal(JSON.parse(second.data).title, '继续补充草稿')
  assert.equal(requests.filter(r => r.url === '/api/template/draft').length, 2)
})

test('主题内新建玩法首存回读新绑定 ID 后可继续编辑并原位二次保存', () => {
  const page = loadPage(TEMP_PAGE)
  page.onLoad({ from: 'fabu' })
  page.data.formData.title = '主题内新玩法'
  page._collectValidationErrors = () => ({})
  page.publishGame()
  const first = requests.filter(r => r.url === '/api/template/draft').at(-1)
  assert.ok(first)
  assert.equal(JSON.parse(first.data).id, undefined)
  page.data.formData.title = '等待期间继续编辑'
  first.success({ code: 200, data: 9203 })
  assert.equal(page._boundDraftId, 9203)
  const confirmation = requests.filter(r => r.url === '/api/template/author-myinfo').at(-1)
  assert.ok(confirmation, '新绑定草稿必须读回本人新 ID 和权限')
  assert.equal(confirmation.data.id, 9203)
  page.onCreationSuccessAction({ detail: { key: 'secondary' } })
  assert.equal(page.data.creationSuccess.show, false)
  page.publishGame()
  assert.equal(requests.filter(r => r.url === '/api/template/draft').length, 1)
  confirmation.success({ code: 200, data: { id: 9203, title: '主题内新玩法', canEdit: true } })
  assert.equal(page.data.formData.title, '等待期间继续编辑')
  page.publishGame()
  const second = requests.filter(r => r.url === '/api/template/draft').at(-1)
  assert.equal(requests.filter(r => r.url === '/api/template/draft').length, 2)
  assert.equal(JSON.parse(second.data).id, 9203)
  assert.equal(JSON.parse(second.data).title, '等待期间继续编辑')
  assert.equal(JSON.parse(second.data).isSync, 0)
})

test('新建草稿首存回读没有明确权限时仍拒绝二次写入', () => {
  const page = loadPage(TEMP_PAGE)
  page.onLoad({})
  page.data.formData.title = '新建草稿'
  page.saveDraft()
  requests.filter(r => r.url === '/api/template/draft').at(-1).success({ code: 200, data: 9202 })
  requests.filter(r => r.url === '/api/template/author-myinfo').at(-1)
    .success({ code: 200, data: { id: 9202, title: '缺少权限字段' } })
  page.saveDraft()
  assert.equal(requests.filter(r => r.url === '/api/template/draft').length, 1)
  assert.equal(page._templateCanEdit, false)
})

test('新建草稿首存回读缺失或错 ID 时保持锁定，重试不覆盖未保存输入', () => {
  const page = loadPage(TEMP_PAGE)
  page.onLoad({})
  page.data.formData.title = '新建草稿'
  page.saveDraft()
  requests.filter(r => r.url === '/api/template/draft').at(-1).success({ code: 200, data: 9202 })
  let readback = requests.filter(r => r.url === '/api/template/author-myinfo').at(-1)
  page.data.formData.title = '等待期间新增的内容'
  page.saveDraft()
  assert.equal(page.data.editorLoading, true, '响应丢失期间不得先解锁')
  assert.equal(requests.filter(r => r.url === '/api/template/draft').length, 1)
  readback.fail({ msg: '超时' }); readback.complete()
  page.retryGetData()
  readback = requests.filter(r => r.url === '/api/template/author-myinfo').at(-1)
  readback.success({ code: 200, data: { id: 9999, title: '其他草稿', canEdit: true } })
  assert.equal(page._templateCanEdit, false)
  assert.equal(page.data.formData.title, '等待期间新增的内容')
  page.saveDraft()
  assert.equal(requests.filter(r => r.url === '/api/template/draft').length, 1)
  page.retryGetData()
  readback = requests.filter(r => r.url === '/api/template/author-myinfo').at(-1)
  readback.success({ code: 200, data: { id: 9202, title: '新建草稿', canEdit: true } })
  assert.equal(page._templateCanEdit, true)
  assert.equal(page.data.formData.title, '等待期间新增的内容')
})

test('新建草稿旧代际回读即使 ID 与权限正确也不能解锁', () => {
  const page = loadPage(TEMP_PAGE)
  page.onLoad({})
  page.data.formData.title = '新建草稿'
  page.saveDraft()
  requests.filter(r => r.url === '/api/template/draft').at(-1).success({ code: 200, data: 9202 })
  const oldReadback = requests.filter(r => r.url === '/api/template/author-myinfo').at(-1)
  page.getData(true)
  oldReadback.success({ code: 200, data: { id: 9202, title: '旧响应', canEdit: true } })
  oldReadback.complete()
  assert.equal(page._templateCanEdit, false)
  assert.equal(page.data.editorLoading, true)
})

test('新建草稿首存后的回读在换号或卸载后不能恢复编辑权限', () => {
  for (const endSession of [
    page => page.onUnload(),
    () => { appStub.getUserID = () => 9005 },
  ]) {
    installGlobals(); requests = []
    const page = loadPage(TEMP_PAGE)
    page.onLoad({})
    page.data.formData.title = '新建草稿'
    page.saveDraft()
    requests.filter(r => r.url === '/api/template/draft').at(-1).success({ code: 200, data: 9202 })
    const readback = requests.filter(r => r.url === '/api/template/author-myinfo').at(-1)
    endSession(page)
    const before = JSON.stringify(page.data)
    readback.success({ code: 200, data: { id: 9202, title: '迟到刷新', canEdit: true } })
    readback.complete()
    assert.equal(JSON.stringify(page.data), before)
    assert.equal(page._templateCanEdit, false)
  }
})

// 2026-09-23 用户裁决(#1137):编辑页试玩底部的「保存前摘要」整块删掉,只在保存失败时出失败说明。
// CU-M-69 修的是摘要里的两句读数;摘要删了,那两句与它们的计算一并撤掉,不留没人渲染的字段。
test('保存前摘要已删:只在保存失败时出失败说明,也不再算摘要读数', () => {
  const fs = require('node:fs')
  const path = require('node:path')
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8')
  const js = fs.readFileSync(path.resolve(__dirname, '../../pages/publish/temp/index.js'), 'utf8')
  assert.doesNotMatch(wxml, /cg-publish-summary/, '保存前摘要回潮了')
  assert.match(wxml, /<cy-inline-error wx:if="\{\{submitError\}\}"/, '失败说明只在保存失败时出')
  assert.doesNotMatch(js, /summaryMethodLabel|summaryDurationText/, '摘要删了，读数也别再算')
})


test('未完整草稿可从编辑态直接保存，不依赖发布预览校验', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8');
  const footer = wxml.slice(wxml.indexOf('<!-- 编辑态底部栏 -->'), wxml.indexOf('<!-- ============ 预览态'));
  assert.match(footer, /<cy-btn[^>]*bindtap="saveDraft"[^>]*>保存草稿<\/cy-btn>/);
  const draftButton = footer.match(/<cy-btn[^>]*bindtap="saveDraft"[^>]*>/)[0];
  assert.match(draftButton, /!previewMode/);
  assert.match(draftButton, /from !== 'fabu'/);
  assert.match(draftButton, /from !== 'citynode'/);
  assert.doesNotMatch(draftButton, /canOpenPreview/);
  const page = loadPage(TEMP_PAGE);
  page.onLoad({ id: '9101', scope: 'my' });
  requests.find(item => item.url === '/api/template/author-myinfo').success({ code: 200, data: { id: 9101, title: '未完成草稿', canEdit: true } });
  page.refreshPreviewState();
  assert.equal(page.data.canOpenPreview, false);
  page.saveDraft();
  const request = requests.find(item => item.url === '/api/template/draft');
  assert.ok(request);
  const body = JSON.parse(request.data);
  assert.equal(body.id, 9101);
  assert.equal(body.status, 0);
  assert.equal(body.title, '未完成草稿');
});

test('采用成功只更新内部scope且静默调用精确登记，额外调用仍拒绝', () => {
  const page = loadPage(TEMP_PAGE)
  const patches = []
  const original = page.setData
  page.setData = function(patch, cb) { patches.push(patch); return original.call(this, patch, cb) }
  page.onLoad({ id: '16' })
  patches.length = 0 // 只检查本合同拥有的采用回执，初载保留原初始化
  requests.find(r => r.url === '/api/template/author-info').success({code:200,data:LIBRARY_TEMPLATE})
  requests.find(r => r.url === '/api/template/adopt-draft').success(adoptedReceipt(requests.find(r=>r.url==='/api/template/adopt-draft'),9101))
  requests.find(r=>r.url==='/api/template/adoption-detail').success({code:200,data:{...LIBRARY_TEMPLATE,id:9101,canEdit:true,advancedConfigJson:'{}'}})
  assert.equal(page.data.isMyScope, true)
  assert.equal(patches.some(p => Object.hasOwn(p, 'isMyScope')), false)
  assert.equal(requests.filter(r => r.url === '/api/template/adoption-detail').length, 1)
  const fs = require('node:fs'), path = require('node:path')
  const file = 'pages/publish/temp/index.js'
  const source = fs.readFileSync(path.resolve(__dirname, '../../', file), 'utf8')
  const entry = require('../../scripts/ui-silent-error-allowlist').find(e => e.file === file)
  const {findU5Issues} = require('../../scripts/ui-validity-gates')
  assert.deepEqual(findU5Issues([{file,source}], [entry]).issues, [])
  assert.ok(findU5Issues([{file,source:source+'\nconst extra={silentError:true};'}], [entry]).issues.length)
  assert.ok(findU5Issues([{file,source}], [{...entry,count:5}]).issues.length)
})

test('draft late success after unloading cannot show result sheet or change draft ID', () => {
  const p=loadPage(TEMP_PAGE);
  p.sendData({status:0,title:'callback test'},'/api/template/draft','draft');
  const req=requests.at(-1);
  p.onUnload();
  const before=JSON.parse(JSON.stringify(p.data));
  req.success({code:200,data:9201});
  assert.deepEqual(p.data,before);
  assert.notEqual(p._templateId,9201);
});
test('draft late failure after unloading cannot change page state', () => {
  const p=loadPage(TEMP_PAGE);
  p.sendData({status:0,title:'callback test'},'/api/template/draft','draft');
  const req=requests.at(-1);
  p.onUnload();
  const before=JSON.parse(JSON.stringify(p.data));
  req.fail({msg:'late offline'});
  assert.deepEqual(p.data,before);
});
test('draft late success after account changes cannot replace current editor identity', () => {
  const p=loadPage(TEMP_PAGE);
  p.sendData({status:0,title:'callback test'},'/api/template/draft','draft');
  const req=requests.at(-1);
  appStub.getUserID=()=>9006;
  const before=JSON.parse(JSON.stringify(p.data));
  req.success({code:200,data:9202});
  assert.deepEqual(p.data,before);
});
test('adoption late success after unloading cannot start new draft read', () => {
  const p=loadPage(TEMP_PAGE);
  p.onLoad({id:'16'});requests.find(r=>r.url==='/api/template/author-info').success({code:200,data:LIBRARY_TEMPLATE});
  const req=requests.find(r=>r.url==='/api/template/adopt-draft');
  p.onUnload();
  const count=requests.length;
  req.success({code:200,data:9203});
  assert.equal(requests.length,count);
});
test('library late response after unloading cannot start adoption write', () => {
  const p=loadPage(TEMP_PAGE);
  p.onLoad({id:'16'});
  const req=requests.find(r=>r.url==='/api/template/author-info');
  p.onUnload();
  const count=requests.length;
  req.success({code:200,data:LIBRARY_TEMPLATE});
  assert.equal(requests.length,count);
});

test('city-node late save after unloading cannot emit created event', () => {
  const p=loadPage(TEMP_PAGE);
  p.data.from='citynode';
  let events=0;
  p.getOpenerEventChannel=()=>({emit:()=>events++});
  p.sendData({status:0,title:'callback test'},'/api/template/draft','draft');
  const req=requests.at(-1);
  p.onUnload();
  req.success({code:200,data:9204});
  assert.equal(events,0);
});
test('city-node delayed exit cannot navigate after unloading', () => {
  const p=loadPage(TEMP_PAGE);
  p.data.from='citynode';
  let exits=0;
  p.exitPage=()=>exits++;
  const original=global.setTimeout;
  const timers=[];try{global.setTimeout=fn=>{timers.push(fn);return timers.length};
  p.sendData({status:0,title:'callback test'},'/api/template/draft','draft');requests.at(-1).success({code:200,data:9205});
  p.onUnload();timers.forEach(fn=>fn());
  assert.equal(exits,0);}finally{global.setTimeout=original}});
test('city-node delayed exit cannot navigate after account changes', () => {
  const p=loadPage(TEMP_PAGE);
  p.data.from='citynode';
  let exits=0;
  p.exitPage=()=>exits++;
  const original=global.setTimeout;
  const timers=[];try{global.setTimeout=fn=>{timers.push(fn);return timers.length};
  p.sendData({status:0,title:'callback test'},'/api/template/draft','draft');requests.at(-1).success({code:200,data:9206});
  appStub.getUserID=()=>9006;timers.forEach(fn=>fn());
  assert.equal(exits,0);}finally{global.setTimeout=original}});
test('unloaded editor ignores old dictionary callbacks', () => {
  const p=loadPage(TEMP_PAGE);
  p.getDictTypeList('app_template_duration','durationOptions','duration');
  const req=requests.at(-1);
  p.onUnload();
  const before=JSON.parse(JSON.stringify(p.data));
  req.success({code:200,data:[{dictLabel:'5',dictValue:'5'}]});
  req.complete();
  assert.deepEqual(p.data,before);
});
for (const [stateKey, listName, retryMethod, dictLabel, dictValue] of [
  ['difficulty', 'difficultyOptions', 'retryDifficultyOptions', '简单', '1'],
]) {
  test(`empty ${stateKey} options preserve draft and internal retry state while ignoring superseded replies`, () => {
    const page = loadPage(TEMP_PAGE);
    page.data.formData.title = '已填写的玩法';
    page.data.formData.questionAnswer = 'CY150';
    page.data.formData.couponId = 31;
    const formBefore = JSON.parse(JSON.stringify(page.data.formData));
    let callbackCount = 0;
    page.getDictTypeList('app_template_' + stateKey, listName, stateKey, () => callbackCount++);
    const empty = requests.at(-1);
    empty.success({ code: 200, data: [] });
    empty.complete();
    assert.equal(page._dictErrors[stateKey], '暂无可选项，请稍后重试');
    assert.equal(page._dictLoading[stateKey], false);
    assert.deepEqual(page.data[listName + 'Data'], []);
    assert.equal(callbackCount, 0, '空选项不能进入成功回填');
    assert.deepEqual(page.data.formData, formBefore, '不能清除已填写内容');

    page[retryMethod]();
    const retry = requests.at(-1);
    assert.notEqual(retry, empty);
    assert.equal(page._dictLoading[stateKey], true);
    empty.success({ code: 200, data: [] });
    empty.fail();
    empty.complete();
    assert.equal(page._dictErrors[stateKey], '');
    assert.equal(page._dictLoading[stateKey], true, '旧请求不能结束新的加载');
    retry.success({ code: 200, data: [{ dictLabel, dictValue }] });
    retry.complete();
    assert.equal(page._dictErrors[stateKey], '');
    assert.equal(page._dictLoading[stateKey], false);
    assert.deepEqual(page.data[listName + 'Data'], [{ dictLabel, dictValue }]);
    assert.equal(page.data.formData.title, formBefore.title);
    assert.equal(page.data.formData.questionAnswer, formBefore.questionAnswer);
    assert.equal(page.data.formData.couponId, formBefore.couponId);
  });

  test(`recovered ${stateKey} options refill the current editor with its own context`, () => {
    const page = loadPage(TEMP_PAGE);
    page.data.formData[stateKey] = dictValue;
    page[retryMethod]();
    const request = requests.at(-1);
    assert.doesNotThrow(() => request.success({ code: 200, data: [{ dictLabel, dictValue }] }));
    assert.equal(page.data[stateKey + 'Index'], 0);
    assert.equal(page.data[stateKey === 'difficulty' ? 'difficultyLabel' : stateKey + 'Text'], dictLabel);
  });
}
test('old account user profile reply cannot fill current editor', () => {
  const p=loadPage(TEMP_PAGE);
  p.getUserData();
  const req=requests.at(-1);
  appStub.getUserID=()=>9006;
  const before=JSON.parse(JSON.stringify(p.data));
  req.success({code:200,data:{id:9004}});
  assert.deepEqual(p.data,before);
});
test('unloaded city-node editor ignores permission reply', () => {
  const p=loadPage(TEMP_PAGE);
  p.loadCityNodeAccess();
  const req=requests.at(-1);
  p.onUnload();
  const before=JSON.parse(JSON.stringify(p.data));
  req.success({code:200,data:{active:true,merchant:{id:5},permissions:['merchant:project:manage']}});
  assert.deepEqual(p.data,before);
});

test('返回编辑器时账号已变化，关闭旧编辑页并回正常模板入口', () => {
  const page = loadPage(TEMP_PAGE);
  page.onLoad({ id: '6016', scope: 'my' });
  appStub.getUserID = () => 9006;
  const navigations = [];
  wx.switchTab = options => navigations.push(options.url);
  page.onShow();
  assert.deepEqual(navigations, ['/pages/template/index']);
});

test('本人草稿发布保留会员模板 ID，不能被原草稿标题挡成重名新建', () => {
  const page = loadPage(TEMP_PAGE)
  page.onLoad({ id: '6029', scope: 'my' })
  requests.find(item => item.url === '/api/template/author-myinfo').success({
    code: 200, data: Object.assign({}, LIBRARY_TEMPLATE, { id: 6029, canEdit: true, originalTemplateId: 16, draftStatus: 0 })
  })
  page.validateForm = () => true
  page.publishGame()
  const request = requests.find(item => item.url === '/api/template/publish')
  assert.ok(request)
  const body = JSON.parse(request.data)
  assert.equal(body.id, 6029, '后端按本人 ID 排除重名并原位更新，不新增模板')
  assert.equal(body.originalTemplateId, 16)
  assert.equal(body.scope, '')
})

test('采用公开模板后发布本人草稿 ID，保留商家作用域和库来源', () => {
  const page = adoptLibraryTemplate(16, 'MERCHANT')
  page.publishGame()
  const body = JSON.parse(requests.find(item => item.url === '/api/template/publish').data)
  assert.equal(body.id, 9101, '采用已创建本人草稿，发布继续使用该草稿而非库 ID')
  assert.equal(body.originalTemplateId, 16)
  assert.equal(body.scope, 'MERCHANT')
})

test('已发布本人模板编辑仍更新原 ID，新建不接受表单残留 ID', () => {
  for (const editing of [true, false]) {
    requests = []; storage = {}
    const page = loadPage(TEMP_PAGE)
    if (editing) {
      page.onLoad({ id: '6030', scope: 'my' })
      requests.find(item => item.url === '/api/template/author-myinfo').success({
        code: 200, data: Object.assign({}, LIBRARY_TEMPLATE, { id: 6030, canEdit: true, originalTemplateId: 16, draftStatus: 1 })
      })
    } else {
      page.onLoad({})
    }
    page.prepareFormData = () => ({ title: '保留服务端归属', id: 999999 })
    page.validateForm = () => true
    page.publishGame()
    const body = JSON.parse(requests.find(item => item.url === '/api/template/publish').data)
    assert.equal(body.id, editing ? 6030 : undefined)
  }
})

test('本人模板用于主题仍另存私有绑定草稿；已有绑定草稿才更新绑定 ID', () => {
  for (const boundId of [0, 9201]) {
    requests = []; storage = {}
    const page = loadPage(TEMP_PAGE)
    page.onLoad({ id: '6030', scope: 'my', from: 'fabu' })
    requests.find(item => item.url === '/api/template/author-myinfo').success({ code: 200, data: { ...LIBRARY_TEMPLATE, id: 6030, canEdit: true } })
    page._boundDraftId = boundId
    page.validateForm = () => true
    page.publishGame()
    assert.equal(requests.filter(item => item.url === '/api/template/publish').length, 0)
    const body = JSON.parse(requests.find(item => item.url === '/api/template/draft').data)
    assert.equal(body.id, boundId || undefined)
    assert.equal(body.status, 0)
    assert.equal(body.isSync, 0)
  }
})

test('据点玩法继续使用专属提交路由，不把本人库 ID 带入节点模板', () => {
  const page = loadPage(TEMP_PAGE)
  page.onLoad({ id: '6030', scope: 'my', from: 'citynode' })
  requests.find(item => item.url === '/api/template/author-myinfo').success({ code: 200, data: { ...LIBRARY_TEMPLATE, id: 6030, canEdit: true } })
  page.data.merchantAccess = { canManageProjects: true }
  page.validateForm = () => true
  page.publishGame()
  const request = requests.find(item => item.url === '/api/merchant/city-node/template/submit')
  assert.ok(request)
  const body = JSON.parse(request.data)
  assert.equal(body.id, undefined)
  assert.equal(requests.filter(item => item.url === '/api/template/publish').length, 0)
})

test('采用丢ACK退出重入先查原回执，来源已下架也不重新读取或采用', () => {
  const first=loadPage(TEMP_PAGE);first.onLoad({id:'16',scope:'MERCHANT'});
  requests.find(r=>r.url==='/api/template/author-info').success({code:200,data:LIBRARY_TEMPLATE});
  const write=requests.find(r=>r.url==='/api/template/adopt-draft');const intent=JSON.parse(write.data);write.fail({});first.onUnload();
  requests=[];const second=loadPage(TEMP_PAGE);second.onLoad({id:'16',scope:'MERCHANT'});
  assert.equal(requests.filter(r=>r.url==='/api/template/author-info').length,0);
  const recovery=requests.find(r=>r.url==='/api/template/adoption-receipt');assert.equal(recovery.data.requestId,intent.requestId);
  recovery.success({code:200,data:{...intent,status:'COMPLETED',draftId:'9007199254740993'}});
  requests.find(r=>r.url==='/api/template/adoption-detail').success({code:200,data:{...LIBRARY_TEMPLATE,id:'9007199254740993',canEdit:true,advancedConfigJson:'{}',originalTemplateId:'9007199254740995'}});
  assert.equal(second._templateId,'9007199254740993');assert.equal(second.data.formData.originalTemplateId,'9007199254740995');
  second.validateForm=()=>true;const body=draftBody(second);assert.equal(body.id,'9007199254740993');assert.equal(body.originalTemplateId,'9007199254740995');
  assert.equal(requests.find(r=>r.url==='/api/template/draft').header['X-Author-Exact-Ids'],'1');
  assert.equal(requests.filter(r=>r.url==='/api/template/adopt-draft').length,0);
})

test('采用重进负查询仅恢复冻结原号原来源版本，不换新号新内容',()=>{
  const first=loadPage(TEMP_PAGE);first.onLoad({id:'16'});requests.find(r=>r.url==='/api/template/author-info').success({code:200,data:LIBRARY_TEMPLATE});
  const write=requests.find(r=>r.url==='/api/template/adopt-draft');write.fail({});
  const payload=write.data;requests=[];const next=loadPage(TEMP_PAGE);next.onLoad({id:'16'});
  requests.find(r=>r.url==='/api/template/adoption-receipt').success({code:200,data:{requestId:JSON.parse(payload).requestId,status:'UNKNOWN'}});
  assert.equal(requests.find(r=>r.url==='/api/template/adopt-draft').data,payload);
  assert.equal(requests.filter(r=>r.url==='/api/template/adoption-source').length,0);
})

test('采用迟到回执不能跨商家scope、授权或继续编辑的输入回填',()=>{
  for(const change of ['scope','auth','input','advanced','ruleSteps','album','hidden']){
    requests=[];storage={};appStub.getAuthorization=()=> 'original';
    const p=loadPage(TEMP_PAGE);p.onLoad({id:'16'});requests.find(r=>r.url==='/api/template/author-info').success({code:200,data:LIBRARY_TEMPLATE});
    const write=requests.find(r=>r.url==='/api/template/adopt-draft');
    if(change==='scope')p.data.operationScope='MERCHANT';if(change==='auth')appStub.getAuthorization=()=> 'changed';if(change==='input')p.data.formData.title='继续写';if(change==='advanced')p.data.advanced={changed:true};if(change==='ruleSteps')p.data.ruleSteps=['继续编辑'];if(change==='album')p._albumEditRevision=99;if(change==='hidden')p.onHide();
    const before=JSON.stringify(p.data.formData);write.success(adoptedReceipt(write,9101));
    assert.equal(JSON.stringify(p.data.formData),before);assert.equal(requests.filter(r=>r.url==='/api/template/adoption-detail').length,0);
  }
})
