// 核销详情读取失败的分流:403 = 岗位没权限;401/重登失败 = 登录失效;只有真正的传输失败才说「网络不稳定」。
// 原来 fail() 不收状态码,三种情况一律显示「网络不稳定」,登录失效的人会一直点重试。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.join(__dirname, '../..')
const PAGE = 'pages/club/checkin-detail/index.js'

function mount() {
  const requests = []
  let definition
  const app = { globalData: { user_id: 7, role: 'club', user_type: 1 }, sendRequest(o) { requests.push(o) } }
  const sandbox = {
    getApp: () => app,
    Page(config) { definition = config },
    require(request) {
      if (request === '../utils/owner-action-guard.js') return require(path.join(ROOT, 'pages/club/utils/owner-action-guard.js'))
      if (request === '../../../utils/cancellation-feedback.js' || request === '../../../utils/response-shape.js') {
        return require(path.join(ROOT, 'utils', path.basename(request)))
      }
      if (request === '../../../utils/toast.js') return { success() {}, error() {} }
      if (request === '../../../utils/modal.js') return { show() {} }
      throw new Error('unexpected require: ' + request)
    },
    wx: { navigateBack() {}, switchTab() {}, stopPullDownRefresh() {} },
    console,
  }
  global.getApp = sandbox.getApp
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, PAGE), 'utf8'), sandbox, { filename: PAGE })
  const page = Object.assign({
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch) },
  }, definition)
  page.onLoad({ clubId: '1', registrationId: '9' })
  const detailRequest = requests.find((r) => r.url === '/api/club/crm/checkin/detail')
  assert.ok(detailRequest, 'onLoad 必须读取核销详情')
  return { page, detailRequest }
}

const SNAPSHOT = { registrationId: 9, displayName: '陈屿', phoneText: '138****0000' }

test('HTTP 403 ⇒ denied(没权限),并清掉上一次渲染的客户信息', () => {
  const { page, detailRequest } = mount()
  page.setData({ state: 'ready', detail: SNAPSHOT })
  detailRequest.fail({ code: 403, msg: '' }, 403)
  assert.equal(page.data.state, 'denied')
  assert.equal(page.data.detail, null)
  assert.ok(!/网络/.test(page.data.errorText))
})

test('重登失败(HTTP 401 / 业务码 401 / 历史码 2)⇒ 提示登录失效,不是网络不稳定,也不是没权限', () => {
  for (const [value, status] of [[{ code: 401, msg: '' }, 401], [{ code: 401 }, 401], [{ code: 2 }, 2]]) {
    const { page, detailRequest } = mount()
    page.setData({ state: 'ready', detail: SNAPSHOT })
    detailRequest.fail(value, status)
    assert.equal(page.data.state, 'error')
    assert.equal(page.data.detail, null, '身份已失效,旧快照不能留在屏幕上')
    assert.match(page.data.errorText, /登录已过期/)
  }
})

test('negative control:真正的传输失败(无状态码)与 5xx 仍是「网络不稳定」', () => {
  for (const args of [[{ errMsg: 'request:fail timeout' }], [{ code: 500, msg: '' }, 500]]) {
    const { page, detailRequest } = mount()
    detailRequest.fail(...args)
    assert.equal(page.data.state, 'error')
    assert.match(page.data.errorText, /网络不稳定/)
  }
})
