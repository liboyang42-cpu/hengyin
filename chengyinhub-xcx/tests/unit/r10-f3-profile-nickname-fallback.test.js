'use strict'
// 第十轮 f3 j5 H5:只有微信昵称(name 为空)的玩家,资料页昵称行与头部不能写「未填写」/「城瘾玩家」,
// 而要显示各处都在用的 nickname;不改昵称直接保存时不提交 name(不触发昵称占用检查)。
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const requests = []
const app = {
  globalData: {}, getUserID: () => 7, getAuthorization: () => 'token', getImgUrl: (v) => v,
  getNickname: () => '玩家小城', getAvatar: () => '', setNickname() {}, setAvatar() {},
  sendRequest(options) { requests.push(options); return { abort() {} } }, tips() {},
}
global.getApp = () => app
global.getCurrentPages = () => [{}, {}]
global.wx = {
  getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {}, showToast() {}, hideToast() {},
  showLoading() {}, hideLoading() {}, navigateBack() {},
  getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
  getWindowInfo: () => ({ windowWidth: 375, statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ top: 24, bottom: 56, height: 32 }),
}

function load() {
  let definition
  global.Page = value => { definition = value }
  const absolute = path.join(ROOT, 'pages/gerenziliao/gerenziliao.js')
  delete require.cache[require.resolve(absolute)]
  require(absolute)
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) })
  page.setData = function (patch, done) { Object.assign(this.data, patch); if (done) done() }
  page.profileContext = () => ({})
  page.currentProfileContext = () => true
  page.loadProfileReview = () => {}
  return page
}

test('name 为空时资料页显示 nickname,不改就不提交 name', () => {
  requests.length = 0
  const page = load()
  page.getUserData()
  const info = requests.find(r => r.url === '/api/user/info')
  assert.ok(info)
  info.success({ code: '200', data: { id: 7, name: null, nickname: '玩家小城', avatar: '' } })
  assert.equal(page.data.userInfo.name, '玩家小城')
  assert.equal(page.data.canSave, true)

  page.data.selectedCategoryIds = []
  page.applyProfileResult = (context, apply) => apply()
  page.scheduleProfileReturn = () => {}
  page.saveInfo()
  const save = requests.find(r => r.url === '/api/user/update')
  assert.ok(save)
  assert.equal(JSON.parse(save.data).name, undefined)
})

test('已存过的 name 仍以 name 为准', () => {
  requests.length = 0
  const page = load()
  page.getUserData()
  requests.find(r => r.url === '/api/user/info').success({ code: '200', data: { id: 7, name: '小城', nickname: '玩家小城' } })
  assert.equal(page.data.userInfo.name, '小城')
})
