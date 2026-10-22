'use strict'

// PRIVATE-GROUP-activity-collaborators / #334 F6:
// 编辑活动时合作者要从服务端 collaboratorsList 回填;发起人信息(getUserData)回来时只把发起人
// 并到首位,不能把已回填的合作者覆盖掉 —— 两个请求谁先回都一样。合作者只是展示署名。

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')

function loadPage() {
  let pageConfig = null
  const requests = []
  global.getApp = () => ({
    globalData: { statusBarHeight: 20, navBarHeight: 44, userInfo: {} },
    getUserID: () => 88,
    getPageSize: () => 10,
    sendRequest(row) { requests.push(row); return { abort() {} } },
    tips() {},
    setUserRole() {},
  })
  global.Page = (config) => { pageConfig = config }
  global.getCurrentPages = () => [{}]
  global.wx = {
    showLoading() {}, hideLoading() {}, showToast() {}, showModal() {},
    navigateTo() {}, navigateBack() {}, switchTab() {}, redirectTo() {},
    stopPullDownRefresh() {}, setNavigationBarTitle() {},
    getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
    getSystemInfoSync: () => ({ statusBarHeight: 20, windowWidth: 375, windowHeight: 667, screenHeight: 667, pixelRatio: 2 }),
    getWindowInfo: () => ({ statusBarHeight: 20, windowWidth: 375, windowHeight: 667, screenHeight: 667, pixelRatio: 2 }),
    getMenuButtonBoundingClientRect: () => ({ top: 24, right: 363, width: 87, height: 32 }),
    createSelectorQuery: () => ({
      in: () => ({ select: () => ({ fields: () => ({ exec() {} }), boundingClientRect: () => ({ exec() {} }) }) }),
      select: () => ({ boundingClientRect: () => ({ exec() {} }) }),
      exec() {},
    }),
  }
  const absolute = path.resolve(ROOT, 'pages/publish/activity/index.js')
  delete require.cache[require.resolve(absolute)]
  require(absolute)
  const page = Object.assign({}, pageConfig)
  page.data = JSON.parse(JSON.stringify(pageConfig.data))
  page.setData = (patch, callback) => {
    Object.entries(patch).forEach(([key, value]) => {
      const parts = key.split('.')
      let cursor = page.data
      parts.slice(0, -1).forEach((part) => {
        if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {}
        cursor = cursor[part]
      })
      cursor[parts[parts.length - 1]] = value
    })
    if (callback) callback()
  }
  page.refreshPrimaryActionState = () => {}
  page.resumeAtFirstIncompleteStep = () => {}
  return { page, requests }
}

const detail = {
  id: 9, memberId: 88, name: '署名测试',
  collaboratorsList: [
    { id: 1, memberId: 88, memberNickname: '发起人旧名', memberAvatar: 'a88' },
    { id: 2, memberId: 21, memberNickname: '小林', memberAvatar: 'a21' },
    { id: 3, memberId: 22, memberNickname: '', memberRealName: '', memberAvatar: '' },
    { id: 4, memberId: 21, memberNickname: '小林(重复行)' },
  ],
}
const ownerReply = { code: '200', data: { id: 88, nickname: '发起人', avatar: 'owner.png' } }

function userInfoRequest(requests) {
  const row = requests.find((item) => item.url === '/api/user/info')
  assert.ok(row, 'getUserData 应请求 /api/user/info')
  return row
}

test('edit prefill keeps server collaborators when owner info arrives later', () => {
  const { page, requests } = loadPage()
  page.applyExistingActivity(detail)
  assert.deepEqual(page.data.formData.collaborators, [88, 21, 22])
  page.getUserData()
  userInfoRequest(requests).success(ownerReply)
  assert.deepEqual(page.data.formData.collaborators, [88, 21, 22], '发起人回包不得覆盖已回填的合作者')
  const list = page.data.formData.collaboratorList
  assert.equal(list[0].nickname, '发起人')
  assert.equal(list[0].isOwner, 1)
  assert.equal(list[1].nickname, '小林')
  assert.equal(list[1].isOwner, 0)
  assert.equal(list[2].nickname, '城瘾用户', '缺昵称时用中性占位')
})

test('edit prefill keeps owner first when owner info arrives before the activity', () => {
  const { page, requests } = loadPage()
  page.getUserData()
  userInfoRequest(requests).success(ownerReply)
  assert.deepEqual(page.data.formData.collaborators, [88])
  page.applyExistingActivity(detail)
  assert.deepEqual(page.data.formData.collaborators, [88, 21, 22])
  assert.equal(page.data.formData.collaboratorList[0].nickname, '发起人')
})

test('removing a prefilled collaborator is sent as the replacement list', () => {
  const { page } = loadPage()
  page.applyExistingActivity(detail)
  page.removeCollaborator({ currentTarget: { dataset: { index: 1 } } })
  assert.deepEqual(page.data.formData.collaborators, [88, 22])
})

test('activity without collaboratorsList prefills nothing and new activity keeps owner only', () => {
  const { page, requests } = loadPage()
  page.applyExistingActivity({ id: 9, memberId: 88, name: 'x' })
  assert.deepEqual(page.data.formData.collaborators, [])
  page.getUserData()
  userInfoRequest(requests).success(ownerReply)
  assert.deepEqual(page.data.formData.collaborators, [88])
})

test('collaborator section is labelled as display-only credits', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/publish/activity/index.wxml'), 'utf8')
  assert.match(wxml, /合作者仅作活动页署名展示，不获得编辑或管理权限/)
})
