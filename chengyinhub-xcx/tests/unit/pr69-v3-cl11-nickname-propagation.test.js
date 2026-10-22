'use strict'
// PR #69 V3 · CL-11(F-W5-05):在个人资料页改昵称并保存后，回到俱乐部发帖入口立即显示新昵称。
// 成长中心 onShow 重读由 growthcenter-partial-state.test.js「资料页改昵称返回成长中心时立即同步…」覆盖;
// 资料页只回写真正提交的昵称由 w5-club-topic-ui-tail.test.js「F-W5-05 资料保存…」覆盖。
// 这里串起同一份会话:资料页保存 → 会话昵称 → 俱乐部详情 onShow 重读 → 发帖入口 WXML 绑定。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const session = { owner: 7, nickname: '旧昵称', avatar: 'a.png' }
const requests = []
const app = {
  globalData: {}, getUserID: () => session.owner, getAuthorization: () => 'token',
  getNickname: () => session.nickname, getAvatar: () => session.avatar,
  setNickname(value) { session.nickname = value }, setAvatar(value) { session.avatar = value },
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

function load(file) {
  let definition
  global.Page = value => { definition = value }
  const absolute = path.join(ROOT, file)
  delete require.cache[require.resolve(absolute)]
  require(absolute)
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) })
  page.setData = function (patch, done) { Object.assign(this.data, patch); if (done) done() }
  return page
}

function composerName(data) {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/club/detail/index.wxml'), 'utf8')
  const binding = wxml.match(/<text class="post-composer-who">\{\{(\w+) \|\| '([^']+)'\}\}<\/text>/)
  assert.ok(binding, '发帖入口第一行必须绑定会话昵称')
  return data[binding[1]] || binding[2]
}

test('资料页保存新昵称后返回俱乐部详情，发帖入口立即显示新昵称', () => {
  const club = load('pages/club/detail/index.js')
  club.refreshCoopPublisherAccess = () => {}
  club.onShow()
  assert.equal(composerName(club.data), '旧昵称')

  const profile = load('pages/gerenziliao/gerenziliao.js')
  profile._loadedProfileName = '旧昵称'
  profile.data.userInfo = Object.assign({}, profile.data.userInfo, { name: '新昵称', avatar: 'a.png' })
  profile.data.canSave = true
  profile.data.selectedCategoryIds = []
  profile.currentProfileContext = () => true
  profile.applyProfileResult = (context, apply) => apply()
  profile.scheduleProfileReturn = () => {}
  profile.saveInfo()
  const save = requests.find(r => r.url === '/api/user/update')
  assert.ok(save)
  assert.equal(JSON.parse(save.data).name, '新昵称')
  save.success({ code: '200' })
  assert.equal(session.nickname, '新昵称')

  club.onShow()
  assert.equal(club.data.myNickname, '新昵称')
  assert.equal(composerName(club.data), '新昵称')
})

test('保存失败不改会话昵称，返回俱乐部仍显示原昵称', () => {
  session.nickname = '原昵称'
  requests.length = 0
  const club = load('pages/club/detail/index.js')
  club.refreshCoopPublisherAccess = () => {}
  club.onShow()
  const profile = load('pages/gerenziliao/gerenziliao.js')
  profile._loadedProfileName = '原昵称'
  profile.data.userInfo = Object.assign({}, profile.data.userInfo, { name: '没保存的昵称' })
  profile.data.canSave = true
  profile.data.selectedCategoryIds = []
  profile.applyProfileResult = (context, apply) => apply()
  profile.saveInfo()
  requests.find(r => r.url === '/api/user/update').success({ code: '409', msg: '昵称已被占用' })
  club.onShow()
  assert.equal(composerName(club.data), '原昵称')
})
