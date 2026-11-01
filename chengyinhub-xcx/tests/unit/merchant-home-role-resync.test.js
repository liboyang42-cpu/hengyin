// 2026-11-01 VPS 测试服实测:商家首页 /api/user/info 回来的角色和本地一样也回写,
// app.setUserRole 会推进会话版本,把同屏还在路上的经营数据 / 消息 / 待办 / 项目请求
// 全部作废成「登录状态已变更，请重试」,首页出现「收入没更新成功」。
// 契约:角色没变不回写;角色真变了照常回写(会话版本照常推进,见 registration-seat-session-revision)。
const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const PAGE_PATH = path.resolve(__dirname, '../../pages/merchant/index/index.js')

let pageConfig
let requests
let roleWrites
let currentRole

global.getApp = () => ({
  globalData: { statusBarHeight: 44, navBarHeight: 44 },
  getUserID: () => 9,
  getAuthorization: () => 'token',
  getUserRole: () => currentRole,
  setUserRole(role) { roleWrites.push(role); currentRole = role },
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  sendRequest: (request) => { requests.push(request) },
})
global.wx = {
  getStorageSync: () => '',
  setStorageSync() {},
  getWindowInfo: () => ({ statusBarHeight: 44 }),
  getSystemInfoSync: () => ({ statusBarHeight: 44 }),
  getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
}
global.Page = (config) => { pageConfig = config }

function loadPage() {
  delete require.cache[require.resolve(PAGE_PATH)]
  require(PAGE_PATH)
  const page = Object.assign({}, pageConfig)
  page.data = JSON.parse(JSON.stringify(pageConfig.data))
  page.setData = function (patch) { Object.assign(this.data, patch) }
  page.isCurrentDataEpoch = () => true
  page.refreshProfileHeader = () => {}
  return page
}

function answerUserInfo(page, role) {
  page.loadUserInfo(0)
  const request = requests.find((r) => r.url === '/api/user/info')
  assert.ok(request, '首页要拉 /api/user/info')
  request.success({ code: 200, data: { role } })
}

beforeEach(() => { requests = []; roleWrites = [] })

test('角色没变不回写,不推进会话版本、不作废同屏在途请求', () => {
  currentRole = 'merchant'
  answerUserInfo(loadPage(), 'merchant')
  assert.deepEqual(roleWrites, [])
})

test('角色真变了照常回写', () => {
  currentRole = 'player'
  answerUserInfo(loadPage(), 'merchant')
  assert.deepEqual(roleWrites, ['merchant'])
})
