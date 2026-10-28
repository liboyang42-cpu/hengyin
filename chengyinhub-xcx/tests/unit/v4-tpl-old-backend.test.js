'use strict'

// SC-TPL-OLD-BACKEND(P1,入口 pages/templatedetail/templatedetail):
// 「开始应用」→ 编辑页 pages/publish/temp/index 先读来源版本(/api/template/adoption-source),
// 再带原请求号调 /api/template/adopt-draft 把公共模板采用成本人草稿。
// 验收:
//   · 旧服务端(adopt-draft 404/405)明确拒绝,绝不回退成普通保存去建一份空/普通草稿;
//   · 回包丢失 / 结果未知时不盲重试:「重试」只按原请求号查回执(/api/template/adoption-receipt),
//     「查看我的玩法」带作者去草稿列表核对,不产生半截或重复草稿。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

let appStub
let requests
let navigations
let storage

function installGlobals() {
  storage = {}
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
    getStorageSync: (key) => storage[key],
    setStorageSync: (key, value) => { storage[key] = value },
    removeStorageSync: (key) => { delete storage[key] },
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    showToast: () => {},
    hideLoading: () => {},
    showLoading: () => {},
    showModal: () => {},
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
  const absolute = path.resolve(__dirname, '../../', relative)
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

const LIBRARY_TEMPLATE = {
  id: 16, title: '今晚的暗号', description: '到店对上暗号', players: '2人', duration: 5,
  validationMethod: 1, questionName: '今晚的暗号是什么', questionAnswer: '风铃', status: 1,
}

// 任何会在服务端落一份模板/草稿的写请求
const WRITE_URLS = ['/api/template/draft', '/api/template/publish', '/api/template/add', '/api/template/save']
const writes = () => requests.filter(r => WRITE_URLS.includes(r.url) || /\/api\/template\/(add|create|insert)/.test(r.url))
const adopts = () => requests.filter(r => r.url === '/api/template/adopt-draft')

/** 从模板详情点「开始应用」进编辑页,公共详情回填成功,返回编辑页与那次采用请求 */
function openFromTemplateDetail() {
  const detail = loadPage('pages/templatedetail/templatedetail.js')
  detail.data.id = 16
  detail.data.scope = 'library'
  detail.goPrimary()
  const nav = navigations.at(-1)
  assert.equal(nav.url, '/pages/publish/temp/index?id=16')
  const editor = loadPage('pages/publish/temp/index.js')
  editor.onLoad(Object.fromEntries(new URL(nav.url, 'https://local.invalid').searchParams))
  requests.find(r => r.url === '/api/template/author-info').success({ code: 200, data: Object.assign({}, LIBRARY_TEMPLATE) })
  requests.find(r => r.url === '/api/template/adoption-source')
    .success({ code: 200, data: { originalTemplateId: '16', sourceVersion: 'a'.repeat(64) } })
  assert.equal(adopts().length, 1)
  return { editor, adoption: adopts()[0] }
}

/** 作者在阻断态下能点的所有「保存」与「重试」入口 */
function pokeEveryWriteEntry(editor) {
  editor.data.formData.title = '作者随手改的标题'
  editor.saveDraft()
  editor.publishGame()
}

beforeEach(() => {
  requests = []
  navigations = []
  installGlobals()
})

for (const status of [404, 405]) {
  test(`SC-TPL-OLD-BACKEND 旧服务端 adopt-draft ${status}:明确提示需更新服务,不回退建普通草稿`, () => {
    const { editor, adoption } = openFromTemplateDetail()
    adoption.fail({ statusCode: status }, status)
    assert.match(editor.data.loadError, /当前服务尚未支持采用请求，请更新服务后重查原请求/)
    assert.equal(editor.data.loadErrorIsOperation, true)
    assert.equal(editor.data.editorLoading, false)
    assert.equal(editor.data.isMyScope, false, '不能把库模板当成本人草稿')
    assert.equal(editor._templateId, 16)

    pokeEveryWriteEntry(editor)
    assert.deepEqual(writes().map(r => r.url), [], '旧服务端下不得回退到普通保存/新建草稿')
    assert.equal(requests.filter(r => r.url === '/api/template/author-myinfo').length, 0)
  })
}

const UNKNOWN_RESULTS = [
  ['网络断开/超时(无状态码)', (a) => a.fail({ errMsg: 'request:fail timeout' })],
  ['服务端 502 回包丢失', (a) => a.fail({ statusCode: 502 }, 502)],
  ['200 但回包缺草稿 id', (a) => a.success({ code: 200, data: null })],
  ['200 回执已完成但缺草稿 id', (a) => { const body = JSON.parse(a.data); a.success({ code: 200, data: { requestId: body.requestId,
    originalTemplateId: body.originalTemplateId, sourceVersion: body.sourceVersion, status: 'COMPLETED', draftId: null } }) }],
]

for (const [label, settle] of UNKNOWN_RESULTS) {
  test(`SC-TPL-OLD-BACKEND 采用结果未知(${label}):先回草稿列表核对,不盲重试、不另建草稿`, () => {
    const { editor, adoption } = openFromTemplateDetail()
    settle(adoption)
    assert.match(editor.data.loadError, /未确认/)
    assert.equal(editor.data.loadErrorIsOperation, true)

    // 作者在页面上点保存/发布:不能补建一份
    pokeEveryWriteEntry(editor)
    // 作者点「重试」:只按原请求号查回执,而不是再发一次采用
    editor.retryGetData()
    const receipt = requests.filter(r => r.url === '/api/template/adoption-receipt').at(-1)
    assert.ok(receipt, '重试必须先查原采用请求回执')
    assert.equal(receipt.data.requestId, JSON.parse(adoption.data).requestId)
    // 作者点「查看我的玩法」:去草稿列表核对
    editor.inspectAdoptionTemplates()
    assert.deepEqual(navigations.at(-1), { method: 'reLaunch', url: '/subpackageMember/mytemplate/mytemplate' })
    // 生命周期再拉一次详情也不能偷偷重采用
    editor.getData()

    assert.equal(adopts().length, 1, '结果未知时不得自动重发 adopt-draft')
    assert.deepEqual(writes().map(r => r.url), [], '结果未知时不得落任何草稿')
  })
}

test('SC-TPL-OLD-BACKEND 对照:采用成功拿到草稿 id 后,保存只更新该本人草稿', () => {
  const { editor, adoption } = openFromTemplateDetail()
  const intent = JSON.parse(adoption.data)
  adoption.success({ code: 200, data: { requestId: intent.requestId, originalTemplateId: '16',
    sourceVersion: intent.sourceVersion, status: 'COMPLETED', draftId: '9101' } })
  requests.filter(r => r.url === '/api/template/adoption-detail').at(-1)
    .success({ code: 200, data: Object.assign({}, LIBRARY_TEMPLATE, { id: '9101', canEdit: true, advancedConfigJson: '{}', originalTemplateId: '16' }) })
  editor.validateForm = () => true
  editor.saveDraft()
  const drafts = writes().filter(r => r.url === '/api/template/draft')
  assert.equal(drafts.length, 1)
  assert.equal(JSON.parse(drafts[0].data).id, 9101)
  assert.equal(adopts().length, 1)
})
