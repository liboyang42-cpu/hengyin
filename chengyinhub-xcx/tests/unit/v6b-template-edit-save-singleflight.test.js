// V6b 验收（CX-11 模板编辑）：我的模板 → 详情 → 编辑页保存。保证：保存请求真的发出、回包被应用；
// 旧保存的晚到回包不会写进当前页。卸载 / 换账号后的晚到回包已由
// template-adopt-draft-and-preview-summary-contract.test.js（draft late success after unloading / account changes）覆盖，
// 返回详情重读由 templatedetail-truthful-actions-contract.test.js 覆盖。这里补「保存在途时不再发第二次」——
// 只要第二次发不出去，就不存在「旧保存回包覆盖新保存」的窗口。负控：删掉 sendData 的 if (that._submitting) return，第一条必红。
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const TEMP_PAGE = path.resolve(__dirname, '../../pages/publish/temp/index.js')
let requests
let appStub

beforeEach(() => {
  requests = []
  appStub = {
    globalData: { nickname: '玩家9004', statusBarHeight: 20, navBarHeight: 44 },
    getUserRole: () => 'player', getUserType: () => 1, getUserID: () => 9004,
    getAuthorization: () => 'Bearer test', getRequestErrorMessage: (_res, fallback) => fallback,
    sendRequest: (options) => { requests.push(options); return { abort() {} } },
    chooseImage: () => {}, chooseDocument: () => {}, tips: () => {},
  }
  global.getApp = () => appStub
  global.wx = {
    getStorageSync: () => undefined, setStorageSync: () => {}, removeStorageSync: () => {},
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    showToast: () => {}, hideLoading: () => {}, showLoading: () => {}, nextTick: (cb) => cb(),
    navigateTo() {}, redirectTo() {}, navigateBack() {}, reLaunch() {},
    createSelectorQuery: () => ({ in: () => ({ select: () => ({ boundingClientRect: () => ({ exec() {} }) }), exec() {} }) }),
  }
})

function loadPage() {
  let config
  global.Page = (value) => { config = value }
  delete require.cache[TEMP_PAGE]
  require(TEMP_PAGE)
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
const drafts = () => requests.filter(r => r.url === '/api/template/draft')

test('V6b CX-11：保存在途时再点保存不发第二个请求，回包后才能再存', () => {
  const page = loadPage()
  page.sendData({ id: 6029, status: 0, title: '第一次' }, '/api/template/draft', 'draft')
  page.sendData({ id: 6029, status: 0, title: '第二次' }, '/api/template/draft', 'draft')
  assert.equal(drafts().length, 1, '在途时又发了一次保存，旧回包可能覆盖新保存')
  assert.equal(JSON.parse(drafts()[0].data).title, '第一次')
  assert.equal(page.data.submitting, true)
  drafts()[0].success({ code: 200, data: 6029 })
  assert.equal(page.data.submitting, false)
  page.sendData({ id: 6029, status: 0, title: '等归属刷新' }, '/api/template/draft', 'draft')
  assert.equal(drafts().length, 1, '新草稿归属尚未确认时不能继续写')
  const confirmation = requests.find(r => r.url === '/api/template/author-myinfo')
  confirmation.success({ code: 200, data: { id: 6029, title: '第一次', canEdit: true } })
  page.sendData({ id: 6029, status: 0, title: '第三次' }, '/api/template/draft', 'draft')
  assert.equal(drafts().length, 2)
  assert.equal(JSON.parse(drafts()[1].data).title, '第三次')
})

test('V6b CX-11：保存成功的回包被应用到页面（草稿 id、结果面板、脏标）', () => {
  const page = loadPage()
  page.data.dirty = true
  page.sendData({ id: 6029, status: 0, title: '我的草稿' }, '/api/template/draft', 'draft')
  drafts()[0].success({ code: 200, data: { id: 6029 } })
  assert.equal(page._templateId, 6029)
  assert.equal(page.data.dirty, false)
  assert.equal(page.data.resultSheet.show, true)
  assert.equal(page.data.resultSheet.title, '模板草稿已保存')
  assert.equal(page.data.submitError, '')
})

test('V6b CX-11：保存失败保留内容并给出错误，之后可以重存', () => {
  const page = loadPage()
  page.sendData({ id: 6029, status: 0, title: '我的草稿' }, '/api/template/draft', 'draft')
  drafts()[0].fail({ errMsg: 'timeout' })
  assert.equal(page.data.submitting, false)
  assert.ok(page.data.submitError)
  page.sendData({ id: 6029, status: 0, title: '我的草稿' }, '/api/template/draft', 'draft')
  assert.equal(drafts().length, 2)
})
